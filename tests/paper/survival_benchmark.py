"""Run the ten-worker house and stock benchmark on a disposable normal Paper world."""
from argparse import ArgumentParser
from collections import Counter
from hashlib import sha256
from pathlib import Path
from urllib.request import urlopen
import json
import math
import os
import re
import secrets
import shutil
import signal
import subprocess as sp
import time

from paper_runtime import Rcon, wait

WORKERS = tuple(f"FuryAutoBot{i:02}" for i in range(1, 11))
MASTER = "FuryAutoMaster"
SERVER_PORT = 25677
RCON_PORT = 25678
CONTROL_PORT = 20030
TARGETS = {"dirt": 320, "cobblestone": 192, "logs": 128, "diamond": 128}
STOCK_ORDER = "bf output manifest minecraft:dirt=320 minecraft:cobblestone=192 any_logs=128 minecraft:diamond=128"
FIXTURE = Path(__file__).resolve().parent / "survival"
SCHEMATIC = FIXTURE / "village-homestead.nbt"
RUNTIME = Path(__file__).resolve().parents[2] / "minecraft-bots/botfury-mod/build/botfury-client"


def find_site(connection, player, cells):
    position = connection.command(f"data get entity {player} Pos")
    x, y, z = (math.floor(float(part)) for part in re.findall(r"(-?[0-9.]+)d", position))
    offsets = [(0, 0), (0, 6), (6, 0), (6, 6), (3, 3), (0, 3), (3, 0), (3, 6), (6, 3)]
    for radius in (8, 18, 28, 38, 48, 58, 68):
        for dx, dz in ((radius, 0), (-radius, 0), (0, radius), (0, -radius),
                       (radius, radius), (-radius, -radius), (radius, -radius), (-radius, radius)):
            left, front = x + dx, z + dz
            for dy in (0, 1, -1, 2, -2, 3, -3, 4, -4, 5, -5, 6, -6, 8, -8, 10, -10):
                bottom = y + dy
                samples = " ".join(f"if block {left+sx} {bottom-1} {front+sz} minecraft:grass_block"
                                   for sx, sz in offsets)
                if connection.command(f"execute {samples}") != "Test passed":
                    continue
                clear_entry = [f"if block {left+dx} {bottom} {front-2} minecraft:air" for dx in (0, 1, 2)]
                clear_base = [f"if block {left+sx} {bottom} {front+sz} minecraft:air" for sx, sz in offsets]
                if connection.command("execute " + " ".join(clear_entry + clear_base)) == "Test passed":
                    return left, bottom, front
    raise RuntimeError(f"No open house site near spawn position {position} for {len(cells)} schematic cells")


def read_status(index):
    with urlopen(f"http://127.0.0.1:{CONTROL_PORT+index}/status", timeout=3) as response:
        return json.load(response)


def start(name, args, directory, output, environment):
    with (output / f"{name}.log").open("w") as log:
        return sp.Popen(args, cwd=directory, env=environment, stdout=log, stderr=sp.STDOUT)


def audit_house(connection, cells, site):
    return [
        [x, y, z, state, connection.command(
            f"execute if block {site[0]+x} {site[1]+y} {site[2]+z} {state}"
        )]
        for x, y, z, state in cells
    ]


def describe_work(project):
    jobs = project["jobs"]
    return {
        "states": dict(Counter(job["state"] for job in jobs)),
        "active": [
            {"job": job["work"]["id"], "kind": job["work"]["kind"],
             "worker": job.get("worker"), "attempts": job["attempts"]}
            for job in jobs if job["state"] == "LEASED"
        ],
        "failures": [
            {"job": job["work"]["id"], "attempts": job["attempts"], "evidence": job.get("evidence")}
            for job in jobs if job["state"] == "FAILED"
        ],
    }


def observe(connect, out, journal, house_id, manifest_id, cells, site, minutes):
    deadline = time.monotonic() + 60 * minutes
    next_audit = 0
    while time.monotonic() < deadline:
        projects = json.loads(journal.read_text())["projects"]
        house = projects[house_id]
        manifest = projects[manifest_id]
        coords = manifest["supplyChest"].removeprefix("block:").replace(",", " ")
        connection = connect()
        try:
            stock = Counter()
            # Read one slot at a time, so RCON cannot truncate multi-packet inventory responses.
            for slot in range(27):
                item = connection.command(f"data get block {coords} Items[{{Slot:{slot}b}}]")
                found = re.search(r'id: "minecraft:([a-z_]+)"[^}]*?Count: (\d+)b', item)
                if found:
                    name, count = found.groups()
                    stock["logs" if name.endswith("_log") else name] += int(count)
            row = {"time": time.time(), "chest": coords, "stock": {k: stock[k] for k in TARGETS},
                   "house": describe_work(house), "manifest": describe_work(manifest)}
            stock_ready = all(stock[item] >= count for item, count in TARGETS.items())
            if time.monotonic() >= next_audit or stock_ready:
                checks = audit_house(connection, cells, site)
                row["houseCellsMatched"] = sum(check[-1] == "Test passed" for check in checks)
                row["houseCellsTotal"] = len(cells)
                next_audit = time.monotonic() + 120
                (out / "latest-house-cells.json").write_text(json.dumps(checks, indent=2) + "\n")
                if stock_ready and row["houseCellsMatched"] == len(cells):
                    (out / "verified.json").write_text(json.dumps(dict(row, targets=TARGETS), indent=2) + "\n")
                    return
        finally:
            connection.close()
        print(json.dumps({
            "stock": row["stock"], "houseCellsMatched": row.get("houseCellsMatched"),
            "houseJobs": row["house"]["states"], "manifestJobs": row["manifest"]["states"],
        }), flush=True)
        with (out / "progress.jsonl").open("a") as trace:
            trace.write(json.dumps(row) + "\n")
        time.sleep(30)
    raise RuntimeError("The survival benchmark reached its time limit before Paper verified the house and stock")


def run(args):
    out = args.output.resolve()
    out.mkdir()
    paper = out / "paper"
    paper.mkdir()
    (paper / "paper.jar").symlink_to(args.paper.resolve(strict=True))
    (paper / "eula.txt").write_text("eula=true\n")
    password = secrets.token_hex(20)
    properties = {
        "server-ip": "127.0.0.1", "server-port": SERVER_PORT,
        "enable-rcon": "true", "rcon.port": RCON_PORT, "rcon.password": password,
        "level-name": "benchmark", "level-type": "minecraft:normal", "level-seed": args.seed,
        "spawn-protection": 0, "online-mode": "false", "view-distance": 8,
        "simulation-distance": 5, "difficulty": "normal",
    }
    (paper / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
    reference = json.loads(SCHEMATIC.with_suffix(".json").read_text())
    if sha256(SCHEMATIC.read_bytes()).hexdigest() != reference["sha256"]:
        raise RuntimeError("The house reference was built from a different schematic")
    cells = reference["cells"]
    display = ":178"
    environment = dict(os.environ, DISPLAY=display, ALSOFT_DRIVERS="null", LP_NUM_THREADS="1")
    java = (RUNTIME / "java-path.txt").read_text().strip()
    processes = []
    connection = None
    captured = None

    def connect():
        return Rcon(RCON_PORT, password)

    def run_process(name, command, directory=out):
        process = start(name, command, directory, out, environment)
        processes.append(process)
        return process

    def x(*arguments):
        return sp.check_output(["xdotool", *arguments], env=environment, text=True, stderr=sp.DEVNULL).strip()

    def window(process, left, top):
        identity = wait(lambda: x("search", "--onlyvisible", "--pid", str(process.pid))).splitlines()[-1]
        x("windowmove", identity, str(left), str(top), "windowraise", identity)
        return identity

    def launch(index, name):
        profile = out / name
        config = profile / "config"
        config.mkdir(parents=True)
        (profile / "options.txt").write_text("pauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:5\nmaxFps:10\n")
        (config / "botfury-ui.json").write_text('{"fleetGuideSeen":true}')
        if index == 0:
            structures = config / "botfury-structures"
            structures.mkdir()
            shutil.copyfile(SCHEMATIC, structures / SCHEMATIC.name)
        visible = index <= 2
        flags = {
            "port": CONTROL_PORT + index, "offlineUsername": name, "managedHttp": str(index != 0).lower(),
            "strictPort": "true", "httpBindAddress": "127.0.0.1", "autoJoin": "true",
            "serverIp": "127.0.0.1", "serverPort": SERVER_PORT, "formationBasePort": CONTROL_PORT + 1,
            "formationPortCount": len(WORKERS),
            "disableRendering": str(not visible).lower(), "advancedModulesFile": out / "botfury-advanced.txt",
        }
        return run_process(name, [
            java, "-Xms128m", "-Xmx720m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC",
            *[f"-Dbotfury.{key}={value}" for key, value in flags.items()], f"@{RUNTIME / 'client.args'}",
            "--username", name, "--width", "480", "--height", "270",
        ], profile)

    def ready(index, name):
        status = read_status(index)
        return status.get("connected") and status.get("player") == name

    def master_at(position):
        status = read_status(0)
        return status.get("connected") and status.get("player") == MASTER and all(
            abs(status.get(axis, math.inf) - expected) < 1
            for axis, expected in zip("xz", (position[0], position[2]))
        )

    def order(master_window, text, reply, allow_chunk_retry=False):
        log = out / MASTER / "logs/latest.log"

        def master_connected():
            return ready(0, MASTER) and MASTER in connection.command("list")

        for attempt in range(3 if allow_chunk_retry else 1):
            wait(master_connected, 120)
            position = log.stat().st_size
            x("windowfocus", "--sync", master_window)
            x("key", "slash")
            time.sleep(0.65)
            x("type", "--clearmodifiers", "--delay", "18", text)
            time.sleep(0.4)
            x("key", "Return")

            def accepted():
                with log.open() as stream:
                    stream.seek(position)
                    response = stream.read()
                if "Could not queue" in response:
                    return response
                return re.search(reply, response)

            try:
                result = wait(accepted, 75)
            except RuntimeError as error:
                raise RuntimeError(f"Could not verify /{text}: {error}") from error
            if isinstance(result, re.Match):
                return result.group(1)
            if not allow_chunk_retry or "Load the entire build site first" not in result or attempt == 2:
                raise RuntimeError(f"Paper replay order /{text} was rejected: {result}")
            print("Build site is still loading in the master client, retrying after a short wait.", flush=True)
            time.sleep(6)

    try:
        run_process("paper", [java, "-Xmx2048m", "-XX:ActiveProcessorCount=4", "-jar", "paper.jar", "nogui"], paper)
        connection = wait(connect, 150)
        command = connection.command
        run_process("display", ["Xvfb", display, "-screen", "0", "960x540x24", "-nolisten", "tcp"])
        time.sleep(1)
        master = launch(0, MASTER)
        wait(lambda: ready(0, MASTER), 150)
        site = tuple(args.site) if args.site else find_site(connection, MASTER, cells)
        command(f"gamemode creative {MASTER}")
        viewpoint = site[0] + 1.5, site[1], site[2] - 3.5
        command(f"tp {MASTER} {' '.join(map(str, viewpoint))} 0 4")
        wait(lambda: master_at(viewpoint), 120)
        site_chunks = sorted({((site[0] + x) // 16, (site[2] + z) // 16) for x, _, z, _ in cells})
        loaded_conditions = " ".join(f"if loaded {cx * 16} {site[1]} {cz * 16}" for cx, cz in site_chunks)
        wait(lambda: command(f"execute {loaded_conditions}") == "Test passed", 120)
        master_window = window(master, 0, 0)
        captured = run_process("capture", [
            "ffmpeg", "-nostdin", "-loglevel", "error", "-n", "-f", "x11grab", "-draw_mouse", "0",
            "-framerate", "8", "-video_size", "960x540", "-i", display + ".0+0,0",
            "-an", "-c:v", "libx264", "-threads", "2", "-preset", "ultrafast", "-crf", "29",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(out / "survival-demo.mp4"),
        ])
        time.sleep(1)
        if captured.poll() is not None:
            raise RuntimeError("Failed to record the hosted survival benchmark")
        house_id = order(master_window, f"bf project build {SCHEMATIC.name} {' '.join(map(str, site))} supplied",
                         r"Queued (village-homestead-[a-f0-9]{8})", allow_chunk_retry=True)
        command(f"gamemode spectator {MASTER}")
        command(f"tp {MASTER} {' '.join(map(str, viewpoint))} 0 4")
        handles = []
        for i, name in enumerate(WORKERS, 1):
            handles.append(launch(i, name))
            wait(lambda: ready(i, name), 150)
            if i <= 2:
                window(handles[-1], 480, (i - 1) * 270)
        initial = {name: command(f"data get entity {name} Inventory") for name in WORKERS}
        if not all(value.endswith(" []") for value in initial.values()):
            raise RuntimeError(f"Expected empty starting inventories, got {initial}")
        manifest_id = order(master_window, STOCK_ORDER, r"Queued (manifest-[a-f0-9]{8}) for 10 bots")
        order(master_window, "bf project workers", r"(Project worker requested for 10 bot\(s\))")
        (out / "orders.json").write_text(json.dumps({
            "seed": command("seed"), "site": site, "inventories": initial,
            "house": house_id, "manifest": manifest_id, "schematicSha256": reference["sha256"],
        }, indent=2) + "\n")
        observe(connect, out, out / MASTER / "config/botfury-projects.json",
                house_id, manifest_id, cells, site, args.minutes)
    finally:
        if captured and captured.poll() is None:
            captured.send_signal(signal.SIGINT)
            try:
                captured.wait(timeout=25)
            except sp.TimeoutExpired:
                captured.kill()
        if connection:
            try:
                connection.command("stop")
            except (OSError, EOFError):
                pass
            finally:
                connection.close()
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except sp.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("paper", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--seed", default="")
    parser.add_argument("--site", type=int, nargs=3)
    parser.add_argument("--minutes", type=int, default=60)
    run(parser.parse_args())
