package com.blackfrequency.rift.client;

import com.blackfrequency.rift.BlackFrequency;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.IOException;
import java.util.Objects;
import org.lwjgl.opengl.GL11;

public final class RiftSkyRenderer {
    private static final ResourceLocation SKY_RIFT_SHADER_ID=Objects.requireNonNull(
        ResourceLocation.tryBuild("blackfrequency","sky_rift"),"Invalid BLACK FREQUENCY sky shader id");
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
    private static boolean firstDepthAwareFrameLogged;
    private static boolean firstDepthStateCleanupLogged;
    private static final boolean CI_TEST_MODE=Boolean.getBoolean("blackfrequency.ciTestMode");
    private static float lastCiCameraYaw=Float.NaN;
    private static float lastCiCameraPitch=Float.NaN;

    private static boolean shouldRender(float strength) {
        // Match the fragment shader's transparent early-out exactly. Avoid
        // rebuilding and submitting a fullscreen quad while the rift is invisible.
        return Float.isFinite(strength) && strength>MIN_VISIBLE_STRENGTH;
    }

    // Cloud suppression must never hide the vanilla sky when shader loading fails.
    public static boolean isShaderReady() {
        return shader!=null;
    }

    private static float wrappedDegrees(float angle) {
        float wrapped=angle%360.0F;
        if(wrapped>=180.0F) wrapped-=360.0F;
        if(wrapped< -180.0F) wrapped+=360.0F;
        return wrapped;
    }

    // CI capture must use the camera rotation that the client actually rendered,
    // not the server's requested teleport aim (which may arrive a tick later).
    // This is disabled in normal play and logs only when the view changes.
    private static void logCiCameraRotation(Camera camera) {
        if(!CI_TEST_MODE || camera==null) return;
        float yaw=camera.getYRot();
        float pitch=camera.getXRot();
        if(!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        boolean changed=!Float.isFinite(lastCiCameraYaw)
            || Math.abs(wrappedDegrees(yaw-lastCiCameraYaw))>=0.25F
            || Math.abs(pitch-lastCiCameraPitch)>=0.25F;
        if(!changed) return;
        lastCiCameraYaw=yaw;
        lastCiCameraPitch=pitch;
        com.mojang.logging.LogUtils.getLogger().info(
            "BLACK_FREQUENCY_TEST CLIENT_CAMERA_ROTATION yaw={} pitch={}",yaw,pitch);
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
                    SKY_RIFT_SHADER_ID, DefaultVertexFormat.POSITION),
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
            // Clouds are submitted after the solid-block stage in Forge 1.20.1.
            // Render after weather so the far-plane quad can tint the completed sky,
            // while terrain and entities already in the depth buffer still occlude it.
            // The depth mask remains disabled for this pass and is restored in finally.
            if(event.getStage()==RenderLevelStageEvent.Stage.AFTER_WEATHER)
                renderRift(event);
        }

        private static void renderRift(RenderLevelStageEvent event) {
            if(shader==null) return;
            Minecraft mc=Minecraft.getInstance();
            if(mc.level==null || mc.player==null) return;
            // A stale Overworld packet must not draw the fracture in other dimensions.
            if(!mc.level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
            float strength=RiftClientState.visibleStrength();
            // Reject corrupted client state before sending it to the GPU, and
            // skip the draw entirely while the shader would output transparency.
            if(!shouldRender(strength)) return;
            strength=Math.min(strength,1f);
            logCiCameraRotation(event.getCamera());
            if(!firstVisibleFrameLogged) {
                firstVisibleFrameLogged=true;
                com.mojang.logging.LogUtils.getLogger().info("BLACK_FREQUENCY_RENDER_ACTIVE strength={}",strength);
            }
            boolean depthAwareDrawSubmitted=false;
            if(shader.getUniform("RiftStrength")!=null)shader.getUniform("RiftStrength").set(strength);
            if(shader.getUniform("GreenPhase")!=null)shader.getUniform("GreenPhase").set(RiftClientState.greenPhase());
            if(shader.getUniform("GameTime")!=null)
                shader.getUniform("GameTime").set(interpolatedGameTime(mc.level.getGameTime(),event.getPartialTick()));
            // Rendering is allowed to fail (for example, during a resource reload),
            // but it must never leave the next world pass with depth writes disabled.
            // Keep every state change inside the guarded region so cleanup also runs
            // if shader binding, buffer construction, or upload throws.
            try {
                // The shader JSON owns blending. Keep the fullscreen quad counter-clockwise
                // so the normal back-face culling state remains untouched.
                // The quad is on the far plane: LEQUAL admits untouched sky pixels
                // while the depth buffer rejects solid terrain and nearer geometry.
                RenderSystem.enableDepthTest();
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.depthMask(false);
                RenderSystem.setShader(() -> shader);
                // A single screen-space quad avoids overlapping translucent sky-cube
                // faces. The vertex shader reconstructs the world-space view ray.
                BufferBuilder bb=Tesselator.getInstance().getBuilder();
                bb.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION);
                bb.vertex(-1f,-1f,0f).endVertex();
                bb.vertex( 1f,-1f,0f).endVertex();
                bb.vertex( 1f, 1f,0f).endVertex();
                bb.vertex(-1f, 1f,0f).endVertex();
                BufferUploader.drawWithShader(bb.end());
                depthAwareDrawSubmitted=true;
            } finally {
                // Restore the vanilla post-level depth contract. Blend and cull
                // state are owned by the shader/pipeline and are never toggled here.
                RenderSystem.depthMask(true);
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableDepthTest();
            }
            if(CI_TEST_MODE && depthAwareDrawSubmitted) {
                // Keep checking every CI frame, but emit the success marker only once.
                if(!GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    || GL11.glGetInteger(GL11.GL_DEPTH_FUNC)!=GL11.GL_LEQUAL
                    || !GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK))
                    throw new IllegalStateException("BLACK FREQUENCY sky depth state cleanup regression");
                if(!firstDepthStateCleanupLogged) {
                    firstDepthStateCleanupLogged=true;
                    com.mojang.logging.LogUtils.getLogger().info(
                        "BLACK_FREQUENCY_TEST SKY_DEPTH_STATE_CLEANUP_VERIFIED");
                }
            }
            // The smoke-test marker means the draw call returned and the depth
            // contract was restored; entering AFTER_SKY alone is not a successful pass.
            if(CI_TEST_MODE && depthAwareDrawSubmitted && !firstDepthAwareFrameLogged) {
                firstDepthAwareFrameLogged=true;
                com.mojang.logging.LogUtils.getLogger().info(
                    "BLACK_FREQUENCY_TEST SKY_DEPTH_AWARE_DRAW_SUBMITTED stage=after_weather");
            }
        }
    }
}
