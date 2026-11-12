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
        private static final Long2DoubleOpenHashMap SURFACE_Y_BY_COLUMN=
            new Long2DoubleOpenHashMap(ARM_COUNT*SEGMENTS_PER_ARM);
        private static final BlockPos.MutableBlockPos SURFACE_CURSOR=
            new BlockPos.MutableBlockPos(0,0,0);
        private static boolean sampleCacheReuseLogged;
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
        private static final double GROUND_GLOW_WIDTH_SCALE=4.0D;
        private static final double GROUND_CORE_WIDTH_SCALE=.66D;
        private static final double GROUND_EDGE_HALF_WIDTH=.020D;
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
        private static boolean supportFilterLogged;
        private static boolean sampleCursorReuseLogged;

        private record GroundPoint(double x,double y,double z) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                double phase,double taperA,double taperB) {}
        private record GroundMesh(List<GroundSegment> segments,int activeArms,int sampledColumns,int cacheHits,
                int steepStepBreaks) {}

        private GroundRenderer() {}

        // Fade both ends of each arm over three samples. The connected ribbons
        // become narrow tips instead of ending as uniform rectangular strips.
        private static double endpointTaper(int sample) {
            double ramp=Math.min(1.0,sample/3.0);
            double tail=Math.min(1.0,(SEGMENTS_PER_ARM-sample+1)/3.0);
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
                double tip=endpointTaper(1);
                double middle=endpointTaper(SEGMENTS_PER_ARM/2);
                double end=endpointTaper(SEGMENTS_PER_ARM);
                if(!(tip<middle && end<middle && Math.abs(tip-end)<1.0E-9D))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh taper regression");
                taperProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED tip={} middle={} end={}",tip,middle,end);
            }
            if(!visualProfileLogged) {
                if(!(GROUND_GLOW_WIDTH_SCALE>3.6D && GROUND_CORE_WIDTH_SCALE>.64D
                    && GROUND_EDGE_HALF_WIDTH>.018D && GROUND_EDGE_HALF_WIDTH<.045D))
                    throw new IllegalStateException("BLACK FREQUENCY ground mesh visual profile regression");
                visualProfileLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_VISUAL_PROFILE_VERIFIED glowScale={} coreScale={} edgeHalfWidth={}",
                    GROUND_GLOW_WIDTH_SCALE,GROUND_CORE_WIDTH_SCALE,GROUND_EDGE_HALF_WIDTH);
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
            if(Math.abs(minimum-.10D)>1.0E-9D
                || Math.abs(midpoint-.21D)>1.0E-9D
                || Math.abs(maximum-.32D)>1.0E-9D
                || Math.abs(invalid-minimum)>1.0E-9D
                || minimum<0.0D || maximum>1.0D)
                throw new IllegalStateException("BLACK FREQUENCY ground core pulse regression");
            animationProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_CORE_PULSE_VERIFIED min={} midpoint={} max={} widthScale={}",
                minimum,midpoint,maximum,.16D);
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
            for(int candidateY=maxSurfaceY;candidateY>=minSurfaceY;candidateY--) {
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
            SURFACE_Y_BY_COLUMN.put(key,Double.NaN);
            boolean cachedUnsupported=SURFACE_Y_BY_COLUMN.containsKey(key)
                && Double.isNaN(SURFACE_Y_BY_COLUMN.get(key));
            SURFACE_Y_BY_COLUMN.clear();
            boolean cleared=SURFACE_Y_BY_COLUMN.isEmpty();
            SURFACE_Y_BY_COLUMN.put(key,1.25D);
            boolean reused=SURFACE_Y_BY_COLUMN.containsKey(key)
                && Math.abs(SURFACE_Y_BY_COLUMN.get(key)-1.25D)<1.0E-9D;
            SURFACE_Y_BY_COLUMN.clear();
            if(!(cachedUnsupported && cleared && reused))
                throw new IllegalStateException("BLACK FREQUENCY reusable ground sample cache regression");
            sampleCacheReuseLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_SAMPLE_CACHE_REUSE_VERIFIED retained=true");
        }

        private static GroundMesh buildSegments(
                net.minecraft.client.multiplayer.ClientLevel level,int worldAnchorX,int worldAnchorZ,
                int referenceY) {
            verifyGroundMeshSamplingProfile();
            verifyTaperProfile();
            verifyGroundContinuityProfile();
            verifyGroundRebuildProfile();
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
                    if(SURFACE_Y_BY_COLUMN.containsKey(key)) {
                        // containsKey is required: NaN is a cached "unsupported"
                        // column, not a cache miss that should be sampled again.
                        sampledY=SURFACE_Y_BY_COLUMN.get(key);
                        cacheHits++;
                    } else {
                        sampledY=sampleGroundY(level,blockX,blockZ,referenceY,SURFACE_CURSOR);
                        surfaceSamples++;
                        SURFACE_Y_BY_COLUMN.put(key,sampledY);
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

        private static void clearCache() {
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
                || !hasAnchor()) return;
            float strength=visibleStrength();
            if(!Float.isFinite(strength) || strength<.08F) return;
            int worldAnchorX=anchorX();
            int worldAnchorZ=anchorZ();
            long dx=(long)player.blockPosition().getX()-worldAnchorX;
            long dz=(long)player.blockPosition().getZ()-worldAnchorZ;
            long horizontalDistanceSquared=dx*dx+dz*dz;
            if(horizontalDistanceSquared>64L*64L) return;
            if(!level.getWorldBorder().isWithinBounds(
                    new BlockPos(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ))
                || !level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4)) {
                clearCache();
                return;
            }

            long tick=level.getGameTime();
            boolean anchorChanged=level!=cachedLevel || worldAnchorX!=cachedAnchorX || worldAnchorZ!=cachedAnchorZ;
            int surfaceReferenceY=player.blockPosition().getY();
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
            double dy=player.getY()-cachedAnchorSurfaceY;
            double playerDistanceSquared=horizontalDistanceSquared+dy*dy;
            if(playerDistanceSquared>64.0D*64.0D) {
                if(!rangeRejectLogged) {
                    rangeRejectLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_RANGE_REJECT playerY={} sampledSurfaceY={} distanceSquared={}",
                        player.getY(),cachedAnchorSurfaceY,playerDistanceSquared);
                }
                return;
            }

            Matrix4f matrix=event.getPoseStack().last().pose();
            Vec3 camera=event.getCamera().getPosition();
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
                double time=tick+event.getPartialTick();
                float boundedStrength=Math.min(1.0F,strength);
                for(GroundSegment segment:cachedSegments) {
                    double wave=.5D+.5D*Math.sin(time*.14D+segment.phase());
                    // A slow travelling pulse changes width independently along
                    // the seam; endpoint taper keeps each arm from ending bluntly.
                    double widthFlicker=.004D*Math.sin(time*.055D+segment.phase()*1.9D);
                    double halfWidth=Math.max(.045D,.048D+.016D*wave+widthFlicker);
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
                        .78F,.18F,1.0F,edgeAlpha);
                    ribbon(builder,matrix,camera,segment,-halfWidth*1.9D,GROUND_EDGE_HALF_WIDTH,
                        .78F,.18F,1.0F,edgeAlpha);
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
