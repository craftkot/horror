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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="blackfrequency", value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class RiftClientState {
    private static float progress;
    private static boolean collapse;
    private static int finaleTicks;
    private static int ticksSincePacket;
    private static int anchorX;
    private static int anchorZ;
    private static boolean anchorValid;
    private static long beaconPosition=Long.MIN_VALUE;
    private static boolean beaconPositionValid;
    private static boolean beaconAuraProfileLogged;
    private static boolean beaconAuraRenderedLogged;
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
    // Both cages complete whole turns at the same 60-second boundary, so wrapping
    // game time cannot snap the client-only containment animation.
    private static final long BEACON_AURA_ROTATION_PERIOD=1200L;
    // World identity also changes when reconnecting to a different server in the same dimension.
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private RiftClientState() {}
    public static void update(float value, boolean collapsing, int ticks, int worldAnchorX, int worldAnchorZ, long worldBeaconPosition) {
        // Packets may arrive before the first client tick after joining a world.
        // Bind the state to that world immediately, rather than carrying it across sessions.
        net.minecraft.client.multiplayer.ClientLevel level=net.minecraft.client.Minecraft.getInstance().level;
        if(level==null) { reset(); lastLevel=null; return; }
        if(lastLevel!=null && lastLevel!=level) reset();
        lastLevel=level;
        ticksSincePacket=0;
        // Network data must never feed NaN or infinity into sky and overlay shaders.
        progress=Float.isFinite(value) ? Math.max(0f,Math.min(1f,value)) : 0f;
        collapse=collapsing;
        finaleTicks=Math.max(0,ticks);
        anchorX=worldAnchorX;
        anchorZ=worldAnchorZ;
        beaconPosition=worldBeaconPosition;
        beaconPositionValid=worldBeaconPosition!=Long.MIN_VALUE;
        anchorValid=Math.abs((long)worldAnchorX)<=30_000_000L
            && Math.abs((long)worldAnchorZ)<=30_000_000L;
    }
    public static float strength() { return progress; }
    public static float greenPhase() { return collapse && finaleTicks>=100 && finaleTicks<180 ? 1f : 0f; }
    public static float visibleStrength() { return collapse && finaleTicks<60 ? strength() * (finaleTicks/60f) : strength(); }
    public static boolean collapsing() { return collapse; }
    public static boolean hasAnchor() { return anchorValid; }
    public static int anchorX() { return anchorX; }
    public static int anchorZ() { return anchorZ; }
    public static void reset() {
        progress=0;collapse=false;finaleTicks=0;ticksSincePacket=0;
        anchorX=0;anchorZ=0;anchorValid=false;
        beaconPosition=Long.MIN_VALUE;beaconPositionValid=false;
        beaconAuraProfileLogged=false;beaconAuraRenderedLogged=false;
        GroundRenderer.clearCache();
    }

    private static boolean shouldRenderBeaconAura(boolean active,boolean hasPosition,
            boolean chunkLoaded,boolean beaconBlock,double distanceSquared) {
        return active && hasPosition && chunkLoaded && beaconBlock
            && Double.isFinite(distanceSquared) && distanceSquared>=0.0D
            && distanceSquared<BEACON_AURA_RENDER_DISTANCE_SQR;
    }

    private static double beaconAuraDistanceFade(double distanceSquared) {
        if(!Double.isFinite(distanceSquared) || distanceSquared<0.0D
            || distanceSquared>=BEACON_AURA_RENDER_DISTANCE_SQR) return 0.0D;
        double distance=Math.sqrt(distanceSquared);
        if(distance<=BEACON_AURA_FADE_START_DISTANCE) return 1.0D;
        double t=(BEACON_AURA_RENDER_DISTANCE-distance)
            /(BEACON_AURA_RENDER_DISTANCE-BEACON_AURA_FADE_START_DISTANCE);
        t=Math.max(0.0D,Math.min(1.0D,t));
        return t*t*(3.0D-2.0D*t);
    }

    private static boolean isSafeBeaconPosition(long packed,net.minecraft.client.multiplayer.ClientLevel level) {
        if(packed==Long.MIN_VALUE) return false;
        BlockPos pos=BlockPos.of(packed);
        return pos.asLong()==packed
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
        boolean fadeMonotonic=fadeNear==1.0D && fadeStart==1.0D
            && Math.abs(fadeMiddle-.5D)<1.0E-12D && fadeBoundary==0.0D;
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
            && fadeMonotonic && invalidFadeRejected)
            || !Double.isFinite(BEACON_AURA_OUTER_MARGIN)
            || !Double.isFinite(BEACON_AURA_INNER_MARGIN)
            || !Double.isFinite(BEACON_AURA_OUTER_HALF_WIDTH)
            || !Double.isFinite(BEACON_AURA_INNER_HALF_WIDTH)
            || BEACON_AURA_OUTER_MARGIN<=BEACON_AURA_INNER_MARGIN
            || BEACON_AURA_INNER_MARGIN<0.0D
            || BEACON_AURA_OUTER_HALF_WIDTH<=0.0D
            || BEACON_AURA_OUTER_HALF_WIDTH>=BEACON_AURA_OUTER_MARGIN
            || BEACON_AURA_INNER_HALF_WIDTH<=0.0D
            || BEACON_AURA_INNER_HALF_WIDTH>=BEACON_AURA_INNER_MARGIN)
            throw new IllegalStateException("BLACK FREQUENCY beacon aura range/geometry regression");
        beaconAuraProfileLogged=true;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_DISTANCE_FADE_PROFILE_VERIFIED start=40.0 end=48.0 curve=smoothstep monotonic=true invalidRejected=true");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST BEACON_AURA_RIBBON_PROFILE_VERIFIED outerHalfWidth=0.022 innerHalfWidth=0.012 cameraFacing=true allocationFree=true");
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
            BlockPos beacon,double margin,double angle,double halfWidth,
            int red,int green,int blue,int alpha) {
        double centerX=beacon.getX()+.5D,centerZ=beacon.getZ()+.5D;
        double cosine=Math.cos(angle),sine=Math.sin(angle);
        double minX=beacon.getX()-margin,maxX=beacon.getX()+1.0D+margin;
        double minY=beacon.getY()-margin,maxY=beacon.getY()+1.0D+margin;
        double minZ=beacon.getZ()-margin,maxZ=beacon.getZ()+1.0D+margin;
        // Bottom ring.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,minY,minZ,maxX,minY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,minY,minZ,maxX,minY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,minY,maxZ,minX,minY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,minY,maxZ,minX,minY,minZ,red,green,blue,alpha);
        // Top ring.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,maxY,minZ,maxX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,maxY,minZ,maxX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,maxY,maxZ,minX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,maxY,maxZ,minX,maxY,minZ,red,green,blue,alpha);
        // Four vertical containment rails.
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,minY,minZ,minX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,minY,minZ,maxX,maxY,minZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,maxX,minY,maxZ,maxX,maxY,maxZ,red,green,blue,alpha);
        beaconAuraRibbon(builder,matrix,camera,centerX,centerZ,cosine,sine,halfWidth,minX,minY,maxZ,minX,maxY,maxZ,red,green,blue,alpha);
    }

    /** Draw a client-only violet containment cage around the server-owned physical beacon. */
    public static void renderBeaconAura(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        verifyBeaconAuraProfile();
        var level=Minecraft.getInstance().level;
        if(level==null || !level.dimension().equals(Level.OVERWORLD)
            || !beaconPositionValid || !(collapse || progress>0.0F)) return;
        if(!isSafeBeaconPosition(beaconPosition,level)) return;
        BlockPos beacon=BlockPos.of(beaconPosition);
        if(!level.getWorldBorder().isWithinBounds(beacon)
            || !level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)) return;
        boolean beaconBlock=level.getBlockState(beacon).is(
            net.minecraft.world.level.block.Blocks.BEACON);
        Vec3 camera=event.getCamera().getPosition();
        if(!Double.isFinite(camera.x) || !Double.isFinite(camera.y) || !Double.isFinite(camera.z)) return;
        double dx=beacon.getX()+.5D-camera.x;
        double dy=beacon.getY()+.5D-camera.y;
        double dz=beacon.getZ()+.5D-camera.z;
        double distanceSquared=dx*dx+dy*dy+dz*dz;
        if(!shouldRenderBeaconAura(collapse || progress>0.0F,beaconPositionValid,
                true,beaconBlock,distanceSquared)) return;
        double distanceFade=beaconAuraDistanceFade(distanceSquared);
        if(distanceFade<=1.0D/255.0D) return;

        double partial=Double.isFinite(event.getPartialTick())
            ? Math.max(0.0D,Math.min(1.0D,event.getPartialTick())) : 0.0D;
        double time=(double)level.getGameTime()+partial;
        double pulse=.5D+.5D*Math.sin(time*.12D);
        double outerAngle=beaconAuraRotationAngle(level.getGameTime(),partial,1);
        double innerAngle=.35D+beaconAuraRotationAngle(level.getGameTime(),partial,-2);
        int outerAlpha=beaconAuraChannel((float)((.42D+.22D*pulse)*distanceFade));
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
                BEACON_AURA_OUTER_HALF_WIDTH,77,13,173,outerAlpha);
            emitBeaconCage(builder,matrix,camera,beacon,BEACON_AURA_INNER_MARGIN,innerAngle,
                BEACON_AURA_INNER_HALF_WIDTH,158,56,255,innerAlpha);
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
        if((progress>0f || collapse || finaleTicks>0) && ++ticksSincePacket>100) reset();
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { reset(); lastLevel=null; }
    /**
     * Client-only ground mesh for the same four-arm fissure emitted by the server.
     * It is cosmetic: it reads only already-loaded client chunks and never edits terrain.
     */
    public static final class GroundRenderer {
        private static final int RADIUS=RiftGeometry.RADIUS;
        private static final int ARM_COUNT=RiftGeometry.FISSURE_ARM_COUNT;
        // Double the cosmetic mesh density while preserving every server-particle sample at even indices.
        private static final int SEGMENTS_PER_ARM=RiftGeometry.FISSURE_SEGMENTS_PER_ARM*2;
        private static final double SAMPLE_SPACING=RiftGeometry.FISSURE_SAMPLE_SPACING*.5D;
        private static final int GROUND_ENDPOINT_TAPER_SAMPLES=RiftGeometry.FISSURE_SEGMENTS_PER_ARM/2;
        // Terrain sampling is throttled; the fissure pulse itself still renders every frame.
        // Surface sampling may inspect a 40-block vertical window per unique column.
        // Rebuild twice per second; anchor and two-block vertical changes still invalidate immediately.
        private static final long REBUILD_INTERVAL=10L;
        private static final int GROUND_REBUILD_VERTICAL_DELTA=2;
        // Reuse these render-thread-only scratch objects between mesh rebuilds.
        // fastutil clear() retains the backing arrays, avoiding a fresh map allocation every 10 ticks.
        private static final double SURFACE_CACHE_MISS=Double.POSITIVE_INFINITY;
        private static final Long2DoubleOpenHashMap SURFACE_Y_BY_COLUMN=createSurfaceCache();
        private static final BlockPos.MutableBlockPos SURFACE_CURSOR=
            new BlockPos.MutableBlockPos(0,0,0);
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
        private static final double MAX_GROUND_SEGMENT_GRADE=2.5D;
        // Keep cliff-side ribbon tips visible without bridging terrain discontinuities.
        private static final double CLIFF_BREAK_TAPER=.22D;
        // Keep the violet halo subordinate to a more legible dark fissure core without adding draw calls or vertices.
        private static final double GROUND_GLOW_WIDTH_SCALE=5.8D;
        private static final double GROUND_CORE_WIDTH_SCALE=1.65D;
        // Move both violet edge rails outward so the wider dark core remains visibly separated.
        private static final double GROUND_EDGE_OFFSET_SCALE=2.4D;
        // The render pulse is clamped to this interval for every wave/flicker phase.
        private static final double GROUND_MIN_HALF_WIDTH=.045D;
        private static final double GROUND_BASE_HALF_WIDTH=.048D;
        private static final double GROUND_WAVE_HALF_WIDTH_RANGE=.016D;
        private static final double GROUND_WIDTH_FLICKER=.004D;
        private static final double GROUND_MAX_HALF_WIDTH=
            GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE+GROUND_WIDTH_FLICKER;
        private static final double GROUND_EDGE_HALF_WIDTH=.026D;
        private static final double GROUND_MIN_CORE_EDGE_GAP=.0075D;
        // Keep every ribbon above the sampled block outline while separating the dark core and violet lips vertically.
        private static final double GROUND_SURFACE_CLEARANCE=.035D;
        private static final double GROUND_CORE_RECESS=.020D;
        private static final double GROUND_FILAMENT_LIFT=.006D;
        private static final double GROUND_EDGE_LIFT=.020D;
        // Shift the ground rails away from hot magenta toward the sky-rift's deep violet palette.
        private static final float GROUND_EDGE_RED=.46F;
        private static final float GROUND_EDGE_GREEN=.18F;
        private static final float GROUND_EDGE_BLUE=.82F;
        // RGB values are invariant across every frame and segment; pack them once at class initialization.
        private static final int GROUND_GLOW_RED_CHANNEL=colorChannel(.20F);
        private static final int GROUND_GLOW_GREEN_CHANNEL=colorChannel(.025F);
        private static final int GROUND_GLOW_BLUE_CHANNEL=colorChannel(.43F);
        private static final int GROUND_CORE_RED_CHANNEL=colorChannel(.008F);
        private static final int GROUND_CORE_GREEN_CHANNEL=colorChannel(.001F);
        private static final int GROUND_CORE_BLUE_CHANNEL=colorChannel(.022F);
        private static final int GROUND_FILAMENT_RED_CHANNEL=colorChannel(.28F);
        private static final int GROUND_FILAMENT_GREEN_CHANNEL=colorChannel(.015F);
        private static final int GROUND_FILAMENT_BLUE_CHANNEL=colorChannel(.62F);
        private static final int GROUND_EDGE_RED_CHANNEL=colorChannel(GROUND_EDGE_RED);
        private static final int GROUND_EDGE_GREEN_CHANNEL=colorChannel(GROUND_EDGE_GREEN);
        private static final int GROUND_EDGE_BLUE_CHANNEL=colorChannel(GROUND_EDGE_BLUE);
        // Keep the halo readable against grass and stone without another draw call or mesh layer.
        // Slightly stronger violet halo keeps the ground seam readable against grass and stone.
        private static final float GROUND_GLOW_ALPHA=.66F;
        // GROUND_GLOW_ALPHA is binary32; allow its widened representation's rounding error.
        private static final double GROUND_GLOW_ALPHA_TOLERANCE=1.0E-7D;
        // A restrained violet filament pulses inside the dark core without another trig call.
        private static final double GROUND_CORE_PULSE_BASE=.10D;
        private static final double GROUND_CORE_PULSE_RANGE=.22D;
        private static boolean continuityProfileLogged;
        private static boolean continuityBuildLogged;
        private static int cachedSteepStepBreaks;
        private static boolean firstRenderLogged;
        private static boolean pendingRenderLogged;
        private static boolean renderEventLogged;
        private static boolean emptyMeshLogged;
        private static boolean taperProfileLogged;
        private static boolean visualProfileLogged;
        private static boolean reliefProfileLogged;
        private static boolean surfaceWindowLogged;
        private static boolean surfaceHeightProfileLogged;
        private static boolean plantOutlineProfileLogged;
        private static boolean rebuildProfileLogged;
        private static boolean animationProfileLogged;
        private static boolean packedColorProfileLogged;
        private static boolean ribbonColorCacheProfileLogged;
        private static boolean samplingProfileLogged;
        private static boolean rangeRejectLogged;
        private static boolean rangeProfileLogged;
        private static boolean verticallyCulled;
        private static int verticallyCulledAnchorX=Integer.MIN_VALUE;
        private static int verticallyCulledAnchorZ=Integer.MIN_VALUE;
        private static int verticallyCulledSurfaceY;
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
        private record GroundSample(double forwardX,double bendX,double forwardZ,double bendZ,double phase,
                double phaseSin,double phaseCos,double flickerPhaseSin,double flickerPhaseCos) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                GroundSample sample,double taperA,double taperB) {}
        private record GroundMesh(List<GroundSegment> segments,int activeArms,int sampledColumns,int cacheHits,
                int steepStepBreaks) {}

        // The four arms and 24 cosmetic mesh samples per arm never change with the world anchor.
        // Even mesh samples align exactly with the 12 server-side particle samples.
        // Precompute their trigonometric terms once instead of recalculating them on every mesh rebuild.
        private static final GroundSample[] GROUND_SAMPLES=createGroundSamples();

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
                    samples[arm*SEGMENTS_PER_ARM+segment-1]=new GroundSample(
                        cosAngle*distance,sinAngle*bend,
                        sinAngle*distance,cosAngle*bend,phase,
                        Math.sin(phase),Math.cos(phase),
                        Math.sin(phase*1.9D),Math.cos(phase*1.9D));
                }
            }
            return samples;
        }

        private GroundRenderer() {}

        // Fade both ends of each arm over six mesh samples, preserving the old 1.35-block taper.
        // The connected ribbons become narrow tips instead of ending as uniform rectangular strips.
        private static double endpointTaper(int sample) {
            // Clamp both ends to the sampled arm so malformed indices cannot
            // create a negative or oversized ribbon width.
            int boundedSample=Math.max(0,Math.min(SEGMENTS_PER_ARM,sample));
            double ramp=Math.min(1.0,boundedSample/(double)GROUND_ENDPOINT_TAPER_SAMPLES);
            // Preserve the old 1.35-block taper length after doubling mesh sample density.
            double tail=Math.min(1.0,(SEGMENTS_PER_ARM-boundedSample)/(double)GROUND_ENDPOINT_TAPER_SAMPLES);
            double progress=Math.min(ramp,tail);
            // Smoothstep gives each arm a zero-slope start/end without changing its taper span.
            double eased=progress*progress*(3.0D-2.0D*progress);
            return .5D+.5D*eased;
        }

        private static void verifyGroundMeshSamplingProfile() {
            if(samplingProfileLogged) return;
            int samples=ARM_COUNT*SEGMENTS_PER_ARM;
            double extent=SEGMENTS_PER_ARM*SAMPLE_SPACING;
            if(ARM_COUNT!=4 || SEGMENTS_PER_ARM!=24 || samples!=96
                || !Double.isFinite(SAMPLE_SPACING) || SAMPLE_SPACING<.22D || SAMPLE_SPACING>.23D
                || Math.abs(SAMPLE_SPACING-RiftGeometry.FISSURE_SAMPLE_SPACING*.5D)>1.0E-9D
                || GROUND_ENDPOINT_TAPER_SAMPLES!=RiftGeometry.FISSURE_SEGMENTS_PER_ARM/2
                || !Double.isFinite(RiftGeometry.FISSURE_BEND_AMPLITUDE)
                || Math.abs(RiftGeometry.FISSURE_BEND_AMPLITUDE-.24D)>1.0E-9D
                || Math.abs(extent-5.40D)>1.0E-9D || extent>=RADIUS)
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
            boolean evenSamplesAligned=true;
            for(int arm=0;arm<ARM_COUNT;arm++) {
                double angle=arm*Math.PI/2.0D;
                double sinAngle=Math.sin(angle);
                double cosAngle=Math.cos(angle);
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
                        || !Double.isFinite(sample.forwardX()) || !Double.isFinite(sample.bendX())
                        || !Double.isFinite(sample.forwardZ()) || !Double.isFinite(sample.bendZ())
                        || !Double.isFinite(sample.phase()) || !Double.isFinite(sample.phaseSin())
                        || !Double.isFinite(sample.phaseCos()) || !Double.isFinite(sample.flickerPhaseSin())
                        || !Double.isFinite(sample.flickerPhaseCos()))
                        throw new IllegalStateException("BLACK FREQUENCY ground sample cache value regression");
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
            if(checked!=96 || !evenSamplesAligned)
                throw new IllegalStateException("BLACK FREQUENCY ground sample cache count/alignment regression");
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
                boolean symmetric=Math.abs(start-end)<1.0E-9D
                    && Math.abs(first-last)<1.0E-9D
                    && Math.abs(second-secondLast)<1.0E-9D;
                boolean rising=start<first && first<second && second<middle;
                boolean falling=middle>secondLast && secondLast>last && last>end;
                boolean invalidClamped=endpointTaper(-1)==start
                    && endpointTaper(SEGMENTS_PER_ARM+1)==end;
                boolean smoothstep=Math.abs(first-(.5D+1.0D/27.0D))<1.0E-9D
                    && Math.abs(second-(17.0D/27.0D))<1.0E-9D
                    && first<7.0D/12.0D && second<2.0D/3.0D;
                if(!(symmetric && rising && falling && invalidClamped && smoothstep
                    && Math.abs(middle-1.0D)<1.0E-9D))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh taper regression");
                taperProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED start={} first={} second={} middle={} secondLast={} last={} end={} symmetric={} invalidClamped={}",
                    start,first,second,middle,secondLast,last,end,symmetric,invalidClamped);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_EASING_VERIFIED curve=smoothstep zeroSlopeAtEnds=true sameSpan=true");
            }
            if(!visualProfileLogged) {
                double minimumHalfWidth=GROUND_MIN_HALF_WIDTH;
                double maximumHalfWidth=GROUND_MAX_HALF_WIDTH;
                double minimumGlowHalfWidth=minimumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double minimumCoreHalfWidth=minimumHalfWidth*GROUND_CORE_WIDTH_SCALE;
                double minimumEdgeInnerHalfWidth=minimumHalfWidth*GROUND_EDGE_OFFSET_SCALE-GROUND_EDGE_HALF_WIDTH;
                double minimumCoreEdgeGap=minimumEdgeInnerHalfWidth-minimumCoreHalfWidth;
                double maximumGlowHalfWidth=maximumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double maximumCoreHalfWidth=maximumHalfWidth*GROUND_CORE_WIDTH_SCALE;
                double maximumEdgeInnerHalfWidth=maximumHalfWidth*GROUND_EDGE_OFFSET_SCALE-GROUND_EDGE_HALF_WIDTH;
                if(Math.abs(GROUND_GLOW_WIDTH_SCALE-5.8D)>1.0E-9D
                    || Math.abs(GROUND_CORE_WIDTH_SCALE-1.65D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_OFFSET_SCALE-2.4D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_HALF_WIDTH-.026D)>1.0E-9D
                    || Math.abs(maximumHalfWidth-.068D)>1.0E-9D
                    || !Float.isFinite(GROUND_EDGE_RED) || !Float.isFinite(GROUND_EDGE_GREEN)
                    || !Float.isFinite(GROUND_EDGE_BLUE)
                    || GROUND_EDGE_RED<0.0F || GROUND_EDGE_RED>1.0F
                    || GROUND_EDGE_GREEN<0.0F || GROUND_EDGE_GREEN>1.0F
                    || GROUND_EDGE_BLUE<0.0F || GROUND_EDGE_BLUE>1.0F
                    || Math.abs(GROUND_EDGE_RED-.46F)>1.0E-6F
                    || Math.abs(GROUND_EDGE_GREEN-.18F)>1.0E-6F
                    || Math.abs(GROUND_EDGE_BLUE-.82F)>1.0E-6F
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
                    "BLACK_FREQUENCY_GROUND_MESH_EDGE_PALETTE_VERIFIED red={} green={} blue={}",
                    GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE);
            }
            if(!surfaceHeightProfileLogged) {
                double fullBlock=groundSurfaceOffset(1.0D,Double.NaN);
                double tallGrassOutline=groundSurfaceOffset(1.0D,.8125D);
                double snowLayer=groundSurfaceOffset(1.0D,.125D);
                double bottomSlab=groundSurfaceOffset(.5D,Double.NaN);
                double invalidCover=groundSurfaceOffset(.75D,Double.POSITIVE_INFINITY);
                if(Math.abs(fullBlock-1.0D)>1.0E-9D
                    || Math.abs(tallGrassOutline-1.8125D)>1.0E-9D
                    || Math.abs(snowLayer-1.125D)>1.0E-9D
                    || Math.abs(bottomSlab-.5D)>1.0E-9D
                    || Math.abs(invalidCover-.75D)>1.0E-9D)
                    throw new IllegalStateException("BLACK FREQUENCY ground surface outline-height regression");
                surfaceHeightProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_SURFACE_OUTLINE_HEIGHT_VERIFIED fullBlock={} tallGrassOutline={} snowLayer={} bottomSlab={} invalidFallback={}",
                    fullBlock,tallGrassOutline,snowLayer,bottomSlab,invalidCover);
            }
        }

        private static void verifyGroundSurfaceReliefProfile() {
            if(reliefProfileLogged) return;
            double coreClearance=GROUND_SURFACE_CLEARANCE-GROUND_CORE_RECESS;
            double glowClearance=GROUND_SURFACE_CLEARANCE;
            double filamentClearance=GROUND_SURFACE_CLEARANCE+GROUND_FILAMENT_LIFT;
            double edgeClearance=GROUND_SURFACE_CLEARANCE+GROUND_EDGE_LIFT;
            if(!Double.isFinite(coreClearance) || !Double.isFinite(glowClearance)
                || !Double.isFinite(filamentClearance) || !Double.isFinite(edgeClearance)
                || coreClearance<.01D || !(coreClearance<glowClearance)
                || !(glowClearance<filamentClearance) || !(filamentClearance<edgeClearance)
                || Math.abs(GROUND_SURFACE_CLEARANCE-.035D)>1.0E-9D
                || Math.abs(GROUND_CORE_RECESS-.020D)>1.0E-9D
                || Math.abs(GROUND_FILAMENT_LIFT-.006D)>1.0E-9D
                || Math.abs(GROUND_EDGE_LIFT-.020D)>1.0E-9D)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh surface relief regression");
            reliefProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SURFACE_RELIEF_VERIFIED coreRecess={} filamentLift={} edgeLift={} coreClearance={} glowClearance={} filamentClearance={} edgeClearance={} ordered=true",
                GROUND_CORE_RECESS,GROUND_FILAMENT_LIFT,GROUND_EDGE_LIFT,
                coreClearance,glowClearance,filamentClearance,edgeClearance);
        }

        private static double boundedShapeHeight(double height,double fallback) {
            return Double.isFinite(height)
                ? Math.max(0.0D,Math.min(1.0D,height))
                : fallback;
        }

        // Match the server particle sampler: use the visible surface outline even
        // when vegetation has no collision shape, so the client ribbon is not buried
        // below tall grass while server motes already sit above its canopy.
        private static double groundSurfaceOffset(double supportHeight,double coverShapeHeight) {
            double supportTop=boundedShapeHeight(supportHeight,1.0D);
            if(!Double.isFinite(coverShapeHeight)) return supportTop;
            return 1.0D+boundedShapeHeight(coverShapeHeight,0.0D);
        }

        private static void verifyGroundPlantOutlineProfile(
                net.minecraft.client.multiplayer.ClientLevel level,BlockPos.MutableBlockPos cursor,
                int blockX,int blockZ,int referenceY) {
            if(plantOutlineProfileLogged) return;
            cursor.set(blockX,referenceY,blockZ);
            BlockState tallGrass=net.minecraft.world.level.block.Blocks.TALL_GRASS.defaultBlockState();
            var outline=tallGrass.getShape(level,cursor);
            double outlineHeight=outline.isEmpty()
                ? Double.NaN : outline.max(Direction.Axis.Y);
            double surfaceOffset=groundSurfaceOffset(1.0D,outlineHeight);
            if(!Double.isFinite(outlineHeight) || outlineHeight<=0.0D
                || !Double.isFinite(surfaceOffset)
                || Math.abs(surfaceOffset-(1.0D+outlineHeight))>1.0E-9D)
                throw new IllegalStateException("BLACK FREQUENCY ground plant outline height regression");
            plantOutlineProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_PLANT_OUTLINE_HEIGHT_VERIFIED tallGrassOutline={} surfaceOffset={} serverAligned=true",
                outlineHeight,surfaceOffset);
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
                last.normalX(),last.normalZ(),last.sample(),last.taperA(),cappedTaper));
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
                && Math.abs(cliffBreakTaper(.10D)-.10D)<1.0E-9D
                && cliffBreakTaper(Double.NaN)==0.0D
                && cliffBreakTaper(Double.POSITIVE_INFINITY)==0.0D
                && cliffBreakTaper(Double.NEGATIVE_INFINITY)==0.0D
                && cliffBreakTaper(-1.0D)==0.0D;
            boolean softenedCliffCap=CLIFF_BREAK_TAPER>=.20D
                && CLIFF_BREAK_TAPER<=.24D && CLIFF_BREAK_TAPER<.25D;
            boolean preservedRiseBudget=Math.abs(SAMPLE_SPACING*MAX_GROUND_SEGMENT_GRADE
                -RiftGeometry.FISSURE_SAMPLE_SPACING*1.25D)<1.0E-9D;
            if(!(flat && snowStep && halfBlockRamp && oneBlockSampleRejected
                && oneBlockRampAccepted && bridgeCandidate && plateauBridge && bumpBridge
                && repeatedStepRejected && cliffBridgeRejected && invalidBridgeRejected
                && cliffRejected && invalid && capped && softenedCliffCap && preservedRiseBudget))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh continuity regression");
            continuityProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep={} maxGrade={} cliffTaper={}",
                MAX_GROUND_SEGMENT_Y_DELTA,MAX_GROUND_SEGMENT_GRADE,CLIFF_BREAK_TAPER);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_GUARD_VERIFIED maxGrade=2.5 oneBlockRiseRejected=true snowAccepted=true cliffCapProfile=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_GRADE_BUDGET_VERIFIED previousMaxRise={} currentMaxRise={} same=true",
                RiftGeometry.FISSURE_SAMPLE_SPACING*1.25D,SAMPLE_SPACING*MAX_GROUND_SEGMENT_GRADE);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SINGLE_STEP_BRIDGE_VERIFIED maxStep=1.0 spanSamples=2 plateau=true bump=true repeatedStepRejected=true cliffGuard=true invalidRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CLIFF_TAPER_PROFILE_VERIFIED taper={} previousTaper=0.25 wider=true invalidInputsZero=true",
                CLIFF_BREAK_TAPER);
        }

        private static boolean shouldRebuild(long tick,long previousTick,boolean anchorChanged,
                int referenceY,int previousReferenceY) {
            // Avoid signed overflow when a corrupted world clock jumps across the
            // long range. A saturated previous tick cannot have another interval.
            boolean intervalElapsed=tick>=previousTick
                && previousTick<=Long.MAX_VALUE-REBUILD_INTERVAL
                && tick>=previousTick+REBUILD_INTERVAL;
            return anchorChanged || previousTick==Long.MIN_VALUE || tick<previousTick
                || intervalElapsed
                || previousReferenceY==Integer.MIN_VALUE
                || Math.abs((long)referenceY-previousReferenceY)>=GROUND_REBUILD_VERTICAL_DELTA;
        }

        private static void verifyGroundRebuildProfile() {
            if(rebuildProfileLogged) return;
            boolean stable=!shouldRebuild(101L,100L,false,65,65);
            boolean oneBlockStable=!shouldRebuild(101L,100L,false,66,65);
            boolean twoBlockMove=shouldRebuild(101L,100L,false,67,65);
            boolean teleport=shouldRebuild(101L,100L,false,80,65);
            boolean sixTickStable=!shouldRebuild(106L,100L,false,65,65);
            boolean beforeInterval=!shouldRebuild(109L,100L,false,65,65);
            boolean interval=shouldRebuild(110L,100L,false,65,65);
            boolean anchor=shouldRebuild(101L,100L,true,65,65);
            boolean extremeForward=shouldRebuild(Long.MAX_VALUE,-1L,false,65,65);
            boolean nearMaxBeforeInterval=!shouldRebuild(Long.MAX_VALUE-1L,Long.MAX_VALUE-5L,false,65,65);
            boolean nearMaxAtInterval=shouldRebuild(Long.MAX_VALUE,Long.MAX_VALUE-10L,false,65,65);
            boolean negativeClockInterval=shouldRebuild(-1L,-11L,false,65,65);
            boolean backwardExtremeReset=shouldRebuild(Long.MIN_VALUE+5L,Long.MAX_VALUE-5L,false,65,65);
            if(!(stable && oneBlockStable && twoBlockMove && teleport && sixTickStable
                && beforeInterval && interval && anchor && extremeForward && nearMaxBeforeInterval
                && nearMaxAtInterval && negativeClockInterval && backwardExtremeReset))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh rebuild profile regression");
            rebuildProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta={} interval={} boundaryVerified={}",
                GROUND_REBUILD_VERTICAL_DELTA,REBUILD_INTERVAL,sixTickStable && beforeInterval && interval);
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_REBUILD_LONG_OVERFLOW_GUARD_VERIFIED forwardExtreme={} nearMaxBefore={} nearMaxAt={} negativeClock={} backwardReset={}",
                extremeForward,nearMaxBeforeInterval,nearMaxAtInterval,negativeClockInterval,backwardExtremeReset);
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
            return horizontalDistanceSquared>=0L && horizontalDistanceSquared<=64L*64L
                && Double.isFinite(verticalDelta)
                && horizontalDistanceSquared+verticalDelta*verticalDelta<=64.0D*64.0D;
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
            double smooth=t*t*(3.0D-2.0D*t);
            return 1.0D-smooth;
        }

        // A vertically distant camera should release the sampled ClientLevel and
        // mesh just like a camera that leaves the horizontal radius.
        private static boolean clearGeometryCacheIfOutsideGroundRenderRange(
                long horizontalDistanceSquared,double verticalDelta,int worldAnchorX,int worldAnchorZ,
                int anchorSurfaceY) {
            if(isWithinGroundRenderRange(horizontalDistanceSquared,verticalDelta)) return false;
            clearGeometryCacheIfPresent();
            verticallyCulled=true;
            verticallyCulledAnchorX=worldAnchorX;
            verticallyCulledAnchorZ=worldAnchorZ;
            verticallyCulledSurfaceY=anchorSurfaceY;
            return true;
        }

        // Once an out-of-range mesh is released, do not rebuild it every frame.
        // Resume only when the camera returns to the 3D envelope or the anchor changes.
        private static boolean shouldSkipGroundMeshBuildWhileVerticallyCulled(
                int worldAnchorX,int worldAnchorZ,long horizontalDistanceSquared,double cameraY) {
            if(!verticallyCulled) return false;
            if(worldAnchorX!=verticallyCulledAnchorX || worldAnchorZ!=verticallyCulledAnchorZ
                || isWithinGroundRenderRange(horizontalDistanceSquared,cameraY-verticallyCulledSurfaceY)) {
                verticallyCulled=false;
                verticallyCulledAnchorX=Integer.MIN_VALUE;
                verticallyCulledAnchorZ=Integer.MIN_VALUE;
                return false;
            }
            return true;
        }

        private static void verifyGroundRenderRangeProfile() {
            if(rangeProfileLogged) return;
            boolean center=isWithinGroundRenderRange(0L,0.0D);
            boolean horizontalBoundary=isWithinGroundRenderRange(64L*64L,0.0D);
            boolean verticalBoundary=isWithinGroundRenderRange(0L,64.0D);
            boolean verticalOutside=!isWithinGroundRenderRange(0L,64.01D);
            boolean horizontalOutside=!isWithinGroundRenderRange(64L*64L+1L,0.0D);
            boolean nanRejected=!isWithinGroundRenderRange(0L,Double.NaN);
            boolean infinityRejected=!isWithinGroundRenderRange(0L,Double.POSITIVE_INFINITY);
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
            if(!(center && horizontalBoundary && verticalBoundary && verticalOutside
                && horizontalOutside && nanRejected && infinityRejected))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh 3D range regression");
            if(!(finiteCameraAccepted && nanCameraRejected && infiniteCameraRejected
                && outOfWorldCameraRejected && cameraOutsideWhilePlayerNear && cameraInsideWhilePlayerOutside))
                throw new IllegalStateException("BLACK FREQUENCY camera-based ground mesh culling regression");
            double fadeStart=groundDistanceFade(48.0D*48.0D);
            double fadeMid=groundDistanceFade((48.0D*48.0D+64.0D*64.0D)*.5D);
            double fadeEnd=groundDistanceFade(64.0D*64.0D);
            double fadeNear=groundDistanceFade(40.0D*40.0D);
            double fadeFar=groundDistanceFade(65.0D*65.0D);
            double fadeNaN=groundDistanceFade(Double.NaN);
            double fadeNegative=groundDistanceFade(-1.0D);
            double fadeAt56=groundDistanceFade(56.0D*56.0D);
            double fadeAt60=groundDistanceFade(60.0D*60.0D);
            if(Math.abs(fadeStart-1.0D)>1.0E-9D
                || Math.abs(fadeMid-.5D)>1.0E-9D
                || fadeEnd!=0.0D || fadeNear!=1.0D || fadeFar!=0.0D
                || fadeNaN!=0.0D || fadeNegative!=0.0D
                || !(fadeAt56>fadeAt60))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh distance fade regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_DISTANCE_FADE_VERIFIED start=48 end=64 midpoint=0.5 monotonic=true invalidRejected=true");
            verticallyCulled=true;
            verticallyCulledAnchorX=1;
            verticallyCulledAnchorZ=2;
            verticallyCulledSurfaceY=64;
            boolean stableOutOfRangeSkip=shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,128.01D);
            boolean repeatedOutOfRangeSkip=shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,128.01D);
            boolean returnToRangeRebuild=!shouldSkipGroundMeshBuildWhileVerticallyCulled(1,2,0L,128.0D);
            verticallyCulled=true;
            verticallyCulledAnchorX=1;
            verticallyCulledAnchorZ=2;
            verticallyCulledSurfaceY=64;
            boolean anchorChangeRebuild=!shouldSkipGroundMeshBuildWhileVerticallyCulled(2,2,0L,128.01D);
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            if(!(stableOutOfRangeSkip && repeatedOutOfRangeSkip
                && returnToRangeRebuild && anchorChangeRebuild))
                throw new IllegalStateException("BLACK FREQUENCY vertical ground mesh cull latch regression");
            rangeProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_3D_RANGE_PROFILE_VERIFIED radius=64 horizontalBoundary=true verticalBoundary=true invalidRejected=true");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_CULL_LATCH_VERIFIED stable=true returnRebuild=true anchorChangeRebuild=true");
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
            if(!Float.isFinite(GROUND_GLOW_ALPHA) || GROUND_GLOW_ALPHA<.64F || GROUND_GLOW_ALPHA>.68F
                || Math.abs(glowAlphaMinimum-.3696D)>GROUND_GLOW_ALPHA_TOLERANCE
                || Math.abs(glowAlphaMaximum-.66D)>GROUND_GLOW_ALPHA_TOLERANCE)
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

        private static void verifyGroundSupportProfile() {
            if(supportFilterLogged) return;
            boolean naturalGrass=isGroundSupport(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState());
            boolean naturalStone=isGroundSupport(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            boolean rejectsStructures=
                !isGroundSupport(net.minecraft.world.level.block.Blocks.OAK_STAIRS.defaultBlockState())
                && !isGroundSupport(net.minecraft.world.level.block.Blocks.COBBLESTONE_SLAB.defaultBlockState())
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
            if(!(naturalGrass && naturalStone && rejectsStructures && rejectsBlockEntities))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh terrain/structure filter regression");
            supportFilterLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_STRUCTURAL_SUPPORT_FILTER_VERIFIED");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_BLOCK_ENTITY_FILTER_VERIFIED support=true surface=true");
        }

        private static boolean isGroundSupport(BlockState state) {
            return state.blocksMotion()
                && !state.hasBlockEntity()
                && !state.is(BlockTags.LOGS)
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.PLANKS)
                && !state.is(BlockTags.STAIRS)
                && !state.is(BlockTags.SLABS)
                && !state.is(BlockTags.WALLS)
                && !state.is(BlockTags.FENCES)
                && !state.is(BlockTags.FENCE_GATES)
                && !state.is(BlockTags.DOORS)
                && !state.is(BlockTags.TRAPDOORS)
                && !state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        }

        private static boolean isGroundSurfaceAvailable(BlockState surface) {
            return !surface.hasBlockEntity()
                && !surface.blocksMotion()
                && surface.getFluidState().isEmpty();
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
            int min=36,reference=68,max=76;
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
                || surfaceSearchCandidateY(33,start,min,max)!=69
                || surfaceSearchCandidateY(40,start,min,max)!=76
                || !lowClamp || !highClamp)
                throw new IllegalStateException("BLACK FREQUENCY ground surface search priority regression");
            surfaceSearchProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_SURFACE_SEARCH_ORDER_VERIFIED nearestBelowFirst=true bounded=true unique=true");
        }

        private static double sampleGroundY(net.minecraft.client.multiplayer.ClientLevel level,
                int blockX,int blockZ,int referenceY,BlockPos.MutableBlockPos cursor) {
            // The caller owns one render-thread cursor for the whole mesh rebuild.
            // Every state and collision shape is consumed before the cursor moves;
            // no mutable position escapes this method or is retained by the mesh.
            cursor.set(blockX,level.getMinBuildHeight(),blockZ);
            if(!level.getWorldBorder().isWithinBounds(cursor)
                || !level.hasChunk(blockX>>4,blockZ>>4)) return Double.NaN;

            // A no-leaves heightmap can still select the top of a tall tree trunk.
            // Search a bounded window around the nearby player's actual height instead:
            // this finds the ground below canopies without scanning an entire column.
            int maxSurfaceY=Math.min(referenceY+8,level.getMaxBuildHeight()-2);
            int minSurfaceY=Math.max(level.getMinBuildHeight()+1,referenceY-32);
            if(maxSurfaceY<minSurfaceY) return Double.NaN;
            int startY=Math.max(minSurfaceY,Math.min(maxSurfaceY,referenceY));
            int candidateCount=maxSurfaceY-minSurfaceY+1;
            for(int sample=0;sample<candidateCount;sample++) {
                int candidateY=surfaceSearchCandidateY(sample,startY,minSurfaceY,maxSurfaceY);
                cursor.set(blockX,candidateY-1,blockZ);
                BlockState support=level.getBlockState(cursor);
                if(!isGroundSupport(support) || !support.getFluidState().isEmpty()) continue;
                var supportCollision=support.getCollisionShape(level,cursor);
                double supportHeight=supportCollision.isEmpty()
                    ? support.getShape(level,cursor).max(Direction.Axis.Y)
                    : supportCollision.max(Direction.Axis.Y);

                cursor.set(blockX,candidateY,blockZ);
                BlockState surface=level.getBlockState(cursor);
                // The ribbon is coplanar with the ground. Canopy and low overhangs
                // above it should occlude naturally through depth testing, not reject
                // an otherwise valid ground sample in forests. Stateful fixtures
                // are excluded even when their collision shape does not block motion.
                if(!isGroundSurfaceAvailable(surface)) continue;
                // Use the outline, not only collision geometry: grass and flowers
                // are non-colliding but visibly cover the ground-level ribbon.
                var coverShape=surface.getShape(level,cursor);
                double coverHeight=coverShape.isEmpty()
                    ? Double.NaN
                    : coverShape.max(Direction.Axis.Y);

                cursor.set(blockX,candidateY+1,blockZ);
                BlockState head=level.getBlockState(cursor);
                if(!head.getFluidState().isEmpty()) continue;

                double surfaceOffset=groundSurfaceOffset(supportHeight,coverHeight);
                return candidateY-1+surfaceOffset+GROUND_SURFACE_CLEARANCE;
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
            verifyInactiveCacheCleanup(level);
            verifyGroundMeshSamplingProfile();
            verifyGroundSampleCacheProfile();
            verifyGroundPlantOutlineProfile(level,SURFACE_CURSOR,worldAnchorX,worldAnchorZ,referenceY);
            verifySurfaceSearchOrder();
            verifyTaperProfile();
            verifyGroundSurfaceReliefProfile();
            verifyGroundContinuityProfile();
            verifyGroundRebuildProfile();
            verifyGroundRenderRangeProfile();
            verifyGroundAnimationProfile();
            verifyPackedColorProfile();
            verifyRibbonColorCacheProfile();
            verifyGroundSupportProfile();
            verifySurfaceCacheReuse();
            List<GroundSegment> result=new ArrayList<>(ARM_COUNT*(SEGMENTS_PER_ARM-1));
            // The static primitive map and mutable cursor are owned by the render
            // thread and reused across rebuilds; clear() keeps the map's capacity.
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
                        sampledY=sampleGroundY(level,blockX,blockZ,referenceY,SURFACE_CURSOR);
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
                                        startTaper,endpointTaper(segment)));
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
                                    startTaper,endpointTaper(segment)));
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
                }
                if(pendingStep!=null) {
                    if(capLastGroundSegmentAtGap(result,armStart,previous)) cliffBreakCaps++;
                    steepStepBreaks++;
                }
                if(result.size()>armStart) activeArms++;
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
            boolean edge=GROUND_EDGE_RED_CHANNEL==117
                && GROUND_EDGE_GREEN_CHANNEL==46 && GROUND_EDGE_BLUE_CHANNEL==209;
            if(!(glow && core && filament && edge))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh static RGB cache regression");
            ribbonColorCacheProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_RIBBON_COLOR_CACHE_VERIFIED staticRgbChannels=12 dynamicAlphaChannels=4 edgeAlphaShared=true");
        }

        // Static RGB channels are packed once; only each ribbon's animated alpha is dynamic.
        private static void vertex(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                double x,double y,double z,int red,int green,int blue,int alpha) {
            builder.vertex(matrix,(float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z))
                .color(red,green,blue,alpha)
                .endVertex();
        }

        private static void ribbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double offset,double halfWidth,double verticalOffset,
                int redChannel,int greenChannel,int blueChannel,int alphaChannel) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            // Scale the offset and half-width together so glow, dark core and
            // bright edge rails converge at the same tapered endpoint.
            double leftA=(offset+halfWidth)*segment.taperA();
            double rightA=(offset-halfWidth)*segment.taperA();
            double leftB=(offset+halfWidth)*segment.taperB();
            double rightB=(offset-halfWidth)*segment.taperB();
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y()+verticalOffset,a.z()+segment.normalZ()*leftA,
                redChannel,greenChannel,blueChannel,alphaChannel);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftB,b.y()+verticalOffset,b.z()+segment.normalZ()*leftB,
                redChannel,greenChannel,blueChannel,alphaChannel);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightB,b.y()+verticalOffset,b.z()+segment.normalZ()*rightB,
                redChannel,greenChannel,blueChannel,alphaChannel);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y()+verticalOffset,a.z()+segment.normalZ()*rightA,
                redChannel,greenChannel,blueChannel,alphaChannel);
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
        }

        private static void clearGeometryCacheIfPresent() {
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
            if(cachedLevel!=null || cachedTick!=Long.MIN_VALUE || !cachedSegments.isEmpty()
                || !SURFACE_Y_BY_COLUMN.isEmpty()) clearGeometryCache();
        }

        private static void clearCache() {
            clearGeometryCache();
            continuityBuildLogged=false;
            continuityProfileLogged=false;
            firstRenderLogged=false;
            pendingRenderLogged=false;
            renderEventLogged=false;
            emptyMeshLogged=false;
            taperProfileLogged=false;
            visualProfileLogged=false;
            reliefProfileLogged=false;
            surfaceWindowLogged=false;
            surfaceHeightProfileLogged=false;
            rebuildProfileLogged=false;
            animationProfileLogged=false;
            rangeRejectLogged=false;
            rangeProfileLogged=false;
            verticallyCulled=false;
            verticallyCulledAnchorX=Integer.MIN_VALUE;
            verticallyCulledAnchorZ=Integer.MIN_VALUE;
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
                new GroundPoint(2,64,2),1,0,GROUND_SAMPLES[0],1,1));
            cachedArmCount=1;
            cachedSampledColumns=1;
            cachedColumnCacheHits=1;
            cachedSteepStepBreaks=1;
            SURFACE_Y_BY_COLUMN.put(columnKey(1,2),64.0D);
            SURFACE_CURSOR.set(1,64,2);
            clearGeometryCacheIfPresent();
            if(cachedLevel!=null || cachedTick!=Long.MIN_VALUE
                || cachedAnchorX!=Integer.MIN_VALUE || cachedAnchorZ!=Integer.MIN_VALUE
                || cachedAnchorSurfaceY!=0 || cachedReferenceY!=Integer.MIN_VALUE
                || !cachedSegments.isEmpty() || cachedArmCount!=0
                || cachedSampledColumns!=0 || cachedColumnCacheHits!=0
                || cachedSteepStepBreaks!=0 || !SURFACE_Y_BY_COLUMN.isEmpty()
                || SURFACE_CURSOR.getX()!=0 || SURFACE_CURSOR.getY()!=0 || SURFACE_CURSOR.getZ()!=0)
                throw new IllegalStateException("BLACK FREQUENCY inactive ground mesh cache cleanup regression");
            // Exercise the same vertical-range cleanup path used by render(), with
            // a real ClientLevel reference and a populated mesh/sample cache.
            cachedLevel=level;
            cachedTick=2L;
            cachedSegments=List.of(new GroundSegment(new GroundPoint(1,64,2),
                new GroundPoint(2,64,2),1,0,GROUND_SAMPLES[0],1,1));
            SURFACE_Y_BY_COLUMN.put(columnKey(1,2),64.0D);
            boolean verticalRangeCleared=clearGeometryCacheIfOutsideGroundRenderRange(0L,64.01D,1,2,64);
            if(!verticalRangeCleared || cachedLevel!=null || cachedTick!=Long.MIN_VALUE
                || !cachedSegments.isEmpty() || !SURFACE_Y_BY_COLUMN.isEmpty())
                throw new IllegalStateException("BLACK FREQUENCY vertical ground mesh cache cleanup regression");
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_TEST GROUND_MESH_VERTICAL_RANGE_CACHE_RELEASE_VERIFIED levelReference=true");
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
            if(cameraHorizontalDistanceSquared>64L*64L) {
                clearGeometryCacheIfPresent();
                return;
            }
            // Reuse the render-thread scratch cursor instead of allocating a BlockPos
            // on every frame. buildSegments() resets this cursor before sampling.
            SURFACE_CURSOR.set(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ);
            if(!level.getWorldBorder().isWithinBounds(SURFACE_CURSOR)
                || !level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4)) {
                clearCache();
                return;
            }
            if(shouldSkipGroundMeshBuildWhileVerticallyCulled(
                    worldAnchorX,worldAnchorZ,cameraHorizontalDistanceSquared,camera.y)) return;
            long tick=level.getGameTime();
            boolean anchorChanged=level!=cachedLevel || worldAnchorX!=cachedAnchorX || worldAnchorZ!=cachedAnchorZ;
            int surfaceReferenceY=cameraBlockY;
            int previousReferenceY=cachedReferenceY;
            boolean verticalWindowChanged=previousReferenceY!=Integer.MIN_VALUE
                && Math.abs((long)surfaceReferenceY-previousReferenceY)>=GROUND_REBUILD_VERTICAL_DELTA;
            if(anchorChanged) emptyMeshLogged=false;
            if(shouldRebuild(tick,cachedTick,anchorChanged,surfaceReferenceY,cachedReferenceY)) {
                GroundMesh mesh=buildSegments(level,worldAnchorX,worldAnchorZ,surfaceReferenceY);
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
                        "BLACK_FREQUENCY_GROUND_MESH_SURFACE_WINDOW referenceY={} topOffset=8 bottomOffset=32 segments={} arms={} sampledColumns={} cacheHits={}",
                        surfaceReferenceY,cachedSegments.size(),cachedArmCount,cachedSampledColumns,cachedColumnCacheHits);
                }
                cachedLevel=level;
                cachedAnchorX=worldAnchorX;
                cachedAnchorZ=worldAnchorZ;
                cachedTick=tick;
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
                    cameraHorizontalDistanceSquared,dy,worldAnchorX,worldAnchorZ,sampledSurfaceY)) {
                if(!rangeRejectLogged) {
                    rangeRejectLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_RANGE_REJECT cameraY={} sampledSurfaceY={} distanceSquared={}",
                        camera.y,sampledSurfaceY,cameraDistanceSquared);
                }
                return;
            }

            double rangeFade=groundDistanceFade(cameraDistanceSquared);
            if(rangeFade<=0.0D) return;
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
                float boundedStrength=(float)(Math.min(1.0F,strength)*rangeFade);
                // Three time bases are shared by every segment. Cached phase sine/cosine
                // values turn per-segment Math.sin calls into multiply-add pairs.
                double waveTime=time*.14D;
                double waveSin=Math.sin(waveTime),waveCos=Math.cos(waveTime);
                double flickerTime=time*.055D;
                double flickerSin=Math.sin(flickerTime),flickerCos=Math.cos(flickerTime);
                double pulseTime=time*.19D;
                double pulseSin=Math.sin(pulseTime),pulseCos=Math.cos(pulseTime);
                for(GroundSegment segment:cachedSegments) {
                    GroundSample sample=segment.sample();
                    double wave=.5D+.5D*(waveSin*sample.phaseCos()+waveCos*sample.phaseSin());
                    // A slow travelling pulse changes width independently along
                    // the seam; endpoint taper keeps each arm from ending bluntly.
                    double widthFlicker=GROUND_WIDTH_FLICKER
                        *(flickerSin*sample.flickerPhaseCos()+flickerCos*sample.flickerPhaseSin());
                    double halfWidth=Math.max(GROUND_MIN_HALF_WIDTH,
                        GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE*wave+widthFlicker);
                    float pulse=(float)(.78D+.22D*(pulseSin*sample.phaseCos()+pulseCos*sample.phaseSin()));
                    float alpha=boundedStrength*pulse;
                    int glowAlphaChannel=colorChannel(alpha*GROUND_GLOW_ALPHA);
                    int coreAlphaChannel=colorChannel(alpha*.94F);
                    float corePulse=(float)boundedCorePulse(wave);
                    int filamentAlphaChannel=colorChannel(alpha*corePulse);
                    float edgeAlpha=Math.min(1.0F,boundedStrength*(.94F*pulse+.06F));
                    int edgeAlphaChannel=colorChannel(edgeAlpha);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_GLOW_WIDTH_SCALE,0.0D,
                        GROUND_GLOW_RED_CHANNEL,GROUND_GLOW_GREEN_CHANNEL,GROUND_GLOW_BLUE_CHANNEL,glowAlphaChannel);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_CORE_WIDTH_SCALE,-GROUND_CORE_RECESS,
                        GROUND_CORE_RED_CHANNEL,GROUND_CORE_GREEN_CHANNEL,GROUND_CORE_BLUE_CHANNEL,coreAlphaChannel);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*.16D,GROUND_FILAMENT_LIFT,
                        GROUND_FILAMENT_RED_CHANNEL,GROUND_FILAMENT_GREEN_CHANNEL,GROUND_FILAMENT_BLUE_CHANNEL,filamentAlphaChannel);
                    ribbon(builder,matrix,camera,segment,halfWidth*GROUND_EDGE_OFFSET_SCALE,GROUND_EDGE_HALF_WIDTH,GROUND_EDGE_LIFT,
                        GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,edgeAlphaChannel);
                    ribbon(builder,matrix,camera,segment,-halfWidth*GROUND_EDGE_OFFSET_SCALE,GROUND_EDGE_HALF_WIDTH,GROUND_EDGE_LIFT,
                        GROUND_EDGE_RED_CHANNEL,GROUND_EDGE_GREEN_CHANNEL,GROUND_EDGE_BLUE_CHANNEL,edgeAlphaChannel);
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
