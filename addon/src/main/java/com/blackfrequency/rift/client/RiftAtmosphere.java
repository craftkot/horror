package com.blackfrequency.rift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Cold atmospheric fog; no changes to biome data or saved vanilla lighting. */
@Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.FORGE, value=Dist.CLIENT)
public final class RiftAtmosphere {
    private RiftAtmosphere() {}

    // Rift packets can outlive a dimension transfer. Never tint the Nether or End.
    private static boolean isOverworld() {
        var level=Minecraft.getInstance().level;
        return level!=null && level.dimension().equals(Level.OVERWORLD);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if(!isOverworld()) return;
        float f=RiftClientState.visibleStrength();
        if(f<=0) return;
        // Slow cold pulses and a subtle violet shimmer make the atmosphere feel alive.
        float time=Minecraft.getInstance().level.getGameTime();
        float pulse=.5f+.5f*(float)Math.sin(time*.035f);
        float blend=Math.min(.96f,f*(.84f+.11f*pulse));
        event.setRed(event.getRed()*(1f-blend)+(.023f+.006f*pulse)*blend);
        event.setGreen(event.getGreen()*(1f-blend)+.008f*blend);
        event.setBlue(event.getBlue()*(1f-blend)+(.065f+.018f*pulse)*blend);
    }

    @SubscribeEvent
    public static void onFogDistance(ViewportEvent.RenderFog event) {
        if(!isOverworld()) return;
        float f=RiftClientState.visibleStrength();
        if(f<=0) return;
        float time=Minecraft.getInstance().level.getGameTime();
        float pulse=.5f+.5f*(float)Math.sin(time*.029f);
        float contraction=Math.min(.88f,f*(.76f+.10f*pulse));
        float far=Math.max(24f,event.getFarPlaneDistance()*(1f-contraction));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(),far*.22f));
        event.setCanceled(true);
    }
}
