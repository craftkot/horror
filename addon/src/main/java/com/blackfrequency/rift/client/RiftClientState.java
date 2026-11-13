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
    // World identity also changes when reconnecting to a different server in the same dimension.
    private static net.minecraft.client.multiplayer.ClientLevel lastLevel;
    private RiftClientState() {}
    public static void update(float value, boolean collapsing, int ticks, int worldAnchorX, int worldAnchorZ) {
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
        GroundRenderer.clearCache();
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
        private static final int SEGMENTS_PER_ARM=RiftGeometry.FISSURE_SEGMENTS_PER_ARM;
        private static final double SAMPLE_SPACING=RiftGeometry.FISSURE_SAMPLE_SPACING;
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
        // Do not turn a sharp cliff into a vertical translucent wall.
        private static final double MAX_GROUND_SEGMENT_Y_DELTA=1.25D;
        // Slightly widen the ground seam so it remains legible through partial canopy gaps.
        private static final double GROUND_GLOW_WIDTH_SCALE=6.8D;
        private static final double GROUND_CORE_WIDTH_SCALE=1.15D;
        // The render pulse is clamped to this interval for every wave/flicker phase.
        private static final double GROUND_MIN_HALF_WIDTH=.045D;
        private static final double GROUND_BASE_HALF_WIDTH=.048D;
        private static final double GROUND_WAVE_HALF_WIDTH_RANGE=.016D;
        private static final double GROUND_WIDTH_FLICKER=.004D;
        private static final double GROUND_MAX_HALF_WIDTH=
            GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE+GROUND_WIDTH_FLICKER;
        private static final double GROUND_EDGE_HALF_WIDTH=.029D;
        // Shift the ground rails away from hot magenta toward the sky-rift's deep violet palette.
        private static final float GROUND_EDGE_RED=.46F;
        private static final float GROUND_EDGE_GREEN=.18F;
        private static final float GROUND_EDGE_BLUE=.82F;
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
        private static boolean surfaceWindowLogged;
        private static boolean surfaceHeightProfileLogged;
        private static boolean rebuildProfileLogged;
        private static boolean animationProfileLogged;
        private static boolean samplingProfileLogged;
        private static boolean rangeRejectLogged;
        private static boolean rangeProfileLogged;
        private static boolean verticallyCulled;
        private static int verticallyCulledAnchorX=Integer.MIN_VALUE;
        private static int verticallyCulledAnchorZ=Integer.MIN_VALUE;
        private static int verticallyCulledSurfaceY;
        private static boolean supportFilterLogged;
        private static boolean sampleCursorReuseLogged;

        private static Long2DoubleOpenHashMap createSurfaceCache() {
            Long2DoubleOpenHashMap cache=new Long2DoubleOpenHashMap(ARM_COUNT*SEGMENTS_PER_ARM);
            // Valid samples are finite and unsupported columns are NaN, so +infinity
            // is an unambiguous missing-key sentinel and lets the hot loop use one lookup.
            cache.defaultReturnValue(SURFACE_CACHE_MISS);
            return cache;
        }
        private static boolean inactiveCacheCleanupLogged;

        private record GroundPoint(double x,double y,double z) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                double phase,double taperA,double taperB) {}
        private record GroundMesh(List<GroundSegment> segments,int activeArms,int sampledColumns,int cacheHits,
                int steepStepBreaks) {}

        private GroundRenderer() {}

        // Fade both ends of each arm over three samples. The connected ribbons
        // become narrow tips instead of ending as uniform rectangular strips.
        private static double endpointTaper(int sample) {
            // Clamp both ends to the sampled arm so malformed indices cannot
            // create a negative or oversized ribbon width.
            int boundedSample=Math.max(0,Math.min(SEGMENTS_PER_ARM,sample));
            double ramp=Math.min(1.0,boundedSample/3.0);
            // The final vertex is sample SEGMENTS_PER_ARM, not SEGMENTS_PER_ARM+1.
            // Using the same endpoint index on both sides keeps all four arms symmetric.
            double tail=Math.min(1.0,(SEGMENTS_PER_ARM-boundedSample)/3.0);
            return .5D+.5D*Math.min(ramp,tail);
        }

        private static void verifyGroundMeshSamplingProfile() {
            if(samplingProfileLogged) return;
            int samples=ARM_COUNT*SEGMENTS_PER_ARM;
            double extent=SEGMENTS_PER_ARM*SAMPLE_SPACING;
            if(ARM_COUNT!=4 || SEGMENTS_PER_ARM!=12 || samples!=48
                || !Double.isFinite(SAMPLE_SPACING) || SAMPLE_SPACING<.45D || SAMPLE_SPACING>.5D
                || Math.abs(extent-5.40D)>1.0E-9D || extent>=RADIUS)
                throw new IllegalStateException("BLACK FREQUENCY ground mesh sampling profile regression");
            samplingProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLING_PROFILE_VERIFIED samples={} spacing={} extent={} radius={}",
                samples,SAMPLE_SPACING,extent,RADIUS);
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
                if(!(symmetric && rising && falling && invalidClamped
                    && Math.abs(middle-1.0D)<1.0E-9D))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh taper regression");
                taperProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED start={} first={} second={} middle={} secondLast={} last={} end={} symmetric={} invalidClamped={}",
                    start,first,second,middle,secondLast,last,end,symmetric,invalidClamped);
            }
            if(!visualProfileLogged) {
                double minimumHalfWidth=GROUND_MIN_HALF_WIDTH;
                double maximumHalfWidth=GROUND_MAX_HALF_WIDTH;
                double minimumGlowHalfWidth=minimumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double minimumCoreHalfWidth=minimumHalfWidth*GROUND_CORE_WIDTH_SCALE;
                double minimumEdgeInnerHalfWidth=minimumHalfWidth*1.9D-GROUND_EDGE_HALF_WIDTH;
                double maximumGlowHalfWidth=maximumHalfWidth*GROUND_GLOW_WIDTH_SCALE;
                double maximumCoreHalfWidth=maximumHalfWidth*GROUND_CORE_WIDTH_SCALE;
                double maximumEdgeInnerHalfWidth=maximumHalfWidth*1.9D-GROUND_EDGE_HALF_WIDTH;
                if(Math.abs(GROUND_GLOW_WIDTH_SCALE-6.8D)>1.0E-9D
                    || Math.abs(GROUND_CORE_WIDTH_SCALE-1.15D)>1.0E-9D
                    || Math.abs(GROUND_EDGE_HALF_WIDTH-.029D)>1.0E-9D
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
                    || !(minimumCoreHalfWidth<minimumEdgeInnerHalfWidth
                        && minimumEdgeInnerHalfWidth<minimumGlowHalfWidth)
                    || !(maximumCoreHalfWidth<maximumEdgeInnerHalfWidth
                        && maximumEdgeInnerHalfWidth<maximumGlowHalfWidth))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh visual profile regression");
                visualProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_VISUAL_PROFILE_VERIFIED glowScale={} coreScale={} edgeHalfWidth={} pulseMin={} pulseMax={} coreInsideEdgeBand=true",
                    GROUND_GLOW_WIDTH_SCALE,GROUND_CORE_WIDTH_SCALE,GROUND_EDGE_HALF_WIDTH,
                    minimumHalfWidth,maximumHalfWidth);
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_EDGE_PALETTE_VERIFIED red={} green={} blue={}",
                    GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE);
            }
            if(!surfaceHeightProfileLogged) {
                double fullBlock=groundSurfaceOffset(1.0D,Double.NaN);
                double tallGrass=groundSurfaceOffset(1.0D,Double.NaN);
                double snowLayer=groundSurfaceOffset(1.0D,.125D);
                double bottomSlab=groundSurfaceOffset(.5D,Double.NaN);
                double invalidCover=groundSurfaceOffset(.75D,Double.POSITIVE_INFINITY);
                if(Math.abs(fullBlock-1.0D)>1.0E-9D
                    || Math.abs(tallGrass-1.0D)>1.0E-9D
                    || Math.abs(snowLayer-1.125D)>1.0E-9D
                    || Math.abs(bottomSlab-.5D)>1.0E-9D
                    || Math.abs(invalidCover-.75D)>1.0E-9D)
                    throw new IllegalStateException("BLACK FREQUENCY ground surface collision-height regression");
                surfaceHeightProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_SURFACE_HEIGHT_VERIFIED fullBlock={} tallGrass={} snowLayer={} bottomSlab={} invalidFallback={}",
                    fullBlock,tallGrass,snowLayer,bottomSlab,invalidCover);
            }
        }

        private static double boundedShapeHeight(double height,double fallback) {
            return Double.isFinite(height)
                ? Math.max(0.0D,Math.min(1.0D,height))
                : fallback;
        }

        // Non-colliding plants have an outline shape but no physical top surface.
        // Only a collision-bearing cover may raise the fissure above its support.
        private static double groundSurfaceOffset(double supportHeight,double coverCollisionHeight) {
            double supportTop=boundedShapeHeight(supportHeight,1.0D);
            if(!Double.isFinite(coverCollisionHeight)) return supportTop;
            return 1.0D+boundedShapeHeight(coverCollisionHeight,0.0D);
        }

        // Adjacent samples may be connected only when their real support heights
        // describe a walkable terrain transition. Large jumps are a gap, not a wall.
        private static boolean isContinuousGroundStep(double fromY,double toY) {
            return Double.isFinite(fromY) && Double.isFinite(toY)
                && Math.abs(toY-fromY)<=MAX_GROUND_SEGMENT_Y_DELTA;
        }

        private static void verifyGroundContinuityProfile() {
            if(continuityProfileLogged) return;
            boolean flat=isContinuousGroundStep(64.0D,64.0D);
            boolean oneBlockStep=isContinuousGroundStep(64.0D,65.0D);
            boolean snowStep=isContinuousGroundStep(64.0D,65.125D);
            boolean cliff=isContinuousGroundStep(64.0D,65.5D);
            boolean invalid=isContinuousGroundStep(64.0D,Double.NaN)
                || isContinuousGroundStep(Double.POSITIVE_INFINITY,64.0D);
            if(!(flat && oneBlockStep && snowStep && !cliff && !invalid))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh continuity regression");
            continuityProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CONTINUITY_VERIFIED maxStep={}",
                MAX_GROUND_SEGMENT_Y_DELTA);
        }

        private static boolean shouldRebuild(long tick,long previousTick,boolean anchorChanged,
                int referenceY,int previousReferenceY) {
            return anchorChanged || previousTick==Long.MIN_VALUE || tick<previousTick
                || tick-previousTick>=REBUILD_INTERVAL
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
            if(!(stable && oneBlockStable && twoBlockMove && teleport && sixTickStable
                && beforeInterval && interval && anchor))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh rebuild profile regression");
            rebuildProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_REBUILD_PROFILE_VERIFIED verticalDelta={} interval={} boundaryVerified={}",
                GROUND_REBUILD_VERTICAL_DELTA,REBUILD_INTERVAL,sixTickStable && beforeInterval && interval);
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
            if(!(naturalGrass && naturalStone && rejectsStructures))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh terrain/structure filter regression");
            supportFilterLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_STRUCTURAL_SUPPORT_FILTER_VERIFIED");
        }

        private static boolean isGroundSupport(BlockState state) {
            return state.blocksMotion()
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
                // an otherwise valid ground sample in forests.
                if(surface.blocksMotion() || !surface.getFluidState().isEmpty()) continue;
                var coverCollision=surface.getCollisionShape(level,cursor);
                double coverHeight=coverCollision.isEmpty()
                    ? Double.NaN
                    : coverCollision.max(Direction.Axis.Y);

                cursor.set(blockX,candidateY+1,blockZ);
                BlockState head=level.getBlockState(cursor);
                if(!head.getFluidState().isEmpty()) continue;

                double surfaceOffset=groundSurfaceOffset(supportHeight,coverHeight);
                return candidateY-1+surfaceOffset+.035D;
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
            verifySurfaceSearchOrder();
            verifyTaperProfile();
            verifyGroundContinuityProfile();
            verifyGroundRebuildProfile();
            verifyGroundRenderRangeProfile();
            verifyGroundAnimationProfile();
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
            for(int arm=0;arm<ARM_COUNT;arm++) {
                int armStart=result.size();
                double angle=arm*Math.PI/2.0D;
                GroundPoint previous=null;
                for(int segment=1;segment<=SEGMENTS_PER_ARM;segment++) {
                    double distance=segment*SAMPLE_SPACING;
                    double bend=Math.sin(segment*.85D+arm*1.1D)*.18D;
                    double x=worldAnchorX+.5D+Math.cos(angle)*distance-Math.sin(angle)*bend;
                    double z=worldAnchorZ+.5D+Math.sin(angle)*distance+Math.cos(angle)*bend;
                    int blockX=(int)Math.floor(x);
                    int blockZ=(int)Math.floor(z);
                    long dx=blockX-(long)worldAnchorX;
                    long dz=blockZ-(long)worldAnchorZ;
                    if(dx*dx+dz*dz>(long)RADIUS*RADIUS) {
                        previous=null;
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
                        previous=null;
                        continue;
                    }
                    if(previous!=null) {
                        double tangentX=current.x()-previous.x();
                        double tangentZ=current.z()-previous.z();
                        double length=Math.sqrt(tangentX*tangentX+tangentZ*tangentZ);
                        if(length>1.0E-5D) {
                            if(isContinuousGroundStep(previous.y(),current.y())) {
                                result.add(new GroundSegment(previous,current,
                                    -tangentZ/length,tangentX/length,arm*1.7D+segment*.63D,
                                    endpointTaper(segment-1),endpointTaper(segment)));
                            } else {
                                // Start the next ribbon at the new surface point,
                                // but leave this cliff transition visibly open.
                                steepStepBreaks++;
                            }
                        }
                    }
                    previous=current;
                }
                if(result.size()>armStart) activeArms++;
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

        private static void vertex(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                double x,double y,double z,float red,float green,float blue,float alpha) {
            builder.vertex(matrix,(float)(x-camera.x),(float)(y-camera.y),(float)(z-camera.z))
                .color(colorChannel(red),colorChannel(green),colorChannel(blue),colorChannel(alpha))
                .endVertex();
        }

        private static void ribbon(BufferBuilder builder,Matrix4f matrix,Vec3 camera,
                GroundSegment segment,double offset,double halfWidth,
                float red,float green,float blue,float alpha) {
            GroundPoint a=segment.a();
            GroundPoint b=segment.b();
            // Scale the offset and half-width together so glow, dark core and
            // bright edge rails converge at the same tapered endpoint.
            double leftA=(offset+halfWidth)*segment.taperA();
            double rightA=(offset-halfWidth)*segment.taperA();
            double leftB=(offset+halfWidth)*segment.taperB();
            double rightB=(offset-halfWidth)*segment.taperB();
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y(),a.z()+segment.normalZ()*leftA,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftB,b.y(),b.z()+segment.normalZ()*leftB,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightB,b.y(),b.z()+segment.normalZ()*rightB,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y(),a.z()+segment.normalZ()*rightA,
                red,green,blue,alpha);
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
                new GroundPoint(2,64,2),1,0,0,1,1));
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
                new GroundPoint(2,64,2),1,0,0,1,1));
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
            if(!level.getWorldBorder().isWithinBounds(
                    new BlockPos(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ))
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
                for(GroundSegment segment:cachedSegments) {
                    double wave=.5D+.5D*Math.sin(time*.14D+segment.phase());
                    // A slow travelling pulse changes width independently along
                    // the seam; endpoint taper keeps each arm from ending bluntly.
                    double widthFlicker=GROUND_WIDTH_FLICKER*Math.sin(time*.055D+segment.phase()*1.9D);
                    double halfWidth=Math.max(GROUND_MIN_HALF_WIDTH,
                        GROUND_BASE_HALF_WIDTH+GROUND_WAVE_HALF_WIDTH_RANGE*wave+widthFlicker);
                    float pulse=(float)(.78D+.22D*Math.sin(time*.19D+segment.phase()));
                    float alpha=boundedStrength*pulse;
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_GLOW_WIDTH_SCALE,
                        .20F,.025F,.43F,alpha*.34F);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*GROUND_CORE_WIDTH_SCALE,
                        .008F,.001F,.022F,alpha*.94F);
                    float corePulse=(float)boundedCorePulse(wave);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*.16D,
                        .28F,.015F,.62F,alpha*corePulse);
                    float edgeAlpha=Math.min(1.0F,boundedStrength*(.94F*pulse+.06F));
                    ribbon(builder,matrix,camera,segment,halfWidth*1.9D,GROUND_EDGE_HALF_WIDTH,
                        GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE,edgeAlpha);
                    ribbon(builder,matrix,camera,segment,-halfWidth*1.9D,GROUND_EDGE_HALF_WIDTH,
                        GROUND_EDGE_RED,GROUND_EDGE_GREEN,GROUND_EDGE_BLUE,edgeAlpha);
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
