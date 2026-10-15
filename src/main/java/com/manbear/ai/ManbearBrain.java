package com.manbear.ai;

import com.manbear.ManbearConfig;
import com.manbear.data.ManbearData;
import com.manbear.data.PlayerProfile;
import com.manbear.entity.ManbearEntity;
import com.manbear.entity.ManbearMode;
import com.manbear.entity.Stance;
import com.manbear.registry.ModBlocks;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.EntityType;
import net.minecraft.tags.FluidTags;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * The decision maker. He is always in exactly one mode, but can switch at any moment:
 * stalk -> peek -> fake chase -> real chase and back, with weights shaped by what he has
 * learned about the player (see PlayerProfile).
 */
public class ManbearBrain {
    private enum Lunge { NONE, WINDUP, AIR, RECOVER }

    private final ManbearEntity m;
    private final BlockBreaker breaker = new BlockBreaker();

    private ManbearMode mode = ManbearMode.STALK;
    private int modeTime;
    private int modeDuration = 600;

    private ServerPlayer target;
    private int targetLock;
    private int ticksAlive;
    private int noTargetTicks;
    private int lastChaseEnd;

    // awareness
    private double dist;
    private boolean los;
    private boolean looking;
    private int lookedTicks;

    // learned traits (0..1)
    private PlayerProfile prof;
    private float vig = 0.5F, panic = 0.5F, aggr = 0F, camp = 0F, sprint = 0.3F;

    // movement
    private int pathCd;
    private boolean navFail;
    private int navFailCount;
    private Vec3 lastPos = Vec3.ZERO;
    private int stuckTicks;
    private int stanceLock;
    private int unreachableTicks;

    // stalk
    private int orbitDir = 1;
    private int orbitFlip = 200;
    private double stalkRadius = 18;
    private int freezeTicks;

    // hide / peek
    private CoverFinder.Cover cover;
    private boolean atHide;
    private int waitTicks;
    private int peekPhase;
    private int peekTimer;
    private int peeksLeft;

    // fake chase
    private int fakePhase;
    private double fakeStop;
    private int fakeFreeze;

    // chase
    private int chaseTicks;
    private int lungeCd;
    private int meleeCd;
    private int pendingMelee;
    private int hitsLanded;
    private Lunge lunge = Lunge.NONE;
    private int lungeTimer;
    private boolean lungeHit;
    private boolean ambushing;

    // traps
    private BlockPos trapSite;
    private int trapPhase;
    private int trapTimer;
    private int trapCooldown = 700;

    // ambience
    private int ambientCd = 200;
    private int coverFailCd;
    private int mimicCd = 300;
    private int leavingTicks;
    private int peekSide;
    private boolean started;
    private float noise;
    private int roarsUsed;
    private int roarCd;
    private int stunTicks;
    private ServerPlayer stunTarget;
    private int breathCd = 100;
    private BlockPos lastHide;
    private int trickCd = 400;
    private final java.util.List<Pending> pending = new java.util.ArrayList<>();

    private record Pending(int at, SoundEvent ev, SoundSource src, double x, double y, double z, float vol, float pitch) { }
    private int snoreCd = 60;
    private int rushTicks;
    private int pathTrigger = 8;
    private double fogDist = -1;
    private boolean caveFleeing;
    private boolean ambushOnly;
    private boolean pounce;
    private boolean lureStarted;
    private boolean lureDone;
    private int lurePhase;
    private int lureTimer;
    private int lureStare;
    private UUID animalId;
    private SoundEvent lureCall = SoundEvents.WOLF_WHINE;
    private boolean lureTrapVariant;
    private BlockPos baitSpot;
    private int lastChaseTick = -100000;

    public ManbearBrain(ManbearEntity m) { this.m = m; }

    private RandomSource rnd() { return m.getRandom(); }

    /** real chases only happen on "chaser" spawns, and never in the first few seconds */
    private boolean canChase() {
        return m.isChaser() && ticksAlive >= ManbearConfig.GRACE_SECONDS.get() * 20;
    }

    private int hideMin() { return ManbearConfig.STALK_MIN_DISTANCE.get(); }
    private int hideMax() { return Math.max(hideMin() + 6, ManbearConfig.STALK_MAX_DISTANCE.get() + 4); }

    public ManbearMode mode() { return mode; }

    public String debugString() {
        return "chaser=" + m.isChaser() + " mode=" + mode + " t=" + modeTime + "/" + modeDuration + " dist=" + (int) dist
                + " looking=" + looking + " vig=" + String.format("%.2f", vig) + " panic=" + String.format("%.2f", panic)
                + " trapCd=" + trapCooldown + " target=" + (target == null ? "none" : target.getGameProfile().getName());
    }

    // ================================================================= main tick
    public void tick(ServerLevel level) {
        ticksAlive++;
        modeTime++;
        if (trapCooldown > 0) trapCooldown--;
        if (lungeCd > 0) lungeCd--;
        if (meleeCd > 0) meleeCd--;
        if (stanceLock > 0) stanceLock--;
        if (ambientCd > 0) ambientCd--;
        if (coverFailCd > 0) coverFailCd--;
        if (targetLock > 0) targetLock--;
        if (roarCd > 0) roarCd--;
        if (!started) {
            started = true;
            if (m.isSleeper()) setMode(level, ManbearMode.SLEEP);
            else if (m.isBehind()) setMode(level, ManbearMode.BEHIND);
            else if (m.isPathBlocker()) setMode(level, ManbearMode.PATH);
            else if (m.isFogger()) setMode(level, ManbearMode.FOG);
            else if (m.isCaver()) setMode(level, ManbearMode.CAVE);
        }
        tickStun();

        if (mimicCd > 0) mimicCd--;
        // he never lingers: after his lifespan he fades away (after a chase in progress, at most 40 s later)
        if (!m.leaving && mode != ManbearMode.SLEEP && mode != ManbearMode.CAVE && m.lifeTicks > m.lifespan() && (!mode.isChaseLike() || m.lifeTicks > m.lifespan() + 800)) beginLeaving(level);
        if (m.leaving) leavingTicks++;

        resolveTarget(level);
        if (mode == ManbearMode.SLEEP) { tickSleep(level); return; }
        if (target == null) {
            m.setFaceTarget(null, 0F);
            m.getNavigation().stop();
            m.setClimbing(false);
            if (m.leaving || ++noTargetTicks > 1200) m.discard();
            return;
        }
        noTargetTicks = 0;
        if (ticksAlive % 20 == 1 || prof == null) refreshTraits(level);
        sense();
        if (!lureStarted && m.isLurer() && mode == ManbearMode.STALK) {
            lureStarted = true;
            setMode(level, ManbearMode.LURE);
        }
        m.setFaceTarget(target, mode.isChaseLike() ? 38F : 16F);
        if (mode == ManbearMode.CAVE && !caveFleeing) m.setFaceTarget(null, 0F);   // sits with his back to you
        updateStance(level);
        ambient();
        mimicAnimal();
        breathe();
        tickTricks(level);

        switch (mode) {
            case STALK -> tickStalk(level);
            case HIDE -> tickHide(level);
            case PEEK -> tickPeek(level);
            case FAKE_CHASE -> tickFake(level);
            case CHASE, AMBUSH -> tickChase(level);
            case LAY_TRAP -> tickTrap(level);
            case RETREAT -> tickRetreat(level);
            case BEHIND -> tickBehind(level);
            case LURE -> tickLure(level);
            case PATH -> tickPath(level);
            case FOG -> tickFog(level);
            case CAVE -> tickCave(level);
            default -> { }
        }

        updateClimb();
        spontaneous(level);
        if (modeTime >= modeDuration) onModeExpired(level);
    }

    // ================================================================= target / senses / traits
    private void resolveTarget(ServerLevel level) {
        if (target != null && !validTarget(target)) target = null;
        if (target == null || (targetLock <= 0 && ticksAlive % 100 == 0)) {
            ServerPlayer best = null;
            double bd = Double.MAX_VALUE;
            for (ServerPlayer p : level.players()) {
                if (!validTarget(p)) continue;
                double d = p.distanceToSqr(m);
                if (p == target) d -= 24 * 24;
                if (d < bd) { bd = d; best = p; }
            }
            target = best;
        }
    }

    private boolean validTarget(ServerPlayer p) {
        return p.isAlive() && !p.isSpectator() && !p.isCreative() && p.level() == m.level() && m.distanceToSqr(p) < 110 * 110;
    }

    private void sense() {
        dist = m.distanceTo(target);
        los = m.hasLineOfSight(target);
        Vec3 look = target.getViewVector(1F);
        Vec3 to = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        looking = los && dist < 70 && look.dot(to) > 0.72;
        lookedTicks = looking ? lookedTicks + 1 : Math.max(0, lookedTicks - 2);
    }

    private void refreshTraits(ServerLevel level) {
        ManbearData data = ManbearData.get(level.getServer());
        prof = data.profile(target.getUUID());
        if (ManbearConfig.ADAPTATION.get()) {
            vig = prof.vigilance; panic = prof.panic; aggr = prof.aggression;
            camp = prof.camping; sprint = prof.sprintiness;
        } else {
            vig = 0.5F; panic = 0.5F; aggr = 0F; camp = 0F; sprint = 0.3F;
        }
        if (ticksAlive % 600 == 1) data.setDirty();
    }

    // ================================================================= mode switching
    private void setMode(ServerLevel level, ManbearMode nm) {
        if (nm == mode && modeTime < 5) return;
        exitMode(level);
        mode = nm;
        modeTime = 0;
        m.setSyncedMode(nm);
        navFail = false;
        navFailCount = 0;
        unreachableTicks = 0;
        stuckTicks = 0;
        pathCd = 0;
        m.getNavigation().stop();
        enterMode(level);
    }

    private void exitMode(ServerLevel level) {
        breaker.cancel(level, m);
        m.setPeekSide(0);
        lunge = Lunge.NONE;
        pounce = false;
        if (mode == ManbearMode.LURE) {
            if (lurePhase >= 2) releaseLureAnimal(level);
            else discardLureAnimal(level);
        }
        if (mode.isChaseLike()) ambushing = false;
    }

    private void enterMode(ServerLevel level) {
        RandomSource r = rnd();
        switch (mode) {
            case STALK -> {
                modeDuration = 160 + r.nextInt(240);
                int lo = ManbearConfig.STALK_MIN_DISTANCE.get();
                int hi = Math.max(lo + 1, ManbearConfig.STALK_MAX_DISTANCE.get());
                stalkRadius = Mth.clamp(lo + r.nextInt(hi - lo) - 4 * (1 - vig) + 4 * vig - 3 * camp, lo - 4, hi + 6);
                orbitDir = r.nextBoolean() ? 1 : -1;
                orbitFlip = 120 + r.nextInt(240);
                freezeTicks = 0;
            }
            case HIDE -> {
                modeDuration = 700;
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax(), lastHide);
                if (cover == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                lastHide = cover.hide();
                atHide = false;
                waitTicks = 80 + r.nextInt(140);
            }
            case PEEK -> {
                modeDuration = 900;
                if (cover == null || cover.peek() == null) cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null || cover.peek() == null) { coverFailCd = 200; setMode(level, ManbearMode.STALK); return; }
                peekPhase = 0;
                peeksLeft = 1 + r.nextInt(3);
            }
            case FAKE_CHASE -> {
                modeDuration = 220;
                fakePhase = 0;
                fakeStop = 7 + r.nextInt(5);
                if (r.nextFloat() < 0.55F) m.playBear(ModSounds.ROAR.get(), 3.5F, 0.85F + r.nextFloat() * 0.2F);
            }
            case CHASE, AMBUSH -> {
                float panicScale = 0.8F + 0.6F * panic;
                modeDuration = (int) ((400 + r.nextInt(700)) * panicScale);
                if (mode == ManbearMode.AMBUSH) modeDuration = 700;
                chaseTicks = 0;
                if (ticksAlive - lastChaseTick > 600) roarsUsed = 0;   // a new chase only after 30 s of calm
                roarCd = Math.max(roarCd, 100);
                hitsLanded = 0;
                lunge = Lunge.NONE;
                pendingMelee = 0;
                lungeCd = ambushing ? 0 : 30 + r.nextInt(30);
                freezeTicks = 0;
                if (prof != null) prof.chases++;
                // unpredictable start: sometimes he roars first, sometimes he just stares for a breath, sometimes silence
                float roll = r.nextFloat();
                if (ambushing || roll < 0.45F) m.playBear(ModSounds.ROAR.get(), 4.5F, 0.8F + r.nextFloat() * 0.2F);
                if (!ambushing && roll > 0.7F) freezeTicks = 10 + r.nextInt(14);
            }
            case LAY_TRAP -> {
                modeDuration = 900;
                trapSite = null;
                if (trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) {
                    trapSite = CoverFinder.findTrapSite(level, target, prof, r);
                }
                if (trapSite == null) { trapCooldown = 300; setMode(level, ManbearMode.STALK); return; }
                trapPhase = 0;
            }
            case LURE -> {
                modeDuration = 1800;
                lurePhase = 0;
                lureTimer = 0;
                lureStare = 0;
                lureTrapVariant = r.nextFloat() < ManbearConfig.LURE_TRAP_CHANCE.get().floatValue();
                cover = coverFailCd > 0 ? null : CoverFinder.findCover(level, m, target, hideMin(), hideMax());
                if (cover == null) { lureDone = true; setMode(level, ManbearMode.STALK); return; }
            }
            case PATH -> {
                modeDuration = 1_000_000;
                pathTrigger = 4 + r.nextInt(9);   // he lunges when you get this close (4-12 blocks)
            }
            case FOG -> {
                modeDuration = 1_000_000;
                fogDist = -1;
            }
            case CAVE -> {
                modeDuration = 1_000_000;
                caveFleeing = false;
                m.setStance(Stance.SIT);
            }
            case BEHIND -> modeDuration = 1_000_000;
            case SLEEP -> {
                modeDuration = 1_000_000;
                m.setStance(Stance.SLEEP);
                m.setFaceTarget(null, 0F);
            }
            case RETREAT -> modeDuration = m.leaving ? 1_000_000 : 100 + r.nextInt(120);
        }
    }

    private void onModeExpired(ServerLevel level) {
        if (m.leaving) { modeTime = 0; return; }
        switch (mode) {
            case CHASE, AMBUSH -> endChase(level);
            case FAKE_CHASE -> setMode(level, ManbearMode.RETREAT);
            case RETREAT -> setMode(level, rnd().nextFloat() < 0.55F ? ManbearMode.HIDE : ManbearMode.STALK);
            default -> setMode(level, pickNextMode(false));
        }
    }

    private ManbearMode pickNextMode(boolean spontaneous) {
        boolean canChase = canChase();
        double tension = Mth.clamp(0.8 + (ticksAlive - lastChaseEnd) / 2400.0, 0.8, 2.0);
        double[] w = new double[ManbearMode.values().length];
        w[ManbearMode.STALK.ordinal()] = spontaneous ? 0 : 1.0;
        w[ManbearMode.HIDE.ordinal()] = coverFailCd > 0 ? 0 : 2.8 + 2.2 * vig;
        w[ManbearMode.PEEK.ordinal()] = coverFailCd > 0 ? 0 : 1.6 + 1.6 * vig;
        w[ManbearMode.FAKE_CHASE.ordinal()] = (m.isFaker() && ticksAlive > 200) ? 0.7 + 1.8 * vig * (1 - aggr) + 0.8 * panic : 0;
        w[ManbearMode.CHASE.ordinal()] = canChase ? (0.5 + 2.2 * panic + 1.2 * (1 - vig) + 1.2 * camp + 0.8 * sprint) * tension : 0;
        w[ManbearMode.LAY_TRAP.ordinal()] = (m.isTrapper() && trapCooldown <= 0 && ManbearConfig.MAX_TRAPS.get() > 0) ? 0.6 + 2.0 * aggr + 1.0 * sprint + 1.0 * camp : 0;
        w[ManbearMode.AMBUSH.ordinal()] = 0;
        w[ManbearMode.RETREAT.ordinal()] = 0;
        w[ManbearMode.SLEEP.ordinal()] = 0;
        w[mode.ordinal()] = 0;
        double total = 0;
        for (double d : w) total += d;
        if (total <= 0) return ManbearMode.STALK;
        double pick = rnd().nextDouble() * total;
        for (int i = 0; i < w.length; i++) {
            pick -= w[i];
            if (pick <= 0) return ManbearMode.byId(i);
        }
        return ManbearMode.STALK;
    }

    /** once a second: random, unpredictable switches */
    private void spontaneous(ServerLevel level) {
        if (m.leaving || ticksAlive % 20 != 0) return;
        boolean calm = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK || mode == ManbearMode.LAY_TRAP;
        if (!calm) return;
        boolean canChase = canChase();

        // you wandered too close while he was lurking: pounce
        if (dist < 8.5 && modeTime > 60 && canChase && rnd().nextFloat() < 0.22F) {
            setMode(level, ManbearMode.CHASE);
            return;
        }
        // stared at for too long: duck out of sight
        if (mode == ManbearMode.STALK && lookedTicks > 40 && vig > 0.25F && coverFailCd <= 0) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        // out in the open he gets nervous quickly: duck behind the next tree
        if (mode == ManbearMode.STALK && coverFailCd <= 0 && rnd().nextFloat() < 0.10F) {
            setMode(level, ManbearMode.HIDE);
            return;
        }
        float p = 0.04F + 0.05F * (1F - vig);
        if (rnd().nextFloat() < p) setMode(level, pickNextMode(true));
    }

    // ================================================================= events
    public void onHurt(ServerLevel level, Entity attacker) {
        if (!(attacker instanceof ServerPlayer sp)) return;
        ManbearData data = ManbearData.get(level.getServer());
        data.profile(sp.getUUID()).onHitManbear();
        data.setDirty();
        target = sp;
        targetLock = 400;
        if (mode == ManbearMode.SLEEP) { wake(level); return; }
        if (mode == ManbearMode.CAVE) { caveFleeing = true; beginLeaving(level); return; }
        if (mode == ManbearMode.FOG) { beginLeaving(level); return; }
        if (mode == ManbearMode.PATH) { pounceChase(level, false, 8.0F); return; }
        if (m.leaving) return;
        if (m.getHealth() < m.getMaxHealth() * 0.3F) { beginLeaving(level); return; }
        if (!mode.isChaseLike()) {
            float r = rnd().nextFloat();
            if (r < 0.35F) setMode(level, ManbearMode.CHASE);
            else if (r < 0.85F) setMode(level, ManbearMode.RETREAT);
            else if (trapCooldown <= 0) setMode(level, ManbearMode.LAY_TRAP);
        }
    }

    public void beginLeaving(ServerLevel level) {
        m.leaving = true;
        setMode(level, ManbearMode.RETREAT);
        modeDuration = 1_000_000;
    }

    /** a trap he laid has just been sprung */
    public void ambush(ServerPlayer p, BlockPos pos) {
        if (m.leaving || !(m.level() instanceof ServerLevel level)) return;
        target = p;
        targetLock = 800;
        ambushing = true;
        ambushOnly = true;      // a trap ambush is a single lunge, not a chase
        setMode(level, ManbearMode.AMBUSH);
        refreshTraits(level);
    }

    public void forceMode(ServerLevel level, ManbearMode mm) {
        ambushing = false;
        modeTime = 100;
        setMode(level, mm);
    }

    // ================================================================= movement helpers
    private boolean canFit(ServerLevel level, Stance s) {
        AABB box = s.dims.makeBoundingBox(m.position()).deflate(1.0E-7D);
        return level.noCollision(m, box);
    }

    /** deep enough water that he swims instead of wading */
    private boolean swimming() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 1.2D;
    }

    /** any real water (rivers are often only 1 block deep) */
    private boolean inWater() {
        return m.isInWater() && m.getFluidHeight(FluidTags.WATER) > 0.4D;
    }

    /** not lava / not a long drop of lava straight ahead */
    private boolean safeToCharge(ServerLevel level) {
        Vec3 d = target.position().subtract(m.position());
        Vec3 h = new Vec3(d.x, 0, d.z);
        if (h.lengthSqr() < 1.0E-4) return false;
        h = h.normalize();
        for (int i = 1; i <= 3; i++) {
            BlockPos ahead = BlockPos.containing(m.getX() + h.x * i, m.getY(), m.getZ() + h.z * i);
            if (level.getFluidState(ahead).is(FluidTags.LAVA) || level.getFluidState(ahead.below()).is(FluidTags.LAVA)) return false;
        }
        return true;
    }

    private void updateStance(ServerLevel level) {
        if (ticksAlive % 5 != 0) return;
        Stance cur = m.getStance();
        if (swimming()) {
            if (cur != Stance.SWIM) m.setStance(Stance.SWIM);
            return;
        }
        if (mode == ManbearMode.CAVE && !caveFleeing) {
            if (cur != Stance.SIT) m.setStance(Stance.SIT);
            return;
        }
        Stance want;
        if (mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE || mode == ManbearMode.BEHIND
                || mode == ManbearMode.PATH || mode == ManbearMode.FOG || (mode == ManbearMode.RETREAT && m.leaving)
                || (mode == ManbearMode.LURE && lurePhase >= 3)) want = Stance.STAND;
        else want = dist < 60 ? Stance.CROUCH : Stance.STAND;
        if (stanceLock > 0 && want.ordinal() < cur.ordinal() && cur != Stance.SWIM) want = cur;
        while (want != Stance.CRAWL && !canFit(level, want)) want = Stance.byId(want.ordinal() + 1);
        if (want != cur) m.setStance(want);
    }

    /** Paths to the goal; if the current body is too big for the route, crouches or crawls to fit. */
    private boolean moveToSmart(ServerLevel level, double x, double y, double z, double speed) {
        PathNavigation nav = m.getNavigation();
        Path best = nav.createPath(x, y, z, 1);
        if (best != null && best.canReach()) {
            nav.moveTo(best, speed);
            return true;
        }
        Stance cur = m.getStance();
        for (Stance s : Stance.values()) {
            if (s.ordinal() >= Stance.SLEEP.ordinal() || s.ordinal() <= cur.ordinal() || !canFit(level, s)) continue;
            m.setStance(s);
            Path p = nav.createPath(x, y, z, 1);
            if (p != null && p.canReach()) {
                nav.moveTo(p, speed);
                stanceLock = 60;
                return true;
            }
        }
        m.setStance(cur);
        if (best != null) nav.moveTo(best, speed);
        return false;
    }

    /** In deep water ground pathing is useless, so he just swims straight at the goal. */
    private void swimToward(double x, double y, double z, double speed) {
        Vec3 to = new Vec3(x - m.getX(), y - m.getY(), z - m.getZ());
        double len = to.length();
        if (len < 0.4) return;
        Vec3 dir = to.scale(1.0 / len);
        if (!swimming()) {   // wading in a shallow river: push along the bottom, do not float up
            dir = new Vec3(dir.x, Mth.clamp(dir.y, -0.2, 0.35), dir.z);
            dir = dir.normalize();
        }
        double perTick = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED) * speed * 2.1 * ManbearConfig.SWIM_SPEED_FACTOR.get();
        Vec3 v = m.getDeltaMovement();
        m.impulse(v.scale(0.4).add(dir.scale(perTick * 0.6)));
    }

    private void navTo(ServerLevel level, double x, double y, double z, double speed, int every) {
        if (inWater()) {
            m.getNavigation().stop();
            navFail = false;
            swimToward(x, y, z, speed);
            return;
        }
        if (pathCd > 0 && !m.getNavigation().isDone()) {
            pathCd--;
            m.getNavigation().setSpeedModifier(speed);
            return;
        }
        pathCd = every;
        navFail = !moveToSmart(level, x, y, z, speed);
        navFailCount = navFail ? navFailCount + 1 : 0;
    }

    private boolean reached(BlockPos p, double sqr) {
        return m.position().distanceToSqr(Vec3.atBottomCenterOf(p)) < sqr;
    }

    /** the player's current top speed as a speed modifier for us (1.0 = walk, 1.3 = sprint) */
    private double refSpeedMod() {
        double base = m.getAttributeBaseValue(Attributes.MOVEMENT_SPEED);
        double ps = target.getAttributeValue(Attributes.MOVEMENT_SPEED);
        if (!target.isSprinting()) ps *= 1.3D;
        return Mth.clamp(ps / base, 1.0D, 1.9D);
    }

    private void updateClimb() {
        boolean chasing = mode.isChaseLike() || mode == ManbearMode.FAKE_CHASE;
        boolean want = m.horizontalCollision
                && ((chasing && (target.getY() > m.getY() + 0.8 || stuckTicks > 8)) || target.getY() > m.getY() + 2.5);
        m.setClimbing(want && lunge == Lunge.NONE);
    }

    // ================================================================= STALK
    private void tickStalk(ServerLevel level) {
        if (--orbitFlip <= 0) {
            orbitDir = -orbitDir;
            orbitFlip = 120 + rnd().nextInt(240);
        }
        // freeze like a statue while you stare at him
        if (looking && dist < 60) freezeTicks = Math.max(freezeTicks, 10);
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        Vec3 p = target.position();
        Vec3 me = m.position();
        Vec3 r = new Vec3(me.x - p.x, 0, me.z - p.z);
        double rl = r.length();
        if (rl < 0.01) { r = new Vec3(1, 0, 0); rl = 1; }
        Vec3 u = r.scale(1.0 / rl);
        Vec3 t = new Vec3(-u.z, 0, u.x).scale(orbitDir);
        double radial = Mth.clamp(stalkRadius - rl, -6, 6);
        Vec3 goal = p.add(u.scale(rl + radial)).add(t.scale(4.0));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 3);
        if (g == null) {
            orbitDir = -orbitDir;
            if (++navFailCount > 3 && coverFailCd <= 0) setMode(level, ManbearMode.HIDE);
            return;
        }
        boolean crouched = m.getStance() != Stance.STAND;
        double speed = crouched ? 0.62 : 0.85;
        if (!looking && rl > stalkRadius + 3) speed = 0.95; // closes in while you look away
        navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, speed, 15);
    }

    // ================================================================= HIDE
    private void tickHide(ServerLevel level) {
        if (cover == null) { setMode(level, ManbearMode.STALK); return; }
        if (!atHide) {
            if (reached(cover.hide(), 1.6)) {
                atHide = true;
                m.getNavigation().stop();
            } else {
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                navTo(level, h.x, h.y, h.z, looking ? 1.15 : 0.9, 10);
                if (navFailCount > 4) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
                return;
            }
        }
        m.getNavigation().stop();
        if (--waitTicks <= 0) {
            float pick = rnd().nextFloat();
            if (cover.peek() != null && pick < 0.55F) setMode(level, ManbearMode.PEEK);
            else if (pick < 0.88F) setMode(level, ManbearMode.HIDE);   // hop to a different tree
            else setMode(level, ManbearMode.STALK);
        }
    }

    // ================================================================= PEEK
    /** +1 = his left, -1 = his right, depending on which side the open view is */
    private int openSide() {
        BlockPos peek = cover.peek();
        if (peek == null) return rnd().nextBoolean() ? 1 : -1;
        double dx = peek.getX() - cover.hide().getX();
        double dz = peek.getZ() - cover.hide().getZ();
        float yaw = m.getYRot() * ((float) Math.PI / 180F);
        double left = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        return left >= 0 ? 1 : -1;
    }

    private void tickPeek(ServerLevel level) {
        if (cover == null || cover.peek() == null) { setMode(level, ManbearMode.STALK); return; }
        Vec3 h = Vec3.atBottomCenterOf(cover.hide());
        switch (peekPhase) {
            case 0 -> { // get behind the tree
                if (reached(cover.hide(), 1.6)) { peekPhase = 1; m.getNavigation().stop(); peekTimer = 20 + rnd().nextInt(30); }
                else navTo(level, h.x, h.y, h.z, 0.95, 10);
            }
            case 1 -> { // stand hidden for a beat
                m.getNavigation().stop();
                if (--peekTimer <= 0) {
                    peekSide = openSide() * cover.lean();
                    m.setPeekSide(peekSide);   // peek_left / peek_right
                    peekPhase = 2;
                    // he holds the lean for a good while, then simply moves on (leaving PEEK clears the pose)
                    peekTimer = Math.max(120, (int) (200 + rnd().nextInt(160) - 100 * vig));
                }
            }
            default -> { // leaning out, staring, motionless
                m.getNavigation().stop();
                m.setPeekSide(peekSide);
                if (--peekTimer <= 0) {
                    setMode(level, rnd().nextFloat() < 0.78F ? ManbearMode.HIDE : ManbearMode.STALK);
                }
            }
        }
        if (navFailCount > 5) { cover = null; coverFailCd = 200; setMode(level, ManbearMode.STALK); }
    }

    // ================================================================= FAKE CHASE
    private void tickFake(ServerLevel level) {
        if (fakePhase == 0) {
            navTo(level, target.getX(), target.getY(), target.getZ(), refSpeedMod() * 1.15, 8);
            if (navFail && safeToCharge(level)) m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), refSpeedMod() * 1.15);
            if (dist <= fakeStop || modeTime > 110) {
                fakePhase = 1;
                fakeFreeze = 25 + rnd().nextInt(25);
                m.getNavigation().stop();
                if (rnd().nextFloat() < 0.4F) m.playBear(ModSounds.SNARL.get(), 3.0F, 0.8F);
            }
        } else {
            m.getNavigation().stop();
            if (--fakeFreeze <= 0 || dist < 5.0) {
                float r = rnd().nextFloat();
                boolean canChase = canChase();
                if (canChase && r < 0.10F) setMode(level, ManbearMode.CHASE);   // the "fake" was real
                else if (r < 0.75F) setMode(level, ManbearMode.RETREAT);
                else setMode(level, ManbearMode.HIDE);
            }
        }
    }

    // ================================================================= CHASE
    private void tickChase(ServerLevel level) {
        chaseTicks++;
        lastChaseTick = ticksAlive;
        if (ambushOnly && chaseTicks > 500) { vanish(level); return; }   // never got him
        if (ticksAlive % 20 == 0) samplePanic();

        if (pendingMelee > 0 && --pendingMelee == 0) {
            if (m.distanceTo(target) < 5.3 && m.doHurtTarget(target)) {
                hitsLanded++;
                if (ambushOnly) { vanish(level); return; }
            }
        }
        if (freezeTicks > 0) {
            freezeTicks--;
            m.getNavigation().stop();
            return;
        }
        // stunning roar: only when you have opened up a gap, at most N times per chase; he keeps running meanwhile
        if (!ambushOnly && lunge == Lunge.NONE && roarCd == 0 && roarsUsed < ManbearConfig.ROARS_PER_CHASE.get() && chaseTicks > 80
                && los && dist >= ManbearConfig.ROAR_MIN_DISTANCE.get() && dist <= ManbearConfig.ROAR_MAX_DISTANCE.get()) {
            startRoar(level);
        }
        if (lunge != Lunge.NONE) { tickLunge(level); return; }

        if (breaker.active()) {
            m.getNavigation().stop();
            if (!breaker.tick(level, m)) { stuckTicks = 0; pathCd = 0; }
            return;
        }

        double ramp = Math.min(1.0, chaseTicks / (ManbearConfig.CHASE_RAMP_SECONDS.get() * 20.0));
        double base = ManbearConfig.CHASE_SPEED_BONUS_BASE.get();
        double bonus = (base + Math.max(0.0, ManbearConfig.CHASE_SPEED_BONUS_MAX.get() - base) * ramp) * (0.9 + 0.2 * sprint);
        double speed = refSpeedMod() * (1.0 + bonus);
        if (mode == ManbearMode.AMBUSH && chaseTicks < 100) speed *= 1.2;
        if (m.getRoarTicks() > 0) speed *= 1.12;   // catching up while you are frozen
        if (rushTicks > 0) {                         // the "behind you" jumpscare charge
            rushTicks--;
            if (dist > 3.5) speed = refSpeedMod() * ManbearConfig.BEHIND_RUSH_MULTIPLIER.get();
            else rushTicks = 0;
        }

        // melee
        if (dist < 4.0 && meleeCd == 0 && pendingMelee == 0) {
            m.triggerAttack(rnd().nextInt(3));
            meleeCd = 24;
            pendingMelee = 5;
            m.getNavigation().stop();
            return;
        }
        // lunge
        if (lungeCd == 0 && dist >= 4.4 && dist <= 9.5 && los && m.onGround()) {
            lunge = Lunge.WINDUP;
            lungeTimer = 7;
            lungeHit = false;
            m.getNavigation().stop();
            m.triggerAttack(rnd().nextInt(3));
            m.playBear(ModSounds.SNARL.get(), 3.5F, 0.75F + rnd().nextFloat() * 0.2F);
            return;
        }

        navTo(level, target.getX(), target.getY(), target.getZ(), speed, 6);

        // Path to you is blocked (you are in a house / bunker) or he is stuck: tunnel straight toward you.
        Vec3 cur = m.position();
        if (cur.distanceToSqr(lastPos) < 0.0016) stuckTicks++; else stuckTicks = 0;
        lastPos = cur;
        if ((navFail || stuckTicks > 12) && dist > 2.4) {
            BlockPos b = BlockBreaker.canGrief(level) ? breaker.findAlongRay(level, m, target) : null;
            if (b != null) {
                if (BlockBreaker.inReach(m, b)) {
                    breaker.start(level, b);
                    m.getNavigation().stop();
                    stuckTicks = 0;
                } else {
                    // walk right up to the wall first (overrides the partial path this tick)
                    m.getMoveControl().setWantedPosition(b.getX() + 0.5, b.getY(), b.getZ() + 0.5, speed);
                }
                unreachableTicks = Math.max(0, unreachableTicks - 3);
            } else if (stuckTicks > 12) {
                unreachableTicks += 2;
            } else if (safeToCharge(level)) {
                // nothing to break: a river or a gap. Go straight at you - he wades and swims across.
                m.getMoveControl().setWantedPosition(target.getX(), target.getY(), target.getZ(), speed);
            }
        }
        if (!navFail) unreachableTicks = Math.max(0, unreachableTicks - 2);
        if (unreachableTicks > 160) {
            setMode(level, trapCooldown <= 0 ? ManbearMode.LAY_TRAP : ManbearMode.STALK);
            return;
        }

        if (hitsLanded >= (aggr > 0.5F ? 2 : 4) || dist > 85) endChase(level);
    }

    private void tickLunge(ServerLevel level) {
        lungeTimer--;
        switch (lunge) {
            case WINDUP -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    Vec3 aim = target.position();
                    if (pounce && prof != null) aim = aim.add(prof.velocity().scale(0.9)); // lead a moving target
                    Vec3 d = aim.subtract(m.position());
                    Vec3 h = new Vec3(d.x, 0, d.z);
                    double hl = h.length();
                    h = hl < 1.0E-4 ? new Vec3(0, 0, 1) : h.scale(1.0 / hl);
                    if (pounce) {
                        // ballistic leap: reaches the target in about 9 ticks (under half a second)
                        double v = Mth.clamp(hl / 6.2, 1.0, 3.2);
                        m.impulse(new Vec3(h.x * v, 0.42, h.z * v));
                        lungeTimer = 24;
                    } else {
                        m.impulse(new Vec3(h.x * 0.95, 0.38, h.z * 0.95));
                        lungeTimer = 16;
                    }
                    lunge = Lunge.AIR;
                }
            }
            case AIR -> {
                if (!lungeHit && m.getBoundingBox().inflate(pounce ? 1.3 : 0.8, pounce ? 0.8 : 0.4, pounce ? 1.3 : 0.8).intersects(target.getBoundingBox())) {
                    lungeHit = true;
                    if (m.doHurtTarget(target)) {
                        hitsLanded++;
                        if (ambushOnly) { vanish(level); return; }   // lunged, hurt you, gone
                    }
                }
                if (lungeTimer <= 0 || (lungeTimer < (pounce ? 18 : 11) && m.onGround())) {
                    lunge = Lunge.RECOVER;
                    lungeTimer = pounce ? 6 : 14;
                }
            }
            case RECOVER -> {
                m.getNavigation().stop();
                if (lungeTimer <= 0) {
                    lunge = Lunge.NONE;
                    pounce = false;
                    lungeCd = ManbearConfig.LUNGE_COOLDOWN_TICKS.get() + rnd().nextInt(30) - (aggr > 0.5F ? 20 : 0);
                    if (lungeHit && (aggr > 0.5F || rnd().nextFloat() < 0.2F)) endChase(level);
                }
            }
            default -> { }
        }
    }

    private void samplePanic() {
        if (prof == null) return;
        Vec3 away = target.position().subtract(m.position());
        away = new Vec3(away.x, 0, away.z);
        if (away.lengthSqr() < 0.01) return;
        away = away.normalize();
        boolean fleeing = prof.velocity().dot(away) > 0.5;
        if (fleeing) prof.panic = PlayerProfile.ema(prof.panic, 1F, 0.08F);
        else if (dist < 10) prof.panic = PlayerProfile.ema(prof.panic, 0F, 0.08F);
    }

    private void endChase(ServerLevel level) {
        lastChaseEnd = ticksAlive;
        float r = rnd().nextFloat();
        setMode(level, r < 0.6F ? ManbearMode.RETREAT : r < 0.85F ? ManbearMode.HIDE : ManbearMode.STALK);
    }

    // ================================================================= LAY TRAP
    private void tickTrap(ServerLevel level) {
        if (trapSite == null) { setMode(level, ManbearMode.STALK); return; }
        if (trapPhase == 0) {
            Vec3 s = Vec3.atBottomCenterOf(trapSite);
            navTo(level, s.x, s.y, s.z, 0.8, 15);
            if (dist < 10 && los) { // caught red-handed
                boolean canChase = canChase();
                setMode(level, canChase && rnd().nextFloat() < 0.5F ? ManbearMode.CHASE : ManbearMode.RETREAT);
                return;
            }
            if (reached(trapSite, 5.0)) {
                trapPhase = 1;
                trapTimer = 35;
                m.getNavigation().stop();
                m.triggerAttack(2);
            }
        } else {
            m.getNavigation().stop();
            if (--trapTimer <= 0) {
                placeTrap(level);
                trapCooldown = 900 + rnd().nextInt(1800);
                setMode(level, rnd().nextFloat() < 0.6F ? ManbearMode.HIDE : ManbearMode.STALK);
            }
        }
    }

    private void placeTrap(ServerLevel level) {
        placeTrapAt(level, trapSite);
    }

    private boolean placeTrapAt(ServerLevel level, BlockPos site) {
        BlockState st = level.getBlockState(site);
        if (!(st.isAir() || st.canBeReplaced())) return false;
        BlockState trap = ModBlocks.BEAR_TRAP.get().defaultBlockState()
                .setValue(com.manbear.block.BearTrapBlock.HUNTER_SET, true);
        if (!trap.canSurvive(level, site)) return false;
        level.setBlock(site, trap, 3);
        ManbearData data = ManbearData.get(level.getServer());
        String dim = level.dimension().location().toString();
        for (ManbearData.Trap old : data.addTrap(new ManbearData.Trap(dim, site.asLong()), Math.max(ManbearConfig.MAX_TRAPS.get(), 4))) {
            ServerLevel l = level.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(old.dim())));
            BlockPos p = BlockPos.of(old.pos());
            if (l != null && l.hasChunkAt(p) && l.getBlockState(p).is(ModBlocks.BEAR_TRAP.get())) l.removeBlock(p, false);
        }
        return true;
    }

    // ================================================================= RETREAT
    private void tickRetreat(ServerLevel level) {
        Vec3 me = m.position();
        Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
        away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
        Vec3 goal = me.add(away.scale(18));
        BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, m.getBlockY(), 3);
        if (caveFleeing || g == null) {
            BlockPos alt = fleeSpot(level);
            if (alt != null) g = alt;
        }
        double fleeSpeed = caveFleeing ? Math.max(2.2, refSpeedMod() * 2.4) : (m.leaving ? 1.1 : 0.9);
        if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, fleeSpeed, caveFleeing ? 8 : 15);
        else m.getNavigation().stop();
        if (caveFleeing) m.setFaceTarget(null, 0F);   // runs forward, away from you

        if (m.leaving) {
            if (!los || dist > 60 || leavingTicks > 300) m.discard();
            return;
        }
        if ((!los && dist > 30) || dist > 46) {
            setMode(level, rnd().nextFloat() < 0.55F && coverFailCd <= 0 ? ManbearMode.HIDE : ManbearMode.STALK);
        }
    }

    // ================================================================= ANIMAL LURE
    private PathfinderMob lureAnimal(ServerLevel level) {
        if (animalId == null) return null;
        return level.getEntity(animalId) instanceof PathfinderMob a && a.isAlive() ? a : null;
    }

    /** nobody saw it yet: just remove it */
    private void discardLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) a.discard();
        animalId = null;
    }

    /** it was seen: it simply becomes an ordinary animal that is terrified of him */
    private void releaseLureAnimal(ServerLevel level) {
        PathfinderMob a = lureAnimal(level);
        if (a != null) {
            a.removeTag("manbear_run");
            a.removeTag("manbear_lure");
            a.getPersistentData().remove("lure_x");
            a.getPersistentData().remove("lure_y");
            a.getPersistentData().remove("lure_z");
        }
        animalId = null;
    }

    private void abortLure(ServerLevel level) {
        lureDone = true;
        setMode(level, ManbearMode.STALK);   // exitMode removes / releases the animal
    }

    /** puts a random animal between you and him, off to the side where you are likely to notice it */
    private boolean spawnLureAnimal(ServerLevel level) {
        Vec3 toBear = new Vec3(m.getX() - target.getX(), 0, m.getZ() - target.getZ());
        double len = toBear.length();
        if (len < 12) return false;
        toBear = toBear.scale(1.0 / len);
        Vec3 side = new Vec3(-toBear.z, 0, toBear.x);
        EntityType<?>[] types = {EntityType.WOLF, EntityType.PIG, EntityType.SHEEP, EntityType.COW};
        for (int i = 0; i < 20; i++) {
            double fwd = 14 + rnd().nextInt(8);
            double lat = rnd().nextInt(13) - 6;
            Vec3 p = target.position().add(toBear.scale(fwd)).add(side.scale(lat));
            BlockPos g = CoverFinder.groundNear(level, p.x, p.z, target.getBlockY(), 2);
            if (g == null || g.distSqr(m.blockPosition()) < 10 * 10) continue;
            Entity e = types[rnd().nextInt(types.length)].create(level);
            if (!(e instanceof PathfinderMob a)) return false;
            a.moveTo(g.getX() + 0.5, g.getY(), g.getZ() + 0.5, rnd().nextFloat() * 360F, 0F);
            a.addTag("manbear_lure");
            a.goalSelector.addGoal(0, new LureWolfGoal(a));
            if (a instanceof Wolf) lureCall = SoundEvents.WOLF_WHINE;
            else if (a instanceof Pig) lureCall = SoundEvents.PIG_AMBIENT;
            else if (a instanceof Sheep) lureCall = SoundEvents.SHEEP_AMBIENT;
            else if (a instanceof Cow) lureCall = SoundEvents.COW_AMBIENT;
            level.addFreshEntity(a);
            animalId = a.getUUID();
            return true;
        }
        return false;
    }

    /** trap variant: a bait spot a few blocks in front of his hiding place, ringed with traps on your side */
    private boolean setupBait(ServerLevel level) {
        Vec3 hide = Vec3.atBottomCenterOf(cover.hide());
        Vec3 toPlayer = new Vec3(target.getX() - hide.x, 0, target.getZ() - hide.z);
        double len = toPlayer.length();
        if (len < 14) return false;
        toPlayer = toPlayer.scale(1.0 / len);
        Vec3 side = new Vec3(-toPlayer.z, 0, toPlayer.x);
        BlockPos bait = CoverFinder.groundNear(level, hide.x + toPlayer.x * 7, hide.z + toPlayer.z * 7, cover.hide().getY(), 2);
        if (bait == null) return false;
        int placed = 0;
        for (int k = -2; k <= 2; k += 2) {
            double tx = bait.getX() + 0.5 + toPlayer.x * 3 + side.x * k;
            double tz = bait.getZ() + 0.5 + toPlayer.z * 3 + side.z * k;
            BlockPos g = CoverFinder.groundNear(level, tx, tz, bait.getY(), 1);
            if (g != null && placeTrapAt(level, g)) placed++;
        }
        if (placed == 0) return false;
        baitSpot = bait;
        return true;
    }

    private boolean playerSeesAnimal(PathfinderMob a) {
        double d = target.distanceTo(a);
        if (d > 48 || d < 6 || !target.hasLineOfSight(a)) return false;
        Vec3 look = target.getViewVector(1F);
        Vec3 to = a.getEyePosition().subtract(target.getEyePosition()).normalize();
        return look.dot(to) > 0.65;
    }

    /**
     * Animal shows up -> you see it -> it bolts.
     *  - 70%: it runs to a bait spot ringed with his bear traps and waits there, calling you in.
     *  - 30%: it runs straight to him, he lets out a long roar and a chase (or fake chase) starts.
     */
    private void tickLure(ServerLevel level) {
        if (lurePhase > 0) m.getNavigation().stop();   // phase 0 is walking to the hiding spot
        switch (lurePhase) {
            case 0 -> { // slip into hiding first
                Vec3 h = Vec3.atBottomCenterOf(cover.hide());
                if (reached(cover.hide(), 1.6) || modeTime > 500) {
                    m.getNavigation().stop();
                    if (!spawnLureAnimal(level)) { abortLure(level); return; }
                    if (lureTrapVariant && !setupBait(level)) lureTrapVariant = false;   // no room for traps: go to him instead
                    lurePhase = 1;
                    lureTimer = 0;
                } else {
                    navTo(level, h.x, h.y, h.z, 1.0, 10);
                }
            }
            case 1 -> { // wait for you to notice the animal
                PathfinderMob a = lureAnimal(level);
                if (a == null) { abortLure(level); return; }
                lureTimer++;
                if (lureTimer % 100 == 40 && lureStare == 0) {
                    level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.5F, 0.9F);
                }
                if (lureStare == 0 && playerSeesAnimal(a)) lureStare = 28;
                if (lureStare > 0) {   // it stares at you for a moment...
                    a.getNavigation().stop();
                    a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                    if (--lureStare == 0) {   // ...then bolts
                        if (lureTrapVariant && baitSpot != null) {
                            a.getPersistentData().putDouble("lure_x", baitSpot.getX() + 0.5);
                            a.getPersistentData().putDouble("lure_y", baitSpot.getY());
                            a.getPersistentData().putDouble("lure_z", baitSpot.getZ() + 0.5);
                        }
                        a.addTag("manbear_run");
                        lurePhase = 2;
                        lureTimer = 0;
                    }
                } else if (lureTimer > 1100) {
                    abortLure(level);
                }
            }
            case 2 -> { // it runs
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { abortLure(level); return; }
                if (lureTrapVariant && baitSpot != null) {
                    if (a.distanceToSqr(baitSpot.getX() + 0.5, baitSpot.getY(), baitSpot.getZ() + 0.5) < 9 || lureTimer > 500) {
                        a.removeTag("manbear_run");        // it stops at the bait spot and calls to you
                        lurePhase = 4;
                        lureTimer = 0;
                    }
                } else if (a.distanceTo(m) < 4.5 || lureTimer > 500) {
                    a.removeTag("manbear_run");
                    a.removeTag("manbear_lure");   // from now on it is just an animal scared of him
                    lurePhase = 3;
                    lureTimer = 0;
                    m.setRoarTicks(80);
                    m.playBear(ModSounds.ROAR.get(), 9.0F, 0.65F);
                    m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.6F);
                }
            }
            case 4 -> { // bait: it waits among the traps; if you step on one, the usual ambush starts
                PathfinderMob a = lureAnimal(level);
                lureTimer++;
                if (a == null) { lureDone = true; setMode(level, ManbearMode.HIDE); return; }
                a.getNavigation().stop();
                a.getLookControl().setLookAt(target, 30.0F, 30.0F);
                if (lureTimer % 90 == 30) level.playSound(null, a.getX(), a.getY(), a.getZ(), lureCall, SoundSource.NEUTRAL, 2.0F, 0.9F);
                if (lureTimer > 900) { lureDone = true; setMode(level, ManbearMode.HIDE); }
            }
            default -> { // phase 3: the long roar, then it starts
                lureTimer++;
                if (lureTimer == 30) m.playBear(ModSounds.ROAR_DEEP.get(), 9.0F, 0.55F);
                if (lureTimer == 55) m.playBear(ModSounds.ROAR.get(), 9.0F, 0.6F);
                if (lureTimer >= 80) {
                    lureDone = true;
                    if (m.isChaser()) {
                        ambushing = true;
                        setMode(level, ManbearMode.CHASE);
                    } else {
                        setMode(level, ManbearMode.FAKE_CHASE);
                    }
                }
            }
        }
    }

    // ================================================================= BEHIND YOU
    /** He stands behind you, silent and still, until you turn around - then charges and roars. */
    private void tickBehind(ServerLevel level) {
        Vec3 look = target.getLookAngle();
        Vec3 lookH = new Vec3(look.x, 0, look.z);
        lookH = lookH.lengthSqr() < 1.0E-4 ? new Vec3(0, 0, 1) : lookH.normalize();
        Vec3 toEye = m.getEyePosition().subtract(target.getEyePosition()).normalize();
        boolean seen = los && dist < 45 && look.dot(toEye) > 0.5;
        if (seen && modeTime > 30) { jumpscare(level); return; }

        // follow you at a distance so he stays at your back; when you stand still he stands still
        if (dist > 17 || dist < 5.5) {
            Vec3 goal = target.position().subtract(lookH.scale(11));
            BlockPos g = CoverFinder.groundNear(level, goal.x, goal.z, target.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, dist > 17 ? Math.max(0.9, refSpeedMod() * 1.05) : 0.7, 8);
        } else {
            m.getNavigation().stop();
        }
    }

    private void jumpscare(ServerLevel level) {
        pounceChase(level, true, 10.0F);
    }

    /** a very fast leap at the player that turns into a chase (behind-you jumpscare, path blocker, ...) */
    private void pounceChase(ServerLevel level, boolean rush, float roarVol) {
        m.setChaser(true);
        m.extendLife(2400);
        ambushing = true;
        rushTicks = rush ? 50 : 0;
        m.setRoarTicks(40);
        m.playBear(ModSounds.ROAR.get(), roarVol, 0.65F);
        m.playBear(ModSounds.ROAR_DEEP.get(), roarVol, 0.6F);
        // right in the player's ears
        level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 4.0F, 0.8F);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR.get(), SoundSource.HOSTILE, 4.0F, 0.7F);
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
        // leap at the player right now (setMode cleared the lunge state, so set it afterwards)
        pounce = true;
        lunge = Lunge.WINDUP;
        lungeTimer = 4;
        lungeHit = false;
        m.getNavigation().stop();
        m.triggerLeap();
    }

    private void vanish(ServerLevel level) {
        level.sendParticles(ParticleTypes.LARGE_SMOKE, m.getX(), m.getY() + 2.0, m.getZ(), 30, 0.6, 1.5, 0.6, 0.02);
        m.discard();
    }

    // ================================================================= PATH BLOCKER / FOG WATCHER / CAVE
    /** He waits where you go most often. Come within a random 4-12 blocks and he lunges and chases. */
    private void tickPath(ServerLevel level) {
        m.getNavigation().stop();
        if (dist <= pathTrigger && (los || dist < 7)) pounceChase(level, false, 8.0F);
    }

    /** Stands at the very edge of your view in the fog and only watches. He keeps that distance and slips away if you rush him. */
    private void tickFog(ServerLevel level) {
        if (fogDist < 0) fogDist = dist;
        if (dist < fogDist * 0.45) { beginLeaving(level); return; }
        if (dist < fogDist * 0.85) {
            Vec3 me = m.position();
            Vec3 away = new Vec3(me.x - target.getX(), 0, me.z - target.getZ());
            away = away.lengthSqr() < 0.01 ? new Vec3(1, 0, 0) : away.normalize();
            BlockPos g = CoverFinder.groundNear(level, me.x + away.x * 14, me.z + away.z * 14, m.getBlockY(), 5);
            if (g != null) navTo(level, g.getX() + 0.5, g.getY(), g.getZ() + 0.5, 0.9, 15);
            else m.getNavigation().stop();
        } else {
            m.getNavigation().stop();
        }
    }

    /** Sits in the blood with his back to you. If you come close he bolts, and vanishes once out of sight. */
    private void tickCave(ServerLevel level) {
        m.getNavigation().stop();
        if (dist < ManbearConfig.CAVE_FLEE_DISTANCE.get() && (los || dist < 6)) {
            caveFleeing = true;
            m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F);   // startled growl
            beginLeaving(level);
            return;
        }
        if (m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0 && (!los || dist > 40)) m.discard();
    }

    private BlockPos fleeSpot(ServerLevel level) {
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < 20; i++) {
            double ang = rnd().nextDouble() * Math.PI * 2;
            double d = 10 + rnd().nextInt(14);
            BlockPos g = CoverFinder.groundNear(level, m.getX() + Math.cos(ang) * d, m.getZ() + Math.sin(ang) * d, m.getBlockY(), 3);
            if (g == null) continue;
            double score = g.distSqr(target.blockPosition());
            if (CoverFinder.lineClear(level, target.getEyePosition(), Vec3.atBottomCenterOf(g).add(0, 2, 0), target)) score *= 0.5;
            if (score > bestScore) { bestScore = score; best = g; }
        }
        return best;
    }

    // ================================================================= STUN ROAR
    private void startRoar(ServerLevel level) {
        roarsUsed++;
        roarCd = 160;
        stunTicks = ManbearConfig.ROAR_STUN_TICKS.get();
        stunTarget = target;
        m.setRoarTicks(Math.max(30, stunTicks));
        m.playBear(ModSounds.ROAR.get(), 6.0F, 0.7F);
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.8F);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), ModSounds.ROAR_DEEP.get(), SoundSource.HOSTILE, 1.8F, 0.9F);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, stunTicks + 6, 6, false, false, false));
        target.addEffect(new MobEffectInstance(MobEffects.JUMP, stunTicks + 6, 128, false, false, false));
    }

    /** freezes the player and forces their camera onto him */
    private void tickStun() {
        if (stunTicks <= 0) return;
        if (stunTarget == null || !stunTarget.isAlive() || stunTarget.hasDisconnected()) { stunTicks = 0; return; }
        stunTicks--;
        if (stunTicks % 2 == 0) {
            Vec3 d = m.getEyePosition().subtract(stunTarget.getEyePosition());
            double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) (Mth.atan2(d.z, d.x) * (180D / Math.PI)) - 90.0F;
            float pitch = (float) -(Mth.atan2(d.y, horiz) * (180D / Math.PI));
            stunTarget.setYRot(yaw);
            stunTarget.setXRot(pitch);
            stunTarget.connection.teleport(stunTarget.getX(), stunTarget.getY(), stunTarget.getZ(), yaw, pitch);
        }
    }

    // ================================================================= SLEEP
    public void addNoise(float amount) {
        if (mode == ManbearMode.SLEEP) noise += amount;
    }

    private void tickSleep(ServerLevel level) {
        m.getNavigation().stop();
        m.setFaceTarget(null, 0F);
        m.setClimbing(false);
        noise = Math.max(0F, noise - 0.025F);   // calms down 0.5 per second

        if (snoreCd-- <= 0) {
            snoreCd = 80 + rnd().nextInt(100);
            m.playBear(ModSounds.SNORE.get(), 2.2F, 0.9F + rnd().nextFloat() * 0.2F);
        }
        double nearest = Double.MAX_VALUE;
        ServerPlayer nearestPlayer = null;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative() || !p.isAlive()) continue;
            double d = p.distanceToSqr(m);
            if (d < nearest) { nearest = d; nearestPlayer = p; }
        }
        // walking right up to him (not sneaking) is noisy; sneaking past is fine
        if (nearestPlayer != null && nearest < 4.0 * 4.0 && !nearestPlayer.isShiftKeyDown()) noise += 0.04F;

        if (noise >= ManbearConfig.SLEEP_NOISE_THRESHOLD.get()) { wake(level); return; }

        // sleepers fade away eventually, but only when nobody is watching
        if (m.lifeTicks > m.lifespan() && ticksAlive % 20 == 0
                && (nearestPlayer == null || nearest > 40 * 40 || !m.hasLineOfSight(nearestPlayer))) {
            m.discard();
        }
    }

    /** woken: roars and immediately chases whoever is closest */
    private void wake(ServerLevel level) {
        noise = 0;
        m.setChaser(true);          // allowed to chase this time
        m.extendLife(2400);
        ambushing = true;           // roar + instant lunge readiness
        resolveTarget(level);
        if (target == null) { m.discard(); return; }
        m.playBear(ModSounds.ROAR_DEEP.get(), 6.0F, 0.75F);
        setMode(level, ManbearMode.CHASE);
        refreshTraits(level);
    }

    /**
     * While he stalks he sometimes makes the sounds of someone else in your base: a chest or door opening
     * (and closing), a torch fizzling out, a low grumble. They come from random spots around you.
     */
    private void tickTricks(ServerLevel level) {
        for (java.util.Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (ticksAlive >= p.at()) {
                level.playSound(null, p.x(), p.y(), p.z(), p.ev(), p.src(), p.vol(), p.pitch());
                it.remove();
            }
        }
        if (trickCd-- > 0 || !ManbearConfig.AMBIENT_TRICKS.get()) return;
        int min = ManbearConfig.TRICK_MIN_INTERVAL_TICKS.get();
        int max = Math.max(min + 1, ManbearConfig.TRICK_MAX_INTERVAL_TICKS.get());
        trickCd = min + rnd().nextInt(max - min);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK;
        if (!stealthy) return;

        // somewhere 7-18 blocks from you, often behind you
        Vec3 look = target.getLookAngle();
        double base = rnd().nextFloat() < 0.5F ? Math.atan2(-look.z, -look.x) : rnd().nextDouble() * Math.PI * 2;
        double ang = base + (rnd().nextDouble() - 0.5) * 1.2;
        double d = 7 + rnd().nextDouble() * 11;
        double x = target.getX() + Math.cos(ang) * d;
        double z = target.getZ() + Math.sin(ang) * d;
        double y = target.getY();
        float pitch = 0.9F + rnd().nextFloat() * 0.2F;
        int pick = rnd().nextInt(10);
        if (pick <= 1) {                       // chest creaks open, then shuts
            level.playSound(null, x, y, z, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 1.3F, pitch);
            pending.add(new Pending(ticksAlive + 30 + rnd().nextInt(30), SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F, pitch));
        } else if (pick <= 3) {                // a door
            boolean iron = rnd().nextFloat() < 0.25F;
            level.playSound(null, x, y, z, iron ? SoundEvents.IRON_DOOR_OPEN : SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 1.4F, pitch);
            pending.add(new Pending(ticksAlive + 25 + rnd().nextInt(40), iron ? SoundEvents.IRON_DOOR_CLOSE : SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, x, y, z, 1.4F, pitch));
        } else if (pick <= 5) {                // a torch burning out
            level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F, 1.3F + rnd().nextFloat() * 0.3F);
        } else if (pick == 6) {                // barrel
            level.playSound(null, x, y, z, SoundEvents.BARREL_OPEN, SoundSource.BLOCKS, 1.3F, pitch);
            pending.add(new Pending(ticksAlive + 35 + rnd().nextInt(30), SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, x, y, z, 1.3F, pitch));
        } else {                               // low grumbling, from him
            m.playBear(rnd().nextBoolean() ? ModSounds.GROWL.get() : ModSounds.HUFF.get(), 1.4F + rnd().nextFloat(), 0.55F + rnd().nextFloat() * 0.15F);
        }
    }

    /** closer = heavier breathing */
    private void breathe() {
        if (breathCd-- > 0) return;
        breathCd = 50 + rnd().nextInt(60);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.BEHIND;
        if (!stealthy || dist >= 30) return;
        float c = 1F - (float) (dist / 30.0);
        m.playBear(ModSounds.HUFF.get(), 0.35F + 2.8F * c * c, 0.8F + rnd().nextFloat() * 0.2F);
    }

    // ================================================================= ambience
    /** distorted pig / cow / sheep noises, from him or from behind you */
    private void mimicAnimal() {
        if (mimicCd > 0 || !ManbearConfig.MIMIC_ANIMAL_SOUNDS.get()) return;
        mimicCd = 450 + rnd().nextInt(900);
        boolean stealthy = mode == ManbearMode.STALK || mode == ManbearMode.HIDE || mode == ManbearMode.PEEK
                || mode == ManbearMode.LAY_TRAP || mode == ManbearMode.RETREAT;
        if (!stealthy) return;
        SoundEvent ev;
        int pick = rnd().nextInt(3);
        if (pick == 0) ev = ModSounds.MIMIC_PIG.get();
        else if (pick == 1) ev = ModSounds.MIMIC_COW.get();
        else ev = ModSounds.MIMIC_SHEEP.get();
        double x = m.getX();
        double y = m.getY();
        double z = m.getZ();
        if (rnd().nextFloat() < 0.3F) { // from behind the player
            Vec3 look = target.getLookAngle();
            x = target.getX() - look.x * 18;
            y = target.getY();
            z = target.getZ() - look.z * 18;
        }
        m.level().playSound(null, x, y, z, ev, SoundSource.NEUTRAL, 2.2F, 1.0F);
    }

    private void ambient() {
        if (ambientCd > 0) return;
        RandomSource r = rnd();
        ambientCd = 300 + r.nextInt(600);
        switch (mode) {
            case STALK -> {
                if (r.nextFloat() < 0.7F) m.playBear(ModSounds.GROWL.get(), 3.0F, 0.8F + r.nextFloat() * 0.3F);
                else m.playBear(ModSounds.HUFF.get(), 1.6F, 0.9F);
                if (r.nextFloat() < 0.05F) m.playBear(ModSounds.ROAR.get(), 5.5F, 0.8F);
            }
            case HIDE, PEEK -> { if (dist < 22) m.playBear(ModSounds.HUFF.get(), 1.1F, 0.9F + r.nextFloat() * 0.2F); }
            case CHASE, AMBUSH -> m.playBear(ModSounds.GROWL.get(), 2.5F, 0.9F);
            case RETREAT -> m.playBear(ModSounds.GROWL.get(), 1.5F, 0.7F);
            default -> { }
        }
    }

}
