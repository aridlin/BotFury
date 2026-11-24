package com.botfury;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Reconciles a chest to a stock target, so repeating a job does not replay a delta. */
final class ProjectChest {
    record Storage(BlockPos key, BlockPos other) {
        int slots() { return other == null ? 27 : 54; }

        ProjectBlock approach(MinecraftClient client, Predicate<BlockPos> allowed) {
            var stand = StructureProject.chooseStand(client, key, Map.of(), null, pos -> {
                if (!allowed.test(pos)) return false;
                var eye = Vec3d.ofBottomCenter(pos).add(0, 1.62, 0);
                return ProjectProduction.workstationHit(client, key, eye) != null;
            });
            return stand == null ? null : new ProjectBlock(key, stand, Identifier.of("minecraft", "chest"));
        }

        ProjectGather.Area include(ProjectGather.Area area) {
            var expanded = StructureSupply.include(area, key);
            return other == null ? expanded : StructureSupply.include(expanded, other);
        }

        void addPartner(JsonObject payload) {
            if (other != null) payload.add("partner", JSON.toJsonTree(StructureSupply.position(other)));
        }
    }

    static Storage aimedStorage(MinecraftClient client, HitResult aim) {
        if (!(aim instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new IllegalArgumentException("Look at a chest");
        }
        var pos = hit.getBlockPos();
        var state = client.world.getBlockState(pos);
        if (!state.isOf(Blocks.CHEST)) throw new IllegalArgumentException("Look at a chest");
        var type = state.get(ChestBlock.CHEST_TYPE);
        if (type == ChestType.SINGLE) return new Storage(pos, null);
        var next = pos.offset(ChestBlock.getFacing(state));
        var other = client.world.getBlockState(next);
        if (!other.isOf(Blocks.CHEST) || other.get(ChestBlock.CHEST_TYPE) != (type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT)
                || !other.get(ChestBlock.FACING).equals(state.get(ChestBlock.FACING))
                || !next.offset(ChestBlock.getFacing(other)).equals(pos)) {
            throw new IllegalArgumentException("Load both halves of the double chest");
        }
        return type == ChestType.LEFT ? new Storage(pos, next) : new Storage(next, pos);
    }

    enum Direction { DEPOSIT, WITHDRAW, TAKE, INSPECT, VERIFY_STOCK }
    record Spec(ProjectBlock location, Direction direction, ProjectItem item, int stock, BlockPos partner, int stockReserve) {
        ProjectQueue.Work reserve(ProjectQueue.Work work) {
            var reserved = location.reserve(work);
            if (partner == null) return reserved;
            var locks = new HashSet<>(reserved.locks());
            locks.add(ProjectBlock.key(partner));
            return new ProjectQueue.Work(work.id(), work.kind(), work.payload(), work.needs(), locks, work.sameWorkerAs(),
                    work.cleanup(), work.constructionMaterial());
        }
    }
    record Result(boolean success, String detail, Map<String, Integer> stock, Integer free, boolean pickupShortfall) {
        Result(boolean success, String detail, Map<String, Integer> stock) { this(success, detail, stock, null, false); }
    }
    private record Payload(Direction direction, String item, Integer stock, ProjectBlock.Position partner, Integer stockReserve) {}
    private static final Gson JSON = new Gson();
    private static final long SETTLE = Duration.ofMillis(750).toNanos();
    private final Spec spec;
    private final int reserve;
    private GenericContainerScreenHandler screen;
    private boolean opening;
    private long nextAction;

    ProjectChest(Spec spec) { this(spec, 0); }

    ProjectChest(Spec spec, int reserve) {
        this.spec = spec;
        this.reserve = Math.max(reserve, spec.stockReserve);
    }

    boolean depositing() { return spec.direction == Direction.DEPOSIT; }

    static Spec parse(String json) {
        ProjectBlock location = ProjectBlock.parse(json);
        Payload payload = JSON.fromJson(json, Payload.class);
        if (payload.direction == null || !location.block().equals(Identifier.of("minecraft", "chest"))) {
            throw new IllegalArgumentException("Transfer requires a chest and direction");
        }
        var partner = payload.partner == null ? null : payload.partner.checked();
        if (partner != null && (partner.getY() != location.target().getY()
                || partner.getManhattanDistance(location.target()) != 1)) {
            throw new IllegalArgumentException("Invalid double chest position");
        }
        if (payload.direction == Direction.INSPECT && payload.item == null) {
            return new Spec(location, payload.direction, null, 0, partner, 0);
        }
        var item = ProjectItem.parse(payload.item);
        if (payload.direction != Direction.INSPECT
                && (payload.stock == null || payload.stock < 0 || payload.stock > (partner == null ? 1728 : 3456))) {
            throw new IllegalArgumentException("Invalid chest item or stock target");
        }
        int reserve = payload.stockReserve == null ? 0 : payload.stockReserve;
        if (reserve < 0 || reserve > (partner == null ? 1728 : 3456)) {
            throw new IllegalArgumentException("Invalid chest stock reserve");
        }
        return new Spec(location, payload.direction, item, payload.stock == null ? 0 : payload.stock, partner, reserve);
    }

    void close(MinecraftClient client) {
        if (client.player != null && (screen != null && client.player.currentScreenHandler == screen
                || opening && client.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
            client.player.closeHandledScreen();
        }
        screen = null;
        opening = false;
    }

    boolean reachableFromHere(MinecraftClient client) {
        if (screen != null && client.player.currentScreenHandler == screen) return true;
        return !client.player.isTouchingWater() && !client.player.isClimbing()
                && ProjectProduction.workstationHit(client, spec.location.target(), client.player.getEyePos()) != null;
    }

    static BlockPos replacementStand(MinecraftClient client, Spec spec, BlockPos carrier) {
        var target = spec.location.target();
        var oldStand = spec.location.stand();
        var world = client.world;
        if (!world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)
                || !world.isChunkLoaded(oldStand.getX() >> 4, oldStand.getZ() >> 4)
                || !matchesStorage(world.getBlockState(target), spec)
                || usableStand(client, target, oldStand)) return null;
        BlockPos best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (var pos : BlockPos.iterateOutwards(target, 4, 4, 4)) {
            if (!usableStand(client, target, pos)) continue;
            double distance = pos.getSquaredDistance(carrier);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pos.toImmutable();
            }
        }
        return best;
    }

    private static boolean usableStand(MinecraftClient client, BlockPos target, BlockPos pos) {
        return ProjectBlock.validStand(target, pos) && client.world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                && ProjectWorker.usableInteractionStand(client, pos, target)
                && ProjectProduction.workstationHit(client, target, Vec3d.ofBottomCenter(pos).add(0, 1.62, 0)) != null;
    }

    private static boolean matchesStorage(net.minecraft.block.BlockState block, Spec spec) {
        return block.isOf(Blocks.CHEST)
                && (spec.partner == null) == (block.get(ChestBlock.CHEST_TYPE) == ChestType.SINGLE)
                && (spec.partner == null || spec.partner.equals(spec.location.target().offset(ChestBlock.getFacing(block))));
    }

    Result tick(MinecraftClient client, long now) {
        if (now < nextAction) return null;
        var target = spec.location.target();
        var block = client.world.getBlockState(target);
        if (!matchesStorage(block, spec)) {
            return failure("Storage chest changed size or location");
        }
        var player = client.player;
        if (screen == null && player.currentScreenHandler instanceof GenericContainerScreenHandler container
                && opening && container.getRows() == (spec.partner == null ? 3 : 6)) {
            screen = container;
            opening = false;
        } else if (screen == null) {
            if (player.currentScreenHandler != player.playerScreenHandler) return failure("Another container is open");
            var hit = ProjectProduction.workstationHit(client, target, player.getEyePos());
            if (hit == null) return failure("Chest obstructed or out of reach");
            opening = true;
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, hit.getPos());
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            nextAction = now + SETTLE;
            return null;
        }
        if (player.currentScreenHandler != screen || !screen.getCursorStack().isEmpty()) {
            return failure("Chest screen changed or inventory cursor is occupied");
        }

        Map<String, Integer> stock = stock();
        if (spec.direction == Direction.INSPECT) return new Result(true, "Observed chest stock", stock, freeSlots(), false);
        int held = spec.item.count(stock);
        if (spec.direction == Direction.VERIFY_STOCK) {
            return new Result(held >= spec.stock, "Observed " + held + "/" + spec.stock + " " + spec.item + " in chest", stock);
        }
        boolean deposit = spec.direction == Direction.DEPOSIT;
        int containerSize = screen.getRows() * 9;
        int carried = 0;
        if (deposit || spec.direction == Direction.TAKE) {
            for (int i = containerSize; i < screen.slots.size(); i++) {
                var stack = screen.getSlot(i).getStack();
                if (spec.item.matches(stack)) carried += stack.getCount();
            }
        }
        int remaining = switch (spec.direction) {
            case DEPOSIT -> spec.stock - held;
            case TAKE -> spec.stock - carried;
            default -> held - spec.stock;
        };
        if (remaining <= 0) {
            String observation = spec.direction == Direction.TAKE
                    ? "Observed " + carried + " " + spec.item + " in worker inventory"
                    : "Observed " + held + " " + spec.item + " in chest";
            return new Result(true, observation, stock);
        }
        if (spec.direction == Direction.TAKE && reserve > 0 && held - remaining < reserve) {
            return new Result(false, "Chest no longer has enough unreserved " + spec.item, stock);
        }
        int fromStart = deposit ? containerSize : 0;
        int fromEnd = deposit ? screen.slots.size() : containerSize;
        for (int from = fromStart; from < fromEnd; from++) {
            var source = screen.getSlot(from);
            ItemStack stack = source.getStack();
            if (!spec.item.matches(stack)
                    || !source.canTakeItems(player)) continue;
            for (int to = deposit ? 0 : containerSize; to < (deposit ? containerSize : screen.slots.size()); to++) {
                var destination = screen.getSlot(to);
                var present = destination.getStack();
                if (!destination.canInsert(stack) || !present.isEmpty() && !ItemStack.canCombine(stack, present)) continue;
                int capacity = destination.getMaxItemCount(stack) - present.getCount();
                int move = Math.min(remaining, Math.min(capacity, stack.getCount()));
                if (move <= 0) continue;
                click(client, from, 0);
                if (move == stack.getCount()) click(client, to, 0);
                else {
                    for (int i = 0; i < Math.min(move, 8); i++) click(client, to, 1);
                    click(client, from, 0);
                }
                nextAction = now + SETTLE;
                return null;
            }
        }
        if (spec.direction == Direction.TAKE && held < remaining) {
            return new Result(false, "Chest has " + held + " " + spec.item + ", worker needs " + remaining,
                    stock, null, true);
        }
        if (deposit && carried < remaining && hasDeliverySpace()) {
            return new Result(false, "Delivery is short by " + (remaining - carried) + " " + spec.item,
                    stock, null, true);
        }
        return new Result(false, "Missing " + spec.item + " or destination is full", stock);
    }

    private boolean hasDeliverySpace() {
        for (var id : spec.item.ids()) {
            if (freeSlots(Registries.ITEM.get(id).getDefaultStack()) > 0) return true;
        }
        return false;
    }

    private void click(MinecraftClient client, int slot, int button) {
        client.interactionManager.clickSlot(screen.syncId, slot, button, SlotActionType.PICKUP, client.player);
    }

    private Integer freeSlots() {
        if (spec.item == null || spec.item.grouped()) return null;
        return freeSlots(spec.item.sample().getDefaultStack());
    }

    private int freeSlots(ItemStack sample) {
        int remaining = 0;
        for (int i = 0; i < screen.getRows() * 9; i++) {
            var slot = screen.getSlot(i);
            var stack = slot.getStack();
            if (slot.canInsert(sample) && (stack.isEmpty() || ItemStack.canCombine(stack, sample))) {
                remaining += Math.max(0, slot.getMaxItemCount(sample) - stack.getCount());
            }
        }
        return remaining;
    }

    private Map<String, Integer> stock() {
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < screen.getRows() * 9; i++) {
            var stack = screen.getSlot(i).getStack();
            if (!stack.isEmpty()) counts.merge(Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    private Result failure(String reason) { return new Result(false, reason, null); }
}
