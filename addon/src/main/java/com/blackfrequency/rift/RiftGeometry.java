package com.blackfrequency.rift;

/**
 * Shared geometry contract for the server-side fracture cues and the client ground mesh.
 * Keeping these values in one place prevents the visible mesh from lagging behind the particles.
 */
public final class RiftGeometry {
    public static final int RADIUS=48;
    public static final int FISSURE_ARM_COUNT=4;
    public static final int FISSURE_SEGMENTS_PER_ARM=12;
    // Longer arms make the ground catastrophe readable beyond the beacon pad;
    // all samples remain well inside the shared 48-block fracture radius.
    public static final double FISSURE_SAMPLE_SPACING=.60D;
    // Shared lateral irregularity keeps the ground ribbon and server motes on the same jagged path.
    public static final double FISSURE_BEND_AMPLITUDE=.24D;

    private RiftGeometry() {}
}
