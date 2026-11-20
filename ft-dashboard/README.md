# BotFury dashboard

The FT dashboard runs in GUI, web, or terminal mode. Start it from
`ft-dashboard` with `sh run_dashboard.sh --web` on Linux or
`run_dashboard.bat` on Windows. Browser mode listens on
`http://127.0.0.1:8080/` by default.

## Linux containers with checkpoints

Use current rootful Podman, CRIU, and a CRIU-capable OCI runtime such as crun
or runc. TigerVNC Viewer is required for the **View** button. From
`ft-dashboard`, prepare the image once and start the dashboard from the
same terminal:

```sh
sudo -v
sh botfury-container.sh prepare
sh run_dashboard.sh --web --containers
```

On the **Instances** tab, choose **Start** for individual slots or
**Launch instances** for the batch. On its first start, each slot boots in
a temporary container, waits for Minecraft to reach the title screen, and
captures Minecraft and its virtual display as a local Podman checkpoint
image. The dashboard restores that image into a named container and
publishes the control and viewer ports on loopback. Later starts restore
the same checkpoint for that slot and username. A failed restore is
reported in the instance log.

- **Fleet → Connect all** joins the configured server from the restored
  title screens. The bundled client uses generated offline usernames.
  For loopback server addresses, container bots connect through Podman's
  host gateway. A Minecraft server on the same host must listen on an
  address reachable from the container bridge.
- **View** opens a TigerVNC window. **Auto tile windows** arranges open
  viewers on the X11 desktop. XWayland viewers require an EWMH-compatible
  compositor. **Tile windows** reapplies the layout manually.
- **Stop** gives the container five seconds to shut down. **Kill** ends the
  named container immediately. **Restart** stops it and restores the title
  screen checkpoint. Checkpoint images survive these operations.
- Containers survive dashboard exit. Click **Start** for the same slot to
  resume dashboard supervision of a running container. Re-run `sudo -v`
  in the launching terminal when the authorization has expired.
- Viewer access relies on the loopback-only port mapping. Use the locally built image and its own checkpoints,
  with a compatible host, kernel, and runtime. Checkpoint images contain
  process memory and can be large.

Checkpoint tags include the bot port, lowercase username, and the render
setting, for example `localhost/botfury-checkpoint:8765-bot01-true`.
After changing the image, stop the affected slot and remove its old
checkpoint image with Podman if you want its next start to capture the
new build. Changing the port, username, or render setting selects a
separate checkpoint. Host bot ports in this mode can be 1024–55535.
Each viewer uses the bot port plus 10000.

Instance logs are in `ft-dashboard/build/instance-logs`. If a slot fails to
start or restore, read its numbered log for the Podman or CRIU error.
The container engine documentation describes [checkpoint creation](https://docs.podman.io/en/latest/markdown/podman-container-checkpoint.1.html)
and [restoring checkpoint images](https://docs.podman.io/en/latest/markdown/podman-container-restore.1.html).

## Direct Java mode

Turn off **Podman checkpoints** to use local process groups on Linux or
Windows job objects. From `minecraft-bots/botfury-mod`, run
`sh ./gradlew prepareBotFuryClient` on Linux or
`gradlew.bat prepareBotFuryClient` on Windows. Use a Gradle-compatible
JDK 21 or 25 for this step. The preparation task resolves the Minecraft
runtime once. Each bot then starts with Java directly and gets its own game
directory and log. Re-run preparation after changing the mod or moving the
checkout.

Enable **Warm at title screen** to stage bots before using **Fleet →
Connect all**. Disable it for immediate auto-join. Ordinary dashboard exit
cleans up dashboard-owned local processes. External commands should keep
the game in their foreground process tree so the dashboard can manage it.

Custom commands support these placeholders.

| Placeholder | Meaning |
| --- | --- |
| `{username}` | Offline Minecraft username |
| `{port}` | BotFury HTTP port |
| `{bot_id}` | One-based launch slot |
| `{server_ip}` | Minecraft server hostname |
| `{server_port}` | Minecraft server port |
| `{disable_rendering}` | Whether to disable world rendering |
| `{auto_join}` | Whether to join immediately |
| `{cwd}` | Launch working directory |

For third-party commands, pass `-Dbotfury.port={port}` to the game JVM.
`-Dbotfury.strictPort=true` makes a bind failure visible on the assigned port.
