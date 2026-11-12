package com.blackfrequency.rift;

/**
 * Shared geometry contract for the server-side fracture cues and the client ground mesh.
 * Keeping these values in one place prevents the visible mesh from lagging behind the particles.
 */
public final class RiftGeometry {
    public static final int RADIUS=48;
    public static final int FISSURE_ARM_COUNT=4;
    public static final int FISSURE_SEGMENTS_PER_ARM=12;
    public static final double FISSURE_SAMPLE_SPACING=.45D;

    private RiftGeometry() {}
}
