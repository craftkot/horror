package com.blackfrequency.rift.client;
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
}
