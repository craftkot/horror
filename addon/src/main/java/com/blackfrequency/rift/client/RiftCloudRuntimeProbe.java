package com.blackfrequency.rift.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Reports the live client cloud option and the effects instance actually attached
 * to the loaded level. Registration alone does not prove LevelRenderer uses it.
 */
@Mod.EventBusSubscriber(modid="blackfrequency", value=Dist.CLIENT)
public final class RiftCloudRuntimeProbe {
    private static final Logger LOGGER=LogUtils.getLogger();
    private static boolean logged;

    private RiftCloudRuntimeProbe() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if(event.phase!=TickEvent.Phase.END || logged) return;
        Minecraft minecraft=Minecraft.getInstance();
        ClientLevel level=minecraft.level;
        if(level==null) return;

        logged=true;
        LOGGER.info("BLACK_FREQUENCY_TEST VANILLA_CLOUD_RUNTIME_STATE dimension={} cloudStatus={} effectsClass={} cloudHeight={} ciTestMode={}",
            level.dimension().location(),
            minecraft.options.getCloudsType().name(),
            level.effects().getClass().getSimpleName(),
            level.effects().getCloudsHeight(),
            Boolean.getBoolean("blackfrequency.ciTestMode"));
    }
}
