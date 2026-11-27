"""Persist and run ordered BotFury projects without an open browser."""

from contextlib import closing, contextmanager
import json
import re
import sqlite3
from threading import Event, Thread
from time import time


@contextmanager
def open_database(path):
    with closing(sqlite3.connect(path)) as db, db:
        yield db


def project_ids(text):
    steps = [step.strip() for step in text.splitlines() if step.strip()]
    if not 1 <= len(steps) <= 40 or len(set(steps)) != len(steps):
        raise ValueError("Enter one to 40 distinct project IDs, in order.")
    if any(not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", step) for step in steps):
        raise ValueError("Use one BotFury project ID per line.")
    return steps


def normalized_server(address):
    address = address.lower()
    if not re.search(r":\d+$", address):
        address += ":25565"
    return address


def broker_server(address):
    name, _, port = normalized_server(address).rpartition(":")
    if name in ("localhost", "127.0.0.1", "[::1]", "host.containers.internal"):
        name = "local"
    return name + ":" + port


class ProfessionRuns:
    def __init__(self, database, coordinators, get_board, get_hosts, invoke, logger):
        self.database = database
        self.coordinators = {normalized_server(server): port for server, port in coordinators.items()}
        self.get_board = get_board
        self.get_hosts = get_hosts
        self.invoke = invoke
        self.logger = logger
        self.wake = Event()
        with open_database(database) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS runs (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, server TEXT NOT NULL,
                steps TEXT NOT NULL, targets TEXT NOT NULL, step INTEGER NOT NULL,
                status TEXT NOT NULL, detail TEXT NOT NULL, progress TEXT NOT NULL
            )""")
        Thread(target=self.run, name="professions", daemon=True).start()

    def rows(self, running=False):
        with open_database(self.database) as db:
            db.row_factory = sqlite3.Row
            query = "SELECT * FROM runs"
            if running:
                query += " WHERE status='running'"
            return [dict(row) for row in db.execute(query + " ORDER BY id DESC LIMIT 100")]

    def create(self, name, server, steps, targets):
        port = self.coordinators.get(normalized_server(server))
        if port is None:
            raise ValueError("This server has no project coordinator configured.")
        board = self.get_board(port)
        available = {project["id"]: project for project in board or []}
        if any(step not in available for step in steps):
            raise ValueError("Every step needs an existing project on the configured coordinator.")
        projects = [available[step] for step in steps]
        if any(project["world"].split("|")[0] != broker_server(server) for project in projects):
            raise ValueError("Each project must belong to the profession's Minecraft server.")
        if any(project.get("repeat") for project in projects):
            raise ValueError("Repeating stock jobs cannot be used as finite profession steps.")
        with open_database(self.database) as db:
            db.execute("BEGIN IMMEDIATE")
            for stored_server, stored_targets in db.execute("SELECT server, targets FROM runs WHERE status='running'"):
                if normalized_server(stored_server) == normalized_server(server):
                    raise ValueError("A profession is already running on that server.")
                if set(map(tuple, targets)) & set(map(tuple, json.loads(stored_targets))):
                    raise ValueError("One or more slots already belong to an active profession.")
            row = db.execute("INSERT INTO runs VALUES (NULL, ?, ?, ?, ?, 0, 'running', ?, '{}')",
                             (name, server, json.dumps(steps), json.dumps(targets), "Queued"))
            job_id = row.lastrowid
        self.wake.set()
        return job_id

    def save(self, row):
        with open_database(self.database) as db:
            db.execute("UPDATE runs SET step=?, status=?, detail=?, progress=? WHERE id=? AND status='running'",
                       (row["step"], row["status"], row["detail"], json.dumps(row["progress"]), row["id"]))

    def cancel(self, run_id):
        with open_database(self.database) as db:
            cursor = db.execute("UPDATE runs SET status='cancelled', detail='Cancelled' WHERE id=? AND status='running'",
                                (run_id,))
        self.wake.set()
        return cursor.rowcount == 1

    def tick(self, row, lookup):
        row["progress"] = json.loads(row["progress"])
        steps = json.loads(row["steps"])
        project_id = steps[row["step"]]
        broker = self.coordinators.get(normalized_server(row["server"]))
        board = self.get_board(broker) if broker else None
        if board is None:
            row["detail"] = "Waiting for project coordinator"
            return
        project = next((project for project in board if project["id"] == project_id), None)
        if project is None:
            row["detail"] = f"Waiting for project {project_id}"
            return
        states = [job["state"] for job in project["jobs"]]
        if states and all(state == "DONE" for state in states):
            row["step"] += 1
            if row["step"] == len(steps):
                row["status"] = "complete"
                row["detail"] = "All projects completed"
            else:
                row["detail"] = "Next project " + steps[row["step"]]
            return
        if "FAILED" in states:
            row["status"] = "failed"
            row["detail"] = f"{project_id} has a failed project job"
            return
        expected_server, _, expected_dimension = project["world"].partition("|")
        running, waiting = 0, 0
        for host_id, slot in json.loads(row["targets"]):
            bot = lookup.get((host_id, slot))
            if bot is None or normalized_server(row["server"]) not in bot.get("projectServers", []):
                waiting += 1
                continue
            status = bot["status"]
            key = f"{host_id}/{slot}"
            progress = row["progress"].get(key, {})
            if status.get("connected"):
                if (normalized_server(status.get("serverAddress", "")) != normalized_server(row["server"])
                        or status.get("server") != expected_server or status.get("dimension") != expected_dimension):
                    waiting += 1
                    continue
                identity = status.get("runtimeId")
                if identity and progress.get("stage") == project_id and progress.get("runtime") == identity:
                    running += 1
                    continue
                action = "project"
            elif bot["reachable"] and status.get("ready"):
                identity = None
                action = "connect"
            elif not bot["running"] and not bot["reachable"]:
                identity = None
                action = "start"
            else:
                waiting += 1
                continue
            now = time()
            if now < progress.get("retryAt", 0) and progress.get("action") == action and progress.get("project") == project_id:
                waiting += 1
                continue
            reply = self.invoke(host_id, slot, action, row["server"], project_id)
            enrolled = action == "project" and reply.get("ok")
            row["progress"][key] = {"retryAt": now + 15, "action": action, "project": project_id,
                                    "stage": project_id if enrolled else "", "runtime": identity}
            if enrolled:
                running += 1
            else:
                waiting += 1
        row["detail"] = f"{project_id} · {states.count('DONE')}/{len(states)} jobs · {running} assigned"
        if waiting:
            row["detail"] += f" · {waiting} waiting for a worker"

    def run(self):
        while True:
            self.wake.wait(2)
            self.wake.clear()
            try:
                rows = self.rows(running=True)
                if not rows:
                    continue
                lookup = {(host["id"], slot["id"]): slot for host in self.get_hosts()
                          for slot in host["slots"]}
                for row in rows:
                    self.tick(row, lookup)
                    self.save(row)
            except (OSError, sqlite3.Error, TypeError, ValueError, KeyError):
                self.logger.exception("Could not refresh profession progress. Will retry.")
