from pathlib import Path
from urllib.request import urlopen
from urllib.parse import urlencode
import json
import os
import re
import secrets
import shutil
import signal
import subprocess as sp
import sys
import time

from paper_runtime import Rcon, wait

# Run on a dedicated CI worker. Paper, RCON and the client APIs bind to loopback.
repo = Path(__file__).resolve().parents[2]
fixture = Path(__file__).resolve().parent
out = Path(sys.argv[2]).resolve()
out.mkdir()
paper = out / "paper"
paper.mkdir()
(paper / "paper.jar").symlink_to(Path(sys.argv[1]).resolve(strict=True))
(paper / "eula.txt").write_text("eula=true\n")
password = secrets.token_hex(20)
properties = {
    "server-ip": "127.0.0.1", "server-port": 25679, "enable-rcon": "true", "rcon.port": 25680,
    "rcon.password": password, "level-name": "fixture", "level-type": "minecraft:flat",
    "level-seed": 732, "generate-structures": "false", "spawn-protection": 0,
    "online-mode": "false", "view-distance": 6, "simulation-distance": 4, "allow-nether": "false",
}
(paper / "server.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()))
runtime = repo / "minecraft-bots/botfury-mod/build/botfury-client"
java = (runtime / "java-path.txt").read_text().strip()
display = ":179"
env = dict(os.environ, DISPLAY=display, ALSOFT_DRIVERS="null", LP_NUM_THREADS="1")
clients = ("FuryFootingLead", "FuryGroundMiner", "FuryFloorBuilder")
processes = []
rcon = None
capture = None


def start(name, args, cwd):
    with (out / f"{name}.log").open("w") as log:
        proc = sp.Popen(args, cwd=cwd, env=env, stdout=log, stderr=sp.STDOUT)
    processes.append(proc)
    return proc


def http(index, path):
    with urlopen(f"http://127.0.0.1:{20045 + index}/{path}", timeout=3) as response:
        return response.read()


def x(*args):
    return sp.check_output(["xdotool", *args], env=env, text=True, stderr=sp.DEVNULL).strip()


def arrange(process, left, top):
    def find_window():
        windows = x("search", "--onlyvisible", "--pid", str(process.pid)).splitlines()
        return windows[-1] if windows else None

    window = wait(find_window)
    x("windowmove", window, str(left), str(top), "windowraise", window)
    return window


def launch(index):
    name = clients[index]
    profile = out / name
    (profile / "config").mkdir(parents=True)
    (profile / "options.txt").write_text("pauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:4\n")
    (profile / "config/botfury-ui.json").write_text('{"fleetGuideSeen":true}')
    if index == 0:
        structures = profile / "config/botfury-structures"
        structures.mkdir()
        shutil.copy2(fixture / "foundation.nbt", structures)
        shutil.copy2(fixture / "manifest.json", profile / "config/botfury-projects.json")
    flags = {
        "port": 20045 + index, "offlineUsername": name, "managedHttp": str(index != 0).lower(),
        "strictPort": "true", "httpBindAddress": "127.0.0.1", "autoJoin": "true",
        "serverIp": "127.0.0.1", "serverPort": 25679, "formationBasePort": 20046,
        "formationPortCount": 2, "maxFps": 20, "advancedModulesFile": out / "botfury-advanced.txt",
    }
    return start(name, [
        java, "-Xms128m", "-Xmx800m", "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC",
        *[f"-Dbotfury.{key}={value}" for key, value in flags.items()], f"@{runtime / 'client.args'}",
        "--username", name, "--width", "640", "--height", "360",
    ], profile)


def ready(index):
    state = json.loads(http(index, "status"))
    return state.get("connected") and state.get("player") == clients[index]


def enroll(index, project_id):
    query = urlencode({"cmd": f"project:worker 20045 {project_id}"})
    assert http(index, f"command?{query}") == b"Project worker enabled"


def projects():
    journal = out / clients[0] / "config/botfury-projects.json"
    return json.loads(journal.read_text())["projects"]


def completed(project_id):
    project = projects().get(project_id)
    if project and all(job["state"] == "DONE" for job in project["jobs"]):
        return project
    return None


def send_chat(window, text):
    x("windowfocus", "--sync", window)
    x("key", "slash")
    time.sleep(0.6)
    x("type", "--clearmodifiers", "--delay", "20", text)
    time.sleep(0.4)
    x("key", "Return")


def automatic_storage(window, quantity):
    log = out / clients[0] / "logs/latest.log"
    offset = log.stat().st_size
    send_chat(window, f"bf output manifest minecraft:dirt={quantity}")

    def submitted():
        with log.open() as stream:
            stream.seek(offset)
            message = stream.read()
        if "Could not queue output manifest" in message:
            raise RuntimeError(message)
        return re.search(r"Queued (manifest-[a-f0-9]{8})", message)

    identity = wait(submitted, 30).group(1)
    return wait(lambda: projects().get(identity), 10)["supplyChest"]


try:
    start('paper',[java,'-Xms256m','-Xmx1024m','-XX:ActiveProcessorCount=2','-jar','paper.jar','nogui'],paper)
    rcon = wait(lambda: Rcon(25680, password))
    cmd = rcon.command
    cmd('forceload add 17296 17296 17327 17327')
    time.sleep(1)
    for command in ('gamerule doMobSpawning false','time set noon',
        'fill 17299 141 17299 17324 141 17313 bedrock','setblock 17308 141 17305 dirt',
        'setblock 17303 142 17305 dirt','setblock 17312 142 17305 chest[facing=west]'):
        cmd(command)
    start('display',['Xvfb',display,'-screen','0','1280x720x24','-nolisten','tcp'],out)
    time.sleep(.5)
    leader=launch(0)
    wait(lambda:ready(0),85)
    cmd('gamemode spectator FuryFootingLead')
    cmd('tp FuryFootingLead 17309.5 147 17309.5 140 31')
    win=arrange(leader,0,0)
    time.sleep(1)
    send_chat(win, "bf project build foundation.nbt 17308 142 17305")
    def getzone():
        a=out/'botfury-exclusion-zones.json'
        if a.exists(): return next((z for z in json.loads(a.read_text()) if z['name'].startswith('foundation-')),None)
    zone=wait(getzone,30)
    print(zone,flush=True)
    assert zone['bounds']['min']['y']==141,zone
    workers=[launch(i) for i in (1,2)]
    wait(lambda:all(ready(i) for i in (1,2)),85)
    for command in ('tp FuryGroundMiner 17307.5 142 17305.5 -90 30',
        'tp FuryFloorBuilder 17308.5 142 17303.5 0 25','give FuryFloorBuilder oak_planks 1'):
        cmd(command)
    arrange(workers[0],640,0)
    arrange(workers[1],640,360)
    capture=start('capture',['ffmpeg','-nostdin','-loglevel','error','-n','-f','x11grab','-draw_mouse','0',
        '-framerate','15','-video_size','1280x720','-i',display+'.0+0,0','-an','-c:v','libx264','-threads','2',
        '-preset','veryfast','-pix_fmt','yuv420p','-movflags','+faststart',str(out/'production.mp4')],out)
    enroll(1,'manifest-footing')
    mined = wait(lambda: completed('manifest-footing'))
    stock=cmd('data get block 17312 142 17305 Items')
    assert 'id: "minecraft:dirt", Count: 1b' in stock,stock
    assert cmd('execute if block 17303 142 17305 air')=='Test passed'
    assert cmd('execute if block 17308 141 17305 dirt')=='Test passed'
    print('The independent gatherer deposited external dirt. The support survived.',flush=True)
    miner_log = out / clients[1] / "logs/latest.log"
    before_disconnect = miner_log.stat().st_size
    cmd(f"kick {clients[1]} BotFury replay disconnect")

    def restored_worker():
        with miner_log.open() as stream:
            stream.seek(before_disconnect)
            return "Restored project worker enrollment" in stream.read() and ready(1)

    wait(restored_worker, 60)
    print("The worker reconnected and restored its saved enrollment without a new command.", flush=True)
    # Set up the same obstruction as the original replay before leasing the builder.
    cmd('tp FuryGroundMiner 17308.25 142 17305.5')
    time.sleep(.5)
    assert cmd('execute at FuryGroundMiner if block ~ ~ ~ minecraft:air') == 'Test passed'
    enroll(2,zone['name'])
    built = wait(lambda: completed(zone['name']))
    assert cmd('execute if block 17308 142 17305 oak_planks')=='Test passed'
    assert "Clearing active placement at 17308, 142, 17305" in miner_log.read_text()
    assert cmd('execute if block 17308 141 17305 dirt')=='Test passed'
    print("Paper verified the idle miner yielded and the owner built without intervention after enrollment.", flush=True)
    cmd("tp FuryFootingLead 17316.5 142 17309.5 0 0")
    time.sleep(2)
    first_storage = automatic_storage(win, 1)
    time.sleep(1)
    second_storage = automatic_storage(win, 2)
    assert second_storage != first_storage, (first_storage, second_storage)
    print(f"Two requests from the same spot reserved separate chests: {first_storage}, {second_storage}", flush=True)
    (out / "verified.json").write_text(json.dumps(
        dict(zone=zone, stock=stock, manifest=mined, build=built, reconnectedWorker=clients[1],
             storage=[first_storage, second_storage]), indent=2
    ) + "\n")
    time.sleep(3)
finally:
    if capture and capture.poll() is None:
        capture.send_signal(signal.SIGINT)
        capture.wait(timeout=10)
    if rcon:
        try:
            rcon.command("stop")
        except (OSError, EOFError):
            pass
        rcon.close()
    for proc in reversed(processes):
        if proc.poll() is None:
            if proc is not processes[0] or rcon is None:
                proc.terminate()
            try:
                proc.wait(timeout=12)
            except sp.TimeoutExpired:
                proc.kill()
                proc.wait()
