# BotFury FT Dashboard

This is the primary BotFury dashboard. It uses the combined `ft.hpp` header from
`aridlin/ft` and starts through FT's normal mode selection. On Windows desktop
that means GUI by default. On Linux, `run_dashboard.sh` starts web mode by
default when launched from bash or zsh. Pass `--gui`, `--web`, or `--tui` when
you want a specific mode.

## Build And Run

Windows:

```bat
cd ft-dashboard
run_dashboard.bat
```

You can also double-click `run_dashboard.bat` or `build\botfury-dashboard.exe`.

Linux:

```sh
cd ft-dashboard
sh run_dashboard.sh
```

Linux shell launches default to browser mode from bash/zsh. Open
`http://127.0.0.1:8080/` unless `config/botfury.json` sets another
`dashboard_port`. Pass `--no-open` when you want web mode to start without
opening a browser.

## Minecraft Launch Command

The default Windows preset runs `botfury-launch.bat`; the default Linux preset
runs `sh botfury-launch.sh`. Both start the bundled Fabric dev client from
`minecraft-bots/botfury-mod`. They pass each generated offline username into
Minecraft, give each bot a unique BotFury HTTP port, auto-join the configured
server, and use a separate run directory such as `run\Bot01` or `run/Bot01`.

You can still replace the command in the dashboard or in `config/botfury.json`
when you want to use Prism, the official launcher, or another Minecraft setup.
The command can use these placeholders:

| Placeholder | Meaning |
| --- | --- |
| `{username}` | Generated offline username, such as `Bot01` |
| `{port}` | BotFury HTTP port for this instance |
| `{bot_id}` | 1-based launch slot |
| `{server_ip}` | Configured Minecraft server host |
| `{server_port}` | Configured Minecraft server port |
| `{disable_rendering}` | `true` or `false` from dashboard settings |
| `{cwd}` | Configured launch working directory |

The dashboard includes presets. The platform wrapper is the default and works
against this repo. The first launch may take a while while Gradle prepares
Minecraft and downloads assets.

The default wrapper is tuned for bots, not visuals. It caps the client JVM at
768 MB, exposes one processor to the game JVM, runs the dashboard-launched
process tree at below-normal priority, disables rendering on start, and writes
minimal Minecraft options into each per-bot run directory before launch.

The command should pass the port to the mod, usually as:

```text
-Dbotfury.port={port}
```

For custom launchers, pass `{username}` wherever your launcher accepts an
offline account name, and optionally mirror it to the mod for status display:

```text
-Dbotfury.offlineUsername={username}
```

The dashboard can auto-attach launched bots at `127.0.0.1:{port}`. If a bot is
still booting, it will appear offline until the mod HTTP server becomes
reachable.
