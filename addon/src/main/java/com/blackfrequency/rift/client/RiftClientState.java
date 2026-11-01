package com.blackfrequency.rift.client;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="blackfrequency", value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class RiftClientState {
    private static float progress;
    private static boolean collapse;
    private RiftClientState() {}
    public static void update(float value, boolean collapsing) {
        progress=Math.max(0f,Math.min(1f,value));
        collapse=collapsing;
    }
    public static float strength() { return progress; }
    public static boolean collapsing() { return collapse; }
    public static void reset() { progress=0;collapse=false; }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
}
