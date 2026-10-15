package com.manbear.entity;

import net.minecraft.world.entity.EntityDimensions;

public enum Stance {
    STAND(EntityDimensions.scalable(1.3F, 5.0F)),
    CROUCH(EntityDimensions.scalable(1.3F, 3.3F)),
    CRAWL(EntityDimensions.scalable(0.9F, 0.9F)),
    /** lying on his stomach, asleep */
    SLEEP(EntityDimensions.scalable(1.6F, 1.1F)),
    /** paddling through deep water */
    SWIM(EntityDimensions.scalable(1.3F, 1.3F)),
    /** sitting on the ground (cave event) */
    SIT(EntityDimensions.scalable(1.3F, 2.5F));

    public final EntityDimensions dims;

    Stance(EntityDimensions dims) { this.dims = dims; }

    public static Stance byId(int id) {
        Stance[] v = values();
        return v[Math.floorMod(id, v.length)];
    }
}
