package com.blackfrequency.rift;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import org.joml.Vector3f;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** No commands. Ten days after installing this addon in a world, its finale begins. */
public final class RiftController {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    // Time-only anomaly phases are shared by every player query in the same finale tick.
    // The field is evaluated on the logical server thread; a tick change refreshes this transient cache.
    private static int gravityFieldCachedTick=Integer.MIN_VALUE;
    private static long gravityFieldCacheRefreshCount;
    private static double gravityFieldBasePhase;
    private static double gravityFieldBaseZOffset;
    private static double gravityFieldCrossPhase;
    private static double gravityFieldCrossTimeOffset;
    private static double gravityFieldTurbulencePhase;
    private static double gravityFieldTurbulenceTimeOffset;
    private static double gravityFieldMicroPhase;
    private static double gravityFieldMicroTimeOffset;
    // Reuse the diagonal cross-wave to warp micro-anomaly phase without another trig call per player.
    private static final double GRAVITY_MICRO_PHASE_WARP_AMPLITUDE=.55D;
    // A low-amplitude deterministic value-noise layer breaks broad sine bands into drifting, non-repeating pockets.
    private static final double GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE=.045D;
    private static final double GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE=.035D;
    private static final double GRAVITY_APERIODIC_TURBULENCE_TIME_X=.00073D;
    private static final double GRAVITY_APERIODIC_TURBULENCE_TIME_Z=.00091D;
    private static final double GRAVITY_LIFT_ZONE_THRESHOLD=.42D;
    private static final double GRAVITY_SINK_ZONE_THRESHOLD=.68D;
    // Fade lift/sink activation over a narrow field band to avoid a force step at either threshold.
    private static final double GRAVITY_ZONE_ACTIVATION_WIDTH=.16D;
    // Keep anomaly drift and vertical terminal speeds explicit so runtime guards and CI probes share one contract.
    private static final double GRAVITY_ANOMALY_HORIZONTAL_DRIFT_CAP=.35D;
    private static final double GRAVITY_LIFT_VERTICAL_VELOCITY_CAP=.43D;
    private static final double GRAVITY_SINK_VERTICAL_VELOCITY_CAP=.55D;
    private static final double GRAVITY_LIFT_BASE_DRIFT=.035D;
    private static final double GRAVITY_LIFT_CROSS_SHEAR_SCALE=.008D;
    // The legacy dark-purple formatting code is magenta; use the actual deep-violet brand color.
    private static final int RECOVERY_UI_VIOLET_RGB=0x5714C7;
    private static final boolean CI_TEST = Boolean.getBoolean("blackfrequency.ciTestMode");
    // The client smoke test verifies a real beacon click, not player survival under random gravity.
    // Keep its single test player stable before the beacon exists; production physics is unchanged.
    private static boolean ciPlayerGravitySuppressionLogged;
    // CI-only integration probe: exercise the real ServerPlayer fall-flying flag.
    private static boolean ciActualElytraStateVerified;
    private static boolean ciActualPassengerStateVerified;
    // CI-only hold while the sparse fracture removes the original spawn support.
    // Released only after the replacement beacon's standing pad is verified.
    private static boolean ciPlayerSafePadReady;
    private static boolean ciFissureTargetUnavailableLogged;
    private static boolean ciBeaconSightlineFailureLogged;
    // CI-only deferred check after an actual player break; never persisted or force-loaded.
    private static BlockPos ciPendingNaturalBeaconBreakVerificationPos;
    private static UUID ciPendingNaturalBeaconBreakVerificationPlayer;
    private static boolean ciPendingNaturalBeaconBreakVerificationSurvival;
    private static double ciPendingNaturalBeaconBreakVerificationDistanceSquared=Double.NaN;
    private static final long DAY = 24000L;
    // Only for disposable CI worlds. Never increases Minecraft's global tick rate.
    private static final long TEST_STEP = Boolean.getBoolean("blackfrequency.ciTestMode")
        ? Math.max(1L, Math.min(2400L, Long.getLong("blackfrequency.ciTickStep", 2400L))) : 1L;
    // Shared with the client mesh so particles and the rendered ground scar use identical bounds.
    private static final int RADIUS = RiftGeometry.RADIUS;
    // Keep the anomaly's outer 16-block envelope, but ease force to zero with a C2-continuous curve.
    private static final double GRAVITY_ANOMALY_FADE_WIDTH=16.0D;
    private static final double GRAVITY_ANOMALY_OUTER_RADIUS=RADIUS+GRAVITY_ANOMALY_FADE_WIDTH;
    // Quintic smoothstep at 0.125 preserves the old linear fade's particle cutoff distance.
    private static final double GRAVITY_ANOMALY_PARTICLE_MIN_FADE=0.01605224609375D;
    private static final String NATURAL_BEACON_ID_TAG = "blackfrequency:natural_recovery_beacon";
    private static final int FISSURE_ARM_COUNT = RiftGeometry.FISSURE_ARM_COUNT;
    private static final int FISSURE_SEGMENTS_PER_ARM = RiftGeometry.FISSURE_SEGMENTS_PER_ARM;
    private static final int FISSURE_MOTES_PER_SAMPLE = 1;
    private static final int IDLE_BEACON_PULSE_INTERVAL_TICKS = 20;
    private static final int IDLE_BEACON_PULSE_PARTICLE_COUNT = 6;
    // Keep the idle beacon cue as a shallow horizontal halo instead of a tall random spray.
    private static final double IDLE_BEACON_PARTICLE_SPREAD_XZ=.34D;
    private static final double IDLE_BEACON_PARTICLE_SPREAD_Y=.08D;
    private static final double IDLE_BEACON_PARTICLE_SPEED=.006D;
    // The default ServerLevel.sendParticles overload only delivers within 32 blocks.
    // Do not scan for idle-beacon viewers outside the packet's actual delivery radius.
    private static final double IDLE_BEACON_PARTICLE_PACKET_RANGE=32.0D;
    private static final double IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR=
        IDLE_BEACON_PARTICLE_PACKET_RANGE*IDLE_BEACON_PARTICLE_PACKET_RANGE;
    // Surface block cues use the same strict 32-block delivery boundary as the default particle packet.
    private static final double SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR=
        IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR;
    // Recovery effects use cached blue-violet Dust and bounded, deterministic pulses.
    private static final int RESTORE_RING_INTERVAL_TICKS = 20;
    // Recovery completion stays in the action bar; the physical beacon remains unobstructed.
    private static final int RESTORE_RING_PARTICLE_COUNT = 24;
    private static final int RESTORE_RISING_SPARK_PARTICLE_COUNT = 4;
    private static final int RESTORE_HELIX_INTERVAL_TICKS = 8;
    // Share one nearby-player pass between beacon-menu closure and visual audience checks.
    private static final int RESTORE_PLAYER_SCAN_INTERVAL_TICKS = 4;
    // The default ServerLevel.sendParticles overload delivers within 32 blocks of each mote.
    // The outer recovery helix stays within a conservative 5-block envelope around the beacon.
    // Scan only the packet radius plus that envelope; UI/overlay messages keep their 128-block audience.
    private static final double RESTORE_PARTICLE_PACKET_RANGE=32.0D;
    private static final double RESTORE_PARTICLE_EFFECT_ENVELOPE=5.0D;
    private static final double RESTORE_PARTICLE_VIEW_DISTANCE=
        RESTORE_PARTICLE_PACKET_RANGE+RESTORE_PARTICLE_EFFECT_ENVELOPE;
    private static final double RESTORE_PARTICLE_VIEW_DISTANCE_SQR=
        RESTORE_PARTICLE_VIEW_DISTANCE*RESTORE_PARTICLE_VIEW_DISTANCE;
    // Common phase window for every periodic restoration effect (4, 8, 10, 20 and 40 ticks).
    private static final int RESTORE_CADENCE_PERIOD_TICKS = 40;
    private static final int RESTORE_PROGRESS_INTERVAL_TICKS = RESTORE_CADENCE_PERIOD_TICKS;
    private static final int RESTORE_OVERLAY_INTERVAL_TICKS = 10;
    private static final int RESTORE_HELIX_ANCHOR_COUNT = 8;
    private static final int RESTORE_HELIX_MOTES_PER_ANCHOR = 2;
    private static final int RESTORE_HELIX_PARTICLE_COUNT =
        RESTORE_HELIX_ANCHOR_COUNT * RESTORE_HELIX_MOTES_PER_ANCHOR;
    // Let the existing gravity field decay smoothly after beacon activation.
    private static final int RESTORE_GRAVITY_FADE_TICKS=40;
    // Keep the first recovery halo entirely above the beacon block so the initial pulse
    // is not hidden inside the beacon model or clipped at its top face.
    private static final double RESTORE_RING_HEIGHT_OFFSET=1.25D;
    private static final double RESTORE_RING_OUTER_HEIGHT_OFFSET=.45D;
    private static final double RESTORE_RING_VERTICAL_WAVE=.18D;
    private static final int RESTORE_BLOCK_CUE_INTERVAL = 48;
    private static final int RESTORE_BLOCK_CUE_PARTICLE_COUNT = 6;
    private static final int RESTORE_AUDIENCE_PARTICLE_COUNT = 8;
    // Restoration motes are deliberately smaller than the ground-fissure Dust.
    // Reuse these options so recovery never allocates a particle option per pulse.
    private static final float RESTORE_VIOLET_DUST_SCALE=.58F;
    private static final float RESTORE_VIOLET_ACCENT_DUST_SCALE=.76F;
    private static final DustParticleOptions RESTORE_VIOLET_DUST =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),RESTORE_VIOLET_DUST_SCALE);
    private static final DustParticleOptions RESTORE_VIOLET_ACCENT_DUST =
        new DustParticleOptions(new Vector3f(.30F,.14F,.88F),RESTORE_VIOLET_ACCENT_DUST_SCALE);
    private static final double RESTORE_AUDIENCE_SPREAD_XZ=.85D;
    private static final double RESTORE_AUDIENCE_SPREAD_Y=.55D;
    // Gravity cues reuse the bounded recovery palette instead of bright portal/dragon-breath particles.
    // These aliases point at cached Dust options; no particle options are allocated during ticks.
    private static final DustParticleOptions ANOMALY_VIOLET_DUST = RESTORE_VIOLET_DUST;
    private static final DustParticleOptions ANOMALY_VIOLET_ACCENT_DUST = RESTORE_VIOLET_ACCENT_DUST;
    // Five cached sizes smooth the fissure's spatial pulse without allocating per sample.
    private static final DustParticleOptions FISSURE_VIOLET_DUST_SMALL =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),.65F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_MEDIUM_SMALL =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),.75F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),.85F);
    // Give the physical recovery beacon a brighter violet idle cue without changing
    // the vanilla beacon block or increasing its six-particle-per-second budget.
    private static final DustParticleOptions BEACON_VIOLET_DUST =
        new DustParticleOptions(new Vector3f(.48F,.14F,.98F),.90F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_MEDIUM_LARGE =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),.95F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_LARGE =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),1.05F);
    // Brighter blue-violet Dust replaces the magenta Dragon Breath accents.
    // Reused for every accent sample; no per-particle option allocation.
    private static final DustParticleOptions FISSURE_VIOLET_ACCENT_DUST =
        new DustParticleOptions(new Vector3f(.30F,.14F,.88F),1.05F);
    // Terrain accents stay compact; the larger cached accent remains reserved for recovery cues.
    private static final DustParticleOptions SURFACE_FISSURE_ACCENT_DUST =
        new DustParticleOptions(new Vector3f(.30F,.14F,.88F),.85F);
    // Edge accents use the furthest valid terrain sample on each arm; a blocked outer cell
    // must not erase the arm's accent or trigger an unloaded-chunk lookup.
    // A compact, slightly larger cached Dust accent stays readable against grass without adding particles.
    // Three cached sizes let each outer fissure tip breathe with its own pulse phase.
    // The maximum remains below the compact-accent ceiling and adds no particle sends.
    private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST_SMALL =
        new DustParticleOptions(new Vector3f(.42F,.18F,.92F),.64F);
    private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST =
        new DustParticleOptions(new Vector3f(.42F,.18F,.92F),.70F);
    private static final DustParticleOptions FISSURE_VIOLET_EDGE_DUST_LARGE =
        new DustParticleOptions(new Vector3f(.42F,.18F,.92F),.74F);
    private static final double FISSURE_SAMPLE_SPACING = RiftGeometry.FISSURE_SAMPLE_SPACING;
    // Avoid aiming almost straight down at a mote beside the player. The wider upper bound
    // also lets the loaded-only camera preview see fissures from a recovery-beacon pad.
    private static final double FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE = FISSURE_SAMPLE_SPACING*5.0D;
    private static final double FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE = 12.0D;
    // The disposable CI pad is perpendicular to the anchor-to-beacon vector.
    // Two empty near rows let the raised player's eye ray enter the beacon instead of passing above it.
    private static final int CI_BEACON_PAD_FLOOR_Y_OFFSET=0;
    private static final int CI_BEACON_PAD_PLAYER_Y_OFFSET=1;
    private static final double CI_BEACON_PLAYER_EYE_HEIGHT=1.62D;
    // One centered support block keeps the fissure ray above the pad and out of the screenshot foreground.
    private static final double CI_BEACON_PAD_STANDOFF=3.0D;
    // Row 2 stays empty; the single support block starts at row 3.
    private static final int CI_BEACON_PAD_GAP_ROW=2;
    private static final int CI_BEACON_PAD_FIRST_SOLID_ROW=3;
    private static final int CI_BEACON_PAD_LAST_SOLID_ROW=3;
    private static final int CI_BEACON_PAD_LATERAL_RADIUS=0;
    // Vanilla survival reach, measured from the player's eyes to the target block AABB.
    private static final double CI_BEACON_INTERACTION_MAX_DISTANCE=4.5D;
    // CI-only integrated ServerPlayer probe; each step moves within an already-loaded chunk.
    private static final int CI_GRAVITY_TRAJECTORY_STEPS=8;
    private static final int CI_GRAVITY_TRAJECTORY_SEARCH_TICKS=10000;

    private static double fissurePulseWave(double phase,int segment,int arm) {
        if(!Double.isFinite(phase)) return 0.0D;
        double wave=Math.sin(phase+segment*.8D+arm*.6D);
        return Double.isFinite(wave) ? wave : 0.0D;
    }

    private static double fissurePulseWave(double phase,FissureSample sample) {
        if(!Double.isFinite(phase)) return 0.0D;
        double wave=Math.sin(phase+sample.segmentPhase()+sample.armPhase());
        return Double.isFinite(wave) ? wave : 0.0D;
    }

    private static DustParticleOptions fissureDustForWave(double wave) {
        if(!Double.isFinite(wave)) return FISSURE_VIOLET_DUST;
        double bounded=Math.max(-1.0D,Math.min(1.0D,wave));
        if(bounded<-.6D) return FISSURE_VIOLET_DUST_SMALL;
        if(bounded<-.2D) return FISSURE_VIOLET_DUST_MEDIUM_SMALL;
        if(bounded<.2D) return FISSURE_VIOLET_DUST;
        if(bounded<.6D) return FISSURE_VIOLET_DUST_MEDIUM_LARGE;
        return FISSURE_VIOLET_DUST_LARGE;
    }

    private static DustParticleOptions fissureEdgeDustForWave(double wave) {
        if(!Double.isFinite(wave)) return FISSURE_VIOLET_EDGE_DUST;
        double bounded=Math.max(-1.0D,Math.min(1.0D,wave));
        if(bounded<-.35D) return FISSURE_VIOLET_EDGE_DUST_SMALL;
        if(bounded>.35D) return FISSURE_VIOLET_EDGE_DUST_LARGE;
        return FISSURE_VIOLET_EDGE_DUST;
    }

    private static Component recoveryViolet(String text) {
        return Component.literal(text).withStyle(style -> style.withColor(TextColor.fromRgb(RECOVERY_UI_VIOLET_RGB)));
    }

    private static boolean hasRecoveryViolet(Component component) {
        TextColor color=component.getStyle().getColor();
        return color!=null && color.getValue()==RECOVERY_UI_VIOLET_RGB;
    }

    private static boolean hasDustColor(DustParticleOptions dust,float red,float green,float blue) {
        Vector3f color=dust.getColor();
        return Float.isFinite(color.x()) && Float.isFinite(color.y()) && Float.isFinite(color.z())
            && Math.abs(color.x()-red)<1.0E-6F
            && Math.abs(color.y()-green)<1.0E-6F
            && Math.abs(color.z()-blue)<1.0E-6F;
    }

    private static boolean hasFissureDustColor(DustParticleOptions dust) {
        return hasDustColor(dust,.34F,.08F,.78F);
    }

    private static void verifyFissureDustPulseProfile() {
        if(fissureDustForWave(-1.0D)!=FISSURE_VIOLET_DUST_SMALL
            || fissureDustForWave(-.4D)!=FISSURE_VIOLET_DUST_MEDIUM_SMALL
            || fissureDustForWave(0.0D)!=FISSURE_VIOLET_DUST
            || fissureDustForWave(.4D)!=FISSURE_VIOLET_DUST_MEDIUM_LARGE
            || fissureDustForWave(1.0D)!=FISSURE_VIOLET_DUST_LARGE
            || fissureDustForWave(-.6D)!=FISSURE_VIOLET_DUST_MEDIUM_SMALL
            || fissureDustForWave(-.2D)!=FISSURE_VIOLET_DUST
            || fissureDustForWave(.2D)!=FISSURE_VIOLET_DUST_MEDIUM_LARGE
            || fissureDustForWave(.6D)!=FISSURE_VIOLET_DUST_LARGE
            || fissureDustForWave(Double.NaN)!=FISSURE_VIOLET_DUST
            || fissureDustForWave(Double.POSITIVE_INFINITY)!=FISSURE_VIOLET_DUST
            || fissureDustForWave(Double.NEGATIVE_INFINITY)!=FISSURE_VIOLET_DUST
            || fissurePulseWave(Double.NaN,1,0)!=0.0D
            || !hasFissureDustColor(FISSURE_VIOLET_DUST_SMALL)
            || !hasFissureDustColor(FISSURE_VIOLET_DUST_MEDIUM_SMALL)
            || !hasFissureDustColor(FISSURE_VIOLET_DUST)
            || !hasFissureDustColor(FISSURE_VIOLET_DUST_MEDIUM_LARGE)
            || !hasFissureDustColor(FISSURE_VIOLET_DUST_LARGE)
            || !(FISSURE_VIOLET_DUST_SMALL.getScale()<FISSURE_VIOLET_DUST_MEDIUM_SMALL.getScale()
                && FISSURE_VIOLET_DUST_MEDIUM_SMALL.getScale()<FISSURE_VIOLET_DUST.getScale()
                && FISSURE_VIOLET_DUST.getScale()<FISSURE_VIOLET_DUST_MEDIUM_LARGE.getScale()
                && FISSURE_VIOLET_DUST_MEDIUM_LARGE.getScale()<FISSURE_VIOLET_DUST_LARGE.getScale())
            || FISSURE_VIOLET_DUST_SMALL.getScale()<.6F
            || FISSURE_VIOLET_DUST_LARGE.getScale()>1.1F)
            throw new IllegalStateException("CI fissure Dust pulse profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_DUST_PULSE_PROFILE_VERIFIED min={} low={} base={} high={} max={} variants=5",
            FISSURE_VIOLET_DUST_SMALL.getScale(),FISSURE_VIOLET_DUST_MEDIUM_SMALL.getScale(),
            FISSURE_VIOLET_DUST.getScale(),FISSURE_VIOLET_DUST_MEDIUM_LARGE.getScale(),
            FISSURE_VIOLET_DUST_LARGE.getScale());
        verifyFissureTargetSelectionPolicy();
    }
    private static void verifyFissureEdgeDustPulseProfile() {
        float minScale=FISSURE_VIOLET_EDGE_DUST_SMALL.getScale();
        float baseScale=FISSURE_VIOLET_EDGE_DUST.getScale();
        float maxScale=FISSURE_VIOLET_EDGE_DUST_LARGE.getScale();
        if(fissureEdgeDustForWave(-1.0D)!=FISSURE_VIOLET_EDGE_DUST_SMALL
            || fissureEdgeDustForWave(-.35D)!=FISSURE_VIOLET_EDGE_DUST
            || fissureEdgeDustForWave(0.0D)!=FISSURE_VIOLET_EDGE_DUST
            || fissureEdgeDustForWave(.35D)!=FISSURE_VIOLET_EDGE_DUST
            || fissureEdgeDustForWave(1.0D)!=FISSURE_VIOLET_EDGE_DUST_LARGE
            || fissureEdgeDustForWave(Double.NaN)!=FISSURE_VIOLET_EDGE_DUST
            || fissureEdgeDustForWave(Double.POSITIVE_INFINITY)!=FISSURE_VIOLET_EDGE_DUST
            || fissureEdgeDustForWave(Double.NEGATIVE_INFINITY)!=FISSURE_VIOLET_EDGE_DUST
            || !hasDustColor(FISSURE_VIOLET_EDGE_DUST_SMALL,.42F,.18F,.92F)
            || !hasDustColor(FISSURE_VIOLET_EDGE_DUST,.42F,.18F,.92F)
            || !hasDustColor(FISSURE_VIOLET_EDGE_DUST_LARGE,.42F,.18F,.92F)
            || !Float.isFinite(minScale) || !Float.isFinite(baseScale) || !Float.isFinite(maxScale)
            || !(minScale<baseScale && baseScale<maxScale)
            || minScale<.6F || maxScale>=.75F)
            throw new IllegalStateException("CI fissure edge Dust pulse profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_PULSE_PROFILE_VERIFIED min={} base={} max={} variants=3 bounded=true phaseDriven=true cached=true",
            minScale,baseScale,maxScale);
    }

    // Production spreads terrain writes across more ticks to reduce synchronous chunk-update bursts.
    // CI keeps the higher deterministic budget so integration fixtures remain short.
    private static int fractureEditBudget(boolean ciTest) {
        return ciTest ? 160 : 96;
    }
    private static final int EDIT_BUDGET = fractureEditBudget(CI_TEST);
    private static final int MAX_EDITS = 95000;
    // Canonical block-state NBT is tiny; reject nested or oversized payloads before retaining snapshots.
    private static final int MAX_SNAPSHOT_BLOCK_ID_LENGTH = 256;
    private static final int MAX_SNAPSHOT_PROPERTY_COUNT = 64;
    private static final int MAX_SNAPSHOT_PROPERTY_KEY_LENGTH = 128;
    private static final int MAX_SNAPSHOT_PROPERTY_VALUE_LENGTH = 128;
    // A 700-byte per-entry ceiling lets the full 95,000-entry queue fit below the
    // 64 MiB aggregate budget, even when every retained entry is at the limit.
    // Oversized modded states are left untouched rather than risking recovery-budget poisoning.
    private static final long MAX_SNAPSHOT_ENTRY_ESTIMATE_BYTES=700L;
    // Bound the total snapshot payload retained from one damaged SavedData record.
    // This is a conservative estimate; the NBT parser has already materialized its input.
    private static final long MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES=64L*1024L*1024L;
    // Only disposable CI worlds can stop after a small fracture to test
    // recovery when fewer than 160 blocks were damaged.
    private static final int EFFECTIVE_MAX_EDITS = CI_TEST
        ? (Boolean.getBoolean("blackfrequency.ciSparseRecoveryTest") ? 80
            : Boolean.getBoolean("blackfrequency.ciClientSmokeTest") ? 160 : MAX_EDITS)
        : MAX_EDITS;

    // New worlds fracture from the anchor outward so the first ground rupture
    // is visible near the player. Legacy saves keep their persisted row-major cursor.
    private static final int[] CENTER_OUT_DX = new int[(RADIUS*2+1)*(RADIUS*2+1)];
    private static final int[] CENTER_OUT_DZ = new int[CENTER_OUT_DX.length];
    private static final int CENTER_OUT_COLUMN_COUNT;
    static {
        int count=0;
        for(int ring=0;ring<=RADIUS;ring++) {
            for(int dx=-ring;dx<=ring;dx++) {
                for(int dz=-ring;dz<=ring;dz++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=ring
                        || dx*dx+dz*dz>RADIUS*RADIUS) continue;
                    CENTER_OUT_DX[count]=dx;
                    CENTER_OUT_DZ[count]=dz;
                    count++;
                }
            }
        }
        CENTER_OUT_COLUMN_COUNT=count;
    }

    // Persisted counters are untrusted input: keep them inside the state machine's
    // real limits so corrupt NBT cannot bypass the edit cap or stall recovery.
    private static int boundedCounter(int value,int maximum) {
        return Math.max(0,Math.min(maximum,value));
    }

    // A valid finale cannot own more recovery snapshots than its edit budget.
    // Bound the copied queue when loading damaged NBT to avoid unbounded growth.
    private static int boundedSnapshotCount(int value) {
        return Math.max(0,Math.min(MAX_EDITS,value));
    }

    /** Count snapshot-list entries that exceed the retained recovery queue cap. */
    private static int boundedSnapshotOverflowEntries(int savedCount,int retainedCount) {
        if(savedCount<0 || retainedCount<0 || retainedCount>savedCount) return 0;
        return Math.min(MAX_EDITS,savedCount-retainedCount);
    }

    // The disposable low-distance client probe needs enough time for natural chunk
    // unloading; production and server integration keep the normal 48-check budget.
    private static int restoreSnapshotScanBudget(boolean deferredClientProbe) {
        return deferredClientProbe?1:48;
    }

    // A terminal cursor is trustworthy only when every retained snapshot has
    // an outcome. A torn/corrupt save must not report recovery complete while
    // snapshots remain unaccounted for.
    private static boolean recoverySnapshotsFullyAccounted(int snapshotCount,
            int restoredBlocks,int skippedOccupied,int skippedInvalid) {
        if(snapshotCount<0) return false;
        long accounted=(long)restoredBlocks+(long)skippedOccupied+(long)skippedInvalid;
        return accounted==snapshotCount;
    }

    /** Reject non-canonical or malformed registry identifiers before NbtUtils resolves them. */
    private static boolean isValidSnapshotBlockId(String blockId) {
        if(blockId==null || blockId.isEmpty()
            || blockId.length()>MAX_SNAPSHOT_BLOCK_ID_LENGTH
            || !ResourceLocation.isValidResourceLocation(blockId)) return false;
        int separator=blockId.indexOf(':');
        if(separator<=0 || separator==blockId.length()-1
            || separator!=blockId.lastIndexOf(':')) return false;
        ResourceLocation parsed=ResourceLocation.tryParse(blockId);
        return parsed!=null && !parsed.getNamespace().isEmpty() && !parsed.getPath().isEmpty();
    }

    /** Accept only the compact schema emitted by NbtUtils.writeBlockState. */
    private static boolean isBoundedSnapshotPayload(CompoundTag entry) {
        if(entry.getAllKeys().size()!=2
            || !entry.contains("pos",Tag.TAG_LONG)
            || !entry.contains("state",Tag.TAG_COMPOUND)) return false;
        CompoundTag state=entry.getCompound("state");
        if(!state.contains("Name",Tag.TAG_STRING)) return false;
        boolean hasProperties=state.contains("Properties");
        if(state.getAllKeys().size()!=(hasProperties ? 2 : 1)
            || (hasProperties && !state.contains("Properties",Tag.TAG_COMPOUND))) return false;
        String blockId=state.getString("Name");
        if(!isValidSnapshotBlockId(blockId)) return false;
        if(!hasProperties) return true;
        CompoundTag properties=state.getCompound("Properties");
        if(properties.getAllKeys().size()>MAX_SNAPSHOT_PROPERTY_COUNT) return false;
        for(String key:properties.getAllKeys()) {
            if(key.isEmpty() || key.length()>MAX_SNAPSHOT_PROPERTY_KEY_LENGTH
                || !properties.contains(key,Tag.TAG_STRING)) return false;
            String value=properties.getString(key);
            if(value.isEmpty() || value.length()>MAX_SNAPSHOT_PROPERTY_VALUE_LENGTH) return false;
        }
        return true;
    }

    /**
     * Conservative encoded-size estimate for the compact block-state schema.
     * Three bytes per UTF-16 code unit upper-bound UTF-8 string storage; fixed
     * overhead accounts for the nested compounds, names and tag headers.
     */
    private static long estimatedSnapshotPayloadBytes(CompoundTag entry) {
        CompoundTag state=entry.getCompound("state");
        long estimate=256L+3L*state.getString("Name").length();
        if(state.contains("Properties",Tag.TAG_COMPOUND)) {
            CompoundTag properties=state.getCompound("Properties");
            for(String key:properties.getAllKeys())
                estimate+=16L+3L*(key.length()+properties.getString(key).length());
        }
        return estimate;
    }

    /** Return zero for a snapshot that is not safe to retain or serialize. */
    private static long boundedSnapshotPayloadEstimate(CompoundTag entry) {
        if(!isBoundedSnapshotPayload(entry)) return 0L;
        long estimate=estimatedSnapshotPayloadBytes(entry);
        return estimate>0L && estimate<=MAX_SNAPSHOT_ENTRY_ESTIMATE_BYTES ? estimate : 0L;
    }

    private static boolean withinSnapshotRetentionBudget(long retainedBytes,long candidateBytes) {
        return retainedBytes>=0L && candidateBytes>0L
            && retainedBytes<=MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES
            && candidateBytes<=MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES-retainedBytes;
    }

    private static long boundedElapsed(long value) {
        return Math.max(0L,Math.min(DAY*10L,value));
    }

    // Tick counters may be restored from damaged saves. Saturation avoids signed
    // overflow turning a long-running finale/recovery back into negative time.
    private static int nextNonNegativeTick(int value) {
        if(value<0) return 0;
        return value==Integer.MAX_VALUE ? value : value+1;
    }

    // Outside the drift cap, an anomaly may slow existing motion but cannot
    // accelerate it farther outward. Preserve external movement unchanged.
    private static double boundedAnomalyVelocity(double current, double impulse) {
        final double cap=GRAVITY_ANOMALY_HORIZONTAL_DRIFT_CAP;
        // Malformed motion from another mod must not spread NaN/Infinity into
        // player physics; ignore an invalid impulse or reset invalid motion.
        if(!Double.isFinite(current)) return 0.0D;
        if(!Double.isFinite(impulse)) return current;
        // A finite sum can still overflow. Keep external knockback intact,
        // while never feeding infinite motion back to the Minecraft client.
        if(impulse>0.0D && current>Double.MAX_VALUE-impulse) return current;
        if(impulse<0.0D && current< -Double.MAX_VALUE-impulse) return current;
        double next=current+impulse;
        if(current>cap) return Math.min(current,next);
        if(current< -cap) return Math.max(current,next);
        return Math.max(-cap,Math.min(cap,next));
    }

    /** Keep corrupted vertical motion from propagating through lift anomalies. */
    private static double boundedLiftVelocity(double current, double lift) {
        if(!Double.isFinite(current)) current=0.0D;
        if(!Double.isFinite(lift)) lift=0.0D;
        // Saturate before adding: two finite doubles can overflow to Infinity.
        // Do not erase externally supplied upward knockback above the lift cap.
        if(lift>0.0D && current>Double.MAX_VALUE-lift) return Math.max(GRAVITY_LIFT_VERTICAL_VELOCITY_CAP,current);
        if(lift<0.0D && current< -Double.MAX_VALUE-lift) return -Double.MAX_VALUE;
        double next=current+lift;
        return current>GRAVITY_LIFT_VERTICAL_VELOCITY_CAP
            ? Math.min(current,next) : Math.min(GRAVITY_LIFT_VERTICAL_VELOCITY_CAP,next);
    }

    /** Downward anomaly drag must not propagate invalid motion from other mods. */
    private static double boundedSinkVelocity(double current, double impulse) {
        if(!Double.isFinite(current)) current=0.0D;
        if(!Double.isFinite(impulse)) return current;
        // Negative impulse is bounded by the sink's terminal speed; preserve
        // pre-existing faster falls rather than artificially braking them.
        if(impulse<0.0D && current< -Double.MAX_VALUE-impulse) return -Double.MAX_VALUE;
        double next=current+impulse;
        return current< -GRAVITY_SINK_VERTICAL_VELOCITY_CAP
            ? Math.min(current,next) : Math.max(-GRAVITY_SINK_VERTICAL_VELOCITY_CAP,next);
    }

    /** Apply sink-zone horizontal drag without propagating invalid motion. */
    private static double boundedSinkDrag(double current, double drag) {
        if(!Double.isFinite(current)) return 0.0D;
        if(!Double.isFinite(drag)) return current;
        // Drag is damping, never a source of extra speed or a direction flip.
        return current*Math.max(0.0D,Math.min(1.0D,drag));
    }

    /**
     * Elytra flight is an active flight mode too. Do not inject rift impulses
     * or misleading sink/lift particles into gliding players.
     */
    private static boolean shouldApplyRiftGravity(boolean alive,boolean removed,boolean creative,
            boolean spectator,boolean abilityFlying,boolean fallFlying,boolean passenger) {
        return alive && !removed && !creative && !spectator
            && !abilityFlying && !fallFlying && !passenger;
    }

    // Use the same eligibility check for the real server player in both the
    // impulse and particle paths; mounted players follow their vehicle's physics.
    private static boolean shouldApplyRiftGravity(net.minecraft.server.level.ServerPlayer player) {
        return shouldApplyRiftGravity(player.isAlive(),player.isRemoved(),
            player.isCreative(),player.isSpectator(),player.getAbilities().flying,
            player.isFallFlying(),player.isPassenger());
    }

    /**
     * One bounded field drives both player impulses and their visible cues.
     * A diagonal cross-wave and a smaller time-warped spatial wave shear the broad
     * lift/sink bands into irregular drifting pockets without changing impulse caps.
     * The micro-wave borrows the cross-wave phase, adding nonlinear local variation at no extra trig cost.
     */
    /** Cache all time-dependent phase terms once per distinct finale tick. */
    private static void updateGravityFieldPhaseCache(int ticks) {
        int time=Math.max(0,ticks);
        if(gravityFieldCachedTick==time) return;
        gravityFieldBasePhase=Math.sin(time*.011D)*1.8D;
        gravityFieldBaseZOffset=time*.008D;
        gravityFieldCrossPhase=Math.sin(time*.013D)*.9D;
        gravityFieldCrossTimeOffset=time*.009D;
        gravityFieldTurbulencePhase=Math.sin(time*.017D)*1.3D;
        gravityFieldTurbulenceTimeOffset=time*.021D;
        gravityFieldMicroPhase=Math.sin(time*.027D)*.8D;
        gravityFieldMicroTimeOffset=time*.031D;
        gravityFieldCachedTick=time;
        if(gravityFieldCacheRefreshCount<Long.MAX_VALUE) gravityFieldCacheRefreshCount++;
    }

    /** A small field-coupled cross-current makes lift pockets shear sideways without another trig call. */
    private static double gravityAnomalyCrossShear(double zone) {
        if(!Double.isFinite(zone)) return 0.0D;
        double boundedZone=Math.max(-1.0D,Math.min(1.0D,zone));
        return Math.max(0.0D,boundedZone-GRAVITY_LIFT_ZONE_THRESHOLD)
            *GRAVITY_LIFT_CROSS_SHEAR_SCALE;
    }

    private static long gravityAnomalyLatticeHash(int cellX,int cellZ,long worldSeed) {
        long value=(((long)cellX)<<32)^(cellZ&0xffffffffL)^worldSeed^0xD1B54A32D192ED03L;
        value=(value^(value>>>30))*0xBF58476D1CE4E5B9L;
        value=(value^(value>>>27))*0x94D049BB133111EBL;
        return value^(value>>>31);
    }

    private static double gravityAnomalyLatticeValue(int cellX,int cellZ,long worldSeed) {
        return ((gravityAnomalyLatticeHash(cellX,cellZ,worldSeed)>>>11)*0x1.0p-53D)*2.0D-1.0D;
    }

    // Quintic fade keeps both the first and second spatial derivatives continuous
    // where the deterministic turbulence crosses an integer lattice cell.
    private static double gravityAnomalyFade(double value) {
        if(!Double.isFinite(value)) return 0.0D;
        double t=Math.max(0.0D,Math.min(1.0D,value));
        return t*t*t*(t*(t*6.0D-15.0D)+10.0D);
    }

    /** C2 activation envelope for lift/sink bands; zero at threshold, full strength after the ramp. */
    private static double gravityAnomalyZoneActivation(double magnitude,double threshold) {
        if(!Double.isFinite(magnitude) || !Double.isFinite(threshold)
            || threshold<0.0D || threshold>=1.0D) return 0.0D;
        return gravityAnomalyFade((magnitude-threshold)/GRAVITY_ZONE_ACTIVATION_WIDTH);
    }

    private static void verifyGravityAnomalyFadeProfile() {
        double h=.0005D;
        double startSlope=gravityAnomalyFade(h)/h;
        double endSlope=(1.0D-gravityAnomalyFade(1.0D-h))/h;
        double startSecond=(gravityAnomalyFade(2.0D*h)-2.0D*gravityAnomalyFade(h)
            +gravityAnomalyFade(0.0D))/(h*h);
        double endSecond=(gravityAnomalyFade(1.0D)-2.0D*gravityAnomalyFade(1.0D-h)
            +gravityAnomalyFade(1.0D-2.0D*h))/(h*h);
        boolean monotonic=true,bounded=true,symmetric=true;
        double previous=-1.0D;
        for(int sample=0;sample<=1000;sample++) {
            double t=sample/1000.0D;
            double value=gravityAnomalyFade(t);
            double opposite=gravityAnomalyFade(1.0D-t);
            monotonic &= value>=previous;
            bounded &= Double.isFinite(value) && value>=0.0D && value<=1.0D;
            symmetric &= Math.abs(value+opposite-1.0D)<=1.0E-12D;
            previous=value;
        }
        boolean endpoints=gravityAnomalyFade(0.0D)==0.0D
            && gravityAnomalyFade(1.0D)==1.0D
            && gravityAnomalyFade(.5D)==.5D;
        boolean flatDerivatives=Math.abs(startSlope)<2.0E-5D
            && Math.abs(endSlope)<2.0E-5D
            && Math.abs(startSecond)<.04D
            && Math.abs(endSecond)<.04D;
        boolean invalidFallback=gravityAnomalyFade(Double.NaN)==0.0D
            && gravityAnomalyFade(Double.POSITIVE_INFINITY)==0.0D
            && gravityAnomalyFade(Double.NEGATIVE_INFINITY)==0.0D;
        if(!(endpoints && monotonic && bounded && symmetric && flatDerivatives && invalidFallback))
            throw new IllegalStateException("CI gravity anomaly quintic interpolation regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_C2_INTERPOLATION_VERIFIED curve=quintic endpoints=true firstDerivativeZero=true secondDerivativeZero=true monotonic=true bounded=true invalidFallback=true");
    }

    private static double gravityAnomalyLerp(double from,double to,double progress) {
        return from+(to-from)*progress;
    }

    /**
     * Smooth, deterministic value noise with independent spatial and time drift.
     * Integer lattice hashing avoids Random allocations and extra trigonometric calls.
     */
    private static double gravityAnomalyAperiodicNoise(double x,double z,int ticks) {
        return gravityAnomalyAperiodicNoise(x,z,ticks,0L);
    }

    private static double gravityAnomalyAperiodicNoise(double x,double z,int ticks,long worldSeed) {
        if(!Double.isFinite(x) || !Double.isFinite(z)
            || Math.abs(x)>30_000_000.0D || Math.abs(z)>30_000_000.0D) return 0.0D;
        int time=Math.max(0,ticks);
        double sampleX=x*GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE
            +time*GRAVITY_APERIODIC_TURBULENCE_TIME_X;
        double sampleZ=z*GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE
            -time*GRAVITY_APERIODIC_TURBULENCE_TIME_Z;
        double floorX=Math.floor(sampleX);
        double floorZ=Math.floor(sampleZ);
        int cellX=(int)floorX;
        int cellZ=(int)floorZ;
        double blendX=gravityAnomalyFade(sampleX-floorX);
        double blendZ=gravityAnomalyFade(sampleZ-floorZ);
        double near=gravityAnomalyLerp(
            gravityAnomalyLatticeValue(cellX,cellZ,worldSeed),
            gravityAnomalyLatticeValue(cellX+1,cellZ,worldSeed),blendX);
        double far=gravityAnomalyLerp(
            gravityAnomalyLatticeValue(cellX,cellZ+1,worldSeed),
            gravityAnomalyLatticeValue(cellX+1,cellZ+1,worldSeed),blendX);
        return gravityAnomalyLerp(near,far,blendZ);
    }

    private static double gravityAnomalyField(double x,double z,int ticks) {
        return gravityAnomalyField(x,z,ticks,0L);
    }

    private static double gravityAnomalyField(double x,double z,int ticks,long worldSeed) {
        if(!Double.isFinite(x) || !Double.isFinite(z)
            || Math.abs(x)>30_000_000.0D || Math.abs(z)>30_000_000.0D) return 0.0D;
        updateGravityFieldPhaseCache(ticks);
        double base=Math.sin(x*.115D+gravityFieldBasePhase)
            *Math.cos(z*.125D-gravityFieldBaseZOffset);
        double cross=Math.sin((x+z)*.047D+gravityFieldCrossPhase-gravityFieldCrossTimeOffset);
        double turbulence=Math.sin((x-z)*.083D+gravityFieldTurbulencePhase+gravityFieldTurbulenceTimeOffset);
        double microTurbulence=Math.sin((x*.17D+z*.13D)
            +gravityFieldMicroPhase+gravityFieldMicroTimeOffset
            +cross*GRAVITY_MICRO_PHASE_WARP_AMPLITUDE);
        double aperiodicTurbulence=gravityAnomalyAperiodicNoise(x,z,ticks,worldSeed);
        double field=base+.12D*cross+.06D*turbulence+.035D*microTurbulence
            +GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE*aperiodicTurbulence;
        return Math.max(-1.0D,Math.min(1.0D,field));
    }

    private static void verifyGravityAnomalyZoneActivation() {
        double[] thresholds={GRAVITY_LIFT_ZONE_THRESHOLD,GRAVITY_SINK_ZONE_THRESHOLD};
        double[] offsets={-.25D,0.0D,.25D,.5D,.75D,1.0D,1.25D};
        double[] expected={0.0D,0.0D,.103515625D,.5D,.896484375D,1.0D,1.0D};
        boolean monotonic=true,bounded=true,invalidNeutral=true;
        for(double threshold:thresholds) {
            for(int i=0;i<offsets.length;i++) {
                double actual=gravityAnomalyZoneActivation(
                    threshold+GRAVITY_ZONE_ACTIVATION_WIDTH*offsets[i],threshold);
                if(!Double.isFinite(actual) || Math.abs(actual-expected[i])>1.0E-12D)
                    throw new IllegalStateException("CI gravity zone activation profile regression");
            }
            double previous=0.0D;
            for(int sample=0;sample<=256;sample++) {
                double magnitude=threshold-GRAVITY_ZONE_ACTIVATION_WIDTH
                    +2.0D*GRAVITY_ZONE_ACTIVATION_WIDTH*sample/256.0D;
                double actual=gravityAnomalyZoneActivation(magnitude,threshold);
                bounded &= Double.isFinite(actual) && actual>=0.0D && actual<=1.0D;
                monotonic &= actual>=previous-1.0E-12D;
                previous=actual;
            }
            invalidNeutral &= gravityAnomalyZoneActivation(Double.NaN,threshold)==0.0D
                && gravityAnomalyZoneActivation(Double.POSITIVE_INFINITY,threshold)==0.0D
                && gravityAnomalyZoneActivation(.9D,Double.NaN)==0.0D
                && gravityAnomalyZoneActivation(.9D,-.01D)==0.0D
                && gravityAnomalyZoneActivation(.9D,1.0D)==0.0D;
        }
        if(!(monotonic && bounded && invalidNeutral))
            throw new IllegalStateException("CI gravity zone activation bounds/monotonicity regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_ZONE_ACTIVATION_VERIFIED liftThreshold={} sinkThreshold={} rampWidth={} quarter={} midpoint={} threeQuarter={} sharedQuintic=true monotonic=true bounded=true invalidNeutral=true",
            GRAVITY_LIFT_ZONE_THRESHOLD,GRAVITY_SINK_ZONE_THRESHOLD,GRAVITY_ZONE_ACTIVATION_WIDTH,
            expected[2],expected[3],expected[4]);
    }

    private static void verifyGravityAnomalyWorldSeedProfile() {
        long firstWorldSeed=0x13579BDF2468ACE0L;
        long secondWorldSeed=0x02468ACE13579BDFL;
        double x=-17.5D,z=20.5D;
        int ticks=2400;
        double firstNoise=gravityAnomalyAperiodicNoise(x,z,ticks,firstWorldSeed);
        double repeatedNoise=gravityAnomalyAperiodicNoise(x,z,ticks,firstWorldSeed);
        double secondNoise=gravityAnomalyAperiodicNoise(x,z,ticks,secondWorldSeed);
        double firstField=gravityAnomalyField(x,z,ticks,firstWorldSeed);
        double repeatedField=gravityAnomalyField(x,z,ticks,firstWorldSeed);
        double secondField=gravityAnomalyField(x,z,ticks,secondWorldSeed);
        // Negative coordinates cross a different integer lattice boundary than
        // positive coordinates. Verify the seeded field stays continuous there.
        double negativeBoundaryX=(-1.0D-ticks*GRAVITY_APERIODIC_TURBULENCE_TIME_X)
            /GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE;
        double negativeBoundaryLeft=gravityAnomalyAperiodicNoise(
            negativeBoundaryX-1.0E-4D,z,ticks,firstWorldSeed);
        double negativeBoundaryRight=gravityAnomalyAperiodicNoise(
            negativeBoundaryX+1.0E-4D,z,ticks,firstWorldSeed);
        // The Z axis has the opposite time-drift sign. Use a tick where its -1
        // lattice seam is also at a negative world coordinate.
        int negativeZBoundaryTicks=600;
        double negativeBoundaryZ=(-1.0D+negativeZBoundaryTicks*GRAVITY_APERIODIC_TURBULENCE_TIME_Z)
            /GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE;
        double negativeZBoundaryLeft=gravityAnomalyAperiodicNoise(
            x,negativeBoundaryZ-1.0E-4D,negativeZBoundaryTicks,firstWorldSeed);
        double negativeZBoundaryRight=gravityAnomalyAperiodicNoise(
            x,negativeBoundaryZ+1.0E-4D,negativeZBoundaryTicks,firstWorldSeed);
        boolean negativeXBoundaryContinuous=negativeBoundaryX<0.0D
            && Double.isFinite(negativeBoundaryLeft) && Double.isFinite(negativeBoundaryRight)
            && Math.abs(negativeBoundaryLeft-negativeBoundaryRight)<1.0E-5D;
        boolean negativeZBoundaryContinuous=negativeBoundaryZ<0.0D
            && Double.isFinite(negativeZBoundaryLeft) && Double.isFinite(negativeZBoundaryRight)
            && Math.abs(negativeZBoundaryLeft-negativeZBoundaryRight)<1.0E-5D;
        boolean negativeBoundaryContinuous=negativeXBoundaryContinuous && negativeZBoundaryContinuous;
        if(!Double.isFinite(firstNoise) || !Double.isFinite(secondNoise)
            || Math.abs(firstNoise-repeatedNoise)>1.0E-12D
            || Math.abs(firstNoise-secondNoise)<.1D
            || !Double.isFinite(firstField) || !Double.isFinite(secondField)
            || Math.abs(firstField-repeatedField)>1.0E-12D
            || Math.abs(firstField-secondField)<.001D
            || firstField< -1.0D || firstField>1.0D
            || secondField< -1.0D || secondField>1.0D
            || !negativeBoundaryContinuous)
            throw new IllegalStateException("CI world-seeded gravity anomaly profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_WORLD_SEED_PROFILE_VERIFIED worldSeedScoped=true deterministic=true distinctWorlds=true bounded=true noRandomAllocation=true");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_NEGATIVE_LATTICE_BOUNDARY_VERIFIED worldSeedScoped=true negativeCoordinates=true continuous=true deterministic=true negativeX=true negativeZ=true");
    }

    private static void verifyGravityAnomalyField() {
        verifyGravityAnomalyFadeProfile();
        verifyGravityAnomalyZoneActivation();
        verifyGravityAnomalyWorldSeedProfile();
        double lift=gravityAnomalyField(-17.5D,20.5D,0);
        double sink=gravityAnomalyField(-19.5D,-3.5D,0);
        double repeated=gravityAnomalyField(-17.5D,20.5D,0);
        double later=gravityAnomalyField(-17.5D,20.5D,85);
        boolean bounded=Double.isFinite(lift) && Double.isFinite(sink)
            && lift>=-1.0D && lift<=1.0D && sink>=-1.0D && sink<=1.0D;
        boolean deterministic=Math.abs(lift-repeated)<=1.0E-12D;
        boolean pockets=lift>GRAVITY_LIFT_ZONE_THRESHOLD && sink< -GRAVITY_SINK_ZONE_THRESHOLD;
        boolean drifting=Math.abs(later-lift)>.05D;
        boolean invalidRejected=gravityAnomalyField(Double.NaN,0.0D,0)==0.0D
            && gravityAnomalyField(30_000_001.0D,0.0D,0)==0.0D;
        double noiseAtZero=gravityAnomalyAperiodicNoise(-17.5D,20.5D,0);
        double noiseRepeated=gravityAnomalyAperiodicNoise(-17.5D,20.5D,0);
        double noiseLater=gravityAnomalyAperiodicNoise(-17.5D,20.5D,2400);
        double noiseAtBasePeriod=gravityAnomalyAperiodicNoise(
            -17.5D+2.0D*Math.PI/.115D,20.5D,0);
        double noiseNearBoundary=gravityAnomalyAperiodicNoise(0.0D,0.0D,0);
        double noiseAcrossBoundary=gravityAnomalyAperiodicNoise(1.0E-4D,0.0D,0);
        boolean aperiodicNoiseProfile=Double.isFinite(noiseAtZero)
            && noiseAtZero>=-1.0D && noiseAtZero<=1.0D
            && Math.abs(noiseAtZero-noiseRepeated)<=1.0E-12D
            && Math.abs(noiseLater-noiseAtZero)>.1D
            && Math.abs(noiseAtBasePeriod-noiseAtZero)>.01D
            && Math.abs(noiseAcrossBoundary-noiseNearBoundary)<1.0E-5D
            && gravityAnomalyAperiodicNoise(Double.NaN,0.0D,0)==0.0D
            && gravityAnomalyAperiodicNoise(0.0D,Double.POSITIVE_INFINITY,0)==0.0D;
        if(!aperiodicNoiseProfile)
            throw new IllegalStateException("CI aperiodic gravity turbulence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_APERIODIC_TURBULENCE_VERIFIED amplitude={} cellScale={} timeDriftX={} timeDriftZ={} deterministic=true bounded=true smooth=true nonRepeating=true",
            GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE,GRAVITY_APERIODIC_TURBULENCE_SPATIAL_SCALE,
            GRAVITY_APERIODIC_TURBULENCE_TIME_X,GRAVITY_APERIODIC_TURBULENCE_TIME_Z);
        double shearAtThreshold=gravityAnomalyCrossShear(GRAVITY_LIFT_ZONE_THRESHOLD);
        double shearAtMidpoint=gravityAnomalyCrossShear(.71D);
        double shearAtPeak=gravityAnomalyCrossShear(1.0D);
        boolean shearProfile=shearAtThreshold==0.0D
            && Math.abs(shearAtMidpoint-.00232D)<1.0E-12D
            && Math.abs(shearAtPeak-.00464D)<1.0E-12D
            && gravityAnomalyCrossShear(2.0D)==shearAtPeak
            && gravityAnomalyCrossShear(-1.0D)==0.0D
            && gravityAnomalyCrossShear(Double.NaN)==0.0D
            && gravityAnomalyCrossShear(Double.POSITIVE_INFINITY)==0.0D
            && gravityAnomalyCrossShear(1.0D)<=.00464D+1.0E-12D
            && GRAVITY_LIFT_BASE_DRIFT+shearAtPeak<.04D;
        if(!(bounded && deterministic && pockets && drifting && invalidRejected && shearProfile))
            throw new IllegalStateException("CI gravity anomaly field regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_TURBULENCE_VERIFIED baseWeight=1.0 crossAmplitude=0.12 bounded=true deterministic=true liftPocket=true sinkPocket=true timeDrift=true invalidRejected=true");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_CROSS_SHEAR_PROFILE_VERIFIED threshold=0.42 max=0.00464 maxCombinedDrift=0.03964 monotonic=true bounded=true deterministic=true invalidRejected=true");

        double[] coordinates={-48.0D,-24.0D,0.0D,24.0D,48.0D};
        int[] ticks={86,240,1200,6000};
        int samples=0;
        for(double sampleX:coordinates)
            for(double sampleZ:coordinates)
                for(int sampleTick:ticks) {
                    double sample=gravityAnomalyField(sampleX,sampleZ,sampleTick);
                    if(!Double.isFinite(sample) || sample< -1.0D || sample>1.0D
                        || Math.abs(sample-gravityAnomalyField(sampleX,sampleZ,sampleTick))>1.0E-12D)
                        throw new IllegalStateException("CI multi-scale gravity anomaly grid regression");
                    samples++;
                }
        double sampleX=-17.5D,sampleZ=20.5D;
        double sampleTime=85.0D;
        double expectedBase=Math.sin(sampleX*.115D+Math.sin(sampleTime*.011D)*1.8D)
            *Math.cos(sampleZ*.125D-sampleTime*.008D);
        double expectedCross=Math.sin((sampleX+sampleZ)*.047D
            +Math.sin(sampleTime*.013D)*.9D-sampleTime*.009D);
        double expectedTurbulence=Math.sin((sampleX-sampleZ)*.083D
            +Math.sin(sampleTime*.017D)*1.3D+sampleTime*.021D);
        double expectedMicroTurbulence=Math.sin((sampleX*.17D+sampleZ*.13D)
            +Math.sin(sampleTime*.027D)*.8D+sampleTime*.031D
            +expectedCross*GRAVITY_MICRO_PHASE_WARP_AMPLITUDE);
        double expectedCrossAtZero=Math.sin((sampleX+sampleZ)*.047D);
        double expectedMicroAtZero=Math.sin(sampleX*.17D+sampleZ*.13D
            +expectedCrossAtZero*GRAVITY_MICRO_PHASE_WARP_AMPLITUDE);
        double expectedMicroLater=Math.sin((sampleX*.17D+sampleZ*.13D)
            +Math.sin(85.0D*.027D)*.8D+85.0D*.031D
            +expectedCross*GRAVITY_MICRO_PHASE_WARP_AMPLITUDE);
        double expectedMicroLaterUnwarped=Math.sin((sampleX*.17D+sampleZ*.13D)
            +Math.sin(85.0D*.027D)*.8D+85.0D*.031D);
        double expectedNoise=gravityAnomalyAperiodicNoise(sampleX,sampleZ,85);
        double expected=Math.max(-1.0D,Math.min(1.0D,
            expectedBase+.12D*expectedCross+.06D*expectedTurbulence+.035D*expectedMicroTurbulence
                +GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE*expectedNoise));
        double expectedNoiseAtZero=gravityAnomalyAperiodicNoise(sampleX,sampleZ,0);
        double expectedAtZero=Math.max(-1.0D,Math.min(1.0D,
            Math.sin(sampleX*.115D)*Math.cos(sampleZ*.125D)
                +.12D*Math.sin((sampleX+sampleZ)*.047D)
                +.06D*Math.sin((sampleX-sampleZ)*.083D)
                +.035D*expectedMicroAtZero
                +GRAVITY_APERIODIC_TURBULENCE_AMPLITUDE*expectedNoiseAtZero));
        double at85Before=gravityAnomalyField(sampleX,sampleZ,85);
        long refreshCountAt85=gravityFieldCacheRefreshCount;
        double sameTickSample=gravityAnomalyField(sampleX+1.0D,sampleZ-1.0D,85);
        long refreshCountAfterSameTick=gravityFieldCacheRefreshCount;
        double atZeroAfterRewind=gravityAnomalyField(sampleX,sampleZ,0);
        long refreshCountAfterRewind=gravityFieldCacheRefreshCount;
        double at85AfterRewind=gravityAnomalyField(sampleX,sampleZ,85);
        long refreshCountAfterSecondRewind=gravityFieldCacheRefreshCount;
        if(samples!=100
            || Math.abs(at85Before-expected)>1.0E-12D
            || !Double.isFinite(sameTickSample) || sameTickSample< -1.0D || sameTickSample>1.0D
            || refreshCountAfterSameTick!=refreshCountAt85
            || refreshCountAfterRewind!=refreshCountAt85+1L
            || refreshCountAfterSecondRewind!=refreshCountAt85+2L
            || Math.abs(atZeroAfterRewind-expectedAtZero)>1.0E-12D
            || Math.abs(at85AfterRewind-expected)>1.0E-12D
            || gravityFieldCachedTick!=85
            || Math.abs(expectedMicroLater-expectedMicroAtZero)<=.1D
            || Math.abs(expectedMicroLater-expectedMicroLaterUnwarped)<=.05D)
            throw new IllegalStateException("CI multi-scale gravity anomaly composition/cache regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_PHASE_WARP_VERIFIED amplitude=0.55 source=crossWave extraTrigPerPlayer=0 nonlinearCoupling=true");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_MULTI_SCALE_VERIFIED turbulenceAmplitude=0.06 samples={} deterministic=true bounded=true",samples);
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_MICRO_TURBULENCE_VERIFIED amplitude=0.035 spatialFrequencyX=0.17 spatialFrequencyZ=0.13 timeWarped=true");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_TIME_PHASE_CACHE_VERIFIED cachedOffsets=8 sameTickReuse=true tickRewind=true");
    }

    private static void verifyRiftGravityEligibility() {
        boolean[][] cases={
            {true,false,false,false,false,false,false,true},
            {false,false,false,false,false,false,false,false},
            {true,true,false,false,false,false,false,false},
            {true,false,true,false,false,false,false,false},
            {true,false,false,true,false,false,false,false},
            {true,false,false,false,true,false,false,false},
            {true,false,false,false,false,true,false,false},
            {true,false,false,false,false,false,true,false}
        };
        for(boolean[] sample:cases) {
            boolean actual=shouldApplyRiftGravity(
                sample[0],sample[1],sample[2],sample[3],sample[4],sample[5],sample[6]);
            if(actual!=sample[7])
                throw new IllegalStateException("CI rift gravity flight eligibility regression");
        }
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FLIGHT_IMMUNITY_VERIFIED");
    }

    /**
     * Exercise the actual Forge player flight flag, not only a boolean test fixture.
     * The equipment and flight state are restored in the same server tick.
     */
    private static void verifyActualElytraFlightImmunity(
            net.minecraft.server.level.ServerPlayer player) {
        net.minecraft.world.item.ItemStack previousChest=
            player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).copy();
        try {
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ELYTRA));
            player.startFallFlying();
            boolean fallFlying=player.isFallFlying();
            boolean gravityEligible=shouldApplyRiftGravity(player);
            if(!fallFlying || gravityEligible)
                throw new IllegalStateException("CI actual elytra flight immunity regression");
            LOGGER.info("BLACK_FREQUENCY_TEST ACTUAL_ELYTRA_FLIGHT_IMMUNITY_VERIFIED");
        } finally {
            player.stopFallFlying();
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,previousChest);
        }
    }

    /**
     * A mounted player must inherit the vehicle's motion rather than receive
     * independent rift impulses. The disposable CI probe mounts and dismounts
     * the real ServerPlayer in one tick and restores its position and velocity.
     */
    private static void verifyActualPassengerGravityImmunity(
            ServerLevel level,net.minecraft.server.level.ServerPlayer player) {
        if(player.isPassenger())
            throw new IllegalStateException("CI passenger probe requires an unmounted player");
        net.minecraft.world.phys.Vec3 previousPosition=player.position();
        net.minecraft.world.phys.Vec3 previousMovement=player.getDeltaMovement();
        float previousFallDistance=player.fallDistance;
        boolean previousHurtMarked=player.hurtMarked;
        State probe=new State();
        probe.x=(int)Math.floor(previousPosition.x);
        probe.z=(int)Math.floor(previousPosition.z);
        double testX=probe.x+.5D,testZ=probe.z+.5D;
        int nearLiftTick=-1,fullLiftTick=-1,nearSinkTick=-1,fullSinkTick=-1;
        double nearLiftZone=Double.NaN,fullLiftZone=Double.NaN;
        double nearSinkMagnitude=Double.NaN,fullSinkMagnitude=Double.NaN;
        double nearLiftMin=GRAVITY_LIFT_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH*.20D;
        double nearLiftMax=GRAVITY_LIFT_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH*.30D;
        double nearSinkMin=GRAVITY_SINK_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH*.20D;
        double nearSinkMax=GRAVITY_SINK_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH*.30D;
        double[] fieldSamples=new double[CI_GRAVITY_TRAJECTORY_SEARCH_TICKS+CI_GRAVITY_TRAJECTORY_STEPS];
        int loadedChunkX=probe.x>>4,loadedChunkZ=probe.z>>4;
        if(!level.hasChunk(loadedChunkX,loadedChunkZ))
            throw new IllegalStateException("CI gravity probe escaped the player's already-loaded chunk");
        var probeWorldBorder=level.getWorldBorder();
        double probeHalfWidth=player.getBbWidth()*.5D;
        long worldSeed=level.getSeed();
        int trajectoryCandidatesChecked=0;
        int liftTrajectoryStart=-1,sinkTrajectoryStart=-1;
        boolean trajectoryPositionFound=false;
        // Seeded turbulence can leave one column without both eight-tick ramps.
        // Search a small deterministic grid inside the same loaded chunk; never request a neighboring chunk.
        for(int offsetX=4;offsetX<=12 && !trajectoryPositionFound;offsetX+=4) {
            for(int offsetZ=4;offsetZ<=12;offsetZ+=4) {
                double candidateX=(((long)loadedChunkX)<<4)+offsetX+.5D;
                double candidateZ=(((long)loadedChunkZ)<<4)+offsetZ+.5D;
                if(candidateX-probeHalfWidth<probeWorldBorder.getMinX()
                    || candidateX+probeHalfWidth>probeWorldBorder.getMaxX()
                    || candidateZ-probeHalfWidth<probeWorldBorder.getMinZ()
                    || candidateZ+probeHalfWidth>probeWorldBorder.getMaxZ()) continue;
                int minChunkX=((int)Math.floor(candidateX-probeHalfWidth))>>4;
                int maxChunkX=((int)Math.floor(Math.nextDown(candidateX+probeHalfWidth)))>>4;
                int minChunkZ=((int)Math.floor(candidateZ-probeHalfWidth))>>4;
                int maxChunkZ=((int)Math.floor(Math.nextDown(candidateZ+probeHalfWidth)))>>4;
                if(minChunkX!=loadedChunkX || maxChunkX!=loadedChunkX
                    || minChunkZ!=loadedChunkZ || maxChunkZ!=loadedChunkZ) continue;
                trajectoryCandidatesChecked++;
                for(int tick=0;tick<fieldSamples.length;tick++)
                    fieldSamples[tick]=gravityAnomalyField(candidateX,candidateZ,tick,worldSeed);
                int candidateLiftStart=findGravityActivationTrajectoryStart(fieldSamples,true);
                int candidateSinkStart=findGravityActivationTrajectoryStart(fieldSamples,false);
                if(candidateLiftStart>=0 && candidateSinkStart>=0) {
                    testX=candidateX;
                    testZ=candidateZ;
                    liftTrajectoryStart=candidateLiftStart;
                    sinkTrajectoryStart=candidateSinkStart;
                    trajectoryPositionFound=true;
                    break;
                }
            }
        }
        if(!trajectoryPositionFound)
            throw new IllegalStateException("CI could not find bounded lift/sink trajectories in the already-loaded chunk");
        LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_TRAJECTORY_PROBE_POSITION_VERIFIED candidatesChecked={} x={} z={} chunkX={} chunkZ={} sameLoadedChunk=true playerBoxInsideChunk=true noChunkLoad=true",
            trajectoryCandidatesChecked,testX,testZ,loadedChunkX,loadedChunkZ);
        for(int tick=0;tick<CI_GRAVITY_TRAJECTORY_SEARCH_TICKS && (nearLiftTick<0 || fullLiftTick<0
                || nearSinkTick<0 || fullSinkTick<0);tick++) {
            double zone=fieldSamples[tick];
            if(!Double.isFinite(zone)) continue;
            if(nearLiftTick<0 && zone>=nearLiftMin && zone<=nearLiftMax) {
                nearLiftTick=tick;
                nearLiftZone=zone;
            }
            if(fullLiftTick<0 && zone>=GRAVITY_LIFT_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH) {
                fullLiftTick=tick;
                fullLiftZone=zone;
            }
            double sinkMagnitude=-zone;
            if(nearSinkTick<0 && sinkMagnitude>=nearSinkMin && sinkMagnitude<=nearSinkMax) {
                nearSinkTick=tick;
                nearSinkMagnitude=sinkMagnitude;
            }
            if(fullSinkTick<0 && sinkMagnitude>=GRAVITY_SINK_ZONE_THRESHOLD+GRAVITY_ZONE_ACTIVATION_WIDTH) {
                fullSinkTick=tick;
                fullSinkMagnitude=sinkMagnitude;
            }
        }
        if(nearLiftTick<0 || fullLiftTick<0 || nearSinkTick<0 || fullSinkTick<0)
            throw new IllegalStateException("CI could not sample both gravity activation ramps");
        int probeTicks=fullLiftTick;
        double probeZone=fullLiftZone;
        probe.finaleTicks=probeTicks;
        double testY=Math.max(level.getMinBuildHeight()+2.0D,
            Math.min(level.getMaxBuildHeight()-3.0D,level.getSeaLevel()+10.0D));
        var mount=net.minecraft.world.entity.EntityType.PIG.create(level);
        if(mount==null)
            throw new IllegalStateException("CI could not create passenger probe mount");
        try {
            player.setPos(testX,testY,testZ);
            if((player.blockPosition().getX()>>4)!=loadedChunkX
                || (player.blockPosition().getZ()>>4)!=loadedChunkZ
                || !level.hasChunk(loadedChunkX,loadedChunkZ))
                throw new IllegalStateException("CI gravity probe escaped the player's already-loaded chunk");
            verifyGravityProbeWorldBorderGuard(level,player);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            probe.finaleTicks=nearLiftTick;
            boolean nearLiftApplied=applyRiftGravityImpulse(level,probe,player);
            double nearLiftImpulse=player.getDeltaMovement().y;
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;

            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            probe.finaleTicks=fullLiftTick;
            boolean fullLiftApplied=applyRiftGravityImpulse(level,probe,player);
            double fullLiftImpulse=player.getDeltaMovement().y;
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;

            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            probe.finaleTicks=nearSinkTick;
            boolean nearSinkApplied=applyRiftGravityImpulse(level,probe,player);
            double nearSinkImpulse=player.getDeltaMovement().y;
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;

            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            probe.finaleTicks=fullSinkTick;
            boolean fullSinkApplied=applyRiftGravityImpulse(level,probe,player);
            double fullSinkImpulse=player.getDeltaMovement().y;
            if(!nearLiftApplied || !fullLiftApplied || !nearSinkApplied || !fullSinkApplied
                || !(nearLiftImpulse>0.0D) || !(fullLiftImpulse>nearLiftImpulse)
                || !(nearSinkImpulse<0.0D) || !(fullSinkImpulse<nearSinkImpulse)
                || !(nearLiftZone<fullLiftZone) || !(nearSinkMagnitude<fullSinkMagnitude))
                throw new IllegalStateException("CI actual ServerPlayer gravity activation ramp regression");
            LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_RAMP_VERIFIED liftNearZone={} liftFullZone={} liftNearY={} liftFullY={} sinkNearMagnitude={} sinkFullMagnitude={} sinkNearY={} sinkFullY={} sameLoadedChunk=true",
                nearLiftZone,fullLiftZone,nearLiftImpulse,fullLiftImpulse,
                nearSinkMagnitude,fullSinkMagnitude,nearSinkImpulse,fullSinkImpulse);

            verifyActualGravityRampTrajectory(level,probe,player,testX,testY,testZ,liftTrajectoryStart,true);
            verifyActualGravityRampTrajectory(level,probe,player,testX,testY,testZ,sinkTrajectoryStart,false);
            LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_TRAJECTORY_VERIFIED liftSteps={} sinkSteps={} integratedPosition=true loadedChunksOnly=true",
                CI_GRAVITY_TRAJECTORY_STEPS,CI_GRAVITY_TRAJECTORY_STEPS);

            probe.finaleTicks=fullLiftTick;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;
            boolean controlImpulseApplied=fullLiftApplied;
            if(!controlImpulseApplied || !(fullLiftImpulse>0.0D))
                throw new IllegalStateException("CI lift-zone control probe did not apply an impulse");
            double halfStrength=restorationGravityScale(RESTORE_GRAVITY_FADE_TICKS/2);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            boolean fullFadeImpulse=applyRiftGravityImpulse(level,probe,player,1.0D);
            double fullLift=player.getDeltaMovement().y;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            boolean halfFadeImpulse=applyRiftGravityImpulse(level,probe,player,halfStrength);
            double halfLift=player.getDeltaMovement().y;
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            boolean zeroFadeImpulse=applyRiftGravityImpulse(level,probe,player,
                restorationGravityScale(RESTORE_GRAVITY_FADE_TICKS));
            double zeroLift=player.getDeltaMovement().y;
            if(!fullFadeImpulse || !halfFadeImpulse || zeroFadeImpulse
                || !(fullLift>0.0D) || Math.abs(halfLift-fullLift*halfStrength)>1.0E-9D
                || Math.abs(zeroLift)>1.0E-9D)
                throw new IllegalStateException("CI actual player restoration gravity fade scaling regression");
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_PLAYER_IMPULSE_VERIFIED full=true half=true zero=true");
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;
            mount.moveTo(testX,testY,testZ,player.getYRot(),player.getXRot());
            mount.setDeltaMovement(.03D,.01D,-.02D);
            if(!level.addFreshEntity(mount) || !player.startRiding(mount,true))
                throw new IllegalStateException("CI could not mount player for gravity probe");
            net.minecraft.world.phys.Vec3 expectedPlayerMotion=
                new net.minecraft.world.phys.Vec3(.17D,.08D,-.13D);
            player.setDeltaMovement(expectedPlayerMotion);
            net.minecraft.world.phys.Vec3 expectedVehicleMotion=mount.getDeltaMovement();
            boolean impulseApplied=applyRiftGravityImpulse(level,probe,player);
            if(!player.isPassenger() || shouldApplyRiftGravity(player) || impulseApplied
                || !expectedPlayerMotion.equals(player.getDeltaMovement())
                || !expectedVehicleMotion.equals(mount.getDeltaMovement()))
                throw new IllegalStateException("CI mounted player received an independent rift impulse");
            LOGGER.info("BLACK_FREQUENCY_TEST ACTUAL_PASSENGER_GRAVITY_IMMUNITY_VERIFIED");
            LOGGER.info("BLACK_FREQUENCY_TEST PASSENGER_RIFT_IMPULSE_SUPPRESSED_VERIFIED controlImpulse=true zone={} tick={}",
                probeZone,probeTicks);
        } finally {
            player.stopRiding();
            mount.discard();
            player.setPos(previousPosition.x,previousPosition.y,previousPosition.z);
            player.setDeltaMovement(previousMovement);
            player.fallDistance=previousFallDistance;
            player.hurtMarked=previousHurtMarked;
        }
    }

    /** Find a short, bounded activation ramp in the real time-varying field at the probe position. */
    private static int findGravityActivationTrajectoryStart(double[] fieldSamples,boolean lift) {
        if(fieldSamples==null || fieldSamples.length<CI_GRAVITY_TRAJECTORY_STEPS) return -1;
        double threshold=lift?GRAVITY_LIFT_ZONE_THRESHOLD:GRAVITY_SINK_ZONE_THRESHOLD;
        for(int start=0;start<=fieldSamples.length-CI_GRAVITY_TRAJECTORY_STEPS;start++) {
            double firstMagnitude=lift?fieldSamples[start]:-fieldSamples[start];
            double previous=gravityAnomalyZoneActivation(firstMagnitude,threshold);
            if(previous<.02D || previous>.20D) continue;
            boolean valid=true;
            double maximumStep=0.0D;
            for(int step=1;step<CI_GRAVITY_TRAJECTORY_STEPS;step++) {
                double zone=fieldSamples[start+step];
                double magnitude=lift?zone:-zone;
                double activation=gravityAnomalyZoneActivation(magnitude,threshold);
                if(!Double.isFinite(activation) || activation<=.005D) {
                    valid=false;
                    break;
                }
                maximumStep=Math.max(maximumStep,Math.abs(activation-previous));
                previous=activation;
            }
            if(valid && previous>=.80D && maximumStep<=.45D) return start;
        }
        return -1;
    }

    /**
     * Check a predicted player box without asking the server to load any of its chunks.
     * The chunk guard covers every horizontal chunk touched by the AABB before collision queries.
     */
    private static boolean gravityProbeCollisionFree(
            ServerLevel level,net.minecraft.server.level.ServerPlayer player,
            net.minecraft.world.phys.AABB box) {
        if(level==null || player==null || box==null
            || !Double.isFinite(box.minX) || !Double.isFinite(box.minY) || !Double.isFinite(box.minZ)
            || !Double.isFinite(box.maxX) || !Double.isFinite(box.maxY) || !Double.isFinite(box.maxZ)
            || box.minY<level.getMinBuildHeight() || box.maxY>level.getMaxBuildHeight()
            // isWithinBounds(AABB) accepts any overlap; a player probe must be fully contained.
            // Reject edge-crossing boxes before consulting chunk state or collision shapes.
            || !gravityProbeInsideWorldBorder(level,box)) return false;
        int minChunkX=((int)Math.floor(box.minX))>>4;
        int maxChunkX=((int)Math.floor(Math.nextDown(box.maxX)))>>4;
        int minChunkZ=((int)Math.floor(box.minZ))>>4;
        int maxChunkZ=((int)Math.floor(Math.nextDown(box.maxZ)))>>4;
        if(maxChunkX<minChunkX || maxChunkZ<minChunkZ) return false;
        for(int chunkX=minChunkX;chunkX<=maxChunkX;chunkX++)
            for(int chunkZ=minChunkZ;chunkZ<=maxChunkZ;chunkZ++)
                if(!level.hasChunk(chunkX,chunkZ)) return false;
        return level.noCollision(player,box);
    }

    /** Require the complete player box to remain inside the border, not merely overlap it. */
    private static boolean gravityProbeInsideWorldBorder(
            ServerLevel level,net.minecraft.world.phys.AABB box) {
        if(level==null || box==null
            || !Double.isFinite(box.minX) || !Double.isFinite(box.maxX)
            || !Double.isFinite(box.minZ) || !Double.isFinite(box.maxZ)) return false;
        var border=level.getWorldBorder();
        return box.minX>=border.getMinX() && box.maxX<=border.getMaxX()
            && box.minZ>=border.getMinZ() && box.maxZ<=border.getMaxZ();
    }

    /** Exercise whole-box and edge-crossing world-border rejection without changing the border. */
    private static void verifyGravityProbeWorldBorderGuard(
            ServerLevel level,net.minecraft.server.level.ServerPlayer player) {
        var border=level.getWorldBorder();
        net.minecraft.world.phys.AABB playerBox=player.getBoundingBox();
        double width=playerBox.maxX-playerBox.minX;
        double outsideX=border.getMaxX()+1.0D;
        net.minecraft.world.phys.AABB outsideBox=new net.minecraft.world.phys.AABB(
            outsideX,playerBox.minY,playerBox.minZ,
            outsideX+width,playerBox.maxY,playerBox.maxZ);
        net.minecraft.world.phys.AABB straddlingBox=new net.minecraft.world.phys.AABB(
            border.getMaxX()-Math.min(.25D,width*.25D),playerBox.minY,playerBox.minZ,
            border.getMaxX()+.01D,playerBox.maxY,playerBox.maxZ);
        boolean insideAccepted=gravityProbeInsideWorldBorder(level,playerBox);
        boolean outsideRejected=!gravityProbeInsideWorldBorder(level,outsideBox);
        boolean straddlingRejected=!gravityProbeInsideWorldBorder(level,straddlingBox);
        if(!insideAccepted || !outsideRejected || !straddlingRejected
            || gravityProbeCollisionFree(level,player,outsideBox)
            || gravityProbeCollisionFree(level,player,straddlingBox))
            throw new IllegalStateException("CI gravity probe world-border guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_PROBE_WORLD_BORDER_GUARD_VERIFIED insideAccepted=true outsideRejected=true straddlingRejected=true loadedChunksOnly=true");
    }

    /**
     * Integrate eight consecutive impulses on a real ServerPlayer. The probe advances its
     * position by the resulting velocity, but every position is checked before use so this
     * diagnostic never requests an unloaded chunk or edits world blocks.
     */
    private static void verifyActualGravityRampTrajectory(
            ServerLevel level,State probe,net.minecraft.server.level.ServerPlayer player,
            double startX,double startY,double startZ,int startTick,boolean lift) {
        net.minecraft.world.phys.Vec3 originalPosition=player.position();
        net.minecraft.world.phys.Vec3 originalMovement=player.getDeltaMovement();
        float originalFallDistance=player.fallDistance;
        boolean originalHurtMarked=player.hurtMarked;
        double currentX=startX,currentY=startY,currentZ=startZ;
        net.minecraft.world.phys.Vec3 movement=net.minecraft.world.phys.Vec3.ZERO;
        double firstVelocityY=Double.NaN,lastVelocityY=Double.NaN;
        double firstActivation=Double.NaN,lastActivation=Double.NaN,maximumActivationStep=0.0D;
        double maximumHorizontalVelocity=0.0D,maximumVerticalVelocity=0.0D;
        double previousActivation=Double.NaN;
        int collisionSamples=0;
        int terminalVelocityNoopSteps=0;
        double threshold=lift?GRAVITY_LIFT_ZONE_THRESHOLD:GRAVITY_SINK_ZONE_THRESHOLD;
        double verticalCap=lift?GRAVITY_LIFT_VERTICAL_VELOCITY_CAP:GRAVITY_SINK_VERTICAL_VELOCITY_CAP;
        try {
            player.setPos(startX,startY,startZ);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            if(!gravityProbeCollisionFree(level,player,player.getBoundingBox()))
                throw new IllegalStateException("CI gravity trajectory starts inside a collision or unloaded chunk");
            collisionSamples++;
            for(int step=0;step<CI_GRAVITY_TRAJECTORY_STEPS;step++) {
                if(!Double.isFinite(currentX) || !Double.isFinite(currentY) || !Double.isFinite(currentZ)
                    || currentY<level.getMinBuildHeight()+1.0D || currentY>level.getMaxBuildHeight()-2.0D)
                    throw new IllegalStateException("CI gravity trajectory left valid world coordinates");
                int chunkX=((int)Math.floor(currentX))>>4;
                int chunkZ=((int)Math.floor(currentZ))>>4;
                if(!level.hasChunk(chunkX,chunkZ))
                    throw new IllegalStateException("CI gravity trajectory would enter an unloaded chunk");
                int tick=startTick+step;
                double zone=gravityAnomalyField(currentX,currentZ,tick,level.getSeed());
                double magnitude=lift?zone:-zone;
                double activation=gravityAnomalyZoneActivation(magnitude,threshold);
                if(!Double.isFinite(activation) || activation<=.005D)
                    throw new IllegalStateException("CI actual ServerPlayer left its active gravity ramp");
                if(step>0) maximumActivationStep=Math.max(maximumActivationStep,
                    Math.abs(activation-previousActivation));
                if(maximumActivationStep>.50D)
                    throw new IllegalStateException("CI actual ServerPlayer gravity activation changed too abruptly");
                player.setPos(currentX,currentY,currentZ);
                player.setDeltaMovement(movement);
                probe.finaleTicks=tick;
                double beforeVelocityY=movement.y;
                boolean applied=applyRiftGravityImpulse(level,probe,player);
                net.minecraft.world.phys.Vec3 nextMovement=player.getDeltaMovement();
                double impulseY=nextMovement.y-beforeVelocityY;
                // Reaching a terminal cap legitimately turns later acceleration into a no-op.
                // Treat that as saturation, not as a failed impulse, while rejecting any reversal.
                boolean atVerticalLimit=lift
                    ? beforeVelocityY>=verticalCap-1.0E-9D
                    : beforeVelocityY<=-verticalCap+1.0E-9D;
                boolean verticalDirectionPreserved=lift
                    ? nextMovement.y>=beforeVelocityY-1.0E-9D
                        && (atVerticalLimit || nextMovement.y>beforeVelocityY+1.0E-9D)
                    : nextMovement.y<=beforeVelocityY+1.0E-9D
                        && (atVerticalLimit || nextMovement.y<beforeVelocityY-1.0E-9D);
                if(!applied || !Double.isFinite(nextMovement.x) || !Double.isFinite(nextMovement.y)
                    || !Double.isFinite(nextMovement.z) || !verticalDirectionPreserved)
                    throw new IllegalStateException("CI actual ServerPlayer gravity trajectory impulse regression");
                if(atVerticalLimit && Math.abs(impulseY)<=1.0E-9D) terminalVelocityNoopSteps++;
                double horizontalVelocity=Math.max(Math.abs(nextMovement.x),Math.abs(nextMovement.z));
                double verticalVelocity=Math.abs(nextMovement.y);
                if(horizontalVelocity>GRAVITY_ANOMALY_HORIZONTAL_DRIFT_CAP+1.0E-9D
                    || verticalVelocity>verticalCap+1.0E-9D)
                    throw new IllegalStateException("CI actual ServerPlayer gravity trajectory exceeded a velocity cap");
                maximumHorizontalVelocity=Math.max(maximumHorizontalVelocity,horizontalVelocity);
                maximumVerticalVelocity=Math.max(maximumVerticalVelocity,verticalVelocity);
                if(step==0) {
                    firstVelocityY=nextMovement.y;
                    firstActivation=activation;
                }
                lastVelocityY=nextMovement.y;
                lastActivation=activation;
                double nextX=currentX+nextMovement.x;
                double nextY=currentY+nextMovement.y;
                double nextZ=currentZ+nextMovement.z;
                net.minecraft.world.phys.AABB nextBox=player.getBoundingBox().move(
                    nextMovement.x,nextMovement.y,nextMovement.z);
                if(!gravityProbeCollisionFree(level,player,nextBox))
                    throw new IllegalStateException("CI gravity trajectory entered a collision or unloaded chunk");
                collisionSamples++;
                currentX=nextX;
                currentY=nextY;
                currentZ=nextZ;
                movement=nextMovement;
                previousActivation=activation;
            }
            if(collisionSamples!=CI_GRAVITY_TRAJECTORY_STEPS+1)
                throw new IllegalStateException("CI gravity trajectory collision sample count regression");
            LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_COLLISION_PATH_VERIFIED mode={} steps={} collisionSamples={} collisionFree=true loadedChunksOnly=true",
                lift?"lift":"sink",CI_GRAVITY_TRAJECTORY_STEPS,collisionSamples);
            if(lift?!(lastVelocityY>firstVelocityY && lastActivation>=.45D
                    && lastActivation-firstActivation>=.25D)
                :!(lastVelocityY<firstVelocityY && lastActivation>=.45D
                    && lastActivation-firstActivation>=.25D))
                throw new IllegalStateException("CI actual ServerPlayer trajectory did not cross the gravity ramp");
            LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_ZONE_SERVER_PLAYER_TRAJECTORY_MODE_VERIFIED mode={} steps={} startTick={} firstActivation={} lastActivation={} maxActivationStep={} firstVelocityY={} finalVelocityY={} terminalVelocityNoopSteps={} maxHorizontalVelocity={} maxVerticalVelocity={} horizontalCap={} verticalCap={} loadedChunksOnly=true",
                lift?"lift":"sink",CI_GRAVITY_TRAJECTORY_STEPS,startTick,
                firstActivation,lastActivation,maximumActivationStep,firstVelocityY,lastVelocityY,
                terminalVelocityNoopSteps,maximumHorizontalVelocity,maximumVerticalVelocity,
                GRAVITY_ANOMALY_HORIZONTAL_DRIFT_CAP,verticalCap);
        } finally {
            player.setPos(originalPosition.x,originalPosition.y,originalPosition.z);
            player.setDeltaMovement(originalMovement);
            player.fallDistance=originalFallDistance;
            player.hurtMarked=originalHurtMarked;
        }
    }

    /**
     * Apply the same bounded anomaly impulse used by the live player loop.
     * Keeping the eligibility guard here lets the real-entity CI probe verify
     * that passengers retain both their own velocity and their vehicle motion.
     */
    private static boolean applyRiftGravityImpulse(
            ServerLevel level,State s,net.minecraft.server.level.ServerPlayer player) {
        return applyRiftGravityImpulse(level,s,player,1.0D);
    }

    /** Smooth, bounded radial falloff shared by player impulses and their visual cues. */
    private static double gravityAnomalyRadialFade(double distanceSquared) {
        if(!Double.isFinite(distanceSquared) || distanceSquared<0.0D) return 0.0D;
        double distance=Math.sqrt(distanceSquared);
        double progress=(GRAVITY_ANOMALY_OUTER_RADIUS-distance)/GRAVITY_ANOMALY_FADE_WIDTH;
        double t=Math.max(0.0D,Math.min(1.0D,progress));
        // Reuse the C2 quintic curve so acceleration does not kink at either radial boundary.
        return gravityAnomalyFade(t);
    }

    private static boolean applyRiftGravityImpulse(
            ServerLevel level,State s,net.minecraft.server.level.ServerPlayer player,double strength) {
        if(!shouldApplyRiftGravity(player) || !Double.isFinite(strength) || strength<=0.0D) return false;
        double impulseStrength=Math.min(1.0D,strength);
        double px=player.getX(),pz=player.getZ();
        double ax=px-s.x,ay=player.getY()-level.getSeaLevel(),az=pz-s.z;
        double distanceSquared=ax*ax+ay*ay+az*az;
        if(distanceSquared>=GRAVITY_ANOMALY_OUTER_RADIUS*GRAVITY_ANOMALY_OUTER_RADIUS) return false;
        double fade=gravityAnomalyRadialFade(distanceSquared);
        if(fade<=0.0D) return false;
        double zone=gravityAnomalyField(px,pz,s.finaleTicks,level.getSeed());
        if(zone>GRAVITY_LIFT_ZONE_THRESHOLD) {
            double activation=gravityAnomalyZoneActivation(zone,GRAVITY_LIFT_ZONE_THRESHOLD);
            if(activation<=0.0D) return false;
            var v=player.getDeltaMovement();
            double lift=Math.max(.045,Math.min(.19,.11+zone*.07))
                *fade*impulseStrength*activation;
            double crossShear=gravityAnomalyCrossShear(zone);
            double dx=(Math.sin(pz*.14+s.finaleTicks*.022)*GRAVITY_LIFT_BASE_DRIFT+crossShear)
                *fade*impulseStrength*activation;
            double dz=(Math.cos(px*.13-s.finaleTicks*.018)*GRAVITY_LIFT_BASE_DRIFT-crossShear)
                *fade*impulseStrength*activation;
            // Limit only the anomaly's additional drift. Never clamp
            // existing sprint, knockback or mod-provided velocity.
            double nextX=boundedAnomalyVelocity(v.x,dx);
            double nextZ=boundedAnomalyVelocity(v.z,dz);
            player.setDeltaMovement(nextX,boundedLiftVelocity(v.y,lift),nextZ);
            player.hurtMarked=true;
            if(fade*impulseStrength*activation>.25) player.fallDistance=0;
            return true;
        }
        if(zone< -GRAVITY_SINK_ZONE_THRESHOLD) {
            double activation=gravityAnomalyZoneActivation(-zone,GRAVITY_SINK_ZONE_THRESHOLD);
            if(activation<=0.0D) return false;
            var v=player.getDeltaMovement();
            double drag=1.0-.06*fade*impulseStrength*activation;
            player.setDeltaMovement(boundedSinkDrag(v.x,drag),
                boundedSinkVelocity(v.y,-.09*fade*impulseStrength*activation),boundedSinkDrag(v.z,drag));
            player.hurtMarked=true;
            return true;
        }
        return false;
    }

    /**
     * Keep the existing anomaly field for a short, persisted tail after beacon
     * activation. This touches player motion only; it never queries chunks or blocks.
     */
    private static void applyRestorationGravityFade(ServerLevel level,State s) {
        // restoreTicks advances once per server tick. Apply each point of the
        // 40-tick smoothstep curve instead of sampling it only eight times.
        if(!shouldApplyRestorationGravityFade(s.finaleTicks,s.restoreTicks)) return;
        if(CI_TEST && s.restoreTicks==0)
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_RUNTIME_STARTED ticks={}",
                RESTORE_GRAVITY_FADE_TICKS);
        if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest")) return;
        double strength=restorationGravityScale(s.restoreTicks);
        if(strength<=0.0D) return;
        for(var player:level.players())
            applyRiftGravityImpulse(level,s,player,strength);
    }

    private static void verifyBoundedSinkDrag() {
        double[][] cases={
            {0.4D,0.94D,0.376D},
            {-0.4D,0.94D,-0.376D},
            {0.4D,1.5D,0.4D},
            {0.4D,-0.5D,0.0D},
            {Double.NaN,0.94D,0.0D},
            {Double.POSITIVE_INFINITY,0.94D,0.0D},
            {0.4D,Double.NaN,0.4D}
        };
        for(double[] sample:cases) {
            double actual=boundedSinkDrag(sample[0],sample[1]);
            if(!Double.isFinite(actual) || Math.abs(actual-sample[2])>1.0E-9D)
                throw new IllegalStateException("CI sink drag regression: "+actual);
        }
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_SINK_DRAG_VERIFIED");
    }

    private static void verifyBoundedSinkVelocity() {
        double[][] cases={
            {0.0D,-0.09D,-0.09D},
            {-0.53D,-0.09D,-0.55D},
            {-0.8D,-0.09D,-0.89D},
            {-GRAVITY_SINK_VERTICAL_VELOCITY_CAP,-0.09D,-GRAVITY_SINK_VERTICAL_VELOCITY_CAP},
            {0.2D,-0.09D,0.11D},
            {Double.NaN,-0.09D,-0.09D},
            {Double.NEGATIVE_INFINITY,-0.09D,-0.09D},
            {0.2D,Double.NaN,0.2D},
            {-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE}
        };
        for(double[] sample:cases) {
            double actual=boundedSinkVelocity(sample[0],sample[1]);
            if(!Double.isFinite(actual) || Math.abs(actual-sample[2])>1.0E-9D)
                throw new IllegalStateException("CI sink velocity regression: "+actual);
        }
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_SINK_BOUNDS_VERIFIED");
    }

    private static void verifyBoundedLiftVelocity() {
        double[][] cases={
            {0.1D,0.05D,0.15D},
            {0.42D,0.05D,0.43D},
            {-0.3D,0.05D,-0.25D},
            {Double.NaN,0.05D,0.05D},
            {Double.POSITIVE_INFINITY,0.05D,0.05D},
            {0.2D,Double.NEGATIVE_INFINITY,0.2D},
            {0.8D,0.05D,0.8D},
            {GRAVITY_LIFT_VERTICAL_VELOCITY_CAP,0.19D,GRAVITY_LIFT_VERTICAL_VELOCITY_CAP},
            {0.8D,-0.05D,0.75D},
            {Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE},
            {-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE}
        };
        for(double[] sample:cases) {
            double actual=boundedLiftVelocity(sample[0],sample[1]);
            if(!Double.isFinite(actual) || Math.abs(actual-sample[2])>1.0E-9D)
                throw new IllegalStateException("CI lift velocity regression: "+actual);
        }
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_LIFT_BOUNDS_VERIFIED");
    }

    /** CI regression: anomaly drift must not overwrite sprint or knockback velocity. */
    private static void verifyBoundedAnomalyVelocity() {
        final double epsilon=1.0E-9D;
        double[][] cases={
            {0.0D,0.035D,0.035D},
            {0.34D,0.035D,0.35D},
            {-0.34D,-0.035D,-0.35D},
            {0.8D,0.035D,0.8D},
            {0.8D,-0.035D,0.765D},
            {-0.8D,-0.035D,-0.8D},
            {-0.8D,0.035D,-0.765D},
            {0.0D,0.0D,0.0D},
            {Double.NaN,0.035D,0.0D},
            {Double.POSITIVE_INFINITY,-0.035D,0.0D},
            {0.2D,Double.NaN,0.2D},
            {0.2D,Double.NEGATIVE_INFINITY,0.2D},
            {Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE},
            {-Double.MAX_VALUE,-Double.MAX_VALUE,-Double.MAX_VALUE}
        };
        for(double[] sample:cases) {
            double actual=boundedAnomalyVelocity(sample[0],sample[1]);
            if(!Double.isFinite(actual) || Math.abs(actual-sample[2])>epsilon)
                throw new IllegalStateException("CI anomaly drift velocity regression: "
                    +sample[0]+" + "+sample[1]+" => "+actual);
        }
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_VELOCITY_BOUNDS_VERIFIED");
    }

    /** A missing legacy key is valid; a present malformed list must be normalized and saved. */
    private static boolean isWellFormedSnapshotListTag(Tag tag) {
        if(tag==null) return true;
        if(!(tag instanceof ListTag list)) return false;
        return list.isEmpty() || list.getElementType()==Tag.TAG_COMPOUND;
    }

    private static final class State extends SavedData {
        long elapsed;
        int x, z, cursorX=-RADIUS, cursorZ=-RADIUS, cursorY;
        // New worlds scan from the visible surface downward. Missing NBT keys
        // deliberately default to false so older worlds keep their saved cursor order.
        boolean surfaceFirstScan;
        // Added after the original row-major cursor. Missing keys preserve old saves.
        boolean centerOutScan;
        int centerOutIndex;
        int edits;
        int finaleTicks;
        boolean started, finished, initialized, restoring, restored;
        // Transient: legacy saves may have a valid cursor but predate persisted x/z.
        boolean anchorCoordinatesNeedInitialization;
        int restoreIndex, restoreScan, restoreTicks, restoredBlocks, skippedOccupied, skippedInvalid;
        // Persist excess NBT list entries and missing records inferred from a damaged edit/snapshot count.
        int snapshotOverflowEntries;
        int snapshotMissingEntries;
        long beaconPos;
        // Distinguish an absent/corrupt NBT field from a legitimate beacon at BlockPos.ZERO.
        boolean beaconPosPresent;
        // A one-time recovery beacon is placed in a loaded, safe air block.
        // Keep it separate from the beacon that a player actually activates.
        long naturalBeaconPos=Long.MIN_VALUE;
        UUID naturalBeaconId;
        // CI diagnostics only; never persisted in production saves.
        long ciRejectedRestorePos=Long.MIN_VALUE;
        int ciFlowingReplaced;
        // Cosmetic-only cooldown; deliberately not persisted across world restarts.
        int surfaceCueCooldownTicks;
        boolean ciRestoredGravityGuardLogged;
        boolean ciRestoreUiAudiencePassLogged;
        // Transient recovery status: never serialized; rebuilt from loaded-chunk checks after restart.
        boolean unloadedSnapshotsPending;
        // Primitive keys keep deferred-chunk tracking allocation-free in the restore hot path.
        final LongOpenHashSet deferredRestoreChunks=new LongOpenHashSet();
        // Chunk availability is stable during one server-thread restore scan; cache it without boxing.
        final LongOpenHashSet restoreChunksCheckedThisTick=new LongOpenHashSet();
        final LongOpenHashSet restoreChunksLoadedThisTick=new LongOpenHashSet();
        // CI-only one-shot beacon removal: exercise recovery after a lost beacon.
        boolean ciNaturalBeaconRemoved, ciNaturalBeaconReplaced;
        boolean ciSparseRecoveryEligibilityLogged;
        boolean ciSurfaceFissureLogged;
        // CI-only retry suppression; never persisted and reset when a safe side appears.
        boolean ciBeaconApproachFailureLogged;
        // One-shot CI fixture proving the real loaded-world canopy sampler, never persisted.
        boolean ciCanopyWorldFixtureVerified;
        int ciCanopyFixtureSearchAttempts;
        boolean ciIdleBeaconVioletDustLogged;
        final List<CompoundTag> snapshots = new ArrayList<>();
        // Derived from validated snapshots on load and maintained on every runtime edit.
        // It is deliberately not persisted: the value is recomputed from the saved list.
        long retainedSnapshotEstimateBytes;
        boolean snapshotBudgetSkipLogged;
        // Persist one warning per recovery state; skippedInvalid retains the full count.
        boolean invalidSnapshotWarningLogged;
        // Reuse the transient audience/menu accumulator across restoration scans.
        // It is intentionally not serialized; each SavedData instance owns its own scratch state.
        final RestorePlayerScanState restorePlayerScan = new RestorePlayerScanState();
        // Reuse one transient packed-position decoder for the bounded snapshot scan.
        // The mutable cursor never escapes restore() and is intentionally not saved to NBT.
        final BlockPos.MutableBlockPos restoreCursor = new BlockPos.MutableBlockPos();
        // Reuse one cursor for the once-per-column world-border preflight during fracture scanning.
        final BlockPos.MutableBlockPos fractureBorderCursor = new BlockPos.MutableBlockPos();
        // Reuse the small per-tick retry set during recovery instead of allocating
        // a new HashSet on every server tick. It is transient and never persisted.
        // Primitive packed positions avoid Long boxing in the per-tick failed-write retry set.
        final LongOpenHashSet restoreFailedWrites=new LongOpenHashSet();
        private static long readBoundedElapsed(CompoundTag n,State state) {
            boolean present=n.contains("elapsed");
            boolean validType=n.contains("elapsed",Tag.TAG_LONG);
            long raw=n.getLong("elapsed");
            long bounded=boundedElapsed(raw);
            if(present && (!validType || raw!=bounded)) {
                state.setDirty();
                LOGGER.warn("BLACK FREQUENCY: normalized malformed saved elapsed counter");
            }
            return bounded;
        }
        private static int readBoundedCounter(CompoundTag n,String key,int maximum,State state) {
            boolean present=n.contains(key);
            boolean validType=n.contains(key,Tag.TAG_INT);
            int raw=n.getInt(key);
            int bounded=boundedCounter(raw,maximum);
            if(present && (!validType || raw!=bounded)) {
                state.setDirty();
                LOGGER.warn("BLACK FREQUENCY: normalized malformed saved counter {}",key);
            }
            return bounded;
        }
        static State load(CompoundTag n) {
            State s = new State();
            s.elapsed=readBoundedElapsed(n,s);
            boolean anchorCoordinatesValid=n.contains("x",Tag.TAG_INT)&&n.contains("z",Tag.TAG_INT);
            // Never let a truncated or mistyped anchor silently default to (0,0).
            // Active fracture state is quarantined below; restoration snapshots remain usable.
            s.x=anchorCoordinatesValid?n.getInt("x"):0;
            s.z=anchorCoordinatesValid?n.getInt("z"):0;
            boolean cursorXPresent=n.contains("cx"),cursorZPresent=n.contains("cz"),cursorYPresent=n.contains("cy");
            boolean cursorXValidType=n.contains("cx",Tag.TAG_INT),cursorZValidType=n.contains("cz",Tag.TAG_INT),cursorYValidType=n.contains("cy",Tag.TAG_INT);
            s.cursorX=cursorXValidType?n.getInt("cx"):0;
            s.cursorZ=cursorZValidType?n.getInt("cz"):0;
            s.cursorY=cursorYValidType?n.getInt("cy"):0;
            s.edits=readBoundedCounter(n,"edits",MAX_EDITS,s);
            s.finaleTicks=readBoundedCounter(n,"finaleTicks",Integer.MAX_VALUE,s);
            boolean surfaceFirstScanPresent=n.contains("surfaceFirstScan");
            boolean surfaceFirstScanValidType=n.contains("surfaceFirstScan",Tag.TAG_BYTE);
            s.surfaceFirstScan=surfaceFirstScanValidType&&n.getBoolean("surfaceFirstScan");
            boolean centerOutScanPresent=n.contains("centerOutScan");
            boolean centerOutScanValidType=n.contains("centerOutScan",Tag.TAG_BYTE);
            s.centerOutScan=centerOutScanValidType&&n.getBoolean("centerOutScan");
            boolean centerOutIndexPresent=n.contains("centerOutIndex");
            boolean centerOutIndexValidType=n.contains("centerOutIndex",Tag.TAG_INT);
            // centerOutScan and centerOutIndex were introduced together. Their presence
            // identifies the current cursor format; never silently downgrade a partially
            // truncated current save to row-major or bottom-up traversal.
            boolean hasCenterOutMetadata=centerOutScanPresent||centerOutIndexPresent;
            boolean incompleteCenterOutMetadata=hasCenterOutMetadata
                && (!surfaceFirstScanPresent || !centerOutScanPresent || !centerOutIndexPresent);
            boolean malformedFractureScanMode=(surfaceFirstScanPresent&&!surfaceFirstScanValidType)
                || (centerOutScanPresent&&!centerOutScanValidType)
                || incompleteCenterOutMetadata;
            s.centerOutIndex=centerOutIndexValidType?n.getInt("centerOutIndex"):0;
            if(centerOutIndexPresent && !centerOutIndexValidType) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed center-out fracture cursor type");
                s.setDirty();
            }
            boolean startedPresent=n.contains("started");
            boolean startedValidType=n.contains("started",Tag.TAG_BYTE);
            boolean malformedStartedType=startedPresent&&!startedValidType;
            s.started=startedValidType&&n.getBoolean("started");
            boolean finishedPresent=n.contains("finished");
            boolean finishedValidType=n.contains("finished",Tag.TAG_BYTE);
            boolean malformedFinishedType=finishedPresent&&!finishedValidType;
            s.finished=finishedValidType&&n.getBoolean("finished");
            boolean initializedPresent=n.contains("initialized");
            boolean initializedValidType=n.contains("initialized",Tag.TAG_BYTE);
            boolean malformedInitializedType=initializedPresent&&!initializedValidType;
            s.initialized=initializedValidType&&n.getBoolean("initialized");
            boolean restoringPresent=n.contains("restoring");
            boolean restoringValidType=n.contains("restoring",Tag.TAG_BYTE);
            boolean malformedRestoringType=restoringPresent&&!restoringValidType;
            s.restoring=restoringValidType&&n.getBoolean("restoring");
            boolean restoredPresent=n.contains("restored");
            boolean restoredValidType=n.contains("restored",Tag.TAG_BYTE);
            boolean malformedRestoredType=restoredPresent&&!restoredValidType;
            s.restored=restoredValidType&&n.getBoolean("restored");
            if(malformedRestoringType || malformedFinishedType || malformedRestoredType) s.setDirty();
            boolean malformedLegacyCursorType=(cursorXPresent&&!cursorXValidType)
                || (cursorZPresent&&!cursorZValidType) || (cursorYPresent&&!cursorYValidType);
            if(malformedLegacyCursorType && s.initialized && !s.centerOutScan
                && !s.restoring && !s.restored && !s.finished) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed legacy fracture cursor tag type");
                // Do not let a wrong NBT type silently become zero and resume a
                // destructive legacy scan from an invented column. Keep snapshots
                // intact so a later beacon can still restore already removed blocks.
                s.cursorX=RADIUS+1;
                s.cursorZ=-RADIUS;
                s.cursorY=Integer.MIN_VALUE;
                s.finished=true;
                s.setDirty();
            }
            if(malformedFractureScanMode) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed fracture scan mode flags");
                // Do not guess a scan order from a mistyped flag: a fallback could resume
                // destructive edits with the wrong vertical or horizontal traversal.
                if(s.initialized && !s.restoring && !s.restored && !s.finished)
                    s.finished=true;
                s.setDirty();
            }
            if(s.centerOutScan && (!centerOutIndexValidType
                || s.centerOutIndex<0 || s.centerOutIndex>CENTER_OUT_COLUMN_COUNT)) {
                if(s.initialized && !s.restoring && !s.restored) {
                    LOGGER.warn("BLACK FREQUENCY: invalid center-out cursor {}; stopping further terrain edits",
                        s.centerOutIndex);
                    // Preserve the terminal sentinel even if malformed companion metadata
                    // already marked this state finished. A normalized save must never turn
                    // an invalid active cursor back into a resumable index zero.
                    s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    s.finished=true;
                    s.setDirty();
                } else {
                    s.centerOutIndex=0;
                    s.setDirty();
                }
            }
            boolean restoreIndexPresent=n.contains("restoreIndex");
            boolean restoreIndexValidType=n.contains("restoreIndex",Tag.TAG_INT);
            s.restoreIndex=restoreIndexValidType?n.getInt("restoreIndex"):0;
            if(restoreIndexPresent && !restoreIndexValidType) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed recovery restoreIndex type");
                s.setDirty();
            }
            // Older worlds have no scan cursor: resume at the last pending snapshot.
            boolean restoreScanPresent=n.contains("restoreScan");
            boolean restoreScanValidType=n.contains("restoreScan",Tag.TAG_INT);
            s.restoreScan=restoreScanValidType?n.getInt("restoreScan"):s.restoreIndex;
            if(restoreScanPresent && !restoreScanValidType) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed recovery restoreScan type");
                s.setDirty();
            }
            s.restoreTicks=readBoundedCounter(n,"restoreTicks",Integer.MAX_VALUE,s);
            s.restoredBlocks=readBoundedCounter(n,"restoredBlocks",MAX_EDITS,s);
            s.skippedOccupied=readBoundedCounter(n,"skippedOccupied",MAX_EDITS,s);
            s.skippedInvalid=readBoundedCounter(n,"skippedInvalid",MAX_EDITS,s);
            s.snapshotOverflowEntries=readBoundedCounter(n,"snapshotOverflowEntries",MAX_EDITS,s);
            s.snapshotMissingEntries=readBoundedCounter(n,"snapshotMissingEntries",MAX_EDITS,s);
            s.invalidSnapshotWarningLogged=n.contains("invalidSnapshotWarningLogged",Tag.TAG_BYTE)
                && n.getBoolean("invalidSnapshotWarningLogged");
            boolean beaconPositionPresent=n.contains("beaconPos");
            boolean beaconPositionValid=n.contains("beaconPos",Tag.TAG_LONG);
            s.beaconPosPresent=beaconPositionValid;
            s.beaconPos=s.beaconPosPresent?n.getLong("beaconPos"):0L;
            if(beaconPositionPresent && !beaconPositionValid) {
                LOGGER.warn("BLACK FREQUENCY: quarantined malformed recovery beacon position");
                s.setDirty();
            }
            boolean naturalBeaconPositionPresent=n.contains("naturalBeaconPos");
            boolean naturalBeaconPositionValid=n.contains("naturalBeaconPos",Tag.TAG_LONG);
            boolean naturalBeaconIdentityPresent=n.contains("naturalBeaconId");
            s.naturalBeaconPos=naturalBeaconPositionValid?n.getLong("naturalBeaconPos"):Long.MIN_VALUE;
            s.naturalBeaconId=s.naturalBeaconPos!=Long.MIN_VALUE && n.hasUUID("naturalBeaconId")?n.getUUID("naturalBeaconId"):null;
            // A coordinate without a valid ownership UUID is not a mod-owned objective.
            // Retire it during load instead of carrying an unusable anchor until a later tick.
            if(s.naturalBeaconPos!=Long.MIN_VALUE && s.naturalBeaconId==null) {
                LOGGER.warn("BLACK FREQUENCY: retired recovery beacon coordinate without ownership UUID");
                s.naturalBeaconPos=Long.MIN_VALUE;
                s.setDirty();
            } else if((naturalBeaconPositionPresent && !naturalBeaconPositionValid)
                    || (s.naturalBeaconPos==Long.MIN_VALUE && naturalBeaconIdentityPresent)) {
                // Also persist removal of an orphan UUID or malformed coordinate. Without
                // setDirty(), SavedData may never write this normalized pair back to disk.
                LOGGER.warn("BLACK FREQUENCY: quarantined orphaned recovery beacon metadata");
                s.setDirty();
            }
            Tag rawSnapshots=n.get("snapshots");
            boolean malformedSnapshotList=!isWellFormedSnapshotListTag(rawSnapshots);
            ListTag saved=rawSnapshots instanceof ListTag list && !malformedSnapshotList
                ? list : new ListTag();
            if(s.restored) {
                // A completed recovery is terminal. Replaying stale snapshots after a later
                // restart could overwrite player edits made after stabilization.
                int staleSnapshotCount=rawSnapshots instanceof ListTag list ? list.size() : 0;
                boolean terminalStateRepaired=s.restoring || !s.finished || staleSnapshotCount>0
                    || malformedSnapshotList || s.restoreIndex!=-1 || s.restoreScan!=-1;
                if(terminalStateRepaired) {
                    s.restoring=false;
                    s.finished=true;
                    s.restoreIndex=-1;
                    s.restoreScan=-1;
                    s.snapshots.clear();
                    s.retainedSnapshotEstimateBytes=0L;
                    s.setDirty();
                    LOGGER.warn("BLACK FREQUENCY: quarantined {} stale recovery snapshot entries from completed restoration",
                        staleSnapshotCount);
                }
                // Do not parse, retain or count stale payloads once restoration is terminal.
                saved=new ListTag();
                malformedSnapshotList=false;
            }
            if(malformedSnapshotList) {
                // A wrong outer tag type or non-compound list cannot contain trusted snapshots.
                // If terrain was already damaged, record the lost container as an incomplete
                // recovery item so the completion action bar cannot claim full stabilization.
                if(s.restoring || s.edits>0) {
                    // When edits are recorded, the missing-entry counter below accounts for every
                    // lost snapshot. Count a standalone malformed container only when no edits exist.
                    if(s.edits<=0) s.skippedInvalid=boundedCounter(s.skippedInvalid+1,MAX_EDITS);
                    // The warning is one-shot, but every newly quarantined container must persist.
                    // A previous warning must not suppress the dirty flag for this new counter value.
                    s.setDirty();
                    warnInvalidSnapshotOnce(s,"malformed snapshot list container","snapshots");
                } else {
                    LOGGER.warn("BLACK FREQUENCY: quarantined malformed recovery snapshot list container");
                    s.setDirty();
                }
            }
            int snapshotCount=boundedSnapshotCount(saved.size());
            boolean snapshotStateRepaired=false;
            int overflowSnapshotEntries=boundedSnapshotOverflowEntries(saved.size(),snapshotCount);
            if(overflowSnapshotEntries>s.snapshotOverflowEntries) {
                s.snapshotOverflowEntries=overflowSnapshotEntries;
                snapshotStateRepaired=true;
                LOGGER.warn("BLACK FREQUENCY: quarantined {} excess recovery snapshot entries beyond the {} entry retention cap",
                    overflowSnapshotEntries,MAX_EDITS);
            }
            // A larger edit count than the saved list means original block states are missing.
            // Persist that gap separately from an over-cap list tail so later truncation cannot hide it.
            int missingSnapshotEntries=s.restored?0:Math.max(0,s.edits-snapshotCount);
            if(missingSnapshotEntries>s.snapshotMissingEntries) {
                s.snapshotMissingEntries=missingSnapshotEntries;
                snapshotStateRepaired=true;
                LOGGER.warn("BLACK FREQUENCY: edit counter {} exceeds {} retained snapshots; marking {} missing entries as unrecoverable",
                    s.edits,snapshotCount,missingSnapshotEntries);
            }
            int quarantinedSnapshotPayloads=0;
            int duplicateSnapshotPositions=0;
            // A fractured block is recorded once. Keep the oldest valid record for a
            // position and quarantine later duplicates so conflicting NBT cannot make
            // recovery depend on cursor order after a damaged save or interrupted write.
            var seenSnapshotPositions=new it.unimi.dsi.fastutil.longs.LongOpenHashSet(snapshotCount);
            for(int i=0;i<snapshotCount;i++) {
                CompoundTag entry=saved.getCompound(i);
                long candidateEstimate=boundedSnapshotPayloadEstimate(entry);
                if(candidateEstimate>0L
                    && withinSnapshotRetentionBudget(s.retainedSnapshotEstimateBytes,candidateEstimate)) {
                    long packedPosition=entry.getLong("pos");
                    if(!seenSnapshotPositions.add(packedPosition)) {
                        s.snapshots.add(new CompoundTag());
                        snapshotStateRepaired=true;
                        duplicateSnapshotPositions++;
                        continue;
                    }
                    // Preserve the oldest, anchor-nearest snapshots first when a damaged
                    // save exceeds the aggregate budget; these are the recovery core.
                    s.snapshots.add(entry);
                    s.retainedSnapshotEstimateBytes+=candidateEstimate;
                } else {
                    // Preserve list indices for recovery cursors, but never retain or re-save
                    // invalid or aggregate-over-budget NBT payloads.
                    s.snapshots.add(new CompoundTag());
                    snapshotStateRepaired=true;
                    quarantinedSnapshotPayloads++;
                }
            }
            if(malformedInitializedType) {
                // A wrong NBT type reads as false. Reinitializing this state would reset
                // its anchor and cursor to the current spawn, potentially fracturing a
                // second area while the original recovery snapshots still belong elsewhere.
                s.setDirty();
                if(!s.restoring && !s.restored
                    && (s.started || s.edits>0 || !s.snapshots.isEmpty())) {
                    LOGGER.warn("BLACK FREQUENCY: stopped fracture resumption because saved initialized flag has the wrong NBT type");
                    s.initialized=true;
                    s.started=true;
                    s.finished=true;
                    if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    else {
                        s.cursorX=RADIUS+1;
                        s.cursorZ=-RADIUS;
                        s.cursorY=Integer.MIN_VALUE;
                    }
                    s.setDirty();
                }
            }
            if(malformedRestoringType && !s.restored && !s.finished
                && (s.started || s.initialized || s.edits>0 || !s.snapshots.isEmpty())) {
                // A mistyped restoring flag defaults to false in CompoundTag. Do not let
                // that silently resume destructive fracture scanning from a valid cursor.
                // Keep validated snapshots so a player can restart recovery at the physical beacon.
                LOGGER.warn("BLACK FREQUENCY: stopped fracture resumption because saved restoring flag has the wrong NBT type");
                s.restoring=false;
                s.finished=true;
                if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                else {
                    s.cursorX=RADIUS+1;
                    s.cursorZ=-RADIUS;
                    s.cursorY=Integer.MIN_VALUE;
                }
                s.setDirty();
            }
            if(malformedStartedType) {
                // A mistyped started flag reads as false. If the save already contains
                // fracture progress, the next countdown completion would choose a new
                // player anchor while retaining the old cursor and recovery snapshots.
                // Stop further edits at the original anchor instead of guessing that state.
                s.setDirty();
                if(!s.restoring && !s.restored
                    && (s.edits>0 || s.finaleTicks>0 || s.finished || !s.snapshots.isEmpty())) {
                    LOGGER.warn("BLACK FREQUENCY: stopped fracture resumption because saved started flag has the wrong NBT type");
                    s.started=true;
                    s.finished=true;
                    if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    else {
                        s.cursorX=RADIUS+1;
                        s.cursorZ=-RADIUS;
                        s.cursorY=Integer.MIN_VALUE;
                    }
                    s.setDirty();
                } else {
                    LOGGER.warn("BLACK FREQUENCY: normalized malformed started flag before destructive progress");
                }
            }
            if(malformedFinishedType) {
                if(s.restoring || s.restored) {
                    // Restoration/terminal state already owns the lifecycle; repair the
                    // companion flag without restarting a fracture scan.
                    s.finished=true;
                    s.setDirty();
                } else if(s.started || s.edits>0 || s.finaleTicks>0 || !s.snapshots.isEmpty()) {
                    LOGGER.warn("BLACK FREQUENCY: stopped fracture resumption because saved finished flag has the wrong NBT type");
                    s.started=true;
                    s.finished=true;
                    if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    else {
                        s.cursorX=RADIUS+1;
                        s.cursorZ=-RADIUS;
                        s.cursorY=Integer.MIN_VALUE;
                    }
                    s.setDirty();
                } else {
                    LOGGER.warn("BLACK FREQUENCY: normalized malformed finished flag before destructive progress");
                }
            }
            if(malformedRestoredType) {
                boolean lifecycleEvidence=s.restoring || s.started || s.edits>0 || s.finaleTicks>0
                    || s.finished || !s.snapshots.isEmpty();
                if(lifecycleEvidence) {
                    // A false default could replay snapshots after an already completed
                    // recovery. Pause automatic writes, retain the validated snapshots,
                    // and let a player explicitly restart recovery at the physical beacon.
                    LOGGER.warn("BLACK FREQUENCY: quarantined recovery because saved restored flag has the wrong NBT type");
                    s.restoring=false;
                    s.restored=false;
                    s.started=true;
                    s.finished=true;
                    s.restoreIndex=-1;
                    s.restoreScan=-1;
                    s.restoreTicks=0;
                    if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    else {
                        s.cursorX=RADIUS+1;
                        s.cursorZ=-RADIUS;
                        s.cursorY=Integer.MIN_VALUE;
                    }
                    s.setDirty();
                } else {
                    LOGGER.warn("BLACK FREQUENCY: normalized malformed restored flag before lifecycle progress");
                }
            }
            if(quarantinedSnapshotPayloads>0)
                LOGGER.warn("BLACK FREQUENCY: quarantined {} recovery snapshot payloads; retained estimate {} bytes of {} byte budget",
                    quarantinedSnapshotPayloads,s.retainedSnapshotEstimateBytes,MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES);
            if(duplicateSnapshotPositions>0)
                LOGGER.warn("BLACK FREQUENCY: quarantined {} duplicate recovery snapshot positions; retained the first valid occurrence",
                    duplicateSnapshotPositions);
            if(snapshotCount>s.edits) {
                // The pending snapshots are authoritative when a damaged save
                // has a smaller edit counter; keep restoration accounting consistent.
                LOGGER.warn("BLACK FREQUENCY: saved edit counter {} is below {} recovery snapshots; raising it",
                    s.edits,snapshotCount);
                s.edits=snapshotCount;
                snapshotStateRepaired=true;
            }
            if(saved.size()>snapshotCount) {
                // Keep the oldest entries: fracture edits are recorded from the
                // anchor outward, so this preserves the recovery core first.
                LOGGER.warn("BLACK FREQUENCY: saved snapshot list has {} entries; retaining the first {} and ignoring {} excess entries",
                    saved.size(),snapshotCount,saved.size()-snapshotCount);
                snapshotStateRepaired=true;
            }
            if(snapshotStateRepaired) s.setDirty();
            // A damaged or older save must not crash the server by indexing past
            // the persisted snapshot list. Never invent missing terrain data.
            if(s.restoring) {
                int last=s.snapshots.size()-1;
                boolean restoreCursorRepaired=false;
                if(!restoreIndexValidType) {
                    // A missing/mistyped cursor during active recovery must not default to zero:
                    // resume from the last validated snapshot. Replaying a snapshot is safer than
                    // skipping the remaining terrain edits and falsely reporting full restoration.
                    if(!restoreIndexPresent)
                        LOGGER.warn("BLACK FREQUENCY: missing recovery restoreIndex; resuming from last snapshot");
                    s.restoreIndex=last;
                    s.restoreScan=last;
                    restoreCursorRepaired=true;
                } else if(s.restoreIndex==-1 && last>=0
                    && !recoverySnapshotsFullyAccounted(s.snapshots.size(),
                        s.restoredBlocks,s.skippedOccupied,s.skippedInvalid)) {
                    // -1 is the completed-scan sentinel. If snapshots remain but
                    // their outcome counters do not account for all of them, a
                    // torn save could otherwise skip restoration and clear the queue.
                    // Replay the validated queue: occupied blocks are preserved by
                    // the normal restore guard, while air/flowing-fluid positions retry.
                    LOGGER.warn("BLACK FREQUENCY: recovery cursor has inconsistent snapshot accounting; replaying {} validated snapshots",
                        s.snapshots.size());
                    s.restoreIndex=last;
                    s.restoreScan=last;
                    s.restoreTicks=0;
                    s.restoredBlocks=0;
                    s.skippedOccupied=0;
                    s.skippedInvalid=0;
                    restoreCursorRepaired=true;
                } else if(s.restoreIndex < -1 || s.restoreIndex > last) {
                    LOGGER.warn("BLACK FREQUENCY: invalid restoreIndex={} for {} snapshots; clamping",
                        s.restoreIndex,s.snapshots.size());
                    s.restoreIndex=last;
                    restoreCursorRepaired=true;
                }
                if(restoreIndexValidType && restoreScanPresent && !restoreScanValidType) {
                    s.restoreScan=s.restoreIndex;
                    restoreCursorRepaired=true;
                } else if(restoreIndexValidType && (s.restoreScan < -1 || s.restoreScan > s.restoreIndex)) {
                    LOGGER.warn("BLACK FREQUENCY: invalid restoreScan={}; resetting to restoreIndex={}",
                        s.restoreScan,s.restoreIndex);
                    s.restoreScan=s.restoreIndex;
                    restoreCursorRepaired=true;
                }
                // Persist cursor repairs even if no later restoration tick runs.
                if(restoreCursorRepaired) s.setDirty();
            }
            // Older saves used initialized to persist a row-major cursor before the
            // scan anchor was serialized. Missing both coordinates in that legacy
            // format is not corruption: preserve its cursor until there is evidence
            // that a current anchor was written or destructive progress exists.
            boolean anchorCoordinatesNeedRepair=!anchorCoordinatesValid
                && (n.contains("x") || n.contains("z") || s.started
                    || s.edits>0 || (s.restoring && (!s.snapshots.isEmpty() || s.beaconPosPresent)));
            if(anchorCoordinatesNeedRepair) {
                // Recovery has a stronger source of truth than the optional scan anchor:
                // prefer the physical beacon, then its owned replacement, then the oldest
                // retained snapshot. This only repairs the audience/scan origin; snapshots
                // still pass their own bounds and loaded-chunk checks before any world write.
                if(s.beaconPosPresent) {
                    BlockPos recoveryAnchor=BlockPos.of(s.beaconPos);
                    s.x=recoveryAnchor.getX();
                    s.z=recoveryAnchor.getZ();
                } else if(s.naturalBeaconPos!=Long.MIN_VALUE) {
                    BlockPos recoveryAnchor=BlockPos.of(s.naturalBeaconPos);
                    s.x=recoveryAnchor.getX();
                    s.z=recoveryAnchor.getZ();
                } else {
                    for(CompoundTag entry:s.snapshots) {
                        if(!entry.contains("pos",Tag.TAG_LONG)) continue;
                        BlockPos recoveryAnchor=BlockPos.of(entry.getLong("pos"));
                        s.x=recoveryAnchor.getX();
                        s.z=recoveryAnchor.getZ();
                        break;
                    }
                }
                boolean destructiveProgress=s.edits>0 || !s.snapshots.isEmpty();
                boolean activeFracture=!s.restoring && !s.restored && !s.finished
                    && (s.initialized || s.started || destructiveProgress);
                boolean activeFractureStopped=activeFracture && destructiveProgress;
                boolean emptyFractureReinitialized=activeFracture && !destructiveProgress;
                if(activeFractureStopped) {
                    // Preserve every recovery snapshot, but never resume destructive edits
                    // from an invented origin after losing the saved anchor coordinates.
                    s.finished=true;
                    if(s.centerOutScan) s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    else {
                        s.cursorX=RADIUS+1;
                        s.cursorZ=0;
                        s.cursorY=Integer.MIN_VALUE;
                    }
                    LOGGER.warn("BLACK FREQUENCY: stopped fracture resumption because saved anchor coordinates are missing or malformed");
                } else if(emptyFractureReinitialized) {
                    // No terrain was changed yet, so restart the scan from the current shared spawn.
                    s.initialized=false;
                    s.cursorX=0;
                    s.cursorZ=0;
                    s.cursorY=Integer.MIN_VALUE;
                    s.surfaceFirstScan=true;
                    s.centerOutScan=true;
                    s.centerOutIndex=0;
                }
                s.setDirty();
                LOGGER.warn("BLACK FREQUENCY: repaired missing or malformed saved anchor coordinates");
            }
            // A legacy cursor without an anchor is safe to keep only before any edits.
            // Resolve its origin from the live spawn on the server tick, not to (0,0).
            s.anchorCoordinatesNeedInitialization=!anchorCoordinatesValid
                && !n.contains("x") && !n.contains("z")
                && s.initialized && !s.started && !s.finished && !s.restoring && !s.restored
                && s.edits==0 && s.snapshots.isEmpty();
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST STATE_LOADED elapsed={} edits={} restored={} restoring={} snapshots={}",s.elapsed,s.edits,s.restored,s.restoring,s.snapshots.size());
            return s;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putLong("elapsed",elapsed);n.putInt("x",x);n.putInt("z",z);
            n.putInt("cx",cursorX);n.putInt("cz",cursorZ);n.putInt("cy",cursorY);
            n.putInt("edits",edits);n.putInt("finaleTicks",finaleTicks);n.putBoolean("started",started);
            n.putBoolean("surfaceFirstScan",surfaceFirstScan);
            n.putBoolean("centerOutScan",centerOutScan);n.putInt("centerOutIndex",centerOutIndex);
            n.putBoolean("finished",finished);n.putBoolean("initialized",initialized);
            n.putBoolean("restoring",restoring);n.putBoolean("restored",restored);
            n.putInt("restoreIndex",restoreIndex);n.putInt("restoreScan",restoreScan);n.putInt("restoreTicks",restoreTicks);n.putInt("restoredBlocks",restoredBlocks);n.putInt("skippedOccupied",skippedOccupied);n.putInt("skippedInvalid",skippedInvalid);n.putInt("snapshotOverflowEntries",snapshotOverflowEntries);n.putInt("snapshotMissingEntries",snapshotMissingEntries);
            n.putBoolean("invalidSnapshotWarningLogged",invalidSnapshotWarningLogged);
            if(beaconPosPresent) n.putLong("beaconPos",beaconPos);
            else n.remove("beaconPos");
            n.putLong("naturalBeaconPos",naturalBeaconPos);
            if(naturalBeaconId!=null) n.putUUID("naturalBeaconId",naturalBeaconId);
            else n.remove("naturalBeaconId");
            ListTag saved=new ListTag();for(CompoundTag tag:snapshots)saved.add(tag.copy());
            n.put("snapshots",saved);
            return n;
        }
    }
    private static boolean markInvalidSnapshotWarningLogged(State s) {
        if(s.invalidSnapshotWarningLogged) return false;
        s.invalidSnapshotWarningLogged=true;
        s.setDirty();
        return true;
    }

    private static void warnInvalidSnapshotOnce(State s,String reason,Object detail) {
        if(markInvalidSnapshotWarningLogged(s))
            LOGGER.warn("BLACK FREQUENCY: invalid recovery snapshot ({} at {}); subsequent invalid entries are counted without per-entry warnings",
                reason,detail);
    }

    /**
     * Recovery beacons are emergency world fixtures, not free survival loot.
     * Let a player dismantle one, but consume it without vanilla drops; the
     * existing bounded placement loop can supply a replacement later.
     * Ordinary player-owned beacons are never intercepted.
     */
    // Run after ordinary protection handlers so their cancellation is visible.
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onRecoveryBeaconBreak(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if(!(event.getLevel() instanceof ServerLevel level)
            || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        // Removed or spectator players must not dismantle a recovery objective
        // through synthetic break events from other mods.
        // Respect prior protection-mod cancellation: a denied break must not
        // remove the recovery fixture or mutate its persisted position.
        if(event.isCanceled()) return;
        var miner=event.getPlayer();
        if(miner==null || !mayMineRecoveryBeacon(miner.isAlive(),miner.isRemoved(),miner.isSpectator(),miner.getAbilities().mayBuild)) return;
        BlockPos pos=event.getPos();
        // Ignore unloaded positions before querying block state: an event from
        // another mod must not cause recovery logic to load a chunk.
        if(!level.hasChunkAt(pos)) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,pos)) return;
        // Synthetic Forge break events must not bypass the same server-side reach
        // boundary used by beacon activation. Ordinary blocks return before this test.
        double distanceSquared=eyeDistanceSquaredToBlock(miner.getEyePosition(),pos);
        if(!withinBeaconInteractionReach(distanceSquared)) {
            if(CI_TEST) LOGGER.warn("BLACK_FREQUENCY_TEST NATURAL_BEACON_BREAK_REJECTED_OUT_OF_REACH distanceSquared={} maxDistanceSquared={}",
                distanceSquared,CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE);
            return;
        }
        // Cancel vanilla harvesting before it creates a valuable beacon item.
        event.setCanceled(true);
        if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)
            && level.getBlockState(pos).isAir()) {
            s.naturalBeaconPos=Long.MIN_VALUE;
            s.naturalBeaconId=null;
            s.setDirty();
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest")) {
                ciPendingNaturalBeaconBreakVerificationPos=pos.immutable();
                ciPendingNaturalBeaconBreakVerificationPlayer=miner.getUUID();
                ciPendingNaturalBeaconBreakVerificationSurvival=!miner.isCreative()
                    && !miner.getAbilities().instabuild;
                ciPendingNaturalBeaconBreakVerificationDistanceSquared=distanceSquared;
            }
            event.getPlayer().displayClientMessage(recoveryViolet(
                s.restoring || s.restored
                    ? "✦ Маяк стабилизации рассеялся."
                    : "✦ Маяк стабилизации рассеялся. Новый появится поблизости."),true);
            if(CI_TEST) {
                LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP x={} y={} z={}",
                    pos.getX(),pos.getY(),pos.getZ());
                LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_CLEARED_AFTER_BREAK");
                if(s.restored) LOGGER.info("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_CONSUMED x={} y={} z={}",
                    pos.getX(),pos.getY(),pos.getZ());
            }
        }
    }

    /**
     * Keep the naturally generated recovery beacon out of explosion block lists.
     * Unlike a player-owned beacon it must never become free explosion loot.
     * Filtering the affected list leaves the rest of the explosion untouched.
     */
    private static boolean protectRecoveryBeaconFromExplosion(List<BlockPos> affected,long beaconPos) {
        return beaconPos!=Long.MIN_VALUE && affected.removeIf(pos -> pos.asLong()==beaconPos);
    }

    private record FissureEmission(int samples,int accentDustMotes,int violetDustMotes,
            int edgeDustMotes,int edgeArmsWithSamples,int edgeFallbackArms,
            int edgeDustSmallMotes,int edgeDustBaseMotes,int edgeDustLargeMotes,double averageSurfaceY) {
        private int totalParticles() {
            return accentDustMotes+violetDustMotes+edgeDustMotes;
        }
    }

    // Geometry and pulse offsets are invariant across worlds and ticks. Cache them once
    // so each server-side fissure pulse performs only the time-varying wave sine calls.
    private record FissureSample(double forwardX,double bendX,double forwardZ,double bendZ,
            double segmentPhase,double armPhase) {}
    private static final FissureSample[] FISSURE_SAMPLES=createFissureSamples();

    private static FissureSample[] createFissureSamples() {
        FissureSample[] samples=new FissureSample[FISSURE_ARM_COUNT*FISSURE_SEGMENTS_PER_ARM];
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            double angle=arm*Math.PI/2.0D;
            double sinAngle=Math.sin(angle);
            double cosAngle=Math.cos(angle);
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                double distance=segment*FISSURE_SAMPLE_SPACING;
                double bend=Math.sin(segment*.85D+arm*1.1D)*RiftGeometry.FISSURE_BEND_AMPLITUDE;
                samples[arm*FISSURE_SEGMENTS_PER_ARM+segment-1]=new FissureSample(
                    cosAngle*distance,sinAngle*bend,
                    sinAngle*distance,cosAngle*bend,
                    segment*.8D,arm*.6D);
            }
        }
        return samples;
    }

    // Sparse accent samples use a brighter cached Dust tint from the same
    // particle family, keeping the whole surface fissure inside the violet palette.
    private static boolean isAccentFissureDustSegment(int segment) {
        return segment>0 && segment<FISSURE_SEGMENTS_PER_ARM && segment%4==0;
    }

    private static boolean isOutermostValidFissureSample(int segment,int previousSegment) {
        return segment>0 && segment<=FISSURE_SEGMENTS_PER_ARM && segment>previousSegment;
    }

    // A motion-blocking heightmap includes leaves and can stop on a tree trunk or village roof.
    // Block entities are fixtures, not terrain: never place the fissure ribbon
    // on top of a chest, furnace, beacon or another stateful block.
    private static boolean isFissureGroundSupport(BlockState state) {
        return state.blocksMotion()
            && !state.hasBlockEntity()
            && !state.is(net.minecraft.tags.BlockTags.LOGS)
            && !state.is(net.minecraft.tags.BlockTags.LEAVES)
            && !state.is(net.minecraft.tags.BlockTags.PLANKS)
            && !state.is(net.minecraft.tags.BlockTags.STAIRS)
            && !state.is(net.minecraft.tags.BlockTags.SLABS)
            && !state.is(net.minecraft.tags.BlockTags.WALLS)
            && !state.is(net.minecraft.tags.BlockTags.FENCES)
            && !state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
            && !state.is(net.minecraft.tags.BlockTags.DOORS)
            && !state.is(net.minecraft.tags.BlockTags.TRAPDOORS);
    }

    // The server particle sampler must use the same full-footprint support rule as
    // the client mesh. A cauldron rim or modded partial collision box is not terrain.
    private static final double FISSURE_SUPPORT_SHAPE_EPSILON=1.0E-7D;
    private static final class FissureSupportTopProbe
            implements net.minecraft.world.phys.shapes.Shapes.DoubleLineConsumer {
        private double expectedHeight;
        private boolean found;
        private boolean invalidBounds;

        private void reset(double height) {
            expectedHeight=height;
            found=false;
            invalidBounds=false;
        }

        private boolean found() {
            return found && !invalidBounds;
        }

        @Override
        public void consume(double minX,double minY,double minZ,
                double maxX,double maxY,double maxZ) {
            if(!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX>maxX || minY>=maxY || minZ>maxZ
                || minX < -FISSURE_SUPPORT_SHAPE_EPSILON
                || minY < -FISSURE_SUPPORT_SHAPE_EPSILON
                || minZ < -FISSURE_SUPPORT_SHAPE_EPSILON
                || maxX > 1.0D+FISSURE_SUPPORT_SHAPE_EPSILON
                || maxY > 1.0D+FISSURE_SUPPORT_SHAPE_EPSILON
                || maxZ > 1.0D+FISSURE_SUPPORT_SHAPE_EPSILON) {
                invalidBounds=true;
                return;
            }
            if(Math.abs(maxY-expectedHeight)<=FISSURE_SUPPORT_SHAPE_EPSILON
                && minX<=FISSURE_SUPPORT_SHAPE_EPSILON
                && maxX>=1.0D-FISSURE_SUPPORT_SHAPE_EPSILON
                && minZ<=FISSURE_SUPPORT_SHAPE_EPSILON
                && maxZ>=1.0D-FISSURE_SUPPORT_SHAPE_EPSILON) found=true;
        }
    }

    private static final FissureSupportTopProbe FISSURE_SUPPORT_TOP_PROBE=
        new FissureSupportTopProbe();

    private static boolean hasFullFissureSupportTop(
            net.minecraft.world.phys.shapes.VoxelShape shape) {
        if(shape==null || shape.isEmpty()) return false;
        double height=shape.max(net.minecraft.core.Direction.Axis.Y);
        if(!Double.isFinite(height) || height<=0.0D
            || height>1.0D+FISSURE_SUPPORT_SHAPE_EPSILON) return false;
        FISSURE_SUPPORT_TOP_PROBE.reset(height);
        shape.forAllBoxes(FISSURE_SUPPORT_TOP_PROBE);
        return FISSURE_SUPPORT_TOP_PROBE.found();
    }

    private static boolean isFissureGroundSupport(
            BlockState state,net.minecraft.world.level.BlockGetter level,BlockPos pos) {
        if(!isFissureGroundSupport(state) || level==null || pos==null) return false;
        var collision=state.getCollisionShape(level,pos);
        var selected=collision.isEmpty()?state.getShape(level,pos):collision;
        return hasFullFissureSupportTop(selected);
    }

    private static boolean isFissureSurfaceAvailable(BlockState state) {
        // Match the client mesh: a surface sample must be a non-solid, dry cell,
        // not a fixture, so server motes and the client ribbon cannot disagree.
        return !state.hasBlockEntity()
            && !state.blocksMotion()
            && state.getFluidState().isEmpty();
    }

    private static final int FISSURE_SURFACE_SEARCH_DEPTH=32;
    // Include leaves in the initial heightmap so the bounded scan can actually traverse canopy.
    private static final net.minecraft.world.level.levelgen.Heightmap.Types FISSURE_SURFACE_HEIGHTMAP=
        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;

    // Only vertical trunks and leaves may be traversed as canopy. Horizontal logs
    // and solid construction remain occluders, preventing particles through roofs.
    private static boolean isFissureCanopyOccluder(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.LEAVES)
            || (state.is(net.minecraft.tags.BlockTags.LOGS)
                && state.hasProperty(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                && state.getValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS)
                    ==net.minecraft.core.Direction.Axis.Y);
    }

    private static boolean isNewFractureColumn(boolean surfaceFirstScan,int cursorY,int minBuildHeight) {
        return surfaceFirstScan ? cursorY==Integer.MIN_VALUE : cursorY==minBuildHeight;
    }

    private static void verifyFissureCanopyTraversal() {
        net.minecraft.world.level.border.WorldBorder probeBorder=
            new net.minecraft.world.level.border.WorldBorder();
        probeBorder.setCenter(0.0D,0.0D);
        probeBorder.setSize(16.0D);
        BlockPos.MutableBlockPos borderCursor=new BlockPos.MutableBlockPos();
        boolean borderInside=isFissureColumnInsideWorldBorder(probeBorder,0,0,-64,borderCursor);
        boolean borderOutsideRejected=!isFissureColumnInsideWorldBorder(probeBorder,32,0,-64,borderCursor);
        boolean nullCursorRejected=!isFissureColumnInsideWorldBorder(probeBorder,0,0,-64,null);
        boolean surfaceFirstColumnStart=isNewFractureColumn(true,Integer.MIN_VALUE,-64)
            && !isNewFractureColumn(true,-63,-64);
        boolean legacyColumnStart=isNewFractureColumn(false,-64,-64)
            && !isNewFractureColumn(false,-63,-64);
        FissureGroundSample rejectedSample=new FissureGroundSample();
        rejectedSample.surfaceY=65;
        rejectedSample.particleY=65.10D;
        boolean nullLevelRejected=!findFissureGroundSample(null,0,0,null,rejectedSample)
            && rejectedSample.isEmpty();
        if(!(borderInside && borderOutsideRejected && nullCursorRejected && nullLevelRejected
            && surfaceFirstColumnStart && legacyColumnStart))
            throw new IllegalStateException("CI fissure world-border sampling guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_WORLD_BORDER_GUARD_VERIFIED inside=true outsideRejected=true nullRejected=true resultCleared=true");
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_COLUMN_BORDER_EARLY_REJECTION_VERIFIED surfaceFirstStart=true legacyStart=true");
        BlockState verticalLog=net.minecraft.world.level.block.Blocks.OAK_LOG.defaultBlockState();
        BlockState horizontalLog=verticalLog.setValue(
            net.minecraft.world.level.block.RotatedPillarBlock.AXIS,net.minecraft.core.Direction.Axis.X);
        boolean verticalTrunk=isFissureCanopyOccluder(verticalLog);
        boolean leaves=isFissureCanopyOccluder(
            net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState());
        boolean horizontalBeamRejected=!isFissureCanopyOccluder(horizontalLog);
        boolean builtRoofRejected=!isFissureCanopyOccluder(
            net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState())
            && !isFissureCanopyOccluder(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        boolean nonSolidPlantRetained=isFissureSurfaceAvailable(
            net.minecraft.world.level.block.Blocks.DANDELION.defaultBlockState());
        boolean leafAwareHeightmap=FISSURE_SURFACE_HEIGHTMAP
            ==net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING;
        if(!(verticalTrunk && leaves && horizontalBeamRejected && builtRoofRejected
            && nonSolidPlantRetained && leafAwareHeightmap && FISSURE_SURFACE_SEARCH_DEPTH==32))
            throw new IllegalStateException("CI fissure canopy traversal/structure occlusion regression");
    }

    /** Allow only air, leaves, or dry non-colliding plants in the disposable fixture column. */
    private static boolean isFissureCanopyFixtureCellClearable(BlockState state,ServerLevel level,BlockPos pos) {
        if(state==null || state.hasBlockEntity()) return false;
        if(state.isAir() || state.is(net.minecraft.tags.BlockTags.LEAVES)) return true;
        return !state.blocksMotion() && state.getFluidState().isEmpty()
            && state.getCollisionShape(level,pos).isEmpty();
    }

    /**
     * Exercise the production sampler against a real, already-loaded ServerLevel column.
     * The fixture is confined to eight blocks from the rift anchor and restored in the
     * same tick. It never requests a chunk and is enabled only in disposable CI worlds.
     */
    private static boolean verifyFissureCanopyTraversalInWorld(ServerLevel level,State s) {
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        BlockPos policyPos=level.getSharedSpawnPos();
        boolean fullBlockShapeAccepted=hasFullFissureSupportTop(
            Blocks.STONE.defaultBlockState().getCollisionShape(level,policyPos));
        boolean cauldronShapeRejected=!hasFullFissureSupportTop(
            Blocks.CAULDRON.defaultBlockState().getCollisionShape(level,policyPos));
        boolean overhangShapeRejected=!hasFullFissureSupportTop(
            net.minecraft.world.phys.shapes.Shapes.box(-.25D,0.0D,0.0D,1.0D,1.0D,1.0D));
        boolean multiBoxOverhangRejected=!hasFullFissureSupportTop(
            net.minecraft.world.phys.shapes.Shapes.or(net.minecraft.world.phys.shapes.Shapes.block(),
                net.minecraft.world.phys.shapes.Shapes.box(1.0D,0.0D,0.0D,1.25D,1.0D,1.0D)));
        boolean slabPolicyPreserved=!isFissureGroundSupport(Blocks.COBBLESTONE_SLAB.defaultBlockState());
        if(!(fullBlockShapeAccepted && cauldronShapeRejected && overhangShapeRejected
            && multiBoxOverhangRejected && slabPolicyPreserved))
            throw new IllegalStateException("CI fissure support collision-shape guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_SUPPORT_SHAPE_GUARD_VERIFIED fullBlock=true cauldronRejected=true overhangRejected=true multiBoxRejected=true slabPolicyPreserved=true");
        boolean fixtureCellPolicy=
            isFissureCanopyFixtureCellClearable(Blocks.AIR.defaultBlockState(),level,policyPos)
            && isFissureCanopyFixtureCellClearable(Blocks.OAK_LEAVES.defaultBlockState(),level,policyPos)
            && isFissureCanopyFixtureCellClearable(Blocks.DANDELION.defaultBlockState(),level,policyPos)
            && !isFissureCanopyFixtureCellClearable(Blocks.WATER.defaultBlockState(),level,policyPos)
            && !isFissureCanopyFixtureCellClearable(Blocks.CHEST.defaultBlockState(),level,policyPos)
            && !isFissureCanopyFixtureCellClearable(Blocks.STONE.defaultBlockState(),level,policyPos);
        boolean fixtureBaseSurfacePolicy=
            isFissureSurfaceAvailable(Blocks.AIR.defaultBlockState())
            && isFissureSurfaceAvailable(Blocks.DANDELION.defaultBlockState())
            && !isFissureSurfaceAvailable(Blocks.OAK_LEAVES.defaultBlockState())
            && !isFissureSurfaceAvailable(Blocks.WATER.defaultBlockState());
        int fixtureX=Integer.MIN_VALUE,fixtureZ=Integer.MIN_VALUE,baseY=Integer.MIN_VALUE;
        for(int radius=2;radius<=8 && fixtureX==Integer.MIN_VALUE;radius++) {
            for(int dx=-radius;dx<=radius && fixtureX==Integer.MIN_VALUE;dx++) {
                for(int dz=-radius;dz<=radius;dz++) {
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius || dx*dx+dz*dz>64) continue;
                    int x=s.x+dx,z=s.z+dz;
                    if(!level.hasChunk(x>>4,z>>4)) continue;
                    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
                    if(y<level.getMinBuildHeight()+1 || y+4>=level.getMaxBuildHeight()) continue;
                    // The fixture adds leaves at y+4. Reject taller existing canopies so
                    // the expected MOTION_BLOCKING top remains exactly y+5 during the probe.
                    int existingSurfaceY=level.getHeight(FISSURE_SURFACE_HEIGHTMAP,x,z);
                    if(existingSurfaceY>y+5) continue;
                    cursor.set(x,y-1,z);
                    if(!level.getWorldBorder().isWithinBounds(cursor)) continue;
                    BlockState candidateSupport=level.getBlockState(cursor);
                    if(!isFissureGroundSupport(candidateSupport,level,cursor)) continue;
                    boolean clearColumn=true;
                    for(int offset=0;offset<=4;offset++) {
                        cursor.set(x,y+offset,z);
                        BlockState existing=level.getBlockState(cursor);
                        // The base cell must be an actual visible surface, not a leaf
                        // block. Leaves are valid canopy cells above it, but a leaf at
                        // ground level correctly hides the terrain sampler's support.
                        if((offset==0 && !isFissureSurfaceAvailable(existing))
                            || !isFissureCanopyFixtureCellClearable(existing,level,cursor)) {
                            clearColumn=false;
                            break;
                        }
                    }
                    if(clearColumn) {
                        fixtureX=x;fixtureZ=z;baseY=y;
                        break;
                    }
                }
            }
        }
        if(fixtureX==Integer.MIN_VALUE) {
            if(++s.ciCanopyFixtureSearchAttempts>=30)
                throw new IllegalStateException("CI could not find a safe loaded canopy fixture column within eight blocks");
            return false;
        }

        int originalSurfaceY=level.getHeight(FISSURE_SURFACE_HEIGHTMAP,fixtureX,fixtureZ);
        BlockState[] original=new BlockState[5];
        for(int offset=0;offset<original.length;offset++) {
            cursor.set(fixtureX,baseY+offset,fixtureZ);
            original[offset]=level.getBlockState(cursor);
        }
        boolean canopyAccepted=false,horizontalLogRejected=false,roofRejected=false,unloadedChunkRejected=false;
        boolean canopyFound=false;
        int canopyTop=Integer.MIN_VALUE,canopySurfaceY=Integer.MIN_VALUE;
        double canopyParticleY=Double.NaN,canopySurfaceShapeHeight=Double.NaN,expectedCanopyParticleY=Double.NaN;
        try {
            BlockState verticalLog=Blocks.OAK_LOG.defaultBlockState().setValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS,net.minecraft.core.Direction.Axis.Y);
            for(int offset=1;offset<=3;offset++) {
                cursor.set(fixtureX,baseY+offset,fixtureZ);
                if(!level.setBlock(cursor,verticalLog,2))
                    throw new IllegalStateException("CI could not place temporary vertical trunk");
            }
            BlockState leaves=Blocks.OAK_LEAVES.defaultBlockState();
            if(leaves.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT))
                leaves=leaves.setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true);
            cursor.set(fixtureX,baseY+4,fixtureZ);
            if(!level.setBlock(cursor,leaves,2))
                throw new IllegalStateException("CI could not place temporary canopy leaves");

            canopyTop=level.getHeight(FISSURE_SURFACE_HEIGHTMAP,fixtureX,fixtureZ);
            FissureGroundSample sample=new FissureGroundSample();
            canopyFound=findFissureGroundSample(level,fixtureX,fixtureZ,cursor,sample);
            canopySurfaceY=sample.surfaceY;
            canopyParticleY=sample.particleY;
            // The real surface may be a path, slab-like support, or another partial shape.
            // Derive the expected particle height from that sampled cell instead of assuming a full cube.
            cursor.set(fixtureX,baseY,fixtureZ);
            BlockState fixtureSurface=level.getBlockState(cursor);
            canopySurfaceShapeHeight=fixtureSurface.isAir()?0.0D
                : fixtureSurface.getShape(level,cursor).max(net.minecraft.core.Direction.Axis.Y);
            double boundedCanopyShapeHeight=Double.isFinite(canopySurfaceShapeHeight)
                ? Math.max(0.0D,Math.min(1.0D,canopySurfaceShapeHeight)) : 0.0D;
            // Keep this expectation independent of fissureSurfaceParticleY(), the method under test.
            expectedCanopyParticleY=baseY+boundedCanopyShapeHeight+0.10D;
            canopyAccepted=canopyTop==baseY+5 && canopyFound && canopySurfaceY==baseY
                && Math.abs(canopyParticleY-expectedCanopyParticleY)<1.0E-9D;
            if(!restoreFissureCanopyFixtureColumn(level,fixtureX,baseY,fixtureZ,original,originalSurfaceY,cursor))
                throw new IllegalStateException("CI failed to restore temporary canopy fixture");

            BlockState horizontalLog=verticalLog.setValue(
                net.minecraft.world.level.block.RotatedPillarBlock.AXIS,net.minecraft.core.Direction.Axis.X);
            cursor.set(fixtureX,baseY+2,fixtureZ);
            if(!level.setBlock(cursor,horizontalLog,2))
                throw new IllegalStateException("CI could not place temporary horizontal beam");
            horizontalLogRejected=!findFissureGroundSample(level,fixtureX,fixtureZ,cursor,sample)
                && sample.isEmpty();
            if(!restoreFissureCanopyFixtureColumn(level,fixtureX,baseY,fixtureZ,original,originalSurfaceY,cursor))
                throw new IllegalStateException("CI failed to restore temporary beam fixture");

            cursor.set(fixtureX,baseY+2,fixtureZ);
            if(!level.setBlock(cursor,Blocks.OAK_PLANKS.defaultBlockState(),2))
                throw new IllegalStateException("CI could not place temporary roof fixture");
            roofRejected=!findFissureGroundSample(level,fixtureX,fixtureZ,cursor,sample)
                && sample.isEmpty();
            if(!restoreFissureCanopyFixtureColumn(level,fixtureX,baseY,fixtureZ,original,originalSurfaceY,cursor))
                throw new IllegalStateException("CI failed to restore temporary roof fixture");

            // A rejected lookup must not leave the previous successful column in the reusable result.
            sample.surfaceY=baseY;
            sample.particleY=baseY+.10D;
            unloadedChunkRejected=!findFissureGroundSample(
                level,Integer.MAX_VALUE,Integer.MAX_VALUE,cursor,sample) && sample.isEmpty();
        } finally {
            if(!restoreFissureCanopyFixtureColumn(level,fixtureX,baseY,fixtureZ,original,originalSurfaceY,cursor))
                throw new IllegalStateException("CI canopy fixture cleanup failed");
        }

        if(!(fixtureCellPolicy && fixtureBaseSurfacePolicy && canopyAccepted
            && horizontalLogRejected && roofRejected && unloadedChunkRejected))
            throw new IllegalStateException("CI real-world canopy scan/structure occlusion regression"
                +" fixtureCellPolicy="+fixtureCellPolicy+" fixtureBaseSurfacePolicy="+fixtureBaseSurfacePolicy
                +" canopyAccepted="+canopyAccepted
                +" canopyFound="+canopyFound+" canopyTop="+canopyTop+" expectedCanopyTop="+(baseY+5)
                +" canopySurfaceY="+canopySurfaceY+" expectedSurfaceY="+baseY
                +" canopyParticleY="+canopyParticleY+" expectedCanopyParticleY="+expectedCanopyParticleY+" surfaceShapeHeight="+canopySurfaceShapeHeight+" horizontalLogRejected="+horizontalLogRejected
                +" roofRejected="+roofRejected+" unloadedChunkRejected="+unloadedChunkRejected
                +" fixture="+fixtureX+","+baseY+","+fixtureZ+" originalSurfaceY="+originalSurfaceY);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_FIXTURE_CELL_POLICY_VERIFIED air=true leaves=true nonSolidPlants=true fluids=false blockEntities=false solids=false");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_BASE_SURFACE_POLICY_VERIFIED air=true plants=true leavesRejected=true fluidsRejected=true");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_FIXTURE_RESTORATION_VERIFIED originalHeightRestored=true loadedOnly=true radius=8");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_TRAVERSAL_VERIFIED verticalLogs=true leaves=true horizontalLogsRejected=true roofBlocksRejected=true nonSolidPlantsRetained=true heightmapIncludesLeaves=true depthBound=32");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_WORLD_CANOPY_SAMPLE_VERIFIED heightmapTopOffset=5 canopyBlocks=4 surfaceFound=true horizontalLogRejected=true roofRejected=true loadedOnly=true searchRadius=8 restored=true rejectedResultCleared=true unloadedChunkRejected=true");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_CANOPY_PARTICLE_HEIGHT_VERIFIED shapeHeight={} particleY={} expectedParticleY={} shapeDerived=true",canopySurfaceShapeHeight,canopyParticleY,expectedCanopyParticleY);
        return true;
    }

    private static boolean restoreFissureCanopyFixtureColumn(ServerLevel level,int x,int baseY,int z,
            BlockState[] original,int originalSurfaceY,BlockPos.MutableBlockPos cursor) {
        for(int offset=0;offset<original.length;offset++) {
            cursor.set(x,baseY+offset,z);
            if(!level.getBlockState(cursor).equals(original[offset])
                && !level.setBlock(cursor,original[offset],2)) return false;
            if(!level.getBlockState(cursor).equals(original[offset])) return false;
        }
        return level.hasChunk(x>>4,z>>4)
            && level.getHeight(FISSURE_SURFACE_HEIGHTMAP,x,z)==originalSurfaceY;
    }

    // One scratch result is reused for every sampled column. The surface BlockState
    // is read once, then its outline height is captured before the mutable cursor moves
    // to the support block. Particle placement never re-reads the same surface cell.
    private static final class FissureGroundSample {
        private int surfaceY=Integer.MIN_VALUE;
        private double particleY=Double.NaN;

        private void clear() {
            surfaceY=Integer.MIN_VALUE;
            particleY=Double.NaN;
        }

        private boolean isEmpty() {
            return surfaceY==Integer.MIN_VALUE && Double.isNaN(particleY);
        }
    }

    private static boolean isFissureColumnInsideWorldBorder(
            net.minecraft.world.level.border.WorldBorder border,int x,int z,int minBuildHeight,
            BlockPos.MutableBlockPos cursor) {
        if(border==null || cursor==null) return false;
        cursor.set(x,minBuildHeight,z);
        return border.isWithinBounds(cursor);
    }

    private static boolean findFissureGroundSample(ServerLevel level,int x,int z,
            BlockPos.MutableBlockPos cursor,FissureGroundSample result) {
        if(result==null) return false;
        result.clear();
        if(level==null || cursor==null) return false;
        if(!isFissureColumnInsideWorldBorder(level.getWorldBorder(),x,z,level.getMinBuildHeight(),cursor)
            || !level.hasChunk(x>>4,z>>4)) return false;
        int y=level.getHeight(FISSURE_SURFACE_HEIGHTMAP,x,z);
        int min=level.getMinBuildHeight();
        for(int depth=0;depth<FISSURE_SURFACE_SEARCH_DEPTH && y>min+1;depth++,y--) {
            cursor.set(x,y,z);
            BlockState surface=level.getBlockState(cursor);
            if(!isFissureSurfaceAvailable(surface)) {
                // Vertical trunks and leaves are canopy, not a reason to abandon the ground scan.
                // Horizontal logs and built roofs remain opaque so cracks never pass through structures.
                if(isFissureCanopyOccluder(surface)) continue;
                return false;
            }
            double shapeHeight=surface.isAir() ? 0.0D
                : surface.getShape(level,cursor).max(net.minecraft.core.Direction.Axis.Y);
            cursor.set(x,y-1,z);
            BlockState support=level.getBlockState(cursor);
            if(isFissureGroundSupport(support,level,cursor)) {
                result.surfaceY=y;
                result.particleY=fissureSurfaceParticleY(y,shapeHeight);
                return true;
            }
        }
        return false;
    }

    /**
     * Keep fissure motes above partial surface blocks such as snow layers.
     * The heightmap can stop at the solid support while a non-motion-blocking
     * layer occupies the first air cell; spawning at +0.10 would hide particles
     * inside that layer. Clamp shape height to one block and reject non-finite data.
     */
    private static double fissureSurfaceParticleY(int surfaceY,double shapeHeight) {
        double boundedHeight=Double.isFinite(shapeHeight)
            ? Math.max(0.0D,Math.min(1.0D,shapeHeight)) : 0.0D;
        return surfaceY+boundedHeight+0.10D;
    }

    private static void verifyFissureSurfaceParticleHeight() {
        FissureGroundSample scratch=new FissureGroundSample();
        scratch.surfaceY=65;
        scratch.particleY=fissureSurfaceParticleY(65,0.125D);
        boolean partialShapeRetained=scratch.surfaceY==65
            && Math.abs(scratch.particleY-65.225D)<1.0E-9D;
        scratch.surfaceY=66;
        scratch.particleY=fissureSurfaceParticleY(66,1.0D);
        boolean scratchReusable=scratch.surfaceY==66
            && Math.abs(scratch.particleY-67.10D)<1.0E-9D;
        if(!partialShapeRetained || !scratchReusable
            || Math.abs(fissureSurfaceParticleY(65,0.0D)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,1.0D)-66.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,Double.NaN)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,Double.POSITIVE_INFINITY)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,2.0D)-66.10D)>1.0E-9D)
            throw new IllegalStateException("CI fissure surface particle height/sample reuse regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_PARTICLE_HEIGHT_VERIFIED");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_SAMPLE_STATE_REUSE_VERIFIED surfaceReads=1 particleRereads=0 scratchReusable=true");
        verifyFissureCanopyTraversal();
    }

    /**
     * Draw a dense twelve-segment, four-armed surface fissure without changing terrain.
     * Every sample is restricted to the rift radius and an already loaded chunk.
     */
    private static FissureEmission emitSurfaceFissure(ServerLevel level,State s,double phase) {
        // One mutable cursor serves every sample; block-state lookups consume it immediately.
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        FissureGroundSample groundSample=new FissureGroundSample();
        int samples=0;
        int accentDustMotes=0;
        int violetDustMotes=0;
        int edgeDustMotes=0;
        int edgeArmsWithSamples=0;
        int edgeFallbackArms=0;
        int edgeDustSmallMotes=0;
        int edgeDustBaseMotes=0;
        int edgeDustLargeMotes=0;
        double surfaceYTotal=0.0D;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            int edgeCandidateSegment=0;
            boolean hasEdgeCandidate=false;
            double edgeX=0.0D,edgeY=0.0D,edgeZ=0.0D,edgePulse=0.0D,edgeWave=0.0D;
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                FissureSample sample=FISSURE_SAMPLES[arm*FISSURE_SEGMENTS_PER_ARM+segment-1];
                double px=s.x+.5+sample.forwardX()-sample.bendX();
                double pz=s.z+.5+sample.forwardZ()+sample.bendZ();
                int blockX=(int)Math.floor(px);
                int blockZ=(int)Math.floor(pz);
                long dx=blockX-(long)s.x;
                long dz=blockZ-(long)s.z;
                cursor.set(blockX,level.getMinBuildHeight(),blockZ);
                if(dx*dx+dz*dz>(long)RADIUS*RADIUS
                    || !level.getWorldBorder().isWithinBounds(cursor)
                    || !level.hasChunk(blockX>>4,blockZ>>4)) continue;
                if(!findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)
                    || groundSample.surfaceY>=level.getMaxBuildHeight()) continue;
                double particleY=groundSample.particleY;
                double wave=fissurePulseWave(phase,sample);
                double pulse=wave*.05D;
                DustParticleOptions dust=fissureDustForWave(wave);
                // Keep the accent on the furthest usable sample. If the exact outer cell
                // is unloaded or structurally blocked, the previous valid sample is used.
                if(isOutermostValidFissureSample(segment,edgeCandidateSegment)) {
                    edgeCandidateSegment=segment;
                    edgeX=px;
                    edgeY=particleY;
                    edgeZ=pz;
                    edgePulse=pulse;
                    edgeWave=wave;
                    hasEdgeCandidate=true;
                }
                if(isAccentFissureDustSegment(segment)) {
                    level.sendParticles(SURFACE_FISSURE_ACCENT_DUST,
                        px,particleY+pulse,pz,FISSURE_MOTES_PER_SAMPLE,.045,.025,.045,.006);
                    accentDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                } else {
                    level.sendParticles(dust,
                        px,particleY+pulse,pz,FISSURE_MOTES_PER_SAMPLE,.035,.012,.035,.008);
                    violetDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                }
                surfaceYTotal+=particleY;
                samples++;
            }
            // Emit exactly one edge accent per arm that had a valid, already-loaded sample.
            if(hasEdgeCandidate) {
                edgeArmsWithSamples++;
                DustParticleOptions edgeDust=fissureEdgeDustForWave(edgeWave);
                level.sendParticles(edgeDust,
                    edgeX,edgeY+.025D+edgePulse,edgeZ,1,.025D,.012D,.025D,.004D);
                if(edgeDust==FISSURE_VIOLET_EDGE_DUST_SMALL) edgeDustSmallMotes++;
                else if(edgeDust==FISSURE_VIOLET_EDGE_DUST_LARGE) edgeDustLargeMotes++;
                else edgeDustBaseMotes++;
                edgeDustMotes++;
                if(edgeCandidateSegment<FISSURE_SEGMENTS_PER_ARM) edgeFallbackArms++;
            }
        }
        if(edgeDustMotes!=edgeArmsWithSamples)
            throw new IllegalStateException("BLACK FREQUENCY edge Dust must match valid fissure arms");
        return new FissureEmission(samples,accentDustMotes,violetDustMotes,edgeDustMotes,
            edgeArmsWithSamples,edgeFallbackArms,edgeDustSmallMotes,edgeDustBaseMotes,edgeDustLargeMotes,
            samples==0?Double.NaN:surfaceYTotal/samples);
    }

    private record FissureTarget(double x,double y,double z,double distanceSquared,
            double horizontalDistanceSquared,double viewScore) {}

    private static boolean isBetterFissureTarget(double candidateScore,
            double bestScore,boolean hasBest) {
        if(!Double.isFinite(candidateScore) || candidateScore<0.0D)
            return false;
        if(!hasBest) return true;
        return Double.isFinite(bestScore) && bestScore>=0.0D
            && candidateScore<bestScore;
    }

    private static boolean isFissureTargetHorizontalDistance(double horizontalSquared) {
        double minimumSquared=FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE*FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE;
        double maximumSquared=FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE*FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE;
        return Double.isFinite(horizontalSquared)
            && horizontalSquared>=minimumSquared && horizontalSquared<=maximumSquared;
    }

    // A 45-degree ground view keeps the fissure readable without aiming almost
    // vertically down at the nearest sample or flattening the crack against the horizon.
    private static double fissureTargetViewScore(double horizontalSquared,double verticalDelta) {
        if(!isFissureTargetHorizontalDistance(horizontalSquared) || !Double.isFinite(verticalDelta))
            return Double.POSITIVE_INFINITY;
        double horizontalDistance=Math.sqrt(horizontalSquared);
        double pitchDegrees=Math.toDegrees(Math.atan2(Math.abs(verticalDelta),horizontalDistance));
        double score=Math.abs(pitchDegrees-45.0D);
        return Double.isFinite(score)?score:Double.POSITIVE_INFINITY;
    }

    private static void verifyFissureTargetSelectionPolicy() {
        double minimumSquared=FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE*FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE;
        double maximumSquared=FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE*FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE;
        double balanced=fissureTargetViewScore(25.0D,5.0D);
        double steep=fissureTargetViewScore(25.0D,10.0D);
        double shallow=fissureTargetViewScore(25.0D,2.0D);
        if(!isBetterFissureTarget(4.0D,Double.NaN,false)
            || !isBetterFissureTarget(4.0D,16.0D,true)
            || isBetterFissureTarget(16.0D,4.0D,true)
            || isBetterFissureTarget(4.0D,4.0D,true)
            || isBetterFissureTarget(Double.NaN,0.0D,false)
            || isBetterFissureTarget(-1.0D,0.0D,false)
            || isBetterFissureTarget(4.0D,Double.NaN,true)
            || !isFissureTargetHorizontalDistance(minimumSquared)
            || !isFissureTargetHorizontalDistance(maximumSquared)
            || isFissureTargetHorizontalDistance(minimumSquared-1.0E-6D)
            || isFissureTargetHorizontalDistance(maximumSquared+1.0E-6D)
            || isFissureTargetHorizontalDistance(Double.NaN)
            || isFissureTargetHorizontalDistance(Double.POSITIVE_INFINITY)
            || isFissureTargetHorizontalDistance(-1.0D)
            || !Double.isFinite(balanced) || Math.abs(balanced)>1.0E-9D
            || !(steep>balanced) || !(shallow>balanced)
            || fissureTargetViewScore(25.0D,Double.NaN)!=Double.POSITIVE_INFINITY
            || fissureTargetViewScore(25.0D,Double.POSITIVE_INFINITY)!=Double.POSITIVE_INFINITY)
            throw new IllegalStateException("CI fissure target selection regression");
        double beaconPadNear=6.5D;
        double beaconPadFar=11.75D;
        // The Sparse client failure on 2026-11-20 produced this valid 7.62-block
        // camera target. Keep it above the retired six-block test cap and inside
        // the same 12-block bound used by the loaded-only server selector.
        double sparseCameraFixtureSquared=58.11826889234223D;
        if(Math.abs(FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE-12.0D)>1.0E-9D
            || !isFissureTargetHorizontalDistance(beaconPadNear*beaconPadNear)
            || !isFissureTargetHorizontalDistance(beaconPadFar*beaconPadFar)
            || !isFissureTargetHorizontalDistance(sparseCameraFixtureSquared)
            || sparseCameraFixtureSquared<=6.0D*6.0D
            || isFissureTargetHorizontalDistance(12.01D*12.01D))
            throw new IllegalStateException("CI beacon-side fissure visibility distance regression");
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_BEACON_STANDOFF_PROFILE_VERIFIED maxHorizontal={} beaconPadNear={} beaconPadFar={} boundaryRejected=true",
            FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE,beaconPadNear,beaconPadFar);
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_SPARSE_RANGE_FIXTURE_VERIFIED horizontalSquared={} acceptedBeyondLegacySixBlockCap=true",
            sparseCameraFixtureSquared);
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_SELECTION_VERIFIED policy=balanced-view targetPitch=45 ties=stable invalidRejected=true minHorizontal={} maxHorizontal={}",
            FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE,FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE);
        verifyCiBeaconPadProfile();
    }

    private static boolean ciBeaconPadAlongX(int anchorX,int anchorZ,int beaconX,int beaconZ) {
        long anchorDeltaX=(long)beaconX-anchorX;
        long anchorDeltaZ=(long)beaconZ-anchorZ;
        // A Z-dominant anchor offset leaves an X-side pad perpendicular to the fissure.
        return Math.abs(anchorDeltaZ)>=Math.abs(anchorDeltaX);
    }

    private static boolean shouldTryAlternateCiBeaconPadAxis(int primarySign) {
        return primarySign==0;
    }

    private static int selectCiBeaconPadSign(boolean positiveClear,boolean negativeClear) {
        return positiveClear?1:negativeClear?-1:0;
    }

    /**
     * Test both perpendicular sides before building the disposable pad. A village wall,
     * tree or other natural block may occupy the nominally empty sightline row.
     * This probe reads only already-loaded chunks and never clears natural terrain.
     */
    private static boolean ciBeaconPadApproachClear(ServerLevel level,net.minecraft.server.level.ServerPlayer player,State s,BlockPos beacon,
            boolean alongX,int sign) {
        if(sign!=1 && sign!=-1) return false;
        for(int offset=CI_BEACON_PAD_FIRST_SOLID_ROW;offset<=CI_BEACON_PAD_LAST_SOLID_ROW;offset++) {
            for(int lateral=-CI_BEACON_PAD_LATERAL_RADIUS;lateral<=CI_BEACON_PAD_LATERAL_RADIUS;lateral++) {
                int dx=alongX?sign*offset:lateral;
                int dz=alongX?lateral:sign*offset;
                BlockPos floor=beacon.offset(dx,CI_BEACON_PAD_FLOOR_Y_OFFSET,dz);
                long radiusX=(long)floor.getX()-s.x;
                long radiusZ=(long)floor.getZ()-s.z;
                if(Math.abs(radiusX)>RADIUS || Math.abs(radiusZ)>RADIUS
                    || radiusX*radiusX+radiusZ*radiusZ>(long)RADIUS*RADIUS
                    || !level.getWorldBorder().isWithinBounds(floor)
                    || !level.hasChunk(floor.getX()>>4,floor.getZ()>>4)
                    || floor.getY()<level.getMinBuildHeight()
                    || (long)floor.getY()+3L>=level.getMaxBuildHeight()) return false;
            }
        }
        double playerX=beacon.getX()+.5D+(alongX?sign*CI_BEACON_PAD_STANDOFF:0.0D);
        double playerZ=beacon.getZ()+.5D+(alongX?0.0D:sign*CI_BEACON_PAD_STANDOFF);
        double playerY=beacon.getY()+CI_BEACON_PAD_PLAYER_Y_OFFSET;
        if(!Double.isFinite(playerX) || !Double.isFinite(playerZ)
            || Math.abs(playerX)>30_000_000.0D || Math.abs(playerZ)>30_000_000.0D) return false;
        BlockPos feet=BlockPos.containing(playerX,playerY,playerZ);
        BlockPos support=feet.below();
        long supportX=(long)support.getX()-s.x;
        long supportZ=(long)support.getZ()-s.z;
        if(Math.abs(supportX)>RADIUS || Math.abs(supportZ)>RADIUS
            || supportX*supportX+supportZ*supportZ>(long)RADIUS*RADIUS
            || !level.getWorldBorder().isWithinBounds(feet)
            || !level.getWorldBorder().isWithinBounds(support)
            || !level.hasChunk(feet.getX()>>4,feet.getZ()>>4)
            || !level.hasChunk(support.getX()>>4,support.getZ()>>4)) return false;

        net.minecraft.world.phys.Vec3 eye=new net.minecraft.world.phys.Vec3(
            playerX,playerY+CI_BEACON_PLAYER_EYE_HEIGHT,playerZ);
        double eyeToBeaconDistanceSquared=eyeDistanceSquaredToBlock(eye,beacon);
        if(!withinBeaconInteractionReach(eyeToBeaconDistanceSquared)) return false;
        net.minecraft.world.phys.Vec3 target=net.minecraft.world.phys.Vec3.atCenterOf(beacon);
        if(!fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ)))
            return false;
        var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,target,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,
            player));
        if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK
            || !hit.getBlockPos().equals(beacon)) return false;
        // A clear beacon ray is not enough: reject a side whose camera is boxed in
        // by nearby village terrain and cannot see any actual loaded fissure sample.
        return findVisibleFissureTarget(level,s,player,eye)!=null;
    }

    private static int chooseCiBeaconPadSign(ServerLevel level,net.minecraft.server.level.ServerPlayer player,State s,BlockPos beacon,
            boolean alongX) {
        boolean positiveClear=ciBeaconPadApproachClear(level,player,s,beacon,alongX,1);
        boolean negativeClear=ciBeaconPadApproachClear(level,player,s,beacon,alongX,-1);
        return selectCiBeaconPadSign(positiveClear,negativeClear);
    }

    private static void verifyCiBeaconPadSignSelection() {
        if(selectCiBeaconPadSign(true,true)!=1
            || selectCiBeaconPadSign(false,true)!=-1
            || selectCiBeaconPadSign(false,false)!=0
            || !shouldTryAlternateCiBeaconPadAxis(0)
            || shouldTryAlternateCiBeaconPadAxis(1)
            || shouldTryAlternateCiBeaconPadAxis(-1))
            throw new IllegalStateException("CI beacon pad clear-side selection regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_APPROACH_SIDE_SELECTION_VERIFIED positivePreferred=true reverseFallback=true blockedRejected=true fissureVisibilityRequired=true");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_APPROACH_AXIS_FALLBACK_POLICY_VERIFIED primaryPerpendicular=true alternateAfterBothSidesBlocked=true loadedOnly=true");
    }

    private static double ciBeaconPadDistanceSquared() {
        double vertical=CI_BEACON_PAD_PLAYER_Y_OFFSET-.5D;
        double horizontal=CI_BEACON_PAD_STANDOFF;
        return horizontal*horizontal+vertical*vertical;
    }

    /** Uses the same eye-to-block-AABB metric as the real beacon interaction guard. */
    private static double ciBeaconPadEyeDistanceSquared() {
        net.minecraft.world.phys.Vec3 eye=new net.minecraft.world.phys.Vec3(
            .5D+CI_BEACON_PAD_STANDOFF,
            CI_BEACON_PAD_FLOOR_Y_OFFSET+CI_BEACON_PAD_PLAYER_Y_OFFSET+CI_BEACON_PLAYER_EYE_HEIGHT,
            .5D);
        return eyeDistanceSquaredToBlock(eye,new BlockPos(0,0,0));
    }

    private static double ciBeaconSightlineHeightAtProgress(double horizontalProgress) {
        double horizontal=CI_BEACON_PAD_STANDOFF;
        if(!Double.isFinite(horizontal) || horizontal<=0.0D
            || !Double.isFinite(horizontalProgress)
            || horizontalProgress<0.0D || horizontalProgress>horizontal) return Double.NaN;
        double eyeY=CI_BEACON_PAD_PLAYER_Y_OFFSET+CI_BEACON_PLAYER_EYE_HEIGHT;
        return eyeY+(.5D-eyeY)*(horizontalProgress/horizontal);
    }

    private static double ciBeaconSightlineHeightAtBeaconFace() {
        return ciBeaconSightlineHeightAtProgress(CI_BEACON_PAD_STANDOFF-.5D);
    }

    private static double ciBeaconSightlineHeightAtPadInnerFace() {
        double innerFaceDistance=CI_BEACON_PAD_STANDOFF-(CI_BEACON_PAD_FIRST_SOLID_ROW-.5D);
        return ciBeaconSightlineHeightAtProgress(innerFaceDistance);
    }

    private static void verifyCiBeaconPadProfile() {
        double distanceSquared=ciBeaconPadDistanceSquared();
        double eyeToAabbDistanceSquared=ciBeaconPadEyeDistanceSquared();
        double maxInteractionDistanceSquared=CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE;
        double beaconEntryY=ciBeaconSightlineHeightAtBeaconFace();
        double padInnerFaceY=ciBeaconSightlineHeightAtPadInnerFace();
        if(CI_BEACON_PAD_FLOOR_Y_OFFSET!=0
            || CI_BEACON_PAD_PLAYER_Y_OFFSET!=1
            || CI_BEACON_PAD_LATERAL_RADIUS!=0
            || Math.abs(CI_BEACON_PAD_STANDOFF-3.0D)>1.0E-9D
            || CI_BEACON_PAD_GAP_ROW!=2
            || CI_BEACON_PAD_FIRST_SOLID_ROW!=3
            || CI_BEACON_PAD_LAST_SOLID_ROW!=3
            || CI_BEACON_PAD_FIRST_SOLID_ROW!=CI_BEACON_PAD_GAP_ROW+1
            || CI_BEACON_PAD_LAST_SOLID_ROW!=CI_BEACON_PAD_FIRST_SOLID_ROW
            || !ciBeaconPadAlongX(2,8,2,11)
            || ciBeaconPadAlongX(2,8,5,8)
            || !ciBeaconPadAlongX(2,8,5,11)
            || !ciBeaconPadAlongX(0,Integer.MIN_VALUE,0,Integer.MAX_VALUE)
            || ciBeaconPadAlongX(Integer.MIN_VALUE,0,Integer.MAX_VALUE,0)
            || !Double.isFinite(distanceSquared)
            || Math.abs(distanceSquared-9.25D)>1.0E-9D
            || distanceSquared>maxInteractionDistanceSquared
            || !Double.isFinite(eyeToAabbDistanceSquared)
            || Math.abs(eyeToAabbDistanceSquared-8.8744D)>1.0E-9D
            || !withinBeaconInteractionReach(eyeToAabbDistanceSquared)
            || !Double.isFinite(beaconEntryY) || !(beaconEntryY>0.0D && beaconEntryY<1.0D)
            || !Double.isFinite(padInnerFaceY)
            || !(padInnerFaceY>CI_BEACON_PAD_FLOOR_Y_OFFSET+1.0D))
            throw new IllegalStateException("CI beacon pad reach/perpendicular sightline profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED floorOffset={} playerOffset={} lateralRadius={} axisPolicy=perpendicular firstSolidRow={} gapRow={} lastSolidRow={} standoff={} distanceSquared={} padInnerFaceY={} beaconEntryY={} withinReach=true beamColumnUntouched=true perpendicular=true",
            CI_BEACON_PAD_FLOOR_Y_OFFSET,CI_BEACON_PAD_PLAYER_Y_OFFSET,CI_BEACON_PAD_LATERAL_RADIUS,
            CI_BEACON_PAD_FIRST_SOLID_ROW,CI_BEACON_PAD_GAP_ROW,CI_BEACON_PAD_LAST_SOLID_ROW,
            CI_BEACON_PAD_STANDOFF,distanceSquared,padInnerFaceY,beaconEntryY);
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_SAFE_PAD_EYE_REACH_VERIFIED distanceSquared={} maxDistanceSquared={} withinReach=true",
            eyeToAabbDistanceSquared,maxInteractionDistanceSquared);
        verifyFissureRayChunkTraversalProfile();
        verifyCiBeaconPadSignSelection();
        verifyCiBeaconBreakReachGeometry();
    }

    @FunctionalInterface
    private interface LoadedChunkLookup {
        boolean hasChunk(int chunkX,int chunkZ);
    }

    /**
     * Checks only the X/Z chunks crossed by a ray. The previous AABB scan could
     * reject a visible target because an unrelated diagonal chunk was unloaded.
     * The lookup is deliberately hasChunk-only: this path must never request a chunk.
     */
    private static boolean fissureRayChunksLoaded(net.minecraft.world.phys.Vec3 from,
            net.minecraft.world.phys.Vec3 to,LoadedChunkLookup chunks) {
        if(from==null || to==null || chunks==null
            || !Double.isFinite(from.x) || !Double.isFinite(from.z)
            || !Double.isFinite(to.x) || !Double.isFinite(to.z)
            || Math.abs(from.x)>30_000_000.0D || Math.abs(from.z)>30_000_000.0D
            || Math.abs(to.x)>30_000_000.0D || Math.abs(to.z)>30_000_000.0D) return false;

        double startX=from.x,startZ=from.z,endX=to.x,endZ=to.z;
        int chunkX=((int)Math.floor(startX))>>4;
        int chunkZ=((int)Math.floor(startZ))>>4;
        int endChunkX=((int)Math.floor(endX))>>4;
        int endChunkZ=((int)Math.floor(endZ))>>4;
        if(!chunks.hasChunk(chunkX,chunkZ)) return false;
        if(chunkX==endChunkX && chunkZ==endChunkZ) return true;

        double deltaX=endX-startX,deltaZ=endZ-startZ;
        int stepX=deltaX>0.0D ? 1 : deltaX<0.0D ? -1 : 0;
        int stepZ=deltaZ>0.0D ? 1 : deltaZ<0.0D ? -1 : 0;
        double nextX=stepX==0 ? Double.POSITIVE_INFINITY
            : ((stepX>0 ? (chunkX+1)*16.0D : chunkX*16.0D)-startX)/deltaX;
        double nextZ=stepZ==0 ? Double.POSITIVE_INFINITY
            : ((stepZ>0 ? (chunkZ+1)*16.0D : chunkZ*16.0D)-startZ)/deltaZ;
        double deltaTX=stepX==0 ? Double.POSITIVE_INFINITY : 16.0D/Math.abs(deltaX);
        double deltaTZ=stepZ==0 ? Double.POSITIVE_INFINITY : 16.0D/Math.abs(deltaZ);
        long maxSteps=Math.abs((long)endChunkX-chunkX)
            +Math.abs((long)endChunkZ-chunkZ)+1L;

        for(long visited=0;visited<maxSteps;visited++) {
            if(chunkX==endChunkX && chunkZ==endChunkZ) return true;
            double crossingDelta=nextX-nextZ;
            if(crossingDelta< -1.0E-12D) {
                chunkX+=stepX;
                nextX+=deltaTX;
            } else if(crossingDelta>1.0E-12D) {
                chunkZ+=stepZ;
                nextZ+=deltaTZ;
            } else {
                // A ray exactly through a chunk corner touches both side cells.
                // Check them before entering the diagonal cell to stay conservative.
                int sideChunkX=chunkX+stepX;
                int sideChunkZ=chunkZ+stepZ;
                if(stepX!=0 && !chunks.hasChunk(sideChunkX,chunkZ)) return false;
                if(stepZ!=0 && !chunks.hasChunk(chunkX,sideChunkZ)) return false;
                chunkX=sideChunkX;
                chunkZ=sideChunkZ;
                nextX+=deltaTX;
                nextZ+=deltaTZ;
            }
            if(!chunks.hasChunk(chunkX,chunkZ)) return false;
        }
        return chunkX==endChunkX && chunkZ==endChunkZ;
    }

    private static void verifyFissureRayChunkTraversalProfile() {
        boolean skipsUncrossedAabbCell=fissureRayChunksLoaded(
            new net.minecraft.world.phys.Vec3(15.25D,64.0D,15.0D),
            new net.minecraft.world.phys.Vec3(16.25D,64.0D,16.9D),
            (chunkX,chunkZ)->!(chunkX==1 && chunkZ==0));
        boolean requiresCrossedChunk=!fissureRayChunksLoaded(
            new net.minecraft.world.phys.Vec3(15.25D,64.0D,15.0D),
            new net.minecraft.world.phys.Vec3(16.25D,64.0D,16.9D),
            (chunkX,chunkZ)->!(chunkX==0 && chunkZ==1));
        boolean negativeDirection=fissureRayChunksLoaded(
            new net.minecraft.world.phys.Vec3(16.75D,64.0D,16.9D),
            new net.minecraft.world.phys.Vec3(15.25D,64.0D,15.0D),
            (chunkX,chunkZ)->true);
        boolean cornerGuard=!fissureRayChunksLoaded(
            new net.minecraft.world.phys.Vec3(15.5D,64.0D,15.5D),
            new net.minecraft.world.phys.Vec3(16.5D,64.0D,16.5D),
            (chunkX,chunkZ)->!(chunkX==1 && chunkZ==0));
        boolean invalidRejected=!fissureRayChunksLoaded(
            new net.minecraft.world.phys.Vec3(Double.NaN,64.0D,0.0D),
            new net.minecraft.world.phys.Vec3(1.0D,64.0D,1.0D),
            (chunkX,chunkZ)->true)
            && !fissureRayChunksLoaded(
                new net.minecraft.world.phys.Vec3(30_000_001.0D,64.0D,0.0D),
                new net.minecraft.world.phys.Vec3(1.0D,64.0D,1.0D),
                (chunkX,chunkZ)->true);
        if(!(skipsUncrossedAabbCell && requiresCrossedChunk && negativeDirection
            && cornerGuard && invalidRejected))
            throw new IllegalStateException("BLACK FREQUENCY loaded-only ray chunk traversal regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RAY_CHUNK_TRAVERSAL_VERIFIED skippedAabbOnlyChunk=true traversedChunkRequired=true negativeDirection=true cornerTouchGuard=true invalidRejected=true");
    }

    /** True only when a loaded-only outline ray reaches the actual physical beacon. */
    private static boolean hasLoadedBeaconSightline(ServerLevel level,
            net.minecraft.server.level.ServerPlayer player,BlockPos beacon) {
        return level!=null && beacon!=null
            && hasLoadedBeaconSightline(level,player,beacon,
                net.minecraft.world.phys.Vec3.atCenterOf(beacon));
    }

    private static boolean hasLoadedBeaconSightline(ServerLevel level,
            net.minecraft.server.level.ServerPlayer player,BlockPos beacon,
            net.minecraft.world.phys.Vec3 target) {
        return hasLoadedBeaconSightline(level,player,beacon,target,null);
    }

    private static boolean hasLoadedBeaconSightline(ServerLevel level,
            net.minecraft.server.level.ServerPlayer player,BlockPos beacon,
            net.minecraft.world.phys.Vec3 target,net.minecraft.world.phys.Vec3 expectedHitPoint) {
        if(level==null || player==null || beacon==null || !isFiniteBeaconVector(target)
            || (expectedHitPoint!=null && !isFiniteBeaconVector(expectedHitPoint))
            || target.x<beacon.getX()-1.0E-5D || target.x>beacon.getX()+1.0D+1.0E-5D
            || target.y<beacon.getY()-1.0E-5D || target.y>beacon.getY()+1.0D+1.0E-5D
            || target.z<beacon.getZ()-1.0E-5D || target.z>beacon.getZ()+1.0D+1.0E-5D) return false;
        net.minecraft.world.phys.Vec3 eye=player.getEyePosition();
        if(!isFiniteBeaconVector(eye)
            || !fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ))) {
            if(CI_TEST && !ciBeaconSightlineFailureLogged) {
                ciBeaconSightlineFailureLogged=true;
                LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_BLOCKER_DIAGNOSTIC reason=unloaded-chunk eye={} target={}",eye,beacon);
            }
            return false;
        }
        var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,target,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE,player));
        boolean reachesBeacon=hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK
            && hit.getBlockPos().equals(beacon);
        boolean hitPointMatches=expectedHitPoint==null
            || sameBeaconHitPoint(hit.getLocation(),expectedHitPoint);
        boolean verified=reachesBeacon && hitPointMatches;
        if(!verified && CI_TEST && !ciBeaconSightlineFailureLogged) {
            ciBeaconSightlineFailureLogged=true;
            LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_BLOCKER_DIAGNOSTIC reason={} type={} block={} eye={} target={}",
                reachesBeacon?"hit-point-mismatch":"ray-hit",hit.getType(),hit.getBlockPos(),eye,beacon);
        }
        return verified;
    }

    private static boolean isFiniteBeaconVector(net.minecraft.world.phys.Vec3 value) {
        return value!=null && Double.isFinite(value.x)
            && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static double eyeDistanceSquaredToHit(net.minecraft.world.phys.Vec3 eye,
            net.minecraft.world.phys.Vec3 hitPoint) {
        if(!isFiniteBeaconVector(eye) || !isFiniteBeaconVector(hitPoint)) return Double.NaN;
        double distanceSquared=eye.distanceToSqr(hitPoint);
        return Double.isFinite(distanceSquared) && distanceSquared>=0.0D
            ? distanceSquared : Double.NaN;
    }

    private static boolean sameBeaconHitPoint(net.minecraft.world.phys.Vec3 actual,
            net.minecraft.world.phys.Vec3 expected) {
        double distanceSquared=eyeDistanceSquaredToHit(actual,expected);
        return Double.isFinite(distanceSquared) && distanceSquared<=1.0E-6D;
    }

    // Only the exact beacon that owns an in-progress recovery loses its vanilla menu.
    // Other nearby beacons, completed recoveries and legacy saves without a known
    // beacon coordinate keep their normal interaction behavior.
    private static boolean shouldSuppressActiveRecoveryBeaconMenu(boolean started,boolean restoring,
            boolean restored,boolean beaconPosPresent,long activeBeaconPos,long clickedBeaconPos,
            boolean hasPendingSnapshots) {
        return started && restoring && !restored && beaconPosPresent && hasPendingSnapshots
            && activeBeaconPos==clickedBeaconPos;
    }

    /**
     * Share the exact lifecycle and radius gate between real beacon clicks and recovery tests.
     * A quarantined save may restart only through a nearby physical beacon with pending snapshots.
     */
    private static boolean canStartBeaconRestoration(State state,BlockPos beacon) {
        return state!=null && beacon!=null && state.started && !state.restoring && !state.restored
            && state.snapshots!=null && !state.snapshots.isEmpty()
            && beaconWithinAnchor(beacon.getX(),beacon.getZ(),state.x,state.z);
    }

    /**
     * Disposable-client regression: prove that a loaded obstruction between the player
     * and the physical beacon is rejected by the same server ray used for activation.
     * The original air state is restored in a finally block before the real mouse click.
     */
    private static boolean verifyBeaconSightlineOcclusion(ServerLevel level,
            net.minecraft.server.level.ServerPlayer player,BlockPos beacon,int anchorX,int anchorZ) {
        if(level==null || player==null || beacon==null) return false;
        net.minecraft.world.phys.Vec3 eye=player.getEyePosition();
        net.minecraft.world.phys.Vec3 target=net.minecraft.world.phys.Vec3.atCenterOf(beacon);
        if(eye==null || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z))
            return false;
        net.minecraft.world.phys.Vec3 delta=target.subtract(eye);
        // Try several interior points because the first may share the player's or beacon's block cell.
        for(int step=2;step<=8;step++) {
            double fraction=step/10.0D;
            BlockPos blocker=BlockPos.containing(
                eye.x+delta.x*fraction,eye.y+delta.y*fraction,eye.z+delta.z*fraction);
            if(blocker.equals(beacon) || blocker.equals(player.blockPosition())) continue;
            long dx=(long)blocker.getX()-anchorX;
            long dz=(long)blocker.getZ()-anchorZ;
            if(Math.abs(dx)>RADIUS || Math.abs(dz)>RADIUS
                || dx*dx+dz*dz>(long)RADIUS*RADIUS
                || blocker.getY()<level.getMinBuildHeight() || blocker.getY()>=level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(blocker)
                || !level.hasChunk(blocker.getX()>>4,blocker.getZ()>>4)) continue;
            BlockState original=level.getBlockState(blocker);
            if(!original.isAir()) continue;
            if(!level.setBlock(blocker,Blocks.BARRIER.defaultBlockState(),3)) continue;
            boolean blocked;
            try {
                blocked=!hasLoadedBeaconSightline(level,player,beacon);
            } finally {
                if(!level.setBlock(blocker,original,3))
                    throw new IllegalStateException("CI temporary beacon sightline blocker was not restored");
            }
            if(blocked) {
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_OCCLUSION_REJECTED temporaryBarrier=true restored=true loadedChunksOnly=true");
                return true;
            }
        }
        return false;
    }

    /**
     * Pick a real emitted fissure sample that the test player can see from the
     * verified beacon pad. A nearby surface hidden behind a cliff is not a
     * useful camera target, even when its particles were sent successfully.
     */
    private static FissureTarget findVisibleFissureTarget(ServerLevel level,State s,
            net.minecraft.server.level.ServerPlayer viewer) {
        if(viewer==null) return null;
        return findVisibleFissureTarget(level,s,viewer,viewer.getEyePosition());
    }

    private static FissureTarget findVisibleFissureTarget(ServerLevel level,State s,
            net.minecraft.server.level.ServerPlayer viewer,net.minecraft.world.phys.Vec3 eye) {
        if(viewer==null || !viewer.isAlive() || viewer.isRemoved() || eye==null) return null;
        BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
        FissureGroundSample groundSample=new FissureGroundSample();
        FissureTarget best=null;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            double angle=arm*Math.PI/2.0D;
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                double distance=segment*FISSURE_SAMPLE_SPACING;
                double bend=Math.sin(segment*.85D+arm*1.1D)*RiftGeometry.FISSURE_BEND_AMPLITUDE;
                double px=s.x+.5D+Math.cos(angle)*distance-Math.sin(angle)*bend;
                double pz=s.z+.5D+Math.sin(angle)*distance+Math.cos(angle)*bend;
                // Cull samples outside the camera standoff before any chunk or heightmap reads.
                // This is a pure coordinate check and does not request or load a chunk.
                double vx=px-eye.x,vz=pz-eye.z;
                double horizontalSquared=vx*vx+vz*vz;
                if(!isFissureTargetHorizontalDistance(horizontalSquared)) continue;
                int blockX=(int)Math.floor(px),blockZ=(int)Math.floor(pz);
                long dx=(long)blockX-s.x,dz=(long)blockZ-s.z;
                cursor.set(blockX,level.getMinBuildHeight(),blockZ);
                if(dx*dx+dz*dz>(long)RADIUS*RADIUS
                    || !level.getWorldBorder().isWithinBounds(cursor)
                    || !level.hasChunk(blockX>>4,blockZ>>4)) continue;
                if(!findFissureGroundSample(level,blockX,blockZ,cursor,groundSample)
                    || groundSample.surfaceY>=level.getMaxBuildHeight()) continue;
                net.minecraft.world.phys.Vec3 target=new net.minecraft.world.phys.Vec3(
                    px,groundSample.particleY,pz);
                if(!fissureRayChunksLoaded(eye,target,(chunkX,chunkZ)->level.hasChunk(chunkX,chunkZ))) continue;
                double vy=target.y-eye.y;
                // The horizontal range was checked before chunk and heightmap reads above.
                // Keep the vertical bound here because surface height is only known after sampling.
                if(Math.abs(vy)>24.0D) continue;
                var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,target,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,viewer));
                if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS) continue;
                double viewScore=fissureTargetViewScore(horizontalSquared,vy);
                if(!Double.isFinite(viewScore)) continue;
                double distanceSquared=vx*vx+vy*vy+vz*vz;
                if(isBetterFissureTarget(viewScore,
                        best==null?Double.NaN:best.viewScore(),best!=null))
                    best=new FissureTarget(px,target.y,pz,distanceSquared,horizontalSquared,viewScore);
            }
        }
        return best;
    }

    // Run after ordinary explosion modifiers: protect the fixture even when
    // another handler added its position to the affected-block list.
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onRecoveryBeaconExplosion(net.minecraftforge.event.level.ExplosionEvent.Detonate event) {
        if(!(event.getLevel() instanceof ServerLevel level)
            || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)
            || event.getAffectedBlocks().isEmpty()) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!s.started || s.naturalBeaconPos==Long.MIN_VALUE) return;
        // A saved beacon coordinate can outlive the actual beacon (e.g. a
        // different mod replaced the block). Do not shield unrelated blocks
        // that later occupy its old position from explosion damage.
        BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
        if(!event.getAffectedBlocks().contains(beacon)
            || !isOwnedNaturalBeaconAtLoadedPosition(level,s,beacon)) return;
        // Explosion affected blocks are already loaded; this does not request
        // additional chunks or modify terrain.
        if(protectRecoveryBeaconFromExplosion(event.getAffectedBlocks(),s.naturalBeaconPos)
            && CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_EXPLOSION_PROTECTED");
    }

    /**
     * Legacy row-major cursors are persisted as offsets. Reject corrupt values
     * before incrementing them: Integer.MAX_VALUE would wrap and stall the scan.
     */
    private static boolean validLegacyFractureCursor(int cursorX,int cursorZ,int cursorY,
            int minY,int maxY,boolean surfaceFirstScan) {
        if(cursorX< -RADIUS || cursorX>RADIUS+1
            || cursorZ< -RADIUS || cursorZ>RADIUS
            || (cursorX==RADIUS+1 && cursorZ!= -RADIUS)) return false;
        if(surfaceFirstScan && cursorY==Integer.MIN_VALUE) return true;
        return maxY>minY && cursorY>=minY && cursorY<maxY;
    }

    /**
     * A damaged legacy cursor must stop new terrain edits rather than wrap through
     * integer space. Existing snapshots remain intact and can still be restored.
     */
    private static boolean quarantineInvalidLegacyFractureCursor(State s,int minY,int maxY) {
        if(!s.initialized || s.centerOutScan || s.restoring || s.restored || s.finished) return false;
        if(validLegacyFractureCursor(s.cursorX,s.cursorZ,s.cursorY,minY,maxY,s.surfaceFirstScan))
            return false;
        LOGGER.warn("BLACK FREQUENCY: invalid legacy fracture cursor x={}, z={}, y={}; stopping further terrain edits",
            s.cursorX,s.cursorZ,s.cursorY);
        s.cursorX=RADIUS+1;
        s.cursorZ=-RADIUS;
        s.cursorY=minY;
        s.finished=true;
        s.setDirty();
        return true;
    }

    /** Reject overflowing fracture columns before narrowing coordinates to int. */
    private static boolean validFractureColumn(int anchorX,int anchorZ,int offsetX,int offsetZ) {
        long x=(long)anchorX+offsetX,z=(long)anchorZ+offsetZ;
        return x>=Integer.MIN_VALUE && x<=Integer.MAX_VALUE
            && z>=Integer.MIN_VALUE && z<=Integer.MAX_VALUE;
    }

    /** Start a surface-first column at its highest in-range motion-blocking block. */
    private static int surfaceFirstCursor(int surfaceY,int minY,int maxY) {
        if(maxY<=minY) return minY;
        return Math.max(minY,Math.min(maxY-1,surfaceY-1));
    }

    private static void advanceFractureColumn(State s) {
        if(s.centerOutScan) {
            if(s.centerOutIndex<CENTER_OUT_COLUMN_COUNT) s.centerOutIndex++;
        } else {
            s.cursorZ++;
            if(s.cursorZ>RADIUS){s.cursorZ=-RADIUS;s.cursorX++;}
        }
    }

    private static void verifyCenterOutColumnOrder() {
        int side=RADIUS*2+1;
        boolean[] seen=new boolean[side*side];
        int previousRing=-1;
        for(int i=0;i<CENTER_OUT_COLUMN_COUNT;i++) {
            int dx=CENTER_OUT_DX[i],dz=CENTER_OUT_DZ[i];
            int ring=Math.max(Math.abs(dx),Math.abs(dz));
            if(dx*dx+dz*dz>RADIUS*RADIUS || ring<previousRing)
                throw new IllegalStateException("CI center-out fracture order escaped radius or reversed");
            int slot=(dx+RADIUS)*side+(dz+RADIUS);
            if(seen[slot]) throw new IllegalStateException("CI center-out fracture order duplicated a column");
            seen[slot]=true;
            if(i==0 && (dx!=0 || dz!=0))
                throw new IllegalStateException("CI center-out fracture did not start at the anchor");
            previousRing=ring;
        }
        int expected=0;
        for(int dx=-RADIUS;dx<=RADIUS;dx++)
            for(int dz=-RADIUS;dz<=RADIUS;dz++)
                if(dx*dx+dz*dz<=RADIUS*RADIUS) expected++;
        if(CENTER_OUT_COLUMN_COUNT!=expected)
            throw new IllegalStateException("CI center-out fracture omitted columns: "
                +CENTER_OUT_COLUMN_COUNT+"/"+expected);
        LOGGER.info("BLACK_FREQUENCY_TEST CENTER_OUT_FRACTURE_COLUMN_ORDER_VERIFIED columns={}",
            CENTER_OUT_COLUMN_COUNT);
    }

    // Emit a small ground-level cue only when an actual top-surface block is removed.
    // The edit cadence and per-tick guard keep particle traffic bounded.
    private static boolean shouldEmitSurfaceFractureCue(int y,int surfaceY,int edits,boolean sentThisTick,int cooldownTicks) {
        return !sentThisTick && cooldownTicks<=0 && surfaceY>Integer.MIN_VALUE
            && (edits&3)==0 && y==surfaceY-1;
    }

    private static boolean withinSurfaceFractureParticleAudience(double distanceSquared) {
        return Double.isFinite(distanceSquared)
            && distanceSquared>=0.0D
            && distanceSquared<SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR;
    }

    private static void verifySurfaceFractureParticleAudienceBounds() {
        boolean origin=withinSurfaceFractureParticleAudience(0.0D);
        boolean justInside=withinSurfaceFractureParticleAudience(
            Math.nextDown(SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR));
        boolean boundaryRejected=!withinSurfaceFractureParticleAudience(
            SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR);
        boolean outsideRejected=!withinSurfaceFractureParticleAudience(
            Math.nextUp(SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR));
        boolean invalidRejected=!withinSurfaceFractureParticleAudience(-1.0D)
            && !withinSurfaceFractureParticleAudience(Double.NaN)
            && !withinSurfaceFractureParticleAudience(Double.POSITIVE_INFINITY)
            && !withinSurfaceFractureParticleAudience(Double.NEGATIVE_INFINITY);
        if(!(origin && justInside && boundaryRejected && outsideRejected && invalidRejected))
            throw new IllegalStateException("CI surface fracture particle audience range regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FRACTURE_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance={} maxDistanceSquared={} strictBoundary=true invalidRejected=true",
            IDLE_BEACON_PARTICLE_PACKET_RANGE, SURFACE_FRACTURE_PARTICLE_PACKET_RANGE_SQR);
    }

    private static boolean hasNearbySurfaceFractureViewer(ServerLevel level,BlockPos pos) {
        for(var player:level.players()) {
            if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
            if(withinSurfaceFractureParticleAudience(player.distanceToSqr(
                pos.getX()+.5D,pos.getY()+.5D,pos.getZ()+.5D))) return true;
        }
        return false;
    }

    private static void verifyFissureSampleCache() {
        if(FISSURE_SAMPLES.length!=FISSURE_ARM_COUNT*FISSURE_SEGMENTS_PER_ARM)
            throw new IllegalStateException("CI fissure sample cache size regression");
        double[] phases={0.0D,.125D,Math.PI,-4.0D*Math.PI,1000.0D,
            Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            double angle=arm*Math.PI/2.0D;
            double sinAngle=Math.sin(angle);
            double cosAngle=Math.cos(angle);
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                FissureSample sample=FISSURE_SAMPLES[arm*FISSURE_SEGMENTS_PER_ARM+segment-1];
                double distance=segment*FISSURE_SAMPLE_SPACING;
                double bend=Math.sin(segment*.85D+arm*1.1D)*RiftGeometry.FISSURE_BEND_AMPLITUDE;
                if(Double.doubleToLongBits(sample.forwardX())!=Double.doubleToLongBits(cosAngle*distance)
                    || Double.doubleToLongBits(sample.bendX())!=Double.doubleToLongBits(sinAngle*bend)
                    || Double.doubleToLongBits(sample.forwardZ())!=Double.doubleToLongBits(sinAngle*distance)
                    || Double.doubleToLongBits(sample.bendZ())!=Double.doubleToLongBits(cosAngle*bend)
                    || Double.doubleToLongBits(sample.segmentPhase())!=Double.doubleToLongBits(segment*.8D)
                    || Double.doubleToLongBits(sample.armPhase())!=Double.doubleToLongBits(arm*.6D)
                    || Math.abs(sample.bendX())>RiftGeometry.FISSURE_BEND_AMPLITUDE
                    || Math.abs(sample.bendZ())>RiftGeometry.FISSURE_BEND_AMPLITUDE)
                    throw new IllegalStateException("CI fissure sample geometry cache changed the legacy profile");
                double offsetX=sample.forwardX()-sample.bendX();
                double offsetZ=sample.forwardZ()+sample.bendZ();
                if(!Double.isFinite(offsetX) || !Double.isFinite(offsetZ)
                    || offsetX*offsetX+offsetZ*offsetZ>=(double)RADIUS*RADIUS)
                    throw new IllegalStateException("CI fissure sample escaped the bounded rift radius");
                for(double phase:phases) {
                    if(Double.doubleToLongBits(fissurePulseWave(phase,sample))
                        !=Double.doubleToLongBits(fissurePulseWave(phase,segment,arm)))
                        throw new IllegalStateException("CI fissure sample pulse cache changed the legacy profile");
                }
            }
        }
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_SAMPLE_CACHE_VERIFIED samples={} geometryBitExact=true pulseBitExact=true geometryTrigPerEmission=0 dynamicPulseSin={} radius={}",
            FISSURE_SAMPLES.length,FISSURE_ARM_COUNT*FISSURE_SEGMENTS_PER_ARM,RADIUS);
    }

    private static void verifyFissureSamplingDensity() {
        int samples=FISSURE_ARM_COUNT*FISSURE_SEGMENTS_PER_ARM;
        double extent=FISSURE_SEGMENTS_PER_ARM*FISSURE_SAMPLE_SPACING;
        if(FISSURE_ARM_COUNT!=4 || samples!=48 || samples*FISSURE_MOTES_PER_SAMPLE!=48
            || FISSURE_MOTES_PER_SAMPLE<1 || !Double.isFinite(FISSURE_SAMPLE_SPACING)
            || !(Math.abs(FISSURE_SAMPLE_SPACING-.45D)<1.0E-9D
                || Math.abs(FISSURE_SAMPLE_SPACING-.60D)<1.0E-9D)
            || !Double.isFinite(RiftGeometry.FISSURE_BEND_AMPLITUDE)
            || Math.abs(RiftGeometry.FISSURE_BEND_AMPLITUDE-.24D)>1.0E-9D
            || !(Math.abs(extent-5.40D)<1.0E-9D || Math.abs(extent-7.20D)<1.0E-9D)
            || extent+RiftGeometry.FISSURE_BEND_AMPLITUDE>=RADIUS
            || FISSURE_VIOLET_EDGE_DUST==null
            || !isOutermostValidFissureSample(1,0)
            || !isOutermostValidFissureSample(11,8)
            || isOutermostValidFissureSample(8,11)
            || isOutermostValidFissureSample(0,0)
            || isOutermostValidFissureSample(FISSURE_SEGMENTS_PER_ARM+1,0))
            throw new IllegalStateException("CI fissure sampling density/outer-tip fallback regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_OUTER_TIP_FALLBACK_VERIFIED policy=furthest-valid-sample");
        verifyFissureSampleCache();
        int edgeDustCount=0;
        int accentDustMotes=0;
        int violetDustMotes=0;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            int edgeCandidateSegment=0;
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                if(isOutermostValidFissureSample(segment,edgeCandidateSegment))
                    edgeCandidateSegment=segment;
                if(isAccentFissureDustSegment(segment)) {
                    accentDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                } else {
                    violetDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                }
            }
            if(edgeCandidateSegment>0) edgeDustCount++;
        }
        int primaryMotes=samples*FISSURE_MOTES_PER_SAMPLE;
        int totalParticles=primaryMotes+edgeDustCount;
        if(edgeDustCount!=FISSURE_ARM_COUNT || accentDustMotes!=8 || violetDustMotes!=40
            || totalParticles!=52 || totalParticles>52)
            throw new IllegalStateException("CI violet-majority fissure particle budget regression");
        Vector3f dustColor=FISSURE_VIOLET_DUST.getColor();
        float dustScale=FISSURE_VIOLET_DUST.getScale();
        if(!Float.isFinite(dustColor.x()) || !Float.isFinite(dustColor.y())
            || !Float.isFinite(dustColor.z()) || dustColor.x()<0.0F || dustColor.x()>1.0F
            || dustColor.y()<0.0F || dustColor.y()>1.0F || dustColor.z()<0.0F
            || dustColor.z()>1.0F || dustColor.z()<=dustColor.x()*1.8F
            || dustColor.y()>0.12F || !Float.isFinite(dustScale)
            || dustScale<.8F || dustScale>.9F)
            throw new IllegalStateException("CI fissure violet dust color/scale regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_VIOLET_DUST_VERIFIED red={} green={} blue={} scale={}",
            dustColor.x(),dustColor.y(),dustColor.z(),dustScale);
        Vector3f accentColor=SURFACE_FISSURE_ACCENT_DUST.getColor();
        float accentScale=SURFACE_FISSURE_ACCENT_DUST.getScale();
        if(!Float.isFinite(accentColor.x()) || !Float.isFinite(accentColor.y())
            || !Float.isFinite(accentColor.z())
            || Math.abs(accentColor.x()-.30F)>1.0E-6F
            || Math.abs(accentColor.y()-.14F)>1.0E-6F
            || Math.abs(accentColor.z()-.88F)>1.0E-6F
            || !Float.isFinite(accentScale) || Math.abs(accentScale-.85F)>1.0E-6F
            || SURFACE_FISSURE_ACCENT_DUST==FISSURE_VIOLET_ACCENT_DUST
            || accentScale>=FISSURE_VIOLET_ACCENT_DUST.getScale()
            || accentColor.z()<=accentColor.x()*2.0F)
            throw new IllegalStateException("CI fissure violet accent Dust color/scale regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_ACCENT_DUST_VERIFIED red={} green={} blue={} scale={}",
            accentColor.x(),accentColor.y(),accentColor.z(),accentScale);
        Vector3f edgeColor=FISSURE_VIOLET_EDGE_DUST.getColor();
        float edgeScale=FISSURE_VIOLET_EDGE_DUST.getScale();
        if(!hasDustColor(FISSURE_VIOLET_EDGE_DUST,.42F,.18F,.92F)
            || !Float.isFinite(edgeScale) || Math.abs(edgeScale-.70F)>1.0E-6F
            || edgeScale>=.75F)
            throw new IllegalStateException("CI compact fissure edge Dust color/scale regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_VERIFIED red={} green={} blue={} scale={}",
            edgeColor.x(),edgeColor.y(),edgeColor.z(),edgeScale);
        verifyFissureEdgeDustPulseProfile();
        verifyFissureDustPulseProfile();
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_DENSITY_VERIFIED samples={} spacing={} extent={} bend={}",
            samples,FISSURE_SAMPLE_SPACING,extent,RiftGeometry.FISSURE_BEND_AMPLITUDE);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_BEND_PROFILE_VERIFIED amplitude={} maxOffset={} shared=true",
            RiftGeometry.FISSURE_BEND_AMPLITUDE,RiftGeometry.FISSURE_BEND_AMPLITUDE);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLE_BUDGET_VERIFIED total={} accentDust={} violetDust={} edgeDust={}",
            totalParticles,accentDustMotes,violetDustMotes,edgeDustCount);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_LAYOUT_VERIFIED count={} color=violet perArm=1 outerTip=true",
            edgeDustCount);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_CACHE_VERIFIED cached={} scale={}",
            FISSURE_VIOLET_EDGE_DUST!=null,FISSURE_VIOLET_EDGE_DUST.getScale());
    }

    private static void verifySurfaceFractureCue() {
        if(!shouldEmitSurfaceFractureCue(63,64,0,false,0)
            || shouldEmitSurfaceFractureCue(62,64,0,false,0)
            || shouldEmitSurfaceFractureCue(63,64,1,false,0)
            || shouldEmitSurfaceFractureCue(63,64,4,true,0)
            || shouldEmitSurfaceFractureCue(63,64,4,false,1)
            || !shouldEmitSurfaceFractureCue(63,64,4,false,0))
            throw new IllegalStateException("CI surface fracture cue regression");
        if(isFissureGroundSupport(Blocks.OAK_LOG.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_LEAVES.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_PLANKS.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_STAIRS.defaultBlockState())
            || isFissureGroundSupport(Blocks.COBBLESTONE_SLAB.defaultBlockState())
            || isFissureGroundSupport(Blocks.COBBLESTONE_WALL.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_FENCE.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_FENCE_GATE.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_DOOR.defaultBlockState())
            || isFissureGroundSupport(Blocks.OAK_TRAPDOOR.defaultBlockState())
            || isFissureGroundSupport(Blocks.CHEST.defaultBlockState())
            || isFissureGroundSupport(Blocks.FURNACE.defaultBlockState())
            || isFissureGroundSupport(Blocks.BEACON.defaultBlockState())
            || isFissureSurfaceAvailable(Blocks.CHEST.defaultBlockState())
            || isFissureSurfaceAvailable(Blocks.FURNACE.defaultBlockState())
            || isFissureSurfaceAvailable(Blocks.BEACON.defaultBlockState())
            || !isFissureSurfaceAvailable(Blocks.AIR.defaultBlockState())
            || isFissureSurfaceAvailable(Blocks.STONE.defaultBlockState())
            || isFissureSurfaceAvailable(Blocks.WATER.defaultBlockState())
            || !isFissureGroundSupport(Blocks.STONE.defaultBlockState())
            || !isFissureGroundSupport(Blocks.DIRT.defaultBlockState()))
            throw new IllegalStateException("CI fissure terrain/structure filter regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_STRUCTURAL_SUPPORT_FILTER_VERIFIED");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_BLOCK_ENTITY_FILTER_VERIFIED support=true surface=true");
        verifyFissureSurfaceParticleHeight();
        verifyFissureSamplingDensity();
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_GROUND_FILTER_VERIFIED");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FRACTURE_CUE_RULE_VERIFIED");
    }

    private static boolean initializeLegacyAnchorFromSpawn(State s,BlockPos spawn) {
        if(!s.anchorCoordinatesNeedInitialization || spawn==null) return false;
        s.x=spawn.getX();
        s.z=spawn.getZ();
        s.anchorCoordinatesNeedInitialization=false;
        s.setDirty();
        return true;
    }

    private static void verifySurfaceFirstScanMigration() {
        // This pure NBT regression runs with every scan-mode startup, not only recovery.
        verifyMalformedInitializedFlagQuarantine();
        verifyMalformedStartedFlagQuarantine();
        verifyMalformedCompletionFlagsQuarantine();
        if(fractureEditBudget(false)!=96 || fractureEditBudget(true)!=160
            || EDIT_BUDGET!=fractureEditBudget(CI_TEST))
            throw new IllegalStateException("CI fracture edit budget selection regression");
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_EDIT_BUDGET_VERIFIED production=96 ci=160 active={}",EDIT_BUDGET);
        int[][] samples={
            {64,-64,320,63},
            {-64,-64,320,-64},
            {320,-64,320,319},
            {900,-64,320,319}
        };
        for(int[] sample:samples) {
            int actual=surfaceFirstCursor(sample[0],sample[1],sample[2]);
            if(actual!=sample[3])
                throw new IllegalStateException("CI surface-first cursor regression: "+actual);
        }
        // A save written before surfaceFirstScan existed must retain its exact
        // bottom-up cursor. New saves must round-trip the sentinel and scan mode.
        CompoundTag legacyTag=new CompoundTag();
        legacyTag.putBoolean("initialized",true);
        legacyTag.putInt("cy",12);
        State legacy=State.load(legacyTag);
        if(legacy.surfaceFirstScan || legacy.centerOutScan || legacy.cursorY!=12 || legacy.isDirty()
            || !legacy.anchorCoordinatesNeedInitialization)
            throw new IllegalStateException("CI legacy fracture cursor migration regression");
        BlockPos legacySpawn=new BlockPos(123,64,-456);
        if(!initializeLegacyAnchorFromSpawn(legacy,legacySpawn)
            || legacy.x!=123 || legacy.z!=-456 || legacy.cursorY!=12
            || legacy.surfaceFirstScan || legacy.centerOutScan
            || legacy.anchorCoordinatesNeedInitialization || !legacy.isDirty())
            throw new IllegalStateException("CI legacy cursor spawn-anchor initialization regression");
        LOGGER.info("BLACK_FREQUENCY_TEST LEGACY_ANCHORLESS_CURSOR_REBOUND_VERIFIED spawnX=123 spawnZ=-456 cursorPreserved=true noOriginFallback=true");

        // The intermediate surface-first format predates center-out metadata and remains valid.
        CompoundTag intermediateTag=new CompoundTag();
        intermediateTag.putBoolean("initialized",true);
        intermediateTag.putBoolean("surfaceFirstScan",true);
        intermediateTag.putInt("cy",12);
        State intermediate=State.load(intermediateTag);
        if(!intermediate.surfaceFirstScan || intermediate.centerOutScan
            || intermediate.cursorY!=12 || intermediate.isDirty())
            throw new IllegalStateException("CI intermediate surface-first cursor migration regression");

        // A current center-out save must contain all three companion fields.
        // Missing one must not silently resume with a different traversal order.
        CompoundTag missingCenterOutMode=new CompoundTag();
        missingCenterOutMode.putBoolean("initialized",true);
        missingCenterOutMode.putBoolean("surfaceFirstScan",true);
        missingCenterOutMode.putInt("centerOutIndex",7);
        State quarantinedMissingCenterOutMode=State.load(missingCenterOutMode);

        CompoundTag missingSurfaceFirstMode=new CompoundTag();
        missingSurfaceFirstMode.putBoolean("initialized",true);
        missingSurfaceFirstMode.putBoolean("centerOutScan",true);
        missingSurfaceFirstMode.putInt("centerOutIndex",7);
        State quarantinedMissingSurfaceFirstMode=State.load(missingSurfaceFirstMode);

        CompoundTag missingCenterOutIndexMode=new CompoundTag();
        missingCenterOutIndexMode.putBoolean("initialized",true);
        missingCenterOutIndexMode.putBoolean("surfaceFirstScan",true);
        missingCenterOutIndexMode.putBoolean("centerOutScan",false);
        State quarantinedMissingCenterOutIndexMode=State.load(missingCenterOutIndexMode);

        CompoundTag malformedSurfaceMode=new CompoundTag();
        malformedSurfaceMode.putBoolean("initialized",true);
        malformedSurfaceMode.putBoolean("centerOutScan",true);
        malformedSurfaceMode.putInt("centerOutIndex",7);
        malformedSurfaceMode.putString("surfaceFirstScan","true");
        State quarantinedSurfaceMode=State.load(malformedSurfaceMode);
        CompoundTag normalizedSurfaceMode=quarantinedSurfaceMode.save(new CompoundTag());
        State reloadedSurfaceMode=State.load(normalizedSurfaceMode);

        CompoundTag malformedCenterOutMode=new CompoundTag();
        malformedCenterOutMode.putBoolean("initialized",true);
        malformedCenterOutMode.putBoolean("surfaceFirstScan",true);
        malformedCenterOutMode.putString("centerOutScan","true");
        State quarantinedCenterOutMode=State.load(malformedCenterOutMode);
        CompoundTag normalizedCenterOutMode=quarantinedCenterOutMode.save(new CompoundTag());

        ListTag recoveringSnapshots=new ListTag();
        CompoundTag recoveringSnapshot=new CompoundTag();
        recoveringSnapshot.putLong("pos",new BlockPos(0,64,0).asLong());
        recoveringSnapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        recoveringSnapshots.add(recoveringSnapshot);
        CompoundTag malformedModeDuringRecovery=new CompoundTag();
        malformedModeDuringRecovery.putBoolean("initialized",true);
        malformedModeDuringRecovery.putBoolean("restoring",true);
        malformedModeDuringRecovery.putString("surfaceFirstScan","true");
        malformedModeDuringRecovery.putInt("edits",1);
        malformedModeDuringRecovery.putInt("restoreIndex",0);
        malformedModeDuringRecovery.putInt("restoreScan",0);
        malformedModeDuringRecovery.put("snapshots",recoveringSnapshots);
        State recoveryWithMalformedMode=State.load(malformedModeDuringRecovery);

        if(!quarantinedMissingCenterOutMode.finished || !quarantinedMissingCenterOutMode.isDirty()
            || !quarantinedMissingSurfaceFirstMode.finished || !quarantinedMissingSurfaceFirstMode.isDirty()
            || !quarantinedMissingCenterOutIndexMode.finished || !quarantinedMissingCenterOutIndexMode.isDirty()
            || !quarantinedSurfaceMode.finished || !quarantinedSurfaceMode.isDirty()
            || !normalizedSurfaceMode.contains("surfaceFirstScan",Tag.TAG_BYTE)
            || !normalizedSurfaceMode.contains("centerOutScan",Tag.TAG_BYTE)
            || !reloadedSurfaceMode.finished
            || !quarantinedCenterOutMode.finished || !quarantinedCenterOutMode.isDirty()
            || !normalizedCenterOutMode.contains("centerOutScan",Tag.TAG_BYTE)
            || recoveryWithMalformedMode.finished || !recoveryWithMalformedMode.restoring
            || !recoveryWithMalformedMode.isDirty())
            throw new IllegalStateException("CI malformed fracture scan mode quarantine regression");
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_SCAN_MODE_TAG_QUARANTINE_VERIFIED missingLegacyAccepted=true malformedSurfaceFirstQuarantined=true malformedCenterOutQuarantined=true restorationPreserved=true roundTrip=true");
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_SCAN_MODE_MISSING_COMPANION_QUARANTINE_VERIFIED missingCenterOut=true missingSurfaceFirst=true missingCenterOutIndex=true legacyIntermediatePreserved=true");
        if(!validLegacyFractureCursor(-RADIUS,-RADIUS,-64,-64,320,false)
            || !validLegacyFractureCursor(RADIUS,RADIUS,319,-64,320,false)
            || !validLegacyFractureCursor(RADIUS+1,-RADIUS,-64,-64,320,false)
            || !validLegacyFractureCursor(0,0,Integer.MIN_VALUE,-64,320,true)
            || validLegacyFractureCursor(0,Integer.MAX_VALUE,12,-64,320,false)
            || validLegacyFractureCursor(Integer.MIN_VALUE,0,12,-64,320,false)
            || validLegacyFractureCursor(0,0,Integer.MAX_VALUE,-64,320,false)
            || validLegacyFractureCursor(RADIUS+1,0,12,-64,320,false)
            || validLegacyFractureCursor(0,0,Integer.MIN_VALUE,-64,320,false))
            throw new IllegalStateException("CI legacy fracture cursor bounds regression");
        State corruptLegacyCursor=new State();
        corruptLegacyCursor.initialized=true;
        corruptLegacyCursor.cursorZ=Integer.MAX_VALUE;
        if(!quarantineInvalidLegacyFractureCursor(corruptLegacyCursor,-64,320)
            || !corruptLegacyCursor.finished
            || corruptLegacyCursor.cursorX!=RADIUS+1
            || corruptLegacyCursor.cursorZ!=-RADIUS)
            throw new IllegalStateException("CI corrupt legacy fracture cursor was not quarantined");
        State corruptLegacyY=new State();
        corruptLegacyY.initialized=true;
        corruptLegacyY.cursorY=Integer.MAX_VALUE;
        if(!quarantineInvalidLegacyFractureCursor(corruptLegacyY,-64,320)
            || !corruptLegacyY.finished)
            throw new IllegalStateException("CI corrupt legacy fracture height cursor was not quarantined");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_LEGACY_FRACTURE_CURSOR_QUARANTINED");
        CompoundTag malformedLegacyCursorBase=new CompoundTag();
        malformedLegacyCursorBase.putInt("x",123);
        malformedLegacyCursorBase.putInt("z",-456);
        malformedLegacyCursorBase.putBoolean("initialized",true);
        malformedLegacyCursorBase.putBoolean("started",true);
        malformedLegacyCursorBase.putBoolean("surfaceFirstScan",true);
        malformedLegacyCursorBase.putInt("cx",0);
        malformedLegacyCursorBase.putInt("cz",0);
        malformedLegacyCursorBase.putInt("cy",12);
        malformedLegacyCursorBase.putInt("edits",1);
        CompoundTag retainedCursorSnapshot=new CompoundTag();
        retainedCursorSnapshot.putLong("pos",new BlockPos(124,64,-456).asLong());
        retainedCursorSnapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag retainedCursorSnapshots=new ListTag();
        retainedCursorSnapshots.add(retainedCursorSnapshot);
        malformedLegacyCursorBase.put("snapshots",retainedCursorSnapshots);
        for(String cursorKey:new String[]{"cx","cz","cy"}) {
            CompoundTag malformedCursorTag=malformedLegacyCursorBase.copy();
            malformedCursorTag.putString(cursorKey,"not-an-int");
            State quarantinedTag=State.load(malformedCursorTag);
            if(!quarantinedTag.finished || !quarantinedTag.isDirty()
                || quarantinedTag.cursorX!=RADIUS+1 || quarantinedTag.cursorZ!=-RADIUS
                || quarantinedTag.snapshots.size()!=1)
                throw new IllegalStateException("CI malformed legacy cursor tag type was not quarantined: "+cursorKey);
        }
        CompoundTag malformedCursorDuringRecovery=malformedLegacyCursorBase.copy();
        malformedCursorDuringRecovery.putBoolean("restoring",true);
        malformedCursorDuringRecovery.putString("cx","not-an-int");
        State recoveryCursorPreserved=State.load(malformedCursorDuringRecovery);
        if(!recoveryCursorPreserved.restoring || recoveryCursorPreserved.finished
            || recoveryCursorPreserved.snapshots.size()!=1)
            throw new IllegalStateException("CI malformed legacy cursor tag interrupted active restoration");
        LOGGER.info("BLACK_FREQUENCY_TEST LEGACY_FRACTURE_CURSOR_TAG_TYPE_QUARANTINE_VERIFIED fields=3 activeScanStopped=true snapshotsPreserved=true restoringPreserved=true");
        CompoundTag malformedRestoringLegacy=malformedLegacyCursorBase.copy();
        malformedRestoringLegacy.putString("restoring","not-a-byte");
        State quarantinedLegacyRestoring=State.load(malformedRestoringLegacy);
        if(quarantinedLegacyRestoring.restoring || !quarantinedLegacyRestoring.finished
            || quarantinedLegacyRestoring.cursorX!=RADIUS+1
            || quarantinedLegacyRestoring.cursorZ!=-RADIUS
            || quarantinedLegacyRestoring.cursorY!=Integer.MIN_VALUE
            || quarantinedLegacyRestoring.snapshots.size()!=1 || !quarantinedLegacyRestoring.isDirty())
            throw new IllegalStateException("CI malformed restoring flag resumed legacy fracture scanning");
        CompoundTag malformedRestoringCenterOut=malformedLegacyCursorBase.copy();
        malformedRestoringCenterOut.putBoolean("centerOutScan",true);
        malformedRestoringCenterOut.putInt("centerOutIndex",7);
        malformedRestoringCenterOut.putString("restoring","not-a-byte");
        State quarantinedCenterOutRestoring=State.load(malformedRestoringCenterOut);
        if(quarantinedCenterOutRestoring.restoring || !quarantinedCenterOutRestoring.finished
            || quarantinedCenterOutRestoring.centerOutIndex!=CENTER_OUT_COLUMN_COUNT
            || quarantinedCenterOutRestoring.snapshots.size()!=1)
            throw new IllegalStateException("CI malformed restoring flag resumed center-out fracture scanning");
        State roundTrippedRestoringQuarantine=State.load(quarantinedCenterOutRestoring.save(new CompoundTag()));
        if(roundTrippedRestoringQuarantine.restoring || !roundTrippedRestoringQuarantine.finished
            || roundTrippedRestoringQuarantine.centerOutIndex!=CENTER_OUT_COLUMN_COUNT
            || roundTrippedRestoringQuarantine.snapshots.size()!=1)
            throw new IllegalStateException("CI malformed restoring flag quarantine was not persistent");
        LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_RESTORING_FLAG_QUARANTINE_VERIFIED scanModes=legacy,centerOut activeScanStopped=true snapshotsPreserved=true terminalCursor=true roundTrip=true");
        State modern=new State();
        modern.surfaceFirstScan=true;
        modern.centerOutScan=true;
        modern.centerOutIndex=3;
        modern.cursorY=Integer.MIN_VALUE;
        State modernReloaded=State.load(modern.save(new CompoundTag()));
        if(!modernReloaded.surfaceFirstScan || !modernReloaded.centerOutScan
            || modernReloaded.centerOutIndex!=3 || modernReloaded.cursorY!=Integer.MIN_VALUE)
            throw new IllegalStateException("CI center-out surface-first cursor persistence regression");
        CompoundTag corruptCenterOut=new CompoundTag();
        corruptCenterOut.putBoolean("initialized",true);
        corruptCenterOut.putBoolean("started",true);
        corruptCenterOut.putBoolean("surfaceFirstScan",true);
        corruptCenterOut.putBoolean("centerOutScan",true);
        corruptCenterOut.putInt("centerOutIndex",Integer.MAX_VALUE);
        State quarantined=State.load(corruptCenterOut);
        if(!quarantined.finished || quarantined.centerOutIndex!=CENTER_OUT_COLUMN_COUNT)
            throw new IllegalStateException("CI corrupt center-out cursor was not quarantined");

        // Also cover a truncated modern save whose invalid cursor is discovered after
        // missing companion metadata has already marked the state finished.
        CompoundTag corruptCenterOutWithMissingCompanion=new CompoundTag();
        corruptCenterOutWithMissingCompanion.putBoolean("initialized",true);
        corruptCenterOutWithMissingCompanion.putBoolean("started",true);
        corruptCenterOutWithMissingCompanion.putBoolean("centerOutScan",true);
        corruptCenterOutWithMissingCompanion.putInt("centerOutIndex",Integer.MAX_VALUE);
        State quarantinedWithMissingCompanion=State.load(corruptCenterOutWithMissingCompanion);
        if(!quarantinedWithMissingCompanion.finished
            || quarantinedWithMissingCompanion.centerOutIndex!=CENTER_OUT_COLUMN_COUNT)
            throw new IllegalStateException("CI terminal center-out sentinel was lost after metadata quarantine");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_CENTER_OUT_CURSOR_QUARANTINED");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_CENTER_OUT_CURSOR_TERMINAL_SENTINEL_VERIFIED isolated=true combinedMissingCompanion=true");
        verifyCenterOutColumnOrder();
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_SCAN_ORDER_MIGRATION_VERIFIED");
        verifySurfaceFractureCue();
    }

    private static void verifyFractureColumnBounds() {
        if(!validFractureColumn(0,0,RADIUS,-RADIUS)
            || !validFractureColumn(Integer.MAX_VALUE, Integer.MIN_VALUE,0,0)
            || validFractureColumn(Integer.MAX_VALUE,0,1,0)
            || validFractureColumn(Integer.MIN_VALUE,0,-1,0)
            || validFractureColumn(0,Integer.MAX_VALUE,0,1)
            || validFractureColumn(0,Integer.MIN_VALUE,0,-1)
            || !validFractureColumn(Integer.MAX_VALUE,0,-RADIUS,0)
            || !validFractureColumn(0,Integer.MIN_VALUE,0,RADIUS))
            throw new IllegalStateException("CI fracture column overflow regression");
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_COLUMN_BOUNDS_VERIFIED");
    }

    /** Verify that the transient retry set is cleared and reused between recovery ticks. */
    private static void verifyRestoreFailureCacheReuse() {
        State state=new State();
        LongOpenHashSet first=beginRestoreTick(state);
        long packedPositionKey=0x7fff0000ffffL;
        first.add(packedPositionKey);
        LongOpenHashSet second=beginRestoreTick(state);
        if(first!=second || !second.isEmpty())
            throw new IllegalStateException("CI restore failure cache reuse regression");
        second.add(packedPositionKey);
        if(!first.contains(packedPositionKey))
            throw new IllegalStateException("CI restore failure primitive-key lookup regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_FAILURE_CACHE_REUSED primitiveKeys=true transient=true clearedBetweenTicks=true");
    }

    /** Verify malformed snapshot containers are quarantined without dirtying legacy saves. */
    private static void verifySnapshotListContainerQuarantine() {
        CompoundTag wrongOuterType=new CompoundTag();
        wrongOuterType.putString("snapshots","not-a-list");
        State outerTypeLoaded=State.load(wrongOuterType);
        CompoundTag outerTypeSaved=outerTypeLoaded.save(new CompoundTag());

        ListTag wrongElementType=new ListTag();
        wrongElementType.add(net.minecraft.nbt.StringTag.valueOf("not-a-compound"));
        CompoundTag heterogeneous=new CompoundTag();
        heterogeneous.put("snapshots",wrongElementType);
        State elementTypeLoaded=State.load(heterogeneous);
        CompoundTag elementTypeSaved=elementTypeLoaded.save(new CompoundTag());

        State legacyWithoutSnapshots=State.load(new CompoundTag());

        // A corrupt container in an active damaged world is not a successful
        // empty recovery: preserve an explicit partial result across another save.
        CompoundTag activeOuterType=new CompoundTag();
        activeOuterType.putBoolean("restoring",true);
        activeOuterType.putInt("edits",160);
        activeOuterType.putInt("restoreIndex",159);
        activeOuterType.putInt("restoreScan",159);
        activeOuterType.putString("snapshots","not-a-list");
        State activeOuterLoaded=State.load(activeOuterType);
        State activeOuterReloaded=State.load(activeOuterLoaded.save(new CompoundTag()));

        // Once the one-shot warning has already fired, a later corrupt container
        // must still persist its newly inferred missing-snapshot count.
        CompoundTag previouslyWarnedType=new CompoundTag();
        previouslyWarnedType.putBoolean("restoring",true);
        previouslyWarnedType.putInt("edits",160);
        // Keep the cursor valid for an empty quarantined list, so no cursor repair
        // can set the dirty flag and mask a missing snapshot-counter persistence call.
        previouslyWarnedType.putInt("restoreIndex",-1);
        previouslyWarnedType.putInt("restoreScan",-1);
        previouslyWarnedType.putInt("skippedInvalid",4);
        previouslyWarnedType.putBoolean("invalidSnapshotWarningLogged",true);
        previouslyWarnedType.putString("snapshots","not-a-list");
        State previouslyWarnedLoaded=State.load(previouslyWarnedType);
        State previouslyWarnedReloaded=State.load(previouslyWarnedLoaded.save(new CompoundTag()));

        CompoundTag activeElementType=new CompoundTag();
        activeElementType.putBoolean("restoring",true);
        activeElementType.putInt("edits",160);
        activeElementType.putInt("restoreIndex",159);
        activeElementType.putInt("restoreScan",159);
        activeElementType.put("snapshots",wrongElementType.copy());
        State activeElementLoaded=State.load(activeElementType);
        Component partial=restorationCompletionMessage(
            activeOuterLoaded.restoredBlocks,activeOuterLoaded.skippedOccupied,activeOuterLoaded.skippedInvalid,
            activeOuterLoaded.snapshotOverflowEntries,activeOuterLoaded.snapshotMissingEntries);

        if(!outerTypeLoaded.isDirty() || !outerTypeLoaded.snapshots.isEmpty()
            || !outerTypeSaved.contains("snapshots",Tag.TAG_LIST)
            || !outerTypeSaved.getList("snapshots",Tag.TAG_COMPOUND).isEmpty()
            || !elementTypeLoaded.isDirty() || !elementTypeLoaded.snapshots.isEmpty()
            || !elementTypeSaved.contains("snapshots",Tag.TAG_LIST)
            || !elementTypeSaved.getList("snapshots",Tag.TAG_COMPOUND).isEmpty()
            || legacyWithoutSnapshots.isDirty()
            || activeOuterLoaded.skippedInvalid!=0 || activeElementLoaded.skippedInvalid!=0
            || activeOuterLoaded.snapshotMissingEntries!=160 || activeElementLoaded.snapshotMissingEntries!=160
            || activeOuterLoaded.restoreIndex!=-1 || activeOuterLoaded.restoreScan!=-1
            || activeOuterReloaded.skippedInvalid!=0 || activeOuterReloaded.snapshotMissingEntries!=160
            || !activeOuterReloaded.restoring
            || !previouslyWarnedLoaded.isDirty() || previouslyWarnedLoaded.skippedInvalid!=4
            || previouslyWarnedLoaded.snapshotMissingEntries!=160
            || !previouslyWarnedLoaded.invalidSnapshotWarningLogged
            || previouslyWarnedReloaded.skippedInvalid!=4
            || previouslyWarnedReloaded.snapshotMissingEntries!=160 || !previouslyWarnedReloaded.restoring
            || !partial.getString().contains("ВОССТАНОВЛЕНИЕ НЕПОЛНО")
            || !partial.getString().contains("пропущено 160"))
            throw new IllegalStateException("CI malformed recovery snapshot list quarantine/partial-status regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_CORRUPTION_PARTIAL_STATUS_VERIFIED activeRecovery=true invalidContainers=2 restored=0 partialMessage=true persisted=true");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_CONTAINER_MISSING_ENTRIES_VERIFIED missing={} persisted=true partialStatus=true",
            activeOuterReloaded.snapshotMissingEntries);
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_CORRUPTION_COUNTER_PERSISTENCE_VERIFIED previousWarning=true incremented=true dirty=true roundTrip=true");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_CONTAINER_QUARANTINE_VERIFIED wrongTagType=true wrongElementType=true normalized=true legacyMissingAllowed=true");
    }

    private static void verifySavedAnchorCoordinateQuarantine() {
        BlockPos damagedPos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",damagedPos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag snapshots=new ListTag();
        snapshots.add(snapshot);

        CompoundTag missingAnchor=new CompoundTag();
        missingAnchor.putBoolean("initialized",true);
        missingAnchor.putBoolean("started",true);
        missingAnchor.putBoolean("surfaceFirstScan",true);
        missingAnchor.putBoolean("centerOutScan",true);
        missingAnchor.putInt("centerOutIndex",12);
        missingAnchor.putInt("edits",1);
        missingAnchor.put("snapshots",snapshots.copy());
        State missing=State.load(missingAnchor);
        boolean missingStopped=missing.finished
            && missing.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && missing.snapshots.size()==1
            && missing.snapshots.get(0).getLong("pos")==damagedPos.asLong()
            && missing.isDirty();

        CompoundTag wrongTypeAnchor=missingAnchor.copy();
        wrongTypeAnchor.putInt("x",12);
        wrongTypeAnchor.putString("z","not-an-int");
        State wrongType=State.load(wrongTypeAnchor);
        boolean wrongTypeStopped=wrongType.finished
            && wrongType.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && wrongType.snapshots.size()==1
            && wrongType.x==damagedPos.getX() && wrongType.z==damagedPos.getZ()
            && wrongType.isDirty();

        CompoundTag emptyFracture=new CompoundTag();
        emptyFracture.putBoolean("initialized",true);
        emptyFracture.putBoolean("started",true);
        emptyFracture.putBoolean("surfaceFirstScan",true);
        emptyFracture.putBoolean("centerOutScan",true);
        emptyFracture.putInt("centerOutIndex",12);
        emptyFracture.put("snapshots",new ListTag());
        State reinitialized=State.load(emptyFracture);
        boolean emptyFractureReinitialized=!reinitialized.initialized
            && !reinitialized.finished && reinitialized.centerOutIndex==0
            && reinitialized.cursorY==Integer.MIN_VALUE && reinitialized.isDirty();

        BlockPos beaconFallback=new BlockPos(30,64,-40);
        CompoundTag activeRecovery=new CompoundTag();
        activeRecovery.putBoolean("initialized",true);
        activeRecovery.putBoolean("started",true);
        activeRecovery.putBoolean("restoring",true);
        activeRecovery.putInt("edits",1);
        activeRecovery.putInt("restoreIndex",0);
        activeRecovery.putInt("restoreScan",0);
        activeRecovery.putLong("beaconPos",beaconFallback.asLong());
        activeRecovery.put("snapshots",snapshots.copy());
        State recovery=State.load(activeRecovery);
        boolean restoringPreserved=recovery.restoring && !recovery.finished
            && recovery.snapshots.size()==1
            && recovery.x==beaconFallback.getX() && recovery.z==beaconFallback.getZ()
            && recovery.isDirty();

        CompoundTag validAnchor=new CompoundTag();
        validAnchor.putInt("x",12);
        validAnchor.putInt("z",-7);
        validAnchor.putBoolean("initialized",true);
        validAnchor.putBoolean("started",true);
        validAnchor.putBoolean("surfaceFirstScan",true);
        validAnchor.putBoolean("centerOutScan",true);
        validAnchor.putInt("centerOutIndex",12);
        validAnchor.put("snapshots",new ListTag());
        State valid=State.load(validAnchor);
        boolean validUnchanged=valid.x==12 && valid.z==-7 && !valid.isDirty();

        if(!missingStopped || !wrongTypeStopped || !emptyFractureReinitialized
            || !restoringPreserved || !validUnchanged)
            throw new IllegalStateException("CI saved fracture anchor coordinate quarantine regression"
                +" missingStopped="+missingStopped
                +" wrongTypeStopped="+wrongTypeStopped
                +" emptyReinitialized="+emptyFractureReinitialized
                +" restoringPreserved="+restoringPreserved
                +" validUnchanged="+validUnchanged);
        LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_ANCHOR_COORDINATE_QUARANTINE_VERIFIED missing=true wrongType=true activeFractureStopped=true emptyFractureReinitialized=true restoringPreserved=true validUnchanged=true fallbackBeacon=true");
    }

    private static void verifyMalformedInitializedFlagQuarantine() {
        BlockPos pos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",pos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag snapshots=new ListTag();
        snapshots.add(snapshot);

        CompoundTag damaged=new CompoundTag();
        damaged.putInt("x",12);
        damaged.putInt("z",-7);
        damaged.putString("initialized","corrupted");
        damaged.putBoolean("started",true);
        damaged.putBoolean("surfaceFirstScan",true);
        damaged.putBoolean("centerOutScan",true);
        damaged.putInt("centerOutIndex",17);
        damaged.putInt("edits",1);
        damaged.put("snapshots",snapshots);

        State quarantined=State.load(damaged);
        boolean stopped=quarantined.initialized && quarantined.started && quarantined.finished
            && quarantined.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && quarantined.x==12 && quarantined.z==-7
            && quarantined.snapshots.size()==1
            && quarantined.snapshots.get(0).getLong("pos")==pos.asLong()
            && quarantined.isDirty();
        State roundTrip=State.load(quarantined.save(new CompoundTag()));
        boolean stable=roundTrip.initialized && roundTrip.started && roundTrip.finished
            && roundTrip.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && roundTrip.x==12 && roundTrip.z==-7
            && roundTrip.snapshots.size()==1
            && roundTrip.snapshots.get(0).getLong("pos")==pos.asLong();
        if(!stopped || !stable)
            throw new IllegalStateException("CI malformed initialized flag quarantine regression"
                +" stopped="+stopped+" stable="+stable);
        LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_INITIALIZED_FLAG_QUARANTINE_VERIFIED activeScanStopped=true anchorPreserved=true snapshotsPreserved=true roundTrip=true");
    }

    /** A corrupt started flag must not restart the countdown at a different anchor after edits. */
    private static void verifyMalformedStartedFlagQuarantine() {
        BlockPos pos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",pos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag snapshots=new ListTag();
        snapshots.add(snapshot);

        CompoundTag damaged=new CompoundTag();
        damaged.putInt("x",12);
        damaged.putInt("z",-7);
        damaged.putBoolean("initialized",true);
        damaged.putString("started","corrupted");
        damaged.putBoolean("surfaceFirstScan",true);
        damaged.putBoolean("centerOutScan",true);
        damaged.putInt("centerOutIndex",17);
        damaged.putInt("edits",1);
        damaged.putInt("finaleTicks",12);
        damaged.put("snapshots",snapshots);

        State quarantined=State.load(damaged);
        boolean stopped=quarantined.started && quarantined.finished
            && quarantined.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && quarantined.x==12 && quarantined.z==-7
            && quarantined.snapshots.size()==1
            && quarantined.snapshots.get(0).getLong("pos")==pos.asLong()
            && quarantined.isDirty();
        State roundTrip=State.load(quarantined.save(new CompoundTag()));
        boolean stable=roundTrip.started && roundTrip.finished
            && roundTrip.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && roundTrip.x==12 && roundTrip.z==-7
            && roundTrip.snapshots.size()==1
            && roundTrip.snapshots.get(0).getLong("pos")==pos.asLong();

        CompoundTag freshMalformed=new CompoundTag();
        freshMalformed.putString("started","corrupted");
        State fresh=State.load(freshMalformed);
        boolean freshCountdownPreserved=!fresh.started && !fresh.finished && fresh.isDirty();
        if(!stopped || !stable || !freshCountdownPreserved)
            throw new IllegalStateException("CI malformed started flag quarantine regression"
                +" stopped="+stopped+" stable="+stable
                +" freshCountdownPreserved="+freshCountdownPreserved);
        LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_STARTED_FLAG_QUARANTINE_VERIFIED activeScanStopped=true anchorPreserved=true snapshotsPreserved=true roundTrip=true freshCountdownPreserved=true");
    }

    /** Completion flags must be typed: a wrong default must not resume fracture or replay recovery. */
    private static void verifyMalformedCompletionFlagsQuarantine() {
        BlockPos pos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",pos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag snapshots=new ListTag();
        snapshots.add(snapshot);

        CompoundTag malformedFinished=new CompoundTag();
        malformedFinished.putInt("x",12);
        malformedFinished.putInt("z",-7);
        malformedFinished.putBoolean("initialized",true);
        malformedFinished.putBoolean("started",true);
        malformedFinished.putString("finished","corrupted");
        malformedFinished.putBoolean("surfaceFirstScan",true);
        malformedFinished.putBoolean("centerOutScan",true);
        malformedFinished.putInt("centerOutIndex",17);
        malformedFinished.putInt("edits",1);
        malformedFinished.put("snapshots",snapshots.copy());
        State finishedQuarantine=State.load(malformedFinished);
        boolean finishedStopped=finishedQuarantine.started && finishedQuarantine.finished
            && finishedQuarantine.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && finishedQuarantine.x==12 && finishedQuarantine.z==-7
            && finishedQuarantine.snapshots.size()==1
            && finishedQuarantine.snapshots.get(0).getLong("pos")==pos.asLong()
            && finishedQuarantine.isDirty();
        State finishedRoundTrip=State.load(finishedQuarantine.save(new CompoundTag()));
        boolean finishedStable=finishedRoundTrip.started && finishedRoundTrip.finished
            && finishedRoundTrip.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && finishedRoundTrip.x==12 && finishedRoundTrip.z==-7
            && finishedRoundTrip.snapshots.size()==1
            && finishedRoundTrip.snapshots.get(0).getLong("pos")==pos.asLong();
        CompoundTag freshFinishedTag=new CompoundTag();
        freshFinishedTag.putString("finished","corrupted");
        State freshFinished=State.load(freshFinishedTag);
        boolean freshCountdownPreserved=!freshFinished.started && !freshFinished.finished
            && freshFinished.isDirty();
        if(!finishedStopped || !finishedStable || !freshCountdownPreserved)
            throw new IllegalStateException("CI malformed finished flag quarantine regression"
                +" stopped="+finishedStopped+" stable="+finishedStable
                +" freshCountdownPreserved="+freshCountdownPreserved);
        LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_FINISHED_FLAG_QUARANTINE_VERIFIED activeScanStopped=true anchorPreserved=true snapshotsPreserved=true roundTrip=true freshCountdownPreserved=true");

        CompoundTag malformedRestored=new CompoundTag();
        malformedRestored.putInt("x",12);
        malformedRestored.putInt("z",-7);
        malformedRestored.putBoolean("initialized",true);
        malformedRestored.putBoolean("started",true);
        malformedRestored.putBoolean("finished",true);
        malformedRestored.putBoolean("restoring",true);
        malformedRestored.putString("restored","corrupted");
        malformedRestored.putInt("restoreIndex",0);
        malformedRestored.putInt("restoreScan",0);
        malformedRestored.putInt("edits",1);
        malformedRestored.putBoolean("surfaceFirstScan",true);
        malformedRestored.putBoolean("centerOutScan",true);
        malformedRestored.putInt("centerOutIndex",17);
        malformedRestored.put("snapshots",snapshots.copy());
        State restoredQuarantine=State.load(malformedRestored);
        boolean restoredStopped=restoredQuarantine.started && restoredQuarantine.finished
            && !restoredQuarantine.restoring && !restoredQuarantine.restored
            && restoredQuarantine.restoreIndex==-1 && restoredQuarantine.restoreScan==-1
            && restoredQuarantine.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && restoredQuarantine.x==12 && restoredQuarantine.z==-7
            && restoredQuarantine.snapshots.size()==1
            && restoredQuarantine.snapshots.get(0).getLong("pos")==pos.asLong()
            && restoredQuarantine.isDirty();
        State restoredRoundTrip=State.load(restoredQuarantine.save(new CompoundTag()));
        boolean restoredStable=restoredRoundTrip.started && restoredRoundTrip.finished
            && !restoredRoundTrip.restoring && !restoredRoundTrip.restored
            && restoredRoundTrip.centerOutIndex==CENTER_OUT_COLUMN_COUNT
            && restoredRoundTrip.snapshots.size()==1
            && restoredRoundTrip.snapshots.get(0).getLong("pos")==pos.asLong();
        BlockPos restartBeacon=new BlockPos(12,64,-7);
        boolean beaconRestartAllowed=restoredStable
            && canStartBeaconRestoration(restoredRoundTrip,restartBeacon);
        boolean distantBeaconRejected=!canStartBeaconRestoration(
            restoredRoundTrip,new BlockPos(141,64,-7));
        State activeRestoreGate=State.load(restoredQuarantine.save(new CompoundTag()));
        activeRestoreGate.restoring=true;
        State completedRestoreGate=State.load(restoredQuarantine.save(new CompoundTag()));
        completedRestoreGate.restored=true;
        State emptySnapshotGate=State.load(restoredQuarantine.save(new CompoundTag()));
        emptySnapshotGate.snapshots.clear();
        State unstartedGate=State.load(restoredQuarantine.save(new CompoundTag()));
        unstartedGate.started=false;
        boolean lifecycleGatesRejected=!canStartBeaconRestoration(activeRestoreGate,restartBeacon)
            && !canStartBeaconRestoration(completedRestoreGate,restartBeacon)
            && !canStartBeaconRestoration(emptySnapshotGate,restartBeacon)
            && !canStartBeaconRestoration(unstartedGate,restartBeacon);
        CompoundTag freshRestoredTag=new CompoundTag();
        freshRestoredTag.putString("restored","corrupted");
        State freshRestored=State.load(freshRestoredTag);
        boolean freshStatePreserved=!freshRestored.started && !freshRestored.finished
            && !freshRestored.restoring && !freshRestored.restored && freshRestored.isDirty();
        if(!restoredStopped || !restoredStable || !beaconRestartAllowed
            || !distantBeaconRejected || !lifecycleGatesRejected || !freshStatePreserved)
            throw new IllegalStateException("CI malformed restored flag quarantine regression"
                +" stopped="+restoredStopped+" stable="+restoredStable
                +" beaconRestartAllowed="+beaconRestartAllowed
                +" distantBeaconRejected="+distantBeaconRejected
                +" lifecycleGatesRejected="+lifecycleGatesRejected
                +" freshStatePreserved="+freshStatePreserved);
        LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_RESTORED_FLAG_QUARANTINE_VERIFIED activeRestoreStopped=true snapshotsPreserved=true beaconRestartAllowed=true distantBeaconRejected=true lifecycleGatesRejected=true terminalCursor=true roundTrip=true freshStatePreserved=true");
    }

    /** Verify that a pending terrain snapshot survives a SavedData round trip. */
    private static void verifyPendingSnapshotPersistence() {
        verifySavedAnchorCoordinateQuarantine();
        verifyRestoreFailureCacheReuse();
        verifySnapshotListContainerQuarantine();
        State original=new State();
        original.started=true;
        original.restoring=true;
        original.x=12;
        original.z=-7;
        original.edits=1;
        original.restoreTicks=20;
        original.restoreIndex=0;
        original.restoreScan=0;
        BlockPos pos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",pos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        long stoneSnapshotEstimate=boundedSnapshotPayloadEstimate(snapshot);
        CompoundTag invalidRuntimeSnapshot=snapshot.copy();
        invalidRuntimeSnapshot.putString("unexpected","payload");
        boolean runtimeBudgetBoundaries=stoneSnapshotEstimate>0L
            && withinSnapshotRetentionBudget(0L,stoneSnapshotEstimate)
            && withinSnapshotRetentionBudget(MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES-stoneSnapshotEstimate,
                stoneSnapshotEstimate)
            && !withinSnapshotRetentionBudget(MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES-stoneSnapshotEstimate+1L,
                stoneSnapshotEstimate)
            && boundedSnapshotPayloadEstimate(invalidRuntimeSnapshot)==0L;
        if(!runtimeBudgetBoundaries)
            throw new IllegalStateException("CI runtime recovery snapshot budget guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_RUNTIME_BUDGET_GUARD_VERIFIED candidateEstimate={} capBytes={} invalidRejected=true exactBoundary=true",
            stoneSnapshotEstimate,MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES);

        // A short but syntactically invalid registry name must be quarantined on load.
        // Otherwise NbtUtils may be asked to resolve malformed ResourceLocation input during recovery.
        CompoundTag malformedNameState=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
        malformedNameState.putString("Name","minecraft:bad block");
        CompoundTag malformedNameEntry=new CompoundTag();
        malformedNameEntry.putLong("pos",new BlockPos(14,64,-7).asLong());
        malformedNameEntry.put("state",malformedNameState);
        boolean resourceLocationGuard=isValidSnapshotBlockId("minecraft:stone")
            && isValidSnapshotBlockId("blackfrequency:recovery_beacon")
            && !isValidSnapshotBlockId("minecraft:bad block")
            && !isValidSnapshotBlockId("minecraft:Uppercase")
            && !isValidSnapshotBlockId("minecraft:")
            && !isValidSnapshotBlockId("stone")
            && boundedSnapshotPayloadEstimate(malformedNameEntry)==0L;
        ListTag malformedNameSnapshots=new ListTag();
        malformedNameSnapshots.add(malformedNameEntry);
        CompoundTag malformedNameSave=new CompoundTag();
        malformedNameSave.putBoolean("restoring",true);
        malformedNameSave.putInt("restoreIndex",0);
        malformedNameSave.putInt("restoreScan",0);
        malformedNameSave.put("snapshots",malformedNameSnapshots);
        State malformedNameLoaded=State.load(malformedNameSave);
        ListTag sanitizedNameSnapshots=malformedNameLoaded.save(new CompoundTag())
            .getList("snapshots",Tag.TAG_COMPOUND);
        boolean quarantinedNamePlaceholder=sanitizedNameSnapshots.size()==1
            && sanitizedNameSnapshots.getCompound(0).getAllKeys().isEmpty();
        if(!resourceLocationGuard || !malformedNameLoaded.isDirty() || !quarantinedNamePlaceholder)
            throw new IllegalStateException("CI malformed recovery block identifier quarantine regression"
                +" validStone="+isValidSnapshotBlockId("minecraft:stone")
                +" validModded="+isValidSnapshotBlockId("blackfrequency:recovery_beacon")
                +" invalidSpace="+!isValidSnapshotBlockId("minecraft:bad block")
                +" invalidUppercase="+!isValidSnapshotBlockId("minecraft:Uppercase")
                +" invalidEmptyPath="+!isValidSnapshotBlockId("minecraft:")
                +" invalidUnqualified="+!isValidSnapshotBlockId("stone")
                +" invalidEstimate="+(boundedSnapshotPayloadEstimate(malformedNameEntry)==0L)
                +" dirty="+malformedNameLoaded.isDirty()
                +" entries="+sanitizedNameSnapshots.size()
                +" emptyPlaceholder="+quarantinedNamePlaceholder);
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_RESOURCE_LOCATION_QUARANTINE_VERIFIED invalidRejected=true validNamespaced=true quarantined=true");
        original.snapshots.add(snapshot);
        State loaded=State.load(original.save(new CompoundTag()));
        if(!loaded.started || !loaded.restoring || loaded.edits!=1
            || loaded.restoreIndex!=0 || loaded.restoreScan!=0 || loaded.restoreTicks!=20
            || Math.abs(restorationGravityScale(loaded.restoreTicks)-.5D)>1.0E-9D
            || loaded.snapshots.size()!=1
            || loaded.retainedSnapshotEstimateBytes!=stoneSnapshotEstimate
            || loaded.snapshots.get(0).getLong("pos")!=pos.asLong()
            || !loaded.snapshots.get(0).getCompound("state").equals(snapshot.getCompound("state")))
            throw new IllegalStateException("CI pending terrain snapshot persistence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_SAVE_ROUNDTRIP_VERIFIED elapsed=20 scale=0.5");
        // A damaged save may contain cursors beyond the serialized list.
        // Recovery must clamp them without discarding the only terrain snapshot.
        CompoundTag damaged=original.save(new CompoundTag());
        damaged.putInt("restoreIndex",Integer.MAX_VALUE);
        damaged.putInt("restoreScan",Integer.MIN_VALUE);
        State repaired=State.load(damaged);
        if(repaired.restoreIndex!=0 || repaired.restoreScan!=0
            || repaired.snapshots.size()!=1
            || repaired.snapshots.get(0).getLong("pos")!=pos.asLong())
            throw new IllegalStateException("CI damaged recovery cursor persistence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST DAMAGED_RECOVERY_CURSOR_REPAIRED");

        // Corrupt counters must not bypass the 95,000-edit ceiling, create
        // negative progress, or make the recovery clock wait billions of ticks.
        CompoundTag negativeCounters=new CompoundTag();
        negativeCounters.putLong("elapsed",Long.MIN_VALUE);
        negativeCounters.putInt("edits",Integer.MIN_VALUE);
        negativeCounters.putInt("finaleTicks",Integer.MIN_VALUE);
        negativeCounters.putInt("restoreTicks",Integer.MIN_VALUE);
        negativeCounters.putInt("restoredBlocks",Integer.MIN_VALUE);
        negativeCounters.putInt("skippedOccupied",Integer.MIN_VALUE);
        negativeCounters.putInt("skippedInvalid",Integer.MIN_VALUE);
        State sanitized=State.load(negativeCounters);
        CompoundTag normalizedNegative=sanitized.save(new CompoundTag());
        if(sanitized.elapsed!=0L || sanitized.edits!=0 || sanitized.finaleTicks!=0
            || sanitized.restoreTicks!=0 || sanitized.restoredBlocks!=0
            || sanitized.skippedOccupied!=0 || sanitized.skippedInvalid!=0
            || !sanitized.isDirty()
            || normalizedNegative.getLong("elapsed")!=0L
            || normalizedNegative.getInt("edits")!=0
            || normalizedNegative.getInt("finaleTicks")!=0
            || normalizedNegative.getInt("restoreTicks")!=0
            || normalizedNegative.getInt("restoredBlocks")!=0
            || normalizedNegative.getInt("skippedOccupied")!=0
            || normalizedNegative.getInt("skippedInvalid")!=0)
            throw new IllegalStateException("CI negative saved counters were not sanitized and persisted");
        CompoundTag excessiveCounters=new CompoundTag();
        excessiveCounters.putLong("elapsed",Long.MAX_VALUE);
        excessiveCounters.putInt("edits",Integer.MAX_VALUE);
        excessiveCounters.putInt("finaleTicks",Integer.MAX_VALUE);
        excessiveCounters.putInt("restoreTicks",Integer.MAX_VALUE);
        excessiveCounters.putInt("restoredBlocks",Integer.MAX_VALUE);
        excessiveCounters.putInt("skippedOccupied",Integer.MAX_VALUE);
        excessiveCounters.putInt("skippedInvalid",Integer.MAX_VALUE);
        State capped=State.load(excessiveCounters);
        CompoundTag normalizedExcessive=capped.save(new CompoundTag());
        CompoundTag wrongTypedCounters=new CompoundTag();
        wrongTypedCounters.putString("edits","not-an-int");
        State wrongTyped=State.load(wrongTypedCounters);
        CompoundTag normalizedWrongType=wrongTyped.save(new CompoundTag());
        State legacyMissingCounters=State.load(new CompoundTag());
        if(capped.elapsed!=DAY*10L || capped.edits!=MAX_EDITS
            || capped.finaleTicks!=Integer.MAX_VALUE || capped.restoreTicks!=Integer.MAX_VALUE
            || capped.restoredBlocks!=MAX_EDITS || capped.skippedOccupied!=MAX_EDITS
            || capped.skippedInvalid!=MAX_EDITS || !capped.isDirty()
            || normalizedExcessive.getLong("elapsed")!=DAY*10L
            || normalizedExcessive.getInt("edits")!=MAX_EDITS
            || normalizedExcessive.getInt("restoredBlocks")!=MAX_EDITS
            || normalizedExcessive.getInt("skippedOccupied")!=MAX_EDITS
            || normalizedExcessive.getInt("skippedInvalid")!=MAX_EDITS
            || !wrongTyped.isDirty()
            || !normalizedWrongType.contains("edits",Tag.TAG_INT)
            || normalizedWrongType.getInt("edits")!=0
            || legacyMissingCounters.isDirty()
            || nextNonNegativeTick(-1)!=0 || nextNonNegativeTick(0)!=1
            || nextNonNegativeTick(Integer.MAX_VALUE)!=Integer.MAX_VALUE)
            throw new IllegalStateException("CI excessive/wrong-type saved counters were not bounded and persisted");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_STATE_COUNTERS_SANITIZED");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_STATE_COUNTERS_PERSISTED negative=true excessive=true wrongType=true legacyMissingAllowed=true");

        // Entry count is not a byte bound: discard unexpected nested data and oversized properties.
        CompoundTag nestedPayload=new CompoundTag();
        nestedPayload.putString("blob","x".repeat(4096));
        CompoundTag nestedEntry=new CompoundTag();
        nestedEntry.putLong("pos",new BlockPos(12,64,-7).asLong());
        nestedEntry.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        nestedEntry.put("payload",nestedPayload);
        CompoundTag oversizedPropertyState=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
        CompoundTag oversizedProperties=new CompoundTag();
        oversizedProperties.putString("facing","n".repeat(MAX_SNAPSHOT_PROPERTY_VALUE_LENGTH+1));
        oversizedPropertyState.put("Properties",oversizedProperties);
        CompoundTag oversizedPropertyEntry=new CompoundTag();
        oversizedPropertyEntry.putLong("pos",new BlockPos(13,64,-7).asLong());
        oversizedPropertyEntry.put("state",oversizedPropertyState);
        ListTag untrustedSnapshots=new ListTag();
        untrustedSnapshots.add(nestedEntry);
        untrustedSnapshots.add(oversizedPropertyEntry);
        CompoundTag untrustedSave=new CompoundTag();
        untrustedSave.putBoolean("restoring",true);
        untrustedSave.putInt("restoreIndex",1);
        untrustedSave.putInt("restoreScan",1);
        untrustedSave.put("snapshots",untrustedSnapshots);
        State quarantined=State.load(untrustedSave);
        ListTag rewrittenSnapshots=quarantined.save(new CompoundTag()).getList("snapshots",Tag.TAG_COMPOUND);
        if(quarantined.snapshots.size()!=2 || quarantined.restoreIndex!=1 || quarantined.restoreScan!=1
            || rewrittenSnapshots.size()!=2
            || !rewrittenSnapshots.getCompound(0).getAllKeys().isEmpty()
            || !rewrittenSnapshots.getCompound(1).getAllKeys().isEmpty())
            throw new IllegalStateException("CI oversized recovery snapshot payload was retained");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_PAYLOAD_QUARANTINE_VERIFIED entries=2 retainedPayload=false");

        long maximumStoneSnapshotEstimate=stoneSnapshotEstimate*MAX_EDITS;
        boolean retentionBoundaries=withinSnapshotRetentionBudget(0L,MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES)
            && withinSnapshotRetentionBudget(MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES-1L,1L)
            && !withinSnapshotRetentionBudget(MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES,1L)
            && !withinSnapshotRetentionBudget(MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES-1L,2L)
            && !withinSnapshotRetentionBudget(-1L,1L)
            && !withinSnapshotRetentionBudget(0L,0L);
        if(!retentionBoundaries || maximumStoneSnapshotEstimate>MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES)
            throw new IllegalStateException("CI recovery snapshot aggregate retention budget regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_RETENTION_BUDGET_VERIFIED capBytes={} maxStoneSnapshots={} estimatedBytes={} boundary=true overflowSafe=true",
            MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES,MAX_EDITS,maximumStoneSnapshotEstimate);

        // A syntactically bounded but oversized property compound must not consume
        // a disproportionate share of the recovery budget before registry decoding.
        CompoundTag boundedEntryState=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
        CompoundTag boundedProperties=new CompoundTag();
        boundedProperties.putString("k".repeat(60),"v".repeat(67));
        boundedEntryState.put("Properties",boundedProperties);
        CompoundTag boundedEntry=new CompoundTag();
        boundedEntry.putLong("pos",new BlockPos(0,64,0).asLong());
        boundedEntry.put("state",boundedEntryState);
        long boundedEntryEstimate=estimatedSnapshotPayloadBytes(boundedEntry);
        CompoundTag oversizedEntryState=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
        CompoundTag oversizedEntryProperties=new CompoundTag();
        oversizedEntryProperties.putString("k".repeat(60),"v".repeat(68));
        oversizedEntryState.put("Properties",oversizedEntryProperties);
        CompoundTag oversizedEntry=new CompoundTag();
        oversizedEntry.putLong("pos",new BlockPos(1,64,0).asLong());
        oversizedEntry.put("state",oversizedEntryState);
        long worstCaseRetainedBytes=(long)MAX_EDITS*MAX_SNAPSHOT_ENTRY_ESTIMATE_BYTES;
        if(boundedEntryEstimate!=698L
            || boundedSnapshotPayloadEstimate(boundedEntry)!=boundedEntryEstimate
            || estimatedSnapshotPayloadBytes(oversizedEntry)!=701L
            || boundedSnapshotPayloadEstimate(oversizedEntry)!=0L
            || worstCaseRetainedBytes>MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES
            || maximumStoneSnapshotEstimate>worstCaseRetainedBytes)
            throw new IllegalStateException("CI per-entry recovery snapshot size bound regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_ENTRY_SIZE_BOUND_VERIFIED acceptedBytes={} rejectedBytes=701 maxEntryBytes={} maxEdits={} worstCaseBytes={} capBytes={}",
            boundedEntryEstimate,MAX_SNAPSHOT_ENTRY_ESTIMATE_BYTES,MAX_EDITS,
            worstCaseRetainedBytes,MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES);

        // A corrupt NBT list must not grow the recovery queue beyond the same
        // 95,000-entry ceiling enforced by terrain destruction.
        ListTag oversizedSnapshots=new ListTag();
        CompoundTag safeStoneState=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
        for(int i=0;i<MAX_EDITS+1;i++) {
            CompoundTag entry=new CompoundTag();
            entry.putLong("pos",new BlockPos(i,64,0).asLong());
            entry.put("state",safeStoneState.copy());
            oversizedSnapshots.add(entry);
        }
        CompoundTag oversizedSave=new CompoundTag();
        oversizedSave.putBoolean("restoring",true);
        oversizedSave.putInt("restoreIndex",MAX_EDITS);
        oversizedSave.putInt("restoreScan",MAX_EDITS);
        oversizedSave.put("snapshots",oversizedSnapshots);
        State boundedSnapshots=State.load(oversizedSave);
        if(boundedSnapshots.snapshots.size()!=MAX_EDITS
            || boundedSnapshots.edits!=MAX_EDITS
            || boundedSnapshots.restoreIndex!=MAX_EDITS-1
            || boundedSnapshots.restoreScan!=MAX_EDITS-1
            || boundedSnapshots.snapshots.get(0).getLong("pos")!=new BlockPos(0,64,0).asLong()
            || boundedSnapshots.snapshots.get(MAX_EDITS-1).getLong("pos")
                !=new BlockPos(MAX_EDITS-1,64,0).asLong()
            || boundedSnapshots.retainedSnapshotEstimateBytes!=maximumStoneSnapshotEstimate
            || boundedSnapshots.retainedSnapshotEstimateBytes>MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES)
            throw new IllegalStateException("CI oversized recovery snapshot list was not bounded safely");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_BOUNDED count={} cap={}",
            boundedSnapshots.snapshots.size(),MAX_EDITS);
        LOGGER.info("BLACK_FREQUENCY_TEST PENDING_SNAPSHOT_PERSISTENCE_VERIFIED");
    }

    /**
     * A persisted activation coordinate is only a visual anchor while it remains
     * inside the accepted beacon radius, build height and current world border.
     * Check this before hasChunkAt so corrupt SavedData cannot probe arbitrary chunks.
     */
    private static boolean isRecoveryBeaconVisualAnchorValid(BlockPos beacon,
            int anchorX,int anchorZ,int minBuildHeight,int maxBuildHeight,
            boolean withinWorldBorder) {
        return beacon!=null && withinWorldBorder
            && beacon.getY()>=minBuildHeight && beacon.getY()<maxBuildHeight
            && beaconWithinAnchor(beacon.getX(),beacon.getZ(),anchorX,anchorZ);
    }

    private static void verifyRecoveryBeaconVisualAnchorBounds() {
        if(!isRecoveryBeaconVisualAnchorValid(new BlockPos(128,64,0),0,0,-64,320,true)
            || isRecoveryBeaconVisualAnchorValid(new BlockPos(129,64,0),0,0,-64,320,true)
            || !isRecoveryBeaconVisualAnchorValid(new BlockPos(0,-64,0),0,0,-64,320,true)
            || isRecoveryBeaconVisualAnchorValid(new BlockPos(0,320,0),0,0,-64,320,true)
            || isRecoveryBeaconVisualAnchorValid(new BlockPos(200,64,0),200,0,-64,320,false)
            || isRecoveryBeaconVisualAnchorValid(null,0,0,-64,320,true))
            throw new IllegalStateException("CI recovery beacon visual anchor bounds regression");
        verifyRecoveryBeaconPositionPresence();
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_BEACON_VISUAL_ANCHOR_BOUNDS_VERIFIED radius=128 minYInclusive=true maxYExclusive=true worldBorder=true invalidRejected=true");
    }

    private static BlockPos recoveryVisualAnchor(State s,int fallbackY) {
        return s.beaconPosPresent ? BlockPos.of(s.beaconPos) : new BlockPos(s.x,fallbackY,s.z);
    }

    /** Missing or mistyped NBT must not alias the valid packed coordinate BlockPos.ZERO. */
    private static void verifyRecoveryBeaconPositionPresence() {
        CompoundTag missingTag=new CompoundTag();
        missingTag.putBoolean("restoring",true);
        missingTag.putInt("x",12);
        missingTag.putInt("z",-7);
        State missing=State.load(missingTag);
        CompoundTag rewritten=new CompoundTag();
        rewritten.putLong("beaconPos",BlockPos.ZERO.asLong());
        missing.save(rewritten);
        BlockPos fallback=recoveryVisualAnchor(missing,64);

        CompoundTag wrongTypeTag=missingTag.copy();
        wrongTypeTag.putBoolean("restoring",false);
        wrongTypeTag.putString("beaconPos","not-a-long");
        State wrongType=State.load(wrongTypeTag);
        CompoundTag normalizedWrongType=new CompoundTag();
        wrongType.save(normalizedWrongType);
        State roundTrippedWrongType=State.load(normalizedWrongType);

        State origin=new State();
        origin.x=12;
        origin.z=-7;
        origin.beaconPos=BlockPos.ZERO.asLong();
        origin.beaconPosPresent=true;
        State loadedOrigin=State.load(origin.save(new CompoundTag()));
        BlockPos originAnchor=recoveryVisualAnchor(loadedOrigin,64);

        if(missing.beaconPosPresent || rewritten.contains("beaconPos",Tag.TAG_LONG)
            || !fallback.equals(new BlockPos(12,64,-7))
            || wrongType.beaconPosPresent || !wrongType.isDirty()
            || normalizedWrongType.contains("beaconPos")
            || roundTrippedWrongType.beaconPosPresent
            || !recoveryVisualAnchor(roundTrippedWrongType,64).equals(new BlockPos(12,64,-7))
            || !loadedOrigin.beaconPosPresent || loadedOrigin.beaconPos!=BlockPos.ZERO.asLong()
            || !originAnchor.equals(BlockPos.ZERO))
            throw new IllegalStateException("CI saved recovery beacon position presence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_BEACON_POSITION_PRESENCE_VERIFIED missingOrWrongTypeRejected=true originCoordinatePreserved=true fallback=rift-anchor");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_BEACON_POSITION_TYPE_QUARANTINE_VERIFIED malformedType=true dirty=true removedOnSave=true roundTrip=true fallbackPreserved=true");
    }

    /** Horizontal anchor check with overflow-safe coordinates, independent of beacon height. */
    private static boolean beaconWithinAnchor(int x,int z,int anchorX,int anchorZ) {
        long dx=(long)x-anchorX,dz=(long)z-anchorZ;
        return Math.abs(dx)<=128L && Math.abs(dz)<=128L && dx*dx+dz*dz<=128L*128L;
    }

    /**
     * A generated recovery beacon is placed no farther than RADIUS-4 from the
     * fracture anchor. Validate the persisted coordinate before treating a
     * beacon there as mod-owned; corrupted NBT must not consume or shield a
     * player-owned beacon elsewhere in the world.
     */
    private static boolean naturalBeaconWithinFracture(BlockPos beacon,int anchorX,int anchorZ,
            int minBuildHeight,int maxBuildHeight) {
        long dx=(long)beacon.getX()-anchorX,dz=(long)beacon.getZ()-anchorZ;
        long limit=RADIUS-4L;
        return beacon.getY()>=minBuildHeight && beacon.getY()<maxBuildHeight
            && Math.abs(dx)<=limit && Math.abs(dz)<=limit
            && dx*dx+dz*dz<=limit*limit;
    }

    private static boolean isValidNaturalBeaconPosition(ServerLevel level,State s,BlockPos beacon) {
        return s.started && s.naturalBeaconPos!=Long.MIN_VALUE && s.naturalBeaconId!=null
            && beacon.asLong()==s.naturalBeaconPos
            && naturalBeaconWithinFracture(beacon,s.x,s.z,
                level.getMinBuildHeight(),level.getMaxBuildHeight())
            && level.getWorldBorder().isWithinBounds(beacon);
    }

    /**
     * A coordinate and block type are not proof of ownership: another mod or a
     * player can replace the generated beacon with a vanilla beacon at the same
     * position. Bind the saved objective to Forge's persistent block-entity data.
     * Check chunk availability before any block-entity lookup; never force-load.
     */
    private static boolean isOwnedNaturalBeaconAtLoadedPosition(ServerLevel level,State s,BlockPos beacon) {
        if(!isValidNaturalBeaconPosition(level,s,beacon) || !level.hasChunkAt(beacon)
            || !level.getBlockState(beacon).is(Blocks.BEACON)) return false;
        var blockEntity=level.getBlockEntity(beacon);
        return blockEntity instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity
            && blockEntity.getPersistentData().hasUUID(NATURAL_BEACON_ID_TAG)
            && s.naturalBeaconId.equals(blockEntity.getPersistentData().getUUID(NATURAL_BEACON_ID_TAG));
    }

    private static void verifyNaturalBeaconIdPersistence() {
        State saved=new State();
        saved.started=true;
        saved.naturalBeaconPos=new BlockPos(3,64,-7).asLong();
        saved.naturalBeaconId=UUID.fromString("2f65b99d-95c1-4cb7-8d8f-1f37f37c4b92");
        State loaded=State.load(saved.save(new CompoundTag()));
        if(!saved.naturalBeaconId.equals(loaded.naturalBeaconId)
            || loaded.naturalBeaconPos!=saved.naturalBeaconPos)
            throw new IllegalStateException("CI natural beacon ownership UUID persistence regression");

        // An owner UUID is meaningful only when its packed objective position is present.
        // Corrupt NBT must not preserve an orphan identity; coordinate zero remains valid.
        CompoundTag orphaned=new CompoundTag();
        orphaned.putString("naturalBeaconPos","not-a-long");
        orphaned.putUUID("naturalBeaconId",UUID.fromString("8e6e7b2a-5b42-4c8f-9b17-6b5d6e7a8c91"));
        State orphanedLoaded=State.load(orphaned);
        boolean orphanedMarkedDirty=orphanedLoaded.isDirty();
        CompoundTag normalizedOrphan=orphanedLoaded.save(new CompoundTag());

        CompoundTag missingPositionWithUuid=new CompoundTag();
        missingPositionWithUuid.putUUID("naturalBeaconId",UUID.fromString("4d0f1a6b-9c32-4e85-b7a1-6f2c8d0e3a59"));
        State missingPositionWithUuidLoaded=State.load(missingPositionWithUuid);
        boolean missingPositionMarkedDirty=missingPositionWithUuidLoaded.isDirty();
        CompoundTag normalizedMissingPositionWithUuid=missingPositionWithUuidLoaded.save(new CompoundTag());

        CompoundTag missingOwner=new CompoundTag();
        missingOwner.putLong("naturalBeaconPos",new BlockPos(5,64,9).asLong());
        State missingOwnerLoaded=State.load(missingOwner);
        CompoundTag normalizedMissingOwner=missingOwnerLoaded.save(new CompoundTag());

        CompoundTag wrongOwnerType=new CompoundTag();
        wrongOwnerType.putLong("naturalBeaconPos",new BlockPos(-5,64,-9).asLong());
        wrongOwnerType.putString("naturalBeaconId","not-a-uuid");
        State wrongOwnerTypeLoaded=State.load(wrongOwnerType);

        State zeroPosition=new State();
        zeroPosition.naturalBeaconPos=BlockPos.ZERO.asLong();
        zeroPosition.naturalBeaconId=UUID.fromString("1f4a6d83-7e22-4c5b-9a31-8b0d2e6f4c70");
        State loadedZero=State.load(zeroPosition.save(new CompoundTag()));
        if(orphanedLoaded.naturalBeaconPos!=Long.MIN_VALUE
            || orphanedLoaded.naturalBeaconId!=null
            || normalizedOrphan.hasUUID("naturalBeaconId")
            || !orphanedMarkedDirty
            || !missingPositionMarkedDirty
            || normalizedMissingPositionWithUuid.hasUUID("naturalBeaconId")
            || missingPositionWithUuidLoaded.naturalBeaconPos!=Long.MIN_VALUE
            || missingOwnerLoaded.naturalBeaconPos!=Long.MIN_VALUE
            || missingOwnerLoaded.naturalBeaconId!=null
            || normalizedMissingOwner.getLong("naturalBeaconPos")!=Long.MIN_VALUE
            || normalizedMissingOwner.hasUUID("naturalBeaconId")
            || wrongOwnerTypeLoaded.naturalBeaconPos!=Long.MIN_VALUE
            || wrongOwnerTypeLoaded.naturalBeaconId!=null
            || loadedZero.naturalBeaconPos!=BlockPos.ZERO.asLong()
            || !zeroPosition.naturalBeaconId.equals(loadedZero.naturalBeaconId))
            throw new IllegalStateException("CI natural beacon UUID/position pair integrity regression");
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_UUID_POSITION_PAIR_VERIFIED orphanRejected=true zeroPositionPreserved=true missingUuidRejected=true wrongTypeUuidRejected=true orphanUuidQuarantined=true malformedCoordinateDirty=true missingCoordinateDirty=true");

        // SavedData normally supplies a fresh tag, but keep save() correct when
        // a caller reuses one: a cleared objective must not retain an old owner UUID.
        CompoundTag reused=new CompoundTag();
        saved.save(reused);
        saved.naturalBeaconPos=Long.MIN_VALUE;
        saved.naturalBeaconId=null;
        saved.save(reused);
        State cleared=State.load(reused);
        if(reused.hasUUID("naturalBeaconId") || cleared.naturalBeaconId!=null
            || cleared.naturalBeaconPos!=Long.MIN_VALUE)
            throw new IllegalStateException("CI stale natural beacon UUID survived save");
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_STATE_PERSISTENCE_VERIFIED");
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_CLEAR_VERIFIED reusedTag=true staleUuidRemoved=true");
    }

    private static void verifyRestoreCursorRepairPersistence() {
        CompoundTag invalidIndex=new CompoundTag();
        invalidIndex.putBoolean("restoring",true);
        invalidIndex.putInt("restoreIndex",0);
        invalidIndex.putInt("restoreScan",0);
        State repairedIndex=State.load(invalidIndex);
        CompoundTag savedIndex=repairedIndex.save(new CompoundTag());
        State reloadedIndex=State.load(savedIndex);

        CompoundTag invalidScan=new CompoundTag();
        invalidScan.putBoolean("restoring",true);
        invalidScan.putInt("restoreIndex",-1);
        invalidScan.putInt("restoreScan",0);
        State repairedScan=State.load(invalidScan);

        CompoundTag validCursor=new CompoundTag();
        validCursor.putBoolean("restoring",true);
        validCursor.putInt("restoreIndex",-1);
        validCursor.putInt("restoreScan",-1);
        State untouched=State.load(validCursor);

        ListTag cursorSnapshots=new ListTag();
        for(int i=0;i<2;i++) {
            CompoundTag entry=new CompoundTag();
            entry.putLong("pos",new BlockPos(i,64,0).asLong());
            entry.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
            cursorSnapshots.add(entry);
        }

        // A terminal cursor with pending snapshots is valid only when every
        // snapshot has a persisted outcome. Otherwise restart the bounded scan.
        CompoundTag pendingTerminalCursor=new CompoundTag();
        pendingTerminalCursor.putInt("x",0);
        pendingTerminalCursor.putInt("z",0);
        pendingTerminalCursor.putBoolean("started",true);
        pendingTerminalCursor.putBoolean("restoring",true);
        pendingTerminalCursor.putInt("edits",2);
        pendingTerminalCursor.putInt("restoreIndex",-1);
        pendingTerminalCursor.putInt("restoreScan",-1);
        pendingTerminalCursor.putInt("restoreTicks",110);
        pendingTerminalCursor.putInt("restoredBlocks",1);
        pendingTerminalCursor.put("snapshots",cursorSnapshots.copy());
        State resumedPendingTerminal=State.load(pendingTerminalCursor);
        CompoundTag normalizedPendingTerminal=resumedPendingTerminal.save(new CompoundTag());
        State reloadedPendingTerminal=State.load(normalizedPendingTerminal);

        CompoundTag accountedTerminalCursor=new CompoundTag();
        accountedTerminalCursor.putInt("x",0);
        accountedTerminalCursor.putInt("z",0);
        accountedTerminalCursor.putBoolean("restoring",true);
        accountedTerminalCursor.putInt("edits",2);
        accountedTerminalCursor.putInt("restoreIndex",-1);
        accountedTerminalCursor.putInt("restoreScan",-1);
        accountedTerminalCursor.putInt("restoreTicks",110);
        accountedTerminalCursor.putInt("restoredBlocks",1);
        accountedTerminalCursor.putInt("skippedOccupied",1);
        accountedTerminalCursor.put("snapshots",cursorSnapshots.copy());
        State accountedTerminal=State.load(accountedTerminalCursor);

        CompoundTag wrongIndexType=new CompoundTag();
        wrongIndexType.putBoolean("restoring",true);
        wrongIndexType.putString("restoreIndex","not-an-int");
        wrongIndexType.putInt("restoreScan",0);
        wrongIndexType.put("snapshots",cursorSnapshots.copy());
        State repairedWrongIndexType=State.load(wrongIndexType);
        CompoundTag normalizedWrongIndexType=repairedWrongIndexType.save(new CompoundTag());
        State reloadedWrongIndexType=State.load(normalizedWrongIndexType);

        CompoundTag wrongScanType=new CompoundTag();
        wrongScanType.putBoolean("restoring",true);
        wrongScanType.putInt("restoreIndex",1);
        wrongScanType.putString("restoreScan","not-an-int");
        wrongScanType.put("snapshots",cursorSnapshots.copy());
        State repairedWrongScanType=State.load(wrongScanType);
        CompoundTag normalizedWrongScanType=repairedWrongScanType.save(new CompoundTag());

        CompoundTag wrongCenterOutType=new CompoundTag();
        wrongCenterOutType.putBoolean("initialized",true);
        wrongCenterOutType.putBoolean("centerOutScan",true);
        wrongCenterOutType.putString("centerOutIndex","not-an-int");
        State quarantinedWrongCenterOutType=State.load(wrongCenterOutType);
        CompoundTag normalizedWrongCenterOutType=quarantinedWrongCenterOutType.save(new CompoundTag());

        CompoundTag missingCenterOutIndex=new CompoundTag();
        missingCenterOutIndex.putBoolean("initialized",true);
        missingCenterOutIndex.putBoolean("centerOutScan",true);
        State quarantinedMissingCenterOutIndex=State.load(missingCenterOutIndex);

        if(repairedIndex.restoreIndex!=-1 || repairedIndex.restoreScan!=-1
            || !repairedIndex.isDirty()
            || reloadedIndex.restoreIndex!=-1 || reloadedIndex.restoreScan!=-1
            || repairedScan.restoreIndex!=-1 || repairedScan.restoreScan!=-1
            || !repairedScan.isDirty()
            || untouched.restoreIndex!=-1 || untouched.restoreScan!=-1
            || untouched.isDirty()
            || resumedPendingTerminal.restoreIndex!=1 || resumedPendingTerminal.restoreScan!=1
            || resumedPendingTerminal.restoreTicks!=0 || resumedPendingTerminal.restoredBlocks!=0
            || resumedPendingTerminal.skippedOccupied!=0 || resumedPendingTerminal.skippedInvalid!=0
            || !resumedPendingTerminal.isDirty()
            || reloadedPendingTerminal.restoreIndex!=1 || reloadedPendingTerminal.restoreScan!=1
            || reloadedPendingTerminal.restoreTicks!=0 || !reloadedPendingTerminal.restoring
            || accountedTerminal.restoreIndex!=-1 || accountedTerminal.restoreScan!=-1
            || accountedTerminal.restoreTicks!=110 || accountedTerminal.restoredBlocks!=1
            || accountedTerminal.skippedOccupied!=1 || accountedTerminal.isDirty()
            || !recoverySnapshotsFullyAccounted(2,1,1,0)
            || recoverySnapshotsFullyAccounted(2,1,0,0)
            || repairedWrongIndexType.restoreIndex!=1 || repairedWrongIndexType.restoreScan!=1
            || !repairedWrongIndexType.isDirty()
            || reloadedWrongIndexType.restoreIndex!=1 || reloadedWrongIndexType.restoreScan!=1
            || !normalizedWrongIndexType.contains("restoreIndex",Tag.TAG_INT)
            || repairedWrongScanType.restoreIndex!=1 || repairedWrongScanType.restoreScan!=1
            || !repairedWrongScanType.isDirty()
            || !normalizedWrongScanType.contains("restoreScan",Tag.TAG_INT)
            || quarantinedWrongCenterOutType.centerOutIndex!=CENTER_OUT_COLUMN_COUNT
            || !quarantinedWrongCenterOutType.finished || !quarantinedWrongCenterOutType.isDirty()
            || normalizedWrongCenterOutType.getInt("centerOutIndex")!=CENTER_OUT_COLUMN_COUNT
            || !normalizedWrongCenterOutType.getBoolean("finished")
            || quarantinedMissingCenterOutIndex.centerOutIndex!=CENTER_OUT_COLUMN_COUNT
            || !quarantinedMissingCenterOutIndex.finished || !quarantinedMissingCenterOutIndex.isDirty())
            throw new IllegalStateException("CI recovery cursor repair persistence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_CURSOR_REPAIR_PERSISTENCE_VERIFIED indexClamped=true scanClamped=true dirty=true roundTrip=true validUnchanged=true malformedIndexType=true malformedScanType=true malformedCenterOutType=true missingCenterOutIndex=true");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_TERMINAL_CURSOR_PENDING_SNAPSHOT_RECOVERY_VERIFIED pendingReplayed=true accountedTerminalPreserved=true countersReset=true dirty=true roundTrip=true");
    }

    private static void verifyDuplicateRecoverySnapshotQuarantine() {
        BlockPos duplicatePos=new BlockPos(4,64,5);
        ListTag snapshots=new ListTag();
        CompoundTag first=new CompoundTag();
        first.putLong("pos",duplicatePos.asLong());
        first.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        snapshots.add(first);
        CompoundTag conflicting=new CompoundTag();
        conflicting.putLong("pos",duplicatePos.asLong());
        conflicting.put("state",NbtUtils.writeBlockState(Blocks.DIRT.defaultBlockState()));
        snapshots.add(conflicting);

        CompoundTag input=new CompoundTag();
        input.putBoolean("restoring",true);
        input.putInt("edits",2);
        input.putInt("restoreIndex",1);
        input.putInt("restoreScan",1);
        input.put("snapshots",snapshots);
        State loaded=State.load(input);
        CompoundTag persisted=loaded.save(new CompoundTag());
        State roundTrip=State.load(persisted);
        ListTag savedSnapshots=persisted.getList("snapshots",Tag.TAG_COMPOUND);
        boolean oldestRecordKept=savedSnapshots.size()==2
            && savedSnapshots.getCompound(0).getLong("pos")==duplicatePos.asLong()
            && savedSnapshots.getCompound(0).getCompound("state")
                .equals(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        boolean duplicatePayloadQuarantined=savedSnapshots.size()==2
            && savedSnapshots.getCompound(1).isEmpty();
        boolean cursorPreserved=roundTrip.restoreIndex==1 && roundTrip.restoreScan==1;
        boolean activeRecoveryPreserved=roundTrip.restoring && !roundTrip.restored
            && roundTrip.snapshots.size()==2 && roundTrip.edits==2;
        boolean retainedBudgetCountsOnlyTrustedPayload=
            roundTrip.retainedSnapshotEstimateBytes==boundedSnapshotPayloadEstimate(first);
        if(!(oldestRecordKept && duplicatePayloadQuarantined && cursorPreserved
            && activeRecoveryPreserved && retainedBudgetCountsOnlyTrustedPayload
            && loaded.isDirty() && roundTrip.isDirty()))
            throw new IllegalStateException("CI duplicate recovery snapshot position quarantine regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_DUPLICATE_SNAPSHOT_POSITION_QUARANTINE_VERIFIED entries=2 retained=1 quarantined=1 oldestWins=true cursorPreserved=true budgetSafe=true roundTrip=true");
    }

    private static void verifyCompletedRecoveryStateQuarantine() {
        ListTag staleSnapshots=new ListTag();
        CompoundTag staleEntry=new CompoundTag();
        staleEntry.putLong("pos",new BlockPos(4,64,5).asLong());
        staleEntry.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        staleSnapshots.add(staleEntry);
        // A terminal save may also contain malformed payloads. They must be discarded
        // before per-entry validation so old corruption cannot affect a stabilized world.
        CompoundTag malformedStaleEntry=new CompoundTag();
        malformedStaleEntry.putString("pos","not-a-long");
        malformedStaleEntry.putString("state","not-a-compound");
        staleSnapshots.add(malformedStaleEntry);

        CompoundTag conflictingFlags=new CompoundTag();
        conflictingFlags.putBoolean("restoring",true);
        conflictingFlags.putBoolean("restored",true);
        conflictingFlags.putBoolean("finished",false);
        conflictingFlags.putInt("edits",2);
        conflictingFlags.putInt("restoreIndex",0);
        conflictingFlags.putInt("restoreScan",0);
        conflictingFlags.put("snapshots",staleSnapshots.copy());
        State normalized=State.load(conflictingFlags);
        CompoundTag normalizedTag=normalized.save(new CompoundTag());
        State reloaded=State.load(normalizedTag);

        CompoundTag completedWithStaleSnapshots=new CompoundTag();
        completedWithStaleSnapshots.putBoolean("restored",true);
        completedWithStaleSnapshots.putBoolean("finished",true);
        completedWithStaleSnapshots.putInt("edits",2);
        completedWithStaleSnapshots.putInt("restoreIndex",0);
        completedWithStaleSnapshots.putInt("restoreScan",0);
        completedWithStaleSnapshots.put("snapshots",staleSnapshots.copy());
        State staleTerminal=State.load(completedWithStaleSnapshots);

        CompoundTag completedWithMalformedContainer=new CompoundTag();
        completedWithMalformedContainer.putBoolean("restored",true);
        completedWithMalformedContainer.putBoolean("finished",true);
        completedWithMalformedContainer.putString("snapshots","not-a-list");
        State malformedTerminal=State.load(completedWithMalformedContainer);

        boolean normalizedTerminal=normalized.restored && !normalized.restoring && normalized.finished
            && normalized.snapshots.isEmpty() && normalized.restoreIndex==-1 && normalized.restoreScan==-1
            && normalized.edits==2 && normalized.skippedInvalid==0
            && normalized.retainedSnapshotEstimateBytes==0L && normalized.isDirty();
        boolean roundTripTerminal=reloaded.restored && !reloaded.restoring && reloaded.finished
            && reloaded.snapshots.isEmpty() && reloaded.restoreIndex==-1 && reloaded.restoreScan==-1
            && reloaded.edits==2 && reloaded.skippedInvalid==0
            && reloaded.retainedSnapshotEstimateBytes==0L && !reloaded.isDirty();
        boolean stalePayloadQuarantined=staleTerminal.restored && !staleTerminal.restoring
            && staleTerminal.finished && staleTerminal.snapshots.isEmpty()
            && staleTerminal.restoreIndex==-1 && staleTerminal.restoreScan==-1
            && staleTerminal.edits==2 && staleTerminal.skippedInvalid==0
            && staleTerminal.retainedSnapshotEstimateBytes==0L && staleTerminal.isDirty();
        boolean malformedContainerQuarantined=malformedTerminal.restored && !malformedTerminal.restoring
            && malformedTerminal.finished && malformedTerminal.snapshots.isEmpty()
            && malformedTerminal.skippedInvalid==0 && malformedTerminal.retainedSnapshotEstimateBytes==0L
            && malformedTerminal.isDirty();
        if(!(normalizedTerminal && roundTripTerminal && stalePayloadQuarantined
            && malformedContainerQuarantined))
            throw new IllegalStateException("CI completed recovery stale-snapshot quarantine regression");
        LOGGER.info("BLACK_FREQUENCY_TEST COMPLETED_RECOVERY_STALE_SNAPSHOT_QUARANTINE_VERIFIED conflictingFlags=true staleSnapshots=true malformedContainer=true terminalPreserved=true dirty=true roundTrip=true");
    }

    private static void verifyInvalidSnapshotWarningRateLimit() {
        State state=new State();
        int warnings=0;
        for(int attempt=0;attempt<1000;attempt++)
            if(markInvalidSnapshotWarningLogged(state)) warnings++;
        if(warnings!=1)
            throw new IllegalStateException("CI invalid recovery snapshot warning rate limit regression: "+warnings);

        State reloaded=State.load(state.save(new CompoundTag()));
        if(markInvalidSnapshotWarningLogged(reloaded))
            throw new IllegalStateException("CI invalid recovery snapshot warning flag was not persisted");
        State legacy=State.load(new CompoundTag());
        if(!markInvalidSnapshotWarningLogged(legacy))
            throw new IllegalStateException("CI legacy recovery state must default to an unlogged warning");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_INVALID_SNAPSHOT_WARNING_RATE_LIMIT_VERIFIED attempts=1000 warnings=1 persisted=true legacyDefault=false");
    }

    /** Overflow-safe 3D audience filter shared by recovery effects and titles. */
    private static boolean withinRecoveryAudience(BlockPos player, int anchorX, int anchorY, int anchorZ) {
        long dx=(long)player.getX()-anchorX;
        long dy=(long)player.getY()-anchorY;
        long dz=(long)player.getZ()-anchorZ;
        if(Math.abs(dx)>128L || Math.abs(dy)>128L || Math.abs(dz)>128L) return false;
        return dx*dx+dy*dy+dz*dz<=128L*128L;
    }

    private static void verifyRecoveryAudienceBounds() {
        if(!withinRecoveryAudience(BlockPos.ZERO,0,0,0)
            || !withinRecoveryAudience(new BlockPos(128,0,0),0,0,0)
            || withinRecoveryAudience(new BlockPos(128,1,0),0,0,0)
            || withinRecoveryAudience(new BlockPos(0,129,0),0,0,0)
            || withinRecoveryAudience(new BlockPos(0,0,-129),0,0,0)
            // Diagonal corners outside the sphere must not pass the axis checks.
            || withinRecoveryAudience(new BlockPos(100,100,0),0,0,0)
            || !withinRecoveryAudience(new BlockPos(64,64,64),0,0,0)
            // Extreme vertical separation must not overflow or wrap into range.
            || withinRecoveryAudience(new BlockPos(0,Integer.MAX_VALUE,0),0,Integer.MIN_VALUE,0)
            || withinRecoveryAudience(new BlockPos(Integer.MAX_VALUE,0,0),Integer.MIN_VALUE,0,0))
            throw new IllegalStateException("CI recovery audience 3D radius regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_AUDIENCE_BOUNDS_VERIFIED");
    }

    /** Recovery vortex shrinks monotonically as pending terrain snapshots decrease. */
    private static double recoveryVortexContraction(int restoreIndex, int snapshotCount) {
        if(snapshotCount<=0) return 0.75D;
        double remaining=Math.max(0.0D,Math.min(1.0D,
            ((double)restoreIndex+1.0D)/snapshotCount));
        return 0.75D+0.25D*remaining;
    }

    /**
     * Reuse the persisted restoration clock so the gravity tail resumes at the
     * same strength after a server restart. The scale is always finite and bounded.
     */
    private static boolean shouldApplyRestorationGravityFade(int finaleTicks,int restoreTicks) {
        return finaleTicks>85 && restoreTicks>=0
            && restoreTicks<RESTORE_GRAVITY_FADE_TICKS;
    }

    private static double restorationGravityScale(int restoreTicks) {
        if(restoreTicks<=0) return 1.0D;
        double progress=Math.min(restoreTicks,RESTORE_GRAVITY_FADE_TICKS)
            /(double)RESTORE_GRAVITY_FADE_TICKS;
        // Cubic smoothstep softens both ends of the gravity tail. The anomaly
        // remains perceptible at the start, then releases the player without
        // an abrupt final impulse drop; endpoints and midpoint stay unchanged.
        double eased=progress*progress*(3.0D-2.0D*progress);
        return 1.0D-eased;
    }

    private static void verifyGravityAnomalyRadialFade() {
        double[] radii={0.0D,RADIUS,RADIUS+4.0D,RADIUS+8.0D,
            RADIUS+12.0D,RADIUS+14.0D,GRAVITY_ANOMALY_OUTER_RADIUS};
        double[] expected={1.0D,1.0D,.896484375D,.5D,.103515625D,.01605224609375D,0.0D};
        for(int i=0;i<radii.length;i++) {
            double actual=gravityAnomalyRadialFade(radii[i]*radii[i]);
            if(!Double.isFinite(actual) || Math.abs(actual-expected[i])>1.0E-12D)
                throw new IllegalStateException("CI gravity anomaly radial falloff profile regression at radius "+radii[i]);
        }
        double previous=1.0D;
        for(int i=0;i<=256;i++) {
            double radius=GRAVITY_ANOMALY_OUTER_RADIUS*i/256.0D;
            double actual=gravityAnomalyRadialFade(radius*radius);
            if(!Double.isFinite(actual) || actual<0.0D || actual>1.0D
                || actual>previous+1.0E-12D)
                throw new IllegalStateException("CI gravity anomaly radial falloff monotonicity regression");
            previous=actual;
        }
        double linearParticleCutoff=.125D;
        double smoothParticleCutoff=gravityAnomalyFade(linearParticleCutoff);
        boolean invalidNeutral=gravityAnomalyRadialFade(-1.0D)==0.0D
            && gravityAnomalyRadialFade(Double.NaN)==0.0D
            && gravityAnomalyRadialFade(Double.POSITIVE_INFINITY)==0.0D
            && gravityAnomalyRadialFade(Double.NEGATIVE_INFINITY)==0.0D;
        if(!invalidNeutral
            || Math.abs(GRAVITY_ANOMALY_PARTICLE_MIN_FADE-smoothParticleCutoff)>1.0E-12D
            || gravityAnomalyRadialFade(Math.nextUp(GRAVITY_ANOMALY_OUTER_RADIUS
                *GRAVITY_ANOMALY_OUTER_RADIUS))!=0.0D)
            throw new IllegalStateException("CI gravity anomaly radial falloff boundary/particle regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ANOMALY_FIELD_RADIAL_FALLOFF_VERIFIED innerRadius={} outerRadius={} width={} curve=quintic c2Endpoints=true midpoint=0.5 monotonic=true invalidNeutral=true particleThreshold={}",
            (double)RADIUS,GRAVITY_ANOMALY_OUTER_RADIUS,GRAVITY_ANOMALY_FADE_WIDTH,
            GRAVITY_ANOMALY_PARTICLE_MIN_FADE);
    }

    private static void verifyRestorationGravityFade() {
        int[] ticks={-1,0,10,20,30,40,Integer.MAX_VALUE};
        double[] expected={1.0D,1.0D,.84375D,.5D,.15625D,0.0D,0.0D};
        for(int i=0;i<ticks.length;i++) {
            double actual=restorationGravityScale(ticks[i]);
            if(!Double.isFinite(actual) || actual<0.0D || actual>1.0D
                || Math.abs(actual-expected[i])>1.0E-9D)
                throw new IllegalStateException("CI restoration gravity fade profile regression: "+actual);
        }
        double previous=1.0D;
        for(int tick=0;tick<=RESTORE_GRAVITY_FADE_TICKS;tick++) {
            double actual=restorationGravityScale(tick);
            if(actual>previous+1.0E-9D)
                throw new IllegalStateException("CI restoration gravity fade is not monotonic");
            previous=actual;
        }
        double firstStep=1.0D-restorationGravityScale(1);
        double lastStep=restorationGravityScale(RESTORE_GRAVITY_FADE_TICKS-1);
        if(firstStep>=1.0D/RESTORE_GRAVITY_FADE_TICKS
            || lastStep>=1.0D/RESTORE_GRAVITY_FADE_TICKS
            || Math.abs(restorationGravityScale(10)+restorationGravityScale(30)-1.0D)>1.0E-9D)
            throw new IllegalStateException("CI restoration gravity fade easing regression");
        int scheduledUpdates=0;
        for(int tick=-1;tick<=RESTORE_GRAVITY_FADE_TICKS+1;tick++) {
            boolean expectedUpdate=tick>=0 && tick<RESTORE_GRAVITY_FADE_TICKS;
            boolean actual=shouldApplyRestorationGravityFade(86,tick);
            if(actual!=expectedUpdate)
                throw new IllegalStateException("CI restoration gravity fade cadence regression at tick "+tick);
            if(actual) scheduledUpdates++;
        }
        if(scheduledUpdates!=RESTORE_GRAVITY_FADE_TICKS
            || shouldApplyRestorationGravityFade(85,0)
            || shouldApplyRestorationGravityFade(Integer.MIN_VALUE,0))
            throw new IllegalStateException("CI restoration gravity fade start/end gate regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_CADENCE_VERIFIED ticks={} intervalTicks=1 updates={}",
            RESTORE_GRAVITY_FADE_TICKS,scheduledUpdates);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_GRAVITY_FADE_PROFILE_VERIFIED ticks=40 start=1.0 midpoint=0.5 end=0.0 monotonic=true");
    }

    private static void verifyRecoveryVortexContraction() {
        double[][] cases={
            {9,10,1.0D}, {4,10,0.875D}, {-1,10,0.75D},
            {Integer.MAX_VALUE,10,1.0D}, {Integer.MIN_VALUE,10,0.75D},
            {-1,0,0.75D}
        };
        for(double[] sample:cases) {
            double actual=recoveryVortexContraction((int)sample[0],(int)sample[1]);
            if(!Double.isFinite(actual) || Math.abs(actual-sample[2])>1.0E-9D)
                throw new IllegalStateException("CI recovery vortex contraction regression: "+actual);
        }
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_VORTEX_CONTRACTION_VERIFIED");
    }

    /** Pure interaction gate, also exercised by disposable-world CI. */
    private static boolean mayActivateBeacon(net.minecraft.world.InteractionHand hand,
            boolean alive, boolean spectator, double distanceSquared,
            boolean canceled, net.minecraftforge.eventbus.api.Event.Result useBlock,
            net.minecraftforge.eventbus.api.Event.Result useItem) {
        return !canceled && useBlock!=net.minecraftforge.eventbus.api.Event.Result.DENY
            && useItem!=net.minecraftforge.eventbus.api.Event.Result.DENY
            && hand==net.minecraft.world.InteractionHand.MAIN_HAND && alive && !spectator
            && withinBeaconInteractionReach(distanceSquared);
    }

    private static boolean mayRecoverCiBeaconAimAfterMiss(
            boolean ciTest,boolean playerBeaconTest,boolean naturalBeaconTest,
            boolean started,boolean restoring,boolean restored,boolean hasPendingSnapshots,
            net.minecraft.world.InteractionHand hand,boolean alive,boolean removed,boolean spectator,
            double distanceSquared,boolean canceled,
            net.minecraftforge.eventbus.api.Event.Result useBlock,
            net.minecraftforge.eventbus.api.Event.Result useItem) {
        return ciTest && playerBeaconTest && naturalBeaconTest && started
            && !restoring && !restored && hasPendingSnapshots && !removed
            && mayActivateBeacon(hand,alive,spectator,distanceSquared,canceled,useBlock,useItem);
    }

    private static boolean recoverCiBeaconAimAfterMiss(ServerLevel level,
            PlayerInteractEvent.RightClickBlock event,State s,BlockPos clicked) {
        if(s.naturalBeaconPos==Long.MIN_VALUE
            || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return false;
        BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
        if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,beacon)) return false;
        double distanceSquared=eyeDistanceSquaredToBlock(player.getEyePosition(),beacon);
        if(!mayRecoverCiBeaconAimAfterMiss(CI_TEST,
            Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest"),
            Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest"),
            s.started,s.restoring,s.restored,!s.snapshots.isEmpty(),
            event.getHand(),player.isAlive(),player.isRemoved(),player.isSpectator(),
            distanceSquared,event.isCanceled(),event.getUseBlock(),event.getUseItem())) return false;
        // A real client click may land on a nearby pad block after camera
        // automation. Re-aim only this disposable CI player at the physical
        // beacon; the next mouse click must still trigger the normal block event.
        player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
            net.minecraft.world.phys.Vec3.atCenterOf(beacon));
        player.connection.teleport(player.getX(),player.getY(),player.getZ(),
            player.getYRot(),player.getXRot());
        player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.fallDistance=0.0F;
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_AIM_RECOVERED_AFTER_MISS clicked={} target={}",
            clicked,beacon);
        return true;
    }

    // Idle objective hints must stop as soon as the physical beacon starts recovery.
    // Otherwise an accelerated CI tick (2400 ticks per server tick) or a coincident
    // 200-tick hint can overwrite the activation/progress action bar.
    private static boolean shouldSendIdleBeaconHint(boolean restoring,boolean restored,int finaleTicks) {
        return !restoring && !restored && finaleTicks%200==0;
    }

    // A nearby idle beacon needs only a restrained once-per-second signal.
    // Keep the check independent of chunk state; callers already require a loaded beacon.
    private static boolean shouldEmitIdleBeaconParticles(int finaleTicks) {
        return finaleTicks>0 && finaleTicks%IDLE_BEACON_PULSE_INTERVAL_TICKS==0;
    }

    private static boolean withinIdleBeaconParticleAudience(double distanceSquared) {
        return Double.isFinite(distanceSquared)
            && distanceSquared>=0.0D
            && distanceSquared<IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR;
    }

    private static void verifyIdleBeaconParticleAudienceBounds() {
        boolean origin=withinIdleBeaconParticleAudience(0.0D);
        boolean justInside=withinIdleBeaconParticleAudience(
            Math.nextDown(IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR));
        boolean boundaryRejected=!withinIdleBeaconParticleAudience(
            IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR);
        boolean outsideRejected=!withinIdleBeaconParticleAudience(
            Math.nextUp(IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR));
        boolean invalidRejected=!withinIdleBeaconParticleAudience(-1.0D)
            && !withinIdleBeaconParticleAudience(Double.NaN)
            && !withinIdleBeaconParticleAudience(Double.POSITIVE_INFINITY)
            && !withinIdleBeaconParticleAudience(Double.NEGATIVE_INFINITY);
        if(!(origin && justInside && boundaryRejected && outsideRejected && invalidRejected))
            throw new IllegalStateException("CI idle beacon particle audience range regression");
        LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance={} maxDistanceSquared={} strictBoundary=true invalidRejected=true",
            IDLE_BEACON_PARTICLE_PACKET_RANGE,IDLE_BEACON_PARTICLE_PACKET_RANGE_SQR);
    }

    private static boolean shouldEmitRestoreHelix(int restoreTicks) {
        return restoreTicks>0 && restoreTicks%RESTORE_HELIX_INTERVAL_TICKS==0;
    }

    private static boolean shouldEmitRestoreBlockCue(int restoredBlocks) {
        return restoredBlocks>0 && restoredBlocks%RESTORE_BLOCK_CUE_INTERVAL==0;
    }

    private static boolean shouldEmitRestoreAudiencePulse(int restoreTicks) {
        // Give a newly activated beacon one immediate pulse; later pulses stay rate-limited.
        return restoreTicks==1 || (restoreTicks>0 && restoreTicks%RESTORE_RING_INTERVAL_TICKS==0);
    }

    private static boolean withinRestoreParticleAudience(double distanceSquared) {
        return Double.isFinite(distanceSquared)
            && distanceSquared>=0.0D
            && distanceSquared<RESTORE_PARTICLE_VIEW_DISTANCE_SQR;
    }

    private static void verifyRestoreParticleAudienceBounds() {
        boolean origin=withinRestoreParticleAudience(0.0D);
        boolean justInside=withinRestoreParticleAudience(Math.nextDown(RESTORE_PARTICLE_VIEW_DISTANCE_SQR));
        boolean boundaryRejected=!withinRestoreParticleAudience(RESTORE_PARTICLE_VIEW_DISTANCE_SQR);
        boolean outsideRejected=!withinRestoreParticleAudience(Math.nextUp(RESTORE_PARTICLE_VIEW_DISTANCE_SQR));
        boolean invalidRejected=!withinRestoreParticleAudience(-1.0D)
            && !withinRestoreParticleAudience(Double.NaN)
            && !withinRestoreParticleAudience(Double.POSITIVE_INFINITY)
            && !withinRestoreParticleAudience(Double.NEGATIVE_INFINITY);
        if(!(origin && justInside && boundaryRejected && outsideRejected && invalidRejected))
            throw new IllegalStateException("CI restore particle audience range regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PARTICLE_AUDIENCE_BOUNDS_VERIFIED maxDistance={} maxDistanceSquared={} packetRange={} effectEnvelope={} strictBoundary=true invalidRejected=true",
            RESTORE_PARTICLE_VIEW_DISTANCE,RESTORE_PARTICLE_VIEW_DISTANCE_SQR,
            RESTORE_PARTICLE_PACKET_RANGE,RESTORE_PARTICLE_EFFECT_ENVELOPE);
    }

    private static boolean shouldScanRestorePlayers(int restoreTicks,boolean beaconPresent) {
        return beaconPresent && restoreTicks>0
            && (restoreTicks==1 || restoreTicks%RESTORE_PLAYER_SCAN_INTERVAL_TICKS==0);
    }

    // A corrupt save can clamp restoreTicks to MAX_VALUE, where a saturating clock
    // never reaches another modulo boundary. Keep visual and persistence cadence alive
    // from the world's monotonic tick without changing normal restoration timing.
    private static int restorationCadenceTick(int restoreTicks,long gameTime) {
        if(restoreTicks<Integer.MAX_VALUE) return Math.max(0,restoreTicks);
        return (int)Math.floorMod(gameTime,(long)RESTORE_CADENCE_PERIOD_TICKS)+1;
    }

    // Keep vortex rotation continuous even while the bounded cadence phase wraps every 40 ticks.
    private static long restorationAnimationTick(int restoreTicks,long gameTime) {
        return restoreTicks<Integer.MAX_VALUE ? Math.max(0L,(long)restoreTicks) : Math.max(0L,gameTime);
    }

    // Accumulate viewer state without short-circuiting the real player iteration:
    // every player must be visited because menu closure is a side effect of this pass.
    private static final class RestorePlayerScanState {
        private boolean hasRingViewer;
        private boolean hasStabilizationViewer;
        private int playersVisited;
        private int beaconMenusClosed;

        private void reset() {
            hasRingViewer=false;
            hasStabilizationViewer=false;
            playersVisited=0;
            beaconMenusClosed=0;
        }

        private void observe(boolean beaconMenuClosed,boolean eligible,
                boolean ringViewer,boolean stabilizationViewer) {
            playersVisited++;
            if(beaconMenuClosed) beaconMenusClosed++;
            if(!eligible) return;
            hasRingViewer|=ringViewer;
            hasStabilizationViewer|=stabilizationViewer;
        }
    }

    private static void verifyRestorePlayerScanFullPass() {
        int scanPasses=0;
        for(int tick=1;tick<=RESTORE_GRAVITY_FADE_TICKS;tick++) {
            boolean expected=tick==1 || tick%RESTORE_PLAYER_SCAN_INTERVAL_TICKS==0;
            boolean actual=shouldScanRestorePlayers(tick,true);
            if(actual!=expected)
                throw new IllegalStateException("CI restore player scan cadence regression at tick "+tick);
            if(actual) scanPasses++;
        }
        if(scanPasses!=11)
            throw new IllegalStateException("CI restore player scan cadence count regression: "+scanPasses);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_ALLOCATION_CADENCE_VERIFIED ticks={} intervalTicks={} scanPasses={}",
            RESTORE_GRAVITY_FADE_TICKS,RESTORE_PLAYER_SCAN_INTERVAL_TICKS,scanPasses);
        State scanOwner=new State();
        RestorePlayerScanState scan=scanOwner.restorePlayerScan;
        // The first player satisfies the ring audience. A later player still
        // has a beacon menu and is the stabilization viewer; both must be seen.
        scan.observe(false,true,true,false);
        scan.observe(true,true,false,true);
        if(scan.playersVisited!=2 || scan.beaconMenusClosed!=1
            || !scan.hasRingViewer || !scan.hasStabilizationViewer)
            throw new IllegalStateException("CI restore player scan stopped before closing later beacon menus");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_FULL_PASS_VERIFIED fixturePlayers=2 menusAfterViewer=1 fullPass=true");
        RestorePlayerScanState reused=scanOwner.restorePlayerScan;
        reused.reset();
        if(reused!=scan || scanOwner.restorePlayerScan!=scan
            || scan.playersVisited!=0 || scan.beaconMenusClosed!=0
            || scan.hasRingViewer || scan.hasStabilizationViewer)
            throw new IllegalStateException("CI restore player scan reuse/reset regression");
        scan.observe(false,true,true,false);
        scan.observe(true,true,false,true);
        if(scan.playersVisited!=2 || scan.beaconMenusClosed!=1
            || !scan.hasRingViewer || !scan.hasStabilizationViewer)
            throw new IllegalStateException("CI restore player scan was not reusable after reset");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_REUSE_VERIFIED sameInstance=true staleStateCleared=true");
        verifyRestorePositionCursorReuse();
    }

    private static void verifyRestorePositionCursorReuse() {
        State owner=new State();
        BlockPos.MutableBlockPos cursor=owner.restoreCursor;
        BlockPos[] samples={
            new BlockPos(-30_000_000,-64,30_000_000),
            new BlockPos(0,319,0),
            new BlockPos(30_000_000,0,-30_000_000)
        };
        for(BlockPos expected:samples) {
            long packed=expected.asLong();
            cursor.set(BlockPos.getX(packed),BlockPos.getY(packed),BlockPos.getZ(packed));
            if(owner.restoreCursor!=cursor || cursor.getX()!=expected.getX()
                || cursor.getY()!=expected.getY() || cursor.getZ()!=expected.getZ())
                throw new IllegalStateException("CI restore snapshot cursor decode/reuse regression");
        }
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_POSITION_CURSOR_REUSE_VERIFIED sameInstance=true packedCoordinates=true transient=true");
    }

    private static void verifyRestoreCadenceSaturationFallback() {
        boolean regularClock=restorationCadenceTick(23,7L)==23;
        boolean negativeClock=restorationCadenceTick(-1,7L)==0;
        boolean animationClockContinuous=restorationAnimationTick(Integer.MAX_VALUE,39L)==39L
            && restorationAnimationTick(Integer.MAX_VALUE,40L)==40L
            && restorationAnimationTick(23,7L)==23L;
        // World time is a long persisted by Minecraft. Keep the fallback well-defined
        // even if a damaged or manually edited save contains an extreme clock value.
        boolean extremeClockBounds=
            restorationCadenceTick(Integer.MAX_VALUE,Long.MIN_VALUE)
                ==(int)Math.floorMod(Long.MIN_VALUE,(long)RESTORE_CADENCE_PERIOD_TICKS)+1
            && restorationCadenceTick(Integer.MAX_VALUE,Long.MAX_VALUE)
                ==(int)Math.floorMod(Long.MAX_VALUE,(long)RESTORE_CADENCE_PERIOD_TICKS)+1
            && restorationAnimationTick(Integer.MAX_VALUE,Long.MIN_VALUE)==0L
            && restorationAnimationTick(Integer.MAX_VALUE,Long.MAX_VALUE)==Long.MAX_VALUE;
        int scans=0,pulses=0,helix=0,overlays=0,checkpoints=0;
        for(long gameTick=0;gameTick<RESTORE_CADENCE_PERIOD_TICKS;gameTick++) {
            int cadence=restorationCadenceTick(Integer.MAX_VALUE,gameTick);
            int expected=(int)gameTick+1;
            if(cadence!=expected)
                throw new IllegalStateException("CI saturated restoration cadence phase regression at game tick "+gameTick);
            if(shouldScanRestorePlayers(cadence,true)) scans++;
            if(shouldEmitRestoreAudiencePulse(cadence)) pulses++;
            if(shouldEmitRestoreHelix(cadence)) helix++;
            if(cadence%10==0) overlays++;
            if(cadence%40==0) checkpoints++;
        }
        boolean commonPeriod=RESTORE_CADENCE_PERIOD_TICKS%RESTORE_PLAYER_SCAN_INTERVAL_TICKS==0
            && RESTORE_CADENCE_PERIOD_TICKS%RESTORE_HELIX_INTERVAL_TICKS==0
            && RESTORE_CADENCE_PERIOD_TICKS%RESTORE_RING_INTERVAL_TICKS==0
            && RESTORE_CADENCE_PERIOD_TICKS%10==0
            && RESTORE_CADENCE_PERIOD_TICKS%40==0;
        if(!(regularClock && negativeClock && animationClockContinuous && extremeClockBounds && commonPeriod
            && scans==11 && pulses==3 && helix==5 && overlays==4 && checkpoints==1))
            throw new IllegalStateException("CI saturated restoration cadence fallback regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_CADENCE_SATURATION_FALLBACK_VERIFIED periodTicks={} scans={} pulses={} helix={} overlays={} checkpoints={} animationContinuous={} extremeClockBounds={}",
            RESTORE_CADENCE_PERIOD_TICKS,scans,pulses,helix,overlays,checkpoints,animationClockContinuous,extremeClockBounds);
    }

    private static void verifyGravityAnomalyParticlePalette() {
        if(ANOMALY_VIOLET_DUST!=RESTORE_VIOLET_DUST
            || ANOMALY_VIOLET_ACCENT_DUST!=RESTORE_VIOLET_ACCENT_DUST
            || !hasDustColor(ANOMALY_VIOLET_DUST,.34F,.08F,.78F)
            || !hasDustColor(ANOMALY_VIOLET_ACCENT_DUST,.30F,.14F,.88F)
            || ANOMALY_VIOLET_DUST.getScale()<=0.0F
            || ANOMALY_VIOLET_DUST.getScale()>=ANOMALY_VIOLET_ACCENT_DUST.getScale()
            || ANOMALY_VIOLET_ACCENT_DUST.getScale()>.85F)
            throw new IllegalStateException("CI gravity anomaly Dust palette regression");
        LOGGER.info("BLACK_FREQUENCY_TEST GRAVITY_ANOMALY_PARTICLE_PALETTE_VERIFIED base=deep-violet accent=bright-violet cached=true");
    }

    private static void verifyIdleBeaconParticlePalette() {
        if(!hasDustColor(BEACON_VIOLET_DUST,.48F,.14F,.98F)
            || Math.abs(BEACON_VIOLET_DUST.getScale()-.90F)>1.0E-6F
            || IDLE_BEACON_PULSE_INTERVAL_TICKS!=20
            || IDLE_BEACON_PULSE_PARTICLE_COUNT!=6
            || !Double.isFinite(IDLE_BEACON_PARTICLE_SPREAD_XZ)
            || !Double.isFinite(IDLE_BEACON_PARTICLE_SPREAD_Y)
            || !Double.isFinite(IDLE_BEACON_PARTICLE_SPEED)
            || IDLE_BEACON_PARTICLE_SPREAD_XZ<.25D
            || IDLE_BEACON_PARTICLE_SPREAD_XZ>.40D
            || IDLE_BEACON_PARTICLE_SPREAD_Y<.05D
            || IDLE_BEACON_PARTICLE_SPREAD_Y>=.12D
            || IDLE_BEACON_PARTICLE_SPREAD_Y>=IDLE_BEACON_PARTICLE_SPREAD_XZ
            || IDLE_BEACON_PARTICLE_SPEED<0.0D
            || IDLE_BEACON_PARTICLE_SPEED>.02D)
            throw new IllegalStateException("CI idle beacon violet Dust palette/halo regression");
        LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_VIOLET_PALETTE_VERIFIED red=0.48 green=0.14 blue=0.98 scale=0.9 count=6 intervalTicks=20 cached=true");
        LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_HALO_PROFILE_VERIFIED spreadXZ={} spreadY={} speed={} flattened=true",
            IDLE_BEACON_PARTICLE_SPREAD_XZ,IDLE_BEACON_PARTICLE_SPREAD_Y,IDLE_BEACON_PARTICLE_SPEED);
        verifyIdleBeaconParticleAudienceBounds();
    }

    private static Component restorationCompletionMessage(int restoredBlocks,int skippedOccupied,int skippedInvalid,
            int snapshotOverflowEntries,int snapshotMissingEntries) {
        if(restoredBlocks<0 || skippedOccupied<0 || skippedInvalid<0
            || snapshotOverflowEntries<0 || snapshotMissingEntries<0)
            throw new IllegalArgumentException("Recovery completion counters cannot be negative");
        long skipped=(long)skippedOccupied+(long)skippedInvalid
            +(long)snapshotOverflowEntries+(long)snapshotMissingEntries;
        String status=skipped==0L ? "✦ МИР СТАБИЛИЗИРОВАН" : "✦ ВОССТАНОВЛЕНИЕ НЕПОЛНО";
        Component details=Component.literal(" — восстановлено "+restoredBlocks+" блоков; пропущено "+skipped)
            .withStyle(style -> style.withColor(TextColor.fromRgb(0xC8C2D4)));
        return Component.literal(status)
            .withStyle(style -> style.withColor(TextColor.fromRgb(RECOVERY_UI_VIOLET_RGB)))
            .append(details);
    }

    private static ListTag buildCiRecoverySnapshotList(int count) {
        if(count<0 || count>MAX_EDITS)
            throw new IllegalArgumentException("CI recovery snapshot fixture count is outside the retention cap");
        ListTag snapshots=new ListTag();
        CompoundTag stoneState=new CompoundTag();
        stoneState.putString("Name","minecraft:stone");
        int added=0;
        for(int y=64;y<320 && added<count;y++) {
            for(int x=-RADIUS;x<=RADIUS && added<count;x++) {
                for(int z=-RADIUS;z<=RADIUS && added<count;z++) {
                    if(x*x+z*z>RADIUS*RADIUS) continue;
                    CompoundTag entry=new CompoundTag();
                    entry.putLong("pos",new BlockPos(x,y,z).asLong());
                    entry.put("state",stoneState.copy());
                    snapshots.add(entry);
                    added++;
                }
            }
        }
        if(added!=count)
            throw new IllegalStateException("CI recovery snapshot fixture could not produce unique in-radius positions");
        return snapshots;
    }

    private static void verifyRecoveryUiPalette() {
        Component success=restorationCompletionMessage(160,0,0,0,0);
        Component partial=restorationCompletionMessage(159,2,1,0,0);

        // Exercise the real NBT retention path: 95,000 valid snapshots are retained,
        // while the malformed 12-entry tail is counted but never parsed or applied.
        ListTag overflowSnapshots=buildCiRecoverySnapshotList(MAX_EDITS);
        for(int i=0;i<12;i++) overflowSnapshots.add(new CompoundTag());
        CompoundTag overflowStateTag=new CompoundTag();
        overflowStateTag.putInt("x",0);
        overflowStateTag.putInt("z",0);
        overflowStateTag.putBoolean("started",true);
        overflowStateTag.putBoolean("initialized",true);
        overflowStateTag.putBoolean("restoring",true);
        overflowStateTag.putInt("edits",MAX_EDITS);
        overflowStateTag.putInt("restoreIndex",MAX_EDITS-1);
        overflowStateTag.putInt("restoreScan",MAX_EDITS-1);
        overflowStateTag.put("snapshots",overflowSnapshots);
        State overflowLoaded=State.load(overflowStateTag);
        State overflowReloaded=State.load(overflowLoaded.save(new CompoundTag()));
        int overflowFixture=overflowSnapshots.size()-MAX_EDITS;
        Component overflowPartial=restorationCompletionMessage(160,0,0,
            overflowReloaded.snapshotOverflowEntries,overflowReloaded.snapshotMissingEntries);

        // A truncated list can be smaller than the persisted edit counter even when it
        // never reaches MAX_EDITS. Keep that missing count across a second NBT load too.
        CompoundTag missingStateTag=new CompoundTag();
        missingStateTag.putInt("x",0);
        missingStateTag.putInt("z",0);
        missingStateTag.putBoolean("started",true);
        missingStateTag.putBoolean("initialized",true);
        missingStateTag.putBoolean("restoring",true);
        missingStateTag.putInt("edits",160);
        missingStateTag.putInt("restoreIndex",158);
        missingStateTag.putInt("restoreScan",158);
        missingStateTag.put("snapshots",buildCiRecoverySnapshotList(159));
        State missingLoaded=State.load(missingStateTag);
        State missingReloaded=State.load(missingLoaded.save(new CompoundTag()));
        Component missingPartial=restorationCompletionMessage(159,0,0,
            missingReloaded.snapshotOverflowEntries,missingReloaded.snapshotMissingEntries);

        CompoundTag combinedStateTag=missingStateTag.copy();
        combinedStateTag.putInt("snapshotOverflowEntries",12);
        State combinedLoaded=State.load(combinedStateTag);
        State combinedReloaded=State.load(combinedLoaded.save(new CompoundTag()));
        Component combinedPartial=restorationCompletionMessage(159,0,0,
            combinedReloaded.snapshotOverflowEntries,combinedReloaded.snapshotMissingEntries);

        boolean invalidRejected=false;
        try {
            restorationCompletionMessage(-1,0,0,0,0);
        } catch(IllegalArgumentException expected) {
            invalidRejected=true;
        }
        if(!hasRecoveryViolet(success) || !hasRecoveryViolet(partial)
            || !success.getString().contains("МИР СТАБИЛИЗИРОВАН")
            || !partial.getString().contains("ВОССТАНОВЛЕНИЕ НЕПОЛНО")
            || !partial.getString().contains("пропущено 3")
            || !hasRecoveryViolet(overflowPartial)
            || !overflowPartial.getString().contains("ВОССТАНОВЛЕНИЕ НЕПОЛНО")
            || !overflowPartial.getString().contains("пропущено 12")
            || overflowFixture!=12 || !overflowLoaded.isDirty()
            || overflowLoaded.snapshots.size()!=MAX_EDITS
            || overflowLoaded.snapshotOverflowEntries!=12
            || overflowLoaded.snapshotMissingEntries!=0
            || overflowLoaded.skippedInvalid!=0
            || overflowReloaded.snapshots.size()!=MAX_EDITS
            || overflowReloaded.snapshotOverflowEntries!=12
            || !hasRecoveryViolet(missingPartial)
            || !missingPartial.getString().contains("ВОССТАНОВЛЕНИЕ НЕПОЛНО")
            || !missingPartial.getString().contains("пропущено 1")
            || !missingLoaded.isDirty()
            || missingLoaded.edits!=160 || missingLoaded.snapshots.size()!=159
            || missingLoaded.snapshotMissingEntries!=1
            || missingReloaded.snapshotMissingEntries!=1
            || !hasRecoveryViolet(combinedPartial)
            || !combinedPartial.getString().contains("пропущено 13")
            || combinedReloaded.snapshotOverflowEntries!=12
            || combinedReloaded.snapshotMissingEntries!=1
            || restoreSnapshotScanBudget(false)!=48
            || restoreSnapshotScanBudget(true)!=1
            || boundedSnapshotOverflowEntries(Integer.MAX_VALUE,0)!=MAX_EDITS
            || boundedSnapshotOverflowEntries(-1,0)!=0
            || boundedSnapshotOverflowEntries(4,5)!=0
            || !invalidRejected)
            throw new IllegalStateException("CI recovery completion accounting must preserve overflow, missing snapshots and reject invalid counters");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_UI_PALETTE_VERIFIED rgb=5714c7 legacyFormatting=false");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_OVERFLOW_ACCOUNTING_VERIFIED cap={} excess={} retained={} total={} malformedTailIgnored=true persisted=true partialStatus=true bounded=true",
            MAX_EDITS,overflowReloaded.snapshotOverflowEntries,overflowReloaded.snapshots.size(),overflowSnapshots.size());
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_MISSING_ENTRIES_VERIFIED edits={} retained={} missing={} persisted=true partialStatus=true",
            missingReloaded.edits,missingReloaded.snapshots.size(),missingReloaded.snapshotMissingEntries);
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_COMBINED_LOSS_VERIFIED overflow={} missing={} totalSkipped=13",
            combinedReloaded.snapshotOverflowEntries,combinedReloaded.snapshotMissingEntries);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_SCAN_BUDGET_PROFILE production=48 deferredClient=1");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_COMPLETION_ACTIONBAR_ONLY_VERIFIED success=true partial=true titlePackets=false");
    }

    private static void verifyRestorationParticleProfile() {
        boolean ringInitial=shouldEmitRestoreAudiencePulse(1);
        boolean ringBoundary=shouldEmitRestoreAudiencePulse(RESTORE_RING_INTERVAL_TICKS);
        boolean ringBetween=!shouldEmitRestoreAudiencePulse(RESTORE_RING_INTERVAL_TICKS-1);
        boolean helixBoundary=shouldEmitRestoreHelix(RESTORE_HELIX_INTERVAL_TICKS);
        boolean helixBetween=!shouldEmitRestoreHelix(RESTORE_HELIX_INTERVAL_TICKS-1);
        boolean blockBoundary=shouldEmitRestoreBlockCue(RESTORE_BLOCK_CUE_INTERVAL);
        boolean blockBetween=!shouldEmitRestoreBlockCue(RESTORE_BLOCK_CUE_INTERVAL-1);
        boolean blockNext=shouldEmitRestoreBlockCue(RESTORE_BLOCK_CUE_INTERVAL*2);
        boolean invalidTicks=!shouldEmitRestoreHelix(0)
            && !shouldEmitRestoreHelix(-RESTORE_HELIX_INTERVAL_TICKS)
            && !shouldEmitRestoreAudiencePulse(0)
            && !shouldEmitRestoreAudiencePulse(-RESTORE_RING_INTERVAL_TICKS)
            && !shouldEmitRestoreBlockCue(0)
            && !shouldEmitRestoreBlockCue(-RESTORE_BLOCK_CUE_INTERVAL);
        boolean boundedCounts=RESTORE_RING_PARTICLE_COUNT==24
            && RESTORE_RISING_SPARK_PARTICLE_COUNT==4
            && RESTORE_HELIX_PARTICLE_COUNT==16
            && RESTORE_HELIX_PARTICLE_COUNT==RESTORE_HELIX_ANCHOR_COUNT*RESTORE_HELIX_MOTES_PER_ANCHOR
            && RESTORE_BLOCK_CUE_PARTICLE_COUNT==6
            && RESTORE_AUDIENCE_PARTICLE_COUNT==8;
        boolean cachedPalette=hasDustColor(FISSURE_VIOLET_DUST,.34F,.08F,.78F)
            && hasDustColor(FISSURE_VIOLET_ACCENT_DUST,.30F,.14F,.88F)
            && Math.abs(FISSURE_VIOLET_ACCENT_DUST.getScale()-1.05F)<1.0E-6F
            && hasDustColor(RESTORE_VIOLET_DUST,.34F,.08F,.78F)
            && hasDustColor(RESTORE_VIOLET_ACCENT_DUST,.30F,.14F,.88F);
        boolean boundedRestoreDust=RESTORE_VIOLET_DUST_SCALE>=.52F
            && RESTORE_VIOLET_DUST_SCALE<=.62F
            && RESTORE_VIOLET_DUST_SCALE<RESTORE_VIOLET_ACCENT_DUST_SCALE
            && RESTORE_VIOLET_ACCENT_DUST_SCALE>=.70F
            && RESTORE_VIOLET_ACCENT_DUST_SCALE<=.82F
            && RESTORE_AUDIENCE_SPREAD_XZ>0.0D && RESTORE_AUDIENCE_SPREAD_XZ<=1.0D
            && RESTORE_AUDIENCE_SPREAD_Y>0.0D && RESTORE_AUDIENCE_SPREAD_Y<=.75D;
        double minimumRingOffset=RESTORE_RING_HEIGHT_OFFSET-RESTORE_RING_VERTICAL_WAVE;
        double maximumRingOffset=RESTORE_RING_HEIGHT_OFFSET+RESTORE_RING_OUTER_HEIGHT_OFFSET
            +RESTORE_RING_VERTICAL_WAVE;
        boolean ringClearsBeaconBlock=Double.isFinite(minimumRingOffset)
            && Double.isFinite(maximumRingOffset)
            && minimumRingOffset>1.0D && maximumRingOffset<=2.0D
            && RESTORE_RING_HEIGHT_OFFSET>1.0D && RESTORE_RING_HEIGHT_OFFSET<=1.5D
            && RESTORE_RING_OUTER_HEIGHT_OFFSET>=0.0D && RESTORE_RING_OUTER_HEIGHT_OFFSET<=0.6D
            && RESTORE_RING_VERTICAL_WAVE>0.0D && RESTORE_RING_VERTICAL_WAVE<=0.25D;
        boolean playerScanInitial=shouldScanRestorePlayers(1,true);
        boolean playerScanInitialWithoutBeacon=!shouldScanRestorePlayers(1,false);
        boolean playerScanBoundary=shouldScanRestorePlayers(RESTORE_PLAYER_SCAN_INTERVAL_TICKS,true)
            && shouldScanRestorePlayers(RESTORE_PLAYER_SCAN_INTERVAL_TICKS*2,true);
        boolean playerScanBetween=!shouldScanRestorePlayers(RESTORE_PLAYER_SCAN_INTERVAL_TICKS-1,true);
        boolean playerScanWithoutBeacon=!shouldScanRestorePlayers(RESTORE_PLAYER_SCAN_INTERVAL_TICKS,false);
        boolean playerScanInvalid=!shouldScanRestorePlayers(0,true)
            && !shouldScanRestorePlayers(-RESTORE_PLAYER_SCAN_INTERVAL_TICKS,true);
        boolean playerScanSaturated=shouldScanRestorePlayers(
            restorationCadenceTick(Integer.MAX_VALUE,3L),true);
        if(!(ringInitial && ringBoundary && ringBetween && helixBoundary && helixBetween
            && blockBoundary && blockBetween && blockNext && invalidTicks
            && boundedCounts && cachedPalette && boundedRestoreDust && ringClearsBeaconBlock
            && playerScanInitial && playerScanInitialWithoutBeacon
            && playerScanBoundary && playerScanBetween && playerScanWithoutBeacon
            && playerScanInvalid && playerScanSaturated))
            throw new IllegalStateException("CI restoration particle profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_PROFILE_VERIFIED ring={} ringIntervalTicks={} sparks={} helix={} helixIntervalTicks={} blockCue={} blockIntervalBlocks={} audience={} audienceIntervalTicks={} cachedDust=true",
            RESTORE_RING_PARTICLE_COUNT,RESTORE_RING_INTERVAL_TICKS,RESTORE_RISING_SPARK_PARTICLE_COUNT,
            RESTORE_HELIX_PARTICLE_COUNT,RESTORE_HELIX_INTERVAL_TICKS,
            RESTORE_BLOCK_CUE_PARTICLE_COUNT,RESTORE_BLOCK_CUE_INTERVAL,
            RESTORE_AUDIENCE_PARTICLE_COUNT,RESTORE_RING_INTERVAL_TICKS);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_SIZE_VERIFIED baseScale={} accentScale={} audienceSpreadXZ={} audienceSpreadY={} cachedDust=true",
            RESTORE_VIOLET_DUST_SCALE,RESTORE_VIOLET_ACCENT_DUST_SCALE,
            RESTORE_AUDIENCE_SPREAD_XZ,RESTORE_AUDIENCE_SPREAD_Y);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_RING_CLEARANCE_VERIFIED offset={} minOffset={} maxOffset={} clearsBeaconTop=true",
            RESTORE_RING_HEIGHT_OFFSET,minimumRingOffset,maximumRingOffset);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_INITIAL_PULSE_POLICY_VERIFIED tick=1 ringParticles={} sparks={} scanOnStart=true loadedOnly=true",
            RESTORE_RING_PARTICLE_COUNT,RESTORE_RISING_SPARK_PARTICLE_COUNT);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_PROFILE_VERIFIED intervalTicks={} menuClose=shared viewerScan=shared loadedOnly=true",
            RESTORE_PLAYER_SCAN_INTERVAL_TICKS);
        verifyRestorePlayerScanFullPass();
        verifyRestoreParticleAudienceBounds();
        verifyRestoreCadenceSaturationFallback();
        verifyRecoveryUiPalette();
        verifyIdleBeaconParticlePalette();
    }

    private static void verifyBeaconInteractionGuard() {
        var main=net.minecraft.world.InteractionHand.MAIN_HAND;
        var off=net.minecraft.world.InteractionHand.OFF_HAND;
        double reachSquared=CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE;
        BlockPos target=new BlockPos(0,64,0);
        double exactAabbReach=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(-CI_BEACON_INTERACTION_MAX_DISTANCE,64.5D,.5D),target);
        double outsideAabbReach=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(-Math.nextUp(CI_BEACON_INTERACTION_MAX_DISTANCE),64.5D,.5D),target);
        net.minecraft.world.phys.Vec3 beaconHitPoint=new net.minecraft.world.phys.Vec3(0.0D,64.5D,.5D);
        net.minecraft.world.phys.Vec3 exactHitEye=new net.minecraft.world.phys.Vec3(
            -CI_BEACON_INTERACTION_MAX_DISTANCE,64.5D,.5D);
        net.minecraft.world.phys.Vec3 outsideHitEye=new net.minecraft.world.phys.Vec3(
            -Math.nextUp(CI_BEACON_INTERACTION_MAX_DISTANCE),64.5D,.5D);
        double exactHitPointReach=eyeDistanceSquaredToHit(exactHitEye,beaconHitPoint);
        double outsideHitPointReach=eyeDistanceSquaredToHit(outsideHitEye,beaconHitPoint);
        double invalidHitPointReach=eyeDistanceSquaredToHit(exactHitEye,
            new net.minecraft.world.phys.Vec3(Double.NaN,64.5D,.5D));
        if(Math.abs(exactHitPointReach-reachSquared)>1.0E-9D
            || !withinBeaconInteractionReach(exactHitPointReach)
            || withinBeaconInteractionReach(outsideHitPointReach)
            || withinBeaconInteractionReach(invalidHitPointReach)
            || !sameBeaconHitPoint(beaconHitPoint,new net.minecraft.world.phys.Vec3(0.0005D,64.5D,.5D))
            || sameBeaconHitPoint(beaconHitPoint,new net.minecraft.world.phys.Vec3(0.0011D,64.5D,.5D))
            || sameBeaconHitPoint(beaconHitPoint,new net.minecraft.world.phys.Vec3(Double.NaN,64.5D,.5D)))
            throw new IllegalStateException("CI beacon exact hit-point reach/ray-match regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_HIT_POINT_GUARD_PROFILE_VERIFIED exactBoundary=true outsideRejected=true invalidRejected=true rayMatch=true displacedRejected=true");
        long activeBeaconPosition=target.asLong();
        boolean sameActiveBeaconSuppressed=shouldSuppressActiveRecoveryBeaconMenu(
            true,true,false,true,activeBeaconPosition,activeBeaconPosition,true);
        boolean differentBeaconPreserved=!shouldSuppressActiveRecoveryBeaconMenu(
            true,true,false,true,activeBeaconPosition,activeBeaconPosition+1L,true);
        boolean inactiveBeaconPreserved=!shouldSuppressActiveRecoveryBeaconMenu(
            false,true,false,true,activeBeaconPosition,activeBeaconPosition,true);
        boolean completedBeaconPreserved=!shouldSuppressActiveRecoveryBeaconMenu(
            true,false,true,true,activeBeaconPosition,activeBeaconPosition,true);
        boolean missingPositionPreserved=!shouldSuppressActiveRecoveryBeaconMenu(
            true,true,false,false,activeBeaconPosition,activeBeaconPosition,true);
        boolean noPendingSnapshotsPreserved=!shouldSuppressActiveRecoveryBeaconMenu(
            true,true,false,true,activeBeaconPosition,activeBeaconPosition,false);
        if(!(sameActiveBeaconSuppressed && differentBeaconPreserved && inactiveBeaconPreserved
            && completedBeaconPreserved && missingPositionPreserved && noPendingSnapshotsPreserved))
            throw new IllegalStateException("CI active recovery beacon menu guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST ACTIVE_RECOVERY_BEACON_MENU_GUARD_VERIFIED sameBeaconSuppressed=true otherBeaconPreserved=true inactivePreserved=true completedPreserved=true missingPositionPreserved=true noPendingPreserved=true");
        if(Math.abs(exactAabbReach-reachSquared)>1.0E-9D
            || !(outsideAabbReach>reachSquared)
            || !mayActivateBeacon(main,true,false,exactAabbReach,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,outsideAabbReach,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,64.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(off,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,false,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,true,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.NaN,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.POSITIVE_INFINITY,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.NEGATIVE_INFINITY,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,-0.001D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || !mayActivateBeacon(main,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT))
            throw new IllegalStateException("CI beacon interaction reach/AABB regression");
        if(!mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                main,true,false,false,exactAabbReach,false,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                main,true,false,false,outsideAabbReach,false,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(false,true,true,true,false,false,true,
                main,true,false,false,0.0D,false,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(true,true,true,true,true,false,true,
                main,true,false,false,0.0D,false,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                off,true,false,false,0.0D,false,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                main,true,false,false,0.0D,true,
                net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                main,true,false,false,0.0D,false,
                net.minecraftforge.eventbus.api.Event.Result.DENY,net.minecraftforge.eventbus.api.Event.Result.DEFAULT))
            throw new IllegalStateException("CI beacon aim recovery reach regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_AIM_RECOVERY_GUARD_VERIFIED");
        if(mayActivateBeacon(main,true,false,0.0D,true,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DENY,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || !mayActivateBeacon(main,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.ALLOW,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DENY))
            throw new IllegalStateException("CI beacon protection-mod denial regression");
        // A high-altitude beacon remains usable when horizontally close;
        // extreme opposite-sign coordinates must never wrap into range.
        if(!beaconWithinAnchor(128,0,0,0)
            || beaconWithinAnchor(129,0,0,0)
            || beaconWithinAnchor(128,1,0,0)
            || !beaconWithinAnchor(-128,0,0,0)
            || beaconWithinAnchor(Integer.MAX_VALUE,0,Integer.MIN_VALUE,0))
            throw new IllegalStateException("CI beacon anchor radius regression");
        if(!naturalBeaconWithinFracture(new BlockPos(RADIUS-4,319,0),0,0,-64,320)
            || naturalBeaconWithinFracture(new BlockPos(RADIUS-3,319,0),0,0,-64,320)
            || naturalBeaconWithinFracture(new BlockPos(RADIUS-4,320,0),0,0,-64,320)
            || naturalBeaconWithinFracture(new BlockPos(0,-65,0),0,0,-64,320)
            || naturalBeaconWithinFracture(new BlockPos(Integer.MAX_VALUE,64,Integer.MIN_VALUE),
                Integer.MIN_VALUE,0,-64,320))
            throw new IllegalStateException("CI natural recovery beacon anchor bounds regression");
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ANCHOR_BOUNDS_VERIFIED");
        verifyRecoveryBeaconVisualAnchorBounds();
        verifyNaturalBeaconIdPersistence();
        verifyRestoreCursorRepairPersistence();
        verifyDuplicateRecoverySnapshotQuarantine();
        verifyCompletedRecoveryStateQuarantine();
        verifyInvalidSnapshotWarningRateLimit();
        verifyRecoveryAudienceBounds();
        verifySurfaceFractureParticleAudienceBounds();
        verifyRecoveryVortexContraction();
        verifyFractureColumnBounds();
        verifyBoundedAnomalyVelocity();
        verifyBoundedLiftVelocity();
        verifyBoundedSinkVelocity();
        verifyBoundedSinkDrag();
        verifyRiftGravityEligibility();
        verifyGravityAnomalyField();
        verifyGravityAnomalyRadialFade();
        verifyRestorationGravityFade();
        verifyGravityAnomalyParticlePalette();
        verifyPendingSnapshotPersistence();
        verifySparseBeaconSupport();
        verifyRestorationProgress();
        boolean idleBoundary=shouldSendIdleBeaconHint(false,false,200);
        boolean idleBetweenBoundaries=!shouldSendIdleBeaconHint(false,false,201);
        boolean restorationSuppressed=!shouldSendIdleBeaconHint(true,false,200);
        boolean completedSuppressed=!shouldSendIdleBeaconHint(false,true,200);
        boolean acceleratedRestorationSuppressed=!shouldSendIdleBeaconHint(true,false,2400);
        if(!(idleBoundary && idleBetweenBoundaries && restorationSuppressed
            && completedSuppressed && acceleratedRestorationSuppressed))
            throw new IllegalStateException("CI idle beacon hint lifecycle regression");
        LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_HINT_LIFECYCLE_VERIFIED idleBoundary=true restorationSuppressed=true completedSuppressed=true acceleratedTickSafe=true");
        boolean idlePulseAtBoundary=shouldEmitIdleBeaconParticles(20);
        boolean idlePulseBetween=!shouldEmitIdleBeaconParticles(19);
        boolean idlePulseNextBoundary=shouldEmitIdleBeaconParticles(40);
        boolean idlePulseZeroSuppressed=!shouldEmitIdleBeaconParticles(0);
        boolean idlePulseNegativeSuppressed=!shouldEmitIdleBeaconParticles(-20);
        if(!(idlePulseAtBoundary && idlePulseBetween && idlePulseNextBoundary
            && idlePulseZeroSuppressed && idlePulseNegativeSuppressed
            && IDLE_BEACON_PULSE_INTERVAL_TICKS==20
            && IDLE_BEACON_PULSE_PARTICLE_COUNT==6))
            throw new IllegalStateException("CI idle beacon particle cadence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_PARTICLE_CADENCE_VERIFIED intervalTicks={} particles={} cachedDust=true",
            IDLE_BEACON_PULSE_INTERVAL_TICKS,IDLE_BEACON_PULSE_PARTICLE_COUNT);
        verifyRestorationParticleProfile();
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_INTERACTION_GUARD_VERIFIED maxDistance={} maxDistanceSquared={} eyeToAabb=true strictBoundary=true remote64Rejected=true invalidRejected=true",
            CI_BEACON_INTERACTION_MAX_DISTANCE,reachSquared);
    }

    /** Progress remains below 100% until the final stabilization hold has completed. */
    private static int restorationPercent(int restoreIndex,int snapshotCount) {
        if(snapshotCount<=0) return 0;
        long pending=Math.max(0L,Math.min((long)snapshotCount,(long)restoreIndex+1L));
        int completed=snapshotCount-(int)pending;
        return Math.max(0,Math.min(99,(int)(100L*completed/snapshotCount)));
    }

    private static boolean shouldSendRestoreProgress(int cadenceTick,boolean hasPendingSnapshots) {
        return cadenceTick%RESTORE_PROGRESS_INTERVAL_TICKS==0 && hasPendingSnapshots;
    }

    private static boolean shouldSendRestoreOverlay(int cadenceTick) {
        return cadenceTick%RESTORE_OVERLAY_INTERVAL_TICKS==0;
    }

    private static void verifyRestorationProgress() {
        int[][] cases={
            {9,10,0}, {4,10,50}, {0,10,90}, {-1,10,99},
            {Integer.MAX_VALUE,10,0}, {Integer.MIN_VALUE,10,99}, {0,0,0}
        };
        for(int[] sample:cases) {
            int actual=restorationPercent(sample[0],sample[1]);
            if(actual!=sample[2] || actual>=100)
                throw new IllegalStateException("CI restoration progress regression: "+actual);
        }
        boolean progressAtBoundary=shouldSendRestoreProgress(RESTORE_PROGRESS_INTERVAL_TICKS,true);
        boolean progressSuppressedWithoutSnapshots=!shouldSendRestoreProgress(RESTORE_PROGRESS_INTERVAL_TICKS,false);
        boolean progressBetweenBoundaries=!shouldSendRestoreProgress(RESTORE_PROGRESS_INTERVAL_TICKS-1,true);
        boolean overlayAtBoundary=shouldSendRestoreOverlay(RESTORE_OVERLAY_INTERVAL_TICKS);
        boolean overlayAtProgressBoundary=shouldSendRestoreOverlay(RESTORE_PROGRESS_INTERVAL_TICKS);
        boolean overlayBetweenBoundaries=!shouldSendRestoreOverlay(RESTORE_OVERLAY_INTERVAL_TICKS-1);
        if(!(progressAtBoundary && progressSuppressedWithoutSnapshots && progressBetweenBoundaries
            && overlayAtBoundary && overlayAtProgressBoundary && overlayBetweenBoundaries))
            throw new IllegalStateException("CI restoration UI cadence regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PROGRESS_HELD_BELOW_COMPLETION");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_UI_AUDIENCE_CADENCE_VERIFIED progressIntervalTicks={} overlayIntervalTicks={} combinedBoundary=true",
            RESTORE_PROGRESS_INTERVAL_TICKS,RESTORE_OVERLAY_INTERVAL_TICKS);
    }

    /** Right-click a vanilla beacon within 128 blocks of the fracture anchor. */
    // Respect earlier protection handlers before committing recovery state.
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public void onBeacon(PlayerInteractEvent.RightClickBlock event) {
        if(!(event.getLevel() instanceof ServerLevel level)
            || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        // Protection-mod cancellation and DENY are enforced by the tested activation gate.
        BlockPos pos=event.getPos();
        // Ignore unloaded positions before querying block state: an event from
        // another mod must not cause recovery logic to load a chunk.
        if(!level.hasChunkAt(pos)) return;
        if(!level.getBlockState(pos).is(Blocks.BEACON)) {
            // The deterministic client smoke uses real mouse input. If its
            // camera lands on the adjacent loaded pad, correct the test player
            // once through the normal server event path instead of pretending
            // the click succeeded or repeatedly clicking the wrong block.
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest")
                && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")) {
                State testState=level.getDataStorage().computeIfAbsent(
                    State::load,State::new,"blackfrequency_finale");
                if(recoverCiBeaconAimAfterMiss(level,event,testState,pos)) return;
            }
            return;
        }
        // A player-owned beacon must obey the same active border restriction
        // as generated recovery beacons; do not start restoration from outside.
        if(!level.getWorldBorder().isWithinBounds(pos)) return;
        // Spectators must not be able to trigger a permanent world-state
        // transition by interacting with a recovery beacon.
        // Reject spectators, off-hand duplicates, dead players and remote
        // interactions before touching persistent recovery state.
        // Removed player entities can still surface through queued interaction events.
        // Never let a stale entity commit a permanent restoration transition.
        if(event.getEntity().isRemoved()) return;
        if(!mayActivateBeacon(event.getHand(),event.getEntity().isAlive(),
            event.getEntity().isSpectator(),
            eyeDistanceSquaredToBlock(event.getEntity().getEyePosition(),pos),
            event.isCanceled(),event.getUseBlock(),event.getUseItem())) return;
        if(!(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        // Reach alone is insufficient: a forged or stale interaction must not activate
        // a beacon through a wall. Trace only chunks already loaded along the eye ray.
        var hit=event.getHitVec();
        if(hit==null || !hit.getBlockPos().equals(pos)) return;
        var requestedHitPoint=hit.getLocation();
        double hitDistanceSquared=eyeDistanceSquaredToHit(player.getEyePosition(),requestedHitPoint);
        if(!withinBeaconInteractionReach(hitDistanceSquared)) {
            if(CI_TEST) LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_HIT_POINT_REACH_REJECTED distanceSquared={} maxDistanceSquared={}",
                hitDistanceSquared,CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE);
            return;
        }
        var face=hit.getDirection();
        // Move a fraction inside the clicked face so a boundary hit at x/z=16
        // does not falsely require the adjacent, unrelated chunk to be loaded.
        var hitPoint=requestedHitPoint.subtract(
            face.getStepX()*1.0E-4D,face.getStepY()*1.0E-4D,face.getStepZ()*1.0E-4D);
        if(!hasLoadedBeaconSightline(level,player,pos,hitPoint,requestedHitPoint)) {
            if(CI_TEST) LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_ACTIVATION_SIGHTLINE_REJECTED loadedChunksOnly=true");
            return;
        }
        if(CI_TEST) {
            LOGGER.info("BLACK_FREQUENCY_TEST BEACON_ACTIVATION_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true");
            LOGGER.info("BLACK_FREQUENCY_TEST BEACON_HIT_POINT_ACCEPTED_VERIFIED distanceSquared={} maxDistanceSquared={} rayMatch=true",
                hitDistanceSquared,CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE);
        }
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(shouldSuppressActiveRecoveryBeaconMenu(s.started,s.restoring,s.restored,
                s.beaconPosPresent,s.beaconPos,pos.asLong(),!s.snapshots.isEmpty())) {
            // A second click on the recovery beacon must not fall through to the
            // vanilla BeaconScreen while the world is already being restored.
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_ACTIVE_MENU_INTERACTION_SUPPRESSED x={} y={} z={}",
                pos.getX(),pos.getY(),pos.getZ());
            return;
        }
        if(!canStartBeaconRestoration(s,pos)) return;
        // Log only accepted player interactions; rejected distant beacons must
        // never count as successful activation in the real-client CI test.
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION x={} y={} z={} natural={}",pos.getX(),pos.getY(),pos.getZ(),pos.asLong()==s.naturalBeaconPos);
        s.restoring=true;s.restoreIndex=s.snapshots.size()-1;s.restoreScan=s.restoreIndex;s.restoreTicks=0;s.restoredBlocks=0;s.skippedOccupied=0;s.skippedInvalid=0;s.beaconPos=pos.asLong();s.beaconPosPresent=true;s.setDirty();
        // Keep the physical beacon visible during activation. A full-screen title
        // covered the object in the player's view; use the action bar instead.
        // Only nearby, active players should receive this world-local notice.
        for(var nearbyPlayer:level.players()) {
            if(!nearbyPlayer.isAlive() || nearbyPlayer.isRemoved() || nearbyPlayer.isSpectator()
                || nearbyPlayer.distanceToSqr(pos.getX()+0.5D,pos.getY()+0.5D,pos.getZ()+0.5D)>128.0D*128.0D)
                continue;
            nearbyPlayer.displayClientMessage(recoveryViolet("✦ Маяк запустил восстановление мира"),true);
        }
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_START_ACTIONBAR_ONLY");
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED");
    }

    /** Only the disposable sparse-client fixture may replace unstable supports. */
    private static boolean needsSparseBeaconSupport(BlockState support,boolean sturdyTopFace) {
        // Motion blocking alone is insufficient: thin snow layers need a genuinely
        // sturdy upward face beneath them or the test beacon can lose its platform.
        return !sturdyTopFace
            || (!support.blocksMotion() && (support.isAir() || !support.getFluidState().isEmpty()))
            || support.getBlock() instanceof net.minecraft.world.level.block.FallingBlock;
    }

    private static void verifySparseBeaconSupport() {
        if(!needsSparseBeaconSupport(Blocks.AIR.defaultBlockState(),false)
            || !needsSparseBeaconSupport(Blocks.WATER.defaultBlockState(),false)
            || !needsSparseBeaconSupport(Blocks.SAND.defaultBlockState(),true)
            || !needsSparseBeaconSupport(Blocks.GRAVEL.defaultBlockState(),true)
            || !needsSparseBeaconSupport(Blocks.STONE.defaultBlockState(),false)
            || needsSparseBeaconSupport(Blocks.STONE.defaultBlockState(),true))
            throw new IllegalStateException("CI sparse beacon sturdy-face support regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SPARSE_BEACON_SUPPORT_RULE_VERIFIED");
    }

    /** Place a recovery beacon without overwriting terrain or loading chunks.
     *  The vanilla beacon is a physical block and still requires a real right-click.
     *  Players may also use their own beacon if the generated one is mined.
     */
    private static boolean tryPlaceRecoveryBeacon(ServerLevel level,State s) {
        for(var player:level.players()) {
            // Dead or removed players are not valid anchors for a physical
            // recovery objective; wait for a living player in a loaded area.
            if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
            BlockPos playerPos=player.blockPosition();
            long ax=(long)playerPos.getX()-s.x,az=(long)playerPos.getZ()-s.z;
            if(Math.abs(ax)>RADIUS-8 || Math.abs(az)>RADIUS-8
                || ax*ax+az*az>(long)(RADIUS-8)*(RADIUS-8)) continue;
            var facing=player.getDirection();
            int forwardX=facing.getStepX(),forwardZ=facing.getStepZ();
            // Search a few additional nearby loaded positions when rough terrain
            // leaves the original 4..8 block corridor without a safe support.
            // All candidates still pass the fracture radius, border and chunk guards.
            // Search in front first, then behind the player. In sparse fractured
            // terrain the forward corridor may be entirely void while a safe
            // loaded platform survives behind them. Never place at their feet.
            for(int step=3;step<=20;step++) {
                int distance=step<=10?step:-(step-7);
                for(int side=0;side<=10;side++) {
                    int offset=side==0?0:(side%2==1?(side+1)/2:-(side/2));
                    // Calculate in long before narrowing: near the integer world
                    // boundary an overflow must never wrap a beacon to the other side.
                    long candidateX=(long)playerPos.getX()+forwardX*distance-forwardZ*offset;
                    long candidateZ=(long)playerPos.getZ()+forwardZ*distance+forwardX*offset;
                    if(candidateX<Integer.MIN_VALUE || candidateX>Integer.MAX_VALUE
                        || candidateZ<Integer.MIN_VALUE || candidateZ>Integer.MAX_VALUE) continue;
                    int x=(int)candidateX,z=(int)candidateZ;
                    long dx=candidateX-s.x,dz=candidateZ-s.z;
                    if(Math.abs(dx)>RADIUS-4 || Math.abs(dz)>RADIUS-4
                        || dx*dx+dz*dz>(long)(RADIUS-4)*(RADIUS-4)) continue;
                    if(!level.hasChunk(x>>4,z>>4)) continue;
                    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z);
                    if(y<=level.getMinBuildHeight() || y>=level.getMaxBuildHeight()-2) continue;
                    BlockPos pos=new BlockPos(x,y,z);
                    // A beacon beyond the active world border is unusable even
                    // when its chunk is loaded. Never create an inaccessible
                    // recovery objective at the edge of a restricted world.
                    if(!level.getWorldBorder().isWithinBounds(pos)) continue;
                    // The natural-beacon client fixture needs an unobstructed sky
                    // column so the sky-rift capture is not hidden under tree canopies.
                    // This is CI-only, reads the already-loaded candidate chunk, and
                    // never requests another chunk or edits the surrounding terrain.
                    if(CI_TEST && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")) {
                        boolean clearSkyColumn=true;
                        for(int dy=1;dy<=12;dy++) {
                            if(!level.getBlockState(pos.above(dy)).isAir()) {
                                clearSkyColumn=false;
                                break;
                            }
                        }
                        if(!clearSkyColumn) continue;
                    }
                    // The disposable sparse client may land in a desert: sand is
                    // motion-blocking but falls away, so the old water/air-only
                    // fixture never created a safe beacon base there. Stabilize
                    // only this loaded, in-radius CI candidate before adding snow.
                    boolean sparseBeaconFixture=CI_TEST
                        && Boolean.getBoolean("blackfrequency.ciSparseRecoveryTest")
                        && Boolean.getBoolean("blackfrequency.ciSnowBeaconTest")
                        && s.finished && s.edits>0 && s.edits<160;
                    boolean existingSnow=level.getBlockState(pos).is(Blocks.SNOW);
                    BlockPos fixtureSupportPos=pos.below();
                    if(sparseBeaconFixture && (existingSnow || level.getBlockState(pos).isAir())
                        && level.getBlockState(pos.above()).isAir()
                        && level.hasChunkAt(fixtureSupportPos)
                        && fixtureSupportPos.getY()>=level.getMinBuildHeight()
                        && level.getWorldBorder().isWithinBounds(fixtureSupportPos)) {
                        BlockState candidateSupport=level.getBlockState(fixtureSupportPos);
                        if(needsSparseBeaconSupport(candidateSupport,
                            candidateSupport.isFaceSturdy(level,fixtureSupportPos,Direction.UP))) {
                            if(!level.setBlock(fixtureSupportPos,Blocks.STONE.defaultBlockState(),3)) continue;
                            LOGGER.info("BLACK_FREQUENCY_TEST SPARSE_BEACON_SUPPORT_PREPARED x={} y={} z={} previous={}",
                                fixtureSupportPos.getX(),fixtureSupportPos.getY(),fixtureSupportPos.getZ(),
                                candidateSupport.getBlock().getDescriptionId());
                        }
                    }
                    // Disposable sparse-client regression: make the replacement
                    // beacon stand above a real snow layer, rather than relying
                    // on a random snowy biome. Never enabled in normal worlds.
                    boolean snowFixture=CI_TEST && Boolean.getBoolean("blackfrequency.ciSnowBeaconTest")
                        && s.ciNaturalBeaconRemoved;
                    if(snowFixture && level.getBlockState(pos).isAir()
                        && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).blocksMotion()
                        && level.getBlockState(pos.below()).getFluidState().isEmpty()
                        && level.getBlockState(pos.below()).isFaceSturdy(
                            level,pos.below(),Direction.UP)
                        && !level.setBlock(pos,Blocks.SNOW.defaultBlockState(),3))
                        throw new IllegalStateException("CI snow support placement failed");
                    // Motion-blocking heightmaps can stop at a solid block below
                    // a thin snow layer. Never replace the snow: put the beacon
                    // in free air above it, provided the snow rests on solid ground.
                    if(level.getBlockState(pos).is(Blocks.SNOW)) pos=pos.above();
                    if(pos.getY()>=level.getMaxBuildHeight()-1) continue;
                    BlockState support=level.getBlockState(pos.below());
                    BlockState snowFoundation=level.getBlockState(pos.below(2));
                    // Sparse CI support was stabilized before the optional snow layer.
                    // Snow is not a safe platform when the block underneath can
                    // fall away or destroy the objective after an update.
                    boolean supportedSnow=support.is(Blocks.SNOW)
                        && snowFoundation.blocksMotion()
                        && snowFoundation.isFaceSturdy(level,pos.below(2),Direction.UP)
                        && snowFoundation.getFluidState().isEmpty()
                        && !(snowFoundation.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)
                        && !snowFoundation.is(Blocks.MAGMA_BLOCK)
                        && !snowFoundation.is(Blocks.CAMPFIRE)
                        && !snowFoundation.is(Blocks.SOUL_CAMPFIRE)
                        && !snowFoundation.is(Blocks.CACTUS)
                        && !snowFoundation.is(Blocks.TNT)
                        && !snowFoundation.is(Blocks.RESPAWN_ANCHOR)
                        // Snow does not stabilize foliage that decays or ice that melts.
                        // Check the actual ground, not only the snow layer above it.
                        && !snowFoundation.is(net.minecraft.tags.BlockTags.LEAVES)
                        && !snowFoundation.is(Blocks.ICE)
                        // Frosted ice melts naturally even without a nearby heat source.
                        && !snowFoundation.is(Blocks.FROSTED_ICE)
                        // A moving piston or its head is temporary support, not terrain.
                        && !snowFoundation.is(Blocks.MOVING_PISTON)
                        && !snowFoundation.is(Blocks.PISTON_HEAD)
                        // Brushable archaeology blocks can collapse when disturbed.
                        && !snowFoundation.is(Blocks.SUSPICIOUS_SAND)
                        && !snowFoundation.is(Blocks.SUSPICIOUS_GRAVEL);
                    // Do not put the recovery objective on a damaging floor: players
                    // must be able to stand beside and interact with it safely.
                    if(support.is(Blocks.MAGMA_BLOCK) || support.is(Blocks.CAMPFIRE)
                        || support.is(Blocks.SOUL_CAMPFIRE) || support.is(Blocks.CACTUS)
                        // Solid but hazardous foundations can destroy the objective
                        // when triggered; do not place an emergency beacon on them.
                        || support.is(Blocks.TNT) || support.is(Blocks.RESPAWN_ANCHOR)
                        // Leaves can decay and ordinary ice can melt, leaving the
                        // recovery objective without a reliable foundation.
                        || support.is(net.minecraft.tags.BlockTags.LEAVES)
                        || support.is(Blocks.ICE) || support.is(Blocks.FROSTED_ICE)
                        // Piston heads and moving piston blocks can disappear on the next tick.
                        || support.is(Blocks.MOVING_PISTON) || support.is(Blocks.PISTON_HEAD)
                        || support.is(Blocks.SUSPICIOUS_SAND) || support.is(Blocks.SUSPICIOUS_GRAVEL)
                        // Gravity-affected foundations can fall away after a
                        // neighboring update, leaving the objective unsupported.
                        || support.is(Blocks.SAND) || support.is(Blocks.RED_SAND)
                        || support.is(Blocks.GRAVEL) || support.is(Blocks.WHITE_CONCRETE_POWDER)
                        || support.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) continue;
                    if((!support.blocksMotion() && !supportedSnow)
                        || !support.getFluidState().isEmpty()
                        || !level.getBlockState(pos).isAir()
                        || !level.getBlockState(pos.above()).isAir()
                        || (snowFixture && !supportedSnow)) continue;
                    // Never materialize the recovery objective inside a solid or
                    // dangerous entity. Falling blocks can settle into the beacon
                    // and end crystals can explode when struck. Hanging decorations
                    // must not be displaced; dropped items and XP do not obstruct.
                    // The query only examines entities in already-loaded chunks.
                    if(!level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,
                        new net.minecraft.world.phys.AABB(pos),
                        entity -> entity instanceof net.minecraft.world.entity.LivingEntity
                            || entity instanceof net.minecraft.world.entity.vehicle.Boat
                            || entity instanceof net.minecraft.world.entity.vehicle.AbstractMinecart
                            || entity instanceof net.minecraft.world.entity.item.FallingBlockEntity
                            || entity instanceof net.minecraft.world.entity.decoration.HangingEntity
                            || entity instanceof net.minecraft.world.entity.boss.enderdragon.EndCrystal).isEmpty()) continue;
                    if(!level.setBlock(pos,Blocks.BEACON.defaultBlockState(),3)) continue;
                    // Other mods may intercept or replace the placement. Do not
                    // persist a recovery objective unless a beacon block entity really exists.
                    if(!level.getBlockState(pos).is(Blocks.BEACON)) continue;
                    var beaconBlockEntity=level.getBlockEntity(pos);
                    if(!(beaconBlockEntity instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity recoveryBeacon)) {
                        LOGGER.warn("BLACK FREQUENCY: placed beacon at {} has no BeaconBlockEntity; leaving it untracked",pos);
                        continue;
                    }
                    UUID recoveryBeaconId=UUID.randomUUID();
                    recoveryBeacon.getPersistentData().putUUID(NATURAL_BEACON_ID_TAG,recoveryBeaconId);
                    recoveryBeacon.setChanged();
                    s.naturalBeaconPos=pos.asLong();
                    s.naturalBeaconId=recoveryBeaconId;
                    if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,pos)) {
                        recoveryBeacon.getPersistentData().remove(NATURAL_BEACON_ID_TAG);
                        recoveryBeacon.setChanged();
                        s.naturalBeaconPos=Long.MIN_VALUE;
                        s.naturalBeaconId=null;
                        s.setDirty();
                        LOGGER.warn("BLACK FREQUENCY: recovery beacon ownership marker could not be verified at {}",pos);
                        continue;
                    }
                    s.setDirty();
                    float beaconProgress=s.restored ? 0f : s.finished ? 1f
                        : Math.max(0f,Math.min(1f,(s.elapsed-3f*DAY)/(7f*DAY)));
                    boolean beaconCollapse=!s.restored && (s.started || s.finished);
                    // Publish the exact physical objective immediately; clients must not
                    // guess its Y coordinate from the fracture anchor or scan chunks.
                    for(var beaconViewer:level.players()) RiftNetwork.send(beaconViewer,beaconProgress,
                        beaconCollapse,s.finaleTicks,s.x,s.z,s.naturalBeaconPos,s.restoring);
                    if(CI_TEST) {
                        // Exercise the actual Forge block-entity persistence path,
                        // not only the SavedData UUID field.
                        CompoundTag beaconSave=recoveryBeacon.saveWithFullMetadata();
                        var reloadedBeacon=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
                            pos,level.getBlockState(pos),beaconSave);
                        if(!(reloadedBeacon instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity)
                            || !reloadedBeacon.getPersistentData().hasUUID(NATURAL_BEACON_ID_TAG)
                            || !recoveryBeaconId.equals(reloadedBeacon.getPersistentData().getUUID(NATURAL_BEACON_ID_TAG)))
                            throw new IllegalStateException("CI natural beacon block-entity UUID persistence regression");
                        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_BLOCK_ENTITY_ID_PERSISTENCE_VERIFIED");
                    }
                    if(snowFixture) {
                        BlockPos snowPos=pos.below();
                        BlockPos foundationPos=pos.below(2);
                        if(!level.getBlockState(snowPos).is(Blocks.SNOW)
                            || !level.getBlockState(foundationPos).isFaceSturdy(
                                level,foundationPos,Direction.UP)) {
                            // A modded block update may invalidate a candidate after
                            // placement. Remove only the beacon carrying our UUID and
                            // retire its anchor; never crash the integrated server or
                            // leave a stale recovery objective in SavedData.
                            if(isOwnedNaturalBeaconAtLoadedPosition(level,s,pos))
                                level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                            if(isOwnedNaturalBeaconAtLoadedPosition(level,s,pos)) {
                                // If another mod cancels removal, keep this anchor tracked
                                // and stop searching rather than creating a second objective.
                                LOGGER.warn("BLACK FREQUENCY: could not retire unstable recovery beacon at {}",pos);
                                return true;
                            }
                            s.naturalBeaconPos=Long.MIN_VALUE;
                            s.naturalBeaconId=null;
                            s.setDirty();
                            LOGGER.warn("BLACK FREQUENCY: rejected recovery beacon with unstable snow support at {}",pos);
                            continue;
                        }
                        LOGGER.info("BLACK_FREQUENCY_TEST SNOW_BEACON_SUPPORT_VERIFIED x={} y={} z={} snowY={}",
                            pos.getX(),pos.getY(),pos.getZ(),pos.getY()-1);
                    }
                    if(CI_TEST) {
                        // Deterministic list-filter regression. This is not a
                        // substitute for a live TNT/creeper explosion test.
                        List<BlockPos> affected=new ArrayList<>(List.of(pos,pos.above(),pos));
                        // Explosions may contain repeated positions after other mods
                        // modify the affected list. Remove every beacon occurrence.
                        if(!protectRecoveryBeaconFromExplosion(affected,s.naturalBeaconPos)
                            || affected.size()!=1 || !affected.get(0).equals(pos.above())
                            || protectRecoveryBeaconFromExplosion(affected,Long.MIN_VALUE))
                            throw new IllegalStateException("CI explosion beacon protection failed");
                        // An unrelated (player-owned) beacon and ordinary blocks
                        // must remain destructible; only the tracked fixture is protected.
                        List<BlockPos> unrelated=new ArrayList<>(List.of(pos.above(),pos.above(2)));
                        if(protectRecoveryBeaconFromExplosion(unrelated,s.naturalBeaconPos)
                            || unrelated.size()!=2 || !unrelated.get(0).equals(pos.above())
                            || !unrelated.get(1).equals(pos.above(2)))
                            throw new IllegalStateException("CI explosion filter damaged unrelated blocks");
                        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_EXPLOSION_FILTER_VERIFIED");
                    }
                    // In the disposable client test, remove the first beacon to
                    // exercise the same recovery path as a player mining it.
                    if(CI_TEST && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")) {
                        if(!s.ciNaturalBeaconRemoved) {
                            if(!level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)
                                || !level.setBlock(pos,Blocks.BEACON.defaultBlockState(),3))
                                throw new IllegalStateException("CI beacon replacement fixture failed");
                            // A new vanilla beacon at the same coordinates has no
                            // ownership marker and must not inherit the old objective.
                            if(isOwnedNaturalBeaconAtLoadedPosition(level,s,pos))
                                throw new IllegalStateException("CI replacement beacon inherited recovery ownership");
                            LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_REPLACEMENT_REJECTED x={} y={} z={}",
                                pos.getX(),pos.getY(),pos.getZ());
                            if(!level.setBlock(pos,Blocks.AIR.defaultBlockState(),3))
                                throw new IllegalStateException("CI beacon removal failed");
                            s.ciNaturalBeaconRemoved=true;
                            LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_REMOVED_FOR_TEST x={} y={} z={}",pos.getX(),pos.getY(),pos.getZ());
                            return true;
                        }
                        s.ciNaturalBeaconReplaced=true;
                        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_REPLACED x={} y={} z={}",pos.getX(),pos.getY(),pos.getZ());
                    }
                    // Use the final block position: on snow it can be one block above
                    // the heightmap Y. Logs, particles and hints must agree with the
                    // physical beacon that the player can actually right-click.
                    // A visible, bounded blue-violet Dust signal marks the newly spawned beacon.
                    level.sendParticles(FISSURE_VIOLET_ACCENT_DUST,pos.getX()+.5,pos.getY()+1.2,pos.getZ()+.5,18,.35,.55,.35,.035);
                    if(CI_TEST && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest"))
                        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_SKY_CLEARANCE_VERIFIED x={} y={} z={} verticalBlocks=12",pos.getX(),pos.getY(),pos.getZ());
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_PLACED x={} y={} z={}",pos.getX(),pos.getY(),pos.getZ());
                    for(var nearby:level.players()) {
                        // Only active players inside the local fracture area
                        // should receive coordinates for the recovery objective.
                        // Use a full 3D distance so players far above or below
                        // the objective do not receive misleading coordinates.
                        if(!nearby.isAlive() || nearby.isRemoved() || nearby.isSpectator()) continue;
                        // The hint belongs to the physical beacon, not an
                        // infinitely tall column above its X/Z coordinates.
                        // Squared double distance is safe across world limits.
                        if(nearby.distanceToSqr(pos.getX()+0.5D,
                            pos.getY()+0.5D,pos.getZ()+0.5D)>128.0D*128.0D) continue;
                        nearby.displayClientMessage(recoveryViolet(
                            "✦ Маяк стабилизации: §f"+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" §7— нажмите ПКМ"),true);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A saved snapshot is never authority to edit terrain outside the original
     * circular fracture or beyond build height, even if its NBT was corrupted.
     * Use long arithmetic: damaged coordinates must not overflow the radius test.
     */
    private static boolean isRestorableSnapshotPosition(BlockPos pos, State s, ServerLevel level) {
        long dx=(long)pos.getX()-s.x, dz=(long)pos.getZ()-s.z;
        // Reject large deltas before squaring: two extreme, malformed
        // coordinates could otherwise overflow even a signed long sum.
        return pos.getY()>=level.getMinBuildHeight()
            && pos.getY()<level.getMaxBuildHeight()
            && Math.abs(dx)<=RADIUS && Math.abs(dz)<=RADIUS
            && dx*dx+dz*dz<=(long)RADIUS*RADIUS;
    }

    private static LongOpenHashSet beginRestoreTick(State s) {
        s.restoreFailedWrites.clear();
        return s.restoreFailedWrites;
    }

    // A syntactically bounded NBT payload is not necessarily a valid block state:
    // unknown blocks and invalid property names/values can decode to a default state.
    // Require an exact registry decode/encode round trip before any recovery write.
    private static BlockState readCanonicalSnapshotBlockState(ServerLevel level,CompoundTag stateTag) {
        try {
            BlockState decoded=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),stateTag);
            // Recovery snapshots contain only BlockState NBT, never block-entity data.
            // Restoring a chest/furnace/beacon from state alone would silently create
            // an empty or incomplete block entity and can discard the original contents.
            return !decoded.isAir() && !decoded.hasBlockEntity()
                && NbtUtils.writeBlockState(decoded).equals(stateTag)
                ? decoded : Blocks.AIR.defaultBlockState();
        } catch(RuntimeException invalidSnapshot) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private static void verifyCanonicalSnapshotBlockState(ServerLevel level) {
        CompoundTag validState=NbtUtils.writeBlockState(Blocks.OAK_LOG.defaultBlockState());
        if(!validState.contains("Properties",Tag.TAG_COMPOUND))
            throw new IllegalStateException("CI canonical snapshot fixture has no block properties");
        BlockState decoded=readCanonicalSnapshotBlockState(level,validState);
        CompoundTag invalidValue=validState.copy();
        invalidValue.getCompound("Properties").putString("axis","diagonal");
        CompoundTag unknownProperty=validState.copy();
        unknownProperty.getCompound("Properties").putString("blackfrequency_unknown","x");
        CompoundTag unknownBlock=validState.copy();
        unknownBlock.putString("Name","blackfrequency:missing_recovery_test_block");
        CompoundTag chestState=NbtUtils.writeBlockState(Blocks.CHEST.defaultBlockState());
        CompoundTag furnaceState=NbtUtils.writeBlockState(Blocks.FURNACE.defaultBlockState());
        CompoundTag beaconState=NbtUtils.writeBlockState(Blocks.BEACON.defaultBlockState());
        boolean blockEntitiesRejected=readCanonicalSnapshotBlockState(level,chestState).isAir()
            && readCanonicalSnapshotBlockState(level,furnaceState).isAir()
            && readCanonicalSnapshotBlockState(level,beaconState).isAir();
        if(decoded.isAir() || !NbtUtils.writeBlockState(decoded).equals(validState)
            || !readCanonicalSnapshotBlockState(level,invalidValue).isAir()
            || !readCanonicalSnapshotBlockState(level,unknownProperty).isAir()
            || !readCanonicalSnapshotBlockState(level,unknownBlock).isAir()
            || !blockEntitiesRejected)
            throw new IllegalStateException("CI recovery snapshot canonical block-state round-trip regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_BLOCK_STATE_CANONICAL_ROUNDTRIP_VERIFIED valid=true invalidPropertyValue=true unknownProperty=true unknownBlock=true");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_BLOCK_ENTITY_STATE_REJECTED chest=true furnace=true beacon=true");
    }

    private static void restore(ServerLevel level,State s) {
        // Reuse primitive per-tick caches: a chunk cannot load or unload halfway through this server-thread scan.
        s.restoreChunksCheckedThisTick.clear();
        s.restoreChunksLoadedThisTick.clear();
        int chunkAvailabilityCandidates=0,chunkAvailabilityProbes=0,chunkAvailabilityCacheHits=0;
        boolean beaconChunkSnapshotCacheHit=false;
        BlockPos activatedBeacon=recoveryVisualAnchor(s,level.getSeaLevel());
        // Resolve the physical beacon through the same per-tick loaded-chunk cache
        // used by snapshots. This avoids a second availability probe when the
        // beacon and the next recovery edits occupy the same chunk.
        boolean beaconPositionValid=s.beaconPosPresent
            && isRecoveryBeaconVisualAnchorValid(activatedBeacon,s.x,s.z,
            level.getMinBuildHeight(),level.getMaxBuildHeight(),
            level.getWorldBorder().isWithinBounds(activatedBeacon));
        int beaconChunkX=activatedBeacon.getX()>>4;
        int beaconChunkZ=activatedBeacon.getZ()>>4;
        long beaconChunkKey=(((long)beaconChunkX)<<32)^(beaconChunkZ&0xffffffffL);
        boolean beaconChunkLoaded=false;
        if(beaconPositionValid) {
            chunkAvailabilityCandidates++;
            if(s.restoreChunksCheckedThisTick.add(beaconChunkKey)) {
                chunkAvailabilityProbes++;
                beaconChunkLoaded=level.hasChunk(beaconChunkX,beaconChunkZ);
                if(beaconChunkLoaded) s.restoreChunksLoadedThisTick.add(beaconChunkKey);
            } else {
                chunkAvailabilityCacheHits++;
                beaconChunkLoaded=s.restoreChunksLoadedThisTick.contains(beaconChunkKey);
            }
        }
        boolean beaconPresent=beaconChunkLoaded
            && level.getBlockState(activatedBeacon).is(Blocks.BEACON);
        double beaconX=activatedBeacon.getX()+0.5D;
        double beaconY=activatedBeacon.getY()+0.5D;
        double beaconZ=activatedBeacon.getZ()+0.5D;
        s.restoreTicks=nextNonNegativeTick(s.restoreTicks);
        long animationTick=restorationAnimationTick(s.restoreTicks,level.getGameTime());
        int cadenceTick=restorationCadenceTick(s.restoreTicks,level.getGameTime());
        if(CI_TEST && s.restoreTicks==1) {
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_BEACON_PRESENCE_CACHED present={}",beaconPresent);
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_BEACON_CHUNK_CACHE_PROFILE positionValid={} loaded={} checked={} loadedCached={} loadedOnly=true",
                beaconPositionValid,beaconChunkLoaded,
                s.restoreChunksCheckedThisTick.contains(beaconChunkKey),
                s.restoreChunksLoadedThisTick.contains(beaconChunkKey));
        }
        // Close only this recovery beacon's vanilla menu during the same bounded
        // player pass used by the visual audience checks. A newly opened menu
        // may remain visible for at most four ticks; no extra per-tick player scan.
        // The audience/menu pass is due only on tick 1 and every four ticks.
        // Avoid allocating its accumulator on the three intervening ticks.
        boolean hasRingViewer=false;
        boolean hasStabilizationViewer=false;
        if(shouldScanRestorePlayers(cadenceTick,beaconPresent)) {
            RestorePlayerScanState playerScan=s.restorePlayerScan;
            playerScan.reset();
            boolean stabilizationDue=shouldEmitRestoreAudiencePulse(cadenceTick);
            // Do not stop after finding a viewer: a later player may still have
            // the beacon's vanilla menu open and must be closed in this same pass.
            for(var player:level.players()) {
                boolean closeBeaconMenu=player.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu
                    && player.blockPosition().distSqr(activatedBeacon)<144d;
                if(closeBeaconMenu) {
                    player.closeContainer();
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_MENU_CLOSED");
                }
                boolean eligible=player.isAlive() && !player.isRemoved() && !player.isSpectator();
                // These beacon effects use the default 32-block particle packet range,
                // unlike the separate 128-block recovery action-bar/overlay audience.
                boolean ringViewer=eligible
                    && withinRestoreParticleAudience(player.distanceToSqr(beaconX,beaconY,beaconZ));
                boolean stabilizationViewer=eligible && stabilizationDue
                    && withinRestoreParticleAudience(player.distanceToSqr(beaconX,beaconY+0.6D,beaconZ));
                playerScan.observe(closeBeaconMenu,eligible,ringViewer,stabilizationViewer);
            }
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_PLAYER_SCAN_COMPLETED playersVisited={} beaconMenusClosed={} fullPass=true",
                playerScan.playersVisited,playerScan.beaconMenusClosed);
            hasRingViewer=playerScan.hasRingViewer;
            hasStabilizationViewer=playerScan.hasStabilizationViewer;
        }
        // A visible, bounded stabilization pulse at the activated physical beacon.
        // Only query an already loaded chunk; never resurrect a removed beacon
        // or force chunk generation during recovery.
        if(shouldEmitRestoreAudiencePulse(cadenceTick) && hasStabilizationViewer) {
            double cx=beaconX, ringCenterY=beaconY+RESTORE_RING_HEIGHT_OFFSET,
                sparkCenterY=beaconY+0.6D, cz=beaconZ;
            // Contract the recovery vortex as the saved terrain is restored.
            // Clamp corrupt/out-of-range indices so the effect stays bounded.
            double contraction=recoveryVortexContraction(s.restoreIndex,s.snapshots.size());
            // Two contrasting violet particle types distinguish the counter-rotating
            // inner and outer rings without increasing particle count or chunk work.
            // Only 24 particles per second; no terrain edits or chunk loads.
            for(int i=0;i<RESTORE_RING_PARTICLE_COUNT;i++) {
                double angle=i*Math.PI/12.0D + (i%2==0 ? 1.0D : -1.0D)*animationTick*0.045D;
                // The fracture closes in a slow, bounded violet heartbeat. Keep the
                // inner and outer rings separated even at maximum contraction.
                double radius=(i%2==0?1.3D:2.1D)
                    +0.16D*Math.sin(animationTick*0.025D);
                radius*=contraction;
                // A slow vertical wave makes the rings visibly breathe without
                // increasing particle count or modifying nearby blocks.
                double height=(i%2==0?0.0D:RESTORE_RING_OUTER_HEIGHT_OFFSET)
                    +RESTORE_RING_VERTICAL_WAVE*Math.sin(angle*2.0D+animationTick*0.035D);
                DustParticleOptions ringDust=(i&1)==0
                    ? RESTORE_VIOLET_DUST : RESTORE_VIOLET_ACCENT_DUST;
                level.sendParticles(ringDust,
                    cx+Math.cos(angle)*radius,ringCenterY+height,cz+Math.sin(angle)*radius,
                    1,0,0.08D,0,0.015D);
            }
            // Four rising violet sparks connect the ground-level rings to the
            // beacon beam. Deterministic positions avoid per-tick random work;
            // the effect stays inside a 2.3-block radius and 3-block height.
            for(int i=0;i<RESTORE_RISING_SPARK_PARTICLE_COUNT;i++) {
                double phase=animationTick*0.055D+i*Math.PI/2.0D;
                double height=0.25D+((animationTick*0.04D+i*0.25D)%2.5D);
                level.sendParticles(RESTORE_VIOLET_ACCENT_DUST,
                    cx+Math.cos(phase)*0.75D*contraction,sparkCenterY+height,
                    cz+Math.sin(phase)*0.75D*contraction,
                    1,0,0,0,0);
            }
            if(CI_TEST && s.restoreTicks==1)
                LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_INITIAL_BEACON_PULSE_EMITTED ring={} sparks={} viewer=true",
                    RESTORE_RING_PARTICLE_COUNT,RESTORE_RISING_SPARK_PARTICLE_COUNT);
            if(CI_TEST && s.restoreTicks==20)
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE viewerScan=shared");
        }
        if(CI_TEST && s.restoreTicks==1) {
            verifyBeaconInteractionGuard();
            verifyCanonicalSnapshotBlockState(level);
        }
        // Exercise boundary and overflow-safe checks in the disposable
        // integration world; this does not mutate any terrain.
        if(CI_TEST && s.restoreTicks==1
            && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest")) {
            int y=level.getMinBuildHeight();
            if(!isRestorableSnapshotPosition(new BlockPos(s.x+RADIUS,y,s.z),s,level)
                || isRestorableSnapshotPosition(new BlockPos(s.x+RADIUS+1,y,s.z),s,level)
                || isRestorableSnapshotPosition(new BlockPos(s.x,y-1,s.z),s,level)
                || isRestorableSnapshotPosition(new BlockPos(Integer.MAX_VALUE,y,Integer.MIN_VALUE),s,level))
                throw new IllegalStateException("CI restoration bounds guard failed");
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_BOUNDS_GUARD_VERIFIED");
        }
        boolean sendProgress=shouldSendRestoreProgress(cadenceTick,!s.snapshots.isEmpty());
        boolean sendOverlay=shouldSendRestoreOverlay(cadenceTick);
        if(sendProgress || sendOverlay) {
            int percent=sendProgress?restorationPercent(s.restoreIndex,s.snapshots.size()):0;
            float remaining=sendOverlay && !s.snapshots.isEmpty()
                ? Math.max(0f,(s.restoreIndex+1f)/s.snapshots.size()) : 0f;
            int audiencePlayersVisited=0;
            // Progress and overlay share one bounded player traversal. At the
            // 40-tick boundary both payloads are sent from this same pass.
            for(var player:level.players()) {
                if(CI_TEST) audiencePlayersVisited++;
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if(!withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z)) continue;
                if(sendProgress) {
                    Component progressMessage=s.unloadedSnapshotsPending
                        ? recoveryViolet("✦ ОЖИДАНИЕ ЗАГРУЗКИ ОБЛАСТИ §7— подойдите ближе к оставшемуся разлому")
                        : recoveryViolet("✦ СТАБИЛИЗАЦИЯ §f"+percent+"% §7— восстановлено "+s.restoredBlocks+" блоков");
                    player.displayClientMessage(progressMessage,true);
                }
                if(sendOverlay)
                    RiftNetwork.send(player,remaining,false,0,s.x,s.z,s.naturalBeaconPos,true);
            }
            if(CI_TEST && sendProgress && sendOverlay && !s.ciRestoreUiAudiencePassLogged) {
                s.ciRestoreUiAudiencePassLogged=true;
                LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_UI_AUDIENCE_SHARED_PASS progress=true overlay=true playersVisited={} singleTraversal=true",
                    audiencePlayersVisited);
            }
        }
        int restoredThisTick=0,checks=0;
        boolean wasWaitingForUnloadedChunks=s.unloadedSnapshotsPending;
        // Never revisit a small pending set dozens of times in one tick.
        // The isolated low-distance client probe scans one snapshot per tick so
        // chunk unloading can occur before the bounded recovery queue is exhausted.
        boolean deferredClientProbe=CI_TEST && Boolean.getBoolean("blackfrequency.ciDeferredChunkProbe");
        int configuredScanBudget=restoreSnapshotScanBudget(deferredClientProbe);
        if(CI_TEST && s.restoreTicks==1)
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_SCAN_BUDGET_SELECTED value={} deferredClient={}",
                configuredScanBudget,deferredClientProbe);
        int scanBudget=Math.min(configuredScanBudget,Math.max(0,s.restoreIndex+1));
        // A failed block write must not be attempted repeatedly in one tick.
        // Other loaded snapshots can still recover; retry this position next tick.
        LongOpenHashSet failedWritesThisTick=beginRestoreTick(s);
        while(s.restoreIndex>=0 && restoredThisTick<12 && checks<scanBudget) {
            // A single unloaded chunk must not block recovery in other loaded chunks.
            // Scan at most 48 candidates per tick and retain unloaded snapshots
            // in the pending prefix; never load chunks just to restore terrain.
            if(s.restoreScan<0 || s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
            int candidate=s.restoreScan;
            CompoundTag entry=s.snapshots.get(candidate);
            // Never let a truncated NBT entry default to (0,0,0) or silently
            // claim that an unknown block state was restored. Quarantine the
            // irrecoverable record without touching terrain or other snapshots.
            if(!entry.contains("pos",Tag.TAG_LONG)
                || !entry.contains("state",Tag.TAG_COMPOUND)
                || !entry.getCompound("state").contains("Name",Tag.TAG_STRING)) {
                s.skippedInvalid++;
                warnInvalidSnapshotOnce(s,"malformed payload",candidate);
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_SNAPSHOT_SKIPPED index={}",candidate);
                if(candidate!=s.restoreIndex) {
                    s.snapshots.set(candidate,s.snapshots.get(s.restoreIndex));
                    s.snapshots.set(s.restoreIndex,entry);
                }
                s.restoreIndex--;
                if(s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
                checks++;
                continue;
            }
            long packedPosition=entry.getLong("pos");
            BlockPos.MutableBlockPos pos=s.restoreCursor;
            pos.set(BlockPos.getX(packedPosition),BlockPos.getY(packedPosition),BlockPos.getZ(packedPosition));
            checks++;
            // Validate saved coordinates before querying chunk availability.
            // Malformed snapshots outside the bounded fracture must be retired
            // even when their referenced chunk is unloaded; never load it.
            if(!isRestorableSnapshotPosition(pos,s,level)) {
                s.skippedInvalid++;
                warnInvalidSnapshotOnce(s,"outside fracture bounds",pos);
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST OUT_OF_BOUNDS_SNAPSHOT_SKIPPED x={} y={} z={}",
                    pos.getX(),pos.getY(),pos.getZ());
                if(candidate!=s.restoreIndex) {
                    s.snapshots.set(candidate,s.snapshots.get(s.restoreIndex));
                    s.snapshots.set(s.restoreIndex,entry);
                }
                s.restoreIndex--;
                if(s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
                continue;
            }
            // World-border changes after the fracture must never authorize a
            // recovery write outside the currently playable world. Retire the
            // snapshot without loading its chunk or touching terrain.
            if(!level.getWorldBorder().isWithinBounds(pos)) {
                s.skippedInvalid++;
                warnInvalidSnapshotOnce(s,"outside world border",pos);
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BORDER_SNAPSHOT_SKIPPED x={} y={} z={}",
                    pos.getX(),pos.getY(),pos.getZ());
                if(candidate!=s.restoreIndex) {
                    s.snapshots.set(candidate,s.snapshots.get(s.restoreIndex));
                    s.snapshots.set(s.restoreIndex,entry);
                }
                s.restoreIndex--;
                if(s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
                continue;
            }
            // A failed write is retried next tick, but must never prevent
            // quarantine of an invalid snapshot sharing the same position.
            if(failedWritesThisTick.contains(pos.asLong())) {
                // Duplicate records can share a position but contain different block states.
                // Validate this rare path before deferring the retry, or a corrupt air record
                // could remain hidden behind a repeatedly rejected valid snapshot.
                BlockState duplicateOriginal=readCanonicalSnapshotBlockState(level,entry.getCompound("state"));
                if(duplicateOriginal.isAir()) {
                    s.skippedInvalid++;
                    warnInvalidSnapshotOnce(s,"duplicate has invalid or non-canonical block state",pos);
                    if(CI_TEST && entry.getBoolean("ciDuplicateInvalid")) {
                        if(s.restoreTicks!=1)
                            throw new IllegalStateException("CI duplicate invalid snapshot was not quarantined in the failed-write tick");
                        LOGGER.info("BLACK_FREQUENCY_TEST DUPLICATE_INVALID_SNAPSHOT_QUARANTINED tick={}",s.restoreTicks);
                    }
                    if(candidate!=s.restoreIndex) {
                        s.snapshots.set(candidate,s.snapshots.get(s.restoreIndex));
                        s.snapshots.set(s.restoreIndex,entry);
                    }
                    s.restoreIndex--;
                    if(s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
                    continue;
                }
                s.restoreScan--;
                continue;
            }
            int restoreChunkX=pos.getX()>>4;
            int restoreChunkZ=pos.getZ()>>4;
            long restoreChunkKey=(((long)restoreChunkX)<<32)^(restoreChunkZ&0xffffffffL);
            // The server thread is single-threaded here, so all snapshots in one chunk
            // share the same availability result for this tick. Avoid repeating hasChunk.
            chunkAvailabilityCandidates++;
            boolean restoreChunkLoaded;
            if(s.restoreChunksCheckedThisTick.add(restoreChunkKey)) {
                chunkAvailabilityProbes++;
                restoreChunkLoaded=level.hasChunk(restoreChunkX,restoreChunkZ);
                if(restoreChunkLoaded) s.restoreChunksLoadedThisTick.add(restoreChunkKey);
            } else {
                chunkAvailabilityCacheHits++;
                restoreChunkLoaded=s.restoreChunksLoadedThisTick.contains(restoreChunkKey);
                if(restoreChunkKey==beaconChunkKey) beaconChunkSnapshotCacheHit=true;
            }
            if(!restoreChunkLoaded) {
                if(s.deferredRestoreChunks.add(restoreChunkKey) && CI_TEST)
                    LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_DEFERRED x={} y={} z={} chunkX={} chunkZ={} loadedOnly=true",
                        pos.getX(),pos.getY(),pos.getZ(),restoreChunkX,restoreChunkZ);
                s.unloadedSnapshotsPending=true;
                if(CI_TEST && Boolean.getBoolean("blackfrequency.ciDeferredChunkTest")
                    && s.restoreTicks==1)
                    LOGGER.info("BLACK_FREQUENCY_TEST DEFERRED_CHUNK_SKIPPED x={} z={}",pos.getX(),pos.getZ());
                s.restoreScan--;
                continue;
            }
            if(s.deferredRestoreChunks.remove(restoreChunkKey) && CI_TEST)
                LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_REENTERED chunkX={} chunkZ={} loadedOnly=true",
                    restoreChunkX,restoreChunkZ);
            // Only retire a snapshot after a successful write or a deliberate
            // preservation decision. A failed setBlock must remain pending so
            // another tick (or a server restart) can retry without data loss.
            BlockState current=level.getBlockState(pos);
            boolean flowingFluid=(current.is(Blocks.WATER) || current.is(Blocks.LAVA))
                && !current.getFluidState().isSource();
            if(!current.isAir() && !flowingFluid) {
                s.skippedOccupied++;
            } else {
                BlockState original=readCanonicalSnapshotBlockState(level,entry.getCompound("state"));
                if(!original.isAir()) {
                    // Simulate one rejected write in the disposable integration
                    // world to prove that the snapshot survives and is retried.
                    boolean testWriteFailure=CI_TEST
                        && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest")
                        && s.restoreTicks==1 && checks==1;
                    if(testWriteFailure || !level.setBlock(pos,original,2|16)
                        // A mod may intercept the write and report success while
                        // replacing the block. Keep the snapshot pending until
                        // the actual terrain matches the saved state.
                        || !level.getBlockState(pos).equals(original)) {
                        failedWritesThisTick.add(pos.asLong());
                        if(testWriteFailure) {
                            s.ciRejectedRestorePos=pos.asLong();
                            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_WRITE_DEFERRED x={} y={} z={} tick={}",pos.getX(),pos.getY(),pos.getZ(),s.restoreTicks);
                        }
                        s.restoreScan--;
                        continue;
                    }
                    restoredThisTick++;s.restoredBlocks++;
                    if(CI_TEST && pos.asLong()==s.ciRejectedRestorePos) {
                        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_WRITE_RETRIED x={} y={} z={} tick={}",pos.getX(),pos.getY(),pos.getZ(),s.restoreTicks);
                        s.ciRejectedRestorePos=Long.MIN_VALUE;
                    }
                    if(CI_TEST && flowingFluid && s.ciFlowingReplaced++==0)
                        LOGGER.info("BLACK_FREQUENCY_TEST FLOWING_FLUID_REPLACED x={} y={} z={}",pos.getX(),pos.getY(),pos.getZ());
                    if(CI_TEST && Boolean.getBoolean("blackfrequency.ciDeferredChunkTest")
                        && s.restoredBlocks==1 && s.restoreIndex>=0)
                        LOGGER.info("BLACK_FREQUENCY_TEST DEFERRED_RESTORATION_PROGRESS pending={} restored={}",s.restoreIndex+1,s.restoredBlocks);
                    if(shouldEmitRestoreBlockCue(s.restoredBlocks))
                        level.sendParticles(RESTORE_VIOLET_DUST,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,
                            RESTORE_BLOCK_CUE_PARTICLE_COUNT,.45,.45,.45,.07);
                } else {
                    // Fractures never record air. An unknown block id decoded as
                    // air is corrupt data, not a successfully restored block.
                    s.skippedInvalid++;
                    warnInvalidSnapshotOnce(s,"block state is invalid or non-canonical",pos);
                }
            }
            // O(1) swap keeps all still-pending entries in [0,restoreIndex].
            if(candidate!=s.restoreIndex) {
                s.snapshots.set(candidate,s.snapshots.get(s.restoreIndex));
                s.snapshots.set(s.restoreIndex,entry);
            }
            s.restoreIndex--;
            if(s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
        }
        if(CI_TEST && s.restoreTicks==1) {
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_BEACON_CHUNK_SNAPSHOT_REUSE sameTick=true reused={}",
                beaconChunkSnapshotCacheHit);
            int uniqueChunks=s.restoreChunksCheckedThisTick.size();
            boolean cacheConsistent=chunkAvailabilityCandidates==chunkAvailabilityProbes+chunkAvailabilityCacheHits
                && chunkAvailabilityProbes==uniqueChunks
                && s.restoreChunksLoadedThisTick.size()<=uniqueChunks;
            if(!cacheConsistent)
                throw new IllegalStateException("CI restore chunk-availability cache accounting failed");
            if(Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest")
                && chunkAvailabilityCacheHits<=0)
                throw new IllegalStateException("CI restore did not reuse a chunk-availability result");
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_CHUNK_AVAILABILITY_CACHE_VERIFIED candidates={} probes={} cacheHits={} uniqueChunks={} loadedChunks={} transient=true loadedOnly=true",
                chunkAvailabilityCandidates,chunkAvailabilityProbes,chunkAvailabilityCacheHits,uniqueChunks,
                s.restoreChunksLoadedThisTick.size());
        }
        s.unloadedSnapshotsPending=!s.deferredRestoreChunks.isEmpty() && s.restoreIndex>=0;
        if(s.unloadedSnapshotsPending && !wasWaitingForUnloadedChunks) {
            int notifiedPlayers=0;
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if(!withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z)) continue;
                player.displayClientMessage(recoveryViolet(
                    "✦ ОЖИДАНИЕ ЗАГРУЗКИ ОБЛАСТИ §7— подойдите ближе к оставшемуся разлому"),true);
                notifiedPlayers++;
            }
            if(CI_TEST)
                LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_UNLOADED_CHUNK_HINT_SENT players={} loadedOnly=true",
                    notifiedPlayers);
        }
        // Avoid phantom rift rings after a beacon is removed, and never
        // query or generate an unloaded chunk just for cosmetic particles.
        // Skip cosmetic work when nobody can see the recovery beacon.
        // The shared viewer scan reads only player positions and never requests a chunk.
        if(shouldEmitRestoreHelix(cadenceTick) && hasRingViewer) {
            double angle=animationTick*.17;
            // A slow breathing ring makes recovery visibly alive without
            // adding particles, world edits, or forced chunk loads.
            // Keep the outer recovery helix synchronized with the shrinking inner vortex.
            // This is cosmetic only: no terrain edits or extra particle packets.
            double radius=(3.4D+0.45D*Math.sin(animationTick*.055D))
                * recoveryVortexContraction(s.restoreIndex,s.snapshots.size());
            for(int k=0;k<RESTORE_HELIX_ANCHOR_COUNT;k++) {
                // Counter-rotate the two alternating strands in the horizontal plane too.
                // Their vertical phases already travel in opposite directions.
                double theta=angle*(k%2==0 ? 1.0D : -1.0D)+k*Math.PI*.25;
                double x=beaconX+Math.cos(theta)*radius;
                double z=beaconZ+Math.sin(theta)*radius;
                // Stagger each wisp vertically into a rotating double helix.
                // Keep the same 16 particles per pulse; avoid the old synchronized
                // height reset that made the entire ring jump every 80 ticks.
                // Opposite angular travel for the two alternating strands; the old
                // shared phase made both wisps climb and descend in lockstep.
                double phase=(animationTick*.045D*(k%2==0 ? 1.0D : -1.0D)
                    + k*Math.PI*.25D)%(Math.PI*2.0D);
                double y=beaconY+1.1D*(1.0D+Math.sin(phase));
                // Counter-rotating strands alternate cached deep and bright violet Dust.
                DustParticleOptions particle=(k&1)==0
                    ? RESTORE_VIOLET_DUST : RESTORE_VIOLET_ACCENT_DUST;
                level.sendParticles(particle,x,y,z,RESTORE_HELIX_MOTES_PER_ANCHOR,
                    .12,.15,.12,.025);
            }
        }
        // A destroyed or unloaded beacon must not leave a phantom recovery glow.
        // hasChunkAt only inspects loaded chunks; it never forces chunk generation.
        if(shouldEmitRestoreAudiencePulse(cadenceTick) && beaconPresent) {
            // The recovery glow belongs to the fracture, not every player in
            // the dimension. Avoid particle traffic for distant or inactive players.
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                BlockPos p=player.blockPosition();
                // Bound all three axes before squaring: distant players (including
                // those high above the rift) should not receive recovery particles.
                // State stores only horizontal rift coordinates; the activated
                // physical beacon supplies the vertical recovery anchor.
                if(!withinRecoveryAudience(p,s.x,activatedBeacon.getY(),s.z)) continue;
                level.sendParticles(RESTORE_VIOLET_ACCENT_DUST,p.getX()+.5,p.getY()+1.5,p.getZ()+.5,
                    RESTORE_AUDIENCE_PARTICLE_COUNT,RESTORE_AUDIENCE_SPREAD_XZ,RESTORE_AUDIENCE_SPREAD_Y,RESTORE_AUDIENCE_SPREAD_XZ,.03);
            }
        }
        // Hold the final stabilization glow long enough to be visible in-game.
        if(s.restoreIndex<0 && s.restoreTicks>=100) {
            s.deferredRestoreChunks.clear();
            s.unloadedSnapshotsPending=false;
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest") && s.skippedInvalid!=3)
                throw new IllegalStateException("CI malformed/out-of-bounds/duplicate snapshot quarantine failed: "+s.skippedInvalid);
            s.restoring=false;s.restored=true;s.finished=true;s.snapshots.clear();
            s.retainedSnapshotEstimateBytes=0L;
            if(s.skippedInvalid>0) {
                LOGGER.warn("BLACK FREQUENCY: recovery completed with {} invalid snapshot entries quarantined; these entries were not applied to world state",
                    s.skippedInvalid);
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_INVALID_SNAPSHOT_SUMMARY_EMITTED count={} singleSummary=true",
                    s.skippedInvalid);
            }
            for(var player:level.players()) RiftNetwork.send(player,0f,false,0,s.x,s.z,s.naturalBeaconPos,false);
            if(CI_TEST) {
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored={} destroyed={} skippedOccupied={} skippedInvalid={} flowingReplaced={}",
                    s.restoredBlocks,s.edits,s.skippedOccupied,s.skippedInvalid,s.ciFlowingReplaced);
                LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_COMPLETION_LOSS_COUNTERS overflow={} missing={} totalSkipped={}",
                    s.snapshotOverflowEntries,s.snapshotMissingEntries,
                    (long)s.skippedOccupied+(long)s.skippedInvalid+(long)s.snapshotOverflowEntries+(long)s.snapshotMissingEntries);
            }
            // A protection mod's earlier cancellation must preserve the physical beacon.
            // Actual mining is exercised by the client with a held left mouse button.
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest")) {
                if(s.naturalBeaconPos==Long.MIN_VALUE || level.players().isEmpty())
                    throw new IllegalStateException("CI recovery beacon missing after restoration");
                BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
                if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,beacon))
                    throw new IllegalStateException("CI recovery beacon ownership marker missing after restoration");
                var miner=level.players().get(0);
                boolean previousMayBuild=miner.getAbilities().mayBuild;
                try {
                    miner.getAbilities().mayBuild=false;
                    var adventureEvent=new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                        level,beacon,level.getBlockState(beacon),miner);
                    new RiftController().onRecoveryBeaconBreak(adventureEvent);
                    if(level.getBlockState(beacon).isAir()
                        || s.naturalBeaconPos!=beacon.asLong() || s.naturalBeaconId==null)
                        throw new IllegalStateException("CI adventure-mode synthetic break consumed recovery beacon");
                    LOGGER.info("BLACK_FREQUENCY_TEST REAL_PLAYER_ADVENTURE_BEACON_BREAK_REJECTED mayBuild=false blockPreserved=true ownershipPreserved=true");
                } finally {
                    miner.getAbilities().mayBuild=previousMayBuild;
                }
                var deniedEvent=new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                    level,beacon,level.getBlockState(beacon),level.players().get(0));
                deniedEvent.setCanceled(true);
                new RiftController().onRecoveryBeaconBreak(deniedEvent);
                if(!deniedEvent.isCanceled() || !level.getBlockState(beacon).is(Blocks.BEACON)
                    || s.naturalBeaconPos!=beacon.asLong() || s.naturalBeaconId==null)
                    throw new IllegalStateException("CI canceled beacon break mutated recovery fixture");
                LOGGER.info("BLACK_FREQUENCY_TEST CANCELED_BEACON_OWNERSHIP_PRESERVED");
                LOGGER.info("BLACK_FREQUENCY_TEST CANCELED_BEACON_BREAK_PRESERVED x={} y={} z={}",
                    beacon.getX(),beacon.getY(),beacon.getZ());
                LOGGER.info("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_READY_FOR_REAL_PLAYER x={} y={} z={}",
                    beacon.getX(),beacon.getY(),beacon.getZ());
            }
            for(var player:level.players()) {
                // Completion is a local event. Do not interrupt distant players
                // or spectators with a full-screen overlay.
                // Keep the physical beacon visible; only the action bar receives the result.
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()
                    || !withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z)) continue;
                player.displayClientMessage(restorationCompletionMessage(
                    s.restoredBlocks,s.skippedOccupied,s.skippedInvalid,
                    s.snapshotOverflowEntries,s.snapshotMissingEntries),true);
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_COMPLETION_ACTIONBAR_SENT restored={} skippedOccupied={} skippedInvalid={} player=true",
                    s.restoredBlocks,s.skippedOccupied,s.skippedInvalid);
            }
        }
        s.setDirty();
    }

    private static double eyeDistanceSquaredToBlock(net.minecraft.world.phys.Vec3 eye,BlockPos pos) {
        if(eye==null || !Double.isFinite(eye.x) || !Double.isFinite(eye.y) || !Double.isFinite(eye.z))
            return Double.NaN;
        double minX=pos.getX();
        double minY=pos.getY();
        double minZ=pos.getZ();
        double closestX=Math.max(minX,Math.min(eye.x,minX+1.0D));
        double closestY=Math.max(minY,Math.min(eye.y,minY+1.0D));
        double closestZ=Math.max(minZ,Math.min(eye.z,minZ+1.0D));
        return eye.distanceToSqr(closestX,closestY,closestZ);
    }

    /** One strict survival-reach predicate shared by activation, pad placement and mining. */
    private static boolean withinBeaconInteractionReach(double distanceSquared) {
        double reach=CI_BEACON_INTERACTION_MAX_DISTANCE;
        return Double.isFinite(distanceSquared) && distanceSquared>=0.0D
            && distanceSquared<=reach*reach;
    }

    /** Adventure-mode players cannot consume the recovery objective through synthetic break events. */
    private static boolean mayMineRecoveryBeacon(boolean alive,boolean removed,boolean spectator,boolean mayBuild) {
        return alive && !removed && !spectator && mayBuild;
    }

    // Exercise the Java helper itself so the Python fixture cannot silently drift
    // from the geometry used when a real Forge break event captures player reach.
    private static void verifyCiBeaconBreakReachGeometry() {
        BlockPos beacon=new BlockPos(-2,64,-3);
        double inside=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(-1.5D,64.5D,-2.5D),beacon);
        double boundaryX=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(3.5D,64.5D,-2.5D),beacon);
        double boundaryY=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(-1.5D,69.5D,-2.5D),beacon);
        double boundaryZ=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(-1.5D,64.5D,2.5D),beacon);
        double outside=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(3.5001D,64.5D,-2.5D),beacon);
        double invalid=eyeDistanceSquaredToBlock(
            new net.minecraft.world.phys.Vec3(Double.NaN,64.5D,-2.5D),beacon);
        double reachSquared=CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE;
        boolean exactBoundaryAccepted=withinBeaconInteractionReach(boundaryX)
            && withinBeaconInteractionReach(boundaryY)
            && withinBeaconInteractionReach(boundaryZ);
        boolean outsideRejected=!withinBeaconInteractionReach(outside);
        boolean invalidRejected=!withinBeaconInteractionReach(invalid)
            && !withinBeaconInteractionReach(-1.0D)
            && !withinBeaconInteractionReach(Double.POSITIVE_INFINITY)
            && !withinBeaconInteractionReach(Double.NEGATIVE_INFINITY);
        boolean survivalAllowed=mayMineRecoveryBeacon(true,false,false,true);
        boolean creativeAllowed=mayMineRecoveryBeacon(true,false,false,true);
        boolean adventureRejected=!mayMineRecoveryBeacon(true,false,false,false);
        boolean spectatorRejected=!mayMineRecoveryBeacon(true,false,true,true);
        boolean deadRejected=!mayMineRecoveryBeacon(false,false,false,true);
        boolean removedRejected=!mayMineRecoveryBeacon(true,true,false,true);
        if(!survivalAllowed || !creativeAllowed || !adventureRejected || !spectatorRejected
            || !deadRejected || !removedRejected)
            throw new IllegalStateException("CI recovery beacon build-permission guard regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_BREAK_BUILD_PERMISSION_GUARD_VERIFIED survival=true creative=true adventureRejected=true spectatorRejected=true deadRejected=true removedRejected=true");
        if(inside!=0.0D
            || Math.abs(boundaryX-reachSquared)>1.0E-9D
            || Math.abs(boundaryY-reachSquared)>1.0E-9D
            || Math.abs(boundaryZ-reachSquared)>1.0E-9D
            || !(outside>reachSquared)
            || Double.isFinite(invalid)
            || !exactBoundaryAccepted || !outsideRejected || !invalidRejected)
            throw new IllegalStateException("CI Java beacon break AABB reach geometry regression");
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_BREAK_REACH_AABB_VERIFIED boundary={} axes=3 negativeCoordinates=true outsideRejected=true invalidRejected=true",
            reachSquared);
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_BREAK_REACH_GUARD_VERIFIED exactBoundary=true outsideRejected=true invalidRejected=true eventGuard=true");
    }

    private static void verifyCiNaturalBeaconBreakNoDrop(ServerLevel level,State s) {
        if(!CI_TEST || ciPendingNaturalBeaconBreakVerificationPos==null) return;
        BlockPos pos=ciPendingNaturalBeaconBreakVerificationPos;
        if(!level.hasChunkAt(pos))
            throw new IllegalStateException("CI broken beacon chunk unloaded before no-drop verification");
        UUID playerId=ciPendingNaturalBeaconBreakVerificationPlayer;
        net.minecraft.server.level.ServerPlayer player=level.players().stream()
            .filter(candidate -> candidate.getUUID().equals(playerId))
            .findFirst().orElse(null);
        if(player==null || !player.isAlive() || player.isRemoved() || player.isSpectator())
            throw new IllegalStateException("CI beacon miner is unavailable during no-drop verification");
        boolean survivalMining=ciPendingNaturalBeaconBreakVerificationSurvival;
        double distanceSquared=ciPendingNaturalBeaconBreakVerificationDistanceSquared;
        boolean withinReach=withinBeaconInteractionReach(distanceSquared);
        double distanceAfterBreakSquared=player.distanceToSqr(
            pos.getX()+.5D,pos.getY()+.5D,pos.getZ()+.5D);
        if(!Double.isFinite(distanceAfterBreakSquared))
            throw new IllegalStateException("CI beacon miner position became non-finite after break");
        if(!survivalMining || !withinReach)
            throw new IllegalStateException("CI beacon must be mined in survival within normal interaction reach");
        int inventoryBeacons=player.getInventory().countItem(net.minecraft.world.item.Items.BEACON);
        int nearbyBeaconDrops=level.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity.class,
            new net.minecraft.world.phys.AABB(
                pos.getX()-2.0D,pos.getY()-2.0D,pos.getZ()-2.0D,
                pos.getX()+3.0D,pos.getY()+3.0D,pos.getZ()+3.0D),
            item -> item.getItem().is(net.minecraft.world.item.Items.BEACON)).size();
        boolean blockAir=level.getBlockState(pos).isAir();
        boolean ownershipCleared=s.naturalBeaconPos==Long.MIN_VALUE && s.naturalBeaconId==null;
        if(inventoryBeacons!=0 || nearbyBeaconDrops!=0 || !blockAir || !ownershipCleared)
            throw new IllegalStateException("CI real beacon break produced a drop or retained its objective");
        LOGGER.info("BLACK_FREQUENCY_TEST REAL_PLAYER_BEACON_BREAK_NO_DROP_VERIFIED survival=true withinReach=true distanceSquared={} capturedAtBreak=true inventoryBeacons={} nearbyDrops={} blockAir=true ownershipCleared=true loadedOnly=true",
            distanceSquared,inventoryBeacons,nearbyBeaconDrops);
        ciPendingNaturalBeaconBreakVerificationPos=null;
        ciPendingNaturalBeaconBreakVerificationPlayer=null;
        ciPendingNaturalBeaconBreakVerificationSurvival=false;
        ciPendingNaturalBeaconBreakVerificationDistanceSquared=Double.NaN;
    }

    @SubscribeEvent
    public void onTick(TickEvent.LevelTickEvent event) {
        if(event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
           || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        State s = level.getDataStorage().computeIfAbsent(State::load, State::new, "blackfrequency_finale");
        if(initializeLegacyAnchorFromSpawn(s,level.getSharedSpawnPos()))
            LOGGER.warn("BLACK FREQUENCY: initialized legacy fracture anchor from current world spawn without resetting its cursor");
        verifyCiNaturalBeaconBreakNoDrop(level,s);
        // Validate the real player fall-flying flag once in disposable CI worlds.
        // The probe restores the original chest item and flight state immediately.
        if(CI_TEST && (!ciActualElytraStateVerified || !ciActualPassengerStateVerified)) {
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isCreative()
                    || player.isSpectator() || player.getAbilities().flying
                    || player.isFallFlying() || player.isPassenger()) continue;
                if(!ciActualElytraStateVerified) {
                    verifyActualElytraFlightImmunity(player);
                    ciActualElytraStateVerified=true;
                }
                if(!ciActualPassengerStateVerified) {
                    verifyActualPassengerGravityImmunity(level,player);
                    ciActualPassengerStateVerified=true;
                }
                break;
            }
        }
        if(s.surfaceCueCooldownTicks>0) s.surfaceCueCooldownTicks--;
        if(s.restoring) {
            applyRestorationGravityFade(level,s);
            restore(level,s);
            return;
        }
        // Keep clients synchronized even on old worlds or after the finale.
        if(level.getGameTime()%(s.started && !s.finished ? 10L : 100L)==0) {
            float progress=s.restored ? 0f : s.finished ? 1f : Math.max(0f, Math.min(1f,(s.elapsed-3f*DAY)/(7f*DAY)));
            for(var player : level.players()) RiftNetwork.send(player,progress,!s.restored && (s.started || s.finished), s.finaleTicks,s.x,s.z,s.naturalBeaconPos,s.restoring);
        }
        // Once the beacon finishes, do not advance the finale clock or apply
        // gravity impulses again. "finished" alone is not sufficient: it also
        // describes a fully fractured world waiting for restoration.
        if(s.restored) {
            if(CI_TEST && !s.ciRestoredGravityGuardLogged) {
                LOGGER.info("BLACK_FREQUENCY_TEST RESTORED_GRAVITY_DISABLED finaleTicks={}",s.finaleTicks);
                s.ciRestoredGravityGuardLogged=true;
            }
            return;
        }
        // Destruction may be complete while the world is still fractured.
        // Keep the gravity field alive until a beacon starts restoration.
        if(!s.initialized) {
            if(CI_TEST) verifySurfaceFirstScanMigration();
            s.initialized=true;
            BlockPos spawn=level.getSharedSpawnPos();s.x=spawn.getX();s.z=spawn.getZ();
            s.surfaceFirstScan=true;
            s.centerOutScan=true;
            s.centerOutIndex=0;
            s.cursorX=0;s.cursorZ=0;
            // Integer.MIN_VALUE means the next already-loaded column still needs
            // its own heightmap-derived starting Y. This sentinel is persisted.
            s.cursorY=Integer.MIN_VALUE;
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_SCAN_ORDER surface-first center-out");
            s.setDirty();
        }
        if(CI_TEST && !s.ciCanopyWorldFixtureVerified && level.getGameTime()%20L==0L)
            s.ciCanopyWorldFixtureVerified=verifyFissureCanopyTraversalInWorld(level,s);
        if(quarantineInvalidLegacyFractureCursor(s,level.getMinBuildHeight(),level.getMaxBuildHeight())
            && CI_TEST)
            LOGGER.info("BLACK_FREQUENCY_TEST LEGACY_FRACTURE_CURSOR_QUARANTINED");
        if(!s.started) {
            // Do not accelerate world generation before the test player has entered.
            // An empty integrated server may still be creating spawn chunks.
            s.elapsed += (CI_TEST && level.players().isEmpty() && !Boolean.getBoolean("blackfrequency.ciAllowEmptyServer")) ? 1L : TEST_STEP;
            // Narrative beats are delivered as on-screen titles, not chat messages.
            // No manual commands are needed.
            if ((s.elapsed >= DAY*3 && s.elapsed-TEST_STEP < DAY*3) ||
                (s.elapsed >= DAY*6 && s.elapsed-TEST_STEP < DAY*6) ||
                (s.elapsed >= DAY*9 && s.elapsed-TEST_STEP < DAY*9)) {
                String subtitle = s.elapsed < DAY*4
                    ? "Ты уверен, что небо всегда было таким?"
                    : s.elapsed < DAY*7
                    ? "Граница мира становится тоньше."
                    : "Оно больше не удерживает то, что снаружи.";
                for(var player : level.players()) {
                    // Narrative titles are for active participants only. A dead,
                    // removed or spectator client must not receive a forced overlay.
                    if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 90, 35));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§8BLACK FREQUENCY")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7"+subtitle)));
                }
            }
            if(s.elapsed >= DAY*10) {
                s.started=true;s.setDirty();
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST FINALE_STARTED");
                // Spectators and dead/disconnecting players must not choose
                // the fracture location for everyone else in the world.
                // Iterate existing player entities only; never load chunks.
                for(var player : level.players()) {
                    if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                    BlockPos anchor=player.blockPosition();
                    s.x=anchor.getX();s.z=anchor.getZ();
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST FINALE_ANCHOR_VALID x={} z={}",s.x,s.z);
                    break;
                }
                for(var player : level.players()) RiftNetwork.send(player,1f,true,0,s.x,s.z,s.naturalBeaconPos,false);
                // The fracture is local: do not interrupt distant players or
                // spectators with a full-screen finale announcement.
                for(var player : level.players()) {
                    if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                    long dx=(long)player.blockPosition().getX()-s.x;
                    long dz=(long)player.blockPosition().getZ()-s.z;
                    if(Math.abs(dx)>128L || Math.abs(dz)>128L
                        || dx*dx+dz*dz>128L*128L) continue;
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 120, 25));
                    player.connection.send(new ClientboundSetTitleTextPacket(recoveryViolet("§lВЫСВОБОЖДЕНИЕ")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(recoveryViolet("Мир больше не цел.")));
                }
            }
            if(level.getGameTime()%20L==0)s.setDirty();
            return;
        }
        s.finaleTicks=nextNonNegativeTick(s.finaleTicks);
        // Spatial gravity anomalies drift through the rift field. Server-side only;
        // gentle bounded impulses allow movement without overriding vanilla physics.
        if (!s.restoring && s.finaleTicks > 85 && s.finaleTicks % 5 == 0) {
            for (var player : level.players()) {
                // The disposable real-click regression must keep its player alive and stationary
                // from the start of the anomaly phase, not only after the beacon has been placed.
                // Otherwise a random high spawn can kill the player before the first right-click.
                if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest")) {
                    if(player.isAlive() && !player.isRemoved() && !player.isSpectator()) {
                        // Suppressing only rift impulses is insufficient: vanilla gravity
                        // still kills the player after the center-out scan removes spawn support.
                        // Hold the disposable test player until the replacement beacon has
                        // a verified solid standing pad; production physics is untouched.
                        player.setNoGravity(!ciPlayerSafePadReady);
                        if(!ciPlayerSafePadReady) {
                            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                            player.fallDistance=0.0F;
                        }
                    }
                    if(!ciPlayerGravitySuppressionLogged) {
                        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_RIFT_IMPULSES_SUPPRESSED_FOR_BEACON_TEST");
                        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_SUPPRESSED_FOR_BEACON_TEST");
                        ciPlayerGravitySuppressionLogged=true;
                    }
                    continue;
                }
                // Dead, removed, flying, gliding and mounted players are not
                // independent bodies in the rift gravity field.
                applyRiftGravityImpulse(level,s,player);
            }
        }
        // A compact violet fracture plume marks the actual ground-rift center.
        // Resolve the same filtered terrain support used by the four-arm fissure;
        // the raw no-leaves heightmap can otherwise place the plume on village roofs.
        if (!s.restoring && s.finaleTicks > 85 && s.finaleTicks % 10 == 0
            && level.hasChunk(s.x >> 4, s.z >> 4)) {
            BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
            FissureGroundSample groundSample=new FissureGroundSample();
            if(findFissureGroundSample(level,s.x,s.z,cursor,groundSample)) {
                double plumeY=groundSample.surfaceY+1.0;
                boolean witnessed = false;
                for (var viewer : level.players()) {
                    if (!viewer.isAlive() || viewer.isRemoved() || viewer.isSpectator()) continue;
                    double dx = viewer.getX() - s.x;
                    double dy = viewer.getY() - plumeY;
                    double dz = viewer.getZ() - s.z;
                    if (dx * dx + dy * dy + dz * dz <= 48.0 * 48.0) {
                        witnessed = true;
                        break;
                    }
                }
                if (witnessed) {
                    double phase = s.finaleTicks * .055;
                    // A low, jagged cross of violet particles reads as a ground crack.
                    // It is cosmetic only and samples terrain only in already loaded chunks.
                    if(s.finaleTicks%20==0) {
                        FissureEmission emission=emitSurfaceFissure(level,s,phase);
                        if(CI_TEST && emission.samples()>0 && !s.ciSurfaceFissureLogged) {
                            LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples={} accentDust={} violetDust={} edgeDust={} total={} heightmap=motion-blocking ground=terrain-support-filtered averageY={}",
                                emission.samples(),emission.accentDustMotes(),emission.violetDustMotes(),
                                emission.edgeDustMotes(),emission.totalParticles(),emission.averageSurfaceY());
                            LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_EDGE_DUST_EMITTED count={} validArms={} material=dust minScale={} baseScale={} maxScale={} minCount={} baseCount={} maxCount={} fallbackArms={}",
                                emission.edgeDustMotes(),emission.edgeArmsWithSamples(),FISSURE_VIOLET_EDGE_DUST_SMALL.getScale(),
                                FISSURE_VIOLET_EDGE_DUST.getScale(),FISSURE_VIOLET_EDGE_DUST_LARGE.getScale(),
                                emission.edgeDustSmallMotes(),emission.edgeDustBaseMotes(),
                                emission.edgeDustLargeMotes(),emission.edgeFallbackArms());
                            s.ciSurfaceFissureLogged=true;
                        }
                    }
                    // Keep the existing rising strands as a vertical accent above the crack.
                    for (int strand = 0; strand < 4; strand++) {
                        double angle = phase + strand * Math.PI / 2.0;
                        level.sendParticles(FISSURE_VIOLET_ACCENT_DUST,
                            s.x + .5 + Math.cos(angle) * 1.5,
                            plumeY + 1.0 + Math.sin(phase * .7 + strand) * .6,
                            s.z + .5 + Math.sin(angle) * 1.5,
                            2, .12, .65, .12, .035);
                    }
                }
            }
        }
        // Lightweight visual signatures of the same drifting gravity field.
        // Particles only: no extra entities, chunk loads or world edits.
        if (!s.restoring && s.finaleTicks > 85 && s.finaleTicks % 8 == 0) {
            for (var player : level.players()) {
                // The real-click regression suppresses player gravity cues for the whole
                // disposable run, matching the test-only impulse suppression above.
                if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest")) continue;
                // Only mark players actually affected by gravity impulses.
                // Creative and flying players are immune, so avoid misleading
                // particle cues and unnecessary network traffic for them.
                if (!shouldApplyRiftGravity(player)) continue;
                double ax=player.getX()-s.x, ay=player.getY()-level.getSeaLevel(), az=player.getZ()-s.z;
                // Mirror the impulse falloff at the outer boundary. Avoid
                // sending a full particle burst where gravity is almost zero.
                double distanceSquared=ax*ax+ay*ay+az*az;
                if(distanceSquared>=GRAVITY_ANOMALY_OUTER_RADIUS*GRAVITY_ANOMALY_OUTER_RADIUS) continue;
                double fade=gravityAnomalyRadialFade(distanceSquared);
                if(fade<GRAVITY_ANOMALY_PARTICLE_MIN_FADE) continue;
                // Match the server-side gravity field so particles mark actual
                // lift/sink zones instead of an unrelated moving pattern.
                double zone=gravityAnomalyField(player.getX(),player.getZ(),s.finaleTicks,level.getSeed());
                if(zone>GRAVITY_LIFT_ZONE_THRESHOLD) {
                    // Deep-violet motes mark lift; smaller bright-violet sparks define the edge.
                    level.sendParticles(ANOMALY_VIOLET_DUST,
                        player.getX(),player.getY()+.5,player.getZ(),
                        Math.max(1,(int)Math.ceil(9*fade)),1.5,.55,1.5,.055);
                    level.sendParticles(ANOMALY_VIOLET_ACCENT_DUST,
                        player.getX(),player.getY()+.3,player.getZ(),
                        Math.max(1,(int)Math.ceil(3*fade)),.9,.25,.9,.025);
                } else if(zone<-.68) {
                    // Sinking anomalies use the same cached deep-violet palette, not magenta smoke.
                    level.sendParticles(ANOMALY_VIOLET_DUST,
                        player.getX(),player.getY()+.15,player.getZ(),
                        Math.max(1,(int)Math.ceil(7*fade)),1.25,.25,1.25,.018);
                }
            }
        }
        // Recovery must remain available after the last terrain edit: a beacon
        // may not find safe ground until a player returns to a loaded chunk.
        // These checks never generate or force-load terrain.
        // Production recovery path: spawn a physical beacon in an existing loaded
        // chunk near a player. Never replace terrain or force-generate chunks.
        if(s.naturalBeaconPos!=Long.MIN_VALUE && s.finaleTicks%4==0) {
            BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
            // Border membership does not require a loaded chunk. Retire an
            // inaccessible objective even when its old chunk is unloaded, so
            // restoration can offer a new beacon near an active player.
            if(!isValidNaturalBeaconPosition(level,s,beacon)) {
                    // Invalid, out-of-build-height, out-of-radius, or out-of-border
                    // saved anchors are retired without querying or deleting a block.
                    // The next bounded placement pass may offer a new objective.
                    boolean outsideBorder=!level.getWorldBorder().isWithinBounds(beacon);
                    s.naturalBeaconPos=Long.MIN_VALUE;
                    s.naturalBeaconId=null;
                    s.setDirty();
                    if(CI_TEST) LOGGER.info(outsideBorder
                        ? "BLACK_FREQUENCY_TEST OUTSIDE_BORDER_BEACON_RETIRED"
                        : "BLACK_FREQUENCY_TEST INVALID_NATURAL_BEACON_ANCHOR_RETIRED");
            // Only inspect block state when the chunk is already loaded.
            } else if(level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)) {
                if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,beacon)) {
                    boolean beaconBlockPresent=level.getBlockState(beacon).is(Blocks.BEACON);
                    s.naturalBeaconPos=Long.MIN_VALUE;
                    s.naturalBeaconId=null;
                    s.setDirty();
                    if(CI_TEST) LOGGER.info(beaconBlockPresent
                        ? "BLACK_FREQUENCY_TEST NATURAL_BEACON_REPLACED x={} y={} z={}"
                        : "BLACK_FREQUENCY_TEST NATURAL_BEACON_MISSING x={} y={} z={}",
                        beacon.getX(),beacon.getY(),beacon.getZ());
                } else {
                    // A local cached Dust pulse is enough for an idle objective.
                    // Six particles once per second replace the old 30-per-second
                    // portal burst; the loaded-chunk guard above remains unchanged.
                    if(shouldEmitIdleBeaconParticles(s.finaleTicks)
                        && level.players().stream().anyMatch(player ->
                            player.isAlive() && !player.isRemoved() && !player.isSpectator()
                            && withinIdleBeaconParticleAudience(player.distanceToSqr(
                                beacon.getX()+.5D,beacon.getY()+1.2D,beacon.getZ()+.5D)))) {
                        level.sendParticles(BEACON_VIOLET_DUST,
                            beacon.getX()+.5,beacon.getY()+1.2,beacon.getZ()+.5,
                            IDLE_BEACON_PULSE_PARTICLE_COUNT,
                            IDLE_BEACON_PARTICLE_SPREAD_XZ,IDLE_BEACON_PARTICLE_SPREAD_Y,
                            IDLE_BEACON_PARTICLE_SPREAD_XZ,IDLE_BEACON_PARTICLE_SPEED);
                        if(CI_TEST && !s.ciIdleBeaconVioletDustLogged) {
                            LOGGER.info("BLACK_FREQUENCY_TEST IDLE_BEACON_VIOLET_DUST_EMITTED count=6 red=0.48 green=0.14 blue=0.98 scale=0.9");
                            s.ciIdleBeaconVioletDustLogged=true;
                        }
                    }
                    if(shouldSendIdleBeaconHint(s.restoring,s.restored,s.finaleTicks)) for(var player:level.players())
                        if(player.isAlive() && !player.isRemoved() && !player.isSpectator()
                            && withinRecoveryAudience(player.blockPosition(),
                                beacon.getX(),beacon.getY(),beacon.getZ()))
                            player.displayClientMessage(recoveryViolet(
                                "✦ Маяк стабилизации: §f"+beacon.getX()+" "+beacon.getY()+" "+beacon.getZ()+" §7— нажмите ПКМ"),true);
                }
            }
        }
        // Sparse/void worlds may finish the bounded scan with fewer than 160
        // removed blocks. A fractured world with saved damage must still offer
        // a physical recovery beacon, even if the early edit threshold was missed.
        if(s.naturalBeaconPos==Long.MIN_VALUE && !s.snapshots.isEmpty()
            && (s.edits>=160 || s.finished)
            && (s.edits==160 || s.finaleTicks%40==0)) {
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciSparseRecoveryTest")
                && s.finished && s.edits<160 && !s.ciSparseRecoveryEligibilityLogged) {
                LOGGER.info("BLACK_FREQUENCY_TEST SPARSE_RECOVERY_ELIGIBLE edits={} pending={}",
                    s.edits,s.snapshots.size());
                s.ciSparseRecoveryEligibilityLogged=true;
            }
            tryPlaceRecoveryBeacon(level,s);
        }
        // CI-only rollback exercise; normal gameplay still needs a real beacon.
        // The sparse CI client must use the same physical beacon and real
        // player right-click path as a normally fractured world. Keep this
        // early recovery threshold strictly test-only; production is unchanged.
        boolean ciSparseReady=CI_TEST && Boolean.getBoolean("blackfrequency.ciSparseRecoveryTest")
            && s.finished && s.edits>0 && s.edits<160;
        if(CI_TEST && Boolean.getBoolean("blackfrequency.ciExerciseRestoration")
            && !s.restoring && !s.beaconPosPresent && (s.edits>=160 || ciSparseReady) && !s.snapshots.isEmpty()
            && (!Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")
                || (s.naturalBeaconPos!=Long.MIN_VALUE && s.ciNaturalBeaconReplaced))) {
            boolean playerInteractionTest=Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest");
            if(!playerInteractionTest) {
                s.restoring=true;
                s.restoreIndex=s.snapshots.size()-1;
                s.restoreScan=s.restoreIndex;
                s.restoreTicks=0;
                s.restoredBlocks=0;
                s.skippedOccupied=0;
                s.skippedInvalid=0;
            }
            if(Boolean.getBoolean("blackfrequency.ciFlowingFluidTest") && !playerInteractionTest) {
                // Disposable CI world: emulate water flowing into a broken block
                // and prove that recovery does not mistake it for a player build.
                BlockPos fluidPos=BlockPos.of(s.snapshots.get(s.restoreIndex).getLong("pos"));
                if(level.hasChunk(fluidPos.getX()>>4,fluidPos.getZ()>>4)
                    && level.setBlock(fluidPos,Blocks.WATER.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,1),2|16))
                    LOGGER.info("BLACK_FREQUENCY_TEST FLOWING_FLUID_INJECTED x={} y={} z={}",fluidPos.getX(),fluidPos.getY(),fluidPos.getZ());
            }
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciDeferredChunkTest")) {
                // Disposable CI world only: inject an out-of-bounds location
                // in a definitely unloaded chunk. The bounds guard must retire
                // it before querying chunk availability, preventing a stuck save.
                BlockPos unloaded=new BlockPos(s.x+4096,level.getSeaLevel(),s.z+4096);
                s.snapshots.get(s.restoreIndex).putLong("pos",unloaded.asLong());
                LOGGER.info("BLACK_FREQUENCY_TEST INVALID_UNLOADED_SNAPSHOT_INJECTED x={} z={}",unloaded.getX(),unloaded.getZ());
            }
            // Add one deliberately truncated record to the disposable server
            // scenario, after the fluid/write-retry fixtures are prepared.
            // Valid snapshots must still restore and (0,0,0) must not be edited.
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest")
                && !playerInteractionTest) {
                // Place a well-formed air snapshot immediately before the real
                // snapshot that will fail its first write. Both share one position;
                // the invalid duplicate must be quarantined in the same tick.
                BlockPos failedWritePos=BlockPos.of(s.snapshots.get(s.restoreIndex).getLong("pos"));
                CompoundTag duplicateInvalid=new CompoundTag();
                duplicateInvalid.putLong("pos",failedWritePos.asLong());
                duplicateInvalid.put("state",NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()));
                duplicateInvalid.putBoolean("ciDuplicateInvalid",true);
                s.snapshots.add(s.snapshots.size()-1,duplicateInvalid);
                CompoundTag malformed=new CompoundTag();
                malformed.put("state",new CompoundTag()); // missing pos and Name
                s.snapshots.add(0,malformed);
                // A second, well-formed snapshot targets an impossible Y in a
                // loaded anchor chunk. It must be quarantined without reading
                // or writing that block, not mistaken for an unloaded chunk.
                CompoundTag outOfBounds=new CompoundTag();
                outOfBounds.putLong("pos",new BlockPos(s.x,level.getMinBuildHeight()-1,s.z).asLong());
                outOfBounds.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
                s.snapshots.add(0,outOfBounds);
                s.restoreIndex=s.snapshots.size()-1;
                s.restoreScan=s.restoreIndex;
                LOGGER.info("BLACK_FREQUENCY_TEST MALFORMED_SNAPSHOT_INJECTED");
                LOGGER.info("BLACK_FREQUENCY_TEST OUT_OF_BOUNDS_SNAPSHOT_INJECTED");
            }
            BlockPos demoPos=new BlockPos(s.x,level.getSeaLevel()+4,s.z);
            if(!level.players().isEmpty()) {
                var demoPlayer=level.players().get(0);
                if(playerInteractionTest && Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")) {
                    // Exercise the naturally spawned beacon, not an extra test beacon.
                    demoPos=BlockPos.of(s.naturalBeaconPos);
                    if(!level.hasChunk(demoPos.getX()>>4,demoPos.getZ()>>4)
                        || !level.getBlockState(demoPos).is(Blocks.BEACON)) {
                        LOGGER.warn("BLACK_FREQUENCY_TEST NATURAL_BEACON_UNAVAILABLE");
                        return;
                    }
                    // The sparse fixture can spawn beside a village wall. Put the
                    // raised pad on the axis perpendicular to the anchor-to-beacon offset:
                    // a pad extending toward the anchor can hide every visible fissure sample.
                    // Every pad column stays inside the bounded rift radius, world border
                    // and already-loaded chunks; this disposable fixture never force-loads.
                    boolean padAlongX=ciBeaconPadAlongX(s.x,s.z,demoPos.getX(),demoPos.getZ());
                    int padSign=chooseCiBeaconPadSign(level,demoPlayer,s,demoPos,padAlongX);
                    if(shouldTryAlternateCiBeaconPadAxis(padSign)) {
                        boolean primaryAlongX=padAlongX;
                        boolean alternateAlongX=!primaryAlongX;
                        int alternateSign=chooseCiBeaconPadSign(level,demoPlayer,s,demoPos,alternateAlongX);
                        if(alternateSign!=0) {
                            padAlongX=alternateAlongX;
                            padSign=alternateSign;
                            LOGGER.info("BLACK_FREQUENCY_TEST BEACON_APPROACH_AXIS_FALLBACK primaryAxis={} selectedAxis={} sign={} loadedOnly=true",
                                primaryAlongX?"X":"Z",padAlongX?"X":"Z",padSign);
                        }
                    }
                    if(padSign==0) {
                        if(!s.ciBeaconApproachFailureLogged) {
                            s.ciBeaconApproachFailureLogged=true;
                            LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_APPROACH_NO_CLEAR_SIDE axis={} alternateTried=true loadedOnly=true",
                                padAlongX?"X":"Z");
                        }
                        return;
                    }
                    s.ciBeaconApproachFailureLogged=false;
                    LOGGER.info("BLACK_FREQUENCY_TEST BEACON_APPROACH_SIDE_SELECTED axis={} sign={} sightlineClear=true fissureVisible=true loadedOnly=true",
                        padAlongX?"X":"Z",padSign);
                    int padDirectionX=padAlongX?padSign:0;
                    int padDirectionZ=padAlongX?0:padSign;
                    int padBlocks=0;
                    // Keep the first two rows free; choose the side with a real, loaded-only sightline.
                    for(int offset=CI_BEACON_PAD_FIRST_SOLID_ROW;offset<=CI_BEACON_PAD_LAST_SOLID_ROW;offset++)
                        for(int lateral=-CI_BEACON_PAD_LATERAL_RADIUS;lateral<=CI_BEACON_PAD_LATERAL_RADIUS;lateral++) {
                            int dx=padAlongX?padSign*offset:lateral;
                            int dz=padAlongX?lateral:padSign*offset;
                            BlockPos floor=demoPos.offset(dx,CI_BEACON_PAD_FLOOR_Y_OFFSET,dz);
                            long padDx=(long)floor.getX()-s.x;
                            long padDz=(long)floor.getZ()-s.z;
                            if(Math.abs(padDx)>RADIUS || Math.abs(padDz)>RADIUS
                                || padDx*padDx+padDz*padDz>(long)RADIUS*RADIUS
                                || !level.getWorldBorder().isWithinBounds(floor)
                                || !level.hasChunk(floor.getX()>>4,floor.getZ()>>4)
                                || floor.getY()<level.getMinBuildHeight()
                                || (long)floor.getY()+3L>=level.getMaxBuildHeight()) continue;
                            level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
                            if(level.getBlockState(floor).blocksMotion()) padBlocks++;
                            for(int dy=1;dy<=3;dy++)
                                level.setBlock(floor.above(dy),Blocks.AIR.defaultBlockState(),3);
                        }
                    // The perpendicular pad leaves the beacon-side row empty and does
                    // not put support blocks between the player's camera and the fissure.
                    demoPlayer.teleportTo(level,
                        demoPos.getX()+.5D+padDirectionX*CI_BEACON_PAD_STANDOFF,
                        demoPos.getY()+CI_BEACON_PAD_PLAYER_Y_OFFSET,
                        demoPos.getZ()+.5D+padDirectionZ*CI_BEACON_PAD_STANDOFF,0f,0f);
                    // Aim from the player's eyes at the actual beacon hitbox instead of
                    // relying on a fixed pitch that misses on different terrain heights.
                    demoPlayer.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        net.minecraft.world.phys.Vec3.atCenterOf(demoPos));
                    // The client must receive the post-lookAt rotation before XTest sends
                    // the real right-click; teleportTo's earlier packet used the old angle.
                    demoPlayer.connection.teleport(demoPlayer.getX(),demoPlayer.getY(),demoPlayer.getZ(),
                        demoPlayer.getYRot(),demoPlayer.getXRot());
                    if(!hasLoadedBeaconSightline(level,demoPlayer,demoPos)) {
                        LOGGER.warn("BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_BLOCKED loadedChunksOnly=true");
                        return;
                    }
                    LOGGER.info("BLACK_FREQUENCY_TEST BEACON_SIGHTLINE_VERIFIED hit=beacon loadedChunksOnly=true");
                    if(playerInteractionTest
                        && !verifyBeaconSightlineOcclusion(level,demoPlayer,demoPos,s.x,s.z))
                        throw new IllegalStateException("CI beacon sightline accepted a temporary obstruction");
                    demoPlayer.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    // teleportTo changes position but does not clear accumulated fall damage.
                    // The player may already be falling because the fracture removed the
                    // original spawn column; clear that history before the first pad tick.
                    demoPlayer.fallDistance=0.0F;
                    BlockPos playerSupport=demoPlayer.blockPosition().below();
                    if(!level.hasChunk(playerSupport.getX()>>4,playerSupport.getZ()>>4)
                        || !level.getWorldBorder().isWithinBounds(playerSupport)
                        || !level.getBlockState(playerSupport).blocksMotion()) {
                        LOGGER.warn("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_UNAVAILABLE x={} y={} z={}",
                            playerSupport.getX(),playerSupport.getY(),playerSupport.getZ());
                        return;
                    }
                    long supportDx=(long)playerSupport.getX()-s.x;
                    long supportDz=(long)playerSupport.getZ()-s.z;
                    boolean supportWithinRiftRadius=Math.abs(supportDx)<=RADIUS
                        && Math.abs(supportDz)<=RADIUS
                        && supportDx*supportDx+supportDz*supportDz<=(long)RADIUS*RADIUS;
                    double beaconDx=demoPlayer.getX()-(demoPos.getX()+.5D);
                    double beaconDy=demoPlayer.getY()-(demoPos.getY()+.5D);
                    double beaconDz=demoPlayer.getZ()-(demoPos.getZ()+.5D);
                    double beaconDistanceSquared=beaconDx*beaconDx+beaconDy*beaconDy+beaconDz*beaconDz;
                    boolean beamColumnUntouched=CI_BEACON_PAD_STANDOFF>1.0D;
                    if(padBlocks==0 || !supportWithinRiftRadius || !Double.isFinite(beaconDistanceSquared)
                        || beaconDistanceSquared>CI_BEACON_INTERACTION_MAX_DISTANCE*CI_BEACON_INTERACTION_MAX_DISTANCE
                        || !beamColumnUntouched) {
                        LOGGER.warn("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_PROFILE_UNAVAILABLE padBlocks={} distanceSquared={} withinRiftRadius={}",
                            padBlocks,beaconDistanceSquared,supportWithinRiftRadius);
                        return;
                    }
                    LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_PROFILE_VERIFIED floorOffset={} playerOffset={} lateralRadius={} axis={} firstSolidRow={} gapRow={} lastSolidRow={} standoff={} distanceSquared={} padBlocks={} withinReach=true beamColumnUntouched=true withinRiftRadius=true perpendicular=true",
                        CI_BEACON_PAD_FLOOR_Y_OFFSET,CI_BEACON_PAD_PLAYER_Y_OFFSET,CI_BEACON_PAD_LATERAL_RADIUS,
                        padAlongX?"X":"Z",CI_BEACON_PAD_FIRST_SOLID_ROW,CI_BEACON_PAD_GAP_ROW,CI_BEACON_PAD_LAST_SOLID_ROW,
                        CI_BEACON_PAD_STANDOFF,beaconDistanceSquared,padBlocks);
                    FissureTarget visibleFissure=findVisibleFissureTarget(level,s,demoPlayer);
                    if(visibleFissure==null) {
                        if(!ciFissureTargetUnavailableLogged) {
                            LOGGER.warn("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_UNAVAILABLE");
                            ciFissureTargetUnavailableLogged=true;
                        }
                    } else {
                        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET x={} y={} z={} distanceSquared={} horizontalDistanceSquared={} viewScore={} selection=balanced-view",
                            visibleFissure.x(),visibleFissure.y(),visibleFissure.z(),
                            visibleFissure.distanceSquared(),visibleFissure.horizontalDistanceSquared(),
                            visibleFissure.viewScore());
                    }
                    // Restore ordinary vanilla gravity only after the player is
                    // standing above a loaded, motion-blocking support block.
                    ciPlayerSafePadReady=true;
                    demoPlayer.setNoGravity(false);
                    demoPlayer.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    demoPlayer.fallDistance=0.0F;
                    if(CI_TEST) LOGGER.info(
                        "BLACK_FREQUENCY_TEST PLAYER_VANILLA_FALL_RESTORED_AFTER_SAFE_PAD x={} y={} z={}",
                        playerSupport.getX(),playerSupport.getY(),playerSupport.getZ());
                    if(CI_TEST) LOGGER.info(
                        "BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_TELEPORT x={} y={} z={} support=solid fallDistance={}",
                        demoPlayer.blockPosition().getX(),demoPlayer.blockPosition().getY(),
                        demoPlayer.blockPosition().getZ(),demoPlayer.fallDistance);
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_AIM_SET yaw={} pitch={}",
                        demoPlayer.getYRot(),demoPlayer.getXRot());
                } else if(playerInteractionTest) {
                    // Legacy fixture for testing a separately placed beacon.
                    int surface=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,s.x,s.z);
                    for(int dz=0;dz<=4;dz++) for(int dx=-1;dx<=1;dx++) {
                        BlockPos floor=new BlockPos(s.x+dx,surface,s.z+dz);
                        if(!level.hasChunk(floor.getX()>>4,floor.getZ()>>4)) continue;
                        level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
                        for(int dy=1;dy<=3;dy++)
                            level.setBlock(floor.above(dy),Blocks.AIR.defaultBlockState(),3);
                    }
                    demoPlayer.teleportTo(level,s.x+.5,surface+1,s.z+.5,0f,0f);
                    demoPos=new BlockPos(s.x,surface+2,s.z+3);
                } else {
                    demoPos=demoPlayer.blockPosition().relative(demoPlayer.getDirection(),4).above(1);
                }
                if(!Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest"))
                    level.setBlock(demoPos,Blocks.BEACON.defaultBlockState(),3);
                // Do not cover the beacon or sky with a huge test-only title.
                demoPlayer.displayClientMessage(recoveryViolet(
                    "✦ Маяк готов — нажмите ПКМ для стабилизации"),true);
            }
            s.beaconPos=demoPos.asLong();s.beaconPosPresent=true;
            if(playerInteractionTest)
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER x={} y={} z={}",demoPos.getX(),demoPos.getY(),demoPos.getZ());
            else
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED");
        }
        // The CI server can request recovery before this tick's fracture scan.
        // Never append more destroyed blocks after the restoration cursor is set.
        if(s.restoring) { s.setDirty(); return; }
        // The fracture can remain open for days; stop terrain edits once complete,
        // but only after the bounded gravity anomalies above have been processed.
        if(s.finished) {
            if(s.finaleTicks%20==0)s.setDirty();
            return;
        }
        // Allow the opening, green silence and sudden return to play before terrain removal.
        if (s.finaleTicks < 190) { if(s.finaleTicks%20==0)s.setDirty(); return; }
        if(CI_TEST && s.finaleTicks == 190) LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_PHASE_STARTED");
        int edits=0, checks=0;
        boolean surfaceCueSentThisTick=false;
        // CI worlds can contain large empty regions; scan more candidate cells
        // per tick there without increasing the number of block writes per tick.
        // Production retains the conservative 2048-check budget.
        int scanCheckBudget=CI_TEST?8192:2048;
        while(edits<EDIT_BUDGET && checks<scanCheckBudget && !s.finished) {
            if(s.edits>=EFFECTIVE_MAX_EDITS
                || (s.centerOutScan ? s.centerOutIndex>=CENTER_OUT_COLUMN_COUNT : s.cursorX>RADIUS)) {
                s.finished=true;
                break;
            }
            int dx=s.centerOutScan ? CENTER_OUT_DX[s.centerOutIndex] : s.cursorX;
            int dz=s.centerOutScan ? CENTER_OUT_DZ[s.centerOutIndex] : s.cursorZ;
            // An anchor near the integer coordinate limits must not wrap around
            // and destroy unrelated terrain on the opposite side of the world.
            long worldX=(long)s.x+dx,worldZ=(long)s.z+dz;
            if(!validFractureColumn(s.x,s.z,dx,dz)) {
                s.cursorY=s.surfaceFirstScan?Integer.MIN_VALUE:level.getMinBuildHeight();
                advanceFractureColumn(s);
                checks++;
                continue;
            }
            int wx=(int)worldX,wz=(int)worldZ;
            // Reject an out-of-border column once, before the loaded-chunk probe and
            // heightmap scan. The per-block border guard below remains the final
            // safety check if the border changes while a column is being processed.
            boolean newColumn=isNewFractureColumn(s.surfaceFirstScan,s.cursorY,level.getMinBuildHeight());
            if(newColumn && !isFissureColumnInsideWorldBorder(level.getWorldBorder(),wx,wz,
                    level.getMinBuildHeight(),s.fractureBorderCursor)) {
                s.cursorY=s.surfaceFirstScan?Integer.MIN_VALUE:level.getMinBuildHeight();
                advanceFractureColumn(s);
                checks++;
                continue;
            }
            if(!level.hasChunk(wx>>4,wz>>4)) {
                // Never force-load chunks for destruction. Skip this column and keep advancing.
                s.cursorY=s.surfaceFirstScan?Integer.MIN_VALUE:level.getMinBuildHeight();
                advanceFractureColumn(s);
                checks++;
                continue;
            }
            if(s.surfaceFirstScan && s.cursorY==Integer.MIN_VALUE) {
                int surfaceY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,wx,wz);
                s.cursorY=surfaceFirstCursor(surfaceY,level.getMinBuildHeight(),level.getMaxBuildHeight());
            }
            int y=s.cursorY;
            // Capture the current column before advancing: otherwise its final block
            // is evaluated against the NEXT column's fracture coordinates.
            if(s.surfaceFirstScan) {
                s.cursorY--;
                if(s.cursorY<level.getMinBuildHeight()) {
                    s.cursorY=Integer.MIN_VALUE;
                    advanceFractureColumn(s);
                }
            } else {
                // Legacy saves retain their original bottom-up scan order.
                s.cursorY++;
                if(s.cursorY>=level.getMaxBuildHeight()) {
                    s.cursorY=level.getMinBuildHeight();
                    advanceFractureColumn(s);
                }
            }
            checks++;
            double u=dx/12.0, v=dz/12.0;
            // Intersecting winding faults, with occasional broad void openings.
            // Natural bedrock is deliberately included: the fissures reach world bottom.
            double faultA=Math.abs(Math.sin(u*1.8+Math.sin(v*1.4)*0.65));
            double faultB=Math.abs(Math.cos(v*1.55-Math.sin(u*1.2)*0.7));
            double pocket=Math.sin(u*0.61+1.2)*Math.cos(v*0.73-0.8);
            double width=pocket>0.67 ? 0.72 : 0.32;
            boolean fracture=faultA<width || faultB<width*0.77;
            if(!fracture || dx*dx+dz*dz>RADIUS*RADIUS) continue;
            BlockPos pos=new BlockPos(wx,y,wz);
            // A shrinking world border must never let the catastrophe erase
            // inaccessible terrain, even inside the original fracture radius.
            // This is a border check only: no chunk loading or extra edits.
            if(!level.getWorldBorder().isWithinBounds(pos)) continue;
            BlockState original=level.getBlockState(pos);
            if(original.isAir() || original.hasBlockEntity()) continue;
            // Heightmap work is limited to the first CI edit and every fourth
            // successful edit candidate until one nearby surface cue is sent this tick.
            int surfaceYForFirstEdit=CI_TEST && s.edits==0
                ? level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,wx,wz)
                : Integer.MIN_VALUE;
            int editsBefore=s.edits;
            boolean inspectSurfaceCue=!surfaceCueSentThisTick && s.surfaceCueCooldownTicks==0
                && (editsBefore&3)==0;
            int surfaceYForCue=inspectSurfaceCue
                ? (surfaceYForFirstEdit!=Integer.MIN_VALUE ? surfaceYForFirstEdit
                    : level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,wx,wz))
                : Integer.MIN_VALUE;
            // Stage the original state before editing. A failed setBlock must not
            // leave a phantom recovery entry; a successful edit must have a
            // snapshot available immediately, before advancing the cursor.
            CompoundTag snapshot=new CompoundTag();
            snapshot.putLong("pos",pos.asLong());
            snapshot.put("state",NbtUtils.writeBlockState(original));
            long candidateSnapshotEstimate=boundedSnapshotPayloadEstimate(snapshot);
            if(!withinSnapshotRetentionBudget(s.retainedSnapshotEstimateBytes,candidateSnapshotEstimate)) {
                // Never remove a block unless its original state can be retained inside
                // the same aggregate budget enforced when loading a damaged save.
                // Unsupported modded states are skipped; no un-restorable edit is made.
                if(!s.snapshotBudgetSkipLogged) {
                    s.snapshotBudgetSkipLogged=true;
                    LOGGER.warn("BLACK FREQUENCY: skipped terrain edit because its recovery snapshot is invalid or exceeds the {} byte retention budget (retained estimate {})",
                        MAX_SNAPSHOT_RETAINED_ESTIMATE_BYTES,s.retainedSnapshotEstimateBytes);
                }
                continue;
            }
            s.snapshots.add(snapshot);
            s.retainedSnapshotEstimateBytes+=candidateSnapshotEstimate;
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16)){
                edits++;s.edits++;
                if(CI_TEST && s.edits==1) {
                    if(s.surfaceFirstScan && (y<surfaceYForFirstEdit-4 || y>=surfaceYForFirstEdit))
                        throw new IllegalStateException("CI first fracture was not near the visible surface: y="
                            +y+" surfaceY="+surfaceYForFirstEdit);
                    LOGGER.info("BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x={} y={} z={} surfaceY={} scanOrder={}",
                        wx,y,wz,surfaceYForFirstEdit,
                        s.surfaceFirstScan?(s.centerOutScan?"surface-first-center-out":"surface-first"):"legacy-bottom-up");
                }
                if(inspectSurfaceCue
                    && shouldEmitSurfaceFractureCue(y,surfaceYForCue,editsBefore,surfaceCueSentThisTick,s.surfaceCueCooldownTicks)
                    && hasNearbySurfaceFractureViewer(level,pos)) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,original),
                        pos.getX()+.5D,pos.getY()+.82D,pos.getZ()+.5D,
                        4,.22D,.12D,.22D,.035D);
                    level.sendParticles(FISSURE_VIOLET_DUST,
                        pos.getX()+.5D,pos.getY()+.72D,pos.getZ()+.5D,
                        3,.16D,.08D,.16D,.012D);
                    surfaceCueSentThisTick=true;
                    s.surfaceCueCooldownTicks=4;
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FRACTURE_CUE_EMITTED x={} y={} z={}",
                        pos.getX(),pos.getY(),pos.getZ());
                }
            } else {
                s.snapshots.remove(s.snapshots.size()-1);
                s.retainedSnapshotEstimateBytes-=candidateSnapshotEstimate;
            }
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
