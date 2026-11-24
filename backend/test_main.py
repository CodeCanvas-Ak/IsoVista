import asyncio
import importlib.util
import json
import os
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

from fastapi import HTTPException


class BackendMainTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original_cwd = os.getcwd()
        cls.import_dir = tempfile.TemporaryDirectory()
        os.chdir(cls.import_dir.name)
        module_path = Path(__file__).with_name("main.py")
        spec = importlib.util.spec_from_file_location("backend_main_under_test", module_path)
        cls.main = importlib.util.module_from_spec(spec)
        try:
            with mock.patch("threading.Thread") as thread:
                spec.loader.exec_module(cls.main)
                thread.return_value.start.assert_called_once_with()
        finally:
            os.chdir(cls.original_cwd)

    @classmethod
    def tearDownClass(cls):
        cls.import_dir.cleanup()

    @staticmethod
    def write_run(root, name="run_1", timestamp=1, bug_count=1, history_count=2):
        run_dir = root / name
        bug_dir = run_dir / "bug_0"
        bug_dir.mkdir(parents=True)
        metadata = {
            "bug_count": bug_count,
            "history_count": history_count,
            "db_type": "MYSQL",
            "db_isolation": "TRANSACTION_SERIALIZATION",
            "checker_isolation": "['SNAPSHOT_ISOLATION']",
            "timestamp": timestamp,
        }
        (run_dir / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
        return run_dir

    def test_bug_store_rescan_replaces_counts_and_index(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = self.write_run(root)
            store = self.main.BugStore(tmp)

            store.scan()
            store.scan()

            self.assertEqual(1, store.bug_count)
            self.assertEqual(2, store.hist_count)
            self.assertEqual(1, len(store.bug_map))

            shutil.rmtree(run_dir)
            store.scan()
            self.assertEqual(0, store.bug_count)
            self.assertEqual(0, store.hist_count)
            self.assertEqual({}, store.bug_map)

    def test_run_store_rescan_does_not_accumulate_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_run(root)
            store = self.main.RunStore(tmp)

            store.scan()
            store.scan()
            self.assertEqual(1, store.run_count)
            self.assertEqual(1, len(store.run_map))

            self.write_run(root, name="run_2", timestamp=2, bug_count=0)
            store.scan()
            self.assertEqual(2, store.run_count)
            self.assertEqual(2, len(store.run_map))

    def test_run_without_profile_returns_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = self.main.Run(1, "MYSQL", "SER", "SI", 1, 1, 0, tmp)
            self.assertEqual([], run.profile_path_list)
            store = SimpleNamespace(get=lambda run_id: run)
            with mock.patch.object(self.main, "run_store", store):
                self.assertIsNone(asyncio.run(self.main.get_profile(1)))

    def test_run_rejects_empty_body(self):
        for params in (None, {}, []):
            with self.subTest(params=params), self.assertRaises(HTTPException) as error:
                self.main.run_task(params)
            self.assertEqual(400, error.exception.status_code)

    def test_stop_without_active_process_returns_conflict(self):
        with mock.patch.object(self.main, "active_runs", {}):
            response = asyncio.run(self.main.stop_run())
        self.assertEqual(409, response.status_code)

    def test_worker_start_failure_records_failed_run_and_resets_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            with mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "workspace_path", str(root / "workspaces")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", self.main.RunStore(str(result_path))), \
                    mock.patch.object(self.main.subprocess, "Popen", side_effect=FileNotFoundError("java")):
                run = self.main.new_run("db.type=MYSQL\ndb.isolation=SER\nchecker.isolation=[]\nworkload.history=1\n")
                self.main.execute_run(run)

            metadata_files = list(result_path.glob("run_*/metadata.json"))
            self.assertEqual(1, len(metadata_files))
            metadata = json.loads(metadata_files[0].read_text(encoding="utf-8"))
            self.assertEqual("Failed", metadata["status"])
            self.assertIn("java", metadata["error"])

        self.assertEqual({}, self.main.active_runs)

    def test_nonzero_java_exit_records_failed_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            process = mock.Mock()
            process.wait.return_value = 7
            with mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "workspace_path", str(root / "workspaces")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", self.main.RunStore(str(result_path))), \
                    mock.patch.object(self.main.subprocess, "Popen", return_value=process):
                self.main.execute_run(self.main.new_run("db.type=MYSQL\ndb.password=secret\n"))

            metadata_path = next(result_path.glob("run_*/metadata.json"))
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual("Failed", metadata["status"])
            self.assertIn("code 7", metadata["error"])
            archived_config = (metadata_path.parent / "config.properties").read_text(encoding="utf-8")
            self.assertNotIn("db.password", archived_config)
            self.assertNotIn("secret", archived_config)

    def test_stop_request_records_stopped_run_and_resets_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            process = mock.Mock()
            process.poll.return_value = None

            run_store = self.main.RunStore(str(result_path))

            def wait_after_stop():
                response = asyncio.run(self.main.stop_run())
                self.assertEqual({"message": "Stop requested", "run_uid": run.run_uid}, response)
                return -15

            process.wait.side_effect = wait_after_stop
            with mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "workspace_path", str(root / "workspaces")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", run_store), \
                    mock.patch.object(self.main.subprocess, "Popen", return_value=process):
                run = self.main.new_run("db.type=MYSQL\ndb.isolation=SER\nchecker.isolation=[]\nworkload.history=1\n")
                self.main.register_run(run)
                self.main.execute_run(run)

            metadata_path = next(result_path.glob("run_*/metadata.json"))
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual("Stopped", metadata["status"])
            self.assertIn("code -15", metadata["error"])
            self.assertEqual("Stopped", run_store.get(1).status)
            process.terminate.assert_called_once_with()
            self.assertEqual({}, self.main.active_runs)

    def test_runs_get_isolated_working_and_result_directories(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            working_directories = []
            configs = []

            def fake_popen(command, cwd=None, **kwargs):
                working_directories.append(cwd)
                configs.append(Path(command[-1]).read_text(encoding="utf-8"))
                process = mock.Mock()
                process.wait.return_value = 0
                return process

            with mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "workspace_path", str(root / "workspaces")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", self.main.RunStore(str(result_path))), \
                    mock.patch.object(self.main.subprocess, "Popen", side_effect=fake_popen):
                first = self.main.new_run("db.type=MYSQL\nworkload.history=2\n")
                second = self.main.new_run("db.type=MYSQL\nworkload.history=3\n")
                self.main.execute_run(first)
                self.main.execute_run(second)

            self.assertNotEqual(working_directories[0], working_directories[1])
            self.assertIn("output.run_id=" + first.run_uid, configs[0])
            self.assertIn("output.run_id=" + second.run_uid, configs[1])
            self.assertEqual(str(result_path / first.run_uid), first.result_dir)
            self.assertNotIn(str(root), configs[0].split("output.path=")[1].splitlines()[0])

    def test_run_list_reports_active_runs(self):
        with tempfile.TemporaryDirectory() as tmp:
            record = self.main.new_run("db.type=MYSQL\nworkload.history=1\n")
            record.work_dir = tmp
            record.status = "Running"
            (Path(tmp) / "current").mkdir()
            (Path(tmp) / "current" / "output.log").write_text("3 of 10", encoding="utf-8")
            self.main.register_run(record)
            try:
                listing = asyncio.run(self.main.get_run_list())
                self.assertEqual(1, len(listing))
                self.assertEqual(record.run_uid, listing[0].run_uid)
                self.assertEqual(20, listing[0].percentage)
            finally:
                self.main.active_runs.clear()

    def test_live_endpoints_follow_the_requested_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            first = self.main.new_run("db.type=MYSQL\nworkload.history=1\n")
            second = self.main.new_run("db.type=MYSQL\nworkload.history=2\n")
            for record, message in ((first, "first log"), (second, "second log")):
                record.work_dir = str(root / record.run_uid)
                record.status = "Running"
                (root / record.run_uid / "current").mkdir(parents=True)
                (root / record.run_uid / "current" / "output.log").write_text(message, encoding="utf-8")
                self.main.register_run(record)
            try:
                self.assertEqual("first log", self.main.current_log(first.run_uid))
                self.assertEqual("second log", self.main.current_log(second.run_uid))
                self.assertEqual("second log", self.main.current_log())
            finally:
                self.main.active_runs.clear()


    def test_config_archiving_sanitizes_embedded_url_credentials(self):
        config = ("db.type=MYSQL\n"
                  "db.url=jdbc:mysql://user:userinfo-secret@localhost:3306/db?user=user&password=query-secret&useSSL=false&pwd=pwd-secret&token=token-secret&passwordPolicy=keep\n"
                  "db.password=property-secret\n")

        archived = self.main.without_database_password(config)

        self.assertIn("db.url=jdbc:mysql://user:<redacted>@localhost:3306/db?user=user&password=<redacted>&useSSL=false&pwd=<redacted>&token=<redacted>&passwordPolicy=keep\n", archived)
        self.assertNotIn("db.password=", archived)
        for secret in ("userinfo-secret", "query-secret", "pwd-secret", "token-secret", "property-secret"):
            self.assertNotIn(secret, archived)

    def test_invalid_metadata_is_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = root / "run_bad"
            run_dir.mkdir()
            (run_dir / "metadata.json").write_text("{broken", encoding="utf-8")

            bug_store = self.main.BugStore(tmp)
            run_store = self.main.RunStore(tmp)
            bug_store.scan()
            run_store.scan()

            self.assertEqual(0, bug_store.hist_count)
            self.assertEqual(0, run_store.run_count)

    def test_view_accepts_dot_without_relate_to(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bug_dir = root / "bug_0"
            bug_dir.mkdir()
            (bug_dir / "conflict.dot").write_text(
                'digraph { "Transaction(id=0)" [ops="[Operation(type=WRITE, key=x)]"]; }',
                encoding="utf-8",
            )
            bug = self.main.Bug(1, "MYSQL", "SER", "SI", 1, str(bug_dir),
                                "config", "metadata", "log", "", "")
            with mock.patch.object(self.main, "bug_store", SimpleNamespace(get=lambda bug_id: bug)):
                result = asyncio.run(self.main.view_bug(1))

            self.assertEqual([], result["nodes"][0]["relate_to"])
            self.assertIn("Operation", result["nodes"][0]["ops"])

    def test_view_reports_missing_conflict_graph(self):
        with tempfile.TemporaryDirectory() as tmp:
            bug = self.main.Bug(1, "MYSQL", "SER", "SI", 1, tmp,
                                "config", "metadata", "log", "", "")
            with mock.patch.object(self.main, "bug_store", SimpleNamespace(get=lambda bug_id: bug)):
                result = asyncio.run(self.main.view_bug(1))
            self.assertEqual([], result["nodes"])
            self.assertIn("not available", result["message"])

    def test_zip_download_uses_unique_file_and_cleans_up(self):
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run_1"
            run_dir.mkdir()
            (run_dir / "config.properties").write_text("db.type=MYSQL\n", encoding="utf-8")
            run = self.main.Run(1, "MYSQL", "SER", "SI", 1, 1, 0, str(run_dir))

            response = self.main.zip_download_response(run, "run-1.zip")
            zip_path = Path(response.path)
            self.assertTrue(zip_path.is_file())
            with zipfile.ZipFile(zip_path) as archive:
                self.assertIn("config.properties", archive.namelist())

            asyncio.run(response.background())
            self.assertFalse(zip_path.exists())

    def test_zip_download_removes_partial_file_when_packing_fails(self):
        item = mock.Mock()
        item.zip.side_effect = RuntimeError("pack failed")
        created_paths = []
        real_mkstemp = tempfile.mkstemp

        def tracked_mkstemp(*args, **kwargs):
            descriptor, path = real_mkstemp(*args, **kwargs)
            created_paths.append(path)
            return descriptor, path

        try:
            with mock.patch.object(self.main.tempfile, "mkstemp", side_effect=tracked_mkstemp):
                with self.assertRaisesRegex(RuntimeError, "pack failed"):
                    self.main.zip_download_response(item, "run-1.zip")

            self.assertEqual(1, len(created_paths))
            self.assertFalse(Path(created_paths[0]).exists())
        finally:
            for path in created_paths:
                Path(path).unlink(missing_ok=True)

    def test_profile_result_preserves_dto(self):
        with tempfile.TemporaryDirectory() as tmp:
            profile_path = Path(tmp) / "PolySI-profile.csv"
            profile_path.write_text("session,time(ms),memory(KB),Construction\n5,100,2048,10\n", encoding="utf-8")

            result = self.main.build_profile_result([str(profile_path)])

            self.assertEqual("session", result["name"])
            self.assertEqual([5], result["x_axis"])
            self.assertEqual("PolySI", result["series"][0]["checker"])
            self.assertEqual({"Construction": [10]}, result["series"][0]["stage_times"])


if __name__ == "__main__":
    unittest.main()
