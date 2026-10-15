package com.manbear.entity;

import com.manbear.ManbearConfig;
import com.manbear.ai.ManbearBrain;
import com.manbear.ai.ManbearMoveControl;
import com.manbear.data.ManbearData;
import com.manbear.registry.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.navigation.WallClimberNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.Animation;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class ManbearEntity extends Monster implements GeoEntity {
    private static final EntityDataAccessor<Integer> DATA_STANCE = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_MODE = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_CLIMBING = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_TICKS = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ROAR = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_PEEK = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_ATTACK_VARIANT = SynchedEntityData.defineId(ManbearEntity.class, EntityDataSerializers.INT);

    public static final int ATTACK_ANIM_TICKS = 14;


    /** the one-and-only Manbear (server side) - lets animals find him cheaply */
    private static volatile ManbearEntity current;
    public static ManbearEntity current() { return current; }

    // ---- animations (names come from manbear.animation.json) ----
    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation HUNT = RawAnimation.begin().thenLoop("hunt");
    private static final RawAnimation CROUCH_WALK = RawAnimation.begin().thenLoop("crouch_walk");
    private static final RawAnimation CRAWL = RawAnimation.begin().thenLoop("crawl");
    private static final RawAnimation RUN = RawAnimation.begin().thenLoop("run2");
    private static final RawAnimation FAKE_RUN = RawAnimation.begin().thenLoop("fake_run");
    private static final RawAnimation PEEK_LEFT = RawAnimation.begin().then("peek_left", Animation.LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation PEEK_RIGHT = RawAnimation.begin().then("peek_right", Animation.LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation SLEEP_ANIM = RawAnimation.begin().thenLoop("sleep");
    private static final RawAnimation ROAR_ANIM = RawAnimation.begin().thenLoop("roar");
    private static final RawAnimation CLIMB = RawAnimation.begin().thenLoop("climb");
    private static final RawAnimation[] ATTACKS = {
            RawAnimation.begin().thenPlay("attack_1"),
            RawAnimation.begin().thenPlay("attack_2"),
            RawAnimation.begin().thenPlay("attack_3"),
            RawAnimation.begin().then("leap", Animation.LoopType.HOLD_ON_LAST_FRAME)
    };
    private static final RawAnimation SIT_ANIM = RawAnimation.begin().thenLoop("sit");
    private static final RawAnimation SWIM_ANIM = RawAnimation.begin().thenLoop("swim");
    private static final RawAnimation PEEK_LEFT_WIDE = RawAnimation.begin().then("peek_left_wide", Animation.LoopType.HOLD_ON_LAST_FRAME);
    private static final RawAnimation PEEK_RIGHT_WIDE = RawAnimation.begin().then("peek_right_wide", Animation.LoopType.HOLD_ON_LAST_FRAME);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final ManbearBrain ai;

    private int stanceCache = 0;
    private Entity faceTarget;
    private boolean facingMode;
    private float turnSpeed = 20F;

    public int lifeTicks;
    public boolean leaving;
    private int lifespan;
    private boolean chaser;
    private boolean faker;
    private boolean trapper;
    private boolean sleeper;
    private boolean behind;
    private boolean lurer;
    private boolean pathBlocker;
    private boolean fogger;
    private boolean caver;

    public ManbearEntity(EntityType<? extends ManbearEntity> type, Level level) {
        super(type, level);
        this.setMaxUpStep(1.5F);
        this.xpReward = 40;
        this.noCulling = true;
        this.moveControl = new ManbearMoveControl(this);
        this.setPersistenceRequired();
        this.ai = new ManbearBrain(this);
        if (!level.isClientSide) {
            this.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(ManbearConfig.DAMAGE.get());
            rollKinds();
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 160.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.1D)
                .add(Attributes.ATTACK_DAMAGE, 6.0D)
                .add(Attributes.ATTACK_KNOCKBACK, 1.2D)
                .add(Attributes.FOLLOW_RANGE, 80.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.75D)
                .add(Attributes.ARMOR, 6.0D);
    }

    public ManbearBrain ai() { return ai; }

    /** ticks he will exist before he fades away */
    public int lifespan() { return lifespan; }

    /** Each spawn rolls what kind of Manbear this is. Most are only ever stalkers. */
    public void rollKinds() {
        RandomSource r = this.random;
        clearFlags();
        if (r.nextInt(ManbearConfig.FOG_CHANCE_DENOMINATOR.get()) == 0) {   // fog watcher: 1 in N of ALL spawns
            fogger = true;
            rollLifespan();
            return;
        }
        // Most encounters have NO event: he just stalks, hides and peeks.
        if (r.nextFloat() < ManbearConfig.EVENT_CHANCE.get()) {
            double[] w = {
                    1.0 / ManbearConfig.CHASE_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.FAKE_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.TRAP_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.SLEEPER_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.BEHIND_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.LURE_CHANCE_DENOMINATOR.get(),
                    1.0 / ManbearConfig.PATH_CHANCE_DENOMINATOR.get()
            };
            double total = 0;
            for (double d : w) total += d;
            double pick = r.nextDouble() * total;
            int k = 0;
            while (k < w.length - 1 && (pick -= w[k]) > 0) k++;
            switch (k) {
                case 0 -> chaser = true;
                case 1 -> faker = true;
                case 2 -> trapper = true;
                case 3 -> sleeper = true;
                case 4 -> behind = true;
                case 5 -> { lurer = true; chaser = r.nextFloat() < 0.3F; }   // the lure ends in a real chase 30% of the time
                default -> pathBlocker = true;
            }
        }
        rollLifespan();
    }

    private void clearFlags() {
        chaser = false; faker = false; trapper = false; sleeper = false; behind = false;
        lurer = false; pathBlocker = false; fogger = false; caver = false;
    }

    /** the special placement for his event was impossible: he becomes an ordinary stalker */
    public void makeOrdinary() {
        clearFlags();
        rollLifespan();
    }

    public void rollLifespan() {
        boolean longLife = sleeper || caver || pathBlocker;
        int lo = longLife ? ManbearConfig.SLEEPER_LIFETIME_MIN_SECONDS.get() : ManbearConfig.LIFETIME_MIN_SECONDS.get();
        int hi = Math.max(lo, longLife ? ManbearConfig.SLEEPER_LIFETIME_MAX_SECONDS.get() : ManbearConfig.LIFETIME_MAX_SECONDS.get());
        this.lifespan = (lo + this.random.nextInt(hi - lo + 1)) * 20;
    }

    /** for /manbear spawn <kind> */
    public void applyKind(String kind) {
        clearFlags();
        switch (kind) {
            case "chaser" -> chaser = true;
            case "faker" -> faker = true;
            case "trapper" -> trapper = true;
            case "sleeper" -> sleeper = true;
            case "behind" -> behind = true;
            case "lurer" -> lurer = true;
            case "path" -> pathBlocker = true;
            case "fog" -> fogger = true;
            case "cave" -> caver = true;
            default -> { }
        }
        rollLifespan();
    }

    public void extendLife(int ticks) { lifespan = Math.max(lifespan, lifeTicks + ticks); }
    public boolean isFaker() { return faker; }
    public boolean isTrapper() { return trapper; }
    public boolean isSleeper() { return sleeper; }
    public boolean isBehind() { return behind; }
    public boolean isLurer() { return lurer; }
    public boolean isPathBlocker() { return pathBlocker; }
    public boolean isFogger() { return fogger; }
    public boolean isCaver() { return caver; }
    public boolean isAsleep() { return getMode() == ManbearMode.SLEEP; }

    /** a noise nearby (only matters while he sleeps) */
    public void hearNoise(float amount) { ai.addNoise(amount); }

    /** true for the rare spawns where he will really hunt you down */
    public boolean isChaser() { return chaser; }
    public void setChaser(boolean b) { chaser = b; }

    // ------------------------------------------------------------------ setup
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new WallClimberNavigation(this, level);
    }

    @Override
    protected BodyRotationControl createBodyControl() {
        final ManbearEntity self = this;
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                self.yBodyRot = self.getYRot();
            }
        };
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STANCE, 0);
        this.entityData.define(DATA_MODE, 0);
        this.entityData.define(DATA_CLIMBING, false);
        this.entityData.define(DATA_ATTACK_TICKS, 0);
        this.entityData.define(DATA_PEEK, 0);
        this.entityData.define(DATA_ROAR, 0);
        this.entityData.define(DATA_ATTACK_VARIANT, 0);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        if (DATA_STANCE.equals(key)) {
            this.stanceCache = this.entityData.get(DATA_STANCE);
            this.refreshDimensions();
        }
        super.onSyncedDataUpdated(key);
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return Stance.byId(stanceCache).dims;
    }

    // ------------------------------------------------------------------ state for the brain / client
    public Stance getStance() { return Stance.byId(stanceCache); }

    public void setStance(Stance s) {
        if (s.ordinal() != stanceCache) {
            this.stanceCache = s.ordinal();
            this.entityData.set(DATA_STANCE, s.ordinal());
            this.refreshDimensions();
        }
    }

    public ManbearMode getMode() { return ManbearMode.byId(this.entityData.get(DATA_MODE)); }
    public void setSyncedMode(ManbearMode m) { this.entityData.set(DATA_MODE, m.ordinal()); }

    public boolean isClimbingFlag() { return this.entityData.get(DATA_CLIMBING); }
    public void setClimbing(boolean b) { if (b != isClimbingFlag()) this.entityData.set(DATA_CLIMBING, b); }

    /** 0 = not peeking, +1 = leaning out to his left, -1 = to his right */
    public int getPeekSide() { return this.entityData.get(DATA_PEEK); }
    public void setPeekSide(int side) { if (side != getPeekSide()) this.entityData.set(DATA_PEEK, side); }

    public int getRoarTicks() { return this.entityData.get(DATA_ROAR); }
    public void setRoarTicks(int t) { this.entityData.set(DATA_ROAR, t); }

    /** the fast pounce used by the "behind you" event (plays the "leap" animation) */
    public void triggerLeap() {
        this.entityData.set(DATA_ATTACK_VARIANT, 3);
        this.entityData.set(DATA_ATTACK_TICKS, 24);
    }

    public int getAttackTicks() { return this.entityData.get(DATA_ATTACK_TICKS); }
    public int getAttackVariant() { return this.entityData.get(DATA_ATTACK_VARIANT); }

    /** variant 0..2 -> attack_1..attack_3 */
    public void triggerAttack(int variant) {
        this.entityData.set(DATA_ATTACK_VARIANT, Mth.clamp(variant, 0, 2));
        this.entityData.set(DATA_ATTACK_TICKS, ATTACK_ANIM_TICKS);
    }

    public void setFaceTarget(Entity e, float turnDegPerTick) {
        this.faceTarget = e;
        this.facingMode = e != null;
        this.turnSpeed = turnDegPerTick;
    }

    public boolean isFacingMode() { return facingMode; }

    public void impulse(Vec3 v) {
        this.setDeltaMovement(v);
        this.hasImpulse = true;
    }

    public void playBear(SoundEvent ev, float volume, float pitch) {
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(), ev, SoundSource.HOSTILE, volume, pitch);
    }

    // ------------------------------------------------------------------ ticking
    @Override
    public void aiStep() {
        super.aiStep();
        if (!this.level().isClientSide) {
            int a = getAttackTicks();
            if (a > 0) this.entityData.set(DATA_ATTACK_TICKS, a - 1);
            int r = getRoarTicks();
            if (r > 0) setRoarTicks(r - 1);
        }
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (!(this.level() instanceof ServerLevel sl)) return;
        current = this;
        MinecraftServer server = sl.getServer();

        // there can only ever be one
        if (this.tickCount % 20 == 1) {
            ManbearData data = ManbearData.get(server);
            if (!data.claim(this.getUUID(), server.overworld().getGameTime())) {
                this.discard();
                return;
            }
        }
        lifeTicks++;
        ai.tick(sl);
        applyFacing();
    }

    private void applyFacing() {
        if (faceTarget == null || !faceTarget.isAlive()) return;
        double dx = faceTarget.getX() - this.getX();
        double dz = faceTarget.getZ() - this.getZ();
        float want = (float) (Mth.atan2(dz, dx) * (180D / Math.PI)) - 90.0F;
        float diff = Mth.wrapDegrees(want - this.getYRot());
        float yaw = this.getYRot() + Mth.clamp(diff, -turnSpeed, turnSpeed);
        this.setYRot(yaw);
        this.yBodyRot = yaw;
        this.getLookControl().setLookAt(faceTarget, 40.0F, 40.0F);
    }

    // ------------------------------------------------------------------ climbing
    @Override
    public boolean onClimbable() {
        return isClimbingFlag();
    }

    // ------------------------------------------------------------------ damage
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return super.isInvulnerableTo(source) || source.is(DamageTypes.IN_WALL) || source.is(DamageTypes.FALL);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && this.level() instanceof ServerLevel sl) {
            ai.onHurt(sl, source.getEntity());
        }
        return result;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        releaseSlot();
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (current == this) current = null;
        if (reason.shouldDestroy()) releaseSlot();
        super.remove(reason);
    }

    private void releaseSlot() {
        if (this.level() instanceof ServerLevel sl) {
            MinecraftServer server = sl.getServer();
            long cd = ManbearConfig.RESPAWN_COOLDOWN_MINUTES.get() * 1200L;
            ManbearData.get(server).release(this.getUUID(), cd, server.overworld().getGameTime());
        }
    }

    public void onKilledPlayer(ServerPlayer p) {
        if (this.level() instanceof ServerLevel sl) {
            ManbearData.get(sl.getServer()).profile(p.getUUID()).deaths++;
            ai.beginLeaving(sl);
        }
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    // ------------------------------------------------------------------ sounds
    @Override
    protected SoundEvent getAmbientSound() { return null; }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) { return ModSounds.HURT.get(); }

    @Override
    protected SoundEvent getDeathSound() { return ModSounds.DEATH.get(); }

    /** Heavy footsteps that get much louder the closer he is to you. */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        Stance st = getStance();
        if (st == Stance.CRAWL || st == Stance.SLEEP) return;
        Player near = this.level().getNearestPlayer(this, 64.0D);
        float closeness = near == null ? 0F : 1F - Mth.clamp((float) (near.distanceTo(this) / 48.0D), 0F, 1F);
        float vol = 0.35F + 4.2F * closeness * closeness;
        if (st == Stance.CROUCH) vol *= 0.45F;
        if (getMode().isChaseLike()) vol *= 1.25F;
        float pitch = 0.85F + this.random.nextFloat() * 0.2F;
        this.playSound(ModSounds.STEP.get(), vol, pitch);
        this.playSound(ModSounds.THUMP.get(), vol * 0.9F, pitch);
    }

    // ------------------------------------------------------------------ persistence
    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt("LifeTicks", lifeTicks);
        tag.putBoolean("Leaving", leaving);
        tag.putInt("Lifespan", lifespan);
        tag.putBoolean("Chaser", chaser);
        tag.putBoolean("Faker", faker);
        tag.putBoolean("Trapper", trapper);
        tag.putBoolean("Sleeper", sleeper);
        tag.putBoolean("Behind", behind);
        tag.putBoolean("Lurer", lurer);
        tag.putBoolean("PathBlocker", pathBlocker);
        tag.putBoolean("Fogger", fogger);
        tag.putBoolean("Caver", caver);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        lifeTicks = tag.getInt("LifeTicks");
        leaving = tag.getBoolean("Leaving");
        if (tag.contains("Lifespan")) lifespan = tag.getInt("Lifespan");
        if (tag.contains("Chaser")) chaser = tag.getBoolean("Chaser");
        if (tag.contains("Faker")) faker = tag.getBoolean("Faker");
        if (tag.contains("Trapper")) trapper = tag.getBoolean("Trapper");
        if (tag.contains("Sleeper")) sleeper = tag.getBoolean("Sleeper");
        if (tag.contains("Behind")) behind = tag.getBoolean("Behind");
        if (tag.contains("Lurer")) lurer = tag.getBoolean("Lurer");
        if (tag.contains("PathBlocker")) pathBlocker = tag.getBoolean("PathBlocker");
        if (tag.contains("Fogger")) fogger = tag.getBoolean("Fogger");
        if (tag.contains("Caver")) caver = tag.getBoolean("Caver");
    }

    // ------------------------------------------------------------------ trap hook
    public static void onTrapSprung(ServerPlayer player, BlockPos pos) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ManbearData data = ManbearData.get(server);
        data.profile(player.getUUID()).trapsSprung++;
        data.setDirty();
        UUID id = data.activeId();
        if (id == null) return;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e instanceof ManbearEntity m) {
                m.ai.ambush(player, pos);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ GeckoLib
    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 6, this::predicate));
        controllers.add(new AnimationController<>(this, "roar", 3, this::roarPredicate));
        controllers.add(new AnimationController<>(this, "peek", 8, this::peekPredicate));
    }

    /** the roar only moves jaw/head/torso/arms, so it plays on top of running */
    private PlayState roarPredicate(AnimationState<ManbearEntity> state) {
        if (getRoarTicks() > 0) return state.setAndContinue(ROAR_ANIM);
        return PlayState.STOP;
    }

    /** The leaning-out-from-behind-the-tree pose, held until he starts moving again. Own layer so it can never be skipped. */
    private PlayState peekPredicate(AnimationState<ManbearEntity> state) {
        int peek = getPeekSide();
        if (peek == 0 || getMode() != ManbearMode.PEEK) return PlayState.STOP;
        boolean left = (peek > 0) != ManbearConfig.SWAP_PEEK_SIDES.get();
        boolean wide = Math.abs(peek) >= 2;   // thick trunks need a deeper lean
        if (wide) return state.setAndContinue(left ? PEEK_LEFT_WIDE : PEEK_RIGHT_WIDE);
        return state.setAndContinue(left ? PEEK_LEFT : PEEK_RIGHT);
    }

    private PlayState predicate(AnimationState<ManbearEntity> state) {
        if (getMode() == ManbearMode.SLEEP) return state.setAndContinue(SLEEP_ANIM);
        if (getStance() == Stance.SIT) return state.setAndContinue(SIT_ANIM);
        if (getAttackTicks() > 0) {
            return state.setAndContinue(ATTACKS[Mth.clamp(getAttackVariant(), 0, 3)]);
        }
        if (isClimbingFlag()) return state.setAndContinue(CLIMB);
        if (getStance() == Stance.SWIM) return state.setAndContinue(SWIM_ANIM);
        boolean moving = state.isMoving();
        Stance stance = getStance();
        ManbearMode mode = getMode();
        if (stance == Stance.CRAWL) return state.setAndContinue(moving ? CRAWL : HUNT);
        if (mode.isChaseLike() && moving) return state.setAndContinue(RUN);
        if ((mode == ManbearMode.FAKE_CHASE || (mode == ManbearMode.RETREAT && stance == Stance.STAND)) && moving) return state.setAndContinue(FAKE_RUN);
        if (stance == Stance.CROUCH) return state.setAndContinue(moving ? CROUCH_WALK : HUNT);
        return state.setAndContinue(moving ? WALK : IDLE);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
