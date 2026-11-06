package com.blackfrequency.rift.client;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid="blackfrequency", value=net.minecraftforge.api.distmarker.Dist.CLIENT)
public final class RiftClientState {
    private static float progress;
    private static boolean collapse;
    private static int finaleTicks;
    private static int ticksSincePacket;
    private RiftClientState() {}
    public static void update(float value, boolean collapsing, int ticks) {
        ticksSincePacket=0;
        // Network data must never feed NaN or infinity into sky and overlay shaders.
        progress=Float.isFinite(value) ? Math.max(0f,Math.min(1f,value)) : 0f;
        collapse=collapsing;
        finaleTicks=Math.max(0,ticks);
    }
    public static float strength() { return progress; }
    public static float greenPhase() { return collapse && finaleTicks>=100 && finaleTicks<180 ? 1f : 0f; }
    public static float visibleStrength() { return collapse && finaleTicks<60 ? strength() * (finaleTicks/60f) : strength(); }
    public static boolean collapsing() { return collapse; }
    public static void reset() { progress=0;collapse=false;finaleTicks=0;ticksSincePacket=0; }
    // Recovery updates are intentionally local. Expire stale visuals when a player
    // leaves the fracture radius or the server stops sending state packets.
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END) return;
        if((progress>0f || collapse || finaleTicks>0) && ++ticksSincePacket>100) reset();
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent
    public static void onLogout(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
}
