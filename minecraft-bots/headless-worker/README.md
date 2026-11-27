# BotFury native worker prototype

The worker and game client are native executables. The worker claims an explicitly named project on the existing BotFury broker, renews before each gameplay action, and reports observed results. It supports locally reachable logs, stems, dirt, stone, deepslate, and coal, iron, or diamond ore. Gathering checks and equips a suitable pickaxe where required. Workers can craft planks, sticks, and a crafting table in their inventory, then use a placed table to craft a furnace and wooden, stone, and iron pickaxes. Furnace jobs can smelt raw iron with coal or charcoal. The worker leaves the batch in the furnace until all requested ingots are ready so another lease holder can recover a queued batch. Single and double chests support deposits, withdrawals, and stock checks. It can place blocks at reserved targets when it carries the material. Placement supports plain blocks and axis-oriented blocks such as logs. Verification checks the observed block and the supplied properties in loaded chunks.

A missing lease, unsuitable task, or mismatched world stops the worker. Use the prototype with a project whose jobs fit these capabilities.

Build [mcclient](https://github.com/MVRer/mcclient) at revision `12179b06709e4d2161483e87065b35f292898783` with the included patches. This revision uses offline Minecraft accounts. The patch fixes position confirmation on 26.3 and keeps the worker's inventory and cursor in sync with container updates. The furnace patch also reads the server’s burn and cook progress. A withdrawal can enforce a minimum stock reserve and split a stack to leave the rest in storage.

```sh
# In a checkout of the pinned mcclient revision
git apply /path/to/BotFury/minecraft-bots/headless-worker/mcclient-26.3-state.patch
git apply /path/to/BotFury/minecraft-bots/headless-worker/mcclient-26.3-furnace.patch
cargo build --locked --release

# In BotFury/minecraft-bots/headless-worker
cargo build --locked --release
target/release/botfury-headless-worker /path/to/mcclient/target/release/mcclient SERVER:PORT NAME COORDINATOR_PORT PROJECT JOB_COUNT
```

Use a job count of `0` to keep a worker available for the project until it has found no eligible work for 12 seconds. This lets a worker finish its own tasks and then pick up work released by a peer.

The coordinator port is on loopback. The lease must name the same server and dimension as the worker. For a 26.3 server, launch the BotFury master with `-Dbotfury.autoJoin=false` and `-Dbotfury.projectWorld=SERVER:PORT|minecraft:overworld` as a single JVM argument. Use `local` as the server in that argument when the worker joins via localhost or 127.0.0.1. Submit jobs through the broker at `POST /projects`.

The world setting applies while the master is at the title screen. When the master joins a game, its connected world takes precedence. The project editor and in-world HUD currently use the master’s game connection. The project HTTP endpoint continues to require a loopback client.

The [optional Baritone engine](baritone/README.md) uses the Rust Baritone planner for walking routes in loaded chunks.
