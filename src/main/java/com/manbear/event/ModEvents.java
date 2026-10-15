package com.manbear.event;

import com.manbear.ManbearMod;
import com.manbear.entity.ManbearEntity;
import com.manbear.client.ManbearRenderer;
import com.manbear.registry.ModEntities;
import com.manbear.registry.ModItems;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public class ModEvents {
    @Mod.EventBusSubscriber(modid = ManbearMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class Common {
        @SubscribeEvent
        public static void attributes(EntityAttributeCreationEvent e) {
            e.put(ModEntities.MANBEAR.get(), ManbearEntity.createAttributes().build());
        }

        @SubscribeEvent
        public static void tabs(BuildCreativeModeTabContentsEvent e) {
            if (e.getTabKey() == CreativeModeTabs.SPAWN_EGGS) e.accept(ModItems.MANBEAR_SPAWN_EGG);
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(ModItems.BEAR_TRAP);
        }
    }

    @Mod.EventBusSubscriber(modid = ManbearMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class Client {
        @SubscribeEvent
        public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
            e.registerEntityRenderer(ModEntities.MANBEAR.get(), ManbearRenderer::new);
        }
    }
}
