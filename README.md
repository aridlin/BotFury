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
- **Java 17 or higher** (for Fabric and Gradle)
- **Minecraft 1.20.1 + Fabric Loader**
- **Fabric API**
- **Baritone for Fabric**
- **Python 3.8+** (for the web dashboard)
- **Git** (for version control)

**Note**: Gradle is automatically downloaded via the build wrapper, so you don't need to install it separately.

### 📥 Clone the Repository
```bash
git clone https://github.com/IgorkotYT/BotFury.git
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
   
   The compiled JAR will be at: `minecraft-bots/botfury-mod/build/libs/botfury-0.1.0.jar`

3. **Install the Mod**  
   Copy the generated JAR into your Fabric Minecraft instance's `mods/` directory:
   ```bash
   cp minecraft-bots/botfury-mod/build/libs/botfury-0.1.0.jar ~/.minecraft/mods/
   ```
   
   Or simply download the latest JAR from [GitHub Releases](https://github.com/IgorkotYT/BotFury/releases).

### Primary Dashboard: FT

BotFury now includes an FT-based dashboard in `ft-dashboard/`. It replaces the
old Flask dashboard as the recommended control surface and supports:

- attaching and scanning BotFury HTTP endpoints
- launching multiple local instances from a Minecraft launch command
- generated offline usernames such as `Bot01`, `Bot02`, `Bot03`
- automatic attachment to launched ports
- optional auto-launch on dashboard startup

Run it on Windows:

```cmd
cd ft-dashboard
run_dashboard.bat
```

You can also double-click `ft-dashboard\run_dashboard.bat`; it starts the GUI
dashboard by default. Pass `--web` or `--tui` to use FT's other modes.

Run it on Linux:

```bash
cd ft-dashboard
sh run_dashboard.sh
```

When launched from bash or zsh, the Linux script starts web mode by default.
Then open `http://127.0.0.1:8080/`.

Configure `launch_command` in `config/botfury.json` or in the dashboard. This is
the OS command BotFury runs to start Minecraft for each bot. It supports
`{username}`, `{port}`, `{bot_id}`, `{server_ip}`,
`{server_port}`, `{disable_rendering}`, and `{cwd}`. Pass `-Dbotfury.port={port}`
to the JVM so each mod instance exposes a unique HTTP port, and pass
`{username}` wherever your offline launcher accepts the offline account name.
The dashboard includes platform wrapper presets. Windows uses
`ft-dashboard\botfury-launch.bat`; Linux uses `sh botfury-launch.sh`. Both run
the bundled Fabric dev client from `minecraft-bots\botfury-mod`, assign names
like `Bot01`, auto-join the configured server, and use separate run directories
like `run\Bot01`. The first launch can take a while while Gradle prepares
Minecraft and downloads assets. You can still edit the wrapper later if you
want to swap in Prism, the official launcher, or another Minecraft setup.

The bundled wrapper is tuned for low resource usage: rendering is disabled by
default, the client JVM is capped at 768 MB, the game sees one processor, Gradle
uses one worker, and each bot run directory gets minimal Minecraft video/audio
options before launch.

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
