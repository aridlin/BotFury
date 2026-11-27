"""Serve the fleet console or a loopback-only BotFury worker supervisor."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
from time import monotonic
import urllib.parse
import urllib.request

from flask import Flask, abort, jsonify, request, send_from_directory
from professions import ProfessionRuns, normalized_server, open_database, project_ids

MAX_RESPONSE = 12 * 1024 * 1024


class RejectRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), RejectRedirect())
HOST_LABEL = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", re.I | re.ASCII)


def server_address(value):
    if not isinstance(value, str) or not value or len(value) > 255:
        raise ValueError("Enter a Minecraft server address.")
    if value.startswith("["):
        host, close, suffix = value[1:].partition("]")
        if not close or (suffix and not suffix.startswith(":")):
            raise ValueError("Invalid IPv6 address.")
        ipaddress.IPv6Address(host)
        port = suffix[1:] if suffix else "25565"
    else:
        host, colon, port = value.partition(":")
        if not all(HOST_LABEL.fullmatch(label) for label in host.removesuffix(".").split(".")):
            raise ValueError("Invalid server name.")
        if not colon:
            port = "25565"
    if not port.isascii() or not port.isdecimal() or not 1 <= int(port) <= 65535:
        raise ValueError("Invalid Minecraft port.")
    return value


def rpc(port, path, *, token="", data=None, timeout=1.5, as_json=True):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=None if data is None else json.dumps(data).encode(), headers=headers,
    )
    try:
        with HTTP.open(req, timeout=timeout) as reply:
            body = reply.read(MAX_RESPONSE + 1)
            if len(body) > MAX_RESPONSE:
                raise ValueError("Response too large")
        return json.loads(body) if as_json else body.decode()
    except (OSError, ValueError):
        return None


class Agent:
    def __init__(self, config, state):
        self.slots = {slot["id"]: slot for slot in config["slots"]}
        ports = {slot["port"] for slot in self.slots.values()}
        if not 1 <= len(ports) == len(config["slots"]) <= 100:
            raise ValueError("Configure between one and 100 slots with unique IDs and ports.")
        self.project_ports = {normalized_server(server_address(server)): port
                              for server, port in config.get("projectPorts", {}).items()}
        if any(not isinstance(port, int) or not 1024 <= port <= 65535 for port in self.project_ports.values()):
            raise ValueError("Invalid project coordinator port.")
        self.state = state
        self.state.mkdir(parents=True, exist_ok=True)
        self.processes = {}
        self.lock = threading.RLock()
        self.pool = ThreadPoolExecutor(max_workers=16)
        self.refresh_lock = threading.Lock()
        self.snapshot = []
        self.snapshot_until = 0

    def read_slot(self, slot):
        bot = rpc(slot["port"], "/status", timeout=0.4)
        with self.lock:
            process = self.processes.get(slot["id"])
            running = process is not None and process.poll() is None
        status = bot if isinstance(bot, dict) else {}
        status = {k: status[k] for k in (
            "connected", "ready", "player", "server", "serverAddress", "dimension", "x", "y", "z",
            "health", "maxHealth", "alive", "items", "inventorySlots", "taskKind",
            "projectTask", "miningTask", "inventoryTask", "formation", "movementState",
            "chat", "runtimeId", "build", "mapRoute", "bedTask",
        ) if k in status}
        return {"id": slot["id"], "name": slot["name"], "running": running,
                "reachable": isinstance(bot, dict), "status": status, "projectServers": list(self.project_ports)}

    def snapshots(self):
        with self.refresh_lock:
            if monotonic() >= self.snapshot_until:
                self.snapshot = list(self.pool.map(self.read_slot, self.slots.values()))
                self.snapshot_until = monotonic() + 0.8
            return self.snapshot

    def action(self, slot_id, verb, target, project=None, bed=None):
        slot = self.slots.get(slot_id)
        if slot is None:
            return {"ok": False, "message": "Unknown slot."}
        if verb in ("start", "stop"):
            with self.lock:
                process = self.processes.get(slot_id)
                running = process is not None and process.poll() is None
                if verb == "stop":
                    if not running:
                        return {"ok": False, "message": "No process owned by this agent."}
                    terminate_process(process)
                    return {"ok": True, "message": "Stopping."}
                if running or rpc(slot["port"], "/status", timeout=0.25) is not None:
                    return {"ok": False, "message": "Slot is already active."}
                env = os.environ.copy()
                if slot.get("authFile"):
                    env["BOTFURY_SERVER_AUTH_FILE"] = slot["authFile"]
                try:
                    with (self.state / f"{slot_id}.log").open("ab") as log:
                        self.processes[slot_id] = subprocess.Popen(
                            slot["argv"], cwd=slot.get("cwd"), env=env, stdin=subprocess.DEVNULL,
                            stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                        )
                except OSError as error:
                    return {"ok": False, "message": f"Launch failed ({error.strerror})."}
                return {"ok": True, "message": "Starting."}
        if verb == "connect":
            command = "connect:" + server_address(target)
        elif verb == "halt":
            command = "control:stop"
        elif verb == "bed":
            dimensions = bed.get("dimension") if isinstance(bed, dict) else None
            coords = bed.get("coords") if isinstance(bed, dict) else None
            if (not isinstance(dimensions, str) or not isinstance(coords, list) or len(coords) != 3
                    or any(type(axis) is not int or abs(axis) > 30_000_000 for axis in coords)
                    or not -2032 <= coords[1] <= 2031):
                return {"ok": False, "message": "Enter integer bed coordinates."}
            status = self.read_slot(slot)["status"]
            if (not status.get("connected") or status.get("dimension") != dimensions
                    or normalized_server(status.get("serverAddress", "")) != normalized_server(server_address(target))):
                return {"ok": False, "message": "Bot is not in the selected world."}
            command = "bed:spawn " + " ".join(map(str, coords))
        elif verb == "project":
            port = self.project_ports.get(normalized_server(server_address(target)))
            if port is None or not isinstance(project, str) or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", project):
                return {"ok": False, "message": "No matching project coordinator."}
            status = self.read_slot(slot)["status"]
            if not status.get("connected") or normalized_server(status.get("serverAddress", "")) != normalized_server(target):
                return {"ok": False, "message": "Worker is not connected to the profession's Minecraft server."}
            command = f"project:worker {port} {project}"
        else:
            return {"ok": False, "message": "Unknown action."}
        reply = rpc(slot["port"], "/command?" + urllib.parse.urlencode({"cmd": command}),
                    timeout=2, as_json=False)
        return {"ok": reply is not None, "message": reply or "Bot rejected the request or is unreachable."}

    def shutdown(self):
        with self.lock:
            for process in self.processes.values():
                if process.poll() is None:
                    terminate_process(process)


def terminate_process(process):
    try:
        if os.name == "posix":
            os.killpg(process.pid, signal.SIGTERM)
        else:
            process.terminate()
    except ProcessLookupError:
        pass


def make_app(config, state):
    app = Flask(__name__, static_folder=None)
    app.config["MAX_CONTENT_LENGTH"] = 48 * 1024
    role = config.get("role", "hub")

    @app.after_request
    def response_headers(response):
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
        )
        return response

    @app.errorhandler(400)
    @app.errorhandler(403)
    @app.errorhandler(404)
    def api_error(error):
        return jsonify(error=error.description), error.code

    if role == "agent":
        token = config["token"]
        if not isinstance(token, str) or len(token) < 32:
            raise ValueError("Use an agent token of at least 32 characters.")
        for slot in config["slots"]:
            if not re.fullmatch(r"[a-zA-Z0-9_-]{1,32}", slot["id"]) or not 1024 <= slot["port"] <= 65535:
                raise ValueError("Invalid slot ID or port.")
            if not isinstance(slot.get("argv"), list) or not slot["argv"]:
                raise ValueError("A slot needs an argv array.")
        agent = Agent(config, state)
        app.extensions["fleet_agent"] = agent

        @app.before_request
        def agent_auth():
            if not hmac.compare_digest(request.headers.get("Authorization", "").encode(), ("Bearer " + token).encode()):
                abort(403, "Agent credentials required.")

        @app.get("/slots")
        def slots():
            return jsonify(agent.snapshots())

        @app.post("/action")
        def action():
            data = request.get_json()
            if not isinstance(data, dict) or not isinstance(data.get("slot"), str):
                abort(400, "Invalid slot action.")
            try:
                return jsonify(agent.action(data["slot"], data.get("action"), data.get("server"),
                                            data.get("project"), data.get("bed")))
            except ValueError as error:
                abort(400, str(error))

        return app

    coordinators = {server_address(server): port for server, port in config.get("coordinators", {}).items()}
    if any(not isinstance(port, int) or not 1024 <= port <= 65535 for port in coordinators.values()):
        raise ValueError("Invalid project coordinator port.")
    hosts = {host["id"]: host for host in config["hosts"]}
    if len(hosts) != len(config["hosts"]):
        raise ValueError("Host IDs must be unique.")
    pool = ThreadPoolExecutor(max_workers=16)
    state.mkdir(parents=True, exist_ok=True)
    database = state / "fleet.sqlite3"
    with open_database(database) as db:
        db.execute("CREATE TABLE IF NOT EXISTS professions (name TEXT PRIMARY KEY, server TEXT NOT NULL, steps TEXT NOT NULL)")

    def json_post():
        if request.headers.get("X-BotFury") != "fleet" or not request.is_json:
            abort(403, "Use the fleet dashboard to send this action.")
        data = request.get_json()
        if not isinstance(data, dict):
            abort(400, "Expected an action object.")
        return data

    def host_snapshot(host):
        slots = rpc(host["port"], "/slots", token=host["token"], timeout=3)
        return {"id": host["id"], "name": host["name"], "online": isinstance(slots, list),
                "slots": slots if isinstance(slots, list) else []}

    def host_snapshots():
        return list(pool.map(host_snapshot, hosts.values()))

    def invoke(host_id, slot, action, server=None, project=None, bed=None):
        host = hosts[host_id]
        result = rpc(host["port"], "/action", token=host["token"], timeout=4,
                     data={"slot": slot, "action": action, "server": server, "project": project, "bed": bed})
        return {"host": host_id, "slot": slot, **(result or {
            "ok": False, "message": "Host did not respond.",
        })}

    def get_board(port):
        board = rpc(port, "/projects", timeout=2)
        return board if isinstance(board, list) else None

    runs = ProfessionRuns(database, coordinators, get_board, host_snapshots, invoke, app.logger)

    @app.get("/")
    def page():
        return send_from_directory(app.root_path, "index.html")

    @app.get("/static/<path:filename>")
    def static_file(filename):
        return send_from_directory(Path(app.root_path) / "static", filename)

    @app.get("/api/fleet")
    def fleet():
        return jsonify(hosts=host_snapshots(),
                       sampled=datetime.now(timezone.utc).isoformat())

    @app.post("/api/action")
    def fleet_action():
        data = json_post()
        action = data.get("action")
        if action not in ("start", "stop", "connect", "halt", "bed"):
            abort(400, "Unknown action.")
        server = None
        if action in ("connect", "bed"):
            try:
                server = server_address(data.get("server"))
            except ValueError as error:
                abort(400, str(error))
        targets = select_targets(data)
        return jsonify(results=list(pool.map(lambda pair: invoke(*pair, action, server, bed=data.get("bed")), targets)))

    def select_targets(data):
        selected = data.get("bots")
        if not isinstance(selected, list) or not 1 <= len(selected) <= 100:
            abort(400, "Select up to 100 slots.")
        targets = {}
        for bot in selected:
            if (not isinstance(bot, dict) or not isinstance(bot.get("host"), str)
                    or bot["host"] not in hosts or not isinstance(bot.get("slot"), str)):
                abort(400, "Invalid fleet selection.")
            targets[(bot["host"], bot["slot"])] = None
        return list(targets)

    @app.route("/api/professions", methods=["GET", "POST"])
    def professions():
        with open_database(database) as db:
            if request.method == "POST":
                data = json_post()
                name = data.get("name")
                lines = data.get("steps")
                if not isinstance(name, str) or not name.strip() or len(name) > 80:
                    abort(400, "Enter a profession name.")
                if not isinstance(lines, str) or not lines.strip() or len(lines) > 12000:
                    abort(400, "Enter a job sequence.")
                try:
                    server = server_address(data.get("server"))
                    project_ids(lines)
                except ValueError as error:
                    abort(400, str(error))
                db.execute("INSERT INTO professions VALUES (?, ?, ?) ON CONFLICT(name) DO UPDATE "
                           "SET server=excluded.server, steps=excluded.steps", (name.strip(), server, lines.strip()))
            rows = db.execute("SELECT name, server, steps FROM professions ORDER BY name")
            return jsonify(professions=[dict(zip(("name", "server", "steps"), row)) for row in rows])

    @app.route("/api/runs", methods=["GET", "POST"])
    def profession_runs():
        if request.method == "POST":
            data = json_post()
            targets = select_targets(data)
            name = data.get("name")
            if not isinstance(name, str):
                abort(400, "Select a saved profession.")
            with open_database(database) as db:
                entry = db.execute("SELECT server, steps FROM professions WHERE name=?", (name,)).fetchone()
            if entry is None:
                abort(404, "Profession not found.")
            try:
                return jsonify(id=runs.create(name, entry[0], project_ids(entry[1]), targets)), 202
            except ValueError as error:
                abort(400, str(error))
        keys = ("id", "name", "server", "status", "detail", "step")
        return jsonify(runs=[{key: row[key] for key in keys} for row in runs.rows()])

    @app.post("/api/runs/<int:run_id>/cancel")
    def cancel_run(run_id):
        json_post()
        if not runs.cancel(run_id):
            abort(404, "Active profession not found.")
        return jsonify(ok=True)

    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--production", action="store_true")
    options = parser.parse_args()
    config = json.loads(options.config.read_text())
    app = make_app(config, options.state)
    agent = app.extensions.get("fleet_agent")

    def shutdown(*_):
        if agent:
            agent.shutdown()
        raise SystemExit

    signal.signal(signal.SIGTERM, shutdown)
    signal.signal(signal.SIGINT, shutdown)
    if options.production:
        from waitress import serve
        serve(app, host="127.0.0.1", port=config["port"], threads=12,
              max_request_body_size=48 * 1024)
    else:
        app.run(host="127.0.0.1", port=config["port"], threaded=True)


if __name__ == "__main__":
    main()
