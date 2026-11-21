import glob
import json
import os
import queue
import re
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile
from typing import Any

import pandas as pd
import pydot
import uvicorn
from fastapi import FastAPI, Request, Body, HTTPException
from fastapi import File, UploadFile
from starlette.background import BackgroundTask
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
import shutil


app = FastAPI()
origins = [
    "*",
]

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

dbtest_path = "DBTest-1.0-SNAPSHOT-shaded.jar"
config_path = "config.properties"
result_path = "result"
current_path = "current"
current_log_path = os.path.join(current_path, "output.log")


class Bug:
    """
    Initializes the object with the given parameters.
    Args:
        bug_id (int): The ID of the bug.
        db_type (str): The type of the database.
        db_isolation (str): The isolation level of the database.
        checker_isolation (str): The isolation level of the checker.
        timestamp (int): The timestamp of the bug.
        bug_dir (str): The directory of the bug.
        config_path (str): The path of the config file.
        metadata_path (str): The path of the metadata file.
        log_path (str): The path of the log file.
    Returns:
        None
    """

    def __init__(self, bug_id, db_type, db_isolation, checker_isolation, timestamp, bug_dir, config_path,
                 metadata_path, log_path, tag_name, tag_type):
        self.bug_id = bug_id
        self.db_type = db_type
        self.db_isolation = db_isolation
        self.checker_isolation = checker_isolation
        self.timestamp = timestamp
        self.bug_dir = bug_dir
        self.dot_path = os.path.join(bug_dir, "conflict.dot")
        self.config_path = config_path
        self.metadata_path = metadata_path
        self.log_path = log_path
        self.tag_name = tag_name
        self.tag_type = tag_type

    def zip(self, filename):
        file_paths = [self.config_path, self.metadata_path, self.log_path]
        for file in os.listdir(self.bug_dir):
            file_paths.append(os.path.join(self.bug_dir, file))
        with zipfile.ZipFile(filename, "w") as zip_file:
            for file_path in file_paths:
                file_name = os.path.basename(file_path)
                zip_file.write(file_path, arcname=file_name)


REQUIRED_METADATA_FIELDS = {
    "db_type", "db_isolation", "checker_isolation", "timestamp", "history_count", "bug_count"
}


def load_run_metadata(metadata_path):
    if not os.path.isfile(metadata_path):
        return None
    try:
        with open(metadata_path, encoding="utf-8") as f:
            metadata = json.load(f)
    except (OSError, json.JSONDecodeError) as error:
        print(f"Skip invalid run metadata {metadata_path}: {error}")
        return None
    if not isinstance(metadata, dict):
        print(f"Skip invalid run metadata {metadata_path}: expected a JSON object")
        return None
    missing_fields = REQUIRED_METADATA_FIELDS - metadata.keys()
    if missing_fields:
        print(f"Skip incomplete run metadata {metadata_path}: missing {sorted(missing_fields)}")
        return None
    return metadata


class BugStore:
    def __init__(self, directory):
        if not os.path.exists(directory):
            os.makedirs(directory)
        self.directory = directory
        self.bug_count = 0
        self.hist_count = 0
        self.bug_map = {}

    def scan(self):
        bug_list = []
        bug_count = 0
        hist_count = 0
        for run_dir in os.listdir(self.directory):
            if not run_dir.startswith("run_"):
                continue
            metadata_path = os.path.join(self.directory, run_dir, "metadata.json")
            config_path = os.path.join(self.directory, run_dir, "config.properties")
            log_path = os.path.join(self.directory, run_dir, "output.log")
            run_meta = load_run_metadata(metadata_path)
            if run_meta is None:
                continue
            bug_count += run_meta["bug_count"]
            hist_count += run_meta["history_count"]
            for bug_dir in os.listdir(os.path.join(self.directory, run_dir)):
                if not bug_dir.startswith("bug_"):
                    continue
                tag_name = run_meta.get("tag_name", "")
                tag_type = run_meta.get("tag_type", "")
                bug_list.append(Bug(0, run_meta["db_type"], run_meta["db_isolation"],
                                    run_meta["checker_isolation"], run_meta["timestamp"],
                                    os.path.join(self.directory, run_dir, bug_dir),
                                    config_path, metadata_path, log_path, tag_name, tag_type))
        bug_list.sort(key=lambda bug: bug.timestamp)
        for index, bug in enumerate(bug_list):
            bug.bug_id = index + 1
        self.bug_count = bug_count
        self.hist_count = hist_count
        self.bug_map = {bug.bug_id: bug for bug in bug_list}

    def get(self, bug_id):
        return self.bug_map[bug_id]


class Run:
    def __init__(self, run_id, db_type, db_isolation, checker_isolation, timestamp, hist_count, bug_count,
                 dir_path, status="Finished", percentage=100, error=''):
        self.run_id = run_id
        self.db_type = db_type
        self.db_isolation = db_isolation
        self.checker_isolation = checker_isolation
        self.timestamp = timestamp
        self.hist_count = hist_count
        self.bug_count = bug_count
        self.dir_path = dir_path
        self.status = status
        self.percentage = percentage
        self.error = error
        self.profile_path_list = glob.glob(os.path.join(dir_path, "*-profile.csv"))
        runtime_info_path = os.path.join(dir_path, "runtime_info.csv")
        if os.path.exists(runtime_info_path):
            self.runtime_info_path = runtime_info_path
        else:
            self.runtime_info_path = None
        runtime_stage_path = os.path.join(dir_path, "runtime_stage.csv")
        if os.path.exists(runtime_stage_path):
            self.runtime_stage_path = runtime_stage_path
        else:
            self.runtime_stage_path = None

    def zip(self, filename):
        with zipfile.ZipFile(filename, 'w', zipfile.ZIP_DEFLATED) as zipf:
            for root, dirs, files in os.walk(self.dir_path):
                for file in files:
                    file_path = os.path.join(root, file)
                    zipf.write(file_path, os.path.relpath(file_path, self.dir_path))


class RunStore:
    def __init__(self, directory):
        if not os.path.exists(directory):
            os.makedirs(directory)
        self.directory = directory
        self.run_count = 0
        self.run_map = {}

    def scan(self):
        run_list = []
        for run_dir in os.listdir(self.directory):
            if not run_dir.startswith("run_"):
                continue
            metadata_path = os.path.join(self.directory, run_dir, "metadata.json")
            run_meta = load_run_metadata(metadata_path)
            if run_meta is None:
                continue
            run_list.append(Run(0, run_meta["db_type"], run_meta["db_isolation"],
                                run_meta["checker_isolation"], run_meta["timestamp"],
                                run_meta["history_count"], run_meta["bug_count"],
                                os.path.join(self.directory, run_dir),
                                status=run_meta.get("status", "Finished"),
                                error=run_meta.get("error", "")))
        run_list.sort(key=lambda run: run.timestamp)
        for index, run in enumerate(run_list):
            run.run_id = index + 1
        self.run_count = len(run_list)
        self.run_map = {run.run_id: run for run in run_list}

    def get(self, run_id):
        if run_id in self.run_map:
            return self.run_map[run_id]
        return None


bug_store = BugStore(result_path)
bug_store.scan()
run_store = RunStore(result_path)
run_store.scan()


@app.get("/history_count")
async def get_history_count():
    return bug_store.hist_count


@app.get("/bug_count")
async def get_bug_count():
    return bug_store.bug_count


current_run = ''
run_queue = queue.Queue()
process = None
stop_requested = False


@app.post("/run")
def run_task(params: Any = Body(None)):
    if not isinstance(params, dict) or not params:
        raise HTTPException(status_code=400, detail="A non-empty configuration object is required")
    config = ''
    for key, value in params.items():
        option = key.replace('_', '.')
        config += f'{option}={value}\n'
    run_queue.put(config)

    return {}


def without_database_password(config):
    return ''.join(line for line in config.splitlines(keepends=True)
                   if not line.startswith('db.password='))


def record_failed_run(config, error, status="Failed"):
    failed_run = config_to_run(config)
    run_dir = os.path.join(result_path, f"run_{uuid.uuid4()}")
    os.makedirs(run_dir)
    with open(os.path.join(run_dir, "config.properties"), 'w', encoding="utf-8") as file:
        file.write(without_database_password(config))
    log_path = os.path.join(run_dir, "output.log")
    if os.path.isfile(current_log_path):
        shutil.copyfile(current_log_path, log_path)
    with open(log_path, 'a', encoding="utf-8") as file:
        file.write(f"\n{status}: {error}\n")
    metadata = {
        "db_type": failed_run.db_type,
        "db_isolation": failed_run.db_isolation,
        "checker_isolation": failed_run.checker_isolation,
        "timestamp": failed_run.timestamp,
        "history_count": 0,
        "bug_count": 0,
        "status": status,
        "error": str(error),
    }
    with open(os.path.join(run_dir, "metadata.json"), 'w', encoding="utf-8") as file:
        json.dump(metadata, file)


def execute_run(config):
    global current_run, process, stop_requested
    current_run = config
    stop_requested = False
    try:
        print("start running")
        with open(config_path, 'w', encoding="utf-8") as file:
            file.write(config)
        process = subprocess.Popen(["java", "-jar", dbtest_path, config_path])
        exit_code = process.wait()
        if exit_code != 0:
            raise RuntimeError(f"Java process exited with code {exit_code}")
    except Exception as e:
        status = "Stopped" if stop_requested else "Failed"
        print(f"Run {status.lower()}: {e}")
        try:
            record_failed_run(config, e, status)
        except Exception as record_error:
            print(f"Failed to record {status.lower()} run: {record_error}")
    finally:
        process = None
        current_run = ''
        if os.path.isdir(current_path):
            shutil.rmtree(current_path, ignore_errors=True)
        try:
            bug_store.scan()
            run_store.scan()
        except Exception as e:
            print(f"Failed to refresh run results: {e}")


def run_worker():
    while True:
        config = run_queue.get()
        try:
            execute_run(config)
        finally:
            run_queue.task_done()


@app.put("/stop")
async def stop_run():
    global process, stop_requested
    if process is None or process.poll() is not None:
        return JSONResponse(status_code=409, content={"message": "No active run"})
    stop_requested = True
    process.terminate()
    return {"message": "Stop requested"}


# start a background thread to run the queue
t = threading.Thread(target=run_worker)
t.daemon = True
t.start()

def config_to_run(config):
    pattern = r'(db\.type|db\.isolation|checker\.isolation|workload\.history)=(.+)\n'
    matches = re.findall(pattern, config)
    result = {}

    for match in matches:
        key = match[0]
        value = match[1]
        result[key] = value

    checker_isolation = result.get('checker.isolation', '[]')
    return Run(0, result.get('db.type', 'UNKNOWN'), result.get('db.isolation', 'UNKNOWN'), checker_isolation,
               int(time.time() * 1000), result.get('workload.history', '0'), 0, '', status='Pending', percentage=0)


def get_current_run_percentage():
    log = current_log()
    if not log:
        return 0
    pattern = r"\d+\s+of\s+\d+"
    match = re.findall(pattern, log)
    if not match:
        return 0
    match = match[-1]
    cur = int(match.split(" ")[0])
    total = int(match.split(" ")[2])
    return (cur - 1) / total * 100


def current_runs():
    result = []
    if current_run:
        result.append(config_to_run(current_run))
        result[0].status = 'Running'
        result[0].percentage = get_current_run_percentage()
    for config in run_queue.queue:
        result.append(config_to_run(config))
    max_run_id = 0
    if run_store.run_map:
        max_run_id = max(run_store.run_map)
    for item in result:
        item.run_id = max_run_id + 1
        max_run_id += 1
    return result


@app.get("/bug_list")
async def get_bug_list():
    return list(bug_store.bug_map.values())


@app.get("/run_list")
async def get_run_list():
    return list(run_store.run_map.values()) + current_runs()


def dot_attr(element, name):
    value = element.get(name)
    return "" if value is None else str(value).replace('"', "")


def dot_relations(element):
    return [item for item in dot_attr(element, "relate_to").split(", ") if item]


@app.get("/view/{bug_id}")
async def view_bug(bug_id: int):
    bug = bug_store.get(bug_id)
    if not os.path.isfile(bug.dot_path):
        return {"name": "Bug", "nodes": [], "edges": [],
                "message": "Conflict graph is not available for this checker; download the archived history instead."}
    graphs = pydot.graph_from_dot_file(bug.dot_path) or []
    if not graphs:
        return {"name": "Bug", "nodes": [], "edges": [], "message": "Conflict graph could not be parsed."}
    graphs[0].del_node('"\\n"')
    nodes = []
    edges = []
    for node in graphs[0].get_nodes():
        nodes.append({
            "id": node.get_name().replace("\"", ""),
            "label": node.get_name().replace("\"", ""),
            "ops": re.sub(r"transaction=Transaction\(id=\d+\), ", "",
                          dot_attr(node, "ops").replace("), Operation", ")\nOperation"))
            .replace("[", "").replace("]", ""),
            "relate_to": dot_relations(node),
            "in_cycle": node.get("in_cycle"),
        })

    for edge in graphs[0].get_edges():
        edges.append({
            "id": dot_attr(edge, "id"),
            "source": edge.get_source().replace("\"", ""),
            "target": edge.get_destination().replace("\"", ""),
            "label": re.sub(r'(\w+)\s+(\d+)', r'\1(k\2)', dot_attr(edge, "label").replace("\\n", " ")),
            "relate_to": dot_relations(edge),
            "style": edge.get("style"),
            "in_cycle": edge.get("in_cycle"),
        })

    return {"name": graphs[0].get_name(), "nodes": nodes, "edges": edges}


def zip_download_response(item, filename):
    descriptor, zip_path = tempfile.mkstemp(prefix="isovista-", suffix=".zip")
    os.close(descriptor)
    try:
        item.zip(zip_path)
    except Exception:
        os.remove(zip_path)
        raise
    return FileResponse(zip_path, filename=filename,
                        background=BackgroundTask(lambda: os.remove(zip_path)))


@app.get("/download/{bug_id}")
async def download_bug(bug_id: int):
    return zip_download_response(bug_store.get(bug_id), f"bug-{bug_id}.zip")


@app.get("/download_run/{run_id}")
async def download_run(run_id: int):
    return zip_download_response(run_store.get(run_id), f"run-{run_id}.zip")


@app.get("/download_dot/{bug_id}")
async def download_dot(bug_id: int):
    return FileResponse(bug_store.get(bug_id).dot_path, filename="conflict.dot")


@app.get("/current_log")
def current_log():
    try:
        with open(current_log_path) as log:
            return log.read()
    except FileNotFoundError:
        return ""


@app.post("/bug/tag")
async def change_bug_tag(request: Request):
    data = await request.json()
    bug = bug_store.get(data["bug_id"])
    bug.tag_name = data["tag_name"]
    bug.tag_type = data["tag_type"]
    # save to metadata
    path = bug.metadata_path
    with open(path, "r") as f:
        metadata = json.load(f) 
    metadata["tag_name"] = bug.tag_name
    metadata["tag_type"] = bug.tag_type
    with open(path, "w") as f:
        json.dump(metadata, f)
    


def build_profile_result(profile_paths):
    result = {}
    series = []
    for profile_path in profile_paths:
        df = pd.read_csv(profile_path)
        result = {
            "name": df.iloc[:, 0].name,
            "x_axis": df.iloc[:, 0].to_list(),
            "series": series
        }
        stage_times = df.to_dict('list')
        for i in range(0, 3):
            stage_times.pop(df.iloc[:, i].name)
        series.append({
            "checker": os.path.basename(profile_path).split("-")[0],
            "time": df.iloc[:, 1].to_list(),
            "memory": df.iloc[:, 2].to_list(),
            "stage_times": stage_times
        })
    return result


@app.get("/run_profile/{run_id}")
async def get_profile(run_id: int):
    run = run_store.get(run_id)
    if run is None or not run.profile_path_list:
        return None
    return build_profile_result(run.profile_path_list)


@app.get("/runtime_info/{run_id}")
async def get_runtime_info(run_id: int):
    run = run_store.get(run_id)
    if run is None or run.runtime_info_path is None:
        return None
    df = pd.read_csv(run.runtime_info_path)
    return {
        "x_axis": df.iloc[:, 0].to_list(),
        "cpu": df.iloc[:, 1].to_list(),
        "memory": df.iloc[:, 2].to_list(),
    }


@app.get("/runtime_stage/{run_id}")
async def get_runtime_stage(run_id: int):
    run = run_store.get(run_id)
    if run is None or run.runtime_stage_path is None:
        return None
    df = pd.read_csv(run.runtime_stage_path)
    dicts = df.to_dict('records')
    result = []
    for i in range(len(dicts)):
        if i == len(dicts) - 1:
            result.append({
                "begin_timestamp": dicts[i]['timestamp'],
                "end_timestamp": 0xffffffff,
                "stage": dicts[i]["stage"]
         })
        else:
            result.append({
                "begin_timestamp": dicts[i]['timestamp'],
                "end_timestamp": dicts[i + 1]['timestamp'],
                "stage": dicts[i]['stage']
            })
    return result


@app.get("/current_runtime_stage")
async def get_current_runtime_stage():
    try:
        with open(os.path.join(current_path, "runtime_stage.csv")) as runtime_stage:
            df = pd.read_csv(runtime_stage)
            dicts = df.to_dict('records')
            result = []
            for i in range(len(dicts)):
                if i == len(dicts) - 1:
                    result.append({
                        "begin_timestamp": dicts[i]['timestamp'],
                        "end_timestamp": 0xffffffff,
                        "stage": dicts[i]["stage"]
                    })
                else:
                    result.append({
                        "begin_timestamp": dicts[i]['timestamp'],
                        "end_timestamp": dicts[i + 1]['timestamp'],
                        "stage": dicts[i]['stage']
                    })
            return result
    except FileNotFoundError:
        return ""


@app.get("/current_run_id")
async def get_current_run_id():
    if current_run:
        if run_store.run_map:
            return max(run_store.run_map) + 1
        else:
            return 1
    else:
        return None


@app.get("/current_runtime_info")
async def get_current_runtime_info():
    try:
        with open(os.path.join(current_path, "runtime_info.csv")) as runtime_info:
            df = pd.read_csv(runtime_info)
            return {
                "x_axis": df.iloc[:, 0].to_list(),
                "cpu": df.iloc[:, 1].to_list(),
                "memory": df.iloc[:, 2].to_list(),
            }
    except FileNotFoundError:
        return ""


@app.get("/current_profile")
async def get_current_profile():
    try:
        return build_profile_result(glob.glob(os.path.join(current_path, "*-profile.csv")))
    except FileNotFoundError:
        return ""


@app.post("/upload/")
async def upload_file(file: UploadFile = File(...)):
    try:
        out_file_path = "user_history.txt"
        with open(out_file_path, "wb") as out_file:
            content = await file.read()
            out_file.write(content)
        return JSONResponse(status_code=200, content={"message": "Upload succeeded!"})
    except Exception as e:
        return JSONResponse(status_code=500, content={"message": "Upload failed!", "details": str(e)})


if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=8000)
