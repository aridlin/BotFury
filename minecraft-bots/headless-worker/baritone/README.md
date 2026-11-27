# Baritone navigation in the headless engine

This engine build connects the Rust Baritone A* planner to the native Minecraft client's loaded chunk cache. The client executes the resulting waypoints through its existing movement loop. Navigation covers loaded routes on full blocks and supported thin snow layers. It refuses to start a walk when the planner cannot reach the destination. Navigation plans use loaded terrain. As the bot scouts, normal movement lets the server stream more chunks. Scaffolding, swimming, and parkour remain outside this adapter’s coverage.

For supported gathering jobs, Baritone can choose a reachable standing position from multiple candidate targets up to 12 blocks away in loaded terrain. When those searches run out, the worker can scout via short routes toward matching blocks it already sees farther away, then search again. The worker still checks exclusion zones for the target and standing position, checks for nearby players, and verifies that the server broke the requested block and the item entered the inventory. Baritone plans routes without digging through obstacles. The worker checks pickaxe requirements for supported ore jobs before breaking a block. Target searches check both the standing surface and the line to the block. This lets the worker dig from a neighboring ledge or beneath a tree and collect from the side when the rest of the trunk blocks the space. Inventory pickup clicks continue once the server updates both the slot and cursor.

Build the optional engine with:

```sh
sh prepare-engine.sh /path/to/new-build-dir
```

Pass the resulting `mcclient/target/release/mcclient` executable to the headless worker in place of its standard engine. The build script applies the Minecraft 26.3 protocol patch and pins both upstream sources. It retains the upstream source and license notices in the build directory.

The planner comes from [Horizon Baritone](https://github.com/luhnox/horizon-baritone/tree/41e1b720dc2028d06618cb8f7cf55f2697bba83c), an LGPL-3.0 Rust port of [Baritone](https://github.com/cabaletta/baritone). See its upstream `NOTICE.md`, `LICENSE`, and `COPYING` files. The engine uses [mcclient](https://github.com/MVRer/mcclient/tree/12179b06709e4d2161483e87065b35f292898783) for the Minecraft wire protocol and movement.
