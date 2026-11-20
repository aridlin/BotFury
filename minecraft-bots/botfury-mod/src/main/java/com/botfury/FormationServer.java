package com.botfury;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FormationServer implements ModInitializer {
    static final Identifier CHANNEL = new Identifier("botfury", "formation");
    static final int SATURN = 0;
    static final int STRIKE = 1;
    static final int STOP = 2;
    private static final int MAX_BOTS = 32;
    private static final double MAX_COMMAND_DISTANCE_SQUARED = 32 * 32;
    private final Map<MinecraftServer, Fleet> fleets = new HashMap<>();

    private static final class Fleet {
        final Set<UUID> enrolled = new LinkedHashSet<>();
        final Map<UUID, Vec3d> strikeOrigins = new HashMap<>();
        UUID owner;
        LivingEntity target;
        int age;

        void stop() {
            owner = null;
            target = null;
            strikeOrigins.clear();
            age = 0;
        }
    }

    @Override
    public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buffer, sender) -> {
            if (!buffer.isReadable()) return;
            int action = buffer.readUnsignedByte();
            if (action > STOP || buffer.readableBytes() != (action == STRIKE ? Integer.BYTES : 0)) return;
            int targetId = action == STRIKE ? buffer.readInt() : -1;
            server.execute(() -> control(server, player, action, targetId));
        });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(fleets::remove);
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                dispatcher.register(CommandManager.literal("botfury")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("enroll")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> changeEnrollment(context.getSource().getServer(),
                                                EntityArgumentType.getPlayer(context, "bot"), true,
                                                message -> context.getSource().sendFeedback(() -> message, false)))))
                        .then(CommandManager.literal("release")
                                .then(CommandManager.argument("bot", EntityArgumentType.player())
                                        .executes(context -> changeEnrollment(context.getSource().getServer(),
                                                EntityArgumentType.getPlayer(context, "bot"), false,
                                                message -> context.getSource().sendFeedback(() -> message, false)))))
                        .then(CommandManager.literal("list").executes(context -> {
                            MinecraftServer server = context.getSource().getServer();
                            Fleet fleet = fleets.get(server);
                            List<String> online = fleet == null ? List.of() : fleet.enrolled.stream()
                                    .map(server.getPlayerManager()::getPlayer)
                                    .filter(java.util.Objects::nonNull)
                                    .map(player -> player.getName().getString())
                                    .toList();
                            context.getSource().sendFeedback(() -> Text.literal(online.isEmpty()
                                    ? "No enrolled bots online." : String.join(", ", online)), false);
                            return online.size();
                        }))
                        .then(CommandManager.literal("clear").executes(context -> {
                            fleets.remove(context.getSource().getServer());
                            context.getSource().sendFeedback(() -> Text.literal("BotFury roster cleared."), false);
                            return 1;
                        }))));
    }

    private int changeEnrollment(MinecraftServer server, ServerPlayerEntity bot, boolean enroll,
                                 java.util.function.Consumer<Text> reply) {
        Fleet fleet = fleets.computeIfAbsent(server, ignored -> new Fleet());
        if (enroll && (bot.hasPermissionLevel(2) || fleet.enrolled.size() >= MAX_BOTS
                && !fleet.enrolled.contains(bot.getUuid()))) {
            reply.accept(Text.literal("Use a non-operator bot account. The roster limit is " + MAX_BOTS + "."));
            return 0;
        }
        if (enroll) fleet.enrolled.add(bot.getUuid());
        else fleet.enrolled.remove(bot.getUuid());
        reply.accept(Text.literal((enroll ? "Enrolled " : "Released ") + bot.getName().getString()));
        return 1;
    }

    private void control(MinecraftServer server, ServerPlayerEntity owner, int action, int targetId) {
        if (!owner.hasPermissionLevel(2) || owner.isSpectator() || !owner.isAlive() || server.getPlayerManager().getPlayer(owner.getUuid()) != owner) return;
        Fleet fleet = fleets.get(server);
        if (action == STOP) {
            if (fleet != null) fleet.stop();
            owner.sendMessage(Text.literal("BotFury formation stopped."), true);
            return;
        }
        if (fleet == null || fleet.enrolled.isEmpty()) {
            owner.sendMessage(Text.literal("Enroll bots with /botfury enroll <player>."), true);
            return;
        }
        ServerWorld world = owner.getServerWorld();
        List<ServerPlayerEntity> bots = world.getPlayers(bot -> isAvailable(fleet, owner, bot));
        if (bots.isEmpty()) {
            owner.sendMessage(Text.literal("No enrolled bots in this world within 32 blocks."), true);
            return;
        }
        LivingEntity target = null;
        if (action == STRIKE) {
            Entity selected = world.getEntityById(targetId);
            if (!(selected instanceof LivingEntity living) || bots.stream().noneMatch(bot -> canAttack(fleet, owner, bot, living))
                    || owner.squaredDistanceTo(living) > MAX_COMMAND_DISTANCE_SQUARED || !owner.canSee(living)) {
                owner.sendMessage(Text.literal("Choose a visible target within 32 blocks that bots can attack."), true);
                return;
            }
            target = living;
        }
        fleet.stop();
        fleet.owner = owner.getUuid();
        fleet.target = target;
        if (target != null) bots.forEach(bot -> fleet.strikeOrigins.put(bot.getUuid(), bot.getPos()));
        owner.sendMessage(Text.literal(action == SATURN ? "Saturn active." : "Lightning strike active."), true);
    }

    private static boolean isAvailable(Fleet fleet, ServerPlayerEntity owner, ServerPlayerEntity bot) {
        return fleet.enrolled.contains(bot.getUuid()) && bot.isAlive() && !bot.isSpectator()
                && !bot.hasPermissionLevel(2) && !bot.hasVehicle()
                && bot.squaredDistanceTo(owner) <= MAX_COMMAND_DISTANCE_SQUARED;
    }

    private void tick(MinecraftServer server) {
        Fleet fleet = fleets.get(server);
        if (fleet == null || fleet.owner == null) return;
        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(fleet.owner);
        LivingEntity target = fleet.target;
        if (owner == null || !owner.isAlive() || owner.isSpectator() || !owner.hasPermissionLevel(2)
                || (target != null && (!target.isAlive() || target.getWorld() != owner.getWorld()
                || target.squaredDistanceTo(owner) > MAX_COMMAND_DISTANCE_SQUARED || fleet.age >= 200))) {
            fleet.stop();
            return;
        }

        int age = fleet.age++;
        if (age % 4 != 0) return;
        ServerWorld world = owner.getServerWorld();
        List<ServerPlayerEntity> bots = world.getPlayers(bot -> isAvailable(fleet, owner, bot)).stream()
                .sorted(Comparator.comparing(Entity::getUuid)).toList();
        for (int index = 0; index < bots.size(); ++index) {
            ServerPlayerEntity bot = bots.get(index);
            if (target == null) {
                int ring = index / 8;
                int ringSize = Math.min(8, bots.size() - ring * 8);
                double angle = age * 0.08 * (ring % 2 == 0 ? 1 : -1)
                        + (index % 8) * 2 * Math.PI / ringSize;
                double radius = 3.5 + 2 * ring;
                move(world, bot, owner.getPos().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                LivingEntity nearby = world.getEntitiesByClass(LivingEntity.class, bot.getBoundingBox().expand(3),
                                entity -> canAttack(fleet, owner, bot, entity) && bot.canSee(entity)).stream()
                        .min(Comparator.comparingDouble(bot::squaredDistanceTo)).orElse(null);
                attack(fleet, owner, bot, nearby);
            } else if (fleet.strikeOrigins.containsKey(bot.getUuid())) {
                if (!canAttack(fleet, owner, bot, target)) continue;
                if (age < 20) {
                    Vec3d start = fleet.strikeOrigins.get(bot.getUuid());
                    Vec3d vector = target.getPos().subtract(start);
                    Vec3d side = new Vec3d(-vector.z, 0, vector.x).normalize();
                    double offset = (age % 8 == 0 ? 1 : -1) * (2.4 - age * 0.09);
                    strikeMove(world, bot, start.lerp(target.getPos(), (age + 4) / 24.0).add(side.multiply(offset)));
                } else {
                    double angle = world.random.nextDouble() * 2 * Math.PI;
                    double radius = 1.6 + world.random.nextDouble() * 0.9;
                    strikeMove(world, bot, target.getPos().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius));
                    attack(fleet, owner, bot, target);
                }
            }
        }
    }

    private static boolean canAttack(Fleet fleet, ServerPlayerEntity owner, ServerPlayerEntity bot,
                                     LivingEntity target) {
        if (target == owner || !target.isAlive() || target.isSpectator() || target.isTeammate(bot)) return false;
        if (target instanceof ServerPlayerEntity player) {
            return !fleet.enrolled.contains(player.getUuid()) && !player.isCreative()
                    && bot.getServerWorld().getServer().isPvpEnabled() && bot.shouldDamagePlayer(player);
        }
        return target instanceof MobEntity;
    }

    private static void attack(Fleet fleet, ServerPlayerEntity owner, ServerPlayerEntity bot, LivingEntity target) {
        if (target == null || !canAttack(fleet, owner, bot, target) || bot.squaredDistanceTo(target) > 9
                || !bot.canSee(target) || bot.getAttackCooldownProgress(0.5f) < 0.95f) return;
        bot.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
        bot.attack(target);
        bot.swingHand(Hand.MAIN_HAND);
    }

    private static void strikeMove(ServerWorld world, ServerPlayerEntity bot, Vec3d desired) {
        Vec3d from = bot.getPos().add(0, 1, 0);
        move(world, bot, desired);
        Vec3d to = bot.getPos().add(0, 1, 0);
        double distance = from.distanceTo(to);
        if (distance < 0.1) return;

        int samples = Math.min(10, (int) Math.ceil(distance * 2));
        for (int point = 1; point <= samples; ++point) {
            Vec3d position = from.lerp(to, point / (double) samples);
            world.spawnParticles(ParticleTypes.END_ROD, position.x, position.y, position.z,
                    1, 0.02, 0.02, 0.02, 0);
        }
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, to.x, to.y, to.z, 10, 0.3, 0.55, 0.3, 0.05);
    }

    private static void move(ServerWorld world, ServerPlayerEntity bot, Vec3d desired) {
        BlockPos center = BlockPos.ofFloored(desired);
        for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3}) {
            BlockPos feet = center.up(dy);
            if (feet.getY() <= world.getBottomY() || feet.getY() + 1 >= world.getTopY()
                    || !world.isChunkLoaded(feet.getX() >> 4, feet.getZ() >> 4)
                    || !world.getWorldBorder().contains(feet)) continue;
            BlockPos floor = feet.down();
            BlockState below = world.getBlockState(floor);
            if (!below.isSideSolidFullSquare(world, floor, Direction.UP)
                    || !world.getFluidState(feet).isEmpty() || !world.getFluidState(feet.up()).isEmpty()) continue;
            Vec3d position = Vec3d.ofBottomCenter(feet);
            if (bot.squaredDistanceTo(position) < 0.01) return;
            if (!world.isSpaceEmpty(bot, bot.getBoundingBox().offset(position.subtract(bot.getPos())))
                    || !world.getOtherEntities(bot, bot.getBoundingBox().offset(position.subtract(bot.getPos())),
                    other -> other instanceof LivingEntity && other.isAlive() && !other.isSpectator()).isEmpty()) continue;
            bot.networkHandler.requestTeleport(position.x, position.y, position.z, bot.getYaw(), bot.getPitch());
            bot.setVelocity(Vec3d.ZERO);
            bot.fallDistance = 0;
            return;
        }
    }
}
