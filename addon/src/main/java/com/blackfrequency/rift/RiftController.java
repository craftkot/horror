package com.blackfrequency.rift;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** No commands. Ten days after installing this addon in a world, its finale begins. */
public final class RiftController {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final boolean CI_TEST = Boolean.getBoolean("blackfrequency.ciTestMode");
    private static final long DAY = 24000L;
    // Only for disposable CI worlds. Never increases Minecraft's global tick rate.
    private static final long TEST_STEP = Boolean.getBoolean("blackfrequency.ciTestMode")
        ? Math.max(1L, Math.min(2400L, Long.getLong("blackfrequency.ciTickStep", 2400L))) : 1L;
    private static final int RADIUS = 48;
    private static final int EDIT_BUDGET = 160;
    private static final int MAX_EDITS = 95000;
    // Only disposable CI worlds can stop after a small fracture to test
    // recovery when fewer than 160 blocks were damaged.
    private static final int EFFECTIVE_MAX_EDITS = CI_TEST && Boolean.getBoolean("blackfrequency.ciSparseRecoveryTest")
        ? 80 : MAX_EDITS;

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
        int edits;
        int finaleTicks;
        boolean started, finished, initialized, restoring, restored;
        int restoreIndex, restoreScan, restoreTicks, restoredBlocks, skippedOccupied, skippedInvalid;
        long beaconPos;
        // A one-time recovery beacon is placed in a loaded, safe air block.
        // Keep it separate from the beacon that a player actually activates.
        long naturalBeaconPos=Long.MIN_VALUE;
        // CI diagnostics only; never persisted in production saves.
        long ciRejectedRestorePos=Long.MIN_VALUE;
        int ciFlowingReplaced;
        boolean ciRestoredGravityGuardLogged;
        // CI-only one-shot beacon removal: exercise recovery after a lost beacon.
        boolean ciNaturalBeaconRemoved, ciNaturalBeaconReplaced;
        boolean ciSparseRecoveryEligibilityLogged;
        final List<CompoundTag> snapshots = new ArrayList<>();
        static State load(CompoundTag n) {
            State s = new State();
            s.elapsed=n.getLong("elapsed");
            s.x=n.getInt("x");s.z=n.getInt("z");
            s.cursorX=n.getInt("cx");s.cursorZ=n.getInt("cz");s.cursorY=n.getInt("cy");
            s.edits=n.getInt("edits");s.finaleTicks=n.getInt("finaleTicks");
            s.started=n.getBoolean("started");s.finished=n.getBoolean("finished");
            s.initialized=n.getBoolean("initialized");
            s.restoring=n.getBoolean("restoring");s.restored=n.getBoolean("restored");
            s.restoreIndex=n.getInt("restoreIndex");
            // Older worlds have no scan cursor: resume at the last pending snapshot.
            s.restoreScan=n.contains("restoreScan",Tag.TAG_INT)?n.getInt("restoreScan"):s.restoreIndex;
            s.restoreTicks=n.getInt("restoreTicks");s.restoredBlocks=n.getInt("restoredBlocks");s.skippedOccupied=n.getInt("skippedOccupied");s.skippedInvalid=n.getInt("skippedInvalid");s.beaconPos=n.getLong("beaconPos");
            s.naturalBeaconPos=n.contains("naturalBeaconPos",Tag.TAG_LONG)?n.getLong("naturalBeaconPos"):Long.MIN_VALUE;
            ListTag saved=n.getList("snapshots",Tag.TAG_COMPOUND);
            for(int i=0;i<saved.size();i++) s.snapshots.add(saved.getCompound(i));
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
            n.putBoolean("finished",finished);n.putBoolean("initialized",initialized);
            n.putBoolean("restoring",restoring);n.putBoolean("restored",restored);
            n.putInt("restoreIndex",restoreIndex);n.putInt("restoreScan",restoreScan);n.putInt("restoreTicks",restoreTicks);n.putInt("restoredBlocks",restoredBlocks);n.putInt("skippedOccupied",skippedOccupied);n.putInt("skippedInvalid",skippedInvalid);n.putLong("beaconPos",beaconPos);
            n.putLong("naturalBeaconPos",naturalBeaconPos);
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
        if(!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.BEACON)) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!s.started || pos.asLong()!=s.naturalBeaconPos
            || !level.getWorldBorder().isWithinBounds(pos)) return;
        // Cancel vanilla harvesting before it creates a valuable beacon item.
        event.setCanceled(true);
        if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),3)
            && level.getBlockState(pos).isAir()) {
            s.naturalBeaconPos=Long.MIN_VALUE;
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
            || !level.hasChunkAt(beacon)
            // A border shrink retires the generated fixture. Do not keep
            // shielding a block at an inaccessible, stale coordinate.
            || !level.getWorldBorder().isWithinBounds(beacon)
            || !level.getBlockState(beacon).is(Blocks.BEACON)) return;
        // Explosion affected blocks are already loaded; this does not request
        // additional chunks or modify terrain.
        if(protectRecoveryBeaconFromExplosion(event.getAffectedBlocks(),s.naturalBeaconPos)
            && CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_EXPLOSION_PROTECTED");
    }

    /** Reject overflowing fracture columns before narrowing coordinates to int. */
    private static boolean validFractureColumn(int anchorX,int anchorZ,int offsetX,int offsetZ) {
        long x=(long)anchorX+offsetX,z=(long)anchorZ+offsetZ;
        return x>=Integer.MIN_VALUE && x<=Integer.MAX_VALUE
            && z>=Integer.MIN_VALUE && z<=Integer.MAX_VALUE;
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

    /** Verify that a pending terrain snapshot survives a SavedData round trip. */
    private static void verifyPendingSnapshotPersistence() {
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
        LOGGER.info("BLACK_FREQUENCY_TEST PENDING_SNAPSHOT_PERSISTENCE_VERIFIED");
    }

    /** Horizontal anchor check with overflow-safe coordinates, independent of beacon height. */
    private static boolean beaconWithinAnchor(int x,int z,int anchorX,int anchorZ) {
        long dx=(long)x-anchorX,dz=(long)z-anchorZ;
        return Math.abs(dx)<=128L && Math.abs(dz)<=128L && dx*dx+dz*dz<=128L*128L;
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
            || withinRecoveryAudience(new BlockPos(Integer.MAX_VALUE,0,0),Integer.MIN_VALUE,0,0))
            throw new IllegalStateException("CI recovery audience 3D radius regression");
        LOGGER.info("BLACK_FREQUENCY_TEST RECOVERY_AUDIENCE_BOUNDS_VERIFIED");
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
        verifyRecoveryAudienceBounds();
        verifyFractureColumnBounds();
        verifyBoundedAnomalyVelocity();
        verifyBoundedLiftVelocity();
        verifyBoundedSinkVelocity();
        verifyBoundedSinkDrag();
        verifyPendingSnapshotPersistence();
        LOGGER.info("BLACK_FREQUENCY_TEST BEACON_INTERACTION_GUARD_VERIFIED");
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
        if(!level.hasChunkAt(pos) || !level.getBlockState(pos).is(Blocks.BEACON)) return;
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
        // Only nearby, active players should see the stabilization title.
        // A distant player must not receive a misleading world-local event.
        for(var player:level.players()) {
            if(!player.isAlive() || player.isRemoved() || player.isSpectator()
                || player.distanceToSqr(pos.getX()+0.5D,pos.getY()+0.5D,pos.getZ()+0.5D)>128.0D*128.0D)
                continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(10,100,30));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§5СТАБИЛИЗАЦИЯ")));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§dМаяк возвращает миру его форму")));
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED");
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
            for(int distance=3;distance<=10;distance++) {
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
                    // Disposable sparse-client regression: make the replacement
                    // beacon stand above a real snow layer, rather than relying
                    // on a random snowy biome. Never enabled in normal worlds.
                    boolean snowFixture=CI_TEST && Boolean.getBoolean("blackfrequency.ciSnowBeaconTest")
                        && s.ciNaturalBeaconRemoved;
                    if(snowFixture && level.getBlockState(pos).isAir()
                        && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).blocksMotion()
                        && level.getBlockState(pos.below()).getFluidState().isEmpty()
                        && !level.setBlock(pos,Blocks.SNOW.defaultBlockState(),3))
                        throw new IllegalStateException("CI snow support placement failed");
                    // Motion-blocking heightmaps can stop at a solid block below
                    // a thin snow layer. Never replace the snow: put the beacon
                    // in free air above it, provided the snow rests on solid ground.
                    if(level.getBlockState(pos).is(Blocks.SNOW)) pos=pos.above();
                    if(pos.getY()>=level.getMaxBuildHeight()-1) continue;
                    BlockState support=level.getBlockState(pos.below());
                    BlockState snowFoundation=level.getBlockState(pos.below(2));
                    // Snow is not a safe platform when the block underneath can
                    // fall away or destroy the objective after an update.
                    boolean supportedSnow=support.is(Blocks.SNOW)
                        && snowFoundation.blocksMotion()
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
                        && !snowFoundation.is(Blocks.FROSTED_ICE);
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
                    if(!level.setBlock(pos,Blocks.BEACON.defaultBlockState(),3)) continue;
                    // Other mods may intercept or replace the placement. Do not
                    // persist a recovery objective unless a beacon really exists.
                    if(!level.getBlockState(pos).is(Blocks.BEACON)) continue;
                    if(snowFixture) {
                        if(!level.getBlockState(pos.below()).is(Blocks.SNOW)
                            || !level.getBlockState(pos.below(2)).blocksMotion())
                            throw new IllegalStateException("CI snow support was destroyed");
                        LOGGER.info("BLACK_FREQUENCY_TEST SNOW_BEACON_SUPPORT_VERIFIED x={} y={} z={} snowY={}",
                            pos.getX(),pos.getY(),pos.getZ(),pos.getY()-1);
                    }
                    s.naturalBeaconPos=pos.asLong();
                    s.setDirty();
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
                    // A visible, bounded violet signal marks the newly spawned beacon.
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL,pos.getX()+.5,pos.getY()+1.2,pos.getZ()+.5,18,.35,.55,.35,.035);
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_PLACED x={} y={} z={}",pos.getX(),pos.getY(),pos.getZ());
                    for(var nearby:level.players()) {
                        // Only active players inside the local fracture area
                        // should receive coordinates for the recovery objective.
                        // Use long arithmetic to avoid overflow near world limits.
                        if(!nearby.isAlive() || nearby.isRemoved() || nearby.isSpectator()) continue;
                        long hintDx=(long)nearby.blockPosition().getX()-pos.getX();
                        long hintDz=(long)nearby.blockPosition().getZ()-pos.getZ();
                        if(Math.abs(hintDx)>128 || Math.abs(hintDz)>128
                            || hintDx*hintDx+hintDz*hintDz>128L*128L) continue;
                        nearby.displayClientMessage(Component.literal(
                            "§d✦ Маяк стабилизации: §f"+pos.getX()+" "+pos.getY()+" "+pos.getZ()+" §7— нажмите ПКМ"),true);
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

    private static void restore(ServerLevel level,State s) {
        // Vanilla can open its beacon configuration screen despite the server
        // interaction being consumed by the rift. Close only the activated
        // beacon's nearby menu; do not interrupt unrelated inventories.
        BlockPos activatedBeacon=BlockPos.of(s.beaconPos);
        for(var player:level.players()) {
            if(player.containerMenu instanceof net.minecraft.world.inventory.BeaconMenu
                && player.blockPosition().distSqr(activatedBeacon)<144d) {
                player.closeContainer();
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_MENU_CLOSED");
            }
        }
        s.restoreTicks++;
        // A visible, bounded stabilization pulse at the activated physical beacon.
        // Only query an already loaded chunk; never resurrect a removed beacon
        // or force chunk generation during recovery.
        if(s.restoreTicks%20==0 && level.hasChunkAt(activatedBeacon)
            && level.getBlockState(activatedBeacon).is(Blocks.BEACON)
            && level.players().stream().anyMatch(player -> player.isAlive()
                && !player.isRemoved() && !player.isSpectator()
                && player.distanceToSqr(activatedBeacon.getX()+0.5D,
                    activatedBeacon.getY()+1.1D,activatedBeacon.getZ()+0.5D)<=128.0D*128.0D)) {
            double cx=activatedBeacon.getX()+0.5D, cy=activatedBeacon.getY()+1.1D;
            double cz=activatedBeacon.getZ()+0.5D;
            // Counter-rotating violet rings show stabilization progressing over time.
            // Only 24 particles per second; no terrain edits or chunk loads.
            for(int i=0;i<24;i++) {
                double angle=i*Math.PI/12.0D + (i%2==0 ? 1.0D : -1.0D)*s.restoreTicks*0.045D;
                double radius=(i%2==0)?1.3D:2.1D;
                // A slow vertical wave makes the rings visibly breathe without
                // increasing particle count or modifying nearby blocks.
                double height=(i%2==0?0.0D:0.45D)
                    +0.18D*Math.sin(angle*2.0D+s.restoreTicks*0.035D);
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                    cx+Math.cos(angle)*radius,cy+height,cz+Math.sin(angle)*radius,
                    1,0,0.08D,0,0.015D);
            }
            if(CI_TEST && s.restoreTicks==20)
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_STABILIZATION_PULSE");
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
            int percent=(int)(100d*(s.snapshots.size()-Math.max(0,s.restoreIndex+1))/(double)s.snapshots.size());
            // Restoration is local to the fracture. Do not spam action-bar
            // updates to players exploring distant parts of the Overworld.
            for(var player:level.players()) {
                if(player.isAlive() && !player.isRemoved() && !player.isSpectator()
                    && withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z))
                    player.displayClientMessage(Component.literal("§5✦ §dСТАБИЛИЗАЦИЯ §f"+percent+"% §7— восстановлено "+s.restoredBlocks+" блоков"),true);
            }
        }
        if(s.restoreTicks%10==0) {
            float remaining=s.snapshots.isEmpty()?0f:Math.max(0f,(s.restoreIndex+1f)/s.snapshots.size());
            // The recovery overlay is local to the fracture. Do not send
            // recovery packets to distant, dead, removed or spectator players.
            for(var player:level.players()) {
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                if(withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z))
                    RiftNetwork.send(player,remaining,false,0);
            }
        }
        int restoredThisTick=0,checks=0;
        // Never revisit a small pending set dozens of times in one tick.
        // In particular, a single snapshot in an unloaded chunk costs one
        // hasChunk check, not the full 48-check scan budget.
        int scanBudget=Math.min(48,Math.max(0,s.restoreIndex+1));
        // A failed block write must not be attempted repeatedly in one tick.
        // Other loaded snapshots can still recover; retry this position next tick.
        java.util.Set<Long> failedWritesThisTick=new java.util.HashSet<>();
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
                    if(restoredThisTick%12==0)
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,6,.45,.45,.45,.07);
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
        if(s.restoreTicks%4==0 && level.hasChunkAt(activatedBeacon)
            && level.getBlockState(activatedBeacon).is(Blocks.BEACON)
            // Skip cosmetic work when nobody can see the recovery beacon.
            // This reads only player positions and never requests a chunk.
            && level.players().stream().anyMatch(player -> player.isAlive()
                && !player.isRemoved() && !player.isSpectator()
                && player.distanceToSqr(activatedBeacon.getX()+0.5D,
                    activatedBeacon.getY()+0.5D,activatedBeacon.getZ()+0.5D)<=128.0D*128.0D)) {
            BlockPos beacon=activatedBeacon;
            double angle=s.restoreTicks*.17;
            // A slow breathing ring makes recovery visibly alive without
            // adding particles, world edits, or forced chunk loads.
            double radius=3.4+0.45*Math.sin(s.restoreTicks*.055);
            for(int k=0;k<8;k++) {
                // Counter-rotate the two alternating strands in the horizontal plane too.
                // Their vertical phases already travel in opposite directions.
                double theta=angle*(k%2==0 ? 1.0D : -1.0D)+k*Math.PI*.25;
                double x=beacon.getX()+.5+Math.cos(theta)*radius;
                double z=beacon.getZ()+.5+Math.sin(theta)*radius;
                // Stagger each wisp vertically into a rotating double helix.
                // Keep the same 16 particles per pulse; avoid the old synchronized
                // height reset that made the entire ring jump every 80 ticks.
                // Opposite angular travel for the two alternating strands; the old
                // shared phase made both wisps climb and descend in lockstep.
                double phase=(s.restoreTicks*.045D*(k%2==0 ? 1.0D : -1.0D)
                    + k*Math.PI*.25D)%(Math.PI*2.0D);
                double y=beacon.getY()+.5D+1.1D*(1.0D+Math.sin(phase));
                var particle=(k&1)==0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.WITCH;
                level.sendParticles(particle,x,y,z,2,.12,.15,.12,.025);
            }
        }
        // A destroyed or unloaded beacon must not leave a phantom recovery glow.
        // hasChunkAt only inspects loaded chunks; it never forces chunk generation.
        if(s.restoreTicks%10==0 && level.hasChunkAt(activatedBeacon)
            && level.getBlockState(activatedBeacon).is(Blocks.BEACON)) {
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
                level.sendParticles(ParticleTypes.END_ROD,p.getX()+.5,p.getY()+1.5,p.getZ()+.5,12,3,2,3,.03);
            }
        }
        // Hold the final stabilization glow long enough to be visible in-game.
        if(s.restoreIndex<0 && s.restoreTicks>=100) {
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciRestoreWriteFailureTest") && s.skippedInvalid!=2)
                throw new IllegalStateException("CI malformed/out-of-bounds snapshot quarantine failed: "+s.skippedInvalid);
            s.restoring=false;s.restored=true;s.finished=true;s.snapshots.clear();
            for(var player:level.players()) RiftNetwork.send(player,0f,false,0);
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored={} destroyed={} skippedOccupied={} skippedInvalid={} flowingReplaced={}",s.restoredBlocks,s.edits,s.skippedOccupied,s.skippedInvalid,s.ciFlowingReplaced);
            // Disposable client test: dispatch a synthetic BreakEvent after
            // restoration. This verifies cancellation and block consumption,
            // but does not replace a real player-mining/drop integration test.
            if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPostRestoreBeaconBreakTest")) {
                if(s.naturalBeaconPos==Long.MIN_VALUE || level.players().isEmpty())
                    throw new IllegalStateException("CI recovery beacon missing after restoration");
                BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
                if(!level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)
                    || !level.getBlockState(beacon).is(Blocks.BEACON))
                    throw new IllegalStateException("CI recovery beacon not loaded after restoration");
                // A protection mod may have canceled the break before our handler.
                // It must leave both the physical beacon and its saved anchor intact.
                var deniedEvent=new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                    level,beacon,level.getBlockState(beacon),level.players().get(0));
                deniedEvent.setCanceled(true);
                new RiftController().onRecoveryBeaconBreak(deniedEvent);
                if(!deniedEvent.isCanceled() || !level.getBlockState(beacon).is(Blocks.BEACON)
                    || s.naturalBeaconPos!=beacon.asLong())
                    throw new IllegalStateException("CI canceled beacon break mutated recovery fixture");
                LOGGER.info("BLACK_FREQUENCY_TEST CANCELED_BEACON_BREAK_PRESERVED x={} y={} z={}",
                    beacon.getX(),beacon.getY(),beacon.getZ());
                var testEvent=new net.minecraftforge.event.level.BlockEvent.BreakEvent(
                    level,beacon,level.getBlockState(beacon),level.players().get(0));
                new RiftController().onRecoveryBeaconBreak(testEvent);
                if(!testEvent.isCanceled() || !level.getBlockState(beacon).isAir()
                    || s.naturalBeaconPos!=Long.MIN_VALUE)
                    throw new IllegalStateException("CI post-restoration beacon break guard failed");
                LOGGER.info("BLACK_FREQUENCY_TEST POST_RESTORE_BEACON_BREAK_CONSUMED x={} y={} z={}",
                    beacon.getX(),beacon.getY(),beacon.getZ());
            }
            for(var player:level.players()) {
                // Completion is a local event. Do not interrupt distant players
                // or spectators with an unrelated full-screen title.
                // Completion titles share the tested 3D radius with recovery particles.
                if(!player.isAlive() || player.isRemoved() || player.isSpectator()
                    || !withinRecoveryAudience(player.blockPosition(),s.x,activatedBeacon.getY(),s.z)) continue;
                player.displayClientMessage(Component.literal("§d✦ СТАБИЛИЗАЦИЯ 100% §7— восстановлено "+s.restoredBlocks+" блоков; пропущено "+s.skippedOccupied),true);
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(
                    s.skippedOccupied==0 && s.skippedInvalid==0 ? "§dМИР ВОССТАНОВЛЕН" : "§dСТАБИЛИЗАЦИЯ ЗАВЕРШЕНА")));
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
        if(s.restoring) { restore(level,s); return; }
        // Keep clients synchronized even on old worlds or after the finale.
        if(level.getGameTime()%(s.started && !s.finished ? 10L : 100L)==0) {
            float progress=s.restored ? 0f : s.finished ? 1f : Math.max(0f, Math.min(1f,(s.elapsed-3f*DAY)/(7f*DAY)));
            for(var player : level.players()) RiftNetwork.send(player,progress,!s.restored && (s.started || s.finished), s.finaleTicks);
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
            s.initialized=true;
            BlockPos spawn=level.getSharedSpawnPos();s.x=spawn.getX();s.z=spawn.getZ();
            s.cursorY=level.getMinBuildHeight();s.setDirty();
        }
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
                for(var player : level.players()) RiftNetwork.send(player,1f,true,0);
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
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§dМир больше не цел.")));
                }
            }
            if(level.getGameTime()%20L==0)s.setDirty();
            return;
        }
        s.finaleTicks++;
        // Spatial gravity anomalies drift through the rift field. Server-side only;
        // gentle bounded impulses allow movement without overriding vanilla physics.
        if (s.finaleTicks > 85 && s.finaleTicks % 5 == 0) {
            for (var player : level.players()) {
                // The disposable real-click regression needs a stable raycast.
                // Do not let test-only gravity impulses drift the player away
                // from the beacon between teleportation and mouse input.
                if(CI_TEST && Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest")
                    && s.beaconPos!=0L && !s.restoring) continue;
                // Never apply rift impulses to dead or removed player entities.
                if (!player.isAlive() || player.isRemoved() || player.isCreative() || player.isSpectator()) continue;
                double px=player.getX(), pz=player.getZ();
                // Terrain edits are limited to RADIUS. Gravity must not follow
                // players across the entire Overworld after they leave the rift.
                double ax=px-s.x, az=pz-s.z;
                double distanceSquared=ax*ax+az*az;
                final double outerRadius=RADIUS+16.0;
                if(distanceSquared>=outerRadius*outerRadius) continue;
                // Fade impulses over the outer 16 blocks instead of introducing
                // a sudden velocity discontinuity at the anomaly boundary.
                double fade=Math.min(1.0,Math.max(0.0,
                    (outerRadius-Math.sqrt(distanceSquared))/16.0));
                double zone=Math.sin(px*.115+Math.sin(s.finaleTicks*.011)*1.8)
                            *Math.cos(pz*.125-s.finaleTicks*.008);
                if(zone > .42) {
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
                } else if (zone < -.68) {
                    var v=player.getDeltaMovement();
                    double drag=1.0-.06*fade;
                    player.setDeltaMovement(boundedSinkDrag(v.x,drag),boundedSinkVelocity(v.y,-.09*fade),boundedSinkDrag(v.z,drag));
                    player.hurtMarked=true;
                }
            }
        }
        // Lightweight visual signatures of the same drifting gravity field.
        // Particles only: no extra entities, chunk loads or world edits.
        if (s.finaleTicks > 85 && s.finaleTicks % 8 == 0) {
            for (var player : level.players()) {
                // Particle effects must not follow invalid or spectator entities.
                if (!player.isAlive() || player.isRemoved() || player.isSpectator()) continue;
                double ax=player.getX()-s.x, az=player.getZ()-s.z;
                if(ax*ax+az*az>(RADIUS+16.0)*(RADIUS+16.0)) continue;
                double zone=Math.sin(player.getX()*.19+s.finaleTicks*.035)
                           *Math.cos(player.getZ()*.17-s.finaleTicks*.026);
                if(zone>.48) {
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        player.getX(),player.getY()+.5,player.getZ(),
                        9,2.2,.7,2.2,.11);
                    level.sendParticles(ParticleTypes.END_ROD,
                        player.getX(),player.getY()+.3,player.getZ(),
                        3,1.4,.25,1.4,.04);
                } else if(zone<-.68) {
                    level.sendParticles(ParticleTypes.SMOKE,
                        player.getX(),player.getY()+.15,player.getZ(),
                        7,1.8,.25,1.8,.018);
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
            if(!level.getWorldBorder().isWithinBounds(beacon)) {
                    // A saved position does not prove that the block is still ours:
                    // another mod or player may have replaced the original beacon.
                    // Untrack the inaccessible fixture instead of deleting a
                    // potentially player-owned block after the border shrinks.
                    s.naturalBeaconPos=Long.MIN_VALUE;
                    s.setDirty();
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST OUTSIDE_BORDER_BEACON_RETIRED");
            // Only inspect block state when the chunk is already loaded.
            } else if(level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)) {
                if(!level.getBlockState(beacon).is(Blocks.BEACON)) {
                    s.naturalBeaconPos=Long.MIN_VALUE;
                    s.setDirty();
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_MISSING x={} y={} z={}",
                        beacon.getX(),beacon.getY(),beacon.getZ());
                } else {
                    // The idle beacon is visible only locally. Avoid broadcasting
                    // six particles every server tick when nobody is nearby.
                    // Do not load chunks or create entities for cosmetic effects.
                    if(level.players().stream().anyMatch(player ->
                        player.isAlive() && !player.isRemoved() && !player.isSpectator()
                        && player.distanceToSqr(beacon.getX()+.5D,beacon.getY()+1.2D,
                            beacon.getZ()+.5D)<=64.0D*64.0D))
                        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                            beacon.getX()+.5,beacon.getY()+1.2,beacon.getZ()+.5,
                            6,.28,.45,.28,.015);
                    if(s.finaleTicks%200==0) for(var player:level.players())
                        if(player.isAlive() && !player.isRemoved() && !player.isSpectator()
                            && Math.abs((long)player.blockPosition().getX()-beacon.getX())<=128L
                            && Math.abs((long)player.blockPosition().getZ()-beacon.getZ())<=128L
                            && ((long)player.blockPosition().getX()-beacon.getX())*((long)player.blockPosition().getX()-beacon.getX())
                                + ((long)player.blockPosition().getZ()-beacon.getZ())*((long)player.blockPosition().getZ()-beacon.getZ())<128L*128L)
                            player.displayClientMessage(Component.literal(
                                "§d✦ Маяк стабилизации: §f"+beacon.getX()+" "+beacon.getY()+" "+beacon.getZ()+" §7— нажмите ПКМ"),true);
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
                        demoPos.getZ()-1.75,0f,27f);
                    demoPlayer.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
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
                    "§d✦ Маяк готов — нажмите ПКМ для стабилизации"),true);
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
        while(edits<EDIT_BUDGET && checks<2048 && !s.finished) {
            if(s.edits>=EFFECTIVE_MAX_EDITS || s.cursorX>RADIUS) {s.finished=true;break;}
            // An anchor near the integer coordinate limits must not wrap around
            // and destroy unrelated terrain on the opposite side of the world.
            long worldX=(long)s.x+s.cursorX,worldZ=(long)s.z+s.cursorZ;
            if(!validFractureColumn(s.x,s.z,s.cursorX,s.cursorZ)) {
                s.cursorY=level.getMinBuildHeight();
                s.cursorZ++;
                if(s.cursorZ>RADIUS){s.cursorZ=-RADIUS;s.cursorX++;}
                checks++;
                continue;
            }
            int wx=(int)worldX,wz=(int)worldZ;
            if(!level.hasChunk(wx>>4,wz>>4)) {
                // Never force-load chunks for destruction. Skip this column and keep advancing.
                s.cursorY=level.getMinBuildHeight();
                s.cursorZ++;
                if(s.cursorZ>RADIUS){s.cursorZ=-RADIUS;s.cursorX++;}
                checks++;
                continue;
            }
            int y=s.cursorY;
            // Capture the current column before advancing: otherwise its final block
            // is evaluated against the NEXT column's fracture coordinates.
            int dx=s.cursorX,dz=s.cursorZ;
            s.cursorY++;
            if(s.cursorY>=level.getMaxBuildHeight()) {
                s.cursorY=level.getMinBuildHeight();
                s.cursorZ++;
                if(s.cursorZ>RADIUS){s.cursorZ=-RADIUS;s.cursorX++;}
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
            // Stage the original state before editing. A failed setBlock must not
            // leave a phantom recovery entry; a successful edit must have a
            // snapshot available immediately, before advancing the cursor.
            CompoundTag snapshot=new CompoundTag();
            snapshot.putLong("pos",pos.asLong());
            snapshot.put("state",NbtUtils.writeBlockState(original));
            s.snapshots.add(snapshot);
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16)){
                edits++;s.edits++;
                if(CI_TEST && s.edits==1) LOGGER.info("BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x={} y={} z={}", wx,y,wz);
            } else {
                s.snapshots.remove(s.snapshots.size()-1);
            }
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
