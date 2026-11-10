package com.blackfrequency.rift.client;

import java.util.ArrayList;
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
        private static final int RADIUS=48;
        private static final int ARM_COUNT=4;
        private static final int SEGMENTS_PER_ARM=12;
        private static final double SAMPLE_SPACING=.39D;
        private static final long REBUILD_INTERVAL=4L;
        private static net.minecraft.client.multiplayer.ClientLevel cachedLevel;
        private static long cachedTick=Long.MIN_VALUE;
        private static int cachedAnchorX=Integer.MIN_VALUE;
        private static int cachedAnchorZ=Integer.MIN_VALUE;
        private static int cachedAnchorSurfaceY;
        private static List<GroundSegment> cachedSegments=List.of();
        private static int cachedArmCount;
        private static final int MIN_RENDERED_SEGMENTS=8;
        private static final int MIN_RENDERED_ARMS=2;
        private static boolean firstRenderLogged;
        private static boolean pendingRenderLogged;
        private static boolean renderEventLogged;
        private static boolean emptyMeshLogged;
        private static boolean taperProfileLogged;
        private static boolean surfaceWindowLogged;
        private static boolean rangeRejectLogged;

        private record GroundPoint(double x,double y,double z) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,
                double phase,double taperA,double taperB) {}
        private record GroundMesh(List<GroundSegment> segments,int activeArms) {}

        private GroundRenderer() {}

        // Fade both ends of each arm over three samples. The connected ribbons
        // become narrow tips instead of ending as uniform rectangular strips.
        private static double endpointTaper(int sample) {
            double ramp=Math.min(1.0,sample/3.0);
            double tail=Math.min(1.0,(SEGMENTS_PER_ARM-sample+1)/3.0);
            return .5D+.5D*Math.min(ramp,tail);
        }

        private static void verifyTaperProfile() {
            if(taperProfileLogged) return;
            double tip=endpointTaper(1);
            double middle=endpointTaper(SEGMENTS_PER_ARM/2);
            double end=endpointTaper(SEGMENTS_PER_ARM);
            if(!(tip<middle && end<middle && Math.abs(tip-end)<1.0E-9D))
                throw new IllegalStateException("BLACK FREQUENCY ground mesh taper regression");
            taperProfileLogged=true;
            com.mojang.logging.LogUtils.getLogger().info(
                "BLACK_FREQUENCY_GROUND_MESH_TAPER_VERIFIED tip={} middle={} end={}",tip,middle,end);
        }

        private static boolean isGroundSupport(BlockState state) {
            return state.blocksMotion()
                && !state.is(BlockTags.LOGS)
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.PLANKS)
                && !state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW);
        }

        private static GroundPoint sampleGround(net.minecraft.client.multiplayer.ClientLevel level,
                int blockX,int blockZ,double x,double z,int referenceY) {
            if(!level.getWorldBorder().isWithinBounds(
                    new BlockPos(blockX,level.getMinBuildHeight(),blockZ))
                || !level.hasChunk(blockX>>4,blockZ>>4)) return null;

            // A no-leaves heightmap can still select the top of a tall tree trunk.
            // Search a bounded window around the nearby player's actual height instead:
            // this finds the ground below canopies without scanning an entire column.
            int maxSurfaceY=Math.min(referenceY+8,level.getMaxBuildHeight()-2);
            int minSurfaceY=Math.max(level.getMinBuildHeight()+1,referenceY-32);
            for(int candidateY=maxSurfaceY;candidateY>=minSurfaceY;candidateY--) {
                BlockPos supportPos=new BlockPos(blockX,candidateY-1,blockZ);
                BlockState support=level.getBlockState(supportPos);
                if(!isGroundSupport(support) || !support.getFluidState().isEmpty()) continue;

                BlockPos surfacePos=new BlockPos(blockX,candidateY,blockZ);
                BlockState surface=level.getBlockState(surfacePos);
                BlockState head=level.getBlockState(surfacePos.above());
                // The ribbon is coplanar with the ground. Canopy and low overhangs
                // above it should occlude naturally through depth testing, not reject
                // an otherwise valid ground sample in forests.
                if(surface.blocksMotion()
                    || !surface.getFluidState().isEmpty() || !head.getFluidState().isEmpty()) continue;

                double shapeHeight=surface.isAir()
                    ? support.getShape(level,supportPos).max(Direction.Axis.Y)
                    : surface.getShape(level,surfacePos).max(Direction.Axis.Y);
                if(!Double.isFinite(shapeHeight)) shapeHeight=0.0D;
                shapeHeight=Math.max(0.0D,Math.min(1.0D,shapeHeight));
                double baseY=surface.isAir()?candidateY-1:candidateY;
                return new GroundPoint(x,baseY+shapeHeight+.035D,z);
            }
            return null;
        }

        private static GroundMesh buildSegments(
                net.minecraft.client.multiplayer.ClientLevel level,int worldAnchorX,int worldAnchorZ,
                int referenceY) {
            verifyTaperProfile();
            List<GroundSegment> result=new ArrayList<>(ARM_COUNT*(SEGMENTS_PER_ARM-1));
            int activeArms=0;
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
                    GroundPoint current=sampleGround(level,blockX,blockZ,x,z,referenceY);
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
                            result.add(new GroundSegment(previous,current,
                                -tangentZ/length,tangentX/length,arm*1.7D+segment*.63D,
                                endpointTaper(segment-1),endpointTaper(segment)));
                        }
                    }
                    previous=current;
                }
                if(result.size()>armStart) activeArms++;
            }
            return new GroundMesh(List.copyOf(result),activeArms);
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
            cachedSegments=List.of();
            cachedArmCount=0;
            firstRenderLogged=false;
            pendingRenderLogged=false;
            renderEventLogged=false;
            emptyMeshLogged=false;
            taperProfileLogged=false;
            surfaceWindowLogged=false;
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
            if(anchorChanged) emptyMeshLogged=false;
            if(anchorChanged || cachedTick==Long.MIN_VALUE || tick<cachedTick || tick-cachedTick>=REBUILD_INTERVAL) {
                int surfaceReferenceY=player.blockPosition().getY();
                GroundMesh mesh=buildSegments(level,worldAnchorX,worldAnchorZ,surfaceReferenceY);
                cachedSegments=mesh.segments();
                cachedArmCount=mesh.activeArms();
                cachedAnchorSurfaceY=estimateAnchorSurfaceY(cachedSegments,
                    worldAnchorX,worldAnchorZ,surfaceReferenceY);
                if(!surfaceWindowLogged) {
                    surfaceWindowLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_GROUND_MESH_SURFACE_WINDOW referenceY={} topOffset=8 bottomOffset=32 segments={} arms={}",
                        surfaceReferenceY,cachedSegments.size(),cachedArmCount);
                }
                cachedLevel=level;
                cachedAnchorX=worldAnchorX;
                cachedAnchorZ=worldAnchorZ;
                cachedTick=tick;
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
                    double widthFlicker=.003D*Math.sin(time*.055D+segment.phase()*1.9D);
                    double halfWidth=Math.max(.038D,.042D+.014D*wave+widthFlicker);
                    float pulse=(float)(.78D+.22D*Math.sin(time*.19D+segment.phase()));
                    float alpha=boundedStrength*pulse;
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*3.6D,
                        .20F,.025F,.43F,alpha*.34F);
                    ribbon(builder,matrix,camera,segment,0.0D,halfWidth*.48D,
                        .008F,.001F,.022F,alpha*.94F);
                    float edgeAlpha=Math.min(1.0F,boundedStrength*(.94F*pulse+.06F));
                    ribbon(builder,matrix,camera,segment,halfWidth*1.9D,.012D,
                        .78F,.18F,1.0F,edgeAlpha);
                    ribbon(builder,matrix,camera,segment,-halfWidth*1.9D,.012D,
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
                    "BLACK_FREQUENCY_GROUND_MESH_RENDERED segments={} arms={} anchorX={} anchorZ={}",
                    cachedSegments.size(),cachedArmCount,worldAnchorX,worldAnchorZ);
            }
        }
    }

}
