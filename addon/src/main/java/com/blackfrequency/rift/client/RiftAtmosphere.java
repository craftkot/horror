package com.blackfrequency.rift.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Cold atmospheric fog; no changes to biome data or saved vanilla lighting. */
@Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.FORGE, value=Dist.CLIENT)
public final class RiftAtmosphere {
    private RiftAtmosphere() {}

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        float f=RiftClientState.visibleStrength();
        if(f<=0) return;
        float blend=Math.min(.96f,f*.92f);
        event.setRed(event.getRed()*(1f-blend)+.027f*blend);
        event.setGreen(event.getGreen()*(1f-blend)+.010f*blend);
        event.setBlue(event.getBlue()*(1f-blend)+.072f*blend);
    }

    @SubscribeEvent
    public static void onFogDistance(ViewportEvent.RenderFog event) {
        float f=RiftClientState.visibleStrength();
        if(f<=0) return;
        float far=Math.max(24f,event.getFarPlaneDistance()*(1f-Math.min(.88f,f*.84f)));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(),far*.22f));
        event.setCanceled(true);
    }
}
