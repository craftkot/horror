package com.blackfrequency.rift.client;

import com.blackfrequency.rift.BlackFrequency;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;

public final class RiftSkyRenderer {
    private static ShaderInstance shader;
    private static boolean firstVisibleFrameLogged;

    @Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void shaders(RegisterShadersEvent event) throws IOException {
            event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                    new ResourceLocation("blackfrequency","sky_rift"), DefaultVertexFormat.POSITION),
                loaded -> {
                    shader=loaded;
                    com.mojang.logging.LogUtils.getLogger().info("BLACK_FREQUENCY_SHADER_READY sky_rift");
                });
        }
    }

    @Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.FORGE, value=Dist.CLIENT)
    public static final class RenderEvents {
        @SubscribeEvent
        public static void sky(RenderLevelStageEvent event) {
            if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY || shader==null) return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null || mc.player==null) return;
            // A stale Overworld packet must not draw the fracture in other dimensions.
            if(!mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
            float strength=RiftClientState.visibleStrength();
            if(strength<=0) return;
            if(!firstVisibleFrameLogged) {
                firstVisibleFrameLogged=true;
                com.mojang.logging.LogUtils.getLogger().info("BLACK_FREQUENCY_RENDER_ACTIVE strength={}",strength);
            }
            if(shader.getUniform("RiftStrength")!=null)shader.getUniform("RiftStrength").set(strength);
            if(shader.getUniform("GreenPhase")!=null)shader.getUniform("GreenPhase").set(RiftClientState.greenPhase());
            if(shader.getUniform("GameTime")!=null)shader.getUniform("GameTime").set((float)(mc.level.getGameTime()%100000L));
            PoseStack stack=event.getPoseStack();
            stack.pushPose();
            // Rendered relative to the camera, so it stays on the sky instead of moving with terrain.
            // Forge's level pose stack is already camera-relative; adding camera coordinates breaks sky alignment.
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            // The camera is INSIDE the sky cube; back-face culling would hide its walls.
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(1f,1f,1f,1f);
            float r=100f;
            BufferBuilder bb=Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
            // Four walls and roof of an inside-facing sky cube.
            // The first corner must be distinct; a duplicate collapsed this wall into a triangle.
            face(bb,stack,-r,-r,-r, r,-r,-r, r,r,-r, -r,r,-r);
            face(bb,stack,-r,-r,r, -r,-r,-r, -r,r,-r, -r,r,r);
            face(bb,stack,r,-r,-r, r,-r,r, r,r,r, r,r,-r);
            face(bb,stack,r,-r,r, -r,-r,r, -r,r,r, r,r,r);
            face(bb,stack,-r,r,-r, r,r,-r, r,r,r, -r,r,r);
            // Close the underside too: the rift should remain visible when the
            // player looks down into exposed voids, without touching terrain.
            face(bb,stack,-r,-r,r, r,-r,r, r,-r,-r, -r,-r,-r);
            BufferUploader.drawWithShader(bb.end());
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            stack.popPose();
        }
        private static void face(BufferBuilder b,PoseStack p,
            float x1,float y1,float z1,float x2,float y2,float z2,
            float x3,float y3,float z3,float x4,float y4,float z4){
            var pose=p.last().pose();
            b.vertex(pose,x1,y1,z1).endVertex();
            b.vertex(pose,x2,y2,z2).endVertex();
            b.vertex(pose,x3,y3,z3).endVertex();
            b.vertex(pose,x4,y4,z4).endVertex();
        }
    }
}
