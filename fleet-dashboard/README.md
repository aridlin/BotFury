# Fleet Command

A shared web console for a BotFury fleet. Run the hub on the ProLiant or another always-on host, then open the same URL from each operator's browser. The hub stores professions and their runs in SQLite. Each host has its own agent and runs its own bot processes.

## Hub and worker agents

Install Flask and Waitress, then run the same entry point on each machine.

```sh
python3 dashboard.py hub.json --state ./hub-state --production
python3 dashboard.py agent.json --state ./agent-state --production
```

A hub config contains its listen port, a list of hosts, and optional project coordinators.

```json
{
  "role": "hub",
  "port": 18081,
  "hosts": [
    {"id": "proliant", "name": "ProLiant", "port": 18084, "token": "REPLACE_WITH_A_RANDOM_TOKEN"},
    {"id": "laptop", "name": "Laptop", "port": 18086, "token": "REPLACE_WITH_A_DIFFERENT_RANDOM_TOKEN"}
  ],
  "coordinators": {"my-world.example:25565": 8764}
}
```

An agent config lists its worker slots. The command array starts a locally installed managed worker at the listed port. Use unique Minecraft usernames across the fleet.

```json
{
  "role": "agent",
  "port": 18086,
  "token": "REPLACE_WITH_A_DIFFERENT_RANDOM_TOKEN",
  "projectPorts": {"my-world.example:25565": 18764},
  "slots": [
    {
      "id": "bot26",
      "name": "Bot26",
      "port": 8790,
      "argv": ["sh", "/absolute/path/to/botfury-launch.sh", "Bot26", "8790", "localhost", "25565", "true", "false"],
      "cwd": "/absolute/path/to/BotFury"
    }
  ]
}
```

Use at least 32 random characters for every token and restrict read access to config files. Listeners bind to loopback. On the laptop, a reverse SSH forward such as `ssh -N -o ExitOnForwardFailure=yes -R 127.0.0.1:18086:127.0.0.1:18086 proliant` makes the agent available to the hub. Add a local forward for the coordinator when the agent's project port points at a remote broker. Keep these forwards private. Put the hub behind an authenticated reverse proxy. For a path such as `/botfury/`, redirect the bare path to the trailing slash and strip the prefix before forwarding.

The console currently uses the managed Java worker's `/status` and `/command` API. Agents can show separately launched workers listening on their configured ports. Start and Stop instances apply to supervised processes. Different slot selections can connect to different Minecraft servers at the same time. Select a server and dimension on the map to see its workers. Map positions, active Baritone routes, inventories and chat are reported by the clients.

## Professions

Save a server and an ordered list of existing, finite BotFury project IDs. Select workers, open the saved profession and choose **Dispatch to selected**. The hub starts idle slots, connects them, and attaches them to each project in turn. It advances when the coordinator reports that every job in the current project finished. It tracks progress across dashboard restarts. The coordinator and the participating agents need to remain available for work to continue when the desktop is off. Independent servers can run their own professions concurrently.

**Stop dispatch** ends the hub's assignment loop. A worker can finish its current assignment independently. **Halt task** stops the selected worker's in-game task.

**Set bed spawn** sends selected workers in the current world to an existing reachable Overworld bed. The inspector reports confirmation from Minecraft.

### Server login and registration

For a server running an AuthMe-style plugin, give each opted-in managed worker a private properties file and put its absolute path in the worker slot's `authFile`. Keep the file readable only by the agent user. Configure the exact server address used in Connect and the exact system messages shown by your plugin. Omit the prompt for any operation that should be disabled.

```properties
server=my-world.example:25565
loginPrompt=Replace this with the server's exact login prompt
registerPrompt=Replace this with the server's exact registration prompt
password=ReplaceWithAUniqueWorkerPassword
```

The Java client sends each configured command at most once per connection. The hub only sees the resulting telemetry and redacted chat. Servers requiring Microsoft accounts still require their normal account authentication.

## Project direction

- Keep the Java master compatible with mixed-host fleets of up to 100 workers, and put the latest-release JVM-free worker on the same supervisor and telemetry contract. Prove five native workers on the ProLiant before a wider rollout.
- Measure time between confirmed actions and remove avoidable waits while preserving shared claims, inventory evidence and task recovery.
- Make professions self-contained. The survival acceptance run starts from empty inventories and finds a safe base about 300 blocks from spawn, remembers it, builds a hut and farm, supplies shared chests with wood, iron and diamonds, and crafts and places beds. Verify each worker's respawn and the completed farm in Minecraft while the desktop is offline.
- Add Minecraft-style terrain tiles from chunks workers actually receive, with persisted exploration per server/dimension and freshness per tile. In the Dither Press interface, draw paths, claims, buildings, stockpiles and squad orders above the terrain. A server-owner Dynmap feed can supply an optional terrain layer.
- Build the RTS view in stages. First prove box selection, squads, waypoints, rally, hold and retreat. Then show server-tracked mobs and players as contacts, with last-seen markers. Add direct in-game attack orders for servers where fleet play is permitted.
