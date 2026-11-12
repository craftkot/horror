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
    private static boolean interpolationVerified;

    private RiftAtmosphere() {}

    // Render events run between simulation ticks. Clamp the partial tick so a
    // malformed renderer value cannot introduce NaN or extrapolate the pulse.
    private static double interpolatedGameTime(long gameTime,double partialTick) {
        double safePartial=Double.isFinite(partialTick)
            ? Math.max(0.0D,Math.min(1.0D,partialTick))
            : 0.0D;
        return (double)gameTime+safePartial;
    }

    private static void verifyFogInterpolation() {
        if(interpolationVerified) return;
        double midpoint=interpolatedGameTime(42L,0.5D);
        double tickBoundary=interpolatedGameTime(42L,1.0D);
        double invalid=interpolatedGameTime(42L,Double.NaN);
        double negative=interpolatedGameTime(42L,-0.5D);
        double over=interpolatedGameTime(42L,1.5D);
        if(Math.abs(midpoint-42.5D)>1.0E-9D || tickBoundary!=43.0D
            || invalid!=42.0D || negative!=42.0D || over!=43.0D)
            throw new IllegalStateException("BLACK FREQUENCY fog interpolation regression");
        interpolationVerified=true;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST FOG_INTERPOLATION_VERIFIED");
    }

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
        verifyFogInterpolation();
        double time=interpolatedGameTime(Minecraft.getInstance().level.getGameTime(),event.getPartialTick());
        float pulse=(float)(.5D+.5D*Math.sin(time*.035D));
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
        verifyFogInterpolation();
        double time=interpolatedGameTime(Minecraft.getInstance().level.getGameTime(),event.getPartialTick());
        float pulse=(float)(.5D+.5D*Math.sin(time*.029D));
        float contraction=Math.min(.88f,f*(.76f+.10f*pulse));
        float far=Math.max(24f,event.getFarPlaneDistance()*(1f-contraction));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(),far*.22f));
        event.setCanceled(true);
    }
}
