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
    // All shader time multipliers are multiples of 0.005. This wrap is
    // four near-complete cycles of the base 0.00006 rad/tick phase.
    // The measured worst float32 phase seam across all shader harmonics is < 0.002 rad.
    private static final long SKY_TIME_WRAP_TICKS=418879L;
    private static final long SKY_TIME_WRAP_CYCLES=4L;
    private static final float SKY_TIME_TICK_SCALE=0.012F;
    private static final float SKY_TIME_MULTIPLIER_QUANTUM=0.005F;
    private static final double SKY_MAX_WRAP_PHASE_ERROR=0.002D;
    private static ShaderInstance shader;
    private static boolean firstVisibleFrameLogged;

    private static boolean shouldRender(float strength) {
        // Match the fragment shader's transparent early-out exactly. Avoid
        // rebuilding and submitting a fullscreen quad while the rift is invisible.
        return Float.isFinite(strength) && strength>MIN_VISIBLE_STRENGTH;
    }

    // Interpolate shader time between simulation ticks so the sky tear animates
    // at render rate instead of stepping visibly at 20 updates per second.
    private static float interpolatedGameTime(long gameTime,float partialTick) {
        float safePartial=Float.isFinite(partialTick)
            ? Math.max(0.0F,Math.min(1.0F,partialTick))
            : 0.0F;
        return (float)Math.floorMod(gameTime,SKY_TIME_WRAP_TICKS)+safePartial;
    }

    private static double shaderWrapPhaseError(float shaderTime,float multiplier) {
        int harmonic=Math.round(multiplier/SKY_TIME_MULTIPLIER_QUANTUM);
        double expected=harmonic*SKY_TIME_WRAP_CYCLES*2.0D*Math.PI;
        float actual=shaderTime*multiplier;
        return Math.abs(Math.IEEEremainder((double)actual-expected,2.0D*Math.PI));
    }

    private static double maximumShaderWrapPhaseError() {
        float wrappedShaderTime=(float)SKY_TIME_WRAP_TICKS*SKY_TIME_TICK_SCALE;
        float[] multipliers={.09F,.13F,.21F,.61F,.43F,.73F,.17F,.27F,.5F,
            .075F,.39F,.19F,.12F,.06F,.37F,.51F,.18F,.7F,.11F,.31F,.57F,
            2.1F,2.7F,5.3F,3.1F,.4F};
        double maximum=0.0D;
        for(float multiplier:multipliers)
            maximum=Math.max(maximum,shaderWrapPhaseError(wrappedShaderTime,multiplier));
        // branchSurge uses a per-branch frequency: .49 + .07 * i, i in [0,4].
        for(int i=0;i<5;i++) {
            float multiplier=.49F+.07F*i;
            maximum=Math.max(maximum,shaderWrapPhaseError(wrappedShaderTime,multiplier));
        }
        return maximum;
    }

    private static void verifyRenderThreshold() {
        if(shouldRender(0.0f) || shouldRender(MIN_VISIBLE_STRENGTH)
            || !shouldRender(MIN_VISIBLE_STRENGTH+0.00001f)
            || shouldRender(Float.NaN) || shouldRender(Float.POSITIVE_INFINITY)
            || shouldRender(Float.NEGATIVE_INFINITY))
            throw new IllegalStateException("BLACK FREQUENCY sky render threshold regression");
        float midpoint=interpolatedGameTime(42L,.5F);
        float tickBoundary=interpolatedGameTime(42L,1.0F);
        float wrapped=interpolatedGameTime(SKY_TIME_WRAP_TICKS,.25F);
        float negative=interpolatedGameTime(-1L,.5F);
        float invalid=interpolatedGameTime(42L,Float.NaN);
        float clamped=interpolatedGameTime(42L,1.5F);
        double maximumWrapError=maximumShaderWrapPhaseError();
        float beforeWrap=interpolatedGameTime(SKY_TIME_WRAP_TICKS-1L,1.0F);
        float afterWrap=interpolatedGameTime(SKY_TIME_WRAP_TICKS,0.0F);
        if(beforeWrap!=SKY_TIME_WRAP_TICKS || afterWrap!=0.0F
            || Math.abs(midpoint-42.5F)>1.0E-4F
            || Math.abs(tickBoundary-43.0F)>1.0E-4F
            || Math.abs(wrapped-.25F)>1.0E-4F
            || Math.abs(negative-(SKY_TIME_WRAP_TICKS-.5F))>1.0E-4F
            || Math.abs(invalid-42.0F)>1.0E-4F
            || Math.abs(clamped-43.0F)>1.0E-4F
            || !Double.isFinite(maximumWrapError)
            || maximumWrapError>SKY_MAX_WRAP_PHASE_ERROR)
            throw new IllegalStateException("BLACK FREQUENCY sky animation interpolation regression");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST SKY_RENDER_THRESHOLD_VERIFIED");
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST SKY_ANIMATION_INTERPOLATION_VERIFIED period={}",SKY_TIME_WRAP_TICKS);
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST SKY_ANIMATION_WRAP_CONTINUITY_VERIFIED cycles={} maxPhaseErrorRadians={}",
            SKY_TIME_WRAP_CYCLES,maximumWrapError);
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
            if(shader.getUniform("GameTime")!=null)
                shader.getUniform("GameTime").set(interpolatedGameTime(mc.level.getGameTime(),event.getPartialTick()));
            // Rendering is allowed to fail (for example, during a resource reload),
            // but it must never leave the next world pass with depth writes disabled.
            // Keep every state change inside the guarded region so cleanup also runs
            // if shader binding, buffer construction, or upload throws.
            try {
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
            } finally {
                RenderSystem.depthMask(true);
                RenderSystem.setShaderColor(1f,1f,1f,1f);
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
            }
        }
    }
}
