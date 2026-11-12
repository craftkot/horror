package com.blackfrequency.rift.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Keep the Overworld's normal effects, but remove its flat cloud layer while
 * the rift is visible so vanilla cloud polygons cannot hide the sky fracture.
 * This is a render-only override: player cloud settings and world data are untouched.
 */
@Mod.EventBusSubscriber(modid="blackfrequency", bus=Mod.EventBusSubscriber.Bus.MOD, value=Dist.CLIENT)
public final class RiftCloudEffects {
    private static final float MIN_VISIBLE_STRENGTH=0.0001F;
    private static final org.slf4j.Logger LOGGER=com.mojang.logging.LogUtils.getLogger();
    private static boolean policyVerified;
    private static boolean suppressionLogged;

    private RiftCloudEffects() {}

    @SubscribeEvent
    public static void register(RegisterDimensionSpecialEffectsEvent event) {
        verifySuppressionPolicy();
        event.register(new ResourceLocation("minecraft","overworld"),new RiftOverworldEffects());
    }

    private static boolean shouldSuppressVanillaClouds(float strength) {
        return Float.isFinite(strength) && strength>MIN_VISIBLE_STRENGTH;
    }

    private static void verifySuppressionPolicy() {
        if(policyVerified) return;
        if(shouldSuppressVanillaClouds(0.0F)
            || shouldSuppressVanillaClouds(MIN_VISIBLE_STRENGTH)
            || !shouldSuppressVanillaClouds(MIN_VISIBLE_STRENGTH+0.00001F)
            || shouldSuppressVanillaClouds(Float.NaN)
            || shouldSuppressVanillaClouds(Float.POSITIVE_INFINITY)
            || shouldSuppressVanillaClouds(Float.NEGATIVE_INFINITY))
            throw new IllegalStateException("BLACK FREQUENCY vanilla cloud suppression regression");
        policyVerified=true;
        LOGGER.info("BLACK_FREQUENCY_TEST VANILLA_CLOUD_SUPPRESSION_POLICY_VERIFIED");
    }

    private static final class RiftOverworldEffects extends DimensionSpecialEffects.OverworldEffects {
        @Override
        public boolean renderClouds(ClientLevel level,int ticks,float partialTick,PoseStack poseStack,
                double camX,double camY,double camZ,Matrix4f projectionMatrix) {
            if(!level.dimension().equals(Level.OVERWORLD)) return false;
            float strength=RiftClientState.visibleStrength();
            if(!shouldSuppressVanillaClouds(strength)) {
                if(suppressionLogged) {
                    suppressionLogged=false;
                    LOGGER.info("BLACK_FREQUENCY_CLOUDS_RESTORED");
                    if(Boolean.getBoolean("blackfrequency.ciTestMode"))
                        LOGGER.info("BLACK_FREQUENCY_TEST VANILLA_CLOUDS_RESTORED");
                }
                return false;
            }
            if(!suppressionLogged) {
                suppressionLogged=true;
                LOGGER.info("BLACK_FREQUENCY_CLOUDS_SUPPRESSED strength={}",strength);
                if(Boolean.getBoolean("blackfrequency.ciTestMode"))
                    LOGGER.info("BLACK_FREQUENCY_TEST VANILLA_CLOUDS_SUPPRESSED");
            }
            return true;
        }
    }
}
