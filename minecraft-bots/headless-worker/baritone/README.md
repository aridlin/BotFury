# Baritone navigation in the headless engine

This engine build connects the Rust Baritone A* planner to the native Minecraft client's loaded chunk cache. The client executes the resulting waypoints through its existing movement loop. Navigation currently covers loaded routes on full blocks. It refuses to start a walk when the planner cannot reach the destination. Mining, scaffolding, swimming, parkour, and unloaded-chunk exploration are outside this adapter's current coverage.

Build the optional engine with:

```sh
sh prepare-engine.sh /path/to/new-build-dir
```

Pass the resulting `mcclient/target/release/mcclient` executable to the headless worker in place of its standard engine. The build script applies the Minecraft 26.3 protocol patch and pins both upstream sources. It retains the upstream source and license notices in the build directory.

The planner comes from [Horizon Baritone](https://github.com/luhnox/horizon-baritone/tree/41e1b720dc2028d06618cb8f7cf55f2697bba83c), an LGPL-3.0 Rust port of [Baritone](https://github.com/cabaletta/baritone). See its upstream `NOTICE.md`, `LICENSE`, and `COPYING` files. The engine uses [mcclient](https://github.com/MVRer/mcclient/tree/12179b06709e4d2161483e87065b35f292898783) for the Minecraft wire protocol and movement.
