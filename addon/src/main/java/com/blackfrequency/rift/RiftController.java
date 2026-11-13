package com.blackfrequency.rift;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
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
    private static final long DAY = 24000L;
    // Only for disposable CI worlds. Never increases Minecraft's global tick rate.
    private static final long TEST_STEP = Boolean.getBoolean("blackfrequency.ciTestMode")
        ? Math.max(1L, Math.min(2400L, Long.getLong("blackfrequency.ciTickStep", 2400L))) : 1L;
    // Shared with the client mesh so particles and the rendered ground scar use identical bounds.
    private static final int RADIUS = RiftGeometry.RADIUS;
    private static final String NATURAL_BEACON_ID_TAG = "blackfrequency:natural_recovery_beacon";
    private static final int FISSURE_ARM_COUNT = RiftGeometry.FISSURE_ARM_COUNT;
    private static final int FISSURE_SEGMENTS_PER_ARM = RiftGeometry.FISSURE_SEGMENTS_PER_ARM;
    private static final int FISSURE_MOTES_PER_SAMPLE = 1;
    private static final int IDLE_BEACON_PULSE_INTERVAL_TICKS = 20;
    private static final int IDLE_BEACON_PULSE_PARTICLE_COUNT = 6;
    // Recovery effects use cached blue-violet Dust and bounded, deterministic pulses.
    private static final int RESTORE_RING_INTERVAL_TICKS = 20;
    private static final int RESTORE_RING_PARTICLE_COUNT = 24;
    private static final int RESTORE_RISING_SPARK_PARTICLE_COUNT = 4;
    private static final int RESTORE_HELIX_INTERVAL_TICKS = 8;
    private static final int RESTORE_HELIX_ANCHOR_COUNT = 8;
    private static final int RESTORE_HELIX_MOTES_PER_ANCHOR = 2;
    private static final int RESTORE_HELIX_PARTICLE_COUNT =
        RESTORE_HELIX_ANCHOR_COUNT * RESTORE_HELIX_MOTES_PER_ANCHOR;
    private static final int RESTORE_BLOCK_CUE_INTERVAL = 48;
    private static final int RESTORE_BLOCK_CUE_PARTICLE_COUNT = 6;
    private static final int RESTORE_AUDIENCE_PARTICLE_COUNT = 8;
    // Restoration motes are deliberately smaller than the ground-fissure Dust.
    // Reuse these options so recovery never allocates a particle option per pulse.
    private static final float RESTORE_VIOLET_DUST_SCALE=.62F;
    private static final float RESTORE_VIOLET_ACCENT_DUST_SCALE=.78F;
    private static final DustParticleOptions RESTORE_VIOLET_DUST =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),RESTORE_VIOLET_DUST_SCALE);
    private static final DustParticleOptions RESTORE_VIOLET_ACCENT_DUST =
        new DustParticleOptions(new Vector3f(.42F,.08F,.98F),RESTORE_VIOLET_ACCENT_DUST_SCALE);
    private static final double RESTORE_AUDIENCE_SPREAD_XZ=.85D;
    private static final double RESTORE_AUDIENCE_SPREAD_Y=.55D;
    // Five cached sizes smooth the fissure's spatial pulse without allocating per sample.
    private static final DustParticleOptions FISSURE_VIOLET_DUST_SMALL =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),.95F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_MEDIUM_SMALL =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),1.05F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),1.15F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_MEDIUM_LARGE =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),1.25F);
    private static final DustParticleOptions FISSURE_VIOLET_DUST_LARGE =
        new DustParticleOptions(new Vector3f(.34F,.08F,.78F),1.35F);
    // Brighter blue-violet Dust replaces the magenta Dragon Breath accents.
    // Reused for every accent sample; no per-particle option allocation.
    private static final DustParticleOptions FISSURE_VIOLET_ACCENT_DUST =
        new DustParticleOptions(new Vector3f(.42F,.08F,.98F),1.35F);
    // Replace two portal accents per arm with textured crying-obsidian chips.
    private static final int FISSURE_SHARD_SEGMENT_INTERVAL = 6;
    private static final double FISSURE_SAMPLE_SPACING = RiftGeometry.FISSURE_SAMPLE_SPACING;
    // Avoid aiming almost straight down at a mote beside the player: a modest
    // standoff keeps the particle trail and the surface ribbon in the same view.
    private static final double FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE = FISSURE_SAMPLE_SPACING*5.0D;
    private static final double FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE = 6.0D;

    private static double fissurePulseWave(double phase,int segment,int arm) {
        if(!Double.isFinite(phase)) return 0.0D;
        double wave=Math.sin(phase+segment*.8D+arm*.6D);
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
            || FISSURE_VIOLET_DUST_SMALL.getScale()<.9F
            || FISSURE_VIOLET_DUST_LARGE.getScale()>1.4F)
            throw new IllegalStateException("CI fissure Dust pulse profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_DUST_PULSE_PROFILE_VERIFIED min={} low={} base={} high={} max={} variants=5",
            FISSURE_VIOLET_DUST_SMALL.getScale(),FISSURE_VIOLET_DUST_MEDIUM_SMALL.getScale(),
            FISSURE_VIOLET_DUST.getScale(),FISSURE_VIOLET_DUST_MEDIUM_LARGE.getScale(),
            FISSURE_VIOLET_DUST_LARGE.getScale());
        verifyFissureTargetSelectionPolicy();
    }
    // Production spreads terrain writes across more ticks to reduce synchronous chunk-update bursts.
    // CI keeps the higher deterministic budget so integration fixtures remain short.
    private static int fractureEditBudget(boolean ciTest) {
        return ciTest ? 160 : 96;
    }
    private static final int EDIT_BUDGET = fractureEditBudget(CI_TEST);
    private static final int MAX_EDITS = 95000;
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
        final double cap=.35D;
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
        if(lift>0.0D && current>Double.MAX_VALUE-lift) return Math.max(.43D,current);
        if(lift<0.0D && current< -Double.MAX_VALUE-lift) return -Double.MAX_VALUE;
        double next=current+lift;
        return current>.43D ? Math.min(current,next) : Math.min(.43D,next);
    }

    /** Downward anomaly drag must not propagate invalid motion from other mods. */
    private static double boundedSinkVelocity(double current, double impulse) {
        if(!Double.isFinite(current)) current=0.0D;
        if(!Double.isFinite(impulse)) return current;
        // Negative impulse is bounded by the sink's terminal speed; preserve
        // pre-existing faster falls rather than artificially braking them.
        if(impulse<0.0D && current< -Double.MAX_VALUE-impulse) return -Double.MAX_VALUE;
        double next=current+impulse;
        return current<-.55D ? Math.min(current,next) : Math.max(-.55D,next);
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
        int probeTicks=-1;
        double probeZone=Double.NaN;
        for(int tick=0;tick<10000;tick++) {
            double zone=Math.sin(testX*.115+Math.sin(tick*.011)*1.8)
                *Math.cos(testZ*.125-tick*.008);
            if(zone>.42D) {
                probeTicks=tick;
                probeZone=zone;
                break;
            }
        }
        if(probeTicks<0)
            throw new IllegalStateException("CI could not place passenger probe inside a lift zone");
        probe.finaleTicks=probeTicks;
        double testY=Math.max(level.getMinBuildHeight()+2.0D,
            Math.min(level.getMaxBuildHeight()-3.0D,level.getSeaLevel()+10.0D));
        var mount=net.minecraft.world.entity.EntityType.PIG.create(level);
        if(mount==null)
            throw new IllegalStateException("CI could not create passenger probe mount");
        try {
            player.setPos(testX,testY,testZ);
            player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            boolean controlImpulseApplied=applyRiftGravityImpulse(level,probe,player);
            if(!controlImpulseApplied
                || net.minecraft.world.phys.Vec3.ZERO.equals(player.getDeltaMovement()))
                throw new IllegalStateException("CI lift-zone control probe did not apply an impulse");
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

    /**
     * Apply the same bounded anomaly impulse used by the live player loop.
     * Keeping the eligibility guard here lets the real-entity CI probe verify
     * that passengers retain both their own velocity and their vehicle motion.
     */
    private static boolean applyRiftGravityImpulse(
            ServerLevel level,State s,net.minecraft.server.level.ServerPlayer player) {
        if(!shouldApplyRiftGravity(player)) return false;
        double px=player.getX(),pz=player.getZ();
        double ax=px-s.x,ay=player.getY()-level.getSeaLevel(),az=pz-s.z;
        double distanceSquared=ax*ax+ay*ay+az*az;
        final double outerRadius=RADIUS+16.0;
        if(distanceSquared>=outerRadius*outerRadius) return false;
        double fade=Math.min(1.0,Math.max(0.0,
            (outerRadius-Math.sqrt(distanceSquared))/16.0));
        double zone=Math.sin(px*.115+Math.sin(s.finaleTicks*.011)*1.8)
            *Math.cos(pz*.125-s.finaleTicks*.008);
        if(zone>.42) {
            var v=player.getDeltaMovement();
            double lift=Math.max(.045,Math.min(.19,.11+zone*.07))*fade;
            double dx=Math.sin(pz*.14+s.finaleTicks*.022)*.035*fade;
            double dz=Math.cos(px*.13-s.finaleTicks*.018)*.035*fade;
            // Limit only the anomaly's additional drift. Never clamp
            // existing sprint, knockback or mod-provided velocity.
            double nextX=boundedAnomalyVelocity(v.x,dx);
            double nextZ=boundedAnomalyVelocity(v.z,dz);
            player.setDeltaMovement(nextX,boundedLiftVelocity(v.y,lift),nextZ);
            player.hurtMarked=true;
            if(fade>.25) player.fallDistance=0;
            return true;
        }
        if(zone<-.68) {
            var v=player.getDeltaMovement();
            double drag=1.0-.06*fade;
            player.setDeltaMovement(boundedSinkDrag(v.x,drag),
                boundedSinkVelocity(v.y,-.09*fade),boundedSinkDrag(v.z,drag));
            player.hurtMarked=true;
            return true;
        }
        return false;
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
        int restoreIndex, restoreScan, restoreTicks, restoredBlocks, skippedOccupied, skippedInvalid;
        long beaconPos;
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
        // CI-only one-shot beacon removal: exercise recovery after a lost beacon.
        boolean ciNaturalBeaconRemoved, ciNaturalBeaconReplaced;
        boolean ciSparseRecoveryEligibilityLogged;
        boolean ciSurfaceFissureLogged;
        final List<CompoundTag> snapshots = new ArrayList<>();
        // Reuse the small per-tick retry set during recovery instead of allocating
        // a new HashSet on every server tick. It is transient and never persisted.
        final java.util.Set<Long> restoreFailedWrites = new java.util.HashSet<>();
        static State load(CompoundTag n) {
            State s = new State();
            s.elapsed=boundedElapsed(n.getLong("elapsed"));
            s.x=n.getInt("x");s.z=n.getInt("z");
            s.cursorX=n.getInt("cx");s.cursorZ=n.getInt("cz");s.cursorY=n.getInt("cy");
            s.edits=boundedCounter(n.getInt("edits"),MAX_EDITS);
            s.finaleTicks=boundedCounter(n.getInt("finaleTicks"),Integer.MAX_VALUE);
            s.surfaceFirstScan=n.contains("surfaceFirstScan",Tag.TAG_BYTE)&&n.getBoolean("surfaceFirstScan");
            s.centerOutScan=n.contains("centerOutScan",Tag.TAG_BYTE)&&n.getBoolean("centerOutScan");
            s.centerOutIndex=n.getInt("centerOutIndex");
            s.started=n.getBoolean("started");s.finished=n.getBoolean("finished");
            s.initialized=n.getBoolean("initialized");
            s.restoring=n.getBoolean("restoring");s.restored=n.getBoolean("restored");
            if(s.centerOutScan && (s.centerOutIndex<0 || s.centerOutIndex>CENTER_OUT_COLUMN_COUNT)) {
                if(s.initialized && !s.restoring && !s.restored && !s.finished) {
                    LOGGER.warn("BLACK FREQUENCY: invalid center-out cursor {}; stopping further terrain edits",
                        s.centerOutIndex);
                    s.centerOutIndex=CENTER_OUT_COLUMN_COUNT;
                    s.finished=true;
                    s.setDirty();
                } else {
                    s.centerOutIndex=0;
                }
            }
            s.restoreIndex=n.getInt("restoreIndex");
            // Older worlds have no scan cursor: resume at the last pending snapshot.
            s.restoreScan=n.contains("restoreScan",Tag.TAG_INT)?n.getInt("restoreScan"):s.restoreIndex;
            s.restoreTicks=boundedCounter(n.getInt("restoreTicks"),Integer.MAX_VALUE);
            s.restoredBlocks=boundedCounter(n.getInt("restoredBlocks"),MAX_EDITS);
            s.skippedOccupied=boundedCounter(n.getInt("skippedOccupied"),MAX_EDITS);
            s.skippedInvalid=boundedCounter(n.getInt("skippedInvalid"),MAX_EDITS);
            s.beaconPos=n.getLong("beaconPos");
            s.naturalBeaconPos=n.contains("naturalBeaconPos",Tag.TAG_LONG)?n.getLong("naturalBeaconPos"):Long.MIN_VALUE;
            s.naturalBeaconId=n.hasUUID("naturalBeaconId")?n.getUUID("naturalBeaconId"):null;
            ListTag saved=n.getList("snapshots",Tag.TAG_COMPOUND);
            int snapshotCount=boundedSnapshotCount(saved.size());
            for(int i=0;i<snapshotCount;i++) s.snapshots.add(saved.getCompound(i));
            boolean snapshotStateRepaired=false;
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
                if(s.restoreIndex < -1 || s.restoreIndex > last) {
                    LOGGER.warn("BLACK FREQUENCY: invalid restoreIndex={} for {} snapshots; clamping",
                        s.restoreIndex,s.snapshots.size());
                    s.restoreIndex=last;
                }
                if(s.restoreScan < -1 || s.restoreScan > s.restoreIndex) {
                    LOGGER.warn("BLACK FREQUENCY: invalid restoreScan={}; resetting to restoreIndex={}",
                        s.restoreScan,s.restoreIndex);
                    s.restoreScan=s.restoreIndex;
                }
            }
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
            n.putInt("restoreIndex",restoreIndex);n.putInt("restoreScan",restoreScan);n.putInt("restoreTicks",restoreTicks);n.putInt("restoredBlocks",restoredBlocks);n.putInt("skippedOccupied",skippedOccupied);n.putInt("skippedInvalid",skippedInvalid);n.putLong("beaconPos",beaconPos);
            n.putLong("naturalBeaconPos",naturalBeaconPos);
            if(naturalBeaconId!=null) n.putUUID("naturalBeaconId",naturalBeaconId);
            ListTag saved=new ListTag();for(CompoundTag tag:snapshots)saved.add(tag.copy());
            n.put("snapshots",saved);
            return n;
        }
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
        if(event.getPlayer()==null || !event.getPlayer().isAlive()
            || event.getPlayer().isRemoved() || event.getPlayer().isSpectator()) return;
        BlockPos pos=event.getPos();
        // Ignore unloaded positions before querying block state: an event from
        // another mod must not cause recovery logic to load a chunk.
        if(!level.hasChunkAt(pos)) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,pos)) return;
        // Cancel vanilla harvesting before it creates a valuable beacon item.
        event.setCanceled(true);
        if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)
            && level.getBlockState(pos).isAir()) {
            s.naturalBeaconPos=Long.MIN_VALUE;
            s.naturalBeaconId=null;
            s.setDirty();
            event.getPlayer().displayClientMessage(Component.literal(
                s.restoring || s.restored
                    ? "§5✦ Маяк стабилизации рассеялся."
                    : "§5✦ Маяк стабилизации рассеялся. Новый появится поблизости."),true);
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_MINED_NO_DROP x={} y={} z={}",
                pos.getX(),pos.getY(),pos.getZ());
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
            int shards,double averageSurfaceY) {
        private int totalParticles() {
            return accentDustMotes+violetDustMotes+shards;
        }
    }

    // Sparse accent samples use a brighter cached Dust tint from the same
    // particle family, keeping the whole surface fissure inside the violet palette.
    private static boolean isAccentFissureDustSegment(int segment) {
        return segment>0 && segment%4==0
            && FISSURE_SHARD_SEGMENT_INTERVAL>0
            && segment%FISSURE_SHARD_SEGMENT_INTERVAL!=0;
    }

    // A no-leaves heightmap can still stop on a tree trunk or a village roof.
    // Walk down to terrain instead of treating stairs, slabs, walls or other
    // structural surfaces as the ground fracture plane.
    private static boolean isFissureGroundSupport(BlockState state) {
        return state.blocksMotion()
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

    private static int findFissureGroundY(ServerLevel level,int x,int z) {
        if(!level.hasChunk(x>>4,z>>4)) return Integer.MIN_VALUE;
        int y=level.getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
        int min=level.getMinBuildHeight();
        for(int depth=0;depth<32 && y>min+1;depth++,y--) {
            if(isFissureGroundSupport(level.getBlockState(new BlockPos(x,y-1,z)))) return y;
        }
        return Integer.MIN_VALUE;
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

    private static double fissureParticleY(ServerLevel level,int x,int surfaceY,int z) {
        BlockPos surface=new BlockPos(x,surfaceY,z);
        BlockState state=level.getBlockState(surface);
        double shapeHeight=state.isAir() ? 0.0D
            : state.getShape(level,surface).max(net.minecraft.core.Direction.Axis.Y);
        return fissureSurfaceParticleY(surfaceY,shapeHeight);
    }

    private static void verifyFissureSurfaceParticleHeight() {
        if(Math.abs(fissureSurfaceParticleY(65,0.0D)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,0.125D)-65.225D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,1.0D)-66.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,Double.NaN)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,Double.POSITIVE_INFINITY)-65.10D)>1.0E-9D
            || Math.abs(fissureSurfaceParticleY(65,2.0D)-66.10D)>1.0E-9D)
            throw new IllegalStateException("CI fissure surface particle height regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_PARTICLE_HEIGHT_VERIFIED");
    }

    /**
     * Draw a dense twelve-segment, four-armed surface fissure without changing terrain.
     * Every sample is restricted to the rift radius and an already loaded chunk.
     */
    private static FissureEmission emitSurfaceFissure(ServerLevel level,State s,double phase) {
        int samples=0;
        int accentDustMotes=0;
        int violetDustMotes=0;
        int shards=0;
        double surfaceYTotal=0.0D;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            double angle=arm*Math.PI/2.0;
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                double distance=segment*FISSURE_SAMPLE_SPACING;
                double bend=Math.sin(segment*.85D+arm*1.1D)*.18D;
                double px=s.x+.5+Math.cos(angle)*distance-Math.sin(angle)*bend;
                double pz=s.z+.5+Math.sin(angle)*distance+Math.cos(angle)*bend;
                int blockX=(int)Math.floor(px);
                int blockZ=(int)Math.floor(pz);
                long dx=blockX-(long)s.x;
                long dz=blockZ-(long)s.z;
                if(dx*dx+dz*dz>(long)RADIUS*RADIUS
                    || !level.getWorldBorder().isWithinBounds(
                        new BlockPos(blockX,level.getMinBuildHeight(),blockZ))
                    || !level.hasChunk(blockX>>4,blockZ>>4)) continue;
                int surfaceY=findFissureGroundY(level,blockX,blockZ);
                if(surfaceY==Integer.MIN_VALUE || surfaceY>=level.getMaxBuildHeight()) continue;
                BlockPos support=new BlockPos(blockX,surfaceY-1,blockZ);
                if(!isFissureGroundSupport(level.getBlockState(support))) continue;
                double particleY=fissureParticleY(level,blockX,surfaceY,blockZ);
                double wave=fissurePulseWave(phase,segment,arm);
                double pulse=wave*.05D;
                DustParticleOptions dust=fissureDustForWave(wave);
                // Keep one primary mote per sample. Cached DustParticleOptions supply the
                // dominant deep-violet line with a slow size pulse; every fourth non-shard
                // segment uses a brighter blue-violet Dust accent for contrast.
                if(segment%FISSURE_SHARD_SEGMENT_INTERVAL==0) {
                    level.sendParticles(dust,
                        px,particleY+pulse,pz,1,.035,.012,.035,.008);
                    violetDustMotes++;
                    // A slightly wider, faster chip burst makes the textured edge
                    // readable against stone while retaining one chip per anchor.
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,
                        Blocks.CRYING_OBSIDIAN.defaultBlockState()),
                        px,particleY+.04+pulse,pz,1,.045,.025,.045,.012);
                    shards++;
                } else if(isAccentFissureDustSegment(segment)) {
                    level.sendParticles(FISSURE_VIOLET_ACCENT_DUST,
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
        }
        return new FissureEmission(samples,accentDustMotes,violetDustMotes,shards,
            samples==0?Double.NaN:surfaceYTotal/samples);
    }

    private record FissureTarget(double x,double y,double z,double distanceSquared,
            double horizontalDistanceSquared) {}

    private static boolean isBetterFissureTarget(double candidateDistanceSquared,
            double bestDistanceSquared,boolean hasBest) {
        if(!Double.isFinite(candidateDistanceSquared) || candidateDistanceSquared<0.0D)
            return false;
        if(!hasBest) return true;
        return Double.isFinite(bestDistanceSquared) && bestDistanceSquared>=0.0D
            && candidateDistanceSquared<bestDistanceSquared;
    }

    private static boolean isFissureTargetHorizontalDistance(double horizontalSquared) {
        double minimumSquared=FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE*FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE;
        double maximumSquared=FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE*FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE;
        return Double.isFinite(horizontalSquared)
            && horizontalSquared>=minimumSquared && horizontalSquared<=maximumSquared;
    }

    private static void verifyFissureTargetSelectionPolicy() {
        double minimumSquared=FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE*FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE;
        double maximumSquared=FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE*FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE;
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
            || isFissureTargetHorizontalDistance(-1.0D))
            throw new IllegalStateException("CI fissure target selection regression");
        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_SELECTION_VERIFIED policy=nearest ties=stable invalidRejected=true minHorizontal={} maxHorizontal={}",
            FISSURE_TARGET_MIN_HORIZONTAL_DISTANCE,FISSURE_TARGET_MAX_HORIZONTAL_DISTANCE);
    }

    private static boolean fissureRayChunksLoaded(ServerLevel level,BlockPos from,BlockPos to) {
        int minChunkX=Math.min(from.getX(),to.getX())>>4;
        int maxChunkX=Math.max(from.getX(),to.getX())>>4;
        int minChunkZ=Math.min(from.getZ(),to.getZ())>>4;
        int maxChunkZ=Math.max(from.getZ(),to.getZ())>>4;
        for(int chunkX=minChunkX;chunkX<=maxChunkX;chunkX++)
            for(int chunkZ=minChunkZ;chunkZ<=maxChunkZ;chunkZ++)
                if(!level.hasChunk(chunkX,chunkZ)) return false;
        return true;
    }

    /**
     * Pick a real emitted fissure sample that the test player can see from the
     * verified beacon pad. A nearby surface hidden behind a cliff is not a
     * useful camera target, even when its particles were sent successfully.
     */
    private static FissureTarget findVisibleFissureTarget(ServerLevel level,State s,
            net.minecraft.server.level.ServerPlayer viewer) {
        if(viewer==null || !viewer.isAlive() || viewer.isRemoved()) return null;
        net.minecraft.world.phys.Vec3 eye=viewer.getEyePosition();
        FissureTarget best=null;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            double angle=arm*Math.PI/2.0D;
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                double distance=segment*FISSURE_SAMPLE_SPACING;
                double bend=Math.sin(segment*.85D+arm*1.1D)*.18D;
                double px=s.x+.5D+Math.cos(angle)*distance-Math.sin(angle)*bend;
                double pz=s.z+.5D+Math.sin(angle)*distance+Math.cos(angle)*bend;
                int blockX=(int)Math.floor(px),blockZ=(int)Math.floor(pz);
                long dx=(long)blockX-s.x,dz=(long)blockZ-s.z;
                if(dx*dx+dz*dz>(long)RADIUS*RADIUS
                    || !level.getWorldBorder().isWithinBounds(
                        new BlockPos(blockX,level.getMinBuildHeight(),blockZ))
                    || !level.hasChunk(blockX>>4,blockZ>>4)) continue;
                int surfaceY=findFissureGroundY(level,blockX,blockZ);
                if(surfaceY==Integer.MIN_VALUE || surfaceY>=level.getMaxBuildHeight()
                    || !isFissureGroundSupport(level.getBlockState(new BlockPos(blockX,surfaceY-1,blockZ))))
                    continue;
                BlockPos rayTarget=new BlockPos(blockX,surfaceY,blockZ);
                if(!fissureRayChunksLoaded(level,viewer.blockPosition(),rayTarget)) continue;
                net.minecraft.world.phys.Vec3 target=new net.minecraft.world.phys.Vec3(
                    px,fissureParticleY(level,blockX,surfaceY,blockZ),pz);
                double vx=target.x-eye.x,vy=target.y-eye.y,vz=target.z-eye.z;
                double horizontalSquared=vx*vx+vz*vz;
                // Keep a modest horizontal standoff so the view does not point
                // almost straight down at a particle just beside the player.
                // The loaded-chunk ray trace remains the authority on visibility.
                if(!isFissureTargetHorizontalDistance(horizontalSquared) || Math.abs(vy)>24.0D)
                    continue;
                var hit=level.clip(new net.minecraft.world.level.ClipContext(eye,target,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,viewer));
                if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS) continue;
                double distanceSquared=vx*vx+vy*vy+vz*vz;
                if(isBetterFissureTarget(distanceSquared,
                        best==null?Double.NaN:best.distanceSquared(),best!=null))
                    best=new FissureTarget(px,target.y,pz,distanceSquared,horizontalSquared);
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

    private static boolean hasNearbySurfaceFractureViewer(ServerLevel level,BlockPos pos) {
        for(var player:level.players()) {
            if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
            if(player.distanceToSqr(pos.getX()+.5D,pos.getY()+.5D,pos.getZ()+.5D)<=32.0D*32.0D)
                return true;
        }
        return false;
    }

    private static void verifyFissureSamplingDensity() {
        int samples=FISSURE_ARM_COUNT*FISSURE_SEGMENTS_PER_ARM;
        double extent=FISSURE_SEGMENTS_PER_ARM*FISSURE_SAMPLE_SPACING;
        if(FISSURE_ARM_COUNT!=4 || samples!=48 || samples*FISSURE_MOTES_PER_SAMPLE!=48
            || FISSURE_MOTES_PER_SAMPLE<1 || !Double.isFinite(FISSURE_SAMPLE_SPACING)
            || FISSURE_SAMPLE_SPACING<=0.0D || FISSURE_SAMPLE_SPACING>0.5D
            || Math.abs(extent-5.40D)>1.0E-9D || extent>=RADIUS
            || FISSURE_SHARD_SEGMENT_INTERVAL<=0
            || FISSURE_SEGMENTS_PER_ARM%FISSURE_SHARD_SEGMENT_INTERVAL!=0
            || FISSURE_SEGMENTS_PER_ARM/FISSURE_SHARD_SEGMENT_INTERVAL!=2)
            throw new IllegalStateException("CI fissure sampling density/radius regression");
        int shardCount=0;
        int accentDustMotes=0;
        int violetDustMotes=0;
        for(int arm=0;arm<FISSURE_ARM_COUNT;arm++) {
            for(int segment=1;segment<=FISSURE_SEGMENTS_PER_ARM;segment++) {
                if(segment%FISSURE_SHARD_SEGMENT_INTERVAL==0) {
                    shardCount++;
                    violetDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                } else if(isAccentFissureDustSegment(segment)) {
                    accentDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                } else {
                    violetDustMotes+=FISSURE_MOTES_PER_SAMPLE;
                }
            }
        }
        int primaryMotes=samples*FISSURE_MOTES_PER_SAMPLE;
        int totalParticles=primaryMotes+shardCount;
        if(shardCount!=8 || accentDustMotes!=8 || violetDustMotes!=40
            || totalParticles!=56 || totalParticles>56)
            throw new IllegalStateException("CI violet-majority fissure particle budget regression");
        Vector3f dustColor=FISSURE_VIOLET_DUST.getColor();
        float dustScale=FISSURE_VIOLET_DUST.getScale();
        if(!Float.isFinite(dustColor.x()) || !Float.isFinite(dustColor.y())
            || !Float.isFinite(dustColor.z()) || dustColor.x()<0.0F || dustColor.x()>1.0F
            || dustColor.y()<0.0F || dustColor.y()>1.0F || dustColor.z()<0.0F
            || dustColor.z()>1.0F || dustColor.z()<=dustColor.x()*1.8F
            || dustColor.y()>0.12F || !Float.isFinite(dustScale)
            || dustScale<1.0F || dustScale>1.4F)
            throw new IllegalStateException("CI fissure violet dust color/scale regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_VIOLET_DUST_VERIFIED red={} green={} blue={} scale={}",
            dustColor.x(),dustColor.y(),dustColor.z(),dustScale);
        Vector3f accentColor=FISSURE_VIOLET_ACCENT_DUST.getColor();
        float accentScale=FISSURE_VIOLET_ACCENT_DUST.getScale();
        if(!Float.isFinite(accentColor.x()) || !Float.isFinite(accentColor.y())
            || !Float.isFinite(accentColor.z())
            || Math.abs(accentColor.x()-.42F)>1.0E-6F
            || Math.abs(accentColor.y()-.08F)>1.0E-6F
            || Math.abs(accentColor.z()-.98F)>1.0E-6F
            || !Float.isFinite(accentScale) || Math.abs(accentScale-1.35F)>1.0E-6F
            || accentColor.z()<=accentColor.x()*2.0F)
            throw new IllegalStateException("CI fissure violet accent Dust color/scale regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_ACCENT_DUST_VERIFIED red={} green={} blue={} scale={}",
            accentColor.x(),accentColor.y(),accentColor.z(),accentScale);
        verifyFissureDustPulseProfile();
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_DENSITY_VERIFIED samples={} spacing={} extent={}",
            samples,FISSURE_SAMPLE_SPACING,extent);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLE_BUDGET_VERIFIED total={} accentDust={} violetDust={} shards={}",
            totalParticles,accentDustMotes,violetDustMotes,shardCount);
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_SHARD_LAYOUT_VERIFIED count={} material=crying_obsidian",
            shardCount);
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
            || !isFissureGroundSupport(Blocks.STONE.defaultBlockState())
            || !isFissureGroundSupport(Blocks.DIRT.defaultBlockState()))
            throw new IllegalStateException("CI fissure terrain/structure filter regression");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_STRUCTURAL_SUPPORT_FILTER_VERIFIED");
        verifyFissureSurfaceParticleHeight();
        verifyFissureSamplingDensity();
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_GROUND_FILTER_VERIFIED");
        LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FRACTURE_CUE_RULE_VERIFIED");
    }

    private static void verifySurfaceFirstScanMigration() {
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
        if(legacy.surfaceFirstScan || legacy.centerOutScan || legacy.cursorY!=12)
            throw new IllegalStateException("CI legacy fracture cursor migration regression");
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
        corruptCenterOut.putBoolean("centerOutScan",true);
        corruptCenterOut.putInt("centerOutIndex",Integer.MAX_VALUE);
        State quarantined=State.load(corruptCenterOut);
        if(!quarantined.finished || quarantined.centerOutIndex!=CENTER_OUT_COLUMN_COUNT)
            throw new IllegalStateException("CI corrupt center-out cursor was not quarantined");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_CENTER_OUT_CURSOR_QUARANTINED");
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
        java.util.Set<Long> first=beginRestoreTick(state);
        first.add(0xBFL);
        java.util.Set<Long> second=beginRestoreTick(state);
        if(first!=second || !second.isEmpty())
            throw new IllegalStateException("CI restore failure cache reuse regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_FAILURE_CACHE_REUSED");
    }

    /** Verify that a pending terrain snapshot survives a SavedData round trip. */
    private static void verifyPendingSnapshotPersistence() {
        verifyRestoreFailureCacheReuse();
        State original=new State();
        original.started=true;
        original.restoring=true;
        original.x=12;
        original.z=-7;
        original.edits=1;
        original.restoreIndex=0;
        original.restoreScan=0;
        BlockPos pos=new BlockPos(12,64,-7);
        CompoundTag snapshot=new CompoundTag();
        snapshot.putLong("pos",pos.asLong());
        snapshot.put("state",NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        original.snapshots.add(snapshot);
        State loaded=State.load(original.save(new CompoundTag()));
        if(!loaded.started || !loaded.restoring || loaded.edits!=1
            || loaded.restoreIndex!=0 || loaded.restoreScan!=0
            || loaded.snapshots.size()!=1
            || loaded.snapshots.get(0).getLong("pos")!=pos.asLong()
            || !loaded.snapshots.get(0).getCompound("state").equals(snapshot.getCompound("state")))
            throw new IllegalStateException("CI pending terrain snapshot persistence regression");
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
        if(sanitized.elapsed!=0L || sanitized.edits!=0 || sanitized.finaleTicks!=0
            || sanitized.restoreTicks!=0 || sanitized.restoredBlocks!=0
            || sanitized.skippedOccupied!=0 || sanitized.skippedInvalid!=0)
            throw new IllegalStateException("CI negative saved counters were not sanitized");
        CompoundTag excessiveCounters=new CompoundTag();
        excessiveCounters.putLong("elapsed",Long.MAX_VALUE);
        excessiveCounters.putInt("edits",Integer.MAX_VALUE);
        excessiveCounters.putInt("finaleTicks",Integer.MAX_VALUE);
        excessiveCounters.putInt("restoreTicks",Integer.MAX_VALUE);
        excessiveCounters.putInt("restoredBlocks",Integer.MAX_VALUE);
        excessiveCounters.putInt("skippedOccupied",Integer.MAX_VALUE);
        excessiveCounters.putInt("skippedInvalid",Integer.MAX_VALUE);
        State capped=State.load(excessiveCounters);
        if(capped.elapsed!=DAY*10L || capped.edits!=MAX_EDITS
            || capped.finaleTicks!=Integer.MAX_VALUE || capped.restoreTicks!=Integer.MAX_VALUE
            || capped.restoredBlocks!=MAX_EDITS || capped.skippedOccupied!=MAX_EDITS
            || capped.skippedInvalid!=MAX_EDITS
            || nextNonNegativeTick(-1)!=0 || nextNonNegativeTick(0)!=1
            || nextNonNegativeTick(Integer.MAX_VALUE)!=Integer.MAX_VALUE)
            throw new IllegalStateException("CI excessive saved counters were not bounded");
        LOGGER.info("BLACK_FREQUENCY_TEST CORRUPT_STATE_COUNTERS_SANITIZED");

        // A corrupt NBT list must not grow the recovery queue beyond the same
        // 95,000-entry ceiling enforced by terrain destruction.
        ListTag oversizedSnapshots=new ListTag();
        for(int i=0;i<MAX_EDITS+1;i++) {
            CompoundTag entry=new CompoundTag();
            entry.putLong("pos",new BlockPos(i,64,0).asLong());
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
                !=new BlockPos(MAX_EDITS-1,64,0).asLong())
            throw new IllegalStateException("CI oversized recovery snapshot list was not bounded safely");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_SNAPSHOT_LIST_BOUNDED count={} cap={}",
            boundedSnapshots.snapshots.size(),MAX_EDITS);
        LOGGER.info("BLACK_FREQUENCY_TEST PENDING_SNAPSHOT_PERSISTENCE_VERIFIED");
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
        LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_STATE_PERSISTENCE_VERIFIED");
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
            && Double.isFinite(distanceSquared) && distanceSquared>=0.0D && distanceSquared<=64.0D;
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
        double distanceSquared=player.distanceToSqr(beacon.getX()+0.5D,
            beacon.getY()+0.5D,beacon.getZ()+0.5D);
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

    private static boolean shouldEmitRestoreHelix(int restoreTicks) {
        return restoreTicks>0 && restoreTicks%RESTORE_HELIX_INTERVAL_TICKS==0;
    }

    private static boolean shouldEmitRestoreBlockCue(int restoredBlocks) {
        return restoredBlocks>0 && restoredBlocks%RESTORE_BLOCK_CUE_INTERVAL==0;
    }

    private static boolean shouldEmitRestoreAudiencePulse(int restoreTicks) {
        return restoreTicks>0 && restoreTicks%RESTORE_RING_INTERVAL_TICKS==0;
    }

    private static void verifyRestorationParticleProfile() {
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
            && hasDustColor(FISSURE_VIOLET_ACCENT_DUST,.42F,.08F,.98F)
            && hasDustColor(RESTORE_VIOLET_DUST,.34F,.08F,.78F)
            && hasDustColor(RESTORE_VIOLET_ACCENT_DUST,.42F,.08F,.98F);
        boolean boundedRestoreDust=RESTORE_VIOLET_DUST_SCALE>=.55F
            && RESTORE_VIOLET_DUST_SCALE<RESTORE_VIOLET_ACCENT_DUST_SCALE
            && RESTORE_VIOLET_ACCENT_DUST_SCALE<=.85F
            && RESTORE_AUDIENCE_SPREAD_XZ>0.0D && RESTORE_AUDIENCE_SPREAD_XZ<=1.0D
            && RESTORE_AUDIENCE_SPREAD_Y>0.0D && RESTORE_AUDIENCE_SPREAD_Y<=.75D;
        if(!(ringBoundary && ringBetween && helixBoundary && helixBetween
            && blockBoundary && blockBetween && blockNext && invalidTicks
            && boundedCounts && cachedPalette && boundedRestoreDust))
            throw new IllegalStateException("CI restoration particle profile regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_PROFILE_VERIFIED ring={} ringIntervalTicks={} sparks={} helix={} helixIntervalTicks={} blockCue={} blockIntervalBlocks={} audience={} audienceIntervalTicks={} cachedDust=true",
            RESTORE_RING_PARTICLE_COUNT,RESTORE_RING_INTERVAL_TICKS,RESTORE_RISING_SPARK_PARTICLE_COUNT,
            RESTORE_HELIX_PARTICLE_COUNT,RESTORE_HELIX_INTERVAL_TICKS,
            RESTORE_BLOCK_CUE_PARTICLE_COUNT,RESTORE_BLOCK_CUE_INTERVAL,
            RESTORE_AUDIENCE_PARTICLE_COUNT,RESTORE_RING_INTERVAL_TICKS);
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PARTICLE_SIZE_VERIFIED baseScale={} accentScale={} audienceSpreadXZ={} audienceSpreadY={} cachedDust=true",
            RESTORE_VIOLET_DUST_SCALE,RESTORE_VIOLET_ACCENT_DUST_SCALE,
            RESTORE_AUDIENCE_SPREAD_XZ,RESTORE_AUDIENCE_SPREAD_Y);
    }

    private static void verifyBeaconInteractionGuard() {
        var main=net.minecraft.world.InteractionHand.MAIN_HAND;
        var off=net.minecraft.world.InteractionHand.OFF_HAND;
        if(!mayActivateBeacon(main,true,false,64.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(off,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,false,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,true,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,64.0001D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.NaN,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.POSITIVE_INFINITY,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,Double.NEGATIVE_INFINITY,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || mayActivateBeacon(main,true,false,-0.001D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT)
            || !mayActivateBeacon(main,true,false,0.0D,false,net.minecraftforge.eventbus.api.Event.Result.DEFAULT,net.minecraftforge.eventbus.api.Event.Result.DEFAULT))
            throw new IllegalStateException("CI beacon interaction guard regression");
        if(!mayRecoverCiBeaconAimAfterMiss(true,true,true,true,false,false,true,
                main,true,false,false,64.0D,false,
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
            throw new IllegalStateException("CI beacon aim recovery guard regression");
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
        verifyNaturalBeaconIdPersistence();
        verifyRecoveryAudienceBounds();
        verifyRecoveryVortexContraction();
        verifyFractureColumnBounds();
        verifyBoundedAnomalyVelocity();
        verifyBoundedLiftVelocity();
        verifyBoundedSinkVelocity();
        verifyBoundedSinkDrag();
        verifyRiftGravityEligibility();
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
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_INTERACTION_GUARD_VERIFIED");
    }

    /** Progress remains below 100% until the final stabilization hold has completed. */
    private static int restorationPercent(int restoreIndex,int snapshotCount) {
        if(snapshotCount<=0) return 0;
        long pending=Math.max(0L,Math.min((long)snapshotCount,(long)restoreIndex+1L));
        int completed=snapshotCount-(int)pending;
        return Math.max(0,Math.min(99,(int)(100L*completed/snapshotCount)));
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
        LOGGER.info("BLACK_FREQUENCY_TEST RESTORATION_PROGRESS_HELD_BELOW_COMPLETION");
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
            event.getEntity().distanceToSqr(pos.getX()+0.5D,pos.getY()+0.5D,pos.getZ()+0.5D),
            event.isCanceled(),event.getUseBlock(),event.getUseItem())) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!s.started || s.restored || s.restoring || s.snapshots.isEmpty()) return;
        if(!beaconWithinAnchor(pos.getX(),pos.getZ(),s.x,s.z)) return;
        // Log only accepted player interactions; rejected distant beacons must
        // never count as successful activation in the real-client CI test.
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION x={} y={} z={} natural={}",pos.getX(),pos.getY(),pos.getZ(),pos.asLong()==s.naturalBeaconPos);
        s.restoring=true;s.restoreIndex=s.snapshots.size()-1;s.restoreScan=s.restoreIndex;s.restoreTicks=0;s.restoredBlocks=0;s.skippedOccupied=0;s.skippedInvalid=0;s.beaconPos=pos.asLong();s.setDirty();
        // Keep the physical beacon visible during activation. A full-screen title
        // covered the object in the player's view; use the action bar instead.
        // Only nearby, active players should receive this world-local notice.
        for(var player:level.players()) {
            if(!player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.distanceToSqr(pos.getX()+0.5D,pos.getY()+0.5D,pos.getZ()+0.5D)>128.0D*128.0D)
                continue;
            player.displayClientMessage(Component.literal("§5✦ Маяк запустил восстановление мира"),true);
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
                        nearby.displayClientMessage(Component.literal(
                            "§5✦ Маяк стабилизации: §f"+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" §7— нажмите ПКМ"),true);
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

    private static java.util.Set<Long> beginRestoreTick(State s) {
        s.restoreFailedWrites.clear();
        return s.restoreFailedWrites;
    }

    private static void restore(ServerLevel level,State s) {
        // Vanilla can open its beacon configuration screen despite the server
        // interaction being consumed by the rift. Close only the activated
        // beacon's nearby menu; do not interrupt unrelated inventories.
        BlockPos activatedBeacon=BlockPos.of(s.beaconPos);
        // Resolve the physical beacon once per restoration tick. All cosmetic
        // effects below share this loaded-only result; none may force a chunk load.
        boolean beaconPresent=level.hasChunkAt(activatedBeacon)
            && level.getBlockState(activatedBeacon).is(Blocks.BEACON);
        double beaconX=activatedBeacon.getX()+0.5D;
        double beaconY=activatedBeacon.getY()+0.5D;
        double beaconZ=activatedBeacon.getZ()+0.5D;
        // Do not close an unrelated beacon menu after the activated beacon
        // has been broken or its chunk unloaded. Neither check loads chunks.
        if(beaconPresent) {
            for(var player:level.players()) {
                if(player.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu
                    && player.blockPosition().distSqr(activatedBeacon)<144d) {
                    player.closeContainer();
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_MENU_CLOSED");
                }
            }
        }
        s.restoreTicks=nextNonNegativeTick(s.restoreTicks);
        if(CI_TEST && s.restoreTicks==1)
            LOGGER.info("BLACK_FREQUENCY_TEST RESTORE_BEACON_PRESENCE_CACHED present={}",beaconPresent);
        // The 8-tick helix and 20-tick ring/audience pulse share one player scan.
        // Preserve their distinct vertical centers without a second stream traversal.
        boolean hasRingViewer=false;
        boolean hasStabilizationViewer=false;
        if(beaconPresent && s.restoreTicks%4==0) {
            boolean stabilizationDue=shouldEmitRestoreAudiencePulse(s.restoreTicks);
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if(!hasRingViewer
                    && player.distanceToSqr(beaconX,beaconY,beaconZ)<=128.0D*128.0D)
                    hasRingViewer=true;
                if(stabilizationDue && !hasStabilizationViewer
                    && player.distanceToSqr(beaconX,beaconY+0.6D,beaconZ)<=128.0D*128.0D)
                    hasStabilizationViewer=true;
                if(hasRingViewer && (!stabilizationDue || hasStabilizationViewer)) break;
            }
        }
        // A visible, bounded stabilization pulse at the activated physical beacon.
        // Only query an already loaded chunk; never resurrect a removed beacon
        // or force chunk generation during recovery.
        if(shouldEmitRestoreAudiencePulse(s.restoreTicks) && hasStabilizationViewer) {
            double cx=beaconX, cy=beaconY+0.6D, cz=beaconZ;
            // Contract the recovery vortex as the saved terrain is restored.
            // Clamp corrupt/out-of-range indices so the effect stays bounded.
            double contraction=recoveryVortexContraction(s.restoreIndex,s.snapshots.size());
            // Two contrasting violet particle types distinguish the counter-rotating
            // inner and outer rings without increasing particle count or chunk work.
            // Only 24 particles per second; no terrain edits or chunk loads.
            for(int i=0;i<RESTORE_RING_PARTICLE_COUNT;i++) {
                double angle=i*Math.PI/12.0D + (i%2==0 ? 1.0D : -1.0D)*s.restoreTicks*0.045D;
                // The fracture closes in a slow, bounded violet heartbeat. Keep the
                // inner and outer rings separated even at maximum contraction.
                double radius=(i%2==0?1.3D:2.1D)
                    +0.16D*Math.sin(s.restoreTicks*0.025D);
                radius*=contraction;
                // A slow vertical wave makes the rings visibly breathe without
                // increasing particle count or modifying nearby blocks.
                double height=(i%2==0?0.0D:0.45D)
                    +0.18D*Math.sin(angle*2.0D+s.restoreTicks*0.035D);
                DustParticleOptions ringDust=(i&1)==0
                    ? RESTORE_VIOLET_DUST : RESTORE_VIOLET_ACCENT_DUST;
                level.sendParticles(ringDust,
                    cx+Math.cos(angle)*radius,cy+height,cz+Math.sin(angle)*radius,
                    1,0,0.08D,0,0.015D);
            }
            // Four rising violet sparks connect the ground-level rings to the
            // beacon beam. Deterministic positions avoid per-tick random work;
            // the effect stays inside a 2.3-block radius and 3-block height.
            for(int i=0;i<RESTORE_RISING_SPARK_PARTICLE_COUNT;i++) {
                double phase=s.restoreTicks*0.055D+i*Math.PI/2.0D;
                double height=0.25D+((s.restoreTicks*0.04D+i*0.25D)%2.5D);
                level.sendParticles(RESTORE_VIOLET_ACCENT_DUST,
                    cx+Math.cos(phase)*0.75D*contraction,cy+height,
                    cz+Math.sin(phase)*0.75D*contraction,
                    1,0,0,0,0);
            }
            if(CI_TEST && s.restoreTicks==20)
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE viewerScan=shared");
        }
        if(CI_TEST && s.restoreTicks==1) verifyBeaconInteractionGuard();
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
        if(s.restoreTicks%40==0 && !s.snapshots.isEmpty()) {
            int percent=restorationPercent(s.restoreIndex,s.snapshots.size());
            // Restoration is local to the fracture. Do not spam action-bar
            // updates to players exploring distant parts of the Overworld.
            for(var player:level.players()) {
                if(player.isAlive() && !player.isRemoved() && !player.isSpectator()
                    && withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z))
                    player.displayClientMessage(Component.literal("§5✦ §5СТАБИЛИЗАЦИЯ §f"+percent+"% §7— восстановлено "+s.restoredBlocks+" блоков"),true);
            }
        }
        if(s.restoreTicks%10==0) {
            float remaining=s.snapshots.isEmpty()?0f:Math.max(0f,(s.restoreIndex+1f)/s.snapshots.size());
            // The recovery overlay is local to the fracture. Do not send
            // recovery packets to distant, dead, removed or spectator players.
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if(withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z))
                    RiftNetwork.send(player,remaining,false,0,s.x,s.z);
            }
        }
        int restoredThisTick=0,checks=0;
        // Never revisit a small pending set dozens of times in one tick.
        // In particular, a single snapshot in an unloaded chunk costs one
        // hasChunk check, not the full 48-check scan budget.
        int scanBudget=Math.min(48,Math.max(0,s.restoreIndex+1));
        // A failed block write must not be attempted repeatedly in one tick.
        // Other loaded snapshots can still recover; retry this position next tick.
        java.util.Set<Long> failedWritesThisTick=beginRestoreTick(s);
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
                LOGGER.warn("BLACK FREQUENCY: malformed recovery snapshot at index {}; no terrain edit",candidate);
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
            BlockPos pos=BlockPos.of(entry.getLong("pos"));
            checks++;
            // Validate saved coordinates before querying chunk availability.
            // Malformed snapshots outside the bounded fracture must be retired
            // even when their referenced chunk is unloaded; never load it.
            if(!isRestorableSnapshotPosition(pos,s,level)) {
                s.skippedInvalid++;
                LOGGER.warn("BLACK FREQUENCY: out-of-bounds recovery snapshot at {}; no terrain edit",pos);
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
                LOGGER.warn("BLACK FREQUENCY: recovery snapshot outside world border at {}; no terrain edit",pos);
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
                BlockState duplicateOriginal=NbtUtils.readBlockState(
                    level.holderLookup(Registries.BLOCK),entry.getCompound("state"));
                if(duplicateOriginal.isAir()) {
                    s.skippedInvalid++;
                    LOGGER.warn("BLACK FREQUENCY: duplicate recovery snapshot at {} resolves to air; no terrain edit",pos);
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
            if(!level.hasChunk(pos.getX()>>4,pos.getZ()>>4)) {
                if(CI_TEST && Boolean.getBoolean("blackfrequency.ciDeferredChunkTest")
                    && s.restoreTicks==1)
                    LOGGER.info("BLACK_FREQUENCY_TEST DEFERRED_CHUNK_SKIPPED x={} z={}",pos.getX(),pos.getZ());
                s.restoreScan--;
                continue;
            }
            // Only retire a snapshot after a successful write or a deliberate
            // preservation decision. A failed setBlock must remain pending so
            // another tick (or a server restart) can retry without data loss.
            BlockState current=level.getBlockState(pos);
            boolean flowingFluid=(current.is(Blocks.WATER) || current.is(Blocks.LAVA))
                && !current.getFluidState().isSource();
            if(!current.isAir() && !flowingFluid) {
                s.skippedOccupied++;
            } else {
                BlockState original=NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),entry.getCompound("state"));
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
                    LOGGER.warn("BLACK FREQUENCY: recovery snapshot at {} resolves to air; no terrain edit",pos);
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
        // Avoid phantom rift rings after a beacon is removed, and never
        // query or generate an unloaded chunk just for cosmetic particles.
        // Skip cosmetic work when nobody can see the recovery beacon.
        // The shared viewer scan reads only player positions and never requests a chunk.
        if(shouldEmitRestoreHelix(s.restoreTicks) && hasRingViewer) {
            double angle=s.restoreTicks*.17;
            // A slow breathing ring makes recovery visibly alive without
            // adding particles, world edits, or forced chunk loads.
            // Keep the outer recovery helix synchronized with the shrinking inner vortex.
            // This is cosmetic only: no terrain edits or extra particle packets.
            double radius=(3.4D+0.45D*Math.sin(s.restoreTicks*.055D))
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
                double phase=(s.restoreTicks*.045D*(k%2==0 ? 1.0D : -1.0D)
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
        if(shouldEmitRestoreAudiencePulse(s.restoreTicks) && beaconPresent) {
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
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest") && s.skippedInvalid!=3)
                throw new IllegalStateException("CI malformed/out-of-bounds/duplicate snapshot quarantine failed: "+s.skippedInvalid);
            s.restoring=false;s.restored=true;s.finished=true;s.snapshots.clear();
            for(var player:level.players()) RiftNetwork.send(player,0f,false,0,s.x,s.z);
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored={} destroyed={} skippedOccupied={} skippedInvalid={} flowingReplaced={}",s.restoredBlocks,s.edits,s.skippedOccupied,s.skippedInvalid,s.ciFlowingReplaced);
            // Disposable client test: dispatch a synthetic BreakEvent after
            // restoration. This verifies cancellation and block consumption,
            // but does not replace a real player-mining/drop integration test.
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest")) {
                if(s.naturalBeaconPos==Long.MIN_VALUE || level.players().isEmpty())
                    throw new IllegalStateException("CI recovery beacon missing after restoration");
                BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
                if(!isOwnedNaturalBeaconAtLoadedPosition(level,s,beacon))
                    throw new IllegalStateException("CI recovery beacon ownership marker missing after restoration");
                // A protection mod may have canceled the break before our handler.
                // It must leave both the physical beacon and its saved anchor intact.
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
                var testEvent=new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                    level,beacon,level.getBlockState(beacon),level.players().get(0));
                new RiftController().onRecoveryBeaconBreak(testEvent);
                if(!testEvent.isCanceled() || !level.getBlockState(beacon).isAir()
                    || s.naturalBeaconPos!=Long.MIN_VALUE || s.naturalBeaconId!=null)
                    throw new IllegalStateException("CI post-restoration beacon break guard failed");
                LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_ID_CLEARED_AFTER_BREAK");
                LOGGER.info("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_CONSUMED x={} y={} z={}",
                    beacon.getX(),beacon.getY(),beacon.getZ());
            }
            for(var player:level.players()) {
                // Completion is a local event. Do not interrupt distant players
                // or spectators with an unrelated full-screen title.
                // Completion titles share the tested 3D radius with recovery particles.
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()
                    || !withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z)) continue;
                player.displayClientMessage(Component.literal("§5✦ СТАБИЛИЗАЦИЯ 100% §7— восстановлено "+s.restoredBlocks+" блоков; пропущено "+s.skippedOccupied),true);
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(
                    s.skippedOccupied==0 && s.skippedInvalid==0 ? "§5МИР ВОССТАНОВЛЕН" : "§5СТАБИЛИЗАЦИЯ ЗАВЕРШЕНА")));
                player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(
                    s.skippedOccupied==0 && s.skippedInvalid==0 ? "§7Разлом закрывается" : "§7Пропущено занятых: "+s.skippedOccupied+"; повреждённых записей: "+s.skippedInvalid)));
            }
        }
        s.setDirty();
    }

    @SubscribeEvent
    public void onTick(TickEvent.LevelTickEvent event) {
        if(event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
           || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        State s = level.getDataStorage().computeIfAbsent(State::load, State::new, "blackfrequency_finale");
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
        if(s.restoring) { restore(level,s); return; }
        // Keep clients synchronized even on old worlds or after the finale.
        if(level.getGameTime()%(s.started && !s.finished ? 10L : 100L)==0) {
            float progress=s.restored ? 0f : s.finished ? 1f : Math.max(0f, Math.min(1f,(s.elapsed-3f*DAY)/(7f*DAY)));
            for(var player : level.players()) RiftNetwork.send(player,progress,!s.restored && (s.started || s.finished), s.finaleTicks,s.x,s.z);
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
                for(var player : level.players()) RiftNetwork.send(player,1f,true,0,s.x,s.z);
                // The fracture is local: do not interrupt distant players or
                // spectators with a full-screen finale announcement.
                for(var player : level.players()) {
                    if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                    long dx=(long)player.blockPosition().getX()-s.x;
                    long dz=(long)player.blockPosition().getZ()-s.z;
                    if(Math.abs(dx)>128L || Math.abs(dz)>128L
                        || dx*dx+dz*dz>128L*128L) continue;
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 120, 25));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§5§lВЫСВОБОЖДЕНИЕ")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§5Мир больше не цел.")));
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
            int surfaceY=findFissureGroundY(level,s.x,s.z);
            if(surfaceY!=Integer.MIN_VALUE) {
                double plumeY=surfaceY+1.0;
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
                            LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_PARTICLES_EMITTED samples={} accentDust={} violetDust={} shards={} total={} heightmap=motion-blocking-no-leaves ground=terrain-support-filtered averageY={}",
                                emission.samples(),emission.accentDustMotes(),emission.violetDustMotes(),
                                emission.shards(),emission.totalParticles(),emission.averageSurfaceY());
                            LOGGER.info("BLACK_FREQUENCY_TEST SURFACE_FISSURE_SHARDS_EMITTED count={} material=crying_obsidian",
                                emission.shards());
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
                final double outerRadius=RADIUS+16.0;
                double distanceSquared=ax*ax+ay*ay+az*az;
                if(distanceSquared>=outerRadius*outerRadius) continue;
                double fade=Math.min(1.0,Math.max(0.0,
                    (outerRadius-Math.sqrt(distanceSquared))/16.0));
                if(fade<.125) continue;
                // Match the server-side gravity field so particles mark actual
                // lift/sink zones instead of an unrelated moving pattern.
                double zone=Math.sin(player.getX()*.115+Math.sin(s.finaleTicks*.011)*1.8)
                           *Math.cos(player.getZ()*.125-s.finaleTicks*.008);
                if(zone>.42) {
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        player.getX(),player.getY()+.5,player.getZ(),
                        Math.max(1,(int)Math.ceil(9*fade)),2.2,.7,2.2,.11);
                    // Violet sparks keep the lift field in the catastrophe palette
                    // instead of introducing bright white END_ROD flashes.
                    level.sendParticles(ParticleTypes.DRAGON_BREATH,
                        player.getX(),player.getY()+.3,player.getZ(),
                        Math.max(1,(int)Math.ceil(3*fade)),1.4,.25,1.4,.04);
                } else if(zone<-.68) {
                    // Sinking anomalies now read as purple distortion, not
                    // ordinary grey fire smoke; keep the same particle budget.
                    level.sendParticles(ParticleTypes.DRAGON_BREATH,
                        player.getX(),player.getY()+.15,player.getZ(),
                        Math.max(1,(int)Math.ceil(7*fade)),1.8,.25,1.8,.018);
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
                            && player.distanceToSqr(beacon.getX()+.5D,beacon.getY()+1.2D,
                                beacon.getZ()+.5D)<=64.0D*64.0D))
                        level.sendParticles(FISSURE_VIOLET_DUST,
                            beacon.getX()+.5,beacon.getY()+1.2,beacon.getZ()+.5,
                            IDLE_BEACON_PULSE_PARTICLE_COUNT,.28,.45,.28,.015);
                    if(shouldSendIdleBeaconHint(s.restoring,s.restored,s.finaleTicks)) for(var player:level.players())
                        if(player.isAlive() && !player.isRemoved() && !player.isSpectator()
                            && withinRecoveryAudience(player.blockPosition(),
                                beacon.getX(),beacon.getY(),beacon.getZ()))
                            player.displayClientMessage(Component.literal(
                                "§5✦ Маяк стабилизации: §f"+beacon.getX()+" "+beacon.getY()+" "+beacon.getZ()+" §7— нажмите ПКМ"),true);
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
            && !s.restoring && s.beaconPos==0L && (s.edits>=160 || ciSparseReady) && !s.snapshots.isEmpty()
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
                    // CI-only clear line of sight north of the physical beacon.
                    // Keep the player standing on a stable platform for the real click.
                    // Clear the final line-of-sight block as well: a random
                    // hillside at dz=-1 can otherwise hide the beacon from
                    // the real mouse-click test even after teleportation.
                    for(int dz=-4;dz<=-1;dz++) for(int dx=-1;dx<=1;dx++) {
                        BlockPos floor=demoPos.offset(dx,-1,dz);
                        if(!level.hasChunk(floor.getX()>>4,floor.getZ()>>4)) continue;
                        level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
                        for(int dy=1;dy<=3;dy++)
                            level.setBlock(floor.above(dy),Blocks.AIR.defaultBlockState(),3);
                    }
                    // Aim the CI player at the beacon centre from a closer,
                    // unobstructed position. The previous 3-block standoff could
                    // leave the crosshair just outside its hitbox on random terrain.
                    demoPlayer.teleportTo(level,demoPos.getX()+.5,demoPos.getY(),
                        demoPos.getZ()-1.75,0f,0f);
                    // Aim from the player's eyes at the actual beacon hitbox instead of
                    // relying on a fixed pitch that misses on different terrain heights.
                    demoPlayer.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                        net.minecraft.world.phys.Vec3.atCenterOf(demoPos));
                    // The client must receive the post-lookAt rotation before XTest sends
                    // the real right-click; teleportTo's earlier packet used the old angle.
                    demoPlayer.connection.teleport(demoPlayer.getX(),demoPlayer.getY(),demoPlayer.getZ(),
                        demoPlayer.getYRot(),demoPlayer.getXRot());
                    demoPlayer.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    // teleportTo changes position but does not clear accumulated fall damage.
                    // The player may already be falling because the fracture removed the
                    // original spawn column; clear that history before the first pad tick.
                    demoPlayer.fallDistance=0.0F;
                    BlockPos playerSupport=demoPlayer.blockPosition().below();
                    if(!level.hasChunk(playerSupport.getX()>>4,playerSupport.getZ()>>4)
                        || !level.getBlockState(playerSupport).blocksMotion()) {
                        LOGGER.warn("BLACK_FREQUENCY_TEST PLAYER_BEACON_SAFE_PAD_UNAVAILABLE x={} y={} z={}",
                            playerSupport.getX(),playerSupport.getY(),playerSupport.getZ());
                        return;
                    }
                    FissureTarget visibleFissure=findVisibleFissureTarget(level,s,demoPlayer);
                    if(visibleFissure==null) {
                        if(!ciFissureTargetUnavailableLogged) {
                            LOGGER.warn("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET_UNAVAILABLE");
                            ciFissureTargetUnavailableLogged=true;
                        }
                    } else {
                        LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_VISIBLE_FISSURE_TARGET x={} y={} z={} distanceSquared={} horizontalDistanceSquared={} selection=nearest",
                            visibleFissure.x(),visibleFissure.y(),visibleFissure.z(),
                            visibleFissure.distanceSquared(),visibleFissure.horizontalDistanceSquared());
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
                demoPlayer.displayClientMessage(Component.literal(
                    "§5✦ Маяк готов — нажмите ПКМ для стабилизации"),true);
            }
            s.beaconPos=demoPos.asLong();
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
            s.snapshots.add(snapshot);
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
            }
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
