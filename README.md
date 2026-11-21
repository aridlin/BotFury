# 🌀 BotFury
🚀 *Automate & Control Minecraft Bots with Fabric*

---

## 📌 About
**BotFury** is a Fabric mod that allows you to remotely control multiple **Minecraft bot instances**, automate tasks like mining with **Baritone**, and toggle rendering for better performance.  
This mod integrates seamlessly with a **Flask-powered web dashboard**, letting you command your bot army via an intuitive HTTP-based control center.  

**Architecture**: Your browser connects to a **Flask dashboard** (port 5000) which sends commands via HTTP to **Fabric mod HTTP servers** (port 8765+) running on each bot instance, which execute Minecraft actions and Baritone commands.

🔹 **Multi-Bot Control**  
🔹 **HTTP-Based Remote Command Center**  
🔹 **Baritone Integration via Chat Commands**  
🔹 **Rendering Optimization for Performance**  

---

## 🚀 Features
✅ **Control Bots Remotely** – Send commands through the web dashboard.  
✅ **Bot Swarm Management** – Launch, stop, and control multiple bot instances.  
✅ **Baritone Integration** – Automate mining, pathfinding, and movement via chat commands (`#mine`, `#goto`, `#stop`).  
✅ **Server IP Selection** – Connect all bots to a chosen server with one click.  
✅ **Toggle Rendering** – Minimize GPU usage for better performance.  
✅ **HTTP-Based API** – Direct API access for custom automation.  

---

## 🛠️ Installation & Setup
### 📌 Prerequisites
Ensure you have the following installed:
- **Java 17** for Minecraft, plus **JDK 21 or 25** to run the Gradle build
- **Minecraft 1.20.1 + Fabric Loader**
- **Fabric API**
- **Baritone for Fabric**
- **C++17 compiler** for the FT dashboard; Python is only needed for the legacy Flask dashboard
- **Git** (for version control)

**Note**: Gradle is automatically downloaded via the build wrapper, so you don't need to install it separately.

### 📥 Clone the Repository
```bash
git clone https://github.com/aridlin/BotFury.git
cd BotFury
```

### 📦 Building the Fabric Mod

1. **Install Required Mods in Minecraft**  
   Before building, ensure your Minecraft instance has:
   - **Fabric API** (`fabric-api-*.jar`)
   - **Baritone for Fabric** (`baritone-fabric-*.jar`)

2. **Build the BotFury Mod** (Linux/macOS):
   ```bash
   ./build_mod.sh
   ```
   
   Or on **Windows**:
   ```cmd
   build_mod.bat
   ```
   
   The build process:
   - Automatically downloads Gradle and required dependencies
   - Compiles Java source code with Java 17 toolchain
   - Runs all tests
   - Generates the mod JAR file
   
   The compiled JAR will be at: `minecraft-bots/botfury-mod/build/libs/botfury-0.3.3.jar`

3. **Install the Mod**  
   Copy the generated JAR into your Fabric Minecraft instance's `mods/` directory:
   ```bash
   cp minecraft-bots/botfury-mod/build/libs/botfury-0.3.3.jar ~/.minecraft/mods/
   ```
   
   You can also install the versioned JAR from the release bundle. Keep the controller and every bot on the same build.

### Primary Dashboard: FT

Use the [FT dashboard](ft-dashboard/README.md) to start, stop, kill, and
restart individual instances. On Linux, its Podman backend can provision
one container checkpoint per bot at the Minecraft title screen and restore
it on subsequent launches. The dashboard can also start Minecraft directly
with Java and tile local game or viewer windows.

### In-game controls (Fabric 1.20.1)

Install the same BotFury 0.3.3 build and Fabric API on the controller and every bot.
The current local formations work on Paper and vanilla multiplayer without a
server mod. Install Fabric Baritone on bots for Saturn's longer approach and
inventory movement; Lightning uses packet movement exclusively.

BotFury registers exactly two keybindings, both remappable in Minecraft Controls:

| Default | Action |
| --- | --- |
| Mouse Forward (Mouse 5) | Open or close the command wheel. Click a sector; right-click goes back. |
| K | Strike the aimed target; during Lightning, return to the previous Saturn formation; with no target, stop. |

The wheel contains Saturn, Lightning, Scatter, Gather, Inventory, Bots, Baritone,
and Stop. Stop cancels Baritone, mining, inventory, recall and formations, including
pending returns from Lightning. The Baritone page includes Mine, Goto, Follow,
Pause, Resume and Stop. Baritone is required for mining and long-distance recall.

Choose an inventory item with the searchable picker, or use Tools, Wood, Stone,
Ores, Food and Blocks categories. The selected item is saved. Scroll the wheel to
change quantity; Shift-scroll changes it by 16. Actions target all bots or the bot
selected in the roster, with the controller as recipient. Delivery faces the
recipient and then moves outward. **Only X** also drops other main-inventory
items; equipped armor and offhand are excluded.

The Bots roster shows health, task, distance, dimension and connection state, and
provides Recall, Hold, Resume, rendering and inventory controls. Choose Track item
in the roster, then pick an item to show its icon and carried quantity above each
bot's ESP name. This selection is saved separately from inventory task items.
Counts include main inventory, equipment and offhand, sum split stacks, and
require this inventory-reporting build on the bots. A question mark means that
client does not report inventory; a tilde marks a last-known count. Stop tracking
clears the selection. Bot-only markers
use each managed client's local HTTP position reports, including beyond the
controller's render distance. They include edge indicators; disconnected positions
are marked with their age and disappear after 30 seconds. Positions in another
dimension stay in the roster. No server mod is required. All clients must run on
the same machine or expose their managed ports through local forwarding.

Mine → Ores → Iron → Both selects iron ore and deepslate iron ore together.
Bots reserve individual ore blocks and spaced standing positions through the
controller before approaching with Baritone and breaking blocks normally. Tools
are selected from their inventories. With no nearby matching ore, each bot explores
in a separate direction. This reduces duplicate targets and crowding; it cannot
guarantee that separate caves always have independent routes. The coordinator
must remain running. Mining stops if its reservation cannot be renewed.

Bot clients suppress local pushing from known fleet bots. Ordinary server
collision and position corrections still apply. Mining uses a block raycast and
manual block interaction so another bot at the crosshair does not become an
attack target. Scatter uses anchored destinations 32–56 blocks away.

The HUD shows mode, fleet count, movement state, target, attack cooldown and key
hints. HUD and marker preferences are saved. There are still exactly two bindings.

Useful client commands:

```text
/bf
/bf help
/bf allow packets
/bf allow movement
/bf allow off
/bf saturn
/bf lightning
/bf stop
/bf scatter
/bf gather
/bf spread
/bf inventory collect minecraft:iron_pickaxe 1
/bf inventory deliver minecraft:stone 16
/bf inventory keep minecraft:iron_pickaxe 1
/bf inventory only minecraft:iron_pickaxe 1
/bf friend add PlayerName
/bf friend remove PlayerName
/bf friend list
/bf bots
/bf esp
/bf bot BotName recall
/bf hud
/bf status
```

Confirm authorized movement using `/bf allow packets` or the dashboard's
Advanced modules setting. `movement` uses Baritone; `packets` enables supported
block-top jumps up to four blocks, with at most three blocks of height change,
every 0.2 seconds. Saturn uses Baritone outside packet range and restores its
previous block-breaking/placing settings after approach. Landing checks account
for slabs, stairs, fluids, entities, body clearance and world borders. A server
can still reject movement; corrections trigger a cooldown rather than immediate
repeated attempts. Teleport-only Lightning holds position if no valid landing is
available, rather than silently invoking Baritone.

Saturn excludes the master, known fleet bots, friends, teammates and
creative/spectator players from normal melee attacks. Lightning also rejects
protected targets. Friends are saved beside the advanced-modules file as
`botfury-friends.txt`; edits are locked and written atomically. If an existing
friend list cannot be read, automatic attacks pause instead of dropping the
protection. Lightning follows a zigzag, continues through visibility breaks,
waits ten seconds for an unloaded target to return, and restores a previously
active Saturn formation when the strike ends. Manual Stop cancels that return.
Managed bots automatically request respawn after death; server respawn rules
still apply.

Local discovery defaults to ports 8765–8774. Custom launchers can set
`botfury.formationBasePort`, `botfury.formationPortCount`, and
`botfury.advancedModulesFile`. Ordinary installations use the Fabric config
folder when no advanced-modules path is supplied. Clients advertise a protocol
version; mismatched clients appear as needing an update and receive no formation
orders. Orders carry sequence numbers so delayed movement cannot override a newer
Stop. Connection/world changes clear active movement and restore owned settings.

### Legacy Dashboard: Flask

1. **Install Python Dependencies**:
   ```bash
   cd flask-dashboard
   pip install -r requirements.txt
   ```

2. **Start the Flask Dashboard**:
   ```bash
   python3 dashboard.py
   ```
   
   The dashboard will be available at: `http://localhost:5000`

3. **Configure Bot Settings** (Optional):
   Edit `config/botfury.json` to customize:
   - Maximum number of bot instances
   - Default bot names and prefixes
   - Dashboard port (default 5000)
   - Baritone command prefix (default `#`)

---

## 🔧 Quick Start Guide

### Step 1: Launch Your Bots
1. Start your Minecraft instances with the BotFury Fabric mod installed.
2. Each bot will start an HTTP server on ports `8765`, `8766`, `8767`, etc.
3. The bots will attempt to connect to the Minecraft server specified in the configuration.

### Step 2: Start the Dashboard
1. Navigate to the `flask-dashboard/` directory.
2. Run `python3 dashboard.py`.
3. Open `http://localhost:5000` in your web browser.

### Step 3: Control Your Bots
The dashboard allows you to:
- **View Bot Status**: See which bots are online and their current state.
- **Send Commands**: Issue Baritone commands to all connected bots:
  - `#mine diamond_ore` – Mine a specific block type
  - `#goto x y z` – Navigate to coordinates
  - `#stop` – Stop current action
- **Toggle Rendering**: Click the "Disable GPU" button to reduce performance load.
- **Connect to Servers**: Select a Minecraft server IP and connect all bots.

### Step 4: Manual API Usage (Optional)
If you want to send commands directly via HTTP:

```bash
# Send a Baritone command to bot 1 (port 8765)
curl -X POST http://localhost:8765/command \
  -H "Content-Type: application/json" \
  -d '{"command": "mine diamond_ore"}'

# Toggle rendering
curl -X POST http://localhost:8765/render/toggle \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'

# Check bot status
curl http://localhost:8765/status
```

---

## 🛠️ Configuration

The mod reads configuration from `config/botfury.json` (created on first launch). You can customize:

```json
{
  "max_bots": 10,
  "dashboard_port": 5000,
  "baritone_command_prefix": "#",
  "disable_rendering_on_start": true,
  "server_ip": "localhost",
  "server_port": 25565,
  "webhook_url": null
}
```

**Configuration Options**:
- **max_bots**: Maximum number of simultaneous bot instances (default: 10)
- **dashboard_port**: Port for the Flask dashboard (default: 5000)
- **baritone_command_prefix**: Prefix for Baritone commands in chat (default: `#`)
- **disable_rendering_on_start**: Whether to disable rendering on bot startup for performance
- **server_ip** / **server_port**: Default Minecraft server to connect to
- **webhook_url**: Discord/custom webhook for status notifications (optional)

---

## 🔍 Troubleshooting

### **Dashboard won't start**
- Ensure Python 3.8+ is installed: `python3 --version`
- Verify Flask and requests are installed: `pip list`
- Check if port 5000 is available: `lsof -i :5000` (Linux/macOS)
- Try a different port: Edit `config/botfury.json` and restart

### **Bots won't connect to the dashboard**
- Verify the mod JAR is in your Minecraft `mods/` folder
- Check that Fabric API and Baritone are installed
- Ensure the bot instances are running Minecraft with the mod loaded
- Check the Minecraft log file for errors

### **Commands don't reach the bots**
- Verify the bot HTTP server is running (check Minecraft chat: `/botfury status`)
- Ensure the port numbers are correct (default: 8765, 8766, etc.)
- Check if a firewall is blocking the ports
- Confirm Baritone is installed and enabled

### **Gradle build fails**
- Ensure Java 17+ is installed: `java -version`
- Delete the cache: `rm -rf minecraft-bots/botfury-mod/.gradle`
- Try again: `./build_mod.sh`
- If still failing, check the detailed error message in the build output

### **Performance issues**
- Use the dashboard to toggle rendering off: Click "Disable GPU"
- Reduce the number of concurrent bot instances
- Check if other applications are consuming resources

### **Port already in use**
- Find the process using the port: `lsof -i :8765` (Linux/macOS)
- Kill the process if necessary: `kill -9 <PID>`
- Or configure bots to use different port ranges in `config/botfury.json`

---

## 🛣️ Roadmap
### ✅ Current Features
- ✅ Multi-bot HTTP control system
- ✅ Flask web dashboard
- ✅ Baritone integration (chat command prefix)
- ✅ Server connection management
- ✅ GPU rendering toggle

### 🔜 Planned Features
- **Advanced Baritone Automation** – Custom task queues and scheduling
- **Discord Bot Integration** – Control bots via Discord commands
- **Persistent Profiles** – Save and load bot configurations
- **Web UI Improvements** – Real-time bot status, command history, metrics
- **Advanced Task Automation** – Farming, building, fishing beyond mining
- **Player Detection & Reactions** – Bots react to nearby players

---

---

## 🏗️ Development & Building from Source

### Project Structure
```
BotFury/
├── minecraft-bots/botfury-mod/       # Fabric mod source code
│   ├── src/main/java/com/botfury/   # Java source files
│   ├── build.gradle                  # Gradle build configuration
│   ├── settings.gradle               # Gradle settings (foojay resolver)
│   └── build/libs/                   # Compiled JAR output
├── flask-dashboard/                  # Flask web dashboard
│   ├── dashboard.py                  # Main Flask app
│   ├── bot_manager.py                # Bot communication logic
│   ├── requirements.txt              # Python dependencies
│   ├── templates/                    # HTML templates
│   └── static/                       # CSS, JavaScript
├── config/                           # Configuration files
├── build_mod.sh / build_mod.bat      # Build scripts
└── README.md                         # This file
```

### Development Dependencies
- **Java 17+** (Gradle will auto-download via foojay-resolver)
- **Python 3.8+** with pip
- **Git**

### Building the Project
```bash
# Build the Fabric mod
./build_mod.sh

# Run tests
cd minecraft-bots/botfury-mod
./gradlew test

# Run the dashboard (in another terminal)
cd flask-dashboard
pip install -r requirements.txt
python3 dashboard.py
```

### Understanding the Code

**Fabric Mod** (`minecraft-bots/botfury-mod/src/main/java/com/botfury/`):
- `BotFuryMod.java` – Main mod entry point, initializes HTTP server
- `WorldRendererMixin.java` – Intercepts world rendering for GPU optimization
- HTTP server listens on configurable ports and accepts JSON commands

**Dashboard** (`flask-dashboard/`):
- `dashboard.py` – Flask app serving the web UI and API endpoints
- `bot_manager.py` – Handles HTTP communication with bot instances
- `templates/dashboard.html` – Web interface for bot control
- `static/script.js` – Frontend logic and API calls

---

## 👨‍💻 Contributing
Contributions are welcome! If you'd like to improve **BotFury**, feel free to:
1. **Fork** the repository.
2. Create a **feature branch**: `git checkout -b feature/my-feature`
3. **Make your changes** and test thoroughly.
4. **Commit with clear messages**: `git commit -m "Add my feature"`
5. **Push to your fork**: `git push origin feature/my-feature`
6. **Submit a pull request** with a description of your changes.

### Areas We're Looking For Help
- Improving the web dashboard UI/UX
- Adding more Baritone command support
- Optimizing bot performance
- Writing more comprehensive tests
- Improving documentation

---

## 📜 License
This project is licensed under the **MIT License**. See `LICENSE` for details.

---

## 📢 Support & Contact
For questions, bugs, or feature requests:
- **Open an Issue** on GitHub
- **Check existing discussions** before posting
- **Include logs and steps to reproduce** for bug reports
- **Be respectful and constructive** in all communications

---

## 🙏 Acknowledgments
- **Baritone** – Pathfinding and automation mod
- **Fabric** – Lightweight mod loader
- **Flask** – Web framework for the dashboard

Saturn bots use normal melee attacks against nearby visible living entities,
excluding the controller, discovered fleet bots, saved friends, teammates, and
creative/spectator players. Use Add friends / Remove friends in the dashboard
with comma-separated Minecraft usernames. The list is saved as
`botfury-friends.txt` beside the advanced-modules settings file.

Task defense can be toggled in the Bots roster. While enabled, managed bots punch
nearby unprotected living targets during mining, inventory and Baritone tasks.
Friends, the controller, known fleet bots, teammates and creative/spectator
players are protected. Defense uses normal reach, visibility and attack cooldowns,
and does not pursue enemies. Stop, task pause, or loss of the controller heartbeat
pauses these attacks. A damaged or unreadable friend list also pauses attacks.
Keep, Collect and Only seek matching drops within 32 blocks when a bot has
fewer than requested. If the closest drop is unreachable, the bot tries other
drops without breaking or placing blocks. After dropping excess, bots move away.

To update the runtime used by the direct launcher without moving game profiles,
set BOTFURY_RUNTIME_DIR or place the prepared runtime directory on one line in
config/client-runtime.path. Relaunch clients to load a new runtime. The mining
menu keeps its screen open with an actionable error if selected bots are outdated
or unavailable, instead of silently closing.

Drop defaults to all matching main-inventory items. On the inventory wheel, scroll
to choose a specific quantity; Keep/Collect/Bring retain their numeric defaults.
/bf inventory drop minecraft:diamond drops all; add a number for a partial drop.
The dashboard Drop button drops all matching items. Console drop <item> accepts
an optional numeric count or all. Equipped armor and offhand remain excluded.

With Defense enabled and packet movement allowed, active bots retreat from nearby
creepers and dodge predicted incoming persistent projectiles. Prediction accounts
for gravity, drag, movement and intervening blocks; grounded arrows are ignored.
Powered creepers receive a wider retreat distance. Each teleport uses the existing
four-block range, three-block vertical limit, block-top collision checks and
server-correction cooldown. A short pause prevents task movement immediately
undoing the dodge; Baritone resumes afterwards. No valid landing means no jump.
Stop and task pause suppress evasion. These checks cannot guarantee every dodge
under network latency or every anti-cheat configuration. No server mod is needed.

Project approaches, mining travel, and inventory delivery routes try a
short detour after about one minute without progress. For overlapping
fleet bots, the check can trigger after six seconds. The bot looks for
clear nearby ground, moves without breaking or placing blocks during
the detour, and returns to its assigned route. Stop cancels the detour.

### Project workers and structure builds

Use `/bf project workers` on the controller to enable project work on the
selected bots. Enrollment survives a disconnect or bot restart and resumes
when that bot joins the same server and dimension. Regular Stop clears
the saved enrollment. The normal launcher still controls when bots start
and connect. Workers claim jobs from the controller's local `/projects`
endpoint and use Baritone for approach, with incidental block breaking
and placement disabled while pathing.

Submit ordered jobs with a local HTTP POST to `/projects`. For `EXCAVATE` and
`VERIFY`, the job's `payload` is a JSON string containing `target` and `stand`
objects with integer `x`, `y`, `z` fields, and a `block` registry ID.
Excavation only removes the specified block type at the target, and an
already-clear target counts as finished. Verification checks a block ID
without changing it. Both jobs reserve the target, stand, and the blocks
above and below the stand. Excavation leaves block entities alone and
requires a suitable hotbar tool for blocks that require one.

For a small structure build, put a compressed vanilla `.nbt` or a WorldEdit
Sponge v2/v3 `.schem` file in the controller's `config/botfury-structures`
directory.
Run `/bf project build example.nbt 100 64 100` at a loaded site. The command
queues the project and enables workers on the selected bots. It plans
excavation for conflicting blocks, placements in support order, and a
verification pass after changes complete. The command reports the material
count. Stock the bots with the blocks they will place.

For `.schem` files, the coordinates specify the minimum corner. The planner
uses the block volume, including air, and leaves biomes untouched. Its
coordinates take precedence over the stored paste offset.

The vanilla importer uses a single palette. Both formats accept explicit
air, full single-state blocks, and full blocks with an axis, such as logs.
A horizontal log needs a reachable face on a solid neighboring block
along the requested axis. If the schematic provides no reachable support,
the bots can place temporary cobblestone in a reachable air cell over a
solid floor. They verify the cell is empty first and remove the cobblestone
after the pillar finishes or exhausts its retries. Supplied builds acquire
the extra cobblestone automatically. Verification checks the requested
axis, and the fleet replaces a log facing the wrong way.
The planner rejects entities, block entities, and plans over 256 jobs. Use an already prepared site with room for the
bots to walk to the stands. Vanilla chest jobs and explicit project jobs
remain available through the local API.

To gather and produce materials automatically, look at a dedicated single or
double chest outside the work area and run
`/bf project build example.nbt 100 64 100 supplied`. Place a reachable
crafting table or furnace near the chest for materials that need one. Give
workers suitable tools and unlock the crafting recipes. Workers gather,
produce, and deposit the required blocks, then builders collect them from
the chest. When multiple selected bots are online, the planner divides each
material into pickup batches. Each batch goes to the bot that collects it,
so different bots can place the same material on disjoint work sites. A bot
can take another batch of that material after its current placements finish.
The console's **Projects** and **Chest stock** controls show job progress
and the most recent stock observations.

If a supplied project's next step needs materials from an offline worker,
other workers can reacquire the materials after a 60-second grace period.
Each material chain can be reacquired twice. Completed placements and
unrelated material chains keep their progress.

Automatic supply uses the server's crafting and furnace recipes, including
intermediate ingredients, when it can trace them to supported raw resources.
For example, it can make glass from sand and bricks from clay. A material
with no supported source is reported before the project starts. The 256-job
limit includes production jobs. Keep reachable raw materials outside the
construction and chest area.

The coordinator keeps up to 32 projects available for scheduling or recent
inspection. When a new submission needs space, it moves the oldest finished
project to durable history, retaining job evidence and chest observations.
Repeating orders stay on the board until cancelled. Inspect archived IDs with
`GET /projects?history` or a full record with `GET /projects?history=PROJECT_ID`.

For a `TRANSFER` job, set `block` to `minecraft:chest` and add a
`direction` of `DEPOSIT`, `WITHDRAW` or `INSPECT` to the payload. Supply
`item` as an item registry ID and `stock` as the desired count in the
chest for deposits and withdrawals. A deposit tops the count up to at least
the target. A withdrawal takes excess until at most that count remains.
Inspect records the contents without moving items. Jobs share an exclusive
lock on the chest. For a double chest, use the left half as the target and
include the right half as the `partner` position. The controller's
`GET /projects?stock` shows last observed counts and timestamps, including
observed partial transfers. Counts describe the chest when the bot checked
it and can change when players or hoppers use it.


Production jobs use ordinary crafting tables and furnaces. A `CRAFT` or
`SMELT` payload uses the same target and stand coordinates as other jobs,
with `block` set to the workstation, `recipe` set to its server recipe ID,
and `count` set to the minimum output in the worker's inventory. Smelting
also takes `input` and `fuel` item IDs. A crafting recipe must be unlocked
on the bot. Use project workstations dedicated to the fleet.

Set a job's optional `sameWorkerAs` to one of its direct prerequisites when
the same bot must carry materials into the next step. For example, link a
chest withdrawal to smelting, crafting and a deposit. Workstations are
reserved while in use. A pinned continuation waits for the original bot
if it goes offline. Its inventory does not automatically move to another
bot. Structure builds still need an explicit production plan or stocked
workers.


Look at either half of a double chest or a single chest and run
`/bf output <item> [batch]`. For example, `/bf output iron_ingot 8` uses
a nearby furnace, and `/bf output oak_planks 16` uses a crafting table.
The default batch is 10. In the pie menu, open **Inventory → Output…**,
choose the item, scroll to set the batch size, and choose **Set output**.
Bots use familiar resource drops and compatible server crafting and furnace
recipes when the required workstation is nearby. For other items, bots use
what they carry or look for matching dropped items. Give workers suitable
tools and unlock their crafting recipes.

Selected bots produce batches in parallel and take turns depositing. A full
chest is checked again after space is freed. Use **Console → Chest stock**
to see the last observed counts, or look at either half of a marked chest and
run `/bf output cancel` to cancel the order.

Gathering widens its search after exhausting nearby ore. In an Overworld
with modern depth, diamond jobs search at Y -54 when they run out of
local candidates. Completion depends on reachable ore and tool condition.
Regular Stop disables worker mode. Look at the marked chest and run
`/bf output cancel` to cancel its persistent order.
