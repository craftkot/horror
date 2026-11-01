package com.blackfrequency.rift;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.common.MinecraftForge;

@Mod("blackfrequency")
public final class BlackFrequency {
    public BlackFrequency() {
        RiftNetwork.register();
        MinecraftForge.EVENT_BUS.register(new RiftController());
    }
}
