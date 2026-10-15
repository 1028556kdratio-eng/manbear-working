package com.manbear.registry;

import com.manbear.ManbearMod;
import com.manbear.block.BearTrapBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ManbearMod.MODID);

    public static final RegistryObject<Block> BEAR_TRAP = BLOCKS.register("bear_trap",
            () -> new BearTrapBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(1.5F)
                    .sound(SoundType.METAL)
                    .noCollission()
                    .noOcclusion()));
}
