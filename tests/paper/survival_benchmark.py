"""Run the ten-worker house and stock benchmark on a disposable normal Paper world."""
from argparse import ArgumentParser
from collections import Counter
from hashlib import sha256
from pathlib import Path
from urllib.parse import urlencode
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
    wait(lambda: connection.command(
        "execute " + " ".join(f"if loaded {x+dx} {y} {z+dz}" for dx in (-40, 40) for dz in (-40, 40))
    ) == "Test passed", 70)
    width = max(dx for dx, _, _, _ in cells)
    depth = max(dz for _, _, dz, _ in cells)
    corners = [(0, 0), (width, 0), (0, depth), (width, depth)]
    samples = [(width // 2, depth // 2), *corners,
               (width // 2, 0), (width // 2, depth), (0, depth // 2), (width, depth // 2)]
    entries = [(dx, -2) for dx in range(3)]
    offsets = sorted(((dx, dz) for dx in range(-64, 65, 8) for dz in range(-64, 65, 8)
                      if max(abs(dx), abs(dz)) >= 8), key=lambda p: p[0] ** 2 + p[1] ** 2)
    elevations = sorted(range(-18, 19), key=abs)
    ground = "#minecraft:dirt"

    def matches(conditions):
        return connection.command("execute " + " ".join(conditions)) == "Test passed"

    supported_centers = 0
    clear_entries = 0
    for dx, dz in offsets:
        left, front = x + dx, z + dz
        center_x, center_z = left + width // 2, front + depth // 2
        for dy in elevations:
            bottom = y + dy
            if not matches([f"if block {center_x} {bottom-1} {center_z} {ground}",
                            f"if block {center_x} {bottom} {center_z} minecraft:air"]):
                continue
            supported_centers += 1
            clear_entry = [
                condition
                for sx, sz in entries
                for condition in (f"if block {left+sx} {bottom} {front+sz} minecraft:air",
                                  f"if block {left+sx} {bottom+1} {front+sz} minecraft:air")
            ]
            if not matches(clear_entry):
                continue
            clear_entries += 1
            # Allow natural terrain one block into the floor. The house order will excavate it.
            if all(
                matches([f"if block {left+sx} {bottom-1} {front+sz} {ground}"])
                or matches([f"if block {left+sx} {bottom} {front+sz} {ground}"])
                for sx, sz in samples
            ):
                return left, bottom, front
    print(f"Survey at {position}: {supported_centers} supported centers and {clear_entries} clear entries.",
          flush=True)
    return None


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

    def assign_shared_workers(manifest_id):
        def enrolled(name, project):
            enrollment = out / name / "config/botfury-worker.json"
            data = json.loads(enrollment.read_text())
            return (data.get("name") == name and data.get("port") == CONTROL_PORT
                    and data.get("project") == project)

        # The in-game order dispatches enrollment asynchronously. Let that finish first.
        wait(lambda: all(enrolled(name, manifest_id) for name in WORKERS), 90)
        worker_command = urlencode({"cmd": f"project:worker {CONTROL_PORT}"})
        for index, name in enumerate(WORKERS, 1):
            with urlopen(f"http://127.0.0.1:{CONTROL_PORT+index}/command?{worker_command}", timeout=15) as response:
                if response.read() != b"Project worker enabled":
                    raise RuntimeError(f"Could not enroll {name} for both projects")

        wait(lambda: all(enrolled(name, None) for name in WORKERS), 90)
        print("All ten workers saved their shared project enrollment.", flush=True)

    def master_at(position):
        status = read_status(0)
        return status.get("connected") and status.get("player") == MASTER and all(
            abs(status.get(axis, math.inf) - expected) < 1
            for axis, expected in zip("xz", (position[0], position[2]))
        )

    def order(master_window, text, reply, allow_chunk_retry=False):
        log = out / MASTER / "logs/latest.log"

        def master_ready_for_input():
            status = read_status(0)
            return (status.get("connected") and status.get("player") == MASTER and status.get("gameInputReady")
                    and MASTER in connection.command("list"))

        for attempt in range(3 if allow_chunk_retry else 1):
            wait(master_ready_for_input, 120)
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
        world_seed = command("seed")
        (out / "world.json").write_text(json.dumps({"seed": world_seed}, indent=2) + "\n")
        print(f"Paper world {world_seed}.", flush=True)
        run_process("display", ["Xvfb", display, "-screen", "0", "960x540x24", "-nolisten", "tcp"])
        time.sleep(1)
        master = launch(0, MASTER)
        wait(lambda: ready(0, MASTER), 150)
        command(f"gamemode creative {MASTER}")
        site = tuple(args.site) if args.site else find_site(connection, MASTER, cells)
        relocated_spawn = False
        if site is None:
            located = command(f"execute at {MASTER} run locate biome minecraft:plains")
            biome = re.search(r"\[(-?\d+),\s*(?:~|-?\d+),\s*(-?\d+)\]", located)
            if not biome:
                raise RuntimeError(f"Could not locate a plains survey area: {located}")
            biome_x, biome_z = map(int, biome.groups())
            command(f"execute positioned {biome_x} 0 {biome_z} positioned over motion_blocking_no_leaves "
                    f"run tp {MASTER} ~ ~ ~")
            wait(lambda: master_at((biome_x, None, biome_z)), 120)
            site = find_site(connection, MASTER, cells)
            if site is None:
                raise RuntimeError(f"No supported house site near the located plains at {biome_x}, {biome_z}")
            spawn = site[0] + 1, site[1], site[2] - 2
            command(f"setworldspawn {' '.join(map(str, spawn))}")
            command("gamerule spawnRadius 0")
            relocated_spawn = True
        print(f"Selected house site {site}. Relocated initial fleet spawn: {relocated_spawn}.", flush=True)
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
        print(f"Paper accepted house order {house_id}.", flush=True)
        command(f"gamemode spectator {MASTER}")
        command(f"tp {MASTER} {' '.join(map(str, viewpoint))} 0 4")
        handles = []
        for i, name in enumerate(WORKERS, 1):
            handles.append(launch(i, name))
            wait(lambda: ready(i, name), 150)
            print(f"{name} joined Paper.", flush=True)
            if i <= 2:
                window(handles[-1], 480, (i - 1) * 270)
        initial = {name: command(f"data get entity {name} Inventory") for name in WORKERS}
        if not all(value.endswith(" []") for value in initial.values()):
            raise RuntimeError(f"Expected empty starting inventories, got {initial}")
        manifest_id = order(master_window, STOCK_ORDER, r"Queued (manifest-[a-f0-9]{8}) for 10 bots")
        print(f"Paper accepted stock order {manifest_id}.", flush=True)
        (out / "orders.json").write_text(json.dumps({
            "seed": world_seed, "site": site, "relocatedSpawn": relocated_spawn, "inventories": initial,
            "house": house_id, "manifest": manifest_id, "schematicSha256": reference["sha256"],
        }, indent=2) + "\n")
        assign_shared_workers(manifest_id)
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
