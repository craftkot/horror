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
    private static final float MIN_FOG_FAR_DISTANCE=36.0F;

    private static float finiteUnit(float value) {
        return Float.isFinite(value) ? Math.max(0.0F,Math.min(1.0F,value)) : 0.0F;
    }

    private static boolean validFogPlanes(float near,float far) {
        return Float.isFinite(near) && Float.isFinite(far) && far>0.0F;
    }

    // Preserve some vanilla fog color for distant terrain. Cloud geometry is
    // controlled separately; this event only changes the fog color.
    private static float fogColorBlend(float strength,float pulse) {
        return Math.min(.76F,finiteUnit(strength)*(.66F+.10F*finiteUnit(pulse)));
    }

    private static float fogDistanceContraction(float strength,float pulse) {
        return Math.min(.72F,finiteUnit(strength)*(.62F+.10F*finiteUnit(pulse)));
    }

    private static float fogFarDistance(float farInput,float contraction) {
        return Math.max(MIN_FOG_FAR_DISTANCE,farInput*(1.0F-finiteUnit(contraction)));
    }

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
        float nanStrength=finiteUnit(Float.NaN);
        float positiveInfinityStrength=finiteUnit(Float.POSITIVE_INFINITY);
        float negativeInfinityStrength=finiteUnit(Float.NEGATIVE_INFINITY);
        float lowStrength=finiteUnit(-0.5F);
        float highStrength=finiteUnit(1.5F);
        boolean validPlanes=validFogPlanes(0.05F,256.0F);
        boolean nanNearRejected=validFogPlanes(Float.NaN,256.0F);
        boolean nanFarRejected=validFogPlanes(0.05F,Float.NaN);
        boolean infiniteFarRejected=validFogPlanes(0.05F,Float.POSITIVE_INFINITY);
        boolean zeroFarRejected=validFogPlanes(0.05F,0.0F);
        if(Math.abs(midpoint-42.5D)>1.0E-9D || tickBoundary!=43.0D
            || invalid!=42.0D || negative!=42.0D || over!=43.0D)
            throw new IllegalStateException("BLACK FREQUENCY fog interpolation regression");
        if(nanStrength!=0.0F || positiveInfinityStrength!=0.0F
            || negativeInfinityStrength!=0.0F || lowStrength!=0.0F || highStrength!=1.0F
            || !validPlanes || nanNearRejected || nanFarRejected
            || infiniteFarRejected || zeroFarRejected)
            throw new IllegalStateException("BLACK FREQUENCY fog input guard regression");
        float lowPulseBlend=fogColorBlend(1.0F,0.0F);
        float highPulseBlend=fogColorBlend(1.0F,1.0F);
        float invalidStrengthBlend=fogColorBlend(Float.NaN,1.0F);
        float lowPulseContraction=fogDistanceContraction(1.0F,0.0F);
        float highPulseContraction=fogDistanceContraction(1.0F,1.0F);
        float invalidStrengthContraction=fogDistanceContraction(Float.NaN,1.0F);
        float farAt256=fogFarDistance(256.0F,highPulseContraction);
        float minimumFar=fogFarDistance(16.0F,highPulseContraction);
        if(Math.abs(lowPulseBlend-.66F)>1.0E-6F
            || Math.abs(highPulseBlend-.76F)>1.0E-6F
            || invalidStrengthBlend!=0.0F
            || Math.abs(lowPulseContraction-.62F)>1.0E-6F
            || Math.abs(highPulseContraction-.72F)>1.0E-6F
            || invalidStrengthContraction!=0.0F
            || Math.abs(farAt256-71.68F)>1.0E-3F
            || Math.abs(minimumFar-MIN_FOG_FAR_DISTANCE)>1.0E-6F)
            throw new IllegalStateException("BLACK FREQUENCY fog contrast/distance regression");
        interpolationVerified=true;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST FOG_INTERPOLATION_VERIFIED");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST FOG_INPUT_GUARDS_VERIFIED");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST FOG_COLOR_BLEND_PROFILE_VERIFIED blendMin={} blendMax={}",
            lowPulseBlend,highPulseBlend);
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST FOG_DISTANCE_PROFILE_VERIFIED contractionMin={} contractionMax={} farFloor={} farAt256={}",
            lowPulseContraction,highPulseContraction,MIN_FOG_FAR_DISTANCE,farAt256);
    }

    // Rift packets can outlive a dimension transfer. Never tint the Nether or End.
    private static boolean isOverworld() {
        var level=Minecraft.getInstance().level;
        return level!=null && level.dimension().equals(Level.OVERWORLD);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if(!isOverworld()) return;
        float f=finiteUnit(RiftClientState.visibleStrength());
        if(f<=0) return;
        // Slow cold pulses and a subtle violet shimmer make the atmosphere feel alive.
        verifyFogInterpolation();
        double time=interpolatedGameTime(Minecraft.getInstance().level.getGameTime(),event.getPartialTick());
        float pulse=(float)(.5D+.5D*Math.sin(time*.035D));
        float blend=fogColorBlend(f,pulse);
        event.setRed(event.getRed()*(1f-blend)+(.065f+.018f*pulse)*blend);
        event.setGreen(event.getGreen()*(1f-blend)+.014f*blend);
        event.setBlue(event.getBlue()*(1f-blend)+(.14f+.035f*pulse)*blend);
    }

    @SubscribeEvent
    public static void onFogDistance(ViewportEvent.RenderFog event) {
        if(!isOverworld()) return;
        float f=finiteUnit(RiftClientState.visibleStrength());
        if(f<=0) return;
        verifyFogInterpolation();
        double time=interpolatedGameTime(Minecraft.getInstance().level.getGameTime(),event.getPartialTick());
        float pulse=(float)(.5D+.5D*Math.sin(time*.029D));
        float nearInput=event.getNearPlaneDistance();
        float farInput=event.getFarPlaneDistance();
        if(!validFogPlanes(nearInput,farInput)) return;
        float contraction=fogDistanceContraction(f,pulse);
        float far=fogFarDistance(farInput,contraction);
        float near=Math.min(Math.max(0.0F,nearInput),far*.22f);
        if(!validFogPlanes(near,far)) return;
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(near);
        event.setCanceled(true);
    }
}
