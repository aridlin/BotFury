package com.botfury;

import com.google.gson.Gson;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** Reconciles a single chest to a stock target, so repeating a job does not replay a delta. */
final class ProjectChest {
    enum Direction { DEPOSIT, WITHDRAW, TAKE, INSPECT }
    record Spec(ProjectBlock location, Direction direction, Identifier item, int stock) {}
    record Result(boolean success, String detail, Map<String, Integer> stock) {}
    private record Payload(Direction direction, String item, Integer stock) {}
    private static final Gson JSON = new Gson();
    private static final long SETTLE = Duration.ofMillis(750).toNanos();
    private final Spec spec;
    private GenericContainerScreenHandler screen;
    private boolean opening;
    private long nextAction;

    ProjectChest(Spec spec) { this.spec = spec; }

    static Spec parse(String json) {
        ProjectBlock location = ProjectBlock.parse(json);
        Payload payload = JSON.fromJson(json, Payload.class);
        if (payload.direction == null || !location.block().equals(Identifier.of("minecraft", "chest"))) {
            throw new IllegalArgumentException("Transfer requires a single chest and direction");
        }
        if (payload.direction == Direction.INSPECT) return new Spec(location, payload.direction, null, 0);
        Identifier id = payload.item == null ? null : Identifier.tryParse(payload.item);
        if (id == null || !Registries.ITEM.containsId(id) || id.equals(Identifier.of("minecraft", "air"))
                || payload.stock == null || payload.stock < 0 || payload.stock > 1728) {
            throw new IllegalArgumentException("Invalid chest item or stock target");
        }
        return new Spec(location, payload.direction, id, payload.stock);
    }

    void close(MinecraftClient client) {
        if (client.player != null && (screen != null && client.player.currentScreenHandler == screen
                || opening && client.player.currentScreenHandler instanceof GenericContainerScreenHandler)) {
            client.player.closeHandledScreen();
        }
        screen = null;
        opening = false;
    }

    Result tick(MinecraftClient client, long now) {
        if (now < nextAction) return null;
        var target = spec.location.target();
        var block = client.world.getBlockState(target);
        if (!block.isOf(Blocks.CHEST) || block.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            return failure("Storage location must contain a single chest");
        }
        var player = client.player;
        if (screen == null && player.currentScreenHandler instanceof GenericContainerScreenHandler container
                && opening && container.getRows() == 3) {
            screen = container;
            opening = false;
        } else if (screen == null) {
            if (player.currentScreenHandler != player.playerScreenHandler) return failure("Another container is open");
            var eye = player.getEyePos();
            var center = Vec3d.ofCenter(target);
            var hit = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            double reach = client.interactionManager.getReachDistance() - .1;
            if (eye.squaredDistanceTo(center) > reach * reach || hit.getType() != HitResult.Type.BLOCK
                    || !target.equals(hit.getBlockPos())) return failure("Chest obstructed or out of reach");
            opening = true;
            player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, center);
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            nextAction = now + SETTLE;
            return null;
        }
        if (player.currentScreenHandler != screen || !screen.getCursorStack().isEmpty()) {
            return failure("Chest screen changed or inventory cursor is occupied");
        }

        Map<String, Integer> stock = stock();
        if (spec.direction == Direction.INSPECT) return new Result(true, "Observed chest stock", stock);
        int held = stock.getOrDefault(spec.item.toString(), 0);
        boolean deposit = spec.direction == Direction.DEPOSIT;
        int containerSize = screen.getRows() * 9;
        int carried = 0;
        if (spec.direction == Direction.TAKE) {
            for (int i = containerSize; i < screen.slots.size(); i++) {
                var stack = screen.getSlot(i).getStack();
                if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).equals(spec.item)) carried += stack.getCount();
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
        int fromStart = deposit ? containerSize : 0;
        int fromEnd = deposit ? screen.slots.size() : containerSize;
        for (int from = fromStart; from < fromEnd; from++) {
            var source = screen.getSlot(from);
            ItemStack stack = source.getStack();
            if (stack.isEmpty() || !Registries.ITEM.getId(stack.getItem()).equals(spec.item)
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
        return new Result(false, "Missing " + spec.item + " or destination is full", stock);
    }

    private void click(MinecraftClient client, int slot, int button) {
        client.interactionManager.clickSlot(screen.syncId, slot, button, SlotActionType.PICKUP, client.player);
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
