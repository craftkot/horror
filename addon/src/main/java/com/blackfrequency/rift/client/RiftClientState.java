package com.blackfrequency.rift.client;

import com.blackfrequency.rift.RiftGeometry;
import java.util.ArrayList;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import java.util.List;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="blackfrequency", value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class RiftClientState {
    private static float progress;
    private static boolean collapse;
    private static int finaleTicks;
    // Last authoritative server value; finaleTicks is the bounded client prediction.
    private static int serverFinaleTicks;
    private static final int RIFT_OPENING_RAMP_TICKS=60;
    // The green corruption flash is a short phase, but its color must not pop at either edge.
    private static final int GREEN_PHASE_START_TICK=100;
    private static final int GREEN_PHASE_END_TICK=180;
    private static final int GREEN_PHASE_FADE_TICKS=8;
    // Active world-state packets are sent every ten server ticks.
    private static final int RIFT_STATE_PACKET_INTERVAL_TICKS=10;
    private static final boolean VERIFY_RIFT_CLIENT_PROFILES=
        Boolean.getBoolean("blackfrequency.verifyGroundMeshProfiles");
    // A separate disposable CI client may preview the shader's peak green phase.
    // The override is inert unless the profile verifier is explicitly enabled.
    private static final boolean CI_GREEN_PHASE_PREVIEW=
        VERIFY_RIFT_CLIENT_PROFILES && Boolean.getBoolean("blackfrequency.ciGreenPhasePreview");
    private static boolean ciGreenPhasePreviewLogged;
    private static boolean riftOpeningRampProfileLogged;
    private static int ticksSincePacket;
    private static int anchorX;
    private static int anchorZ;
    private static boolean anchorValid;
    private static long beaconPosition=Long.MIN_VALUE;
    private static BlockPos beaconBlockPos;
    private static boolean beaconPositionValid;
    private static boolean restorationActive;
    private static boolean beaconAuraProfileLogged;
    private static boolean beaconAuraRenderedLogged;
    private static boolean beaconAuraRestorationRenderedLogged;
    private static final double BEACON_AURA_RENDER_DISTANCE=48.0D;
    private static final double BEACON_AURA_RENDER_DISTANCE_SQR=
        BEACON_AURA_RENDER_DISTANCE*BEACON_AURA_RENDER_DISTANCE;
    // Fade the cage over the outer eight blocks instead of popping at the render boundary.
    private static final double BEACON_AURA_FADE_START_DISTANCE=40.0D;
    private static final double BEACON_AURA_FADE_START_DISTANCE_SQR=
        BEACON_AURA_FADE_START_DISTANCE*BEACON_AURA_FADE_START_DISTANCE;
    private static final double BEACON_AURA_OUTER_MARGIN=.14D;
    private static final double BEACON_AURA_INNER_MARGIN=.025D;
    // Camera-facing ribbons keep the cage readable without relying on platform-specific GL line widths.
    private static final double BEACON_AURA_OUTER_HALF_WIDTH=.022D;
    private static final double BEACON_AURA_INNER_HALF_WIDTH=.012D;
    // The outer cage frames the physical beacon without competing with its core.
    private static final double BEACON_AURA_OUTER_ALPHA_BASE=.30D;
    private static final double BEACON_AURA_OUTER_ALPHA_PULSE=.18D;
    // Both cages complete whole turns at the same 60-second boundary, so wrapping
    // game time cannot snap the client-only containment animation.
    private static final long BEACON_AURA_ROTATION_PERIOD=1200L;
    // World identity also changes when reconnecting to a different server in the same dimension.
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private static final int CLIENT_STATE_PACKET_TIMEOUT_TICKS=100;
    private static final int BEACON_AURA_COMPLETION_FADE_TICKS=16;
    private static int beaconAuraCompletionFadeTicks;
    private static boolean beaconAuraCompletionFadeProfileLogged;
    private static boolean beaconAuraCompletionFadeRenderedLogged;
    private static boolean beaconAuraCompletionFadeMidpointRenderedLogged;
    private static boolean beaconAuraCompletionFadeTailRenderedLogged;
    private RiftClientState() {}
    public static void update(float value, boolean collapsing, int ticks, int worldAnchorX, int worldAnchorZ, long worldBeaconPosition, boolean worldRestorationActive) {
        // Packets may arrive before the first client tick after joining a world.
        // Bind the state to that world immediately, rather than carrying it across sessions.
        net.minecraft.client.multiplayer.ClientLevel level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null) { reset("client-level-unavailable"); lastLevel=null; return; }
        if(lastLevel!=null && lastLevel!=level) reset("client-level-replaced");
        lastLevel=level;
        ticksSincePacket=0;
        // Network data must never feed NaN or infinity into sky and overlay shaders.
        float safeProgress=Float.isFinite(value) ? Math.max(0f,Math.min(1f,value)) : 0f;
        boolean wasRestoring=restorationActive;
        beaconAuraCompletionFadeTicks=nextBeaconAuraCompletionFadeTicks(
            beaconAuraCompletionFadeTicks,wasRestoring,worldRestorationActive,collapsing,safeProgress);
        if(worldRestorationActive || collapsing || safeProgress>0f) {
            beaconAuraCompletionFadeRenderedLogged=false;
            beaconAuraCompletionFadeMidpointRenderedLogged=false;
            beaconAuraCompletionFadeTailRenderedLogged=false;
        }
        progress=safeProgress;
        collapse=collapsing;
        serverFinaleTicks=Math.max(0,ticks);
        finaleTicks=serverFinaleTicks;
        if(VERIFY_RIFT_CLIENT_PROFILES) verifyRiftOpeningRampProfile();
        anchorX=worldAnchorX;
        anchorZ=worldAnchorZ;
        if(worldBeaconPosition!=beaconPosition) {
            beaconPosition=worldBeaconPosition;
            beaconPositionValid=worldBeaconPosition!=Long.MIN_VALUE;
            beaconBlockPos=beaconPositionValid?BlockPos.of(worldBeaconPosition):null;
        }
        restorationActive=worldRestorationActive;
        anchorValid=Math.abs((long)worldAnchorX)<=30_000_000L
            && Math.abs((long)worldAnchorZ)<=30_000_000L;
    }
    public static float strength() { return progress; }
    public static float greenPhase() {
        if(CI_GREEN_PHASE_PREVIEW && progress>0.0F) {
            if(!ciGreenPhasePreviewLogged) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_CI_GREEN_PHASE_PREVIEW_ACTIVE strength={} collapse={} finaleTicks={}",
                    progress,collapse,finaleTicks);
                ciGreenPhasePreviewLogged=true;
            }
            return 1.0F;
        }
        return greenPhase(collapse,finaleTicks);
    }

    // Quintic easing keeps both velocity and acceleration continuous at phase boundaries.
    private static float smootherstep01(float value) {
        return (float)smootherstep01((double)value);
    }

    private static double smootherstep01(double value) {
        if(!Double.isFinite(value)) return 0.0D;
        double t=Math.max(0.0D,Math.min(1.0D,value));
        double t2=t*t;
        double t3=t2*t;
        return t3*(t*(t*6.0D-15.0D)+10.0D);
    }

    private static float smootherstepDerivative01(float value) {
        return (float)smootherstepDerivative01((double)value);
    }

    private static double smootherstepDerivative01(double value) {
        if(!Double.isFinite(value)) return 0.0D;
        double t=Math.max(0.0D,Math.min(1.0D,value));
        double oneMinusT=1.0D-t;
        return 30.0D*t*t*oneMinusT*oneMinusT;
    }

    private static float smootherstepSecondDerivative01(float value) {
        return (float)smootherstepSecondDerivative01((double)value);
    }

    private static double smootherstepSecondDerivative01(double value) {
        if(!Double.isFinite(value)) return 0.0D;
        double t=Math.max(0.0D,Math.min(1.0D,value));
        return 60.0D*t*(1.0D-t)*(1.0D-2.0D*t);
    }

    private static void verifySmootherstepProfile() {
        float start=smootherstep01(0.0F);
        float quarter=smootherstep01(.25F);
        float midpoint=smootherstep01(.5F);
        float threeQuarter=smootherstep01(.75F);
        float end=smootherstep01(1.0F);
        boolean monotonic=true;
        float previous=start;
        for(int sample=1;sample<=1000;sample++) {
            float current=smootherstep01(sample/1000.0F);
            monotonic &= Float.isFinite(current) && current>=previous
                && current>=0.0F && current<=1.0F;
            previous=current;
        }
        boolean firstDerivativeZero=smootherstepDerivative01(0.0F)==0.0F
            && smootherstepDerivative01(1.0F)==0.0F;
        boolean secondDerivativeZero=smootherstepSecondDerivative01(0.0F)==0.0F
            && smootherstepSecondDerivative01(1.0F)==0.0F;
        boolean invalidFallback=smootherstep01(Float.NaN)==0.0F
            && smootherstep01(Float.POSITIVE_INFINITY)==0.0F
            && smootherstep01(Float.NEGATIVE_INFINITY)==0.0F;
        boolean boundedClamp=smootherstep01(-1.0F)==0.0F && smootherstep01(2.0F)==1.0F;
        if(start!=0.0F || Math.abs(quarter-.103515625F)>1.0E-6F
            || midpoint!=.5F || Math.abs(threeQuarter-.896484375F)>1.0E-6F
            || end!=1.0F || !firstDerivativeZero || !secondDerivativeZero
            || !monotonic || !invalidFallback || !boundedClamp)
            throw new IllegalStateException("BLACK FREQUENCY smootherstep profile regression");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_SMOOTHERSTEP_PROFILE_VERIFIED curve=quintic quarter={} midpoint={} threeQuarter={} endpoints=true firstDerivativeZero=true secondDerivativeZero=true monotonic=true bounded=true invalidFallback=true",
            quarter,midpoint,threeQuarter);
    }

    private static float greenPhase(boolean collapsing,int ticks) {
        if(!collapsing || ticks<GREEN_PHASE_START_TICK || ticks>GREEN_PHASE_END_TICK) return 0.0F;
        float fadeIn=smootherstep01((ticks-GREEN_PHASE_START_TICK)/(float)GREEN_PHASE_FADE_TICKS);
        float fadeOut=smootherstep01((GREEN_PHASE_END_TICK-ticks)/(float)GREEN_PHASE_FADE_TICKS);
        return Math.min(fadeIn,fadeOut);
    }

    private static void verifyGreenPhaseProfile() {
        float before=greenPhase(true,GREEN_PHASE_START_TICK-1);
        float start=greenPhase(true,GREEN_PHASE_START_TICK);
        float fadeInQuarter=greenPhase(true,GREEN_PHASE_START_TICK+GREEN_PHASE_FADE_TICKS/4);
        float fadeInMidpoint=greenPhase(true,GREEN_PHASE_START_TICK+GREEN_PHASE_FADE_TICKS/2);
        float fadeInThreeQuarter=greenPhase(true,GREEN_PHASE_START_TICK+3*GREEN_PHASE_FADE_TICKS/4);
        float peak=greenPhase(true,GREEN_PHASE_START_TICK+GREEN_PHASE_FADE_TICKS);
        float plateau=greenPhase(true,(GREEN_PHASE_START_TICK+GREEN_PHASE_END_TICK)/2);
        float plateauEnd=greenPhase(true,GREEN_PHASE_END_TICK-GREEN_PHASE_FADE_TICKS);
        float fadeOutThreeQuarter=greenPhase(true,GREEN_PHASE_END_TICK-3*GREEN_PHASE_FADE_TICKS/4);
        float fadeOutMidpoint=greenPhase(true,GREEN_PHASE_END_TICK-GREEN_PHASE_FADE_TICKS/2);
        float fadeOutQuarter=greenPhase(true,GREEN_PHASE_END_TICK-GREEN_PHASE_FADE_TICKS/4);
        float end=greenPhase(true,GREEN_PHASE_END_TICK);
        float after=greenPhase(true,GREEN_PHASE_END_TICK+1);
        boolean bounded=true;
        for(int tick=0;tick<=GREEN_PHASE_END_TICK+20;tick++) {
            float sample=greenPhase(true,tick);
            bounded &= Float.isFinite(sample) && sample>=0.0F && sample<=1.0F;
        }
        boolean rising=true;
        float previous=start;
        for(int tick=GREEN_PHASE_START_TICK+1;tick<=GREEN_PHASE_START_TICK+GREEN_PHASE_FADE_TICKS;tick++) {
            float current=greenPhase(true,tick);
            rising &= current>=previous;
            previous=current;
        }
        boolean falling=true;
        previous=plateauEnd;
        for(int tick=GREEN_PHASE_END_TICK-GREEN_PHASE_FADE_TICKS+1;tick<=GREEN_PHASE_END_TICK;tick++) {
            float current=greenPhase(true,tick);
            falling &= current<=previous;
            previous=current;
        }
        if(before!=0.0F || start!=0.0F
            || Math.abs(fadeInQuarter-.103515625F)>1.0E-6F
            || Math.abs(fadeInMidpoint-.5F)>1.0E-6F
            || Math.abs(fadeInThreeQuarter-.896484375F)>1.0E-6F
            || peak!=1.0F || plateau!=1.0F || plateauEnd!=1.0F
            || Math.abs(fadeOutThreeQuarter-.896484375F)>1.0E-6F
            || Math.abs(fadeOutMidpoint-.5F)>1.0E-6F
            || Math.abs(fadeOutQuarter-.103515625F)>1.0E-6F
            || end!=0.0F || after!=0.0F
            || greenPhase(false,(GREEN_PHASE_START_TICK+GREEN_PHASE_END_TICK)/2)!=0.0F
            || !bounded || !rising || !falling)
            throw new IllegalStateException("BLACK FREQUENCY green corruption phase easing regression");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_GREEN_PHASE_EASING_VERIFIED curve=quintic start={} fadeTicks={} peak={} plateauEnd={} end={} fadeInQuarter={} fadeInMidpoint={} fadeInThreeQuarter={} fadeOutThreeQuarter={} fadeOutMidpoint={} fadeOutQuarter={} bounded=true monotonic=true c2Endpoints=true",
            GREEN_PHASE_START_TICK,GREEN_PHASE_FADE_TICKS,
            GREEN_PHASE_START_TICK+GREEN_PHASE_FADE_TICKS,
            GREEN_PHASE_END_TICK-GREEN_PHASE_FADE_TICKS,GREEN_PHASE_END_TICK,
            fadeInQuarter,fadeInMidpoint,fadeInThreeQuarter,
            fadeOutThreeQuarter,fadeOutMidpoint,fadeOutQuarter);
    }

    // Ease the first three seconds of the rift in without changing its final strength.
    private static float riftOpeningRamp(int ticks) {
        return smootherstep01(ticks/(float)RIFT_OPENING_RAMP_TICKS);
    }

    private static void verifyRiftOpeningRampProfile() {
        if(riftOpeningRampProfileLogged) return;
        verifySmootherstepProfile();
        float start=riftOpeningRamp(0);
        float quarter=riftOpeningRamp(15);
        float midpoint=riftOpeningRamp(30);
        float threeQuarter=riftOpeningRamp(45);
        float end=riftOpeningRamp(RIFT_OPENING_RAMP_TICKS);
        boolean monotonic=true;
        float previous=start;
        for(int tick=1;tick<=RIFT_OPENING_RAMP_TICKS;tick++) {
            float current=riftOpeningRamp(tick);
            monotonic &= Float.isFinite(current) && current>=previous && current<=1.0F;
            previous=current;
        }
        int packetStart=predictedFinaleTicks(0,0);
        int packetMiddle=predictedFinaleTicks(0,5);
        int packetEnd=predictedFinaleTicks(0,RIFT_STATE_PACKET_INTERVAL_TICKS);
        int packetLate=predictedFinaleTicks(0,RIFT_STATE_PACKET_INTERVAL_TICKS+50);
        int packetBase=predictedFinaleTicks(55,5);
        int packetNegative=predictedFinaleTicks(-4,-2);
        int packetOverflow=predictedFinaleTicks(Integer.MAX_VALUE,100);
        boolean packetMonotonic=true;
        int previousPacketTick=packetStart;
        for(int age=1;age<=RIFT_STATE_PACKET_INTERVAL_TICKS;age++) {
            int current=predictedFinaleTicks(0,age);
            packetMonotonic &= current>=previousPacketTick;
            previousPacketTick=current;
        }
        if(start!=0.0F || Math.abs(quarter-.103515625F)>1.0E-6F
            || Math.abs(midpoint-.5F)>1.0E-6F
            || Math.abs(threeQuarter-.896484375F)>1.0E-6F
            || end!=1.0F || riftOpeningRamp(-1)!=0.0F
            || riftOpeningRamp(RIFT_OPENING_RAMP_TICKS+1)!=1.0F || !monotonic)
            throw new IllegalStateException("BLACK FREQUENCY rift opening ramp regression");
        if(packetStart!=0 || packetMiddle!=5 || packetEnd!=10 || packetLate!=10
            || packetBase!=60 || packetNegative!=0 || packetOverflow!=Integer.MAX_VALUE
            || !packetMonotonic)
            throw new IllegalStateException("BLACK FREQUENCY rift packet interpolation regression");
        verifyGreenPhaseProfile();
        riftOpeningRampProfileLogged=true;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_RIFT_OPENING_RAMP_PROFILE_VERIFIED ticks={} curve=quintic quarter={} midpoint={} threeQuarter={} endpoints=true monotonic=true",
            RIFT_OPENING_RAMP_TICKS,quarter,midpoint,threeQuarter);
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_RIFT_PACKET_INTERPOLATION_VERIFIED intervalTicks={} cap={} monotonic=true overflowSafe=true negativeSafe=true",
            RIFT_STATE_PACKET_INTERVAL_TICKS,packetEnd);
    }

    public static float visibleStrength() {
        return collapse ? strength()*riftOpeningRamp(finaleTicks) : strength();
    }
    public static boolean collapsing() { return collapse; }
    public static boolean hasAnchor() { return anchorValid; }
    public static int anchorX() { return anchorX; }
    public static int anchorZ() { return anchorZ; }
    public static void reset() {
        reset("explicit-reset");
    }
    private static void reset(String reason) {
        if(VERIFY_RIFT_CLIENT_PROFILES) {
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST CLIENT_STATE_RESET reason={} previousLevelPresent={}",reason,lastLevel!=null);
        }
        progress=0;collapse=false;finaleTicks=0;serverFinaleTicks=0;ticksSincePacket=0;
        ciGreenPhasePreviewLogged=false;
        anchorX=0;anchorZ=0;anchorValid=false;
        beaconPosition=Long.MIN_VALUE;beaconBlockPos=null;beaconPositionValid=false;restorationActive=false;
        beaconAuraCompletionFadeTicks=0;beaconAuraCompletionFadeProfileLogged=false;
        beaconAuraCompletionFadeRenderedLogged=false;
        beaconAuraCompletionFadeMidpointRenderedLogged=false;
        beaconAuraCompletionFadeTailRenderedLogged=false;
        beaconAuraProfileLogged=false;beaconAuraRenderedLogged=false;
        beaconAuraRestorationRenderedLogged=false;
        GroundRenderer.clearCache(reason);
    }

    private static boolean shouldRenderBeaconAura(boolean active,boolean hasPosition,
            boolean chunkLoaded,boolean beaconBlock,double distanceSquared) {
        return active && hasPosition && chunkLoaded && beaconBlock
            && Double.isFinite(distanceSquared) && distanceSquared>=0.0D
            && distanceSquared<BEACON_AURA_RENDER_DISTANCE_SQR;
    }

    private static boolean shouldShowBeaconAura(boolean collapsing,float value,boolean restoring) {
        return restoring || collapsing || (Float.isFinite(value) && value>0.0F);
    }

    private static boolean hasActiveTimedClientState(float value,boolean collapsing,
            int finaleTicks,boolean restoring) {
        return restoring || collapsing || finaleTicks>0
            || (Float.isFinite(value) && value>0.0F);
    }

    private static int predictedFinaleTicks(int packetFinaleTicks,int packetAgeTicks) {
        long base=Math.max(0L,(long)packetFinaleTicks);
        long age=Math.max(0L,Math.min((long)RIFT_STATE_PACKET_INTERVAL_TICKS,(long)packetAgeTicks));
        return (int)Math.min((long)Integer.MAX_VALUE,base+age);
    }

    private static boolean isClientPacketStateStale(int ticksSincePacket) {
        return ticksSincePacket>CLIENT_STATE_PACKET_TIMEOUT_TICKS;
    }

    private static int nextBeaconAuraCompletionFadeTicks(int previousTicks,boolean wasRestoring,
            boolean restoring,boolean collapsing,float value) {
        if(restoring || collapsing || (Float.isFinite(value) && value>0.0F)) return 0;
        if(wasRestoring) return BEACON_AURA_COMPLETION_FADE_TICKS;
        return Math.max(0,Math.min(BEACON_AURA_COMPLETION_FADE_TICKS,previousTicks));
    }

    private static double beaconAuraCompletionFade(int ticksRemaining,double partialTick) {
        int boundedTicks=Math.max(0,Math.min(BEACON_AURA_COMPLETION_FADE_TICKS,ticksRemaining));
        if(boundedTicks==0) return 0.0D;
        double partial=Double.isFinite(partialTick)
            ? Math.max(0.0D,Math.min(1.0D,partialTick)) : 0.0D;
        double remaining=Math.max(0.0D,Math.min(1.0D,
            (boundedTicks-partial)/BEACON_AURA_COMPLETION_FADE_TICKS));
        return smootherstep01(remaining);
    }

    // Contract the containment cage toward the physical beacon while its completion alpha fades.
    private static double beaconAuraCompletionScale(double fade) {
        double bounded=Double.isFinite(fade)?Math.max(0.0D,Math.min(1.0D,fade)):0.0D;
        return .55D+.45D*bounded;
    }

    private static double beaconAuraDistanceFade(double distanceSquared) {
        if(!Double.isFinite(distanceSquared) || distanceSquared<0.0D
            || distanceSquared>=BEACON_AURA_RENDER_DISTANCE_SQR) return 0.0D;
        if(distanceSquared<=BEACON_AURA_FADE_START_DISTANCE_SQR) return 1.0D;
        double distance=Math.sqrt(distanceSquared);
        double t=(BEACON_AURA_RENDER_DISTANCE-distance)
            /(BEACON_AURA_RENDER_DISTANCE-BEACON_AURA_FADE_START_DISTANCE);
        t=Math.max(0.0D,Math.min(1.0D,t));
        return smootherstep01(t);
    }

    private static boolean matchesCachedBeaconPosition(BlockPos pos,long packed) {
        return pos!=null && packed!=Long.MIN_VALUE && pos.asLong()==packed;
    }

    private static boolean isSafeBeaconPosition(BlockPos pos,long packed,
            net.minecraft.client.multiplayer.ClientLevel level) {
        return matchesCachedBeaconPosition(pos,packed)
            && pos.getY()>=level.getMinBuildHeight() && pos.getY()<level.getMaxBuildHeight()
            && Math.abs((long)pos.getX())<=30_000_000L
            && Math.abs((long)pos.getZ())<=30_000_000L;
    }

    private static double beaconAuraRotationAngle(long gameTime,double partialTick,int turns) {
        double safePartial=Double.isFinite(partialTick)
            ? Math.max(0.0D,Math.min(1.0D,partialTick)) : 0.0D;
        long phaseTick=Math.floorMod(gameTime,BEACON_AURA_ROTATION_PERIOD);
        return (phaseTick+safePartial)*(Math.PI*2.0D/BEACON_AURA_ROTATION_PERIOD)*turns;
    }

    private static void verifyBeaconAuraRotationProfile() {
        double outerBefore=beaconAuraRotationAngle(BEACON_AURA_ROTATION_PERIOD-1L,1.0D,1);
        double outerAfter=beaconAuraRotationAngle(BEACON_AURA_ROTATION_PERIOD,0.0D,1);
        double innerBefore=beaconAuraRotationAngle(BEACON_AURA_ROTATION_PERIOD-1L,1.0D,-2);
        double innerAfter=beaconAuraRotationAngle(BEACON_AURA_ROTATION_PERIOD,0.0D,-2);
        double outerQuarter=beaconAuraRotationAngle(300L,0.0D,1);
        double innerQuarter=beaconAuraRotationAngle(300L,0.0D,-2);
        double invalidPartial=beaconAuraRotationAngle(0L,Double.NaN,1);
        double overrunPartial=beaconAuraRotationAngle(0L,2.0D,1);
        boolean wrapContinuous=Math.abs(Math.cos(outerBefore)-Math.cos(outerAfter))<1.0E-12D
            && Math.abs(Math.sin(outerBefore)-Math.sin(outerAfter))<1.0E-12D
            && Math.abs(Math.cos(innerBefore)-Math.cos(innerAfter))<1.0E-12D
            && Math.abs(Math.sin(innerBefore)-Math.sin(innerAfter))<1.0E-12D;
        boolean counterRotating=Math.abs(outerQuarter-Math.PI*.5D)<1.0E-12D
            && Math.abs(innerQuarter+Math.PI)<1.0E-12D;
        boolean partialBounded=invalidPartial==0.0D
            && Math.abs(overrunPartial-Math.PI*2.0D/BEACON_AURA_ROTATION_PERIOD)<1.0E-12D;
        if(!wrapContinuous || !counterRotating || !partialBounded)
            throw new IllegalStateException("BLACK FREQUENCY beacon aura rotation regression");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_ROTATION_PROFILE_VERIFIED periodTicks=1200 outerTurns=1 innerTurns=-2 wrapContinuous=true partialBounded=true");
    }

    private static void verifyBeaconAuraProfile() {
        if(beaconAuraProfileLogged) return;
        verifyBeaconAuraRotationProfile();
        boolean inside=shouldRenderBeaconAura(true,true,true,true,
            Math.nextDown(BEACON_AURA_RENDER_DISTANCE_SQR));
        boolean collapseVisible=shouldShowBeaconAura(true,0.0F,false);
        boolean progressVisible=shouldShowBeaconAura(false,.25F,false);
        boolean zeroProgressRestorationVisible=shouldShowBeaconAura(false,0.0F,true);
        boolean completedHidden=!shouldShowBeaconAura(false,0.0F,false);
        boolean restorationOnlyTimedState=hasActiveTimedClientState(0.0F,false,0,true);
        boolean idleStateUntimed=!hasActiveTimedClientState(0.0F,false,0,false);
        boolean packetTimeoutBoundary=!isClientPacketStateStale(CLIENT_STATE_PACKET_TIMEOUT_TICKS)
            && isClientPacketStateStale(CLIENT_STATE_PACKET_TIMEOUT_TICKS+1);
        BlockPos cachedPositionProbe=new BlockPos(4,64,-3);
        boolean cachedPositionRoundTrip=matchesCachedBeaconPosition(cachedPositionProbe,cachedPositionProbe.asLong());
        boolean cachedPositionMismatchRejected=!matchesCachedBeaconPosition(cachedPositionProbe,cachedPositionProbe.asLong()+1L);
        int completionFadeStarted=nextBeaconAuraCompletionFadeTicks(0,true,false,false,0.0F);
        int completionFadeContinued=nextBeaconAuraCompletionFadeTicks(6,false,false,false,0.0F);
        int completionFadeCanceledByRestore=nextBeaconAuraCompletionFadeTicks(6,false,true,false,0.0F);
        int completionFadeCanceledByCollapse=nextBeaconAuraCompletionFadeTicks(6,false,false,true,0.0F);
        int completionFadeCanceledByProgress=nextBeaconAuraCompletionFadeTicks(6,false,false,false,.1F);
        double completionFadeFull=beaconAuraCompletionFade(16,0.0D);
        double completionFadeMiddle=beaconAuraCompletionFade(8,0.0D);
        double completionFadeEnd=beaconAuraCompletionFade(0,0.0D);
        double completionFadeQuarter=beaconAuraCompletionFade(4,0.0D);
        double completionFadeThreeQuarter=beaconAuraCompletionFade(12,0.0D);
        boolean completionFadeC2Endpoints=smootherstepDerivative01(0.0D)==0.0D
            && smootherstepDerivative01(1.0D)==0.0D
            && smootherstepSecondDerivative01(0.0D)==0.0D
            && smootherstepSecondDerivative01(1.0D)==0.0D;
        double completionScaleFull=beaconAuraCompletionScale(1.0D);
        double completionScaleMiddle=beaconAuraCompletionScale(.5D);
        double completionScaleEnd=beaconAuraCompletionScale(0.0D);
        boolean completionScaleMonotonic=completionScaleFull==1.0D
            && Math.abs(completionScaleMiddle-.775D)<1.0E-12D
            && Math.abs(completionScaleEnd-.55D)<1.0E-12D;
        double previousCompletionScale=completionScaleFull;
        for(int step=1;step<=100;step++) {
            double current=beaconAuraCompletionScale(1.0D-step/100.0D);
            completionScaleMonotonic &= Double.isFinite(current)
                && current>=.55D && current<=previousCompletionScale;
            previousCompletionScale=current;
        }
        boolean completionScaleProfile=completionScaleMonotonic
            && beaconAuraCompletionScale(Double.NaN)==.55D
            && beaconAuraCompletionScale(Double.POSITIVE_INFINITY)==.55D
            && beaconAuraCompletionScale(-1.0D)==.55D
            && beaconAuraCompletionScale(2.0D)==1.0D;
        boolean completionFadeMonotonic=completionFadeFull==1.0D
            && Math.abs(completionFadeMiddle-.5D)<1.0E-12D && completionFadeEnd==0.0D
            && Math.abs(beaconAuraCompletionFade(16,1.0D)-beaconAuraCompletionFade(15,0.0D))<1.0E-12D;
        double previousCompletionFade=completionFadeFull;
        for(int remaining=15;remaining>=0;remaining--) {
            double current=beaconAuraCompletionFade(remaining,0.0D);
            completionFadeMonotonic &= Double.isFinite(current)
                && current>=0.0D && current<=previousCompletionFade;
            previousCompletionFade=current;
        }
        double completionFadeMidpointProbe=beaconAuraCompletionFade(8,0.0D);
        double completionFadeTailProbe=beaconAuraCompletionFade(3,0.0D);
        boolean completionFadeMilestones=Math.abs(completionFadeMidpointProbe-.5D)<1.0E-12D
            && completionFadeTailProbe>1.0D/255.0D && completionFadeTailProbe<=.12D;
        boolean completionFadeProfile=completionFadeStarted==16 && completionFadeContinued==6
            && completionFadeCanceledByRestore==0 && completionFadeCanceledByCollapse==0
            && completionFadeCanceledByProgress==0 && completionFadeMonotonic
            && Math.abs(completionFadeQuarter-.103515625D)<1.0E-12D
            && Math.abs(completionFadeThreeQuarter-.896484375D)<1.0E-12D
            && completionFadeC2Endpoints && completionFadeMilestones && completionScaleProfile
            && beaconAuraCompletionFade(99,Double.NaN)==1.0D
            && beaconAuraCompletionFade(-1,0.0D)==0.0D;
        boolean inactiveRejected=!shouldRenderBeaconAura(false,true,true,true,1.0D);
        boolean missingPositionRejected=!shouldRenderBeaconAura(true,false,true,true,1.0D);
        boolean unloadedRejected=!shouldRenderBeaconAura(true,true,false,true,1.0D);
        boolean replacedBeaconRejected=!shouldRenderBeaconAura(true,true,true,false,1.0D);
        boolean boundaryRejected=!shouldRenderBeaconAura(true,true,true,true,BEACON_AURA_RENDER_DISTANCE_SQR);
        boolean invalidRejected=!shouldRenderBeaconAura(true,true,true,true,Double.NaN)
            && !shouldRenderBeaconAura(true,true,true,true,Double.POSITIVE_INFINITY)
            && !shouldRenderBeaconAura(true,true,true,true,-1.0D);
        double fadeNear=beaconAuraDistanceFade(39.0D*39.0D);
        double fadeStart=beaconAuraDistanceFade(40.0D*40.0D);
        double fadeMiddle=beaconAuraDistanceFade(44.0D*44.0D);
        double fadeBoundary=beaconAuraDistanceFade(48.0D*48.0D);
        double fadeQuarter=beaconAuraDistanceFade(46.0D*46.0D);
        double fadeThreeQuarter=beaconAuraDistanceFade(42.0D*42.0D);
        boolean fadeC2Endpoints=smootherstepDerivative01(0.0D)==0.0D
            && smootherstepDerivative01(1.0D)==0.0D
            && smootherstepSecondDerivative01(0.0D)==0.0D
            && smootherstepSecondDerivative01(1.0D)==0.0D;
        boolean fadeMonotonic=fadeNear==1.0D && fadeStart==1.0D
            && Math.abs(fadeQuarter-.103515625D)<1.0E-12D
            && Math.abs(fadeMiddle-.5D)<1.0E-12D
            && Math.abs(fadeThreeQuarter-.896484375D)<1.0E-12D
            && fadeBoundary==0.0D && fadeC2Endpoints;
        double previousFade=fadeStart;
        for(int step=1;step<=8;step++) {
            double distance=BEACON_AURA_FADE_START_DISTANCE+step;
            double currentFade=beaconAuraDistanceFade(distance*distance);
            fadeMonotonic &= Double.isFinite(currentFade)
                && currentFade>=0.0D && currentFade<=previousFade;
            previousFade=currentFade;
        }
        boolean invalidFadeRejected=beaconAuraDistanceFade(Double.NaN)==0.0D
            && beaconAuraDistanceFade(Double.POSITIVE_INFINITY)==0.0D
            && beaconAuraDistanceFade(-1.0D)==0.0D;
        if(!(inside && inactiveRejected && missingPositionRejected && unloadedRejected
            && replacedBeaconRejected && boundaryRejected && invalidRejected
            && collapseVisible && progressVisible && zeroProgressRestorationVisible && completedHidden
            && restorationOnlyTimedState && idleStateUntimed && packetTimeoutBoundary
            && cachedPositionRoundTrip && cachedPositionMismatchRejected
            && completionFadeProfile && fadeMonotonic && invalidFadeRejected)
            || !Double.isFinite(BEACON_AURA_OUTER_MARGIN)
            || !Double.isFinite(BEACON_AURA_INNER_MARGIN)
            || !Double.isFinite(BEACON_AURA_OUTER_HALF_WIDTH)
            || !Double.isFinite(BEACON_AURA_INNER_HALF_WIDTH)
            || BEACON_AURA_OUTER_MARGIN<=BEACON_AURA_INNER_MARGIN
            || BEACON_AURA_INNER_MARGIN<0.0D
            || BEACON_AURA_OUTER_HALF_WIDTH<=0.0D
            || BEACON_AURA_OUTER_HALF_WIDTH>=BEACON_AURA_OUTER_MARGIN
            || BEACON_AURA_INNER_HALF_WIDTH<=0.0D
            || BEACON_AURA_INNER_HALF_WIDTH>=BEACON_AURA_INNER_MARGIN
            || !Double.isFinite(BEACON_AURA_OUTER_ALPHA_BASE)
            || !Double.isFinite(BEACON_AURA_OUTER_ALPHA_PULSE)
            || BEACON_AURA_OUTER_ALPHA_BASE<=0.0D
            || BEACON_AURA_OUTER_ALPHA_PULSE<0.0D
            || BEACON_AURA_OUTER_ALPHA_BASE+BEACON_AURA_OUTER_ALPHA_PULSE>1.0D)
            throw new IllegalStateException("BLACK FREQUENCY beacon aura range/geometry regression");
        beaconAuraProfileLogged=true;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_VISIBILITY_VERIFIED zeroProgressRestoring=true completed=false");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_STALE_PACKET_EXPIRY_VERIFIED restorationOnly=true idle=false timeoutTicks=100");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_POSITION_CACHE_VERIFIED packetUpdatesOnly=true blockPosAllocationsPerFrame=0 mismatchRejected=true");
        if(!beaconAuraCompletionFadeProfileLogged) {
            beaconAuraCompletionFadeProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_PROFILE_VERIFIED ticks=16 curve=quintic partialTick=true quarter={} midpoint={} threeQuarter={} c2Endpoints=true",
                completionFadeQuarter,completionFadeMiddle,completionFadeThreeQuarter);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MILESTONES_VERIFIED midpointInRange=true tailVisible=true");
        }
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_COLLAPSE_PROFILE_VERIFIED start=1.0 midpoint=0.775 end=0.55 monotonic=true geometryScaled=true");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=quintic quarter={} midpoint={} threeQuarter={} c2Endpoints=true monotonic=true invalidRejected=true",
            fadeQuarter,fadeMiddle,fadeThreeQuarter);
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_RIBBON_PROFILE_VERIFIED outerHalfWidth=0.022 innerHalfWidth=0.012 cameraFacing=true allocationFree=true");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_OUTER_CAGE_DEEMPHASIS_VERIFIED min=0.3 max=0.48 innerUnchanged=true");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_PROFILE_VERIFIED radius={} cages=2 edges=24 loadedOnly=true boundaryStrict=true invalidRejected=true",
            BEACON_AURA_RENDER_DISTANCE);
    }

    private static int beaconAuraChannel(float value) {
        if(!Float.isFinite(value)) return 0;
        return Math.max(0,Math.min(255,Math.round(value*255.0F)));
    }

    private static void beaconAuraWorldVertex(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
            double x,double y,double z,int red,int green,int blue,int alpha) {
        builder.vertex(matrix,(float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z))
            .color(red,green,blue,alpha).endVertex();
    }

    // Build a camera-facing quad around each edge. This avoids relying on GL line-width
    // support, which is commonly limited to one pixel on modern drivers.
    private static void beaconAuraRibbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
            double centerX,double centerZ,double cosine,double sine,double halfWidth,
            double x1,double y1,double z1,double x2,double y2,double z2,
            int red,int green,int blue,int alpha) {
        double dx1=x1-centerX,dz1=z1-centerZ;
        double ax=centerX+dx1*cosine-dz1*sine;
        double az=centerZ+dx1*sine+dz1*cosine;
        double dx2=x2-centerX,dz2=z2-centerZ;
        double bx=centerX+dx2*cosine-dz2*sine;
        double bz=centerZ+dx2*sine+dz2*cosine;
        double directionX=bx-ax,directionY=y2-y1,directionZ=bz-az;
        double viewX=camera.x-(ax+bx)*.5D;
        double viewY=camera.y-(y1+y2)*.5D;
        double viewZ=camera.z-(az+bz)*.5D;
        double sideX=directionY*viewZ-directionZ*viewY;
        double sideY=directionZ*viewX-directionX*viewZ;
        double sideZ=directionX*viewY-directionY*viewX;
        double sideLength=Math.sqrt(sideX*sideX+sideY*sideY+sideZ*sideZ);
        if(!Double.isFinite(sideLength) || sideLength<1.0E-8D) {
            // A vertical edge can be parallel to world-up; use world-X as the final fallback.
            sideX=-directionZ;
            sideY=0.0D;
            sideZ=directionX;
            sideLength=Math.sqrt(sideX*sideX+sideZ*sideZ);
            if(!Double.isFinite(sideLength) || sideLength<1.0E-8D) {
                sideX=0.0D;
                sideY=directionZ;
                sideZ=-directionY;
                sideLength=Math.sqrt(sideY*sideY+sideZ*sideZ);
            }
        }
        if(!Double.isFinite(sideLength) || sideLength<1.0E-8D
            || !Double.isFinite(halfWidth) || halfWidth<=0.0D) return;
        double scale=halfWidth/sideLength;
        sideX*=scale;
        sideY*=scale;
        sideZ*=scale;
        beaconAuraWorldVertex(builder,matrix,camera,ax+sideX,y1+sideY,az+sideZ,red,green,blue,alpha);
        beaconAuraWorldVertex(builder,matrix,camera,bx+sideX,y2+sideY,bz+sideZ,red,green,blue,alpha);
        beaconAuraWorldVertex(builder,matrix,camera,bx-sideX,y2-sideY,bz-sideZ,red,green,blue,alpha);
        beaconAuraWorldVertex(builder,matrix,camera,ax-sideX,y1-sideY,az-sideZ,red,green,blue,alpha);
    }

    private static void emitBeaconCage(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
            BlockPos beacon,double margin,double angle,double halfWidth,double completionScale,
            int red,int green,int blue,int alpha) {
        double centerX=beacon.getX()+.5D,centerZ=beacon.getZ()+.5D;
        double cosine=Math.cos(angle),sine=Math.sin(angle);
        double boundedScale=Double.isFinite(completionScale)
            ? Math.max(.55D,Math.min(1.0D,completionScale)) : .55D;
        double scaledMargin=margin*boundedScale;
        double scaledHalfWidth=halfWidth*boundedScale;
        double minX=beacon.getX()-scaledMargin,maxX=beacon.getX()+1.0D+scaledMargin;
        double minY=beacon.getY()-scaledMargin,maxY=beacon.getY()+1.0D+scaledMargin;
        double minZ=beacon.getZ()-scaledMargin,maxZ=beacon.getZ()+1.0D+scaledMargin;
        // Bottom ring.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,minY,minZ,maxX,minY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,minY,minZ,maxX,minY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,minY,maxZ,minX,minY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,minY,maxZ,minX,minY,minZ,red,green,blue,alpha);
        // Top ring.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,maxY,minZ,maxX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,maxY,minZ,maxX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,maxY,maxZ,minX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,maxY,maxZ,minX,maxY,minZ,red,green,blue,alpha);
        // Four vertical containment rails.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,minY,minZ,minX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,minY,minZ,maxX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,maxX,minY,maxZ,maxX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,scaledHalfWidth,minX,minY,maxZ,minX,maxY,maxZ,red,green,blue,alpha);
    }

    /** Draw a client-only violet containment cage around the server-owned physical beacon. */
    public static void renderBeaconAura(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        verifyBeaconAuraProfile();
        var level=Minecraft.getInstance().level;
        boolean completionFadeActive=beaconAuraCompletionFadeTicks>0;
        boolean auraActive=shouldShowBeaconAura(collapse,progress,restorationActive)
            || completionFadeActive;
        if(level==null || !level.dimension().equals(Level.OVERWORLD)
            || !beaconPositionValid || !auraActive) return;
        BlockPos beacon=beaconBlockPos;
        if(!isSafeBeaconPosition(beacon,beaconPosition,level)) return;
        if(!level.getWorldBorder().isWithinBounds(beacon)
            || !level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)) return;
        boolean beaconBlock=level.getBlockState(beacon).is(
            net.minecraft.world.level.block.Blocks.BEACON);
        // The server may consume the physical beacon immediately after successful
        // recovery. Keep its last authoritative position for this short visual tail.
        if(!beaconBlock && !completionFadeActive) return;
        Vec3 camera=event.getCamera().getPosition();
        if(!Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)) return;
        double dx=beacon.getX()+.5D-camera.x;
        double dy=beacon.getY()+.5D-camera.y;
        double dz=beacon.getZ()+.5D-camera.z;
        double distanceSquared=dx*dx+dy*dy+dz*dz;
        if(!shouldRenderBeaconAura(auraActive,beaconPositionValid,
                true,beaconBlock || completionFadeActive,distanceSquared)) return;
        double distanceFade=beaconAuraDistanceFade(distanceSquared);
        double partial=Double.isFinite(event.getPartialTick())
            ? Math.max(0.0D,Math.min(1.0D,event.getPartialTick())) : 0.0D;
        double completionFade=completionFadeActive
            ? beaconAuraCompletionFade(beaconAuraCompletionFadeTicks,partial) : 1.0D;
        double completionScale=completionFadeActive
            ? beaconAuraCompletionScale(completionFade) : 1.0D;
        distanceFade*=completionFade;
        if(distanceFade<=1.0D/255.0D) return;
        double time=(double)level.getGameTime()+partial;
        double pulse=.5D+.5D*Math.sin(time*.12D);
        double outerAngle=beaconAuraRotationAngle(level.getGameTime(),partial,1);
        double innerAngle=.35D+beaconAuraRotationAngle(level.getGameTime(),partial,-2);
        int outerAlpha=beaconAuraChannel((float)((BEACON_AURA_OUTER_ALPHA_BASE+BEACON_AURA_OUTER_ALPHA_PULSE*pulse)*distanceFade));
        int innerAlpha=beaconAuraChannel((float)((.58D+.30D*pulse)*distanceFade));
        Matrix4f matrix=event.getPoseStack().last().pose();
        BufferBuilder builder=Tesselator.getInstance().getBuilder();
        boolean submitted=false;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F,1.0F,1.0F,1.0F);
        try {
            builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
            emitBeaconCage(builder,matrix,camera,beacon,BEACON_AURA_OUTER_MARGIN,outerAngle,
                BEACON_AURA_OUTER_HALF_WIDTH,completionScale,77,13,173,outerAlpha);
            emitBeaconCage(builder,matrix,camera,beacon,BEACON_AURA_INNER_MARGIN,innerAngle,
                BEACON_AURA_INNER_HALF_WIDTH,completionScale,158,56,255,innerAlpha);
            BufferUploader.drawWithShader(builder.end());
            submitted=true;
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            RenderSystem.setShaderColor(1.0F,1.0F,1.0F,1.0F);
        }
        if(submitted && !beaconAuraRenderedLogged) {
            beaconAuraRenderedLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST BEACON_AURA_RENDERED cages=2 edgeRibbons=24 rotation=counterphase loadedOnly=true");
        }
        if(submitted && restorationActive && progress<=0.0F && !beaconAuraRestorationRenderedLogged) {
            beaconAuraRestorationRenderedLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST BEACON_AURA_RESTORATION_ACTIVE_RENDERED progressZero=true loadedOnly=true");
        }
        if(submitted && completionFadeActive && !restorationActive) {
            if(!beaconAuraCompletionFadeRenderedLogged) {
                beaconAuraCompletionFadeRenderedLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_RENDERED ticksRemaining={} cageScale={}",
                    beaconAuraCompletionFadeTicks,completionScale);
            }
            if(!beaconAuraCompletionFadeMidpointRenderedLogged
                && completionFade<=.60D && completionFade>.25D) {
                beaconAuraCompletionFadeMidpointRenderedLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_MIDPOINT_RENDERED ticksRemaining={} alphaFactor={} cageScale={}",
                    beaconAuraCompletionFadeTicks,completionFade,completionScale);
            }
            if(!beaconAuraCompletionFadeTailRenderedLogged
                && completionFade<=.12D && completionFade>1.0D/255.0D) {
                beaconAuraCompletionFadeTailRenderedLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST BEACON_AURA_COMPLETION_FADE_TAIL_RENDERED ticksRemaining={} alphaFactor={} cageScale={}",
                    beaconAuraCompletionFadeTicks,completionFade,completionScale);
            }
        }
    }
    // Recovery updates are intentionally local. Expire stale visuals when a player
    // leaves the fracture radius or the server stops sending state packets.
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.multiplayer.ClientLevel level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null) {
            reset();
            lastLevel=null;
            return;
        }
        if(lastLevel!=null && lastLevel!=level) reset();
        lastLevel=level;
        // Integrated singleplayer pauses server packets too. Do not expire the
        // fracture overlay while the simulation is paused in a menu.
        if(net.minecraft.client.Minecraft.getInstance().isPaused()) return;
        if(beaconAuraCompletionFadeTicks>0) beaconAuraCompletionFadeTicks--;
        if(hasActiveTimedClientState(progress,collapse,finaleTicks,restorationActive)) {
            ticksSincePacket=Math.min(CLIENT_STATE_PACKET_TIMEOUT_TICKS+1,ticksSincePacket+1);
            if(collapse) finaleTicks=predictedFinaleTicks(serverFinaleTicks,ticksSincePacket);
            if(isClientPacketStateStale(ticksSincePacket)) reset();
        }
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { reset(); lastLevel=null; }
    /**
     * Client-only ground mesh for the same four-arm fissure emitted by the server.
     * It is cosmetic: it reads only already-loaded client chunks and never edits terrain.
     */
    public static final class GroundRenderer {
        private static final int RADIUS=RiftGeometry.RADIUS;
        // Keep the sampled vertical window consistent with the 64-block 3D render envelope.
        private static final int GROUND_RENDER_RADIUS=64;
        // Resume two blocks inside the fade boundary to prevent camera-bob rebuild churn.
        private static final int GROUND_RENDER_RESUME_RADIUS=62;
        private static final int GROUND_SURFACE_SEARCH_BELOW=GROUND_RENDER_RADIUS;
        private static final int GROUND_SURFACE_SEARCH_ABOVE=8;
        private static final int ARM_COUNT=RiftGeometry.FISSURE_ARM_COUNT;
        // Double the cosmetic mesh density while preserving every server-particle sample at even indices.
        private static final int SEGMENTS_PER_ARM=RiftGeometry.FISSURE_SEGMENTS_PER_ARM*2;
        private static final double SAMPLE_SPACING=RiftGeometry.FISSURE_SAMPLE_SPACING*.5D;
        private static final int GROUND_ENDPOINT_TAPER_SAMPLES=RiftGeometry.FISSURE_SEGMENTS_PER_ARM/2;
        // Terrain sampling is throttled; the fissure pulse itself still renders every frame.
        // Surface sampling is bounded to the full vertical render radius below and eight blocks above the camera.
        // Rebuild twice per second; anchor and two-block vertical changes still invalidate immediately.
        private static final long REBUILD_INTERVAL=10L;
        // Empty meshes often mean the camera is outside the bounded surface-search window
        // or nearby arm chunks have not arrived yet. Retry less often; anchor/height changes
        // still invalidate immediately and populated meshes keep the responsive 10-tick cadence.
        private static final long EMPTY_MESH_REBUILD_INTERVAL=40L;
        private static final int GROUND_REBUILD_VERTICAL_DELTA=2;
        // Reuse these render-thread-only scratch objects between mesh rebuilds.
        // fastutil clear() retains the backing arrays, avoiding a fresh map allocation every 10 ticks.
        private static final double SURFACE_CACHE_MISS=Double.POSITIVE_INFINITY;
        private static final Long2DoubleOpenHashMap SURFACE_Y_BY_COLUMN=createSurfaceCache();
        private static final BlockPos.MutableBlockPos SURFACE_CURSOR=
            new BlockPos.MutableBlockPos(0,0,0);
        // A bounded set of brighter violet motes clears short foliage without lifting the ground ribbon.
        private static final int GROUND_CANOPY_ACCENT_PARTICLE_BUDGET=8;
        // Queue candidates while sampling, then emit only when the final mesh retains a nearby segment.
        private static final int GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET=32;
        // Mesh invalidation can happen more often than the normal rebuild cadence while moving.
        private static final long GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS=REBUILD_INTERVAL;
        private static final double GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE=.85D;
        private static final double GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE_SQR=
            GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE*GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE;
        private static final double GROUND_CANOPY_ACCENT_MAX_GROUND_DELTA=.35D;
        // Lift and enlarge sparse motes just enough to read above short foliage.
        private static final float GROUND_CANOPY_ACCENT_PARTICLE_SCALE=.81F;
        private static final double GROUND_CANOPY_ACCENT_VERTICAL_OFFSET=.26D;
        private static final int[] GROUND_CANOPY_ACCENT_X=new int[GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET];
        private static final int[] GROUND_CANOPY_ACCENT_Z=new int[GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET];
        private static final double[] GROUND_CANOPY_ACCENT_Y=new double[GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET];
        private static final double[] GROUND_CANOPY_ACCENT_GROUND_Y=new double[GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET];
        private static final DustParticleOptions GROUND_CANOPY_ACCENT_DUST=
            new DustParticleOptions(new Vector3f(.42F,.18F,.92F),GROUND_CANOPY_ACCENT_PARTICLE_SCALE);
        private static int groundCanopyAccentCandidateCount;
        // Rotate the bounded emission window so later fissure arms are not starved by insertion order.
        private static int groundCanopyAccentCandidateCursor;
        private static long groundCanopyAccentLastAttemptTick=Long.MIN_VALUE;
        private static int groundCanopyAccentParticlesEmitted;
        private static boolean canopyAccentSelectorProfileLogged;
        private static boolean canopyAccentBuildProfileLogged;
        private static boolean canopyAccentEmissionLogged;
        private static boolean sampleCacheReuseLogged;
        private static boolean surfaceSearchProfileLogged;
        private static net.minecraft.client.multiplayer.ClientLevel cachedLevel;
        private static long cachedTick=Long.MIN_VALUE;
        private static int cachedAnchorX=Integer.MIN_VALUE;
        private static int cachedAnchorZ=Integer.MIN_VALUE;
        private static int cachedAnchorSurfaceY;
        // The sampled surface window is centered on the player's block Y.
        // Rebuild after a meaningful vertical move even when the anchor is unchanged.
        private static int cachedReferenceY=Integer.MIN_VALUE;
        private static List<GroundSegment> cachedSegments=List.of();
        private static int cachedArmCount;
        private static int cachedSampledColumns;
        private static int cachedColumnCacheHits;
        private static final int MIN_RENDERED_SEGMENTS=8;
        private static final int MIN_RENDERED_ARMS=2;
        // Reject near-vertical interpolated ribbons: grade is measured against the
        // actual horizontal distance between sampled surface points.
        private static final double MAX_GROUND_SEGMENT_Y_DELTA=1.25D;
        // A single one-block sample may be skipped only when the two-sample span remains slope-safe.
        private static final double MAX_BRIDGED_GROUND_STEP_Y_DELTA=1.0D;
        // Keep the same maximum height change per segment after halving sample spacing.
        // Keep the absolute 0.5625-block rise budget while the shared fissure reach changes.
        private static final double MAX_GROUND_SEGMENT_GRADE=.5625D/SAMPLE_SPACING;
        // Narrow the ribbon on sloped samples so its outer halo is less likely to cross block sides.
        private static final double GROUND_SLOPE_WIDTH_REDUCTION=.15D;
        // Taper cliff-side ribbon tips almost to a point without bridging terrain discontinuities.
        private static final double CLIFF_BREAK_TAPER=.06D;
        private static final double GROUND_CAP_TAPER_EPSILON=1.0E-9D;
        // Keep the halo broad while narrowing the dark core and pulling its violet rails inward.
        private static final double GROUND_GLOW_WIDTH_SCALE=5.75D;
        private static final double GROUND_CORE_WIDTH_SCALE=1.5D;
        // The recessed core pinches and widens along the seam; endpoint widths are interpolated per segment.
        private static final double GROUND_MIN_CORE_WIDTH_SCALE=.78D;
        private static final double GROUND_MAX_CORE_WIDTH_SCALE=1.22D;
        private static final double GROUND_CORE_WIDTH_BROAD_AMPLITUDE=.16D;
        private static final double GROUND_CORE_WIDTH_DETAIL_AMPLITUDE=.05D;
        private static final double GROUND_CORE_WIDTH_DETAIL_FREQUENCY=.43D;
        private static final double GROUND_CORE_WIDTH_ARM_PHASE=1.13D;
        // A small cached lateral skew gives the dark core unequal walls without changing its total width.
        private static final double GROUND_CORE_SKEW_AMPLITUDE=.15D;
        private static final double GROUND_CORE_SKEW_FREQUENCY=.71D;
        private static final double GROUND_CORE_SKEW_ARM_PHASE=2.17D;
        // Place both violet lips outside the widened dark core while keeping them inside the soft halo.
        private static final double GROUND_EDGE_OFFSET_SCALE=2.6D;
        // Let each violet lip follow a small fraction of the dark core's cached skew.
        private static final double GROUND_EDGE_OFFSET_SKEW_SCALE=.12D;
        // The render pulse is clamped to this interval for every wave/flicker phase.
        private static final double GROUND_MIN_HALF_WIDTH=.045D;
        private static final double GROUND_BASE_HALF_WIDTH=.052D;
        private static final double GROUND_WAVE_HALF_WIDTH_RANGE=.017D;
        private static final double GROUND_WIDTH_FLICKER=.005D;
        private static final double GROUND_MAX_HALF_WIDTH=
            GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE+GROUND_WIDTH_FLICKER;
        // Keep violet lip thickness proportional to the travelling core width so pulse minima cannot overlap the dark seam.
        private static final double GROUND_EDGE_HALF_WIDTH=.025D;
        private static final double GROUND_MIN_CORE_EDGE_GAP=.0075D;
        // Keep the dark core near the support top while retaining a measurable depth-buffer gap.
        private static final double GROUND_SURFACE_CLEARANCE=.010D;
        private static final double GROUND_MIN_CORE_CLEARANCE=.005D;
        private static final double GROUND_CORE_RECESS=.005D;
        // Two shallow dark-violet bevels break the flat ribbon silhouette without editing terrain.
        private static final double GROUND_BEVEL_WIDTH=.045D;
        private static final double GROUND_BEVEL_LIFT=.010D;
        // Reuse the travelling width wave to raise one crack lip as the opposite lip settles.
        private static final double GROUND_BEVEL_LIFT_PULSE_RANGE=.10D;
        private static final double GROUND_FILAMENT_LIFT=.001D;
        private static final double GROUND_EDGE_LIFT=.005D;
        // Shift the ground rails away from hot magenta toward the sky-rift's deep violet palette.
        private static final float GROUND_EDGE_RED=.50F;
        private static final float GROUND_EDGE_GREEN=.14F;
        private static final float GROUND_EDGE_BLUE=.90F;
        // Fixed hash salts create independent, reproducible chips in the two exposed crack lips.
        private static final long GROUND_LEFT_EDGE_NOTCH_SALT=0x9E3779B97F4A7C15L;
        private static final long GROUND_RIGHT_EDGE_NOTCH_SALT=0xD1B54A32D192ED03L;
        // Two deterministic core-only gaps expose the dark seam while the violet envelope stays connected.
        private static final long GROUND_CORE_SEGMENT_GAP_SALT=0x2E2AC13EF8E8D8D2L;
        // RGB values are invariant across every frame and segment; pack them once at class initialization.
        private static final int GROUND_GLOW_RED_CHANNEL=colorChannel(.20F);
        private static final int GROUND_GLOW_GREEN_CHANNEL=colorChannel(.025F);
        private static final int GROUND_GLOW_BLUE_CHANNEL=colorChannel(.43F);
        private static final int GROUND_CORE_RED_CHANNEL=colorChannel(.008F);
        private static final int GROUND_CORE_GREEN_CHANNEL=colorChannel(.001F);
        private static final int GROUND_CORE_BLUE_CHANNEL=colorChannel(.022F);
        private static final int GROUND_BEVEL_RED_CHANNEL=colorChannel(.055F);
        private static final int GROUND_BEVEL_GREEN_CHANNEL=colorChannel(.008F);
        private static final int GROUND_BEVEL_BLUE_CHANNEL=colorChannel(.12F);
        private static final int GROUND_FILAMENT_RED_CHANNEL=colorChannel(.28F);
        private static final int GROUND_FILAMENT_GREEN_CHANNEL=colorChannel(.015F);
        private static final int GROUND_FILAMENT_BLUE_CHANNEL=colorChannel(.62F);
        private static final int GROUND_EDGE_RED_CHANNEL=colorChannel(GROUND_EDGE_RED);
        private static final int GROUND_EDGE_GREEN_CHANNEL=colorChannel(GROUND_EDGE_GREEN);
        private static final int GROUND_EDGE_BLUE_CHANNEL=colorChannel(GROUND_EDGE_BLUE);
        // Keep the halo readable against grass and stone without another draw call or mesh layer.
        // Slightly stronger violet halo keeps the ground seam readable against grass and stone.
        private static final float GROUND_GLOW_ALPHA=.72F;
        // GROUND_GLOW_ALPHA is binary32; allow its widened representation's rounding error.
        private static final double GROUND_GLOW_ALPHA_TOLERANCE=1.0E-7D;
        // A restrained violet filament pulses inside the dark core without another trig call.
        private static final double GROUND_CORE_PULSE_BASE=.10D;
        private static final double GROUND_CORE_PULSE_RANGE=.22D;
        private static boolean continuityProfileLogged;
        private static boolean coreGapLayerPolicyLogged;
        // Render-thread-only guard: invariant probes run once per client JVM, not on every mesh rebuild.
        private static boolean edgeMaskProfileLogged;
        private static boolean continuityBuildLogged;
        private static int cachedSteepStepBreaks;
        private static boolean firstRenderLogged;
        private static boolean visibilityPrefixReuseLogged;
        private static boolean endpointFadeReuseLogged;
        private static boolean endpointFadeReuseProfileLogged;
        private static boolean pendingRenderLogged;
        private static boolean renderEventLogged;
        private static boolean emptyMeshLogged;
        private static boolean taperProfileLogged;
        private static boolean visualProfileLogged;
        private static boolean reliefProfileLogged;
        private static boolean slopeWidthProfileLogged;
        private static boolean surfaceWindowLogged;
        // Keep only the first few rebuild timings: the first includes one-time profile checks.
        private static int groundBuildTimingSamplesLogged;

        private static boolean shouldMeasureGroundMeshBuild(int samplesLogged) {
            return samplesLogged>=0 && samplesLogged<4;
        }
        // Expensive invariant probes belong in disposable smoke clients, not production render frames.
        private static final boolean VERIFY_GROUND_MESH_PROFILES=
            Boolean.getBoolean("blackfrequency.verifyGroundMeshProfiles");
        private static boolean groundMeshProfileModeLogged;
        private static boolean supportSurfaceProfileLogged;
        private static boolean rebuildProfileLogged;
        private static boolean animationProfileLogged;
        private static boolean packedColorProfileLogged;
        private static boolean ribbonColorCacheProfileLogged;
        private static boolean edgeInterpolationProfileLogged;
        private static boolean samplingProfileLogged;
        private static boolean rangeRejectLogged;
        private static boolean rangeProfileLogged;
        private static boolean verticallyCulled;
        private static int verticallyCulledAnchorX=Integer.MIN_VALUE;
        private static int verticallyCulledAnchorZ=Integer.MIN_VALUE;
        private static int verticallyCulledSurfaceY;
        private static boolean verticalCullResumePending;
        // Preserve the actual anchor transition across cache release until the mesh is rebuilt.
        private static boolean verticalCullResumeAnchorChanged;
        private static boolean verticalCameraRangeSampleLogged;
        // A missing anchor chunk must not discard an active vertical-cull latch; wait for it to be loaded again.
        private static boolean unavailableAnchorChunkLogged;
        // Diagnostic-only: each skipped frame avoids one border probe, chunk probe and rebuild check.
        private static long verticallyCulledSkippedFrames;
        private static boolean supportFilterLogged;
        private static boolean sampleCursorReuseLogged;
        private static boolean sampleOffsetsProfileLogged;

        private static Long2DoubleOpenHashMap createSurfaceCache() {
            Long2DoubleOpenHashMap cache=new Long2DoubleOpenHashMap(ARM_COUNT*SEGMENTS_PER_ARM);
            // Valid samples are finite and unsupported columns are NaN, so +infinity
            // is an unambiguous missing-key sentinel and lets the hot loop use one lookup.
            cache.defaultReturnValue(SURFACE_CACHE_MISS);
            return cache;
        }
        private static boolean inactiveCacheCleanupLogged;

        private record GroundPoint(double x,double y,double z) {}
        private static double groundSlopeWidthScale(GroundPoint a,GroundPoint b) {
            if(a==null || b==null || !Double.isFinite(a.x()) || !Double.isFinite(a.y())
                || !Double.isFinite(a.z()) || !Double.isFinite(b.x()) || !Double.isFinite(b.y())
                || !Double.isFinite(b.z())) return 1.0D;
            double horizontal=Math.hypot(b.x()-a.x(),b.z()-a.z());
            double rise=Math.abs(b.y()-a.y());
            if(!Double.isFinite(horizontal) || horizontal<=1.0E-5D || !Double.isFinite(rise)) return 1.0D;
            double grade=rise/horizontal;
            if(!Double.isFinite(grade)) return 1.0D;
            double normalizedGrade=Math.max(0.0D,Math.min(1.0D,grade/MAX_GROUND_SEGMENT_GRADE));
            double scale=1.0D-GROUND_SLOPE_WIDTH_REDUCTION*normalizedGrade;
            return Double.isFinite(scale)
                ? Math.max(1.0D-GROUND_SLOPE_WIDTH_REDUCTION,Math.min(1.0D,scale)) : 1.0D;
        }
        private record GroundSample(double forwardX,double bendX,double forwardZ,double bendZ,double phase,
                double phaseSin,double phaseCos,double flickerPhaseSin,double flickerPhaseCos,
                double coreWidthScale,double coreSkew,boolean coreSegmentGap,
                boolean leftEdgeNotched,boolean rightEdgeNotched) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                GroundSample sample,double taperA,double taperB,double coreWidthScaleA,double coreWidthScaleB,
                double coreSkewA,double coreSkewB,double slopeWidthScale) {
            private GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                    GroundSample sample,double taperA,double taperB,double coreWidthScaleA,double coreWidthScaleB,
                    double coreSkewA,double coreSkewB) {
                this(a,b,normalX,normalZ,sample,taperA,taperB,coreWidthScaleA,coreWidthScaleB,
                    coreSkewA,coreSkewB,groundSlopeWidthScale(a,b));
            }
        }
        private record GroundMesh(List<GroundSegment> segments,int activeArms,int sampledColumns,int cacheHits,
                int steepStepBreaks) {}

        // The four arms and 24 cosmetic mesh samples per arm never change with the world anchor.
        // Even mesh samples align exactly with the 12 server-side particle samples.
        // Precompute their trigonometric terms once instead of recalculating them on every mesh rebuild.
        private static final GroundSample[] GROUND_SAMPLES=createGroundSamples();

        // Hash each static sample once so the two exposed lips fracture independently.
        // One in thirty-two samples interrupts each lip; independent salts keep the seam asymmetric.
        private static long groundSampleHash(double phase,long salt) {
            long hash=Double.doubleToLongBits(phase)^salt;
            hash ^= hash >>> 33;
            hash *= 0xff51afd7ed558ccdL;
            hash ^= hash >>> 33;
            hash *= 0xc4ceb9fe1a85ec53L;
            hash ^= hash >>> 33;
            return hash;
        }

        private static boolean isGroundEdgeNotched(double phase,long salt) {
            if(!Double.isFinite(phase)) return true;
            return (groundSampleHash(phase,salt) & 31L)==0L;
        }

        private static boolean isGroundCoreSegmentGap(double phase) {
            if(!Double.isFinite(phase)) return true;
            return (groundSampleHash(phase,GROUND_CORE_SEGMENT_GAP_SALT) & 63L)==0L;
        }

        private static boolean shouldRenderGroundCore(boolean coreSegmentGap) {
            return !coreSegmentGap;
        }

        private static void verifyGroundCoreGapLayerPolicy() {
            if(coreGapLayerPolicyLogged) return;
            boolean intactCoreRendered=shouldRenderGroundCore(false);
            boolean gapCoreSuppressed=!shouldRenderGroundCore(true);
            GroundSample base=GROUND_SAMPLES[0];
            GroundSample gapSample=new GroundSample(base.forwardX(),base.bendX(),base.forwardZ(),base.bendZ(),
                base.phase(),base.phaseSin(),base.phaseCos(),base.flickerPhaseSin(),base.flickerPhaseCos(),
                base.coreWidthScale(),base.coreSkew(),true,base.leftEdgeNotched(),base.rightEdgeNotched());
            GroundPoint beforeGap=new GroundPoint(-.5D,64.0D,.5D);
            GroundPoint gapStart=new GroundPoint(.5D,64.0D,.5D);
            GroundPoint gapEnd=new GroundPoint(1.5D,64.0D,.5D);
            GroundPoint afterGap=new GroundPoint(2.5D,64.0D,.5D);
            GroundSegment beforeGapSegment=new GroundSegment(beforeGap,gapStart,0.0D,1.0D,base,
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment gapSegment=new GroundSegment(gapStart,gapEnd,0.0D,1.0D,gapSample,
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment afterGapSegment=new GroundSegment(gapEnd,afterGap,0.0D,1.0D,base,
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            Vec3 camera=new Vec3(.5D,64.0D,.5D);
            boolean gapEnvelopeVisible=hasVisibleGroundSegment(List.of(gapSegment),camera);
            double beforeGapFade=groundPointDistanceFade(beforeGapSegment.b(),camera);
            double gapEndFade=groundPointDistanceFade(gapSegment.b(),camera);
            boolean fadeReuseIntoGap=canReuseGroundPointFade(
                gapSegment.a(),beforeGapSegment.b(),beforeGapFade);
            boolean fadeReuseOutOfGap=canReuseGroundPointFade(
                afterGapSegment.a(),gapSegment.b(),gapEndFade);
            GroundPoint equalButDistinct=new GroundPoint(gapEnd.x(),gapEnd.y(),gapEnd.z());
            boolean disconnectedFallback=!canReuseGroundPointFade(
                equalButDistinct,gapSegment.b(),gapEndFade);
            boolean invalidFadeRejected=!canReuseGroundPointFade(gapEnd,gapEnd,Double.NaN)
                && !canReuseGroundPointFade(gapEnd,gapEnd,-.01D)
                && !canReuseGroundPointFade(gapEnd,gapEnd,1.01D);
            if(!(intactCoreRendered && gapCoreSuppressed && gapEnvelopeVisible
                && fadeReuseIntoGap && fadeReuseOutOfGap && disconnectedFallback && invalidFadeRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground core-gap layer policy regression");
            coreGapLayerPolicyLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_CORE_GAP_LAYER_POLICY_VERIFIED gapCount=2 outerHalo=true bevels=true edgePolicy=preserved coreSuppressed=true gapEnvelopeVisible=true fadeReuseIntoGap=true fadeReuseOutOfGap=true disconnectedIdentityFallback=true invalidFadeRejected=true");
        }

        private static double groundCoreWidthScale(double phase,int arm) {
            if(!Double.isFinite(phase) || arm<0 || arm>=ARM_COUNT) return 1.0D;
            double broad=Math.sin(phase)*GROUND_CORE_WIDTH_BROAD_AMPLITUDE;
            double detail=Math.sin(phase*GROUND_CORE_WIDTH_DETAIL_FREQUENCY
                +arm*GROUND_CORE_WIDTH_ARM_PHASE)*GROUND_CORE_WIDTH_DETAIL_AMPLITUDE;
            double scale=1.0D+broad+detail;
            return Math.max(GROUND_MIN_CORE_WIDTH_SCALE,Math.min(GROUND_MAX_CORE_WIDTH_SCALE,scale));
        }

        private static double groundCoreSkew(double phase,int arm) {
            if(!Double.isFinite(phase) || arm<0 || arm>=ARM_COUNT) return 0.0D;
            double skew=Math.sin(phase*GROUND_CORE_SKEW_FREQUENCY
                +arm*GROUND_CORE_SKEW_ARM_PHASE)*GROUND_CORE_SKEW_AMPLITUDE;
            return Math.max(-GROUND_CORE_SKEW_AMPLITUDE,Math.min(GROUND_CORE_SKEW_AMPLITUDE,skew));
        }

        private static double groundEdgeCenterOffset(double halfWidth,double coreSkew,boolean left) {
            if(!Double.isFinite(halfWidth) || halfWidth<0.0D || !Double.isFinite(coreSkew)) return 0.0D;
            double boundedSkew=Math.max(-GROUND_CORE_SKEW_AMPLITUDE,Math.min(GROUND_CORE_SKEW_AMPLITUDE,coreSkew));
            double edgeOffsetSkew=boundedSkew*GROUND_EDGE_OFFSET_SKEW_SCALE;
            double magnitude=halfWidth*GROUND_EDGE_OFFSET_SCALE*(left ? 1.0D+edgeOffsetSkew : 1.0D-edgeOffsetSkew);
            double offset=left ? magnitude : -magnitude;
            return Double.isFinite(offset) ? offset : 0.0D;
        }

        private static double groundEdgeMaximumOffsetDelta(double halfWidth) {
            if(!Double.isFinite(halfWidth) || halfWidth<0.0D) return 0.0D;
            double delta=halfWidth*GROUND_EDGE_OFFSET_SCALE
                *GROUND_CORE_SKEW_AMPLITUDE*GROUND_EDGE_OFFSET_SKEW_SCALE;
            return Double.isFinite(delta) ? delta : 0.0D;
        }

        // Scale the lip thickness with the same pulse width as its center offset.
        // This preserves the dark-core clearance at the narrowest wave and on slopes.
        private static double groundEdgeHalfWidth(double halfWidth) {
            if(!Double.isFinite(halfWidth) || halfWidth<=0.0D) return 0.0D;
            double scale=Math.min(1.0D,halfWidth/GROUND_MAX_HALF_WIDTH);
            double edgeHalfWidth=GROUND_EDGE_HALF_WIDTH*scale;
            return Double.isFinite(edgeHalfWidth)
                ? Math.max(0.0D,Math.min(GROUND_EDGE_HALF_WIDTH,edgeHalfWidth)) : 0.0D;
        }

        private static void verifyGroundEdgeInterpolationProfile() {
            if(edgeInterpolationProfileLogged) return;
            double halfWidth=GROUND_MAX_HALF_WIDTH;
            double centeredLeft=groundEdgeCenterOffset(halfWidth,0.0D,true);
            double centeredRight=groundEdgeCenterOffset(halfWidth,0.0D,false);
            double positiveLeft=groundEdgeCenterOffset(halfWidth,GROUND_CORE_SKEW_AMPLITUDE,true);
            double negativeLeft=groundEdgeCenterOffset(halfWidth,-GROUND_CORE_SKEW_AMPLITUDE,true);
            double positiveRight=groundEdgeCenterOffset(halfWidth,GROUND_CORE_SKEW_AMPLITUDE,false);
            double negativeRight=groundEdgeCenterOffset(halfWidth,-GROUND_CORE_SKEW_AMPLITUDE,false);
            double maximumOffsetDelta=groundEdgeMaximumOffsetDelta(halfWidth);
            GroundSample sample=GROUND_SAMPLES[0];
            GroundSegment first=new GroundSegment(new GroundPoint(0.0D,0.0D,0.0D),
                new GroundPoint(1.0D,0.0D,0.0D),0.0D,1.0D,sample,1.0D,1.0D,
                1.0D,1.0D,-.15D,.075D);
            GroundSegment second=new GroundSegment(new GroundPoint(1.0D,0.0D,0.0D),
                new GroundPoint(2.0D,0.0D,0.0D),0.0D,1.0D,sample,1.0D,1.0D,
                1.0D,1.0D,.075D,.15D);
            double firstLeftEnd=groundEdgeCenterOffset(halfWidth,first.coreSkewB(),true);
            double secondLeftStart=groundEdgeCenterOffset(halfWidth,second.coreSkewA(),true);
            double firstRightEnd=groundEdgeCenterOffset(halfWidth,first.coreSkewB(),false);
            double secondRightStart=groundEdgeCenterOffset(halfWidth,second.coreSkewA(),false);
            boolean centered=Math.abs(centeredLeft-.1924D)<1.0E-9D
                && Math.abs(centeredRight+.1924D)<1.0E-9D;
            boolean skewed=Math.abs(positiveLeft-(centeredLeft+maximumOffsetDelta))<1.0E-9D
                && Math.abs(negativeLeft-(centeredLeft-maximumOffsetDelta))<1.0E-9D
                && Math.abs(positiveRight-(centeredRight+maximumOffsetDelta))<1.0E-9D
                && Math.abs(negativeRight-(centeredRight-maximumOffsetDelta))<1.0E-9D;
            boolean bounded=Math.abs(positiveLeft-centeredLeft)<=maximumOffsetDelta+1.0E-12D
                && Math.abs(negativeLeft-centeredLeft)<=maximumOffsetDelta+1.0E-12D
                && Math.abs(positiveRight-centeredRight)<=maximumOffsetDelta+1.0E-12D
                && Math.abs(negativeRight-centeredRight)<=maximumOffsetDelta+1.0E-12D;
            boolean sharedBoundary=Double.doubleToLongBits(firstLeftEnd)==Double.doubleToLongBits(secondLeftStart)
                && Double.doubleToLongBits(firstRightEnd)==Double.doubleToLongBits(secondRightStart);
            boolean invalidRejected=groundEdgeCenterOffset(Double.NaN,.1D,true)==0.0D
                && groundEdgeCenterOffset(halfWidth,Double.POSITIVE_INFINITY,false)==0.0D
                && groundEdgeCenterOffset(-.1D,.1D,true)==0.0D
                && groundEdgeMaximumOffsetDelta(Double.NaN)==0.0D
                && groundEdgeMaximumOffsetDelta(-.1D)==0.0D;
            if(!(centered && skewed && bounded && sharedBoundary && invalidRejected
                && Double.isFinite(maximumOffsetDelta) && maximumOffsetDelta<=.004D))
                throw new IllegalStateException("BLACK FREQUENCY ground edge endpoint interpolation regression");
            edgeInterpolationProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EDGE_ENDPOINT_INTERPOLATION_VERIFIED maxOffsetDelta={} sharedBoundary=true invalidRejected=true",
                maximumOffsetDelta);
        }

        private static GroundSample[] createGroundSamples() {
            GroundSample[] samples=new GroundSample[ARM_COUNT*SEGMENTS_PER_ARM];
            for(int arm=0;arm<ARM_COUNT;arm++) {
                double angle=arm*Math.PI/2.0D;
                double sinAngle=Math.sin(angle);
                double cosAngle=Math.cos(angle);
                for(int segment=1;segment<=SEGMENTS_PER_ARM;segment++) {
                    double sourceSample=segment*.5D;
                    double distance=sourceSample*RiftGeometry.FISSURE_SAMPLE_SPACING;
                    double bend=Math.sin(sourceSample*.85D+arm*1.1D)*RiftGeometry.FISSURE_BEND_AMPLITUDE;
                    double phase=arm*1.7D+sourceSample*.63D;
                    double phaseSin=Math.sin(phase);
                    double phaseCos=Math.cos(phase);
                    double coreWidthScale=groundCoreWidthScale(phase,arm);
                    double coreSkew=groundCoreSkew(phase,arm);
                    samples[arm*SEGMENTS_PER_ARM+segment-1]=new GroundSample(
                        cosAngle*distance,sinAngle*bend,
                        sinAngle*distance,cosAngle*bend,phase,
                        phaseSin,phaseCos,
                        Math.sin(phase*1.9D),Math.cos(phase*1.9D),
                        coreWidthScale,coreSkew,
                        isGroundCoreSegmentGap(phase),
                        isGroundEdgeNotched(phase,GROUND_LEFT_EDGE_NOTCH_SALT),
                        isGroundEdgeNotched(phase,GROUND_RIGHT_EDGE_NOTCH_SALT));
                }
            }
            return samples;
        }

        private GroundRenderer() {}

        // Keep a broad start at the beacon, but taper each outer arm to a narrow point.
        // Independent six-sample smoothstep ramps avoid the blunt half-width cap at the far end.
        private static double endpointTaper(int sample) {
            int boundedSample=Math.max(0,Math.min(SEGMENTS_PER_ARM,sample));
            double startProgress=Math.min(1.0,boundedSample/(double)GROUND_ENDPOINT_TAPER_SAMPLES);
            double endProgress=Math.min(1.0,(SEGMENTS_PER_ARM-boundedSample)/(double)GROUND_ENDPOINT_TAPER_SAMPLES);
            double startEased=startProgress*startProgress*(3.0D-2.0D*startProgress);
            double endEased=endProgress*endProgress*(3.0D-2.0D*endProgress);
            double startWidth=.5D+.5D*startEased;
            double endWidth=CLIFF_BREAK_TAPER+(1.0D-CLIFF_BREAK_TAPER)*endEased;
            return Math.min(startWidth,endWidth);
        }

        private static void verifyGroundMeshSamplingProfile() {
            if(samplingProfileLogged) return;
            int samples=ARM_COUNT*SEGMENTS_PER_ARM;
            double extent=SEGMENTS_PER_ARM*SAMPLE_SPACING;
            double expectedExtent=RiftGeometry.FISSURE_SEGMENTS_PER_ARM*RiftGeometry.FISSURE_SAMPLE_SPACING;
            if(ARM_COUNT!=4 || SEGMENTS_PER_ARM!=24 || samples!=96
                || !Double.isFinite(SAMPLE_SPACING) || SAMPLE_SPACING<.22D || SAMPLE_SPACING>.31D
                || Math.abs(SAMPLE_SPACING-RiftGeometry.FISSURE_SAMPLE_SPACING*.5D)>1.0E-9D
                || GROUND_ENDPOINT_TAPER_SAMPLES!=RiftGeometry.FISSURE_SEGMENTS_PER_ARM/2
                || !Double.isFinite(RiftGeometry.FISSURE_BEND_AMPLITUDE)
                || Math.abs(RiftGeometry.FISSURE_BEND_AMPLITUDE-.24D)>1.0E-9D
                || Math.abs(extent-expectedExtent)>1.0E-9D
                || extent+RiftGeometry.FISSURE_BEND_AMPLITUDE>=RADIUS
                || Math.abs(SAMPLE_SPACING*MAX_GROUND_SEGMENT_GRADE-.5625D)>1.0E-9D)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh sampling profile regression");
            samplingProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLING_PROFILE_VERIFIED samples={} spacing={} extent={} radius={} bend={}",
                samples,SAMPLE_SPACING,extent,RADIUS,RiftGeometry.FISSURE_BEND_AMPLITUDE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_BEND_PROFILE_VERIFIED amplitude={} maxOffset={} shared=true",
                RiftGeometry.FISSURE_BEND_AMPLITUDE,RiftGeometry.FISSURE_BEND_AMPLITUDE);
        }

        private static void verifyGroundSampleCacheProfile() {
            if(sampleOffsetsProfileLogged) return;
            if(GROUND_SAMPLES.length!=ARM_COUNT*SEGMENTS_PER_ARM)
                throw new IllegalStateException("BLACK FREQUENCY ground sample cache size regression");
            int checked=0;
            int coreSegmentGaps=0;
            int adjacentCoreGapPairs=0;
            int coreGapArmMask=0;
            int previousCoreGapSampleIndex=-1;
            int minimumCoreGapSeparation=Integer.MAX_VALUE;
            int leftEdgeNotches=0;
            int rightEdgeNotches=0;
            int adjacentLeftEdgeNotchPairs=0;
            int adjacentRightEdgeNotchPairs=0;
            int asymmetricEdgeSamples=0;
            double minimumCoreWidthScale=Double.POSITIVE_INFINITY;
            double maximumCoreWidthScale=Double.NEGATIVE_INFINITY;
            double maximumAdjacentCoreWidthDelta=0.0D;
            double minimumCoreSkew=Double.POSITIVE_INFINITY;
            double maximumCoreSkew=Double.NEGATIVE_INFINITY;
            double maximumAdjacentCoreSkewDelta=0.0D;
            boolean evenSamplesAligned=true;
            for(int arm=0;arm<ARM_COUNT;arm++) {
                double angle=arm*Math.PI/2.0D;
                double sinAngle=Math.sin(angle);
                double cosAngle=Math.cos(angle);
                double previousCoreWidthScale=Double.NaN;
                double previousCoreSkew=Double.NaN;
                boolean previousCoreSegmentGap=false;
                boolean previousLeftEdgeNotched=false;
                boolean previousRightEdgeNotched=false;
                for(int segment=1;segment<=SEGMENTS_PER_ARM;segment++) {
                    GroundSample sample=GROUND_SAMPLES[arm*SEGMENTS_PER_ARM+segment-1];
                    double sourceSample=segment*.5D;
                    double distance=sourceSample*RiftGeometry.FISSURE_SAMPLE_SPACING;
                    double bend=Math.sin(sourceSample*.85D+arm*1.1D)*RiftGeometry.FISSURE_BEND_AMPLITUDE;
                    double forwardX=cosAngle*distance;
                    double bendX=sinAngle*bend;
                    double forwardZ=sinAngle*distance;
                    double bendZ=cosAngle*bend;
                    double phase=arm*1.7D+sourceSample*.63D;
                    double phaseSin=Math.sin(phase);
                    double phaseCos=Math.cos(phase);
                    double flickerPhaseSin=Math.sin(phase*1.9D);
                    double flickerPhaseCos=Math.cos(phase*1.9D);
                    double coreWidthScale=Math.max(GROUND_MIN_CORE_WIDTH_SCALE,Math.min(GROUND_MAX_CORE_WIDTH_SCALE,
                        1.0D+Math.sin(phase)*GROUND_CORE_WIDTH_BROAD_AMPLITUDE
                            +Math.sin(phase*GROUND_CORE_WIDTH_DETAIL_FREQUENCY
                                +arm*GROUND_CORE_WIDTH_ARM_PHASE)*GROUND_CORE_WIDTH_DETAIL_AMPLITUDE));
                    double coreSkew=Math.max(-GROUND_CORE_SKEW_AMPLITUDE,Math.min(GROUND_CORE_SKEW_AMPLITUDE,
                        Math.sin(phase*GROUND_CORE_SKEW_FREQUENCY
                            +arm*GROUND_CORE_SKEW_ARM_PHASE)*GROUND_CORE_SKEW_AMPLITUDE));
                    if(sample==null
                        || Double.doubleToLongBits(sample.forwardX())!=Double.doubleToLongBits(forwardX)
                        || Double.doubleToLongBits(sample.bendX())!=Double.doubleToLongBits(bendX)
                        || Double.doubleToLongBits(sample.forwardZ())!=Double.doubleToLongBits(forwardZ)
                        || Double.doubleToLongBits(sample.bendZ())!=Double.doubleToLongBits(bendZ)
                        || Math.abs(sample.bendX())>RiftGeometry.FISSURE_BEND_AMPLITUDE
                        || Math.abs(sample.bendZ())>RiftGeometry.FISSURE_BEND_AMPLITUDE
                        || Double.doubleToLongBits(sample.phase())!=Double.doubleToLongBits(phase)
                        || Double.doubleToLongBits(sample.phaseSin())!=Double.doubleToLongBits(phaseSin)
                        || Double.doubleToLongBits(sample.phaseCos())!=Double.doubleToLongBits(phaseCos)
                        || Double.doubleToLongBits(sample.flickerPhaseSin())!=Double.doubleToLongBits(flickerPhaseSin)
                        || Double.doubleToLongBits(sample.flickerPhaseCos())!=Double.doubleToLongBits(flickerPhaseCos)
                        || Double.doubleToLongBits(sample.coreWidthScale())!=Double.doubleToLongBits(coreWidthScale)
                        || Double.doubleToLongBits(sample.coreSkew())!=Double.doubleToLongBits(coreSkew)
                        || !Double.isFinite(sample.coreWidthScale())
                        || !Double.isFinite(sample.coreSkew())
                        || sample.coreSkew() < -GROUND_CORE_SKEW_AMPLITUDE
                        || sample.coreSkew() > GROUND_CORE_SKEW_AMPLITUDE
                        || sample.coreWidthScale()<GROUND_MIN_CORE_WIDTH_SCALE
                        || sample.coreWidthScale()>GROUND_MAX_CORE_WIDTH_SCALE
                        || !Double.isFinite(sample.forwardX()) || !Double.isFinite(sample.bendX())
                        || !Double.isFinite(sample.forwardZ()) || !Double.isFinite(sample.bendZ())
                        || !Double.isFinite(sample.phase()) || !Double.isFinite(sample.phaseSin())
                        || !Double.isFinite(sample.phaseCos()) || !Double.isFinite(sample.flickerPhaseSin())
                        || !Double.isFinite(sample.flickerPhaseCos())
                        || sample.coreSegmentGap()!=isGroundCoreSegmentGap(phase)
                        || sample.leftEdgeNotched()!=isGroundEdgeNotched(phase,GROUND_LEFT_EDGE_NOTCH_SALT)
                        || sample.rightEdgeNotched()!=isGroundEdgeNotched(phase,GROUND_RIGHT_EDGE_NOTCH_SALT))
                        throw new IllegalStateException("BLACK FREQUENCY ground sample cache value regression");
                    if(sample.coreSegmentGap()) {
                        coreSegmentGaps++;
                        if(previousCoreSegmentGap) adjacentCoreGapPairs++;
                        coreGapArmMask|=1<<arm;
                        int gapSampleIndex=arm*SEGMENTS_PER_ARM+segment-1;
                        if(previousCoreGapSampleIndex>=0)
                            minimumCoreGapSeparation=Math.min(minimumCoreGapSeparation,
                                gapSampleIndex-previousCoreGapSampleIndex);
                        previousCoreGapSampleIndex=gapSampleIndex;
                    }
                    previousCoreSegmentGap=sample.coreSegmentGap();
                    if(sample.leftEdgeNotched()) {
                        leftEdgeNotches++;
                        if(previousLeftEdgeNotched) adjacentLeftEdgeNotchPairs++;
                    }
                    if(sample.rightEdgeNotched()) {
                        rightEdgeNotches++;
                        if(previousRightEdgeNotched) adjacentRightEdgeNotchPairs++;
                    }
                    previousLeftEdgeNotched=sample.leftEdgeNotched();
                    previousRightEdgeNotched=sample.rightEdgeNotched();
                    if(segment>1) {
                        double adjacentDelta=Math.abs(sample.coreWidthScale()-previousCoreWidthScale);
                        if(!Double.isFinite(adjacentDelta))
                            throw new IllegalStateException("BLACK FREQUENCY ground core width interpolation regression");
                        maximumAdjacentCoreWidthDelta=Math.max(maximumAdjacentCoreWidthDelta,adjacentDelta);
                        double adjacentSkewDelta=Math.abs(sample.coreSkew()-previousCoreSkew);
                        if(!Double.isFinite(adjacentSkewDelta))
                            throw new IllegalStateException("BLACK FREQUENCY ground core skew interpolation regression");
                        maximumAdjacentCoreSkewDelta=Math.max(maximumAdjacentCoreSkewDelta,adjacentSkewDelta);
                    }
                    previousCoreWidthScale=sample.coreWidthScale();
                    previousCoreSkew=sample.coreSkew();
                    minimumCoreSkew=Math.min(minimumCoreSkew,sample.coreSkew());
                    maximumCoreSkew=Math.max(maximumCoreSkew,sample.coreSkew());
                    minimumCoreWidthScale=Math.min(minimumCoreWidthScale,sample.coreWidthScale());
                    maximumCoreWidthScale=Math.max(maximumCoreWidthScale,sample.coreWidthScale());
                    if(sample.leftEdgeNotched()!=sample.rightEdgeNotched()) asymmetricEdgeSamples++;
                    double dx=sample.forwardX()-sample.bendX();
                    double dz=sample.forwardZ()+sample.bendZ();
                    if(dx*dx+dz*dz>=(double)RADIUS*RADIUS)
                        throw new IllegalStateException("BLACK FREQUENCY ground sample escaped fracture radius");
                    if(segment%2==0) {
                        int serverSegment=segment/2;
                        double serverDistance=serverSegment*RiftGeometry.FISSURE_SAMPLE_SPACING;
                        double serverBend=Math.sin(serverSegment*.85D+arm*1.1D)
                            *RiftGeometry.FISSURE_BEND_AMPLITUDE;
                        evenSamplesAligned &= Double.doubleToLongBits(sample.forwardX())
                                ==Double.doubleToLongBits(cosAngle*serverDistance)
                            && Double.doubleToLongBits(sample.bendX())
                                ==Double.doubleToLongBits(sinAngle*serverBend)
                            && Double.doubleToLongBits(sample.forwardZ())
                                ==Double.doubleToLongBits(sinAngle*serverDistance)
                            && Double.doubleToLongBits(sample.bendZ())
                                ==Double.doubleToLongBits(cosAngle*serverBend);
                    }
                    // Validate the angle-addition cache against the original per-segment sine formulas.
                    for(double time:new double[]{0.0D,1.0D,20.0D,1234.5D,-120.0D}) {
                        double wave=Math.sin(time*.14D+phase);
                        double waveCached=Math.sin(time*.14D)*sample.phaseCos()
                            +Math.cos(time*.14D)*sample.phaseSin();
                        double pulse=Math.sin(time*.19D+phase);
                        double pulseCached=Math.sin(time*.19D)*sample.phaseCos()
                            +Math.cos(time*.19D)*sample.phaseSin();
                        double flicker=Math.sin(time*.055D+phase*1.9D);
                        double flickerCached=Math.sin(time*.055D)*sample.flickerPhaseCos()
                            +Math.cos(time*.055D)*sample.flickerPhaseSin();
                        if(Math.abs(wave-waveCached)>1.0E-12D
                            || Math.abs(pulse-pulseCached)>1.0E-12D
                            || Math.abs(flicker-flickerCached)>1.0E-12D)
                            throw new IllegalStateException("BLACK FREQUENCY ground animation phase cache regression");
                    }
                    checked++;
                }
            }
            if(groundCoreWidthScale(Double.NaN,0)!=1.0D
                || groundCoreWidthScale(0.0D,ARM_COUNT)!=1.0D)
                throw new IllegalStateException("BLACK FREQUENCY ground core width invalid-input fallback regression");
            if(groundCoreSkew(Double.NaN,0)!=0.0D
                || groundCoreSkew(0.0D,ARM_COUNT)!=0.0D)
                throw new IllegalStateException("BLACK FREQUENCY ground core skew invalid-input fallback regression");
            if(checked!=96 || !evenSamplesAligned || coreSegmentGaps!=2
                || adjacentCoreGapPairs!=0 || Integer.bitCount(coreGapArmMask)!=2
                || minimumCoreGapSeparation<12 || leftEdgeNotches!=4 || rightEdgeNotches!=4
                || adjacentLeftEdgeNotchPairs!=0 || adjacentRightEdgeNotchPairs!=0 || asymmetricEdgeSamples!=6
                || minimumCoreWidthScale<GROUND_MIN_CORE_WIDTH_SCALE
                || maximumCoreWidthScale>GROUND_MAX_CORE_WIDTH_SCALE
                || maximumAdjacentCoreWidthDelta>.075D
                || minimumCoreSkew < -GROUND_CORE_SKEW_AMPLITUDE
                || maximumCoreSkew > GROUND_CORE_SKEW_AMPLITUDE
                || maximumAdjacentCoreSkewDelta>.04D)
                throw new IllegalStateException("BLACK FREQUENCY ground edge notch profile regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CORE_SEGMENT_GAP_PROFILE_VERIFIED samples={} gaps={} adjacentGapPairs={} gapArms={} minGapSeparation={} deterministic=true gapRate=1/64",
                checked,coreSegmentGaps,adjacentCoreGapPairs,Integer.bitCount(coreGapArmMask),minimumCoreGapSeparation);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EDGE_NOTCH_PROFILE_VERIFIED samples={} left={} right={} asymmetricSamples={} adjacentLeftPairs={} adjacentRightPairs={} deterministic=true notchRate=1/32",
                checked,leftEdgeNotches,rightEdgeNotches,asymmetricEdgeSamples,
                adjacentLeftEdgeNotchPairs,adjacentRightEdgeNotchPairs);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CORE_WIDTH_PROFILE_VERIFIED samples={} minBound={} maxBound={} maxAdjacentDeltaLimit=0.075 interpolated=true bounded=true harmonics=2 broadAmplitude=0.16 detailAmplitude=0.05 detailFrequency=0.43 armPhase=1.13 invalidFallback=1.0",
                checked,GROUND_MIN_CORE_WIDTH_SCALE,GROUND_MAX_CORE_WIDTH_SCALE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CORE_ASYMMETRY_PROFILE_VERIFIED samples={} minBound=-0.15 maxBound=0.15 maxAdjacentDeltaLimit=0.04 interpolated=true deterministic=true invalidFallback=0.0",
                checked);
            sampleOffsetsProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_OFFSETS_VERIFIED samples={} trigPerRebuild=0 bounded=true phaseCached=true",
                checked);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_ANIMATION_PHASE_CACHE_VERIFIED samples={} perFrameTrig=6 legacyPerSegmentTrig=3 equivalent=true",
                checked);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_DENSITY_VERIFIED meshSamples={} serverSamples={} ratio=2 evenSamplesAligned=true extent={}",
                checked,checked/2,SEGMENTS_PER_ARM*SAMPLE_SPACING);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EVEN_SAMPLE_ALIGNMENT_VERIFIED meshSamples={} serverSamples={} aligned=true",
                checked,checked/2);
        }

        private static void verifyTaperProfile() {
            if(!taperProfileLogged) {
                double start=endpointTaper(0);
                double first=endpointTaper(1);
                double second=endpointTaper(2);
                double middle=endpointTaper(SEGMENTS_PER_ARM/2);
                double secondLast=endpointTaper(SEGMENTS_PER_ARM-2);
                double last=endpointTaper(SEGMENTS_PER_ARM-1);
                double end=endpointTaper(SEGMENTS_PER_ARM);
                double expectedSecondLast=CLIFF_BREAK_TAPER
                    +(1.0D-CLIFF_BREAK_TAPER)*(7.0D/27.0D);
                double expectedLast=CLIFF_BREAK_TAPER
                    +(1.0D-CLIFF_BREAK_TAPER)*(2.0D/27.0D);
                boolean beaconSideRises=start<first && first<second && second<middle;
                boolean outerTipFalls=middle>secondLast && secondLast>last && last>end;
                boolean asymmetric=start>end && first>last;
                boolean invalidClamped=endpointTaper(-1)==start
                    && endpointTaper(SEGMENTS_PER_ARM+1)==end;
                boolean startSmoothstep=Math.abs(first-(.5D+1.0D/27.0D))<1.0E-9D
                    && Math.abs(second-(17.0D/27.0D))<1.0E-9D
                    && first<7.0D/12.0D && second<2.0D/3.0D;
                boolean outerSmoothstep=Math.abs(secondLast-expectedSecondLast)<1.0E-9D
                    && Math.abs(last-expectedLast)<1.0E-9D;
                boolean boundedFloors=Math.abs(start-.5D)<1.0E-9D
                    && Math.abs(middle-1.0D)<1.0E-9D
                    && Math.abs(end-CLIFF_BREAK_TAPER)<1.0E-9D;
                if(!(beaconSideRises && outerTipFalls && asymmetric && invalidClamped
                    && startSmoothstep && outerSmoothstep && boundedFloors))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh taper regression");
                taperProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED start={} first={} second={} middle={} secondLast={} last={} end={} asymmetric={} invalidClamped={}",
                    start,first,second,middle,secondLast,last,end,asymmetric,invalidClamped);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_EASING_VERIFIED startCurve=smoothstep outerCurve=smoothstep zeroSlopeAtEnds=true sameSpan=true beaconStartFloor=0.5 outerTipFloor={}",
                    CLIFF_BREAK_TAPER);
            }
            if(!visualProfileLogged) {
                double minimumHalfWidth=GROUND_MIN_HALF_WIDTH;
                double maximumHalfWidth=GROUND_MAX_HALF_WIDTH;
                double minimumGlowHalfWidth=minimumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double minimumCoreHalfWidth=minimumHalfWidth*GROUND_CORE_WIDTH_SCALE*GROUND_MIN_CORE_WIDTH_SCALE
                    *(1.0D-GROUND_CORE_SKEW_AMPLITUDE);
                double edgeOffsetSkew=GROUND_CORE_SKEW_AMPLITUDE*GROUND_EDGE_OFFSET_SKEW_SCALE;
                double minimumEdgeHalfWidth=groundEdgeHalfWidth(minimumHalfWidth);
                double maximumGlowHalfWidth=maximumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double maximumCoreHalfWidth=maximumHalfWidth*GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE
                    *(1.0D+GROUND_CORE_SKEW_AMPLITUDE);
                double maximumEdgeHalfWidth=groundEdgeHalfWidth(maximumHalfWidth);
                double minimumEdgeInnerHalfWidth=minimumHalfWidth*GROUND_EDGE_OFFSET_SCALE
                    *(1.0D-edgeOffsetSkew)-minimumEdgeHalfWidth;
                double maximumEdgeInnerHalfWidth=maximumHalfWidth*GROUND_EDGE_OFFSET_SCALE
                    *(1.0D+edgeOffsetSkew)-maximumEdgeHalfWidth;
                double leftCoreEdgeGap=maximumHalfWidth*(GROUND_EDGE_OFFSET_SCALE*(1.0D+edgeOffsetSkew)
                    -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D+GROUND_CORE_SKEW_AMPLITUDE))
                    -maximumEdgeHalfWidth;
                double rightCoreEdgeGap=maximumHalfWidth*(GROUND_EDGE_OFFSET_SCALE*(1.0D-edgeOffsetSkew)
                    -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D-GROUND_CORE_SKEW_AMPLITUDE))
                    -maximumEdgeHalfWidth;
                double minimumCoreEdgeGap=Math.min(leftCoreEdgeGap,rightCoreEdgeGap);
                double maximumEdgeOffsetDelta=groundEdgeMaximumOffsetDelta(maximumHalfWidth);
                if(Math.abs(GROUND_GLOW_WIDTH_SCALE-5.75D)>1.0E-9D
                    || Math.abs(GROUND_CORE_WIDTH_SCALE-1.5D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_OFFSET_SCALE-2.6D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_OFFSET_SKEW_SCALE-.12D)>1.0E-9D
                    || !Double.isFinite(maximumEdgeOffsetDelta) || maximumEdgeOffsetDelta>.004D
                    || Math.abs(GROUND_CORE_SKEW_AMPLITUDE-.15D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_HALF_WIDTH-.025D)>1.0E-9D
                    || Math.abs(maximumHalfWidth-.074D)>1.0E-9D
                    || !Float.isFinite(GROUND_EDGE_RED) || !Float.isFinite(GROUND_EDGE_GREEN)
                    || !Float.isFinite(GROUND_EDGE_BLUE)
                    || GROUND_EDGE_RED<0.0F || GROUND_EDGE_RED>1.0F
                    || GROUND_EDGE_GREEN<0.0F || GROUND_EDGE_GREEN>1.0F
                    || GROUND_EDGE_BLUE<0.0F || GROUND_EDGE_BLUE>1.0F
                    || Math.abs(GROUND_EDGE_RED-.50F)>1.0E-6F
                    || Math.abs(GROUND_EDGE_GREEN-.14F)>1.0E-6F
                    || Math.abs(GROUND_EDGE_BLUE-.90F)>1.0E-6F
                    || !(GROUND_EDGE_BLUE>GROUND_EDGE_RED && GROUND_EDGE_RED>GROUND_EDGE_GREEN)
                    || !Double.isFinite(minimumCoreEdgeGap) || minimumCoreEdgeGap<GROUND_MIN_CORE_EDGE_GAP
                    || !(minimumCoreHalfWidth<minimumEdgeInnerHalfWidth
                        && minimumEdgeInnerHalfWidth<minimumGlowHalfWidth)
                    || !(maximumCoreHalfWidth<maximumEdgeInnerHalfWidth
                        && maximumEdgeInnerHalfWidth<maximumGlowHalfWidth))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh visual profile regression");
                visualProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_VISUAL_PROFILE_VERIFIED glowScale={} coreScale={} edgeOffsetScale={} edgeHalfWidth={} pulseMin={} pulseMax={} coreInsideEdgeBand=true",
                    GROUND_GLOW_WIDTH_SCALE,GROUND_CORE_WIDTH_SCALE,GROUND_EDGE_OFFSET_SCALE,GROUND_EDGE_HALF_WIDTH,
                    minimumHalfWidth,maximumHalfWidth);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_CORE_EDGE_GAP_VERIFIED min={} required={} valid=true",
                    minimumCoreEdgeGap,GROUND_MIN_CORE_EDGE_GAP);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_EDGE_OFFSET_ASYMMETRY_VERIFIED scale={} maxSkew={} maxOffsetDelta={} correlatedWithCore=true bounded=true",
                    GROUND_EDGE_OFFSET_SKEW_SCALE,edgeOffsetSkew,maximumEdgeOffsetDelta);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_EDGE_PALETTE_VERIFIED red={} green={} blue={}",
                    GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE);
            }
            if(!supportSurfaceProfileLogged) {
                double fullBlock=groundSurfaceOffset(1.0D,Double.NaN);
                double bottomSlab=groundSurfaceOffset(.5D,Double.NaN);
                double invalidSupport=groundSurfaceOffset(Double.NaN,Double.NaN);
                if(Math.abs(fullBlock-1.0D)>1.0E-9D
                    || Math.abs(bottomSlab-.5D)>1.0E-9D
                    || Math.abs(invalidSupport-1.0D)>1.0E-9D)
                    throw new IllegalStateException("BLACK FREQUENCY ground support-top profile regression");
                supportSurfaceProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_TOP_PROFILE_VERIFIED fullBlock={} bottomSlab={} invalidFallback={} nonCollidingCoverIgnored=true",
                    fullBlock,bottomSlab,invalidSupport);
            }
        }

        private static void verifyGroundSurfaceReliefProfile() {
            if(reliefProfileLogged) return;
            double coreClearance=GROUND_SURFACE_CLEARANCE-GROUND_CORE_RECESS;
            double glowClearance=GROUND_SURFACE_CLEARANCE;
            double filamentClearance=GROUND_SURFACE_CLEARANCE+GROUND_FILAMENT_LIFT;
            double edgeClearance=GROUND_SURFACE_CLEARANCE+GROUND_EDGE_LIFT;
            double minimumGlowHalfWidth=GROUND_MIN_HALF_WIDTH*GROUND_GLOW_WIDTH_SCALE;
            double maximumGlowHalfWidth=GROUND_MAX_HALF_WIDTH*GROUND_GLOW_WIDTH_SCALE;
            double minimumCoreHalfWidth=GROUND_MIN_HALF_WIDTH*GROUND_CORE_WIDTH_SCALE*GROUND_MIN_CORE_WIDTH_SCALE
                *(1.0D-GROUND_CORE_SKEW_AMPLITUDE);
            double edgeOffsetSkew=GROUND_CORE_SKEW_AMPLITUDE*GROUND_EDGE_OFFSET_SKEW_SCALE;
            double minimumEdgeHalfWidth=groundEdgeHalfWidth(GROUND_MIN_HALF_WIDTH);
            double minimumEdgeInnerHalfWidth=GROUND_MIN_HALF_WIDTH*GROUND_EDGE_OFFSET_SCALE
                *(1.0D-edgeOffsetSkew)-minimumEdgeHalfWidth;
            double maximumHalfWidth=GROUND_MAX_HALF_WIDTH;
            double maximumCoreHalfWidth=maximumHalfWidth*GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE
                *(1.0D+GROUND_CORE_SKEW_AMPLITUDE);
            double maximumEdgeHalfWidth=groundEdgeHalfWidth(maximumHalfWidth);
            double maximumEdgeInnerHalfWidth=maximumHalfWidth*GROUND_EDGE_OFFSET_SCALE
                *(1.0D+edgeOffsetSkew)-maximumEdgeHalfWidth;
            double leftCoreEdgeGap=maximumHalfWidth*(GROUND_EDGE_OFFSET_SCALE*(1.0D+edgeOffsetSkew)
                -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D+GROUND_CORE_SKEW_AMPLITUDE))
                -maximumEdgeHalfWidth;
            double rightCoreEdgeGap=maximumHalfWidth*(GROUND_EDGE_OFFSET_SCALE*(1.0D-edgeOffsetSkew)
                -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D-GROUND_CORE_SKEW_AMPLITUDE))
                -maximumEdgeHalfWidth;
            double minimumCoreEdgeGap=Math.min(leftCoreEdgeGap,rightCoreEdgeGap);
            double maximumBevelHalfWidth=maximumCoreHalfWidth+GROUND_BEVEL_WIDTH;
            double minimumBevelLiftScale=1.0D-GROUND_BEVEL_LIFT_PULSE_RANGE;
            double maximumBevelLiftScale=1.0D+GROUND_BEVEL_LIFT_PULSE_RANGE;
            double minimumBevelOuterClearance=GROUND_SURFACE_CLEARANCE+GROUND_BEVEL_LIFT*minimumBevelLiftScale;
            double maximumBevelOuterClearance=GROUND_SURFACE_CLEARANCE+GROUND_BEVEL_LIFT*maximumBevelLiftScale;
            double midpointLeftBevelLiftScale=groundBevelLiftScale(.5D,true);
            double midpointRightBevelLiftScale=groundBevelLiftScale(.5D,false);
            boolean complementaryBevelLift=true;
            for(int sample=0;sample<=16;sample++) {
                double wave=sample/16.0D;
                double leftScale=groundBevelLiftScale(wave,true);
                double rightScale=groundBevelLiftScale(wave,false);
                complementaryBevelLift &= Double.isFinite(leftScale) && Double.isFinite(rightScale)
                    && Math.abs(leftScale+rightScale-2.0D)<1.0E-12D
                    && leftScale>=minimumBevelLiftScale && leftScale<=maximumBevelLiftScale
                    && rightScale>=minimumBevelLiftScale && rightScale<=maximumBevelLiftScale;
            }
            boolean invalidBevelLiftRejected=Double.isNaN(groundBevelLiftScale(Double.NaN,true))
                && Double.isNaN(groundBevelLiftScale(Double.POSITIVE_INFINITY,false));
            double leftBevelOffset=groundBevelOuterOffset(.12D,1.0D,true);
            double rightBevelOffset=groundBevelOuterOffset(-.12D,1.0D,false);
            double halfTaperBevelOffset=groundBevelOuterOffset(.12D,.5D,true);
            boolean bevelOffsets=Math.abs(leftBevelOffset-.165D)<1.0E-9D
                && Math.abs(rightBevelOffset+.165D)<1.0E-9D
                && Math.abs(halfTaperBevelOffset-.1425D)<1.0E-9D;
            boolean invalidBevelRejected=Double.isNaN(groundBevelOuterOffset(Double.NaN,1.0D,true))
                && Double.isNaN(groundBevelOuterOffset(.12D,Double.POSITIVE_INFINITY,false));
            if(!Double.isFinite(coreClearance) || !Double.isFinite(glowClearance)
                || !Double.isFinite(filamentClearance) || !Double.isFinite(edgeClearance)
                || !Double.isFinite(minimumGlowHalfWidth) || !Double.isFinite(maximumGlowHalfWidth)
                || !Double.isFinite(minimumCoreHalfWidth) || !Double.isFinite(minimumEdgeInnerHalfWidth)
                || !Double.isFinite(maximumCoreHalfWidth) || !Double.isFinite(maximumEdgeInnerHalfWidth)
                || !Double.isFinite(minimumCoreEdgeGap)
                || coreClearance<GROUND_MIN_CORE_CLEARANCE || !(coreClearance<glowClearance)
                || !(glowClearance<filamentClearance) || !(filamentClearance<edgeClearance)
                || minimumCoreEdgeGap<GROUND_MIN_CORE_EDGE_GAP
                || !(minimumCoreHalfWidth<minimumEdgeInnerHalfWidth
                    && minimumEdgeInnerHalfWidth<minimumGlowHalfWidth)
                || !(maximumCoreHalfWidth<maximumEdgeInnerHalfWidth
                    && maximumEdgeInnerHalfWidth<maximumGlowHalfWidth)
                || Math.abs(GROUND_EDGE_OFFSET_SCALE-2.6D)>1.0E-9D
                || Math.abs(GROUND_EDGE_OFFSET_SKEW_SCALE-.12D)>1.0E-9D
                || Math.abs(GROUND_CORE_SKEW_AMPLITUDE-.15D)>1.0E-9D
                || Math.abs(GROUND_SURFACE_CLEARANCE-.010D)>1.0E-9D
                || Math.abs(GROUND_CORE_RECESS-.005D)>1.0E-9D
                || Math.abs(GROUND_FILAMENT_LIFT-.001D)>1.0E-9D
                || Math.abs(GROUND_EDGE_LIFT-.005D)>1.0E-9D
                || Math.abs(GROUND_BEVEL_WIDTH-.045D)>1.0E-9D
                || Math.abs(GROUND_BEVEL_LIFT-.010D)>1.0E-9D
                || Math.abs(GROUND_BEVEL_LIFT_PULSE_RANGE-.10D)>1.0E-9D
                || !Double.isFinite(maximumBevelHalfWidth)
                || !(maximumCoreHalfWidth<maximumBevelHalfWidth
                    && maximumBevelHalfWidth<maximumGlowHalfWidth)
                || !(edgeClearance<minimumBevelOuterClearance
                    && minimumBevelOuterClearance>=.018D
                    && maximumBevelOuterClearance<=.022D)
                || Math.abs(midpointLeftBevelLiftScale-1.0D)>1.0E-12D
                || Math.abs(midpointRightBevelLiftScale-1.0D)>1.0E-12D
                || !complementaryBevelLift || !invalidBevelLiftRejected
                || !bevelOffsets || !invalidBevelRejected)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh surface relief regression");
            double fullBlockSnowOffset=groundSurfaceOffset(1.0D,.125D);
            double partialSupportSnowOffset=groundSurfaceOffset(.9375D,.125D);
            double invalidSnowFallback=groundSurfaceOffset(.9375D,Double.NaN);
            double boundedSnowOffset=groundSurfaceOffset(1.5D,1.25D);
            if(Math.abs(fullBlockSnowOffset-1.125D)>1.0E-9D
                || Math.abs(partialSupportSnowOffset-1.0625D)>1.0E-9D
                || Math.abs(invalidSnowFallback-.9375D)>1.0E-9D
                || Math.abs(boundedSnowOffset-2.0D)>1.0E-9D)
                throw new IllegalStateException("BLACK FREQUENCY snow-layer ground surface offset regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SNOW_SURFACE_OFFSET_VERIFIED fullBlock=1.125 partialSupport=1.0625 supportIncluded=true invalidSnowFallback=true bounded=true");
            reliefProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SURFACE_RELIEF_VERIFIED coreRecess={} filamentLift={} edgeLift={} coreClearance={} glowClearance={} filamentClearance={} edgeClearance={} ordered=true",
                GROUND_CORE_RECESS,GROUND_FILAMENT_LIFT,GROUND_EDGE_LIFT,
                coreClearance,glowClearance,filamentClearance,edgeClearance);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EMBEDDED_CRACK_PROFILE_VERIFIED minGlowHalfWidth={} maxGlowHalfWidth={} minCoreHalfWidth={} minEdgeInnerHalfWidth={} maxCoreHalfWidth={} maxEdgeInnerHalfWidth={} minCoreEdgeGap={} coreClearance={} glowClearance={} filamentClearance={} edgeClearance={} surfaceAligned=true",
                minimumGlowHalfWidth,maximumGlowHalfWidth,minimumCoreHalfWidth,
                minimumEdgeInnerHalfWidth,maximumCoreHalfWidth,maximumEdgeInnerHalfWidth,minimumCoreEdgeGap,coreClearance,glowClearance,
                filamentClearance,edgeClearance);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_RECESSED_CORE_PROFILE_VERIFIED minClearance={} supportTop=true depthTest=true",
                GROUND_MIN_CORE_CLEARANCE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_BEVEL_PROFILE_VERIFIED width={} lift={} innerRecess={} minOuterClearance={} maxOuterClearance={} maxOuterHalfWidth={} maxGlowHalfWidth={} withinGlow=true offsets=true invalidRejected=true",
                GROUND_BEVEL_WIDTH,GROUND_BEVEL_LIFT,GROUND_CORE_RECESS,minimumBevelOuterClearance,maximumBevelOuterClearance,maximumBevelHalfWidth,maximumGlowHalfWidth);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_BEVEL_PULSE_PROFILE_VERIFIED minScale={} maxScale={} midpointLeft={} midpointRight={} complementary=true bounded=true invalidRejected=true noExtraTrig=true",
                minimumBevelLiftScale,maximumBevelLiftScale,midpointLeftBevelLiftScale,midpointRightBevelLiftScale);
        }

        private static void verifyGroundSlopeWidthProfile() {
            if(slopeWidthProfileLogged) return;
            GroundPoint flatA=new GroundPoint(0.0D,0.0D,0.0D);
            GroundPoint flatB=new GroundPoint(1.0D,0.0D,0.0D);
            GroundPoint halfGradeB=new GroundPoint(1.0D,MAX_GROUND_SEGMENT_GRADE*.5D,0.0D);
            GroundPoint maxGradeB=new GroundPoint(1.0D,MAX_GROUND_SEGMENT_GRADE,0.0D);
            GroundPoint overGradeB=new GroundPoint(1.0D,MAX_GROUND_SEGMENT_GRADE*2.0D,0.0D);
            double flat=groundSlopeWidthScale(flatA,flatB);
            double halfGrade=groundSlopeWidthScale(flatA,halfGradeB);
            double maxGrade=groundSlopeWidthScale(flatA,maxGradeB);
            double overGrade=groundSlopeWidthScale(flatA,overGradeB);
            double invalid=groundSlopeWidthScale(new GroundPoint(Double.NaN,0.0D,0.0D),flatB);
            double minimumPulseHalfWidth=GROUND_MIN_HALF_WIDTH*(1.0D-GROUND_SLOPE_WIDTH_REDUCTION);
            double minimumEdgeHalfWidth=groundEdgeHalfWidth(minimumPulseHalfWidth);
            double midpointEdgeHalfWidth=groundEdgeHalfWidth(
                (minimumPulseHalfWidth+GROUND_MAX_HALF_WIDTH)*.5D);
            double maximumEdgeHalfWidth=groundEdgeHalfWidth(GROUND_MAX_HALF_WIDTH);
            double invalidEdgeHalfWidth=groundEdgeHalfWidth(Double.NaN);
            double maximumEdgeSkew=GROUND_CORE_SKEW_AMPLITUDE*GROUND_EDGE_OFFSET_SKEW_SCALE;
            double leftGap=minimumPulseHalfWidth
                *(GROUND_EDGE_OFFSET_SCALE*(1.0D+maximumEdgeSkew)
                    -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D+GROUND_CORE_SKEW_AMPLITUDE))
                -minimumEdgeHalfWidth;
            double rightGap=minimumPulseHalfWidth
                *(GROUND_EDGE_OFFSET_SCALE*(1.0D-maximumEdgeSkew)
                    -GROUND_CORE_WIDTH_SCALE*GROUND_MAX_CORE_WIDTH_SCALE*(1.0D-GROUND_CORE_SKEW_AMPLITUDE))
                -minimumEdgeHalfWidth;
            double minimumCoreEdgeGap=Math.min(leftGap,rightGap);
            GroundSegment cached=new GroundSegment(flatA,maxGradeB,0.0D,1.0D,GROUND_SAMPLES[0],
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            boolean edgeWidthMonotonic=minimumEdgeHalfWidth<=midpointEdgeHalfWidth
                && midpointEdgeHalfWidth<=maximumEdgeHalfWidth;
            if(Math.abs(GROUND_SLOPE_WIDTH_REDUCTION-.15D)>1.0E-12D
                || Math.abs(GROUND_EDGE_HALF_WIDTH-.025D)>1.0E-12D
                || Math.abs(flat-1.0D)>1.0E-12D
                || Math.abs(halfGrade-.925D)>1.0E-12D
                || Math.abs(maxGrade-.85D)>1.0E-12D
                || Math.abs(overGrade-.85D)>1.0E-12D
                || invalid!=1.0D
                || Math.abs(cached.slopeWidthScale()-maxGrade)>1.0E-12D
                || !edgeWidthMonotonic
                || invalidEdgeHalfWidth!=0.0D
                || Math.abs(maximumEdgeHalfWidth-GROUND_EDGE_HALF_WIDTH)>1.0E-12D
                || !Double.isFinite(minimumCoreEdgeGap)
                || minimumCoreEdgeGap<GROUND_MIN_CORE_EDGE_GAP)
                throw new IllegalStateException("BLACK FREQUENCY ground slope/edge width profile regression");
            slopeWidthProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SLOPE_WIDTH_PROFILE_VERIFIED reduction={} flat={} halfGrade={} maxGrade={} overGrade={} minPulseHalfWidth={} minEdgeHalfWidth={} maxEdgeHalfWidth={} minCoreEdgeGap={} bounded=true monotonic=true cachedPerSegment=true invalidNeutral=true coreSeparated=true",
                GROUND_SLOPE_WIDTH_REDUCTION,flat,halfGrade,maxGrade,overGrade,minimumPulseHalfWidth,
                minimumEdgeHalfWidth,maximumEdgeHalfWidth,minimumCoreEdgeGap);
        }

        private static double boundedShapeHeight(double height,double fallback) {
            return Double.isFinite(height)
                ? Math.max(0.0D,Math.min(1.0D,height))
                : fallback;
        }

        // Anchor the fissure to the supporting block's collision top. Non-colliding
        // foliage remains in front and occludes it through depth testing; lifting the
        // whole ribbon to plant-canopy height made the crack look like a raised cable.
        private static double groundSurfaceOffset(double supportHeight,double snowLayerHeight) {
            // Snow layers sit on the support's actual collision top, which may be below a full block.
            double supportOffset=boundedShapeHeight(supportHeight,1.0D);
            if(Double.isFinite(snowLayerHeight) && snowLayerHeight>0.0D)
                return supportOffset+Math.min(1.0D,snowLayerHeight);
            return supportOffset;
        }

        // Adjacent samples are connected only when both the height delta and the
        // actual vertical/horizontal grade are bounded. The grade doubles with the
        // halved sample spacing, preserving the previous 0.5625-block rise limit.
        private static boolean isContinuousGroundStep(double fromY,double toY,double horizontalDistance) {
            if(!Double.isFinite(fromY) || !Double.isFinite(toY)
                || !Double.isFinite(horizontalDistance) || horizontalDistance<=1.0E-5D) return false;
            double rise=Math.abs(toY-fromY);
            return Double.isFinite(rise)
                && rise<=MAX_GROUND_SEGMENT_Y_DELTA
                && rise<=horizontalDistance*MAX_GROUND_SEGMENT_GRADE;
        }

        private static boolean canStartSingleGroundStepBridge(double fromY,double stepY,double horizontalDistance) {
            if(!Double.isFinite(fromY) || !Double.isFinite(stepY)
                || !Double.isFinite(horizontalDistance) || horizontalDistance<=1.0E-5D) return false;
            double rise=Math.abs(stepY-fromY);
            return Double.isFinite(rise) && rise>0.0D
                && rise<=MAX_BRIDGED_GROUND_STEP_Y_DELTA
                && isContinuousGroundStep(fromY,stepY,horizontalDistance*2.0D);
        }

        private static boolean canBridgeSingleGroundStep(double fromY,double stepY,double toY,
                double firstStepDistance,double spanDistance) {
            if(!canStartSingleGroundStepBridge(fromY,stepY,firstStepDistance)
                || !Double.isFinite(toY)) return false;
            double secondRise=Math.abs(toY-stepY);
            return Double.isFinite(secondRise)
                && secondRise<=MAX_BRIDGED_GROUND_STEP_Y_DELTA
                && isContinuousGroundStep(fromY,toY,spanDistance);
        }

        private static double cliffBreakTaper(double taper) {
            if(!Double.isFinite(taper)) return 0.0D;
            return Math.max(0.0D,Math.min(CLIFF_BREAK_TAPER,taper));
        }

        // Keep the violet lips intact where the mesh already tapers into a real terrain break.
        // This removes stacked decorative gaps without extending geometry across the break.
        private static boolean isGroundSegmentCap(GroundSegment segment) {
            return segment!=null
                && Double.isFinite(segment.taperA())
                && Double.isFinite(segment.taperB())
                && (segment.taperA()<=CLIFF_BREAK_TAPER+GROUND_CAP_TAPER_EPSILON
                    || segment.taperB()<=CLIFF_BREAK_TAPER+GROUND_CAP_TAPER_EPSILON);
        }

        private static final int GROUND_EDGE_LEFT_MASK=1;
        private static final int GROUND_EDGE_RIGHT_MASK=2;

        // Compute the shared terrain-cap decision once; both violet lips consume the same mask.
        private static int groundEdgeMask(GroundSample sample,GroundSegment segment) {
            if(sample==null || segment==null
                || !Double.isFinite(segment.taperA()) || !Double.isFinite(segment.taperB())) return 0;
            if(isGroundSegmentCap(segment)) return GROUND_EDGE_LEFT_MASK|GROUND_EDGE_RIGHT_MASK;
            int mask=0;
            if(!sample.leftEdgeNotched()) mask|=GROUND_EDGE_LEFT_MASK;
            if(!sample.rightEdgeNotched()) mask|=GROUND_EDGE_RIGHT_MASK;
            return mask;
        }

        private static void verifyGroundEdgeMaskProfile() {
            if(edgeMaskProfileLogged) return;
            GroundSample notched=new GroundSample(0.0D,0.0D,0.0D,0.0D,0.0D,
                0.0D,1.0D,0.0D,1.0D,1.0D,0.0D,false,true,true);
            GroundSample intact=new GroundSample(0.0D,0.0D,0.0D,0.0D,0.0D,
                0.0D,1.0D,0.0D,1.0D,1.0D,0.0D,false,false,false);
            GroundPoint a=new GroundPoint(0.0D,64.0D,0.0D);
            GroundPoint b=new GroundPoint(1.0D,64.0D,0.0D);
            GroundSegment ordinary=new GroundSegment(a,b,0.0D,1.0D,notched,
                .5D,.8D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment startCap=new GroundSegment(a,b,0.0D,1.0D,notched,
                CLIFF_BREAK_TAPER,.8D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment endCap=new GroundSegment(a,b,0.0D,1.0D,notched,
                .8D,CLIFF_BREAK_TAPER,1.0D,1.0D,0.0D,0.0D);
            GroundSegment invalid=new GroundSegment(a,b,0.0D,1.0D,notched,
                Double.NaN,.8D,1.0D,1.0D,0.0D,0.0D);
            int ordinaryMask=groundEdgeMask(notched,ordinary);
            int startCapMask=groundEdgeMask(notched,startCap);
            int endCapMask=groundEdgeMask(notched,endCap);
            int intactMask=groundEdgeMask(intact,ordinary);
            int invalidMask=groundEdgeMask(notched,invalid);
            int bothEdgesMask=GROUND_EDGE_LEFT_MASK|GROUND_EDGE_RIGHT_MASK;
            boolean ordinaryNotchesPreserved=ordinaryMask==0;
            boolean startCapLipsPreserved=startCapMask==bothEdgesMask;
            boolean endCapLipsPreserved=endCapMask==bothEdgesMask;
            boolean intactLipsPreserved=intactMask==bothEdgesMask;
            boolean invalidRejected=invalidMask==0
                && groundEdgeMask(null,ordinary)==0
                && groundEdgeMask(notched,null)==0;
            if(!(ordinaryNotchesPreserved && startCapLipsPreserved && endCapLipsPreserved
                && intactLipsPreserved && invalidRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground edge mask regression");
            edgeMaskProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EDGE_MASK_VERIFIED leftBit={} rightBit={} capMask={} ordinaryMask={} invalidMask={}",
                GROUND_EDGE_LEFT_MASK,GROUND_EDGE_RIGHT_MASK,bothEdgesMask,ordinaryMask,invalidMask);
        }

        private static boolean capLastGroundSegmentAtGap(List<GroundSegment> segments,
                int armStart,GroundPoint endpoint) {
            if(endpoint==null || segments.size()<=armStart) return false;
            int lastIndex=segments.size()-1;
            GroundSegment last=segments.get(lastIndex);
            // Only cap a segment whose end is the exact point at this gap.
            if(last.b()!=endpoint) return false;
            double cappedTaper=cliffBreakTaper(last.taperB());
            if(cappedTaper>=last.taperB()) return false;
            segments.set(lastIndex,new GroundSegment(last.a(),last.b(),
                last.normalX(),last.normalZ(),last.sample(),last.taperA(),cappedTaper,
                last.coreWidthScaleA(),last.coreWidthScaleB(),last.coreSkewA(),last.coreSkewB()));
            return true;
        }

        private static void verifyGroundContinuityProfile() {
            if(continuityProfileLogged) return;
            boolean flat=isContinuousGroundStep(64.0D,64.0D,SAMPLE_SPACING);
            boolean snowStep=isContinuousGroundStep(64.0D,64.125D,SAMPLE_SPACING);
            boolean halfBlockRamp=isContinuousGroundStep(64.0D,64.5D,SAMPLE_SPACING);
            boolean oneBlockSampleRejected=!isContinuousGroundStep(64.0D,65.0D,SAMPLE_SPACING);
            boolean oneBlockRampAccepted=isContinuousGroundStep(64.0D,65.0D,1.0D);
            boolean bridgeCandidate=canStartSingleGroundStepBridge(64.0D,65.0D,SAMPLE_SPACING);
            boolean plateauBridge=canBridgeSingleGroundStep(64.0D,65.0D,65.0D,
                SAMPLE_SPACING,SAMPLE_SPACING*2.0D);
            boolean bumpBridge=canBridgeSingleGroundStep(64.0D,65.0D,64.0D,
                SAMPLE_SPACING,SAMPLE_SPACING*2.0D);
            boolean repeatedStepRejected=!canBridgeSingleGroundStep(64.0D,65.0D,66.0D,
                SAMPLE_SPACING,SAMPLE_SPACING*2.0D);
            boolean cliffBridgeRejected=!canStartSingleGroundStepBridge(64.0D,65.5D,SAMPLE_SPACING);
            boolean invalidBridgeRejected=!canStartSingleGroundStepBridge(Double.NaN,65.0D,SAMPLE_SPACING)
                && !canBridgeSingleGroundStep(64.0D,65.0D,Double.NaN,SAMPLE_SPACING,SAMPLE_SPACING*2.0D);
            boolean cliffRejected=!isContinuousGroundStep(64.0D,65.5D,SAMPLE_SPACING);
            boolean invalid=!isContinuousGroundStep(64.0D,64.0D,0.0D)
                || isContinuousGroundStep(64.0D,Double.NaN,SAMPLE_SPACING)
                || isContinuousGroundStep(Double.POSITIVE_INFINITY,64.0D,SAMPLE_SPACING);
            boolean capped= Math.abs(cliffBreakTaper(1.0D)-CLIFF_BREAK_TAPER)<1.0E-9D
                && Math.abs(cliffBreakTaper(.10D)-CLIFF_BREAK_TAPER)<1.0E-9D
                && Math.abs(cliffBreakTaper(.04D)-.04D)<1.0E-9D
                && cliffBreakTaper(Double.NaN)==0.0D
                && cliffBreakTaper(Double.POSITIVE_INFINITY)==0.0D
                && cliffBreakTaper(Double.NEGATIVE_INFINITY)==0.0D
                && cliffBreakTaper(-1.0D)==0.0D;
            boolean sharpCliffCap=CLIFF_BREAK_TAPER>=.04D
                && CLIFF_BREAK_TAPER<=.08D && CLIFF_BREAK_TAPER<.10D;
            boolean preservedRiseBudget=Math.abs(SAMPLE_SPACING*MAX_GROUND_SEGMENT_GRADE
                -.5625D)<1.0E-9D;
            if(!(flat && snowStep && halfBlockRamp && oneBlockSampleRejected
                && oneBlockRampAccepted && bridgeCandidate && plateauBridge && bumpBridge
                && repeatedStepRejected && cliffBridgeRejected && invalidBridgeRejected
                && cliffRejected && invalid && capped && sharpCliffCap && preservedRiseBudget))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh continuity regression");
            continuityProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep={} maxGrade={} cliffTaper={}",
                MAX_GROUND_SEGMENT_Y_DELTA,MAX_GROUND_SEGMENT_GRADE,CLIFF_BREAK_TAPER);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_GUARD_VERIFIED maxGrade={} oneBlockRiseRejected=true snowAccepted=true cliffCapProfile=true",
                MAX_GROUND_SEGMENT_GRADE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_BUDGET_VERIFIED previousMaxRise={} currentMaxRise={} same=true",
                .5625D,SAMPLE_SPACING*MAX_GROUND_SEGMENT_GRADE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SINGLE_STEP_BRIDGE_VERIFIED maxStep=1.0 spanSamples=2 plateau=true bump=true repeatedStepRejected=true cliffGuard=true invalidRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CLIFF_TAPER_PROFILE_VERIFIED taper={} previousTaper=0.25 sharper=true invalidInputsZero=true",
                CLIFF_BREAK_TAPER);
        }

        private static boolean shouldRebuild(long tick,long previousTick,boolean anchorChanged,
                int referenceY,int previousReferenceY,boolean hasSegments) {
            // Empty meshes are expensive to rediscover when the camera is above the
            // bounded surface window or arm chunks are still absent. Back off only
            // those retries; a populated mesh keeps the responsive normal interval.
            long interval=hasSegments ? REBUILD_INTERVAL : EMPTY_MESH_REBUILD_INTERVAL;
            // Avoid signed overflow when a corrupted world clock jumps across the
            // long range. A saturated previous tick cannot have another interval.
            boolean intervalElapsed=tick>=previousTick
                && previousTick<=Long.MAX_VALUE-interval
                && tick>=previousTick+interval;
            return anchorChanged || previousTick==Long.MIN_VALUE || tick<previousTick
                || intervalElapsed
                || previousReferenceY==Integer.MIN_VALUE
                || Math.abs((long)referenceY-previousReferenceY)>=GROUND_REBUILD_VERTICAL_DELTA;
        }

        private static void verifyGroundRebuildProfile() {
            if(rebuildProfileLogged) return;
            boolean stable=!shouldRebuild(101L,100L,false,65,65,true);
            boolean oneBlockStable=!shouldRebuild(101L,100L,false,66,65,true);
            boolean twoBlockMove=shouldRebuild(101L,100L,false,67,65,true);
            boolean teleport=shouldRebuild(101L,100L,false,80,65,true);
            boolean sixTickStable=!shouldRebuild(106L,100L,false,65,65,true);
            boolean beforeInterval=!shouldRebuild(109L,100L,false,65,65,true);
            boolean interval=shouldRebuild(110L,100L,false,65,65,true);
            boolean anchor=shouldRebuild(101L,100L,true,65,65,true);
            boolean extremeForward=shouldRebuild(Long.MAX_VALUE,-1L,false,65,65,true);
            boolean nearMaxBeforeInterval=!shouldRebuild(Long.MAX_VALUE-1L,Long.MAX_VALUE-5L,false,65,65,true);
            boolean nearMaxAtInterval=shouldRebuild(Long.MAX_VALUE,Long.MAX_VALUE-10L,false,65,65,true);
            boolean negativeClockInterval=shouldRebuild(-1L,-11L,false,65,65,true);
            boolean backwardExtremeReset=shouldRebuild(Long.MIN_VALUE+5L,Long.MAX_VALUE-5L,false,65,65,true);
            boolean emptyStable=!shouldRebuild(101L,100L,false,65,65,false);
            boolean emptyBeforeInterval=!shouldRebuild(139L,100L,false,65,65,false);
            boolean emptyInterval=shouldRebuild(140L,100L,false,65,65,false);
            boolean emptyHeightMove=shouldRebuild(101L,100L,false,67,65,false);
            boolean emptyAnchor=shouldRebuild(101L,100L,true,65,65,false);
            boolean emptyExtremeForward=shouldRebuild(Long.MAX_VALUE,-1L,false,65,65,false);
            boolean emptyNearMaxBefore=!shouldRebuild(Long.MAX_VALUE-1L,Long.MAX_VALUE-5L,false,65,65,false);
            boolean emptyNearMaxAt=shouldRebuild(Long.MAX_VALUE,Long.MAX_VALUE-40L,false,65,65,false);
            boolean emptyNegativeClock=shouldRebuild(-1L,-41L,false,65,65,false);
            boolean emptyBackwardReset=shouldRebuild(Long.MIN_VALUE+5L,Long.MAX_VALUE-5L,false,65,65,false);
            boolean firstTimingSample=shouldMeasureGroundMeshBuild(0);
            boolean lastTimingSample=shouldMeasureGroundMeshBuild(3);
            boolean timingBudgetExhausted=!shouldMeasureGroundMeshBuild(4)
                && !shouldMeasureGroundMeshBuild(Integer.MAX_VALUE);
            boolean invalidTimingCounterRejected=!shouldMeasureGroundMeshBuild(-1);
            if(!(firstTimingSample && lastTimingSample && timingBudgetExhausted
                && invalidTimingCounterRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh timing budget regression");
            if(!(stable && oneBlockStable && twoBlockMove && teleport && sixTickStable
                && beforeInterval && interval && anchor && extremeForward && nearMaxBeforeInterval
                && nearMaxAtInterval && negativeClockInterval && backwardExtremeReset
                && emptyStable && emptyBeforeInterval && emptyInterval && emptyHeightMove && emptyAnchor
                && emptyExtremeForward && emptyNearMaxBefore && emptyNearMaxAt
                && emptyNegativeClock && emptyBackwardReset))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh rebuild profile regression");
            rebuildProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta={} interval={} boundaryVerified={}",
                GROUND_REBUILD_VERTICAL_DELTA,REBUILD_INTERVAL,sixTickStable && beforeInterval && interval);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_REBUILD_LONG_OVERFLOW_GUARD_VERIFIED forwardExtreme={} nearMaxBefore={} nearMaxAt={} negativeClock={} backwardReset={}",
                extremeForward,nearMaxBeforeInterval,nearMaxAtInterval,negativeClockInterval,backwardExtremeReset);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_EMPTY_REBUILD_BACKOFF_VERIFIED populatedInterval={} emptyInterval={} ratio={} immediateInvalidation=true",
                REBUILD_INTERVAL,EMPTY_MESH_REBUILD_INTERVAL,EMPTY_MESH_REBUILD_INTERVAL/REBUILD_INTERVAL);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_TIMING_BUDGET_VERIFIED maxSamples=4 first=true last=true afterBudget=false invalidCounter=false");
        }

        private static boolean isSafeCameraPosition(Vec3 camera) {
            return camera!=null
                && Double.isFinite(camera.x) && Math.abs(camera.x)<=30_000_000.0D
                && Double.isFinite(camera.y) && Math.abs(camera.y)<=30_000_000.0D
                && Double.isFinite(camera.z) && Math.abs(camera.z)<=30_000_000.0D;
        }

        private static long horizontalDistanceSquared(int blockX,int blockZ,int worldAnchorX,int worldAnchorZ) {
            long dx=(long)blockX-worldAnchorX;
            long dz=(long)blockZ-worldAnchorZ;
            return dx*dx+dz*dz;
        }

        // The ground mesh is useful only inside the same 64-block 3D envelope
        // used by its visual culling. Reject non-finite deltas instead of letting
        // NaN bypass the comparison and keep an invisible mesh pinned in memory.
        private static boolean isWithinGroundRenderRange(long horizontalDistanceSquared,double verticalDelta) {
            return isWithinGroundRange(horizontalDistanceSquared,verticalDelta,GROUND_RENDER_RADIUS);
        }

        // Rebuild only after returning inside the fade envelope. The two-block deadband
        // avoids repeatedly allocating and resampling the mesh when the camera bobs at 64.
        private static boolean isWithinGroundRenderResumeRange(long horizontalDistanceSquared,double verticalDelta) {
            return isWithinGroundRange(horizontalDistanceSquared,verticalDelta,GROUND_RENDER_RESUME_RADIUS);
        }

        private static boolean isWithinGroundRange(
                long horizontalDistanceSquared,double verticalDelta,int radius) {
            long radiusSquared=(long)radius*radius;
            return horizontalDistanceSquared>=0L && horizontalDistanceSquared<=radiusSquared
                && Double.isFinite(verticalDelta)
                && horizontalDistanceSquared+verticalDelta*verticalDelta<=(double)radiusSquared;
        }

        // If an unchanged cached mesh has left the 3D camera envelope, release it
        // before the periodic rebuild check. Otherwise a due rebuild can resample
        // terrain only to discard the new mesh a few lines later.
        private static boolean shouldCullCachedGroundMeshBeforeRebuild(boolean anchorChanged,boolean hasSegments,
                long horizontalDistanceSquared,double verticalDelta) {
            return !anchorChanged && hasSegments
                && !isWithinGroundRenderRange(horizontalDistanceSquared,verticalDelta);
        }

        // Fade the cosmetic ground mesh over the last 16 blocks of its existing
        // 64-block 3D render envelope. Squared distance avoids a per-frame sqrt.
        private static double groundDistanceFade(double distanceSquared) {
            if(!Double.isFinite(distanceSquared) || distanceSquared<0.0D) return 0.0D;
            double startSquared=48.0D*48.0D;
            double endSquared=64.0D*64.0D;
            if(distanceSquared<=startSquared) return 1.0D;
            if(distanceSquared>=endSquared) return 0.0D;
            double t=(distanceSquared-startSquared)/(endSquared-startSquared);
            // Keep alpha velocity and acceleration continuous at both render-envelope boundaries.
            return 1.0D-smootherstep01(t);
        }

        // Evaluate the 3D fade at each endpoint so a short quad can taper across the
        // 64-block boundary instead of disappearing when only its midpoint crosses it.
        private static double groundPointDistanceFade(GroundPoint point,Vec3 camera) {
            if(point==null || camera==null) return 0.0D;
            double dx=point.x()-camera.x;
            double dy=point.y()-camera.y;
            double dz=point.z()-camera.z;
            return groundDistanceFade(dx*dx+dy*dy+dz*dz);
        }

        // Adjacent mesh segments share the same GroundPoint instance at their join.
        // Reuse the previous segment's B fade instead of recalculating the same 3D distance.
        private static boolean canReuseGroundPointFade(
                GroundPoint point,GroundPoint previousPoint,double previousFade) {
            return point!=null && point==previousPoint && Double.isFinite(previousFade)
                && previousFade>=0.0D && previousFade<=1.0D;
        }

        private static void verifyGroundEndpointFadeReuseProfile() {
            if(endpointFadeReuseProfileLogged) return;
            Vec3 camera=new Vec3(0.0D,64.0D,0.0D);
            GroundPoint start=new GroundPoint(54.0D,64.0D,0.0D);
            GroundPoint shared=new GroundPoint(56.0D,64.0D,0.0D);
            GroundPoint end=new GroundPoint(58.0D,64.0D,0.0D);
            GroundPoint equalButDistinct=new GroundPoint(56.0D,64.0D,0.0D);
            GroundSegment first=new GroundSegment(start,shared,0.0D,1.0D,GROUND_SAMPLES[0],
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment second=new GroundSegment(shared,end,0.0D,1.0D,GROUND_SAMPLES[1],
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            double previousFade=groundPointDistanceFade(first.b(),camera);
            boolean sharedIdentity=first.b()==second.a()
                && canReuseGroundPointFade(second.a(),first.b(),previousFade);
            boolean distinctFallback=!canReuseGroundPointFade(equalButDistinct,first.b(),previousFade);
            boolean invalidFadeRejected=!canReuseGroundPointFade(shared,first.b(),Double.NaN)
                && !canReuseGroundPointFade(shared,first.b(),-0.01D)
                && !canReuseGroundPointFade(shared,first.b(),1.01D);
            if(!sharedIdentity || !distinctFallback || !invalidFadeRejected)
                throw new IllegalStateException("BLACK FREQUENCY adjacent ground endpoint fade reuse regression");
            endpointFadeReuseProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_FADE_REUSE_PROFILE_VERIFIED sharedPointIdentity=true distinctPointFallback=true invalidFadeRejected=true");
        }

        // A segment remains drawable while either endpoint overlaps the fade envelope.
        // Its two vertex alphas are interpolated independently by the GPU.
        private static double groundSegmentDistanceFade(GroundSegment segment,Vec3 camera) {
            if(segment==null || camera==null) return 0.0D;
            return Math.max(groundPointDistanceFade(segment.a(),camera),
                groundPointDistanceFade(segment.b(),camera));
        }

        // Core-only gaps still draw their violet envelope and bevels, so they remain visible segments.
        // This helper is also used by the deterministic empty-buffer profile.
        private static boolean hasVisibleGroundSegment(List<GroundSegment> segments,Vec3 camera) {
            if(segments==null || camera==null) return false;
            for(GroundSegment segment:segments) {
                if(segment!=null && groundSegmentDistanceFade(segment,camera)>0.0D) return true;
            }
            return false;
        }

        // A vertically distant camera should release the sampled ClientLevel and
        // mesh just like a camera that leaves the horizontal radius.
        private static boolean clearGeometryCacheIfOutsideGroundRenderRange(
                long horizontalDistanceSquared,double verticalDelta,int worldAnchorX,int worldAnchorZ,
                int anchorSurfaceY,boolean trackLiveCameraCull) {
            if(isWithinGroundRenderRange(horizontalDistanceSquared,verticalDelta)) return false;
            boolean hadCachedMesh=cachedLevel!=null && !cachedSegments.isEmpty();
            clearGeometryCacheIfPresent();
            if(!trackLiveCameraCull) return true;
            verticallyCulled=true;
            verticallyCulledAnchorX=worldAnchorX;
            verticallyCulledAnchorZ=worldAnchorZ;
            verticallyCulledSurfaceY=anchorSurfaceY;
            if(hadCachedMesh) {
                verticalCullResumePending=true;
                verticalCullResumeAnchorChanged=false;
                if(VERIFY_GROUND_MESH_PROFILES) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_ENTERED cameraY={} surfaceY={} verticalDelta={} threshold=64 anchorX={} anchorZ={}",
                        anchorSurfaceY+verticalDelta,anchorSurfaceY,verticalDelta,worldAnchorX,worldAnchorZ);
                }
            }
            return true;
        }

        // This is emitted only from a real render pass with a retained mesh. Profile fixtures
        // must not masquerade as player-camera movement in the integration log.
        private static void logVerticalCameraRangeSample(
                Vec3 camera,net.minecraft.client.multiplayer.ClientLevel level,
                int worldAnchorX,int worldAnchorZ,long horizontalDistanceSquared) {
            if(!VERIFY_GROUND_MESH_PROFILES || cachedLevel!=level || cachedSegments.isEmpty()) {
                verticalCameraRangeSampleLogged=false;
                return;
            }
            double verticalDelta=camera.y-cachedAnchorSurfaceY;
            if(Math.abs(verticalDelta)<=64.0D) {
                verticalCameraRangeSampleLogged=false;
                return;
            }
            if(verticalCameraRangeSampleLogged) return;
            boolean anchorChanged=worldAnchorX!=cachedAnchorX || worldAnchorZ!=cachedAnchorZ;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_VERTICAL_RANGE_SAMPLE cameraY={} surfaceY={} verticalDelta={} horizontalDistanceSquared={} anchorChanged={} cachedSegments={} levelMatches=true",
                camera.y,cachedAnchorSurfaceY,verticalDelta,horizontalDistanceSquared,anchorChanged,cachedSegments.size());
            verticalCameraRangeSampleLogged=true;
        }

        // Once an out-of-range mesh is released, do not rebuild it every frame.
        // Resume only when the camera returns to the 3D envelope or the anchor changes.
        private static boolean isVerticalCullAnchorChanged(
                int worldAnchorX,int worldAnchorZ,int culledAnchorX,int culledAnchorZ) {
            return worldAnchorX!=culledAnchorX || worldAnchorZ!=culledAnchorZ;
        }

        private static boolean shouldSkipGroundMeshBuildWhileVerticallyCulled(
                int worldAnchorX,int worldAnchorZ,long horizontalDistanceSquared,double cameraY) {
            if(!verticallyCulled) return false;
            boolean anchorChanged=isVerticalCullAnchorChanged(
                worldAnchorX,worldAnchorZ,verticallyCulledAnchorX,verticallyCulledAnchorZ);
            if(anchorChanged
                || isWithinGroundRenderResumeRange(horizontalDistanceSquared,cameraY-verticallyCulledSurfaceY)) {
                if(verticalCullResumePending) verticalCullResumeAnchorChanged=anchorChanged;
                verticallyCulled=false;
                verticallyCulledAnchorX=Integer.MIN_VALUE;
                verticallyCulledAnchorZ=Integer.MIN_VALUE;
                return false;
            }
            return true;
        }

        private static long nextVerticalCullSkippedFrames(long previousFrames) {
            return previousFrames==Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L,previousFrames)+1L;
        }

        private static void flushVerticalCullSkipDiagnostics(String reason) {
            if(verticallyCulledSkippedFrames<=0L) return;
            long skippedFrames=verticallyCulledSkippedFrames;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_VERTICAL_CULL_SKIP_SUMMARY frames={} worldBorderProbesSkipped={} loadedChunkProbesSkipped={} rebuildChecksSkipped={} endedBy={}",
                skippedFrames,skippedFrames,skippedFrames,skippedFrames,reason);
            verticallyCulledSkippedFrames=0L;
        }

        private static void verifyGroundRenderRangeProfile() {
            if(rangeProfileLogged) return;
            boolean center=isWithinGroundRenderRange(0L,0.0D);
            boolean horizontalBoundary=isWithinGroundRenderRange(64L*64L,0.0D);
            boolean verticalBoundary=isWithinGroundRenderRange(0L,64.0D);
            boolean verticalOutside=!isWithinGroundRenderRange(0L,64.01D);
            boolean resumeBoundary=isWithinGroundRenderResumeRange(0L,62.0D);
            boolean resumeOutside=!isWithinGroundRenderResumeRange(0L,62.01D);
            boolean horizontalOutside=!isWithinGroundRenderRange(64L*64L+1L,0.0D);
            boolean nanRejected=!isWithinGroundRenderRange(0L,Double.NaN);
            boolean infinityRejected=!isWithinGroundRenderRange(0L,Double.POSITIVE_INFINITY);
            boolean sameAnchorOutsideCull=shouldCullCachedGroundMeshBeforeRebuild(false,true,0L,64.01D);
            boolean sameAnchorInsideRetained=!shouldCullCachedGroundMeshBeforeRebuild(false,true,0L,64.0D);
            boolean anchorChangeRetained=!shouldCullCachedGroundMeshBeforeRebuild(true,true,0L,64.01D);
            boolean emptyMeshRetained=!shouldCullCachedGroundMeshBeforeRebuild(false,false,0L,64.01D);
            boolean invalidRangeCulled=shouldCullCachedGroundMeshBeforeRebuild(false,true,0L,Double.NaN);
            boolean sameAnchorResume=!isVerticalCullAnchorChanged(1,2,1,2);
            boolean changedAnchorResume=isVerticalCullAnchorChanged(2,2,1,2);
            if(!(sameAnchorOutsideCull && sameAnchorInsideRetained && anchorChangeRetained
                && emptyMeshRetained && invalidRangeCulled && sameAnchorResume && changedAnchorResume))
                throw new IllegalStateException("BLACK FREQUENCY pre-build vertical ground mesh cull regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_PREBUILD_VERTICAL_CULL_VERIFIED sameAnchorOutside=true insideRetained=true anchorChangeRetained=true emptyMeshRetained=true invalidRangeCulled=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_RESUME_ANCHOR_PROFILE_VERIFIED sameAnchor=false changedAnchor=true");
            boolean finiteCameraAccepted=isSafeCameraPosition(new Vec3(0.5D,65.0D,0.5D));
            boolean nanCameraRejected=!isSafeCameraPosition(new Vec3(Double.NaN,65.0D,0.5D));
            boolean infiniteCameraRejected=!isSafeCameraPosition(new Vec3(0.5D,65.0D,Double.POSITIVE_INFINITY));
            boolean outOfWorldCameraRejected=!isSafeCameraPosition(new Vec3(30_000_001.0D,65.0D,0.5D));
            int playerBlockX=0;
            int cameraOutsideBlockX=65;
            boolean cameraOutsideWhilePlayerNear=
                horizontalDistanceSquared(cameraOutsideBlockX,0,0,0)>64L*64L
                && horizontalDistanceSquared(playerBlockX,0,0,0)<=64L*64L;
            boolean cameraInsideWhilePlayerOutside=
                horizontalDistanceSquared(0,0,0,0)<=64L*64L
                && horizontalDistanceSquared(65,0,0,0)>64L*64L;
            if(GROUND_RENDER_RESUME_RADIUS!=62 || GROUND_RENDER_RESUME_RADIUS>=GROUND_RENDER_RADIUS
                || !(center && horizontalBoundary && verticalBoundary && verticalOutside
                && resumeBoundary && resumeOutside && horizontalOutside && nanRejected && infinityRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh 3D range regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_HYSTERESIS_PROFILE_VERIFIED cullRadius={} resumeRadius={} deadband={} boundary=true outsideRejected=true",
                GROUND_RENDER_RADIUS,GROUND_RENDER_RESUME_RADIUS,
                GROUND_RENDER_RADIUS-GROUND_RENDER_RESUME_RADIUS);
            if(!(finiteCameraAccepted && nanCameraRejected && infiniteCameraRejected
                && outOfWorldCameraRejected && cameraOutsideWhilePlayerNear && cameraInsideWhilePlayerOutside))
                throw new IllegalStateException("BLACK FREQUENCY camera-based ground mesh culling regression");
            double fadeStartSquared=48.0D*48.0D;
            double fadeEndSquared=64.0D*64.0D;
            double fadeSpanSquared=fadeEndSquared-fadeStartSquared;
            double fadeStart=groundDistanceFade(fadeStartSquared);
            double fadeQuarter=groundDistanceFade(fadeStartSquared+fadeSpanSquared*.25D);
            double fadeMid=groundDistanceFade((fadeStartSquared+fadeEndSquared)*.5D);
            double fadeThreeQuarter=groundDistanceFade(fadeStartSquared+fadeSpanSquared*.75D);
            double fadeEnd=groundDistanceFade(fadeEndSquared);
            double fadeNear=groundDistanceFade(40.0D*40.0D);
            double fadeFar=groundDistanceFade(65.0D*65.0D);
            double fadeNaN=groundDistanceFade(Double.NaN);
            double fadeNegative=groundDistanceFade(-1.0D);
            double fadeInfinity=groundDistanceFade(Double.POSITIVE_INFINITY);
            double fadeAt56=groundDistanceFade(56.0D*56.0D);
            double fadeAt60=groundDistanceFade(60.0D*60.0D);
            boolean fadeC2Endpoints=smootherstepDerivative01(0.0D)==0.0D
                && smootherstepDerivative01(1.0D)==0.0D
                && smootherstepSecondDerivative01(0.0D)==0.0D
                && smootherstepSecondDerivative01(1.0D)==0.0D;
            boolean fadeMonotonic=true;
            double previousFade=1.0D;
            for(int sample=1;sample<=1000;sample++) {
                double t=sample/1000.0D;
                double currentFade=groundDistanceFade(fadeStartSquared+fadeSpanSquared*t);
                fadeMonotonic &= Double.isFinite(currentFade) && currentFade>=0.0D
                    && currentFade<=1.0D && currentFade<=previousFade+1.0E-12D;
                previousFade=currentFade;
            }
            if(fadeStart!=1.0D || Math.abs(fadeQuarter-.896484375D)>1.0E-12D
                || Math.abs(fadeMid-.5D)>1.0E-12D
                || Math.abs(fadeThreeQuarter-.103515625D)>1.0E-12D
                || fadeEnd!=0.0D || fadeNear!=1.0D || fadeFar!=0.0D
                || fadeNaN!=0.0D || fadeNegative!=0.0D || fadeInfinity!=0.0D
                || !(fadeAt56>fadeAt60) || !fadeC2Endpoints || !fadeMonotonic)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh distance fade regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_DISTANCE_FADE_VERIFIED start=48 end=64 curve=quintic quarter={} midpoint={} threeQuarter={} c2Endpoints=true monotonic=true invalidRejected=true",
                fadeQuarter,fadeMid,fadeThreeQuarter);
            GroundSample fadeSample=null;
            for(GroundSample candidate:GROUND_SAMPLES) {
                if(!candidate.coreSegmentGap()) { fadeSample=candidate; break; }
            }
            if(fadeSample==null)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh has no non-gap fade fixture");
            GroundSegment nearSegment=new GroundSegment(new GroundPoint(40.0D,64.0D,0.0D),
                new GroundPoint(42.0D,64.0D,0.0D),1.0D,0.0D,fadeSample,1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment midSegment=new GroundSegment(new GroundPoint(55.0D,64.0D,0.0D),
                new GroundPoint(57.0D,64.0D,0.0D),1.0D,0.0D,fadeSample,1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment crossingSegment=new GroundSegment(new GroundPoint(63.0D,64.0D,0.0D),
                new GroundPoint(65.0D,64.0D,0.0D),1.0D,0.0D,fadeSample,1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            GroundSegment farSegment=new GroundSegment(new GroundPoint(65.0D,64.0D,0.0D),
                new GroundPoint(67.0D,64.0D,0.0D),1.0D,0.0D,fadeSample,1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            Vec3 fadeCamera=new Vec3(0.0D,64.0D,0.0D);
            double nearSegmentFade=groundSegmentDistanceFade(nearSegment,fadeCamera);
            double midSegmentFade=groundSegmentDistanceFade(midSegment,fadeCamera);
            double crossingSegmentFade=groundSegmentDistanceFade(crossingSegment,fadeCamera);
            double crossingEndFade=groundPointDistanceFade(crossingSegment.b(),fadeCamera);
            double farSegmentFade=groundSegmentDistanceFade(farSegment,fadeCamera);
            double invalidSegmentFade=groundSegmentDistanceFade(nearSegment,
                new Vec3(Double.NaN,64.0D,0.0D));
            if(nearSegmentFade!=1.0D || !(midSegmentFade>0.0D && midSegmentFade<1.0D)
                || !(crossingSegmentFade>0.0D) || crossingEndFade!=0.0D
                || farSegmentFade!=0.0D || invalidSegmentFade!=0.0D
                || !(nearSegmentFade>midSegmentFade && midSegmentFade>crossingSegmentFade
                    && crossingSegmentFade>farSegmentFade))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh endpoint distance fade regression");
            boolean nearMeshVisible=hasVisibleGroundSegment(List.of(nearSegment),fadeCamera);
            boolean crossingMeshVisible=hasVisibleGroundSegment(List.of(crossingSegment),fadeCamera);
            boolean farMeshHidden=!hasVisibleGroundSegment(List.of(farSegment),fadeCamera);
            boolean invalidCameraHidden=!hasVisibleGroundSegment(List.of(nearSegment),
                new Vec3(Double.NaN,64.0D,0.0D));
            if(!(nearMeshVisible && crossingMeshVisible && farMeshHidden && invalidCameraHidden))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh empty-buffer guard regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_EMPTY_BUFFER_GUARD_VERIFIED nearVisible=true crossingVisible=true farHidden=true invalidCameraHidden=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_DISTANCE_FADE_VERIFIED metric=per-endpoint start=48 end=64 near={} mid={} crossing={} crossingEnd={} far={} invalidRejected=true monotonic=true",
                nearSegmentFade,midSegmentFade,crossingSegmentFade,crossingEndFade,farSegmentFade);
            verticallyCulled=true;
            verticallyCulledAnchorX=1;
            verticallyCulledAnchorZ=2;
            verticallyCulledSurfaceY=64;
            boolean stableOutOfRangeSkip=shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,128.01D);
            boolean repeatedOutOfRangeSkip=shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,128.01D);
            boolean hysteresisBandStillCulled=shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,127.0D);
            boolean returnToRangeRebuild=!shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,126.0D);
            verticallyCulled=true;
            verticallyCulledAnchorX=1;
            verticallyCulledAnchorZ=2;
            verticallyCulledSurfaceY=64;
            boolean anchorChangeRebuild=!shouldSkipGroundMeshBuildWhileVerticallyCulled(2,2,0L,128.01D);
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            if(!(stableOutOfRangeSkip && repeatedOutOfRangeSkip && hysteresisBandStillCulled
                && returnToRangeRebuild && anchorChangeRebuild))
                throw new IllegalStateException("BLACK FREQUENCY vertical ground mesh cull latch regression");
            long firstSkippedFrame=nextVerticalCullSkippedFrames(0L);
            long secondSkippedFrame=nextVerticalCullSkippedFrames(firstSkippedFrame);
            boolean skipCounterAccumulates=firstSkippedFrame==1L && secondSkippedFrame==2L;
            boolean skipCounterSaturates=nextVerticalCullSkippedFrames(Long.MAX_VALUE)==Long.MAX_VALUE;
            boolean negativeCounterRecovers=nextVerticalCullSkippedFrames(-1L)==1L;
            if(!(skipCounterAccumulates && skipCounterSaturates && negativeCounterRecovers))
                throw new IllegalStateException("BLACK FREQUENCY vertical ground mesh cull skip counter regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_SKIP_COUNTER_VERIFIED increments=true saturates=true negativeRecovered=true");
            rangeProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_3D_RANGE_PROFILE_VERIFIED radius=64 horizontalBoundary=true verticalBoundary=true invalidRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_LATCH_VERIFIED stable=true hysteresisBandSkip=true returnRebuildAt62=true anchorChangeRebuild=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_CULL_PROFILE_VERIFIED radius=64 cameraPosition=true playerCameraIndependent=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_CAMERA_POSITION_GUARD_VERIFIED nonFiniteRejected=true worldLimitRejected=true");
        }

        private static double boundedRenderPartialTick(double partialTick) {
            return Double.isFinite(partialTick)
                ? Math.max(0.0D,Math.min(1.0D,partialTick))
                : 0.0D;
        }

        private static double boundedCorePulse(double wave) {
            double boundedWave=Double.isFinite(wave) ? Math.max(0.0D,Math.min(1.0D,wave)) : 0.0D;
            return GROUND_CORE_PULSE_BASE+GROUND_CORE_PULSE_RANGE*boundedWave;
        }

        private static void verifyGroundAnimationProfile() {
            if(animationProfileLogged) return;
            double glowPulseMinimum=.78D-.22D;
            double glowPulseMaximum=.78D+.22D;
            double glowAlphaMinimum=glowPulseMinimum*GROUND_GLOW_ALPHA;
            double glowAlphaMaximum=glowPulseMaximum*GROUND_GLOW_ALPHA;
            if(!Float.isFinite(GROUND_GLOW_ALPHA) || GROUND_GLOW_ALPHA<.70F || GROUND_GLOW_ALPHA>.74F
                || Math.abs(glowAlphaMinimum-.4032D)>GROUND_GLOW_ALPHA_TOLERANCE
                || Math.abs(glowAlphaMaximum-.72D)>GROUND_GLOW_ALPHA_TOLERANCE)
                throw new IllegalStateException("BLACK FREQUENCY ground glow alpha profile regression");
            double minimum=boundedCorePulse(0.0D);
            double midpoint=boundedCorePulse(.5D);
            double maximum=boundedCorePulse(1.0D);
            double invalid=boundedCorePulse(Double.NaN);
            double tickStart=boundedRenderPartialTick(0.0D);
            double tickEnd=boundedRenderPartialTick(1.0D);
            double tickBelow=boundedRenderPartialTick(-0.5D);
            double tickAbove=boundedRenderPartialTick(1.5D);
            double tickNaN=boundedRenderPartialTick(Double.NaN);
            double tickPositiveInfinity=boundedRenderPartialTick(Double.POSITIVE_INFINITY);
            double tickNegativeInfinity=boundedRenderPartialTick(Double.NEGATIVE_INFINITY);
            if(Math.abs(minimum-.10D)>1.0E-9D
                || Math.abs(midpoint-.21D)>1.0E-9D
                || Math.abs(maximum-.32D)>1.0E-9D
                || Math.abs(invalid-minimum)>1.0E-9D
                || minimum<0.0D || maximum>1.0D
                || tickStart!=0.0D || tickEnd!=1.0D
                || tickBelow!=0.0D || tickAbove!=1.0D
                || tickNaN!=0.0D || tickPositiveInfinity!=0.0D
                || tickNegativeInfinity!=0.0D)
                throw new IllegalStateException("BLACK FREQUENCY ground core pulse regression");
            animationProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_GLOW_ALPHA_VERIFIED multiplier={} fullStrengthPulseMin={} fullStrengthPulseMax={} minAlpha={} maxAlpha={}",
                GROUND_GLOW_ALPHA,glowPulseMinimum,glowPulseMaximum,glowAlphaMinimum,glowAlphaMaximum);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CORE_PULSE_VERIFIED min={} midpoint={} max={} widthScale={}",
                minimum,midpoint,maximum,.16D);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_PARTIAL_TICK_GUARD_VERIFIED finiteClamped=true nonFiniteFallback=zero");
        }

        private static void verifyGroundSupportProfile(net.minecraft.world.level.Level level) {
            if(supportFilterLogged) return;
            boolean naturalGrass=isGroundSupport(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            boolean naturalStone=isGroundSupport(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            BlockState bottomSlab=net.minecraft.world.level.block.Blocks.COBBLESTONE_SLAB.defaultBlockState();
            BlockState topSlab=bottomSlab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.TOP);
            BlockState doubleSlab=bottomSlab.setValue(net.minecraft.world.level.block.SlabBlock.TYPE,
                net.minecraft.world.level.block.state.properties.SlabType.DOUBLE);
            double bottomSlabHeight=groundSupportHeight(bottomSlab,level,BlockPos.ZERO);
            double topSlabHeight=groundSupportHeight(topSlab,level,BlockPos.ZERO);
            double doubleSlabHeight=groundSupportHeight(doubleSlab,level,BlockPos.ZERO);
            double bottomSlabSnowOffset=groundSurfaceOffset(bottomSlabHeight,.5D);
            double topSlabSnowOffset=groundSurfaceOffset(topSlabHeight,.5D);
            boolean supportsSlabs=isGroundSupport(bottomSlab) && isGroundSupport(topSlab)
                && isGroundSupport(doubleSlab)
                && bottomSlab.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    ==net.minecraft.world.level.block.state.properties.SlabType.BOTTOM
                && topSlab.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    ==net.minecraft.world.level.block.state.properties.SlabType.TOP
                && doubleSlab.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                    ==net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                && Math.abs(bottomSlabHeight-.5D)<1.0E-9D
                && Math.abs(topSlabHeight-1.0D)<1.0E-9D
                && Math.abs(doubleSlabHeight-1.0D)<1.0E-9D
                && Math.abs(bottomSlabSnowOffset-1.0D)<1.0E-9D
                && Math.abs(topSlabSnowOffset-1.5D)<1.0E-9D;
            boolean rejectsStructures=
                !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.COBBLESTONE_WALL.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_FENCE.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_FENCE_GATE.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_TRAPDOOR.defaultBlockState());
            boolean rejectsBlockEntities=
                !isGroundSupport(net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.BEACON.defaultBlockState())
                && !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState())
                && !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState())
                && !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.BEACON.defaultBlockState())
                && isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            boolean rejectsHazardousSurfaces=
                !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.POWDER_SNOW.defaultBlockState())
                && !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState())
                && !isGroundSurfaceAvailable(net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
            boolean rejectsHazardousHeadroom=
                !isGroundHeadClear(net.minecraft.world.level.block.Blocks.POWDER_SNOW.defaultBlockState())
                && isGroundHeadClear(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                && !isGroundHeadClear(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState())
                && !isGroundHeadClear(net.minecraft.world.level.block.Blocks.LAVA.defaultBlockState());
            var fullShape=net.minecraft.world.phys.shapes.Shapes.block();
            var partialShape=net.minecraft.world.phys.shapes.Shapes.box(0.0D,0.0D,0.0D,1.0D,.5D,1.0D);
            var overheightShape=net.minecraft.world.phys.shapes.Shapes.box(0.0D,0.0D,0.0D,1.0D,1.25D,1.0D);
            var epsilonOverheightShape=net.minecraft.world.phys.shapes.Shapes.box(
                0.0D,0.0D,0.0D,1.0D,1.0D+GROUND_SUPPORT_SHAPE_EPSILON*.5D,1.0D);
            double fullBlockHeight=groundSupportHeight(fullShape,net.minecraft.world.phys.shapes.Shapes.empty());
            double partialBlockHeight=groundSupportHeight(partialShape,net.minecraft.world.phys.shapes.Shapes.empty());
            boolean overheightSupportRejected=Double.isNaN(groundSupportHeight(
                overheightShape,net.minecraft.world.phys.shapes.Shapes.empty()));
            boolean epsilonOverheightAccepted=Double.isFinite(groundSupportHeight(
                epsilonOverheightShape,net.minecraft.world.phys.shapes.Shapes.empty()));
            boolean outlineFallback=Math.abs(groundSupportHeight(net.minecraft.world.phys.shapes.Shapes.empty(),
                fullShape)-1.0D)<1.0E-9D;
            boolean emptyShapeRejected=Double.isNaN(groundSupportHeight(
                net.minecraft.world.phys.shapes.Shapes.empty(),net.minecraft.world.phys.shapes.Shapes.empty()));
            var cauldronState=net.minecraft.world.level.block.Blocks.CAULDRON.defaultBlockState();
            var cauldronShape=cauldronState.getCollisionShape(level,BlockPos.ZERO);
            double cauldronSupportHeight=groundSupportHeight(cauldronState,level,BlockPos.ZERO);
            boolean cauldronTopRejected=Double.isNaN(cauldronSupportHeight);
            boolean reusableProbeSequence=hasFullFootprintTop(fullShape,1.0D)
                && !hasFullFootprintTop(cauldronShape,1.0D)
                && hasFullFootprintTop(partialShape,.5D);
            boolean malformedProbeBoundsRejected=verifyMalformedSupportProbeBounds();
            boolean horizontalSupportBoundsRejected=verifyHorizontalSupportProbeBounds();
            boolean verticalSupportBoundsRejected=verifyVerticalSupportProbeBounds();
            boolean validSupportHeights=Math.abs(fullBlockHeight-1.0D)<1.0E-9D
                && Math.abs(partialBlockHeight-.5D)<1.0E-9D && outlineFallback && emptyShapeRejected
                && overheightSupportRejected && epsilonOverheightAccepted
                && reusableProbeSequence && !hasFullFootprintTop(
                    net.minecraft.world.phys.shapes.Shapes.empty(),0.0D);
            double minimumSnowOffset=Double.POSITIVE_INFINITY;
            double maximumSnowOffset=Double.NEGATIVE_INFINITY;
            double previousSnowOffset=Double.NEGATIVE_INFINITY;
            double fullBlockSupportHeight=1.0D;
            double partialSupportSnowOffset=groundSurfaceOffset(.5D,.5D);
            boolean snowSurfaceOffsets=true;
            boolean snowSurfaceAccepted=true;
            for(int layers=1;layers<=8;layers++) {
                var snow=net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SnowLayerBlock.LAYERS,layers);
                snowSurfaceAccepted &= isGroundSurfaceAvailable(snow);
                double shapeHeight=snow.getShape(level,BlockPos.ZERO).max(Direction.Axis.Y);
                double expectedHeight=layers/8.0D;
                double offset=groundSurfaceOffset(fullBlockSupportHeight,shapeHeight);
                snowSurfaceOffsets &= Double.isFinite(shapeHeight)
                    && Math.abs(shapeHeight-expectedHeight)<1.0E-9D
                    && Math.abs(offset-(fullBlockSupportHeight+expectedHeight))<1.0E-9D
                    && offset>previousSnowOffset;
                minimumSnowOffset=Math.min(minimumSnowOffset,offset);
                maximumSnowOffset=Math.max(maximumSnowOffset,offset);
                previousSnowOffset=offset;
            }
            double invalidSnowFallback=groundSurfaceOffset(.5D,Double.NaN);
            double overheightSnowClamp=groundSurfaceOffset(.5D,1.5D);
            boolean fullSnowBlockRejected=!isGroundSurfaceAvailable(
                net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState());
            snowSurfaceOffsets &= Math.abs(minimumSnowOffset-1.125D)<1.0E-9D
                && Math.abs(maximumSnowOffset-2.0D)<1.0E-9D
                && Math.abs(partialSupportSnowOffset-1.0D)<1.0E-9D
                && Math.abs(invalidSnowFallback-.5D)<1.0E-9D
                && Math.abs(overheightSnowClamp-1.5D)<1.0E-9D;
            if(!(naturalGrass && naturalStone && supportsSlabs && rejectsStructures && rejectsBlockEntities
                && rejectsHazardousSurfaces && rejectsHazardousHeadroom && validSupportHeights
                && cauldronTopRejected && malformedProbeBoundsRejected && horizontalSupportBoundsRejected
                && verticalSupportBoundsRejected && snowSurfaceOffsets && snowSurfaceAccepted && fullSnowBlockRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh terrain/structure/support-shape regression");
            supportFilterLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_STRUCTURAL_SUPPORT_FILTER_VERIFIED");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_BLOCK_ENTITY_FILTER_VERIFIED support=true surface=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SURFACE_HAZARD_FILTER_VERIFIED powderSnow=true water=true lava=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_HEADROOM_HAZARD_FILTER_VERIFIED powderSnow=true water=true lava=true air=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_SHAPE_GUARD_VERIFIED fullBlock={} partial={} outlineFallback={} emptyRejected={}",
                fullBlockHeight,partialBlockHeight,outlineFallback,emptyShapeRejected);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_HEIGHT_BOUND_VERIFIED maxHeight=1.0 overheightRejected={} epsilonTolerance={}",
                overheightSupportRejected,epsilonOverheightAccepted);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_UNIFORM_TOP_SUPPORT_VERIFIED fullBlock=true bottomSlab=true topSlab=true doubleSlab=true cauldronRejected={}",
                cauldronTopRejected);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_SCAN_ALLOCATION_VERIFIED strategy=forAllBoxes reusableProbe=true materializedAabbs=false staleResultCleared={}",
                reusableProbeSequence);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_PROBE_BOUNDS_VERIFIED nonFiniteRejected=true invertedBoundsRejected=true invertedYRejected=true degenerateYRejected=true validAccepted=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_HORIZONTAL_BOUNDS_VERIFIED overhangRejected=true epsilonTolerance=true voxelShapeMultiBoxRejected=true orderIndependent=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SUPPORT_VERTICAL_BOUNDS_VERIFIED underhangRejected=true epsilonTolerance=true voxelShapeMultiBoxRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SLAB_SUPPORT_VERIFIED bottom={} top={} double={} bottomSnow={} topSnow={} stairsRejected=true shapeDerived=true",
                bottomSlabHeight,topSlabHeight,doubleSlabHeight,bottomSlabSnowOffset,topSlabSnowOffset);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SNOW_LAYER_SURFACE_HEIGHT_VERIFIED layers=1..8 minOffset={} maxOffset={} invalidFallback={} overheightClamp={} monotonic=true shapeDerived=true surfaceAccepted={} fullSnowBlockRejected={}",
                minimumSnowOffset,maximumSnowOffset,invalidSnowFallback,overheightSnowClamp,
                snowSurfaceAccepted,fullSnowBlockRejected);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_PARTIAL_SUPPORT_SNOW_OFFSET_VERIFIED supportHeight=0.5 snowHeight=0.5 offset={} separateFromLayerSweep=true",
                partialSupportSnowOffset);
        }

        // GroundRenderer runs on the render thread. Reuse one callback instead of
        // materializing a List<AABB> for every candidate support block during a mesh rebuild.
        private static final double GROUND_SUPPORT_SHAPE_EPSILON=1.0E-7D;
        private static final class FullFootprintTopProbe
                implements net.minecraft.world.phys.shapes.Shapes.DoubleLineConsumer {
            private double expectedHeight;
            private boolean found;
            private boolean invalidBounds;

            private void reset(double height) {
                expectedHeight=height;
                found=false;
                invalidBounds=false;
            }

            private boolean found() {
                // Validate every streamed box: a later overhang must invalidate an
                // earlier full-footprint box, regardless of iteration order.
                return found && !invalidBounds;
            }

            @Override
            public void consume(double minX,double minY,double minZ,
                    double maxX,double maxY,double maxZ) {
                // Modded collision shapes are untrusted input. Do not stop after finding
                // a valid top: a separate malformed or out-of-block box still invalidates
                // the entire support shape.
                if(!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                    || minX>maxX || minY>=maxY || minZ>maxZ
                    || minY < -GROUND_SUPPORT_SHAPE_EPSILON
                    || minX < -GROUND_SUPPORT_SHAPE_EPSILON
                    || maxX > 1.0D+GROUND_SUPPORT_SHAPE_EPSILON
                    || minZ < -GROUND_SUPPORT_SHAPE_EPSILON
                    || maxZ > 1.0D+GROUND_SUPPORT_SHAPE_EPSILON) {
                    invalidBounds=true;
                    return;
                }
                if(Math.abs(maxY-expectedHeight)<=GROUND_SUPPORT_SHAPE_EPSILON
                    && minX<=GROUND_SUPPORT_SHAPE_EPSILON
                    && maxX>=1.0D-GROUND_SUPPORT_SHAPE_EPSILON
                    && minZ<=GROUND_SUPPORT_SHAPE_EPSILON
                    && maxZ>=1.0D-GROUND_SUPPORT_SHAPE_EPSILON) found=true;
            }
        }
        private static boolean verifyMalformedSupportProbeBounds() {
            FullFootprintTopProbe probe=new FullFootprintTopProbe();
            double[][] malformed={
                {Double.NaN,0,0,1,1,1}, {0,Double.NaN,0,1,1,1}, {0,0,Double.NaN,1,1,1},
                {0,0,0,Double.POSITIVE_INFINITY,1,1}, {0,0,0,1,Double.POSITIVE_INFINITY,1},
                {0,0,0,1,1,Double.POSITIVE_INFINITY}, {Double.NEGATIVE_INFINITY,0,0,1,1,1},
                {1,0,0,0,1,1}, {0,2,0,1,1,1}, {0,0,1,1,1,0}, {0,1,0,1,1,1}
            };
            boolean rejected=true;
            for(double[] box:malformed) {
                probe.reset(1.0D);
                probe.consume(box[0],box[1],box[2],box[3],box[4],box[5]);
                rejected &= !probe.found();
            }
            probe.reset(1.0D);
            probe.consume(0.0D,0.0D,0.0D,1.0D,1.0D,1.0D);
            return rejected && probe.found();
        }

        private static boolean verifyHorizontalSupportProbeBounds() {
            FullFootprintTopProbe probe=new FullFootprintTopProbe();
            double[][] overhangs={
                {-.25D,0,0,1,1,1}, {0,0,0,1.25D,1,1},
                {0,0,-.25D,1,1,1}, {0,0,0,1,1,1.25D}
            };
            boolean rejected=true;
            for(double[] box:overhangs) {
                probe.reset(1.0D);
                probe.consume(box[0],box[1],box[2],box[3],box[4],box[5]);
                rejected &= !probe.found();
            }

            // A valid full-block box must not hide a second malformed box. Check both
            // callback orders, then exercise the same case through Minecraft VoxelShape.
            probe.reset(1.0D);
            probe.consume(0,0,0,1,1,1);
            probe.consume(-.25D,0,0,1,1,1);
            boolean validThenOverhangRejected=!probe.found();
            probe.reset(1.0D);
            probe.consume(-.25D,0,0,1,1,1);
            probe.consume(0,0,0,1,1,1);
            boolean overhangThenValidRejected=!probe.found();

            var fullBlock=net.minecraft.world.phys.shapes.Shapes.block();
            var multiBoxOverhangs=new net.minecraft.world.phys.shapes.VoxelShape[] {
                net.minecraft.world.phys.shapes.Shapes.or(fullBlock,fullBlock.move(-1.25D,0,0)),
                net.minecraft.world.phys.shapes.Shapes.or(fullBlock,fullBlock.move(1.25D,0,0)),
                net.minecraft.world.phys.shapes.Shapes.or(fullBlock,fullBlock.move(0,0,-1.25D)),
                net.minecraft.world.phys.shapes.Shapes.or(fullBlock,fullBlock.move(0,0,1.25D))
            };
            boolean voxelShapeMultiBoxRejected=true;
            for(var shape:multiBoxOverhangs)
                voxelShapeMultiBoxRejected &= !hasFullFootprintTop(shape,1.0D);

            probe.reset(1.0D);
            probe.consume(-GROUND_SUPPORT_SHAPE_EPSILON*.5D,0,0,1,1,1);
            boolean minXTolerance=probe.found();
            probe.reset(1.0D);
            probe.consume(0,0,0,1.0D+GROUND_SUPPORT_SHAPE_EPSILON*.5D,1,1);
            boolean maxXTolerance=probe.found();
            probe.reset(1.0D);
            probe.consume(0,0,-GROUND_SUPPORT_SHAPE_EPSILON*.5D,1,1,1);
            boolean minZTolerance=probe.found();
            probe.reset(1.0D);
            probe.consume(0,0,0,1,1,1.0D+GROUND_SUPPORT_SHAPE_EPSILON*.5D);
            boolean maxZTolerance=probe.found();
            probe.reset(1.0D);
            probe.consume(0,0,0,1,1,1);
            return rejected && validThenOverhangRejected && overhangThenValidRejected
                && voxelShapeMultiBoxRejected && minXTolerance && maxXTolerance
                && minZTolerance && maxZTolerance && probe.found();
        }

        private static boolean verifyVerticalSupportProbeBounds() {
            FullFootprintTopProbe probe=new FullFootprintTopProbe();
            probe.reset(1.0D);
            probe.consume(0,-.25D,0,1,1,1);
            boolean underhangRejected=!probe.found();
            probe.reset(1.0D);
            probe.consume(0,-GROUND_SUPPORT_SHAPE_EPSILON*.5D,0,1,1,1);
            boolean minYTolerance=probe.found();

            var fullBlock=net.minecraft.world.phys.shapes.Shapes.block();
            var underhangShape=net.minecraft.world.phys.shapes.Shapes.or(
                fullBlock,fullBlock.move(0,-1.25D,0));
            boolean voxelShapeUnderhangRejected=!hasFullFootprintTop(underhangShape,1.0D);

            probe.reset(1.0D);
            probe.consume(0,0,0,1,1,1);
            return underhangRejected && minYTolerance && voxelShapeUnderhangRejected
                && probe.found();
        }

        private static final FullFootprintTopProbe GROUND_SUPPORT_TOP_PROBE=
            new FullFootprintTopProbe();

        // A maximum Y alone is not enough: cauldrons and other hollow shapes
        // have a high rim but cannot support a ribbon across their whole footprint.
        // forAllBoxes streams the boxes without allocating a temporary AABB list.
        private static boolean hasFullFootprintTop(
                net.minecraft.world.phys.shapes.VoxelShape shape,double height) {
            if(shape==null || shape.isEmpty() || !Double.isFinite(height)) return false;
            GROUND_SUPPORT_TOP_PROBE.reset(height);
            shape.forAllBoxes(GROUND_SUPPORT_TOP_PROBE);
            return GROUND_SUPPORT_TOP_PROBE.found();
        }

        private static double groundSupportHeight(
                net.minecraft.world.phys.shapes.VoxelShape collision,
                net.minecraft.world.phys.shapes.VoxelShape outline) {
            var selected=collision.isEmpty()?outline:collision;
            if(selected.isEmpty()) return Double.NaN;
            double height=selected.max(Direction.Axis.Y);
            return Double.isFinite(height) && height>0.0D
                && height<=1.0D+GROUND_SUPPORT_SHAPE_EPSILON
                && hasFullFootprintTop(selected,height) ? height : Double.NaN;
        }

        private static double groundSupportHeight(BlockState support,Level level,BlockPos pos) {
            var collision=support.getCollisionShape(level,pos);
            return groundSupportHeight(collision,collision.isEmpty()?support.getShape(level,pos):collision);
        }

        // Slabs have a uniform support plane across their footprint; stairs do not.
        private static boolean isGroundSupport(BlockState state) {
            return state.blocksMotion()
                && !state.hasBlockEntity()
                && !state.is(BlockTags.LOGS)
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.PLANKS)
                && !state.is(BlockTags.STAIRS)
                && !state.is(BlockTags.WALLS)
                && !state.is(BlockTags.FENCES)
                && !state.is(BlockTags.FENCE_GATES)
                && !state.is(BlockTags.DOORS)
                && !state.is(BlockTags.TRAPDOORS)
                && !state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        }

        private static boolean isGroundSurfaceAvailable(BlockState surface) {
            boolean snowLayer=surface.is(net.minecraft.world.level.block.Blocks.SNOW);
            return !surface.hasBlockEntity()
                && (!surface.blocksMotion() || snowLayer)
                && !surface.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                && surface.getFluidState().isEmpty();
        }

        // Powder snow is non-colliding, but it still occupies the visual headroom
        // above a fissure. Do not place the ground ribbon inside that translucent block.
        private static boolean isGroundHeadClear(BlockState head) {
            return !head.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)
                && head.getFluidState().isEmpty();
        }

        // Search from the camera height downward first: a player standing on the
        // ground usually finds support in one or two candidates instead of eight
        // guaranteed empty candidates above the player. Only then inspect higher
        // terrain, preserving the same bounded vertical window and full coverage.
        private static int surfaceSearchCandidateY(int sample,int startY,int minSurfaceY,int maxSurfaceY) {
            int belowCount=startY-minSurfaceY+1;
            return sample<belowCount
                ? startY-sample
                : startY+1+(sample-belowCount);
        }

        private static void verifySurfaceSearchOrder() {
            if(surfaceSearchProfileLogged) return;
            int reference=68;
            int min=reference-GROUND_SURFACE_SEARCH_BELOW;
            int max=reference+GROUND_SURFACE_SEARCH_ABOVE;
            int start=Math.max(min,Math.min(max,reference));
            int candidateCount=max-min+1;
            boolean[] visited=new boolean[candidateCount];
            for(int sample=0;sample<candidateCount;sample++) {
                int candidate=surfaceSearchCandidateY(sample,start,min,max);
                if(candidate<min || candidate>max || visited[candidate-min])
                    throw new IllegalStateException("BLACK FREQUENCY ground surface search order regression");
                visited[candidate-min]=true;
            }
            boolean lowClamp=surfaceSearchCandidateY(0,Math.max(20,Math.min(24,10)),20,24)==20;
            boolean highClamp=surfaceSearchCandidateY(0,Math.max(20,Math.min(24,30)),20,24)==24;
            if(surfaceSearchCandidateY(0,start,min,max)!=68
                || surfaceSearchCandidateY(1,start,min,max)!=67
                || surfaceSearchCandidateY(32,start,min,max)!=36
                || surfaceSearchCandidateY(63,start,min,max)!=5
                || surfaceSearchCandidateY(64,start,min,max)!=4
                || surfaceSearchCandidateY(65,start,min,max)!=69
                || surfaceSearchCandidateY(72,start,min,max)!=76
                || !lowClamp || !highClamp)
                throw new IllegalStateException("BLACK FREQUENCY ground surface search priority regression");
            if(GROUND_RENDER_RADIUS!=64 || GROUND_SURFACE_SEARCH_BELOW!=GROUND_RENDER_RADIUS
                || GROUND_SURFACE_SEARCH_ABOVE!=8 || candidateCount!=73)
                throw new IllegalStateException("BLACK FREQUENCY ground surface search/render range mismatch");
            surfaceSearchProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_SURFACE_SEARCH_ORDER_VERIFIED nearestBelowFirst=true bounded=true unique=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_SURFACE_SEARCH_RANGE_VERIFIED renderRadius=64 searchBelow=64 searchAbove=8 bounded=true");
        }

        private static boolean shouldQueueCanopyAccentCandidate(BlockState surface,
                int blockX,int blockZ,int queued) {
            return surface!=null
                && surface.getBlock() instanceof net.minecraft.world.level.block.BushBlock
                && queued>=0 && queued<GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET
                && ((blockX&1)==0 || (blockZ&1)==0);
        }

        // Accent motes must not spawn inside a second foliage layer above the sampled plant.
        // The ground ribbon still renders beneath leaves; only the optional particle is suppressed.
        private static boolean isCanopyAccentHeadroomAvailable(BlockState head) {
            return head!=null && head.isAir() && head.getFluidState().isEmpty();
        }

        private static boolean canopyAccentCandidateInsideExpandedSegmentBounds(double x,double z,
                GroundPoint a,GroundPoint b) {
            if(a==null || b==null || !Double.isFinite(x) || !Double.isFinite(z)
                || !Double.isFinite(a.x()) || !Double.isFinite(a.z())
                || !Double.isFinite(b.x()) || !Double.isFinite(b.z())) return false;
            double margin=GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE;
            return x>=Math.min(a.x(),b.x())-margin && x<=Math.max(a.x(),b.x())+margin
                && z>=Math.min(a.z(),b.z())-margin && z<=Math.max(a.z(),b.z())+margin;
        }

        private static boolean canopyAccentCandidateNearSegment(double x,double z,double groundY,
                GroundSegment segment) {
            if(segment==null || !Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(groundY))
                return false;
            GroundPoint a=segment.a(),b=segment.b();
            if(a==null || b==null || !Double.isFinite(a.x()) || !Double.isFinite(a.y())
                || !Double.isFinite(a.z()) || !Double.isFinite(b.x()) || !Double.isFinite(b.y())
                || !Double.isFinite(b.z())
                || !canopyAccentCandidateInsideExpandedSegmentBounds(x,z,a,b)) return false;
            double dx=b.x()-a.x(),dz=b.z()-a.z();
            double lengthSquared=dx*dx+dz*dz;
            if(!Double.isFinite(lengthSquared) || lengthSquared<=1.0E-10D) return false;
            double t=((x-a.x())*dx+(z-a.z())*dz)/lengthSquared;
            if(!Double.isFinite(t)) return false;
            t=Math.max(0.0D,Math.min(1.0D,t));
            double nearestX=a.x()+dx*t,nearestZ=a.z()+dz*t;
            double offsetX=x-nearestX,offsetZ=z-nearestZ;
            double horizontalDistanceSquared=offsetX*offsetX+offsetZ*offsetZ;
            double segmentY=a.y()+(b.y()-a.y())*t;
            double groundDelta=Math.abs(groundY-segmentY);
            return Double.isFinite(horizontalDistanceSquared) && Double.isFinite(groundDelta)
                && horizontalDistanceSquared<=GROUND_CANOPY_ACCENT_MAX_HORIZONTAL_DISTANCE_SQR
                && groundDelta<=GROUND_CANOPY_ACCENT_MAX_GROUND_DELTA;
        }

        private static boolean canopyAccentCandidateTouchesMesh(int blockX,int blockZ,double groundY,
                List<GroundSegment> segments) {
            if(segments==null || segments.isEmpty()) return false;
            double x=blockX+.5D,z=blockZ+.5D;
            for(GroundSegment segment:segments)
                if(canopyAccentCandidateNearSegment(x,z,groundY,segment)) return true;
            return false;
        }

        private static int canopyAccentCandidateIndex(int start,int offset,int candidateCount) {
            if(candidateCount<=0 || offset<0) return -1;
            return (int)Math.floorMod((long)start+(long)offset,(long)candidateCount);
        }

        private static boolean shouldAttemptCanopyAccentScan(long gameTime,long lastEmissionTick) {
            if(lastEmissionTick==Long.MIN_VALUE || gameTime<lastEmissionTick) return true;
            try {
                return Math.subtractExact(gameTime,lastEmissionTick)>=GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS;
            } catch(ArithmeticException elapsedOverflow) {
                return true;
            }
        }

        private static long nextCanopyAccentAttemptTick(long gameTime,long lastAttemptTick,int candidatesVisited) {
            return candidatesVisited>0 ? gameTime : lastAttemptTick;
        }

        private static void emitCanopyAccentCandidates(Level level,List<GroundSegment> segments,
                int worldAnchorX,int worldAnchorZ) {
            groundCanopyAccentParticlesEmitted=0;
            if(level==null || segments==null || segments.isEmpty()) return;
            int candidateCount=Math.min(groundCanopyAccentCandidateCount,GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET);
            if(candidateCount<=0) return;
            long gameTime=level.getGameTime();
            if(!shouldAttemptCanopyAccentScan(gameTime,groundCanopyAccentLastAttemptTick)) return;
            int start=canopyAccentCandidateIndex(groundCanopyAccentCandidateCursor,0,candidateCount);
            int visited=0;
            for(int offset=0;offset<candidateCount
                && groundCanopyAccentParticlesEmitted<GROUND_CANOPY_ACCENT_PARTICLE_BUDGET;offset++) {
                int i=canopyAccentCandidateIndex(start,offset,candidateCount);
                visited=offset+1;
                int x=GROUND_CANOPY_ACCENT_X[i],z=GROUND_CANOPY_ACCENT_Z[i];
                long dx=(long)x-worldAnchorX,dz=(long)z-worldAnchorZ;
                if(Math.abs(dx)>RADIUS || Math.abs(dz)>RADIUS || dx*dx+dz*dz>(long)RADIUS*RADIUS)
                    continue;
                double accentY=GROUND_CANOPY_ACCENT_Y[i];
                double groundY=GROUND_CANOPY_ACCENT_GROUND_Y[i];
                if(!Double.isFinite(accentY) || !Double.isFinite(groundY)
                    || accentY<level.getMinBuildHeight() || accentY>=level.getMaxBuildHeight()
                    || groundY<level.getMinBuildHeight() || groundY>=level.getMaxBuildHeight()) continue;
                SURFACE_CURSOR.set(x,(int)Math.floor(groundY),z);
                if(!level.getWorldBorder().isWithinBounds(SURFACE_CURSOR)
                    || !level.hasChunk(x>>4,z>>4)
                    || !canopyAccentCandidateTouchesMesh(x,z,groundY,segments)) continue;
                level.addParticle(GROUND_CANOPY_ACCENT_DUST,x+.5D,accentY,z+.5D,0.0D,.006D,0.0D);
                groundCanopyAccentParticlesEmitted++;
            }
            groundCanopyAccentCandidateCursor=canopyAccentCandidateIndex(start,visited,candidateCount);
            // Record the bounded scan even when every candidate is rejected; otherwise rapid mesh
            // invalidations can repeat the same 32 distance/segment checks inside one rebuild interval.
            groundCanopyAccentLastAttemptTick=nextCanopyAccentAttemptTick(
                gameTime,groundCanopyAccentLastAttemptTick,visited);
        }

        private static double canopyAccentParticleY(int surfaceY,double shapeHeight) {
            double boundedHeight=Double.isFinite(shapeHeight)
                ? Math.max(0.0D,Math.min(1.0D,shapeHeight)) : 0.0D;
            return surfaceY+boundedHeight+GROUND_CANOPY_ACCENT_VERTICAL_OFFSET;
        }

        private static void verifyCanopyAccentProfile() {
            if(canopyAccentSelectorProfileLogged) return;
            BlockState plant=net.minecraft.world.level.block.Blocks.DANDELION.defaultBlockState();
            boolean selected=shouldQueueCanopyAccentCandidate(plant,0,0,0);
            boolean sparse=!shouldQueueCanopyAccentCandidate(plant,1,1,0);
            boolean threeOfFourPattern=shouldQueueCanopyAccentCandidate(plant,0,0,0)
                && shouldQueueCanopyAccentCandidate(plant,1,0,0)
                && shouldQueueCanopyAccentCandidate(plant,0,1,0)
                && !shouldQueueCanopyAccentCandidate(plant,1,1,0);
            boolean negativeCoordinatePattern=shouldQueueCanopyAccentCandidate(plant,-1,0,0)
                && !shouldQueueCanopyAccentCandidate(plant,-1,-1,0);
            boolean deterministic=shouldQueueCanopyAccentCandidate(plant,4,0,0)
                && shouldQueueCanopyAccentCandidate(plant,4,0,0);
            boolean candidateBudgetRejected=!shouldQueueCanopyAccentCandidate(
                plant,0,0,GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET);
            boolean negativeCounterRejected=!shouldQueueCanopyAccentCandidate(plant,0,0,-1);
            boolean nonPlantsRejected=
                !shouldQueueCanopyAccentCandidate(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),0,0,0)
                && !shouldQueueCanopyAccentCandidate(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),0,0,0);
            double plantTop=canopyAccentParticleY(70,.8D);
            double invalidHeight=canopyAccentParticleY(70,Double.NaN);
            double excessiveHeight=canopyAccentParticleY(70,2.0D);
            boolean particleScaleBounded=Float.isFinite(GROUND_CANOPY_ACCENT_PARTICLE_SCALE)
                && GROUND_CANOPY_ACCENT_PARTICLE_SCALE>=.72F
                && GROUND_CANOPY_ACCENT_PARTICLE_SCALE<=.82F;
            boolean particleOffsetBounded=Double.isFinite(GROUND_CANOPY_ACCENT_VERTICAL_OFFSET)
                && GROUND_CANOPY_ACCENT_VERTICAL_OFFSET>=.20D
                && GROUND_CANOPY_ACCENT_VERTICAL_OFFSET<=.28D;
            boolean foliageClearanceProfile=particleOffsetBounded
                && Math.abs((plantTop-70.8D)-GROUND_CANOPY_ACCENT_VERTICAL_OFFSET)<1.0E-9D
                && Math.abs(groundSurfaceOffset(1.0D,Double.NaN)-1.0D)<1.0E-9D
                && Math.abs(groundSurfaceOffset(.5D,Double.NaN)-.5D)<1.0E-9D;
            boolean canopyAccentHeadroomProfile=
                isCanopyAccentHeadroomAvailable(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
                && !isCanopyAccentHeadroomAvailable(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState())
                && !isCanopyAccentHeadroomAvailable(net.minecraft.world.level.block.Blocks.DANDELION.defaultBlockState())
                && !isCanopyAccentHeadroomAvailable(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState())
                && isGroundHeadClear(net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
            GroundSegment fixture=new GroundSegment(new GroundPoint(0.0D,70.0D,0.0D),
                new GroundPoint(1.0D,70.0D,0.0D),0.0D,1.0D,GROUND_SAMPLES[0],
                1.0D,1.0D,1.0D,1.0D,0.0D,0.0D);
            boolean broadPhaseNear=canopyAccentCandidateInsideExpandedSegmentBounds(
                .5D,.2D,fixture.a(),fixture.b());
            boolean broadPhaseFar=!canopyAccentCandidateInsideExpandedSegmentBounds(
                .5D,1.0D,fixture.a(),fixture.b());
            boolean broadPhaseCorner=canopyAccentCandidateInsideExpandedSegmentBounds(
                -.8D,.8D,fixture.a(),fixture.b());
            boolean exactCornerRejected=!canopyAccentCandidateNearSegment(-.8D,.8D,70.05D,fixture);
            boolean invalidBoundsRejected=!canopyAccentCandidateInsideExpandedSegmentBounds(
                Double.NaN,.2D,fixture.a(),fixture.b());
            boolean nearSegment=canopyAccentCandidateNearSegment(.5D,.2D,70.05D,fixture);
            boolean farSegment=!canopyAccentCandidateNearSegment(.5D,1.0D,70.05D,fixture);
            boolean heightMismatch=!canopyAccentCandidateNearSegment(.5D,.2D,70.5D,fixture);
            boolean invalidRejected=!canopyAccentCandidateNearSegment(Double.NaN,.2D,70.05D,fixture);
            boolean emptyMeshRejected=!canopyAccentCandidateTouchesMesh(0,0,70.0D,List.of());
            boolean meshAccepted=canopyAccentCandidateTouchesMesh(0,0,70.05D,List.of(fixture));
            int rotationCandidateCount=12;
            int firstWindowStart=canopyAccentCandidateIndex(0,0,rotationCandidateCount);
            int firstWindowEnd=canopyAccentCandidateIndex(firstWindowStart,GROUND_CANOPY_ACCENT_PARTICLE_BUDGET-1,rotationCandidateCount);
            int secondWindowStart=canopyAccentCandidateIndex(firstWindowStart,GROUND_CANOPY_ACCENT_PARTICLE_BUDGET,rotationCandidateCount);
            int secondWindowEnd=canopyAccentCandidateIndex(secondWindowStart,3,rotationCandidateCount);
            int wrappedWindow=canopyAccentCandidateIndex(secondWindowStart,4,rotationCandidateCount);
            boolean rotationProfile=firstWindowStart==0 && firstWindowEnd==7
                && secondWindowStart==8 && secondWindowEnd==11 && wrappedWindow==0
                && canopyAccentCandidateIndex(-1,0,rotationCandidateCount)==11
                && canopyAccentCandidateIndex(0,0,0)==-1;
            boolean emissionThrottleProfile=GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS==10L
                && shouldAttemptCanopyAccentScan(0L,Long.MIN_VALUE)
                && !shouldAttemptCanopyAccentScan(100L,100L)
                && !shouldAttemptCanopyAccentScan(109L,100L)
                && shouldAttemptCanopyAccentScan(110L,100L)
                && shouldAttemptCanopyAccentScan(99L,100L)
                && shouldAttemptCanopyAccentScan(Long.MAX_VALUE,-1L);
            // Candidate scans can emit zero particles when terrain/segment guards reject every entry.
            // Their bounded work still counts toward the ten-tick attempt interval.
            boolean zeroEmissionScanRecorded=nextCanopyAccentAttemptTick(110L,100L,12)==110L;
            boolean emptyScanPreservesAttempt=nextCanopyAccentAttemptTick(110L,100L,0)==100L;
            if(!(selected && sparse && threeOfFourPattern && negativeCoordinatePattern && deterministic && candidateBudgetRejected && negativeCounterRejected
                && rotationProfile && emissionThrottleProfile
                && zeroEmissionScanRecorded && emptyScanPreservesAttempt
                && nonPlantsRejected && particleScaleBounded && particleOffsetBounded
                && foliageClearanceProfile && canopyAccentHeadroomProfile
                && Math.abs(plantTop-71.06D)<1.0E-9D
                && Math.abs(invalidHeight-70.26D)<1.0E-9D
                && Math.abs(excessiveHeight-71.26D)<1.0E-9D
                && broadPhaseNear && broadPhaseFar && broadPhaseCorner && exactCornerRejected
                && invalidBoundsRejected && nearSegment && farSegment && heightMismatch && invalidRejected
                && emptyMeshRejected && meshAccepted))
                throw new IllegalStateException("BLACK FREQUENCY canopy accent particle profile regression");
            canopyAccentSelectorProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_SELECTOR_PROFILE_VERIFIED budget={} candidateBudget={} selection=three-of-four-sparse plantOnly=true invalidRejected=true heightBounded=true segmentBounded=true",
                GROUND_CANOPY_ACCENT_PARTICLE_BUDGET,GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_ROTATION_PROFILE_VERIFIED candidateCount=12 budget=8 firstWindow=0-7 secondWindow=8-11 wrap=true negativeStart=true invalidCountRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_EMISSION_THROTTLE_PROFILE_VERIFIED intervalTicks={} first=true sameTickBlocked=true beforeBoundaryBlocked=true boundaryAllowed=true clockRollbackReset=true overflowSafe=true",
                GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_ATTEMPT_THROTTLE_PROFILE_VERIFIED intervalTicks={} zeroEmissionScanRecorded=true emptyScanPreservesAttempt=true",
                GROUND_CANOPY_ACCENT_EMISSION_INTERVAL_TICKS);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_PARTICLE_PROFILE_VERIFIED scale={} verticalOffset={} scaleBounded=true heightBounded=true",
                GROUND_CANOPY_ACCENT_PARTICLE_SCALE,GROUND_CANOPY_ACCENT_VERTICAL_OFFSET);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_FOLIAGE_CLEARANCE_VERIFIED offset={} particleScale={} baseRibbonUnchanged=true bounded=true",
                GROUND_CANOPY_ACCENT_VERTICAL_OFFSET,GROUND_CANOPY_ACCENT_PARTICLE_SCALE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_HEADROOM_POLICY_VERIFIED airOnly=true leavesRejected=true plantsRejected=true fluidsRejected=true ribbonStillAllowed=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_SEGMENT_FILTER_VERIFIED near=true far=false broadPhaseCorner=true exactCornerRejected=true invalidBoundsRejected=true heightBounded=true invalidRejected=true emptyMeshRejected=true");
        }

        private static double sampleGroundY(net.minecraft.client.multiplayer.ClientLevel level,
                int blockX,int blockZ,int referenceY,BlockPos.MutableBlockPos cursor,boolean emitCanopyAccent) {
            // The caller owns one render-thread cursor for the whole mesh rebuild.
            // Every state and collision shape is consumed before the cursor moves;
            // no mutable position escapes this method or is retained by the mesh.
            cursor.set(blockX,level.getMinBuildHeight(),blockZ);
            if(!level.getWorldBorder().isWithinBounds(cursor)
                || !level.hasChunk(blockX>>4,blockZ>>4)) return Double.NaN;

            // A heightmap can sit at the top of a tall tree trunk.
            // Search a bounded window around the nearby player's actual height instead:
            // this finds the ground below canopies without scanning an entire column.
            int maxSurfaceY=Math.min(referenceY+GROUND_SURFACE_SEARCH_ABOVE,level.getMaxBuildHeight()-2);
            int minSurfaceY=Math.max(level.getMinBuildHeight()+1,referenceY-GROUND_SURFACE_SEARCH_BELOW);
            if(maxSurfaceY<minSurfaceY) return Double.NaN;
            int startY=Math.max(minSurfaceY,Math.min(maxSurfaceY,referenceY));
            int candidateCount=maxSurfaceY-minSurfaceY+1;
            for(int sample=0;sample<candidateCount;sample++) {
                int candidateY=surfaceSearchCandidateY(sample,startY,minSurfaceY,maxSurfaceY);
                cursor.set(blockX,candidateY-1,blockZ);
                BlockState support=level.getBlockState(cursor);
                if(!isGroundSupport(support) || !support.getFluidState().isEmpty()) continue;
                double supportHeight=groundSupportHeight(support,level,cursor);
                if(!Double.isFinite(supportHeight)) continue;

                cursor.set(blockX,candidateY,blockZ);
                BlockState surface=level.getBlockState(cursor);
                // The ribbon is coplanar with the ground. Canopy and low overhangs
                // above it should occlude naturally through depth testing, not reject
                // an otherwise valid ground sample in forests. Stateful fixtures
                // are excluded even when their collision shape does not block motion.
                if(!isGroundSurfaceAvailable(surface)) continue;
                // Non-colliding foliage is intentionally not sampled as a height offset.
                // The already-rendered foliage occludes this ground-level mesh naturally.
                // Snow layers are different: they cover the ground and the ribbon must clear their actual top.
                double snowLayerHeight=surface.is(net.minecraft.world.level.block.Blocks.SNOW)
                    ? surface.getShape(level,cursor).max(Direction.Axis.Y) : Double.NaN;
                boolean plantSurface=surface.getBlock() instanceof net.minecraft.world.level.block.BushBlock;
                double plantShapeHeight=plantSurface
                    ? surface.getShape(level,cursor).max(Direction.Axis.Y) : Double.NaN;

                cursor.set(blockX,candidateY+1,blockZ);
                BlockState head=level.getBlockState(cursor);
                if(!isGroundHeadClear(head)) continue;

                double surfaceOffset=groundSurfaceOffset(supportHeight,snowLayerHeight);
                double groundY=candidateY-1+surfaceOffset+GROUND_SURFACE_CLEARANCE;
                if(emitCanopyAccent && plantSurface && isCanopyAccentHeadroomAvailable(head)
                    && Double.isFinite(plantShapeHeight) && plantShapeHeight>0.0D
                    && shouldQueueCanopyAccentCandidate(surface,blockX,blockZ,groundCanopyAccentCandidateCount)) {
                    double accentY=canopyAccentParticleY(candidateY,plantShapeHeight);
                    if(Double.isFinite(accentY) && accentY>=level.getMinBuildHeight()
                        && accentY<level.getMaxBuildHeight()
                        && Double.isFinite(groundY) && groundY>=level.getMinBuildHeight()
                        && groundY<level.getMaxBuildHeight()) {
                        int candidate=groundCanopyAccentCandidateCount++;
                        GROUND_CANOPY_ACCENT_X[candidate]=blockX;
                        GROUND_CANOPY_ACCENT_Z[candidate]=blockZ;
                        GROUND_CANOPY_ACCENT_Y[candidate]=accentY;
                        GROUND_CANOPY_ACCENT_GROUND_Y[candidate]=groundY;
                    }
                }
                return groundY;
            }
            return Double.NaN;
        }

        private static long columnKey(int blockX,int blockZ) {
            return ((long)blockX<<32) | (blockZ & 0xffffffffL);
        }

        private static void verifySurfaceCacheReuse() {
            if(sampleCacheReuseLogged) return;
            long key=columnKey(-17,29);
            SURFACE_Y_BY_COLUMN.clear();
            boolean missingSentinel=SURFACE_Y_BY_COLUMN.get(key)==SURFACE_CACHE_MISS;
            SURFACE_Y_BY_COLUMN.put(key,Double.NaN);
            boolean cachedUnsupported=Double.isNaN(SURFACE_Y_BY_COLUMN.get(key));
            SURFACE_Y_BY_COLUMN.clear();
            boolean cleared=SURFACE_Y_BY_COLUMN.isEmpty()
                && SURFACE_Y_BY_COLUMN.get(key)==SURFACE_CACHE_MISS;
            SURFACE_Y_BY_COLUMN.put(key,1.25D);
            boolean reused=Math.abs(SURFACE_Y_BY_COLUMN.get(key)-1.25D)<1.0E-9D;
            SURFACE_Y_BY_COLUMN.clear();
            if(!(missingSentinel && cachedUnsupported && cleared && reused))
                throw new IllegalStateException("BLACK FREQUENCY reusable ground sample cache regression");
            sampleCacheReuseLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CACHE_REUSE_VERIFIED retained=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CACHE_SENTINEL_VERIFIED miss=positiveInfinity unsupported=nan singleLookup=true");
        }

        private static GroundMesh buildSegments(
                net.minecraft.client.multiplayer.ClientLevel level,int worldAnchorX,int worldAnchorZ,
                int referenceY) {
            if(VERIFY_GROUND_MESH_PROFILES) {
                if(!groundMeshProfileModeLogged) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_PROFILE_TEST_MODE enabled=true");
                    groundMeshProfileModeLogged=true;
                }
                verifyInactiveCacheCleanup(level);
                verifyGroundMeshSamplingProfile();
                verifyGroundSampleCacheProfile();
                verifyGroundCoreGapLayerPolicy();
                verifySurfaceSearchOrder();
                verifyTaperProfile();
                verifyGroundSurfaceReliefProfile();
                verifyGroundSlopeWidthProfile();
                verifyGroundEdgeInterpolationProfile();
                verifyGroundEdgeMaskProfile();
                verifyGroundContinuityProfile();
                verifyGroundRebuildProfile();
                verifyGroundRenderRangeProfile();
                verifyGroundEndpointFadeReuseProfile();
                verifyGroundAnimationProfile();
                verifyPackedColorProfile();
                verifyRibbonColorCacheProfile();
                verifyGroundSupportProfile(level);
                verifySurfaceCacheReuse();
                verifyCanopyAccentProfile();
            }
            List<GroundSegment> result=new ArrayList<>(ARM_COUNT*(SEGMENTS_PER_ARM-1));
            // The static primitive map and mutable cursor are owned by the render
            // thread and reused across rebuilds; clear() keeps the map's capacity.
            groundCanopyAccentCandidateCount=0;
            groundCanopyAccentParticlesEmitted=0;
            SURFACE_Y_BY_COLUMN.clear();
            SURFACE_CURSOR.set(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ);
            int surfaceSamples=0;
            int cacheHits=0;
            int activeArms=0;
            int steepStepBreaks=0;
            int bridgedSingleSteps=0;
            int cliffBreakCaps=0;
            int unsupportedGapCaps=0;
            for(int arm=0;arm<ARM_COUNT;arm++) {
                int armStart=result.size();
                double angle=arm*Math.PI/2.0D;
                GroundPoint previous=null;
                GroundSample previousSample=null;
                GroundPoint pendingStep=null;
                GroundSample pendingStepSample=null;
                double pendingStepDistance=0.0D;
                boolean afterGap=false;
                for(int segment=1;segment<=SEGMENTS_PER_ARM;segment++) {
                    GroundSample sample=GROUND_SAMPLES[arm*SEGMENTS_PER_ARM+segment-1];
                    double x=worldAnchorX+.5D+sample.forwardX()-sample.bendX();
                    double z=worldAnchorZ+.5D+sample.forwardZ()+sample.bendZ();
                    int blockX=(int)Math.floor(x);
                    int blockZ=(int)Math.floor(z);
                    long dx=blockX-(long)worldAnchorX;
                    long dz=blockZ-(long)worldAnchorZ;
                    if(dx*dx+dz*dz>(long)RADIUS*RADIUS) {
                        if(capLastGroundSegmentAtGap(result,armStart,previous)) unsupportedGapCaps++;
                        previous=null;
                        previousSample=null;
                        pendingStep=null;
                        pendingStepSample=null;
                        pendingStepDistance=0.0D;
                        afterGap=true;
                        continue;
                    }
                    long key=columnKey(blockX,blockZ);
                    double sampledY;
                    sampledY=SURFACE_Y_BY_COLUMN.get(key);
                    if(sampledY==SURFACE_CACHE_MISS) {
                        // Unsupported columns are cached as NaN; only the +infinity
                        // default-return sentinel means this primitive-map key is absent.
                        sampledY=sampleGroundY(level,blockX,blockZ,referenceY,SURFACE_CURSOR,true);
                        surfaceSamples++;
                        SURFACE_Y_BY_COLUMN.put(key,sampledY);
                    } else {
                        cacheHits++;
                    }
                    GroundPoint current=Double.isFinite(sampledY)
                        ? new GroundPoint(x,sampledY,z)
                        : null;
                    if(current==null) {
                        // Never bridge a visual seam across an unloaded chunk,
                        // unsupported column, liquid, or obstructed headroom.
                        if(capLastGroundSegmentAtGap(result,armStart,previous)) unsupportedGapCaps++;
                        previous=null;
                        previousSample=null;
                        pendingStep=null;
                        pendingStepSample=null;
                        pendingStepDistance=0.0D;
                        afterGap=true;
                        continue;
                    }
                    if(previous!=null) {
                        double tangentX=current.x()-previous.x();
                        double tangentZ=current.z()-previous.z();
                        double length=Math.sqrt(tangentX*tangentX+tangentZ*tangentZ);
                        if(length>1.0E-5D) {
                            if(pendingStep!=null) {
                                if(canBridgeSingleGroundStep(previous.y(),pendingStep.y(),current.y(),
                                        pendingStepDistance,length)) {
                                    double startTaper=afterGap ? CLIFF_BREAK_TAPER : endpointTaper(segment-2);
                                    result.add(new GroundSegment(previous,current,
                                        -tangentZ/length,tangentX/length,pendingStepSample,
                                        startTaper,endpointTaper(segment),
                                        previousSample.coreWidthScale(),sample.coreWidthScale(),
                                        previousSample.coreSkew(),sample.coreSkew()));
                                    bridgedSingleSteps++;
                                    afterGap=false;
                                } else {
                                    // A second steep sample or a cliff keeps the ribbon split.
                                    if(capLastGroundSegmentAtGap(result,armStart,previous)) cliffBreakCaps++;
                                    afterGap=true;
                                    steepStepBreaks++;
                                }
                                pendingStep=null;
                                pendingStepSample=null;
                                pendingStepDistance=0.0D;
                            } else if(isContinuousGroundStep(previous.y(),current.y(),length)) {
                                double startTaper=afterGap ? CLIFF_BREAK_TAPER : endpointTaper(segment-1);
                                result.add(new GroundSegment(previous,current,
                                    -tangentZ/length,tangentX/length,sample,
                                    startTaper,endpointTaper(segment),
                                    previousSample.coreWidthScale(),sample.coreWidthScale(),
                                    previousSample.coreSkew(),sample.coreSkew()));
                                afterGap=false;
                            } else if(canStartSingleGroundStepBridge(previous.y(),current.y(),length)) {
                                // Hold one isolated sample. The next point must prove that
                                // a two-sample span is still within the same grade budget.
                                pendingStep=current;
                                pendingStepSample=sample;
                                pendingStepDistance=length;
                                continue;
                            } else {
                                // Keep real cliffs open rather than drawing a near-vertical wall.
                                if(capLastGroundSegmentAtGap(result,armStart,previous)) cliffBreakCaps++;
                                afterGap=true;
                                steepStepBreaks++;
                            }
                        }
                    }
                    previous=current;
                    previousSample=sample;
                }
                if(pendingStep!=null) {
                    if(capLastGroundSegmentAtGap(result,armStart,previous)) cliffBreakCaps++;
                    steepStepBreaks++;
                }
                if(result.size()>armStart) activeArms++;
            }
            emitCanopyAccentCandidates(level,result,worldAnchorX,worldAnchorZ);
            if(VERIFY_GROUND_MESH_PROFILES && !canopyAccentBuildProfileLogged) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_BUDGET_VERIFIED candidates={} emitted={} candidateBudget={} budget={} loadedOnly=true radius=48 clientOnly=true plantOnly=true segmentBounded=true",
                    groundCanopyAccentCandidateCount,groundCanopyAccentParticlesEmitted,
                    GROUND_CANOPY_ACCENT_CANDIDATE_BUDGET,GROUND_CANOPY_ACCENT_PARTICLE_BUDGET);
                canopyAccentBuildProfileLogged=true;
            }
            if(VERIFY_GROUND_MESH_PROFILES && groundCanopyAccentParticlesEmitted>0
                && !canopyAccentEmissionLogged) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST GROUND_CANOPY_ACCENT_PARTICLES_EMITTED count={} cachedDust=true clientOnly=true loadedOnly=true",
                    groundCanopyAccentParticlesEmitted);
                canopyAccentEmissionLogged=true;
            }
            if(!continuityBuildLogged) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_GRADE_STATS maxGrade={} cliffCaps={} unsupportedGapCaps={} singleStepBridges={} steepStepsSkipped={}",
                    MAX_GROUND_SEGMENT_GRADE,cliffBreakCaps,unsupportedGapCaps,bridgedSingleSteps,steepStepBreaks);
            }
            if(!sampleCursorReuseLogged) {
                if(surfaceSamples<=0 || surfaceSamples!=SURFACE_Y_BY_COLUMN.size()
                    || surfaceSamples+cacheHits!=ARM_COUNT*SEGMENTS_PER_ARM)
                    throw new IllegalStateException("BLACK FREQUENCY ground sample cursor reuse regression");
                sampleCursorReuseLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CURSOR_REUSE_VERIFIED sampledColumns={} cacheHits={} totalSamples={} mutableCursors=1 primitiveCache=true",
                    surfaceSamples,cacheHits,surfaceSamples+cacheHits);
            }
            return new GroundMesh(List.copyOf(result),activeArms,SURFACE_Y_BY_COLUMN.size(),cacheHits,
                steepStepBreaks);
        }

        // Use the nearest actually sampled surface point for vertical culling.
        // The anchor column's heightmap may point at a tree canopy dozens of blocks
        // above the ground and incorrectly hide a valid nearby fissure mesh.
        private static int estimateAnchorSurfaceY(List<GroundSegment> segments,
                int worldAnchorX,int worldAnchorZ,int fallbackY) {
            double centerX=worldAnchorX+.5D;
            double centerZ=worldAnchorZ+.5D;
            double nearestDistanceSquared=Double.POSITIVE_INFINITY;
            double nearestY=fallbackY;
            for(GroundSegment segment:segments) {
                GroundPoint a=segment.a();
                double ax=a.x()-centerX,az=a.z()-centerZ;
                double aDistanceSquared=ax*ax+az*az;
                if(aDistanceSquared<nearestDistanceSquared) {
                    nearestDistanceSquared=aDistanceSquared;
                    nearestY=a.y();
                }
                GroundPoint b=segment.b();
                double bx=b.x()-centerX,bz=b.z()-centerZ;
                double bDistanceSquared=bx*bx+bz*bz;
                if(bDistanceSquared<nearestDistanceSquared) {
                    nearestDistanceSquared=bDistanceSquared;
                    nearestY=b.y();
                }
            }
            return (int)Math.floor(nearestY);
        }

        private static int colorChannel(float value) {
            return Math.max(0,Math.min(255,Math.round(value*255.0F)));
        }

        private static void verifyPackedColorProfile() {
            if(packedColorProfileLogged) return;
            boolean normalized=colorChannel(0.0F)==0
                && colorChannel(.5F)==128
                && colorChannel(1.0F)==255;
            boolean clamped=colorChannel(-1.0F)==0
                && colorChannel(2.0F)==255;
            boolean nonFinite=colorChannel(Float.NaN)==0
                && colorChannel(Float.POSITIVE_INFINITY)==255
                && colorChannel(Float.NEGATIVE_INFINITY)==0;
            if(!(normalized && clamped && nonFinite))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh packed color regression");
            packedColorProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_COLOR_CHANNEL_PROFILE_VERIFIED zero=0 half=128 one=255 lowClamp=0 highClamp=255 nonFinite=true");
        }

        private static void verifyRibbonColorCacheProfile() {
            if(ribbonColorCacheProfileLogged) return;
            boolean glow=GROUND_GLOW_RED_CHANNEL==51
                && GROUND_GLOW_GREEN_CHANNEL==6 && GROUND_GLOW_BLUE_CHANNEL==110;
            boolean core=GROUND_CORE_RED_CHANNEL==2
                && GROUND_CORE_GREEN_CHANNEL==0 && GROUND_CORE_BLUE_CHANNEL==6;
            boolean filament=GROUND_FILAMENT_RED_CHANNEL==71
                && GROUND_FILAMENT_GREEN_CHANNEL==4 && GROUND_FILAMENT_BLUE_CHANNEL==158;
            boolean bevel=GROUND_BEVEL_RED_CHANNEL==14
                && GROUND_BEVEL_GREEN_CHANNEL==2 && GROUND_BEVEL_BLUE_CHANNEL==31;
            boolean edge=GROUND_EDGE_RED_CHANNEL==128
                && GROUND_EDGE_GREEN_CHANNEL==36 && GROUND_EDGE_BLUE_CHANNEL==230;
            boolean edgePaletteBound=GROUND_EDGE_RED_CHANNEL==colorChannel(GROUND_EDGE_RED)
                && GROUND_EDGE_GREEN_CHANNEL==colorChannel(GROUND_EDGE_GREEN)
                && GROUND_EDGE_BLUE_CHANNEL==colorChannel(GROUND_EDGE_BLUE);
            if(!(glow && core && bevel && filament && edge && edgePaletteBound))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh static RGB cache regression");
            ribbonColorCacheProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_RIBBON_COLOR_CACHE_VERIFIED staticRgbChannels=15 dynamicAlphaChannels=10 edgeAlphaBaseShared=true edgeNotchesCached=true edgePaletteBound={}",
                edgePaletteBound);
        }

        // Static RGB channels are packed once; each ribbon interpolates two endpoint alphas.
        private static void vertex(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                double x,double y,double z,int red,int green,int blue,int alpha) {
            builder.vertex(matrix,(float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z))
                .color(red,green,blue,alpha)
                .endVertex();
        }

        // Extend the dark core's two sides into shallow bevels. The inner edge stays
        // recessed while the outer lip rises slightly above the sampled support surface.
        private static double groundBevelOuterOffset(double coreOffset,double taper,boolean left) {
            if(!Double.isFinite(coreOffset) || !Double.isFinite(taper)
                || taper<0.0D || taper>1.0D) return Double.NaN;
            double offset=coreOffset+(left?1.0D:-1.0D)*GROUND_BEVEL_WIDTH*taper;
            return Double.isFinite(offset) ? offset : Double.NaN;
        }

        private static double groundBevelLiftScale(double wave,boolean left) {
            if(!Double.isFinite(wave)) return Double.NaN;
            double boundedWave=Math.max(0.0D,Math.min(1.0D,wave));
            double pulse=(2.0D*boundedWave-1.0D)*GROUND_BEVEL_LIFT_PULSE_RANGE;
            double scale=1.0D+(left?pulse:-pulse);
            return Double.isFinite(scale) ? scale : Double.NaN;
        }

        private static void bevelRibbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double halfWidth,boolean left,double liftScale,
                int redChannel,int greenChannel,int blueChannel,int alphaChannelA,int alphaChannelB) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            double innerA=left
                ? halfWidth*segment.coreWidthScaleA()*(1.0D+segment.coreSkewA())*segment.taperA()
                : -halfWidth*segment.coreWidthScaleA()*(1.0D-segment.coreSkewA())*segment.taperA();
            double innerB=left
                ? halfWidth*segment.coreWidthScaleB()*(1.0D+segment.coreSkewB())*segment.taperB()
                : -halfWidth*segment.coreWidthScaleB()*(1.0D-segment.coreSkewB())*segment.taperB();
            double outerA=groundBevelOuterOffset(innerA,segment.taperA(),left);
            double outerB=groundBevelOuterOffset(innerB,segment.taperB(),left);
            if(!Double.isFinite(outerA) || !Double.isFinite(outerB)
                || !Double.isFinite(liftScale)
                || liftScale<1.0D-GROUND_BEVEL_LIFT_PULSE_RANGE
                || liftScale>1.0D+GROUND_BEVEL_LIFT_PULSE_RANGE) return;
            double bevelLift=GROUND_BEVEL_LIFT*liftScale;
            if(!Double.isFinite(bevelLift)) return;
            vertex(builder,matrix,camera,a.x()+segment.normalX()*outerA,a.y()+bevelLift,
                a.z()+segment.normalZ()*outerA,redChannel,greenChannel,blueChannel,alphaChannelA);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*outerB,b.y()+bevelLift,
                b.z()+segment.normalZ()*outerB,redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*innerB,b.y()-GROUND_CORE_RECESS,
                b.z()+segment.normalZ()*innerB,redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*innerA,a.y()-GROUND_CORE_RECESS,
                a.z()+segment.normalZ()*innerA,redChannel,greenChannel,blueChannel,alphaChannelA);
        }

        // Interpolate the dark core's precomputed spatial width at both endpoints so
        // its static profile changes gradually across sample boundaries.
        private static void coreRibbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double halfWidth,double verticalOffset,
                int redChannel,int greenChannel,int blueChannel,int alphaChannelA,int alphaChannelB) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            double coreOffset=verticalOffset;
            double leftA=halfWidth*segment.coreWidthScaleA()*(1.0D+segment.coreSkewA())*segment.taperA();
            double rightA=-halfWidth*segment.coreWidthScaleA()*(1.0D-segment.coreSkewA())*segment.taperA();
            double leftB=halfWidth*segment.coreWidthScaleB()*(1.0D+segment.coreSkewB())*segment.taperB();
            double rightB=-halfWidth*segment.coreWidthScaleB()*(1.0D-segment.coreSkewB())*segment.taperB();
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y()+coreOffset,a.z()+segment.normalZ()*leftA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftB,b.y()+coreOffset,b.z()+segment.normalZ()*leftB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightB,b.y()+coreOffset,b.z()+segment.normalZ()*rightB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y()+coreOffset,a.z()+segment.normalZ()*rightA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
        }

        // Keep each violet lip aligned with the dark core's interpolated skew at both segment ends.
        // This removes per-quad offset jumps without adding vertices, draw calls or trigonometry.
        private static void edgeRibbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double halfWidth,boolean left,
                int redChannel,int greenChannel,int blueChannel,int alphaChannelA,int alphaChannelB) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            double centerA=groundEdgeCenterOffset(halfWidth,segment.coreSkewA(),left);
            double centerB=groundEdgeCenterOffset(halfWidth,segment.coreSkewB(),left);
            double edgeHalfWidth=groundEdgeHalfWidth(halfWidth);
            double leftA=(centerA+edgeHalfWidth)*segment.taperA();
            double rightA=(centerA-edgeHalfWidth)*segment.taperA();
            double leftB=(centerB+edgeHalfWidth)*segment.taperB();
            double rightB=(centerB-edgeHalfWidth)*segment.taperB();
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y()+GROUND_EDGE_LIFT,a.z()+segment.normalZ()*leftA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftB,b.y()+GROUND_EDGE_LIFT,b.z()+segment.normalZ()*leftB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightB,b.y()+GROUND_EDGE_LIFT,b.z()+segment.normalZ()*rightB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y()+GROUND_EDGE_LIFT,a.z()+segment.normalZ()*rightA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
        }

        private static void ribbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double offset,double halfWidth,double verticalOffset,
                int redChannel,int greenChannel,int blueChannel,int alphaChannelA,int alphaChannelB) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            // Scale the offset and half-width together so glow, dark core and
            // bright edge rails converge at the same tapered endpoint.
            double leftA=(offset+halfWidth)*segment.taperA();
            double rightA=(offset-halfWidth)*segment.taperA();
            double leftB=(offset+halfWidth)*segment.taperB();
            double rightB=(offset-halfWidth)*segment.taperB();
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y()+verticalOffset,a.z()+segment.normalZ()*leftA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftB,b.y()+verticalOffset,b.z()+segment.normalZ()*leftB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightB,b.y()+verticalOffset,b.z()+segment.normalZ()*rightB,
                redChannel,greenChannel,blueChannel,alphaChannelB);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y()+verticalOffset,a.z()+segment.normalZ()*rightA,
                redChannel,greenChannel,blueChannel,alphaChannelA);
        }

        private static void clearGeometryCache() {
            // Release the old ClientLevel and its sampled mesh as soon as the
            // fissure is no longer relevant to this camera/world.
            cachedLevel=null;
            cachedTick=Long.MIN_VALUE;
            cachedAnchorX=Integer.MIN_VALUE;
            cachedAnchorZ=Integer.MIN_VALUE;
            cachedAnchorSurfaceY=0;
            cachedReferenceY=Integer.MIN_VALUE;
            cachedSegments=List.of();
            cachedArmCount=0;
            cachedSampledColumns=0;
            cachedColumnCacheHits=0;
            cachedSteepStepBreaks=0;
            SURFACE_Y_BY_COLUMN.clear();
            SURFACE_CURSOR.set(0,0,0);
            groundCanopyAccentCandidateCount=0;
            groundCanopyAccentCandidateCursor=0;
            groundCanopyAccentLastAttemptTick=Long.MIN_VALUE;
            groundCanopyAccentParticlesEmitted=0;
        }

        private static void clearGeometryCacheIfPresent() {
            if(VERIFY_GROUND_MESH_PROFILES) flushVerticalCullSkipDiagnostics("cache-cleared");
            else verticallyCulledSkippedFrames=0L;
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            // A discarded mesh cannot later be reported as a successful resume.
            // A live vertical-cull entry sets this flag again after this cleanup.
            verticalCullResumePending=false;
            verticalCullResumeAnchorChanged=false;
            verticalCameraRangeSampleLogged=false;
            if(cachedLevel!=null || cachedTick!=Long.MIN_VALUE || !cachedSegments.isEmpty()
                || !SURFACE_Y_BY_COLUMN.isEmpty()) clearGeometryCache();
        }

        private static void resetCacheProfileFlags() {
            continuityBuildLogged=false;
            continuityProfileLogged=false;
            firstRenderLogged=false;
            visibilityPrefixReuseLogged=false;
            pendingRenderLogged=false;
            renderEventLogged=false;
            emptyMeshLogged=false;
            taperProfileLogged=false;
            visualProfileLogged=false;
            reliefProfileLogged=false;
            slopeWidthProfileLogged=false;
            edgeInterpolationProfileLogged=false;
            surfaceWindowLogged=false;
            supportSurfaceProfileLogged=false;
            rebuildProfileLogged=false;
            animationProfileLogged=false;
            rangeRejectLogged=false;
            rangeProfileLogged=false;
            verticalCameraRangeSampleLogged=false;
            canopyAccentSelectorProfileLogged=false;
            canopyAccentBuildProfileLogged=false;
            canopyAccentEmissionLogged=false;
            groundCanopyAccentCandidateCount=0;
            groundCanopyAccentCandidateCursor=0;
            groundCanopyAccentLastAttemptTick=Long.MIN_VALUE;
            groundCanopyAccentParticlesEmitted=0;
        }

        private static void clearCache() {
            clearCache("cache-reset");
        }

        private static void clearCache(String reason) {
            if(VERIFY_GROUND_MESH_PROFILES) flushVerticalCullSkipDiagnostics(reason);
            else verticallyCulledSkippedFrames=0L;
            clearGeometryCache();
            resetCacheProfileFlags();
            verticalCullResumePending=false;
            verticalCullResumeAnchorChanged=false;
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            unavailableAnchorChunkLogged=false;
        }

        // A transiently unavailable client chunk must not erase a live vertical-cull latch.
        // Release stale geometry once per unavailable-anchor episode, whether or not culling is active.
        // This path only observes hasChunk(); it never requests or forces a chunk load.
        private static void clearCacheForUnavailableAnchor(int worldAnchorX,int worldAnchorZ) {
            boolean preserveVerticalCull=verticallyCulled || verticalCullResumePending;
            if(!unavailableAnchorChunkLogged) {
                if(!preserveVerticalCull) {
                    // clearCache() also resets the episode latch; set it again after the full reset below.
                    clearCache();
                } else {
                    if(cachedLevel!=null || cachedTick!=Long.MIN_VALUE || !cachedSegments.isEmpty()
                        || !SURFACE_Y_BY_COLUMN.isEmpty()) clearGeometryCache();
                    resetCacheProfileFlags();
                    // The camera sample was already emitted before this availability check.
                    verticalCameraRangeSampleLogged=true;
                }
                if(VERIFY_GROUND_MESH_PROFILES) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_TEST GROUND_MESH_ANCHOR_CHUNK_UNAVAILABLE_HANDLED anchorX={} anchorZ={} preservedCull={} loadedOnly=true noChunkLoad=true resetOnce=true",
                        worldAnchorX,worldAnchorZ,preserveVerticalCull);
                    if(preserveVerticalCull) {
                        com.mojang.logging.LogUtils.getLogger().info(
                            "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_ANCHOR_CHUNK_UNAVAILABLE_PRESERVED anchorX={} anchorZ={} verticallyCulled={} skippedFrames={} loadedOnly=true noChunkLoad=true resetOnce=true",
                            worldAnchorX,worldAnchorZ,verticallyCulled,verticallyCulledSkippedFrames);
                    }
                }
                // clearCache() clears this latch as part of its full reset, so re-arm it here.
                unavailableAnchorChunkLogged=true;
            }
        }

        private static void verifyInactiveCacheCleanup(net.minecraft.client.multiplayer.ClientLevel level) {
            if(inactiveCacheCleanupLogged) return;
            cachedLevel=level;
            cachedTick=1L;
            cachedAnchorX=1;
            cachedAnchorZ=2;
            cachedAnchorSurfaceY=64;
            cachedReferenceY=64;
            cachedSegments=List.of(new GroundSegment(new GroundPoint(1,64,2),
                new GroundPoint(2,64,2),1,0,GROUND_SAMPLES[0],1,1,1.0D,1.0D,0.0D,0.0D));
            cachedArmCount=1;
            cachedSampledColumns=1;
            cachedColumnCacheHits=1;
            cachedSteepStepBreaks=1;
            SURFACE_Y_BY_COLUMN.put(columnKey(1,2),64.0D);
            SURFACE_CURSOR.set(1,64,2);
            verticalCullResumePending=true;
            verticalCullResumeAnchorChanged=true;
            verticalCameraRangeSampleLogged=true;
            clearGeometryCacheIfPresent();
            if(cachedLevel!=null || cachedTick!=Long.MIN_VALUE
                || cachedAnchorX!=Integer.MIN_VALUE || cachedAnchorZ!=Integer.MIN_VALUE
                || cachedAnchorSurfaceY!=0 || cachedReferenceY!=Integer.MIN_VALUE
                || !cachedSegments.isEmpty() || cachedArmCount!=0
                || cachedSampledColumns!=0 || cachedColumnCacheHits!=0
                || cachedSteepStepBreaks!=0 || !SURFACE_Y_BY_COLUMN.isEmpty()
                || SURFACE_CURSOR.getX()!=0 || SURFACE_CURSOR.getY()!=0 || SURFACE_CURSOR.getZ()!=0
                || verticalCullResumePending || verticalCullResumeAnchorChanged || verticalCameraRangeSampleLogged)
                throw new IllegalStateException("BLACK FREQUENCY inactive ground mesh cache cleanup regression");
            // Exercise the same vertical-range cleanup path used by render(), with
            // a real ClientLevel reference and a populated mesh/sample cache.
            cachedLevel=level;
            cachedTick=2L;
            cachedSegments=List.of(new GroundSegment(new GroundPoint(1,64,2),
                new GroundPoint(2,64,2),1,0,GROUND_SAMPLES[0],1,1,1.0D,1.0D,0.0D,0.0D));
            SURFACE_Y_BY_COLUMN.put(columnKey(1,2),64.0D);
            verticalCullResumePending=true;
            verticalCullResumeAnchorChanged=true;
            verticalCameraRangeSampleLogged=true;
            boolean verticalRangeCleared=clearGeometryCacheIfOutsideGroundRenderRange(0L,64.01D,1,2,64,false);
            if(!verticalRangeCleared || cachedLevel!=null || cachedTick!=Long.MIN_VALUE
                || !cachedSegments.isEmpty() || !SURFACE_Y_BY_COLUMN.isEmpty()
                || verticalCullResumePending || verticalCullResumeAnchorChanged || verticalCameraRangeSampleLogged)
                throw new IllegalStateException("BLACK FREQUENCY vertical ground mesh cache cleanup regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_RANGE_CACHE_RELEASE_VERIFIED levelReference=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_CULL_LIFECYCLE_RESET_VERIFIED inactive=true verticalRange=true staleResume=false cameraSample=false");
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            inactiveCacheCleanupLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_INACTIVE_CACHE_CLEANUP_VERIFIED levelReference=true");
        }

        public static void render(RenderLevelStageEvent event) {
            if(!renderEventLogged) {
                renderEventLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_EVENT_ACTIVE stage=after_particles");
            }
            if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
            Minecraft minecraft=Minecraft.getInstance();
            var level=minecraft.level;
            var player=minecraft.player;
            if(level==null || player==null || !level.dimension().equals(Level.OVERWORLD)
                || !hasAnchor()) {
                clearGeometryCacheIfPresent();
                return;
            }
            float strength=visibleStrength();
            if(!Float.isFinite(strength) || strength<.08F) {
                clearGeometryCacheIfPresent();
                return;
            }
            int worldAnchorX=anchorX();
            int worldAnchorZ=anchorZ();
            Vec3 camera=event.getCamera().getPosition();
            if(!isSafeCameraPosition(camera)) {
                clearGeometryCacheIfPresent();
                return;
            }
            int cameraBlockX=(int)Math.floor(camera.x);
            int cameraBlockY=(int)Math.floor(camera.y);
            int cameraBlockZ=(int)Math.floor(camera.z);
            long cameraHorizontalDistanceSquared=horizontalDistanceSquared(
                cameraBlockX,cameraBlockZ,worldAnchorX,worldAnchorZ);
            logVerticalCameraRangeSample(camera,level,worldAnchorX,worldAnchorZ,cameraHorizontalDistanceSquared);
            if(cameraHorizontalDistanceSquared>64L*64L) {
                clearGeometryCacheIfPresent();
                return;
            }
            // The client-state reset clears this latch when the world changes.
            // While the same anchor remains vertically culled, avoid even the loaded-chunk probe.
            if(shouldSkipGroundMeshBuildWhileVerticallyCulled(
                    worldAnchorX,worldAnchorZ,cameraHorizontalDistanceSquared,camera.y)) {
                if(VERIFY_GROUND_MESH_PROFILES) {
                    verticallyCulledSkippedFrames=nextVerticalCullSkippedFrames(verticallyCulledSkippedFrames);
                    if(verticallyCulledSkippedFrames==3L) {
                        com.mojang.logging.LogUtils.getLogger().info(
                            "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_SKIP_MILESTONE frames=3 anchorX={} anchorZ={} cameraY={}",
                            worldAnchorX,worldAnchorZ,camera.y);
                    }
                }
                return;
            }
            if(VERIFY_GROUND_MESH_PROFILES)
                flushVerticalCullSkipDiagnostics("camera-returned-or-anchor-changed");
            // Reuse the render-thread scratch cursor instead of allocating a BlockPos
            // on every frame. buildSegments() resets this cursor before sampling.
            SURFACE_CURSOR.set(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ);
            if(!level.getWorldBorder().isWithinBounds(SURFACE_CURSOR)) {
                clearCache();
                return;
            }
            if(!level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4)) {
                clearCacheForUnavailableAnchor(worldAnchorX,worldAnchorZ);
                return;
            }
            unavailableAnchorChunkLogged=false;
            long tick=level.getGameTime();
            boolean anchorChanged=level!=cachedLevel || worldAnchorX!=cachedAnchorX || worldAnchorZ!=cachedAnchorZ;
            double cachedVerticalDelta=camera.y-cachedAnchorSurfaceY;
            if(shouldCullCachedGroundMeshBeforeRebuild(anchorChanged,!cachedSegments.isEmpty(),
                    cameraHorizontalDistanceSquared,cachedVerticalDelta)) {
                clearGeometryCacheIfOutsideGroundRenderRange(cameraHorizontalDistanceSquared,cachedVerticalDelta,
                    worldAnchorX,worldAnchorZ,cachedAnchorSurfaceY,true);
                return;
            }
            int surfaceReferenceY=cameraBlockY;
            int previousReferenceY=cachedReferenceY;
            boolean verticalWindowChanged=previousReferenceY!=Integer.MIN_VALUE
                && Math.abs((long)surfaceReferenceY-previousReferenceY)>=GROUND_REBUILD_VERTICAL_DELTA;
            if(anchorChanged) emptyMeshLogged=false;
            if(shouldRebuild(tick,cachedTick,anchorChanged,surfaceReferenceY,cachedReferenceY,
                    !cachedSegments.isEmpty())) {
                boolean measureBuildTiming=shouldMeasureGroundMeshBuild(groundBuildTimingSamplesLogged);
                long buildStartedNanos=measureBuildTiming?System.nanoTime():0L;
                GroundMesh mesh=buildSegments(level,worldAnchorX,worldAnchorZ,surfaceReferenceY);
                if(measureBuildTiming) {
                    long buildElapsedNanos=System.nanoTime()-buildStartedNanos;
                    int timingSample=groundBuildTimingSamplesLogged++;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_BUILD_TIMING elapsedMicros={} firstBuild={} profileChecks={} sampledColumns={} cacheHits={} segments={} arms={} steepStepBreaks={}",
                        Math.max(0L,buildElapsedNanos/1_000L),timingSample==0,VERIFY_GROUND_MESH_PROFILES,mesh.sampledColumns(),
                        mesh.cacheHits(),mesh.segments().size(),mesh.activeArms(),mesh.steepStepBreaks());
                }
                cachedSegments=mesh.segments();
                cachedArmCount=mesh.activeArms();
                cachedSampledColumns=mesh.sampledColumns();
                cachedColumnCacheHits=mesh.cacheHits();
                cachedSteepStepBreaks=mesh.steepStepBreaks();
                cachedAnchorSurfaceY=estimateAnchorSurfaceY(cachedSegments,
                    worldAnchorX,worldAnchorZ,surfaceReferenceY);
                cachedReferenceY=surfaceReferenceY;
                if(verticalWindowChanged) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_HEIGHT_INVALIDATED previousReferenceY={} referenceY={} delta={}",
                        previousReferenceY,surfaceReferenceY,Math.abs((long)surfaceReferenceY-previousReferenceY));
                }
                if(!surfaceWindowLogged) {
                    surfaceWindowLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_SURFACE_WINDOW referenceY={} topOffset=8 bottomOffset=64 segments={} arms={} sampledColumns={} cacheHits={}",
                        surfaceReferenceY,cachedSegments.size(),cachedArmCount,cachedSampledColumns,cachedColumnCacheHits);
                }
                cachedLevel=level;
                cachedAnchorX=worldAnchorX;
                cachedAnchorZ=worldAnchorZ;
                cachedTick=tick;
                if(verticalCullResumePending) {
                    boolean resumedForAnchorChange=verticalCullResumeAnchorChanged;
                    verticalCullResumePending=false;
                    verticalCullResumeAnchorChanged=false;
                    if(VERIFY_GROUND_MESH_PROFILES) {
                        com.mojang.logging.LogUtils.getLogger().info(
                            "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_RESUMED rebuilt=true segments={} arms={} cameraY={} surfaceY={} anchorChanged={}",
                            cachedSegments.size(),cachedArmCount,camera.y,cachedAnchorSurfaceY,resumedForAnchorChange);
                    }
                }
                if(!continuityBuildLogged) {
                    continuityBuildLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_STATS maxStep={} steepStepsSkipped={}",
                        MAX_GROUND_SEGMENT_Y_DELTA,cachedSteepStepBreaks);
                }
            }
            if(cachedSegments.isEmpty()) {
                if(!emptyMeshLogged) {
                    emptyMeshLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_EMPTY anchorY={} anchorChunkLoaded={}",
                        cachedAnchorSurfaceY,level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4));
                }
                return;
            }
            double dy=camera.y-cachedAnchorSurfaceY;
            double cameraDistanceSquared=cameraHorizontalDistanceSquared+dy*dy;
            int sampledSurfaceY=cachedAnchorSurfaceY;
            if(clearGeometryCacheIfOutsideGroundRenderRange(
                    cameraHorizontalDistanceSquared,dy,worldAnchorX,worldAnchorZ,sampledSurfaceY,true)) {
                if(!rangeRejectLogged) {
                    rangeRejectLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_RANGE_REJECT cameraY={} sampledSurfaceY={} distanceSquared={}",
                        camera.y,sampledSurfaceY,cameraDistanceSquared);
                }
                return;
            }

            // Find the first drawable segment once. Reuse its endpoint fades and
            // start the draw loop there, so the invisible prefix is not evaluated twice.
            int firstVisibleSegmentIndex=-1;
            double firstVisibleFadeA=0.0D;
            double firstVisibleFadeB=0.0D;
            int prefixFadeChecks=0;
            for(int i=0;i<cachedSegments.size();i++) {
                GroundSegment segment=cachedSegments.get(i);
                if(segment==null) continue;
                double fadeA=groundPointDistanceFade(segment.a(),camera);
                double fadeB=groundPointDistanceFade(segment.b(),camera);
                prefixFadeChecks+=2;
                if(fadeA>0.0D || fadeB>0.0D) {
                    firstVisibleSegmentIndex=i;
                    firstVisibleFadeA=fadeA;
                    firstVisibleFadeB=fadeB;
                    break;
                }
            }
            if(firstVisibleSegmentIndex<0) return;
            if(VERIFY_GROUND_MESH_PROFILES && !visibilityPrefixReuseLogged) {
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST GROUND_MESH_VISIBILITY_PREFIX_REUSE_VERIFIED firstVisibleIndex={} prefixFadeChecks={} cachedFirstSegmentFade=true redundantPrefixRechecksAvoided={}",
                    firstVisibleSegmentIndex,prefixFadeChecks,prefixFadeChecks);
                visibilityPrefixReuseLogged=true;
            }
            Matrix4f matrix=event.getPoseStack().last().pose();
            BufferBuilder builder=Tesselator.getInstance().getBuilder();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            RenderSystem.setShaderColor(1.0F,1.0F,1.0F,1.0F);
            try {
                builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
                double time=(double)tick+boundedRenderPartialTick(event.getPartialTick());
                float boundedStrength=(float)Math.min(1.0F,strength);
                // Three time bases are shared by every segment. Cached phase sine/cosine
                // values turn per-segment Math.sin calls into multiply-add pairs.
                double waveTime=time*.14D;
                double waveSin=Math.sin(waveTime),waveCos=Math.cos(waveTime);
                double flickerTime=time*.055D;
                double flickerSin=Math.sin(flickerTime),flickerCos=Math.cos(flickerTime);
                double pulseTime=time*.19D;
                double pulseSin=Math.sin(pulseTime),pulseCos=Math.cos(pulseTime);
                GroundPoint previousFadePoint=null;
                double previousFadeB=0.0D;
                int reusedEndpointFadeStarts=0;
                int recomputedEndpointFadeStarts=0;
                for(int segmentIndex=firstVisibleSegmentIndex;segmentIndex<cachedSegments.size();segmentIndex++) {
                    GroundSegment segment=cachedSegments.get(segmentIndex);
                    GroundSample sample=segment.sample();
                    // A core-only gap suppresses its dark center, not the shared violet-envelope fade chain.
                    boolean coreGap=sample.coreSegmentGap();
                    double segmentFadeA,segmentFadeB;
                    if(segmentIndex==firstVisibleSegmentIndex) {
                        segmentFadeA=firstVisibleFadeA;
                        segmentFadeB=firstVisibleFadeB;
                    } else if(canReuseGroundPointFade(segment.a(),previousFadePoint,previousFadeB)) {
                        segmentFadeA=previousFadeB;
                        if(VERIFY_GROUND_MESH_PROFILES) reusedEndpointFadeStarts++;
                        segmentFadeB=groundPointDistanceFade(segment.b(),camera);
                    } else {
                        segmentFadeA=groundPointDistanceFade(segment.a(),camera);
                        if(VERIFY_GROUND_MESH_PROFILES) recomputedEndpointFadeStarts++;
                        segmentFadeB=groundPointDistanceFade(segment.b(),camera);
                    }
                    // The halo and bevels remain continuous across a core gap; keep the shared endpoint fade cached.
                    previousFadePoint=segment.b();
                    previousFadeB=segmentFadeB;
                    if(segmentFadeA<=0.0D && segmentFadeB<=0.0D) continue;
                    float segmentStrengthA=boundedStrength*(float)segmentFadeA;
                    float segmentStrengthB=boundedStrength*(float)segmentFadeB;
                    double wave=.5D+.5D*(waveSin*sample.phaseCos()+waveCos*sample.phaseSin());
                    // A slow travelling pulse changes width independently along
                    // the seam; endpoint taper keeps each arm from ending bluntly.
                    double widthFlicker=GROUND_WIDTH_FLICKER
                        *(flickerSin*sample.flickerPhaseCos()+flickerCos*sample.flickerPhaseSin());
                    double halfWidth=Math.max(GROUND_MIN_HALF_WIDTH,
                        GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE*wave+widthFlicker);
                    halfWidth*=segment.slopeWidthScale();
                    float pulse=(float)(.78D+.22D*(pulseSin*sample.phaseCos()+pulseCos*sample.phaseSin()));
                    float alphaA=segmentStrengthA*pulse;
                    float alphaB=segmentStrengthB*pulse;
                    int glowAlphaChannelA=colorChannel(alphaA*GROUND_GLOW_ALPHA);
                    int glowAlphaChannelB=colorChannel(alphaB*GROUND_GLOW_ALPHA);
                    int coreAlphaChannelA=colorChannel(alphaA*.94F);
                    int coreAlphaChannelB=colorChannel(alphaB*.94F);
                    int bevelAlphaChannelA=colorChannel(alphaA*.82F);
                    int bevelAlphaChannelB=colorChannel(alphaB*.82F);
                    double leftBevelLiftScale=groundBevelLiftScale(wave,true);
                    double rightBevelLiftScale=groundBevelLiftScale(wave,false);
                    float corePulse=(float)boundedCorePulse(wave);
                    int filamentAlphaChannelA=colorChannel(alphaA*corePulse);
                    int filamentAlphaChannelB=colorChannel(alphaB*corePulse);
                    float edgeAlphaA=Math.min(1.0F,segmentStrengthA*(.94F*pulse+.06F));
                    float edgeAlphaB=Math.min(1.0F,segmentStrengthB*(.94F*pulse+.06F));
                    int edgeAlphaChannelA=colorChannel(edgeAlphaA);
                    int edgeAlphaChannelB=colorChannel(edgeAlphaB);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_GLOW_WIDTH_SCALE,0.0D,
                        GROUND_GLOW_RED_CHANNEL,GROUND_GLOW_GREEN_CHANNEL,GROUND_GLOW_BLUE_CHANNEL,
                        glowAlphaChannelA,glowAlphaChannelB);
                    bevelRibbon(builder,matrix,camera,segment,halfWidth,true,leftBevelLiftScale,
                        GROUND_BEVEL_RED_CHANNEL,GROUND_BEVEL_GREEN_CHANNEL,GROUND_BEVEL_BLUE_CHANNEL,
                        bevelAlphaChannelA,bevelAlphaChannelB);
                    bevelRibbon(builder,matrix,camera,segment,halfWidth,false,rightBevelLiftScale,
                        GROUND_BEVEL_RED_CHANNEL,GROUND_BEVEL_GREEN_CHANNEL,GROUND_BEVEL_BLUE_CHANNEL,
                        bevelAlphaChannelA,bevelAlphaChannelB);
                    if(shouldRenderGroundCore(coreGap)) {
                        coreRibbon(builder,matrix,camera,segment,halfWidth*GROUND_CORE_WIDTH_SCALE,-GROUND_CORE_RECESS,
                            GROUND_CORE_RED_CHANNEL,GROUND_CORE_GREEN_CHANNEL,GROUND_CORE_BLUE_CHANNEL,
                            coreAlphaChannelA,coreAlphaChannelB);
                        ribbon(builder,matrix,camera,segment,0.0D,halfWidth*.16D,GROUND_FILAMENT_LIFT,
                            GROUND_FILAMENT_RED_CHANNEL,GROUND_FILAMENT_GREEN_CHANNEL,GROUND_FILAMENT_BLUE_CHANNEL,
                            filamentAlphaChannelA,filamentAlphaChannelB);
                    }
                    // Leave small, deterministic gaps in each lip independently. The
                    // mostly continuous dark core remains visible, so the seam reads as fractured
                    // earth rather than two uninterrupted luminous rails.
                    int edgeMask=groundEdgeMask(sample,segment);
                    if((edgeMask&GROUND_EDGE_LEFT_MASK)!=0)
                        edgeRibbon(builder,matrix,camera,segment,halfWidth,true,
                            GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,
                            edgeAlphaChannelA,edgeAlphaChannelB);
                    if((edgeMask&GROUND_EDGE_RIGHT_MASK)!=0)
                        edgeRibbon(builder,matrix,camera,segment,halfWidth,false,
                            GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,
                            edgeAlphaChannelA,edgeAlphaChannelB);
                }
                if(VERIFY_GROUND_MESH_PROFILES && !endpointFadeReuseLogged) {
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_TEST GROUND_MESH_ENDPOINT_FADE_REUSE_VERIFIED reusedStarts={} recomputedStarts={} gapResetPolicy=true",
                        reusedEndpointFadeStarts,recomputedEndpointFadeStarts);
                    endpointFadeReuseLogged=true;
                }
                BufferUploader.drawWithShader(builder.end());
            } finally {
                RenderSystem.depthMask(true);
                RenderSystem.enableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.disableBlend();
            }
            if(!firstRenderLogged && !pendingRenderLogged && !cachedSegments.isEmpty()
                && (cachedSegments.size()<MIN_RENDERED_SEGMENTS
                    || cachedArmCount<MIN_RENDERED_ARMS)) {
                pendingRenderLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_PENDING segments={} arms={} requiredSegments={} requiredArms={}",
                    cachedSegments.size(),cachedArmCount,MIN_RENDERED_SEGMENTS,MIN_RENDERED_ARMS);
            }
            // The first render can happen while only the spawn chunk has arrived.
            // Keep drawing the available local segments, but do not report the mesh
            // as ready until a connected surface is large enough for the client test.
            // Rebuilds continue from loaded chunks only; no chunk tickets are requested.
            if(!firstRenderLogged && cachedSegments.size()>=MIN_RENDERED_SEGMENTS
                && cachedArmCount>=MIN_RENDERED_ARMS) {
                firstRenderLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_RENDERED segments={} arms={} anchorX={} anchorZ={} sampledColumns={} cacheHits={}",
                    cachedSegments.size(),cachedArmCount,worldAnchorX,worldAnchorZ,cachedSampledColumns,cachedColumnCacheHits);
            }
        }
    }

}
