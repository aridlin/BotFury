# Cooperative work in Baritone

The source patch here applies to cabaletta/baritone v1.10.1 (7487b90). It adds ordered goals with explicit completion and a travel goal that passes a snapshot of occupied cells into native A* movement evaluation. The patch retains Baritone's LGPL notices. Build it with JDK 17 from a BotFury checkout.

```sh
git clone --branch v1.10.1 https://github.com/cabaletta/baritone.git /tmp/botfury-baritone-src
git -C /tmp/botfury-baritone-src apply "$PWD/minecraft-bots/baritone/cooperative-work.patch"
(cd /tmp/botfury-baritone-src && sh gradlew --configure-on-demand -Pavailable_loaders=fabric :fabric:remapJar)
```

Use the remapped Fabric jar from `fabric/build/libs` (exclude the dev and dev-shadow jars). The BotFury client build accepts its absolute path via `-Dbotfury.baritoneJar=/absolute/path/to/the.jar`. The dashboard runtime also looks for `config/baritone-fabric.jar`. Existing installations can continue to use stock Baritone for individual jobs.

## Scheduling decisions

* The coordinator owns project dependencies, durable work leases, shared stock and exclusion zones. Lease a short, bounded set of actions whose targets and work stands can be reserved together. Keep cancellation, expiry and reassignment visible at that boundary.
* Each bot's mod owns its tools, safety and inventory. It can reuse a good stand, equip or craft a suitable tool, suspend for danger, and verify the expected block or inventory before reporting progress. If a worker dies or a reply is lost, reconcile with the world and the chest before repeating an action.
* Baritone owns reachable stands and movement. Supply alternative stands and peer reservations directly to its path search. Snapshot occupancy per search. A bot that cannot advance should report the obstruction and yield or replan. Only destructive work inside the active assignment should authorize changes to the world.

Scaffold recovery currently reserves up to eight steps together. Each observed step still goes back to the coordinator for an atomic completion and handoff. This is the first migration.

Alto Clef is a reference for local task composition and interruption. Its task runner selects the highest-priority active chain on each tick and notifies the prior chain when control changes. BotFury can apply that pattern inside a worker to pause an assignment for safety or material preparation, with fleet reservations and completion evidence owned by the coordinator. [Alto Clef task runner](https://github.com/gaucho-matrero/altoclef/blob/main/src/main/java/adris/altoclef/tasksystem/TaskRunner.java) · [Meloweh’s schematic and macro fork](https://github.com/Meloweh/altoclef/releases)

## Workstation access

A chest or table job needs a reachable interaction face. Its saved stand is a starting suggestion. The worker can test line of sight from its current position and pass several usable stands to one Baritone search. After walk-only routes fail near the station, the worker can open a short passage through permitted terrain with the tools it has. Protect the station, its support, reserved footing, occupied footing and exclusion zones. Treating every candidate floor as reserved can make the worker veto the path it just found.

For other kinds of work, keep three facts distinct when extending the native API: the outcome the world must confirm, the cells this worker may change, and the cells other workers own. In particular, a worker waiting for one chest interaction need only hold the transfer reservation while it can actually use the chest. After a disconnect during a transfer, read the chest and worker inventory before issuing another transfer.

## Next migrations and measurements

1. Measure elapsed ticks separately for tool preparation, travel, productive work, server confirmation, and waiting for the coordinator. Correlate by lease and step. Compare median and slowest workers on identical copied worlds. Include lost cargo and damage to protected blocks alongside throughput.
2. Give miners disjoint search areas and partial quantities against the same output request. Record carried and banked stock separately. A miner can return with valuables while another keeps exploring. Explicitly reserve chest access only during a transfer. Test two miners and one chest, a lost acknowledgment, and one miner dying before deposit.
3. Move local sequences into the lease payload. Let independent, observable block edits continue until a checkpoint, cancellation deadline, conflict or missing tool. Replay each outcome by lease and step. Reobserve the chest and inventory after uncertain transfers. Keep inventory handoffs as synchronization points.
4. Separate lease renewal from the full project snapshot. A small durable record keyed by lease and generation can preserve the promised expiry across restarts. Replay only matching leases, periodically checkpoint, and crash-test between the record write, checkpoint and log retirement. Measure journal bytes and time spent holding the queue lock before choosing the storage format.
