package com.blackfrequency.rift.client;

import com.blackfrequency.rift.BlackFrequency;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    private static final float MIN_VISIBLE_STRENGTH=0.0001f;
    private static ShaderInstance shader;
    private static boolean firstVisibleFrameLogged;

    private static boolean shouldRender(float strength) {
        // Match the fragment shader's transparent early-out exactly. Avoid
        // rebuilding and submitting a fullscreen quad while the rift is invisible.
        return Float.isFinite(strength) && strength>MIN_VISIBLE_STRENGTH;
    }

    private static void verifyRenderThreshold() {
        if(shouldRender(0.0f) || shouldRender(MIN_VISIBLE_STRENGTH)
            || !shouldRender(MIN_VISIBLE_STRENGTH+0.00001f)
            || shouldRender(Float.NaN) || shouldRender(Float.POSITIVE_INFINITY)
            || shouldRender(Float.NEGATIVE_INFINITY))
            throw new IllegalStateException("BLACK FREQUENCY sky render threshold regression");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST SKY_RENDER_THRESHOLD_VERIFIED");
    }

    @Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
    public static final class Registration {
        @SubscribeEvent
        public static void shaders(RegisterShadersEvent event) throws IOException {
            verifyRenderThreshold();
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
            if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                RiftClientState.GroundRenderer.render(event);
                return;
            }
            if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY || shader==null) return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null || mc.player==null) return;
            // A stale Overworld packet must not draw the fracture in other dimensions.
            if(!mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
            float strength=RiftClientState.visibleStrength();
            // Reject corrupted client state before sending it to the GPU, and
            // skip the draw entirely while the shader would output transparency.
            if(!shouldRender(strength)) return;
            strength=Math.min(strength,1f);
            if(!firstVisibleFrameLogged) {
                firstVisibleFrameLogged=true;
                com.mojang.logging.LogUtils.getLogger().info("BLACK_FREQUENCY_RENDER_ACTIVE strength={}",strength);
            }
            if(shader.getUniform("RiftStrength")!=null)shader.getUniform("RiftStrength").set(strength);
            if(shader.getUniform("GreenPhase")!=null)shader.getUniform("GreenPhase").set(RiftClientState.greenPhase());
            if(shader.getUniform("GameTime")!=null)shader.getUniform("GameTime").set((float)(mc.level.getGameTime()%100000L));
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            // The camera is INSIDE the sky cube; back-face culling would hide its walls.
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderColor(1f,1f,1f,1f);
            // A single screen-space quad avoids overlapping translucent sky-cube
            // faces. The vertex shader reconstructs the world-space view ray.
            BufferBuilder bb=Tesselator.getInstance().getBuilder();
            bb.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
            bb.vertex(-1f,-1f,0f).endVertex();
            bb.vertex( 1f,-1f,0f).endVertex();
            bb.vertex( 1f, 1f,0f).endVertex();
            bb.vertex(-1f, 1f,0f).endVertex();
            BufferUploader.drawWithShader(bb.end());
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }
}
