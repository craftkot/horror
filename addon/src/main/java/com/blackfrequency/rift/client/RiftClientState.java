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
import net.minecraft.world.level.levelgen.Heightmap;
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
    @Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.FORGE, value=Dist.CLIENT)
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
        private static List<GroundSegment> cachedSegments=List.of();
        private static boolean firstRenderLogged;

        private record GroundPoint(double x,double y,double z) {}
        private record GroundSegment(GroundPoint a,GroundPoint b,double normalX,double normalZ,double phase) {}

        private GroundRenderer() {}

        private static boolean isGroundSupport(BlockState state) {
            return state.blocksMotion()
                && !state.is(BlockTags.LOGS)
                && !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.PLANKS);
        }

        private static GroundPoint sampleGround(net.minecraft.client.multiplayer.ClientLevel level,
                int blockX,int blockZ,double x,double z) {
            if(!level.getWorldBorder().isWithinBounds(
                    new BlockPos(blockX,level.getMinBuildHeight(),blockZ))
                || !level.hasChunk(blockX>>4,blockZ>>4)) return null;
            int surfaceY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,blockX,blockZ);
            int min=level.getMinBuildHeight();
            boolean supported=false;
            for(int depth=0;depth<32 && surfaceY>min+1;depth++,surfaceY--) {
                if(isGroundSupport(level.getBlockState(new BlockPos(blockX,surfaceY-1,blockZ)))) {
                    supported=true;
                    break;
                }
            }
            if(!supported || surfaceY>=level.getMaxBuildHeight()) return null;
            BlockPos surfacePos=new BlockPos(blockX,surfaceY,blockZ);
            BlockState surface=level.getBlockState(surfacePos);
            double shapeHeight=surface.isAir()?0.0D
                :surface.getShape(level,surfacePos).max(Direction.Axis.Y);
            if(!Double.isFinite(shapeHeight)) shapeHeight=0.0D;
            shapeHeight=Math.max(0.0D,Math.min(1.0D,shapeHeight));
            return new GroundPoint(x,surfaceY+shapeHeight+.035D,z);
        }

        private static List<GroundSegment> buildSegments(
                net.minecraft.client.multiplayer.ClientLevel level,int worldAnchorX,int worldAnchorZ) {
            List<GroundSegment> result=new ArrayList<>(ARM_COUNT*(SEGMENTS_PER_ARM-1));
            for(int arm=0;arm<ARM_COUNT;arm++) {
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
                    GroundPoint current=sampleGround(level,blockX,blockZ,x,z);
                    if(current==null) {
                        // Never bridge a visual seam across an unloaded chunk or missing support.
                        previous=null;
                        continue;
                    }
                    if(previous!=null) {
                        double tangentX=current.x()-previous.x();
                        double tangentZ=current.z()-previous.z();
                        double length=Math.sqrt(tangentX*tangentX+tangentZ*tangentZ);
                        if(length>1.0E-5D) {
                            result.add(new GroundSegment(previous,current,
                                -tangentZ/length,tangentX/length,arm*1.7D+segment*.63D));
                        }
                    }
                    previous=current;
                }
            }
            return List.copyOf(result);
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
            double leftA=offset+halfWidth;
            double rightA=offset-halfWidth;
            vertex(builder,matrix,camera,a.x()+segment.normalX()*leftA,a.y(),a.z()+segment.normalZ()*leftA,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*leftA,b.y(),b.z()+segment.normalZ()*leftA,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,b.x()+segment.normalX()*rightA,b.y(),b.z()+segment.normalZ()*rightA,
                red,green,blue,alpha);
            vertex(builder,matrix,camera,a.x()+segment.normalX()*rightA,a.y(),a.z()+segment.normalZ()*rightA,
                red,green,blue,alpha);
        }

        private static void clearCache() {
            cachedLevel=null;
            cachedTick=Long.MIN_VALUE;
            cachedAnchorX=Integer.MIN_VALUE;
            cachedAnchorZ=Integer.MIN_VALUE;
            cachedSegments=List.of();
            firstRenderLogged=false;
        }

        @SubscribeEvent
        public static void render(RenderLevelStageEvent event) {
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
            if(!level.getWorldBorder().isWithinBounds(
                    new BlockPos(worldAnchorX,level.getMinBuildHeight(),worldAnchorZ))
                || !level.hasChunk(worldAnchorX>>4,worldAnchorZ>>4)) {
                cachedSegments=List.of();
                return;
            }
            int anchorSurfaceY=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                worldAnchorX,worldAnchorZ);
            double dy=player.getY()-anchorSurfaceY;
            if(dx*dx+dz*dz+dy*dy>64.0D*64.0D) {
                cachedSegments=List.of();
                return;
            }

            long tick=level.getGameTime();
            if(level!=cachedLevel || worldAnchorX!=cachedAnchorX || worldAnchorZ!=cachedAnchorZ
                || cachedTick==Long.MIN_VALUE || tick<cachedTick || tick-cachedTick>=REBUILD_INTERVAL) {
                cachedSegments=buildSegments(level,worldAnchorX,worldAnchorZ);
                cachedLevel=level;
                cachedAnchorX=worldAnchorX;
                cachedAnchorZ=worldAnchorZ;
                cachedTick=tick;
            }
            if(cachedSegments.isEmpty()) return;

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
                    double halfWidth=.042D+.014D*wave;
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
            if(!firstRenderLogged) {
                firstRenderLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_GROUND_MESH_RENDERED segments={} anchorX={} anchorZ={}",
                    cachedSegments.size(),worldAnchorX,worldAnchorZ);
            }
        }
    }

}
