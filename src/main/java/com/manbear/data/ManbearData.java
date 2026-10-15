package com.manbear.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** World-wide state: which Manbear is the one-and-only, cool-downs and every player's profile. */
public class ManbearData extends SavedData {
    private static final String NAME = "manbear_data";
    /** if the active Manbear has not "beaten its heart" for this long, he is considered gone */
    private static final long STALE_TICKS = 6000L;

    private UUID activeId;
    private long lastBeat;
    private long cooldownUntil;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();
    private final List<Trap> traps = new ArrayList<>();

    /** a bear trap he laid; they stay in the world after he vanishes */
    public record Trap(String dim, long pos) { }

    public int trapCount() { return traps.size(); }

    /** @return the oldest traps that had to be removed to stay under the cap */
    public List<Trap> addTrap(Trap t, int max) {
        traps.add(t);
        List<Trap> evicted = new ArrayList<>();
        while (traps.size() > max && !traps.isEmpty()) evicted.add(traps.remove(0));
        setDirty();
        return evicted;
    }

    public static ManbearData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ManbearData::load, ManbearData::new, NAME);
    }

    public UUID activeId() { return activeId; }

    public boolean isActive(long now) {
        return activeId != null && now - lastBeat < STALE_TICKS;
    }

    /** Returns true if this Manbear is (or just became) the active one. */
    public boolean claim(UUID id, long now) {
        if (activeId == null || activeId.equals(id) || now - lastBeat >= STALE_TICKS) {
            activeId = id;
            lastBeat = now;
            setDirty();
            return true;
        }
        return false;
    }

    public void release(UUID id, long cooldownTicks, long now) {
        if (id.equals(activeId)) {
            activeId = null;
            cooldownUntil = Math.max(cooldownUntil, now + cooldownTicks);
            setDirty();
        }
    }

    public boolean onCooldown(long now) { return now < cooldownUntil; }

    public void clearAll() {
        activeId = null; cooldownUntil = 0; setDirty();
    }

    public PlayerProfile profile(UUID id) {
        return profiles.computeIfAbsent(id, k -> new PlayerProfile());
    }

    public void resetProfiles() { profiles.clear(); setDirty(); }

    @Override
    public CompoundTag save(CompoundTag tag) {
        if (activeId != null) tag.putUUID("active", activeId);
        tag.putLong("beat", lastBeat);
        tag.putLong("cooldown", cooldownUntil);
        CompoundTag pt = new CompoundTag();
        for (Map.Entry<UUID, PlayerProfile> e : profiles.entrySet()) {
            pt.put(e.getKey().toString(), e.getValue().save());
        }
        tag.put("profiles", pt);
        ListTag tl = new ListTag();
        for (Trap t : traps) {
            CompoundTag c = new CompoundTag();
            c.putString("d", t.dim()); c.putLong("p", t.pos());
            tl.add(c);
        }
        tag.put("traps", tl);
        return tag;
    }

    public static ManbearData load(CompoundTag tag) {
        ManbearData d = new ManbearData();
        if (tag.hasUUID("active")) d.activeId = tag.getUUID("active");
        d.lastBeat = tag.getLong("beat");
        d.cooldownUntil = tag.getLong("cooldown");
        CompoundTag pt = tag.getCompound("profiles");
        for (String key : pt.getAllKeys()) {
            try {
                d.profiles.put(UUID.fromString(key), PlayerProfile.load(pt.getCompound(key)));
            } catch (IllegalArgumentException ignored) { }
        }
        ListTag tl = tag.getList("traps", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++) {
            CompoundTag c = tl.getCompound(i);
            d.traps.add(new Trap(c.getString("d"), c.getLong("p")));
        }
        return d;
    }
}
