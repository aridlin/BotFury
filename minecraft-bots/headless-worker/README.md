# BotFury native worker prototype

The worker and game client are native executables. The worker claims an explicitly named project on the existing BotFury broker, renews before each gameplay action, and reports observed results. It accepts an exact locally reachable log, stem, or dirt gather, a player-inventory plank craft, and deposits into one chest. A missing lease, unsuitable task, or mismatched world stops the worker.

Build [mcclient](https://github.com/MVRer/mcclient) at revision `12179b06709e4d2161483e87065b35f292898783` with the included patch. This revision uses offline Minecraft accounts. The patch fixes position confirmation on 26.3 and mirrors the server's chest inventory updates into the player inventory.

```sh
# In a checkout of the pinned mcclient revision
git apply /path/to/BotFury/minecraft-bots/headless-worker/mcclient-26.3-state.patch
cargo build --locked --release

# In BotFury/minecraft-bots/headless-worker
cargo build --locked --release
target/release/botfury-headless-worker /path/to/mcclient/target/release/mcclient SERVER:PORT NAME COORDINATOR_PORT PROJECT JOB_COUNT
```

The coordinator port is on loopback. The lease must name the same server and dimension as the worker. For a 26.3 server, launch the BotFury master with `-Dbotfury.autoJoin=false` and `-Dbotfury.projectWorld=SERVER:PORT|minecraft:overworld` as a single JVM argument. Use `local` as the server in that argument when the worker joins via localhost or 127.0.0.1. Submit jobs through the broker at `POST /projects`.

The world setting applies while the master is at the title screen. When the master joins a game, its connected world takes precedence. The project editor and in-world HUD currently use the master’s game connection. The project HTTP endpoint continues to require a loopback client.
