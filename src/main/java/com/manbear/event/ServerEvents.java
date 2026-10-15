package com.manbear.event;

import com.manbear.ManbearConfig;
import com.manbear.ai.CoverFinder;
import com.manbear.ai.FleeManbearGoal;
import com.manbear.command.ManbearCommand;
import com.manbear.data.ManbearData;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.Stance;
import com.manbear.registry.ModEntities;
import net.minecraft.core.BlockPos;
import com.manbear.data.PlayerProfile;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.Mth;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ServerEvents {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.overworld().getGameTime();
        if (now % 10 == 0) sampleProfiles(server, now);
        if (now > 0 && now % ManbearConfig.SPAWN_INTERVAL_TICKS.get() == 0) spawnRoll(server, now);
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent e) {
        ManbearCommand.register(e.getDispatcher());
    }

    /** every animal learns to be afraid of him */
    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent e) {
        if (!e.getLevel().isClientSide() && e.getEntity() instanceof Animal a) {
            a.goalSelector.addGoal(0, new FleeManbearGoal(a));
        }
    }

    /** vibrations (like the Warden hears) can wake a sleeping Manbear */
    @SubscribeEvent
    public void onGameEvent(VanillaGameEvent ev) {
        ManbearEntity b = ManbearEntity.current();
        if (b == null || !b.isAsleep() || ev.getLevel() != b.level() || ev.getCause() == b) return;
        double d = ev.getEventPosition().distanceTo(b.position());
        GameEvent ge = ev.getVanillaEvent();
        float amt = 0F;
        if (ge == GameEvent.STEP) amt = d < 16 ? 0.8F * (1F - (float) d / 16F) : 0F;
        else if (ge == GameEvent.HIT_GROUND) amt = d < 24 ? 2.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.BLOCK_DESTROY || ge == GameEvent.BLOCK_PLACE) amt = d < 24 ? 3.0F * (1F - (float) d / 24F) : 0F;
        else if (ge == GameEvent.EXPLODE) amt = d < 64 ? 30F : 0F;
        else if (ge == GameEvent.PROJECTILE_LAND || ge == GameEvent.SPLASH) amt = d < 20 ? 1.5F * (1F - (float) d / 20F) : 0F;
        else if (ge == GameEvent.ENTITY_DAMAGE) amt = d < 24 ? 4.0F * (1F - (float) d / 24F) : 0F;
        if (amt > 0F) b.hearNoise(amt);
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && e.getSource().getEntity() instanceof ManbearEntity m) {
            m.onKilledPlayer(p);
        }
    }

    // ------------------------------------------------------------------ profiles
    private void sampleProfiles(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        Entity bear = findActive(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            data.profile(p.getUUID()).sample(p, now, bear);
        }
        if (now % 600 == 0) data.setDirty();
    }

    public static ManbearEntity findActive(MinecraftServer server) {
        UUID id = ManbearData.get(server).activeId();
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) return m;
        }
        return null;
    }

    // ------------------------------------------------------------------ spawning (1/25 per minute, only one ever)
    private void spawnRoll(MinecraftServer server, long now) {
        ManbearData data = ManbearData.get(server);
        if (data.isActive(now) || data.onCooldown(now)) return;
        if (server.overworld().getDifficulty() == Difficulty.PEACEFUL) return;
        RandomSource rnd = server.overworld().random;
        if (rnd.nextInt(ManbearConfig.SPAWN_CHANCE_DENOMINATOR.get()) != 0) return;

        List<? extends String> dims = ManbearConfig.DIMENSIONS.get();
        List<ServerPlayer> candidates = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            if (!dims.contains(p.level().dimension().location().toString())) continue;
            candidates.add(p);
        }
        if (candidates.isEmpty()) return;
        ServerPlayer chosen = candidates.get(rnd.nextInt(candidates.size()));
        String kind = null;
        if (!chosen.level().canSeeSky(chosen.blockPosition()) && chosen.getBlockY() < 50
                && rnd.nextFloat() < ManbearConfig.CAVE_EVENT_CHANCE.get()) {
            kind = "cave";
        }
        spawnNear(server, chosen, kind);
    }

    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p) {
        return spawnNear(server, p, null);
    }

    /** Spawns him out of sight. kind (optional, for commands): chaser | faker | trapper | sleeper | behind | lurer | path | fog | cave. */
    public static ManbearEntity spawnNear(MinecraftServer server, ServerPlayer p, String kind) {
        ServerLevel level = p.serverLevel();
        RandomSource rnd = level.random;
        ManbearEntity e = ModEntities.MANBEAR.get().create(level);
        if (e == null) return null;
        if (kind != null) e.applyKind(kind);

        if (e.isBehind()) return spawnBehind(server, level, p, e) ? e : null;
        if (e.isFogger() && spawnFog(server, level, p, e)) return e;
        if (e.isPathBlocker() && spawnPath(server, level, p, e)) return e;
        if (e.isCaver() && spawnCave(server, level, p, e)) return e;
        // the special spot was not possible (no hot spots yet, not in a cave, ...): ordinary stalker
        if (e.isFogger() || e.isPathBlocker() || e.isCaver()) e.makeOrdinary();

        int min = ManbearConfig.SPAWN_MIN_DIST.get();
        int max = Math.max(min + 1, ManbearConfig.SPAWN_MAX_DIST.get());
        if (e.isSleeper()) { min = 22; max = 40; }   // closer, so you can actually stumble on him
        for (int i = 0; i < 60; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;

            BlockPos g;
            if (level.canSeeSky(p.blockPosition())) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                g = new BlockPos(x, y, z);
                if (!CoverFinder.standable(level, g, e.isSleeper() ? 2 : 5)) continue;
            } else {
                g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), e.isSleeper() ? 2 : 5);
                if (g == null) continue;
            }
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision((e.isSleeper() ? Stance.SLEEP : Stance.STAND).dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, rnd.nextFloat() * 360F);
            return e;
        }
        return null;
    }

    private static void place(MinecraftServer server, ServerLevel level, ManbearEntity e, Vec3 v, float yaw) {
        e.moveTo(v.x, v.y, v.z, yaw, 0F);
        level.addFreshEntity(e);
        ManbearData.get(server).claim(e.getUUID(), server.overworld().getGameTime());
    }

    private static float yawFacing(Vec3 from, double tx, double tz) {
        return (float) (Mth.atan2(tz - from.z, tx - from.x) * (180D / Math.PI)) - 90.0F;
    }

    /** behind-you event: directly behind the player, out of their view */
    private static boolean spawnBehind(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        RandomSource rnd = level.random;
        Vec3 look = p.getLookAngle();
        double base = Math.atan2(-look.z, -look.x);
        for (int i = 0; i < 40; i++) {
            double ang = base + (rnd.nextDouble() - 0.5) * 0.9;
            double d = 11 + rnd.nextDouble() * 5;
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            BlockPos g = CoverFinder.groundNear(level, x + 0.5, z + 0.5, p.getBlockY(), 5);
            if (g == null || Math.abs(g.getY() - p.getBlockY()) > 3) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            Vec3 to = new Vec3(v.x - p.getX(), 0, v.z - p.getZ()).normalize();
            if (new Vec3(look.x, 0, look.z).normalize().dot(to) > -0.3) continue; // must not be in front of them
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, 0F);
            return true;
        }
        return false;
    }

    /**
     * Fog watcher: at the very edge of YOUR render distance (where the fog thickens), roughly where you are
     * looking, so he is just barely visible as a dark shape.
     */
    private static boolean spawnFog(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        if (!level.canSeeSky(p.blockPosition())) return false;
        RandomSource rnd = level.random;
        int chunks = Math.min(server.getPlayerList().getViewDistance(), Math.max(2, p.requestedViewDistance()));
        double edge = chunks * 16.0;
        Vec3 look = p.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < 60; i++) {
            double ang = base + (rnd.nextDouble() - 0.5) * 1.2;
            double d = edge * (0.90 + rnd.nextDouble() * 0.07);
            int x = (int) Math.floor(p.getX() + Math.cos(ang) * d);
            int z = (int) Math.floor(p.getZ() + Math.sin(ang) * d);
            if (!level.hasChunkAt(new BlockPos(x, p.getBlockY(), z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos g = new BlockPos(x, y, z);
            if (!CoverFinder.standable(level, g, 5) || Math.abs(y - p.getBlockY()) > 30) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (!CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 4.0, 0), p)) continue;   // you must be able to see him
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()));
            return true;
        }
        return false;
    }

    /** Path blocker: standing at one of the places you visit most, 30-40 blocks away, not in your view. */
    private static boolean spawnPath(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        PlayerProfile prof = ManbearData.get(server).profile(p.getUUID());
        int min = ManbearConfig.PATH_MIN_DISTANCE.get();
        int max = Math.max(min + 1, ManbearConfig.PATH_MAX_DISTANCE.get());
        Vec3 look = p.getLookAngle();
        for (BlockPos h : prof.hotspots()) {
            double dx = h.getX() + 0.5 - p.getX();
            double dz = h.getZ() + 0.5 - p.getZ();
            double dd = Math.sqrt(dx * dx + dz * dz);
            if (dd < min || dd > max || !level.hasChunkAt(h)) continue;
            BlockPos g = CoverFinder.groundNear(level, h.getX() + 0.5, h.getZ() + 0.5, h.getY(), 5);
            if (g == null) continue;
            Vec3 v = Vec3.atBottomCenterOf(g);
            Vec3 to = v.subtract(p.getEyePosition()).normalize();
            if (look.dot(to) > 0.35 && CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 3.0, 0), p)) continue; // no pop-in
            if (!level.noCollision(Stance.STAND.dims.makeBoundingBox(v))) continue;
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()));
            return true;
        }
        return false;
    }

    /** Cave event: sitting in the open middle of a cave, back to you, in a spatter of redstone "blood". */
    private static boolean spawnCave(MinecraftServer server, ServerLevel level, ServerPlayer p, ManbearEntity e) {
        if (level.canSeeSky(p.blockPosition())) return false;
        RandomSource rnd = level.random;
        for (int i = 0; i < 70; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double d = 18 + rnd.nextDouble() * 18;
            double x = p.getX() + Math.cos(ang) * d;
            double z = p.getZ() + Math.sin(ang) * d;
            BlockPos g = CoverFinder.groundNear(level, x, z, p.getBlockY(), 3);
            if (g == null || level.canSeeSky(g) || Math.abs(g.getY() - p.getBlockY()) > 10) continue;
            int open = 0;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (CoverFinder.standable(level, g.offset(dx, 0, dz), 3)) open++;
                }
            }
            if (open < 16) continue;   // needs real open floor: the middle of a cave, not a tunnel
            Vec3 v = Vec3.atBottomCenterOf(g);
            if (seenByAnyPlayer(level, v)) continue;
            if (!level.noCollision(Stance.SIT.dims.makeBoundingBox(v))) continue;
            placeBlood(level, g, rnd);
            // back turned to you
            place(server, level, e, v, yawFacing(v, p.getX(), p.getZ()) + 180F);
            return true;
        }
        return false;
    }

    private static void placeBlood(ServerLevel level, BlockPos center, RandomSource rnd) {
        int placed = 0;
        for (int i = 0; i < 40 && placed < 16; i++) {
            int dx = rnd.nextInt(9) - 4;
            int dz = rnd.nextInt(9) - 4;
            if (Math.abs(dx) > 2 && Math.abs(dz) > 2 && rnd.nextBoolean()) continue;   // thicker near him
            BlockPos pos = center.offset(dx, 0, dz);
            if (!level.getBlockState(pos).isAir()) continue;
            BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();
            if (!wire.canSurvive(level, pos)) continue;
            level.setBlock(pos, wire, 3);
            placed++;
        }
    }

    private static boolean seenByAnyPlayer(ServerLevel level, Vec3 v) {
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(v) < 60 * 60 && CoverFinder.lineClear(level, p.getEyePosition(), v.add(0, 1.5, 0), p)) return true;
        }
        return false;
    }
}
