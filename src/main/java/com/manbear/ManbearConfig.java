package com.manbear;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class ManbearConfig {
    public static final ForgeConfigSpec SPEC;

    // spawning
    public static final ForgeConfigSpec.IntValue SPAWN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue SPAWN_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> DIMENSIONS;
    public static final ForgeConfigSpec.IntValue SPAWN_MIN_DIST;
    public static final ForgeConfigSpec.IntValue SPAWN_MAX_DIST;
    public static final ForgeConfigSpec.IntValue LIFETIME_MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue LIFETIME_MAX_SECONDS;
    public static final ForgeConfigSpec.IntValue RESPAWN_COOLDOWN_MINUTES;
    // behaviour
    public static final ForgeConfigSpec.IntValue GRACE_SECONDS;
    public static final ForgeConfigSpec.DoubleValue EVENT_CHANCE;
    public static final ForgeConfigSpec.IntValue CHASE_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue FAKE_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue TRAP_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue SLEEPER_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue BEHIND_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue LURE_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue PATH_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.IntValue FOG_CHANCE_DENOMINATOR;
    public static final ForgeConfigSpec.DoubleValue CAVE_EVENT_CHANCE;
    public static final ForgeConfigSpec.IntValue CAVE_FLEE_DISTANCE;
    public static final ForgeConfigSpec.IntValue PATH_MIN_DISTANCE;
    public static final ForgeConfigSpec.IntValue PATH_MAX_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue LURE_TRAP_CHANCE;
    public static final ForgeConfigSpec.BooleanValue AMBIENT_TRICKS;
    public static final ForgeConfigSpec.IntValue TRICK_MIN_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue TRICK_MAX_INTERVAL_TICKS;
    public static final ForgeConfigSpec.DoubleValue SWIM_SPEED_FACTOR;
    public static final ForgeConfigSpec.DoubleValue BEHIND_RUSH_MULTIPLIER;
    public static final ForgeConfigSpec.IntValue SLEEPER_LIFETIME_MIN_SECONDS;
    public static final ForgeConfigSpec.IntValue SLEEPER_LIFETIME_MAX_SECONDS;
    public static final ForgeConfigSpec.DoubleValue SLEEP_NOISE_THRESHOLD;
    public static final ForgeConfigSpec.IntValue ROARS_PER_CHASE;
    public static final ForgeConfigSpec.IntValue ROAR_MIN_DISTANCE;
    public static final ForgeConfigSpec.IntValue ROAR_MAX_DISTANCE;
    public static final ForgeConfigSpec.IntValue ROAR_STUN_TICKS;
    public static final ForgeConfigSpec.BooleanValue SWAP_PEEK_SIDES;
    public static final ForgeConfigSpec.DoubleValue CHASE_SPEED_BONUS_BASE;
    public static final ForgeConfigSpec.DoubleValue CHASE_SPEED_BONUS_MAX;
    public static final ForgeConfigSpec.IntValue CHASE_RAMP_SECONDS;
    public static final ForgeConfigSpec.DoubleValue DAMAGE;
    public static final ForgeConfigSpec.IntValue LUNGE_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue ADAPTATION;
    public static final ForgeConfigSpec.IntValue STALK_MIN_DISTANCE;
    public static final ForgeConfigSpec.IntValue STALK_MAX_DISTANCE;
    // animals / ambience
    public static final ForgeConfigSpec.BooleanValue ANIMALS_FLEE;
    public static final ForgeConfigSpec.IntValue ANIMAL_FLEE_RANGE;
    public static final ForgeConfigSpec.BooleanValue MIMIC_ANIMAL_SOUNDS;
    // blocks / traps
    public static final ForgeConfigSpec.BooleanValue BREAK_BLOCKS;
    public static final ForgeConfigSpec.DoubleValue MAX_BREAK_HARDNESS;
    public static final ForgeConfigSpec.BooleanValue DROP_BROKEN_BLOCKS;
    public static final ForgeConfigSpec.IntValue MAX_TRAPS;
    public static final ForgeConfigSpec.DoubleValue TRAP_DAMAGE;
    public static final ForgeConfigSpec.IntValue TRAP_ROOT_TICKS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("spawning");
        SPAWN_INTERVAL_TICKS = b.comment("Ticks between spawn rolls (1200 = 1 minute).").defineInRange("spawnIntervalTicks", 1200, 20, 1_000_000);
        SPAWN_CHANCE_DENOMINATOR = b.comment("Each roll succeeds with chance 1/N. Default 25 = 1/25 per minute.").defineInRange("spawnChanceDenominator", 25, 1, 100_000);
        DIMENSIONS = b.comment("Dimensions the Manbear may spawn in.").defineList("dimensions", List.of("minecraft:overworld"), o -> o instanceof String);
        SPAWN_MIN_DIST = b.defineInRange("spawnMinDistance", 30, 16, 128);
        SPAWN_MAX_DIST = b.defineInRange("spawnMaxDistance", 46, 20, 160);
        LIFETIME_MIN_SECONDS = b.comment("He vanishes after a random time between min and max (seconds), so sightings stay rare and scary.").defineInRange("lifetimeMinSeconds", 90, 10, 3600);
        LIFETIME_MAX_SECONDS = b.defineInRange("lifetimeMaxSeconds", 120, 10, 3600);
        RESPAWN_COOLDOWN_MINUTES = b.comment("Cool-down after he is killed or leaves before he can spawn again.").defineInRange("respawnCooldownMinutes", 3, 0, 6000);
        b.pop();

        b.push("behaviour");
        GRACE_SECONDS = b.comment("Seconds after spawning before he may start a REAL chase on his own.").defineInRange("graceSeconds", 20, 0, 3600);
        EVENT_CHANCE = b.comment("Chance that a spawn has ANY special event (chase, fake chase, traps, lure, behind-you, sleeper). Otherwise he only stalks, hides and peeks. 0.3 = 70% of encounters have no event at all.").defineInRange("eventChance", 0.3, 0.0, 1.0);
        CHASE_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the real-chase event among special events.").defineInRange("chaseChanceDenominator", 14, 1, 10_000);
        FAKE_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the fake-chase event among special events.").defineInRange("fakeChaseChanceDenominator", 9, 1, 10_000);
        TRAP_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the trap-laying event among special events.").defineInRange("trapChanceDenominator", 6, 1, 10_000);
        SLEEPER_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the sleeping event among special events. Loud noise wakes him and he chases at once.").defineInRange("sleeperChanceDenominator", 13, 1, 10_000);
        LURE_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the animal-lure event among special events.").defineInRange("lureChanceDenominator", 6, 1, 10_000);
        PATH_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the path-blocker event: he waits where you go most often and lunges if you come close.").defineInRange("pathBlockerChanceDenominator", 9, 1, 10_000);
        FOG_CHANCE_DENOMINATOR = b.comment("Each spawn has a 1/N chance of the fog-watcher event (independent of eventChance): he stands at the very edge of your render distance, half lost in fog, and just watches.").defineInRange("fogWatcherChanceDenominator", 10, 1, 10_000);
        CAVE_EVENT_CHANCE = b.comment("When he spawns while you are in a cave, this is the chance it is the cave event: him sitting in a pool of 'blood'. He runs off very fast if you get close.").defineInRange("caveEventChance", 0.35, 0.0, 1.0);
        CAVE_FLEE_DISTANCE = b.comment("The sitting cave Manbear bolts when you come this close.").defineInRange("caveFleeDistance", 12, 3, 40);
        PATH_MIN_DISTANCE = b.comment("Path blocker: he appears at one of your favourite spots this far from you...").defineInRange("pathBlockerMinDistance", 30, 12, 100);
        PATH_MAX_DISTANCE = b.defineInRange("pathBlockerMaxDistance", 40, 14, 120);
        LURE_TRAP_CHANCE = b.comment("In the lure event: chance the animal leads you into a field of bear traps (otherwise it runs straight to him).").defineInRange("lureTrapChance", 0.7, 0.0, 1.0);
        AMBIENT_TRICKS = b.comment("While stalking he sometimes makes chest / door / torch-fizzle / grumble sounds around you.").define("ambientTricks", true);
        TRICK_MIN_INTERVAL_TICKS = b.defineInRange("trickMinIntervalTicks", 500, 60, 100_000);
        TRICK_MAX_INTERVAL_TICKS = b.defineInRange("trickMaxIntervalTicks", 1400, 100, 200_000);
        SWIM_SPEED_FACTOR = b.comment("How fast he swims relative to his land speed.").defineInRange("swimSpeedFactor", 1.0, 0.2, 5.0);
        BEHIND_CHANCE_DENOMINATOR = b.comment("Relative rarity (1/N weight) of the behind-you event among special events (the rarest by default).").defineInRange("behindChanceDenominator", 20, 1, 10_000);
        BEHIND_RUSH_MULTIPLIER = b.comment("How many times your sprint speed he runs at when you turn and see him in that event.").defineInRange("behindRushMultiplier", 5.0, 1.0, 20.0);
        SLEEPER_LIFETIME_MIN_SECONDS = b.comment("A sleeping Manbear stays around longer so there is time to stumble on him.").defineInRange("sleeperLifetimeMinSeconds", 240, 30, 7200);
        SLEEPER_LIFETIME_MAX_SECONDS = b.defineInRange("sleeperLifetimeMaxSeconds", 360, 30, 7200);
        SLEEP_NOISE_THRESHOLD = b.comment("How much noise wakes him. Sneaking is silent; sprinting nearby, landing jumps, breaking or placing blocks are loud.").defineInRange("sleepNoiseThreshold", 6.0, 1.0, 1000.0);
        ROARS_PER_CHASE = b.comment("Stunning roars he can use per chase.").defineInRange("roarsPerChase", 2, 0, 20);
        ROAR_MIN_DISTANCE = b.comment("He only roars when you are at least this far away (he keeps running while you are frozen).").defineInRange("roarMinDistance", 16, 4, 100);
        ROAR_MAX_DISTANCE = b.defineInRange("roarMaxDistance", 50, 8, 200);
        ROAR_STUN_TICKS = b.comment("How long the roar freezes you and forces you to look at him (40 = 2 seconds).").defineInRange("roarStunTicks", 40, 5, 400);
        SWAP_PEEK_SIDES = b.comment("If he leans INTO the tree instead of around it, flip this. (Set it on the client.)").define("swapPeekSides", true);
        CHASE_SPEED_BONUS_BASE = b.comment("How much faster than your sprint speed he is the moment a chase starts. 0.15 = 15% faster.").defineInRange("chaseSpeedBonusBase", 0.15, 0.0, 2.0);
        CHASE_SPEED_BONUS_MAX = b.comment("The extra speed he reaches after chaseRampSeconds of chasing.").defineInRange("chaseSpeedBonusMax", 0.35, 0.0, 2.0);
        CHASE_RAMP_SECONDS = b.defineInRange("chaseRampSeconds", 30, 5, 600);
        DAMAGE = b.comment("Base attack damage (hearts x2).").defineInRange("attackDamage", 6.0, 0.5, 100.0);
        LUNGE_COOLDOWN_TICKS = b.defineInRange("lungeCooldownTicks", 70, 20, 1200);
        ADAPTATION = b.comment("Learn from each player's habits and adapt.").define("adaptation", true);
        STALK_MIN_DISTANCE = b.comment("While stalking/hiding he keeps at least this far away. He only closes in when ambushing, chasing or fake chasing.").defineInRange("stalkMinDistance", 26, 10, 80);
        STALK_MAX_DISTANCE = b.defineInRange("stalkMaxDistance", 42, 12, 100);
        b.pop();

        b.push("animals_and_ambience");
        ANIMALS_FLEE = b.comment("Animals panic and run when he is near (and scatter when he first appears).").define("animalsFlee", true);
        ANIMAL_FLEE_RANGE = b.defineInRange("animalFleeRange", 40, 8, 128);
        MIMIC_ANIMAL_SOUNDS = b.comment("Occasional distorted pig / cow / sheep noises coming from him or from behind you.").define("mimicAnimalSounds", true);
        b.pop();

        b.push("blocks_and_traps");
        BREAK_BLOCKS = b.comment("Allow him to break blocks to reach you (also needs the mobGriefing gamerule).").define("breakBlocks", true);
        MAX_BREAK_HARDNESS = b.comment("Blocks harder than this are safe (obsidian = 50).").defineInRange("maxBreakHardness", 30.0, 0.0, 2000.0);
        DROP_BROKEN_BLOCKS = b.define("dropBrokenBlocks", true);
        MAX_TRAPS = b.defineInRange("maxTraps", 8, 0, 64);
        TRAP_DAMAGE = b.defineInRange("trapDamage", 4.0, 0.0, 100.0);
        TRAP_ROOT_TICKS = b.comment("How long a sprung trap holds you in place.").defineInRange("trapRootTicks", 100, 10, 2400);
        b.pop();

        SPEC = b.build();
    }
}
