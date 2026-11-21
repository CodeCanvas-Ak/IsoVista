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
        with mock.patch.object(self.main, "process", None):
            response = asyncio.run(self.main.stop_run())
        self.assertEqual(409, response.status_code)

    def test_worker_start_failure_records_failed_run_and_resets_state(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            current_path = root / "current"
            config_path = root / "config.properties"
            with mock.patch.object(self.main, "config_path", str(config_path)), \
                    mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "current_path", str(current_path)), \
                    mock.patch.object(self.main, "current_log_path", str(current_path / "output.log")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", self.main.RunStore(str(result_path))), \
                    mock.patch.object(self.main.subprocess, "Popen", side_effect=FileNotFoundError("java")):
                self.main.execute_run("db.type=MYSQL\ndb.isolation=SER\nchecker.isolation=[]\nworkload.history=1\n")

            metadata_files = list(result_path.glob("run_*/metadata.json"))
            self.assertEqual(1, len(metadata_files))
            metadata = json.loads(metadata_files[0].read_text(encoding="utf-8"))
            self.assertEqual("Failed", metadata["status"])
            self.assertIn("java", metadata["error"])

        self.assertEqual("", self.main.current_run)
        self.assertIsNone(self.main.process)

    def test_nonzero_java_exit_records_failed_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            result_path = root / "result"
            process = mock.Mock()
            process.wait.return_value = 7
            with mock.patch.object(self.main, "config_path", str(root / "config.properties")), \
                    mock.patch.object(self.main, "result_path", str(result_path)), \
                    mock.patch.object(self.main, "current_path", str(root / "current")), \
                    mock.patch.object(self.main, "current_log_path", str(root / "current" / "output.log")), \
                    mock.patch.object(self.main, "bug_store", self.main.BugStore(str(result_path))), \
                    mock.patch.object(self.main, "run_store", self.main.RunStore(str(result_path))), \
                    mock.patch.object(self.main.subprocess, "Popen", return_value=process):
                self.main.execute_run("db.type=MYSQL\ndb.password=secret\n")

            metadata_path = next((root / "result").glob("run_*/metadata.json"))
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            self.assertEqual("Failed", metadata["status"])
            self.assertIn("code 7", metadata["error"])
            archived_config = (metadata_path.parent / "config.properties").read_text(encoding="utf-8")
            self.assertNotIn("db.password", archived_config)
            self.assertNotIn("secret", archived_config)
            runtime_config = (root / "config.properties").read_text(encoding="utf-8")
            self.assertIn("db.password=secret", runtime_config)

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
