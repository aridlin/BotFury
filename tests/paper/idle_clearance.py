from pathlib import Path
from urllib.request import urlopen
from urllib.parse import urlencode
import json
import os
import secrets
import shutil
import signal
import socket
import struct
import subprocess as sp
import sys
import time

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


def wait(condition, limit=90):
    deadline = time.monotonic() + limit
    while time.monotonic() < deadline:
        try:
            if result := condition():
                return result
        except (OSError, EOFError, ValueError, sp.CalledProcessError):
            pass
        time.sleep(0.25)
    raise RuntimeError(f"Timed out from line {sys._getframe(1).f_lineno}")


class Rcon:
    def __init__(self):
        self.socket = socket.create_connection(("127.0.0.1", 25680), timeout=12)
        self.reader = self.socket.makefile("rb")
        self.request_id = 1
        try:
            self.send(3, password)
            while True:
                request_id, kind, _ = self.read()
                if request_id == -1:
                    raise RuntimeError("Paper fixture RCON authentication failed")
                if kind == 2:
                    break
        except BaseException:
            self.close()
            raise

    def send(self, kind, body):
        payload = struct.pack("<ii", self.request_id, kind) + body.encode() + b"\0\0"
        self.socket.sendall(struct.pack("<i", len(payload)) + payload)

    def read(self):
        length = self.reader.read(4)
        if len(length) != 4:
            raise EOFError("Paper closed the RCON connection")
        size, = struct.unpack("<i", length)
        data = self.reader.read(size)
        return (*struct.unpack("<ii", data[:8]), data[8:-2].decode())

    def command(self, command):
        self.request_id += 1
        self.send(2, command)
        while True:
            request_id, _, body = self.read()
            if request_id == self.request_id:
                return body

    def close(self):
        self.reader.close()
        self.socket.close()


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


def completed(project_id):
    journal = out / clients[0] / "config/botfury-projects.json"
    project = json.loads(journal.read_text())["projects"].get(project_id)
    if project and all(job["state"] == "DONE" for job in project["jobs"]):
        return project
    return None


try:
    start('paper',[java,'-Xms256m','-Xmx1024m','-XX:ActiveProcessorCount=2','-jar','paper.jar','nogui'],paper)
    rcon = wait(Rcon)
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
    x('windowfocus','--sync',win)
    x('key','slash')
    time.sleep(.6)
    x('type','--clearmodifiers','--delay','20','bf project build foundation.nbt 17308 142 17305')
    x('key','Return')
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
    # Set up the same obstruction as the original replay before leasing the builder.
    cmd('tp FuryGroundMiner 17308.25 142 17305.5')
    time.sleep(.5)
    assert cmd('execute at FuryGroundMiner if block ~ ~ ~ minecraft:air') == 'Test passed'
    enroll(2,zone['name'])
    built = wait(lambda: completed(zone['name']))
    assert cmd('execute if block 17308 142 17305 oak_planks')=='Test passed'
    miner_log=(out/clients[1]/'logs/latest.log').read_text()
    assert 'Clearing active placement at 17308, 142, 17305' in miner_log
    assert cmd('execute if block 17308 141 17305 dirt')=='Test passed'
    (out/'verified.json').write_text(json.dumps(dict(zone=zone,stock=stock,manifest=mined,build=built),indent=2)+'\n')
    print('Paper verified the idle miner yielded and the owner built without intervention after enrollment.',flush=True)
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
