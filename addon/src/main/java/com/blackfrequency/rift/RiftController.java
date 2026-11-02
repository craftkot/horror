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

    private static final class State extends SavedData {
        long elapsed;
        int x, z, cursorX=-RADIUS, cursorZ=-RADIUS, cursorY;
        int edits;
        int finaleTicks;
        boolean started, finished, initialized, restoring, restored;
        int restoreIndex, restoreScan, restoreTicks, restoredBlocks, skippedOccupied;
        long beaconPos;
        // A one-time recovery beacon is placed in a loaded, safe air block.
        // Keep it separate from the beacon that a player actually activates.
        long naturalBeaconPos=Long.MIN_VALUE;
        // CI diagnostics only; never persisted in production saves.
        long ciRejectedRestorePos=Long.MIN_VALUE;
        int ciFlowingReplaced;
        boolean ciRestoredGravityGuardLogged;
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
            s.restoreTicks=n.getInt("restoreTicks");s.restoredBlocks=n.getInt("restoredBlocks");s.skippedOccupied=n.getInt("skippedOccupied");s.beaconPos=n.getLong("beaconPos");
            s.naturalBeaconPos=n.contains("naturalBeaconPos",Tag.TAG_LONG)?n.getLong("naturalBeaconPos"):Long.MIN_VALUE;
            ListTag saved=n.getList("snapshots",Tag.TAG_COMPOUND);
            for(int i=0;i<saved.size();i++) s.snapshots.add(saved.getCompound(i));
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST STATE_LOADED elapsed={} edits={} restored={} restoring={} snapshots={}",s.elapsed,s.edits,s.restored,s.restoring,s.snapshots.size());
            return s;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putLong("elapsed",elapsed);n.putInt("x",x);n.putInt("z",z);
            n.putInt("cx",cursorX);n.putInt("cz",cursorZ);n.putInt("cy",cursorY);
            n.putInt("edits",edits);n.putInt("finaleTicks",finaleTicks);n.putBoolean("started",started);
            n.putBoolean("finished",finished);n.putBoolean("initialized",initialized);
            n.putBoolean("restoring",restoring);n.putBoolean("restored",restored);
            n.putInt("restoreIndex",restoreIndex);n.putInt("restoreScan",restoreScan);n.putInt("restoreTicks",restoreTicks);n.putInt("restoredBlocks",restoredBlocks);n.putInt("skippedOccupied",skippedOccupied);n.putLong("beaconPos",beaconPos);
            n.putLong("naturalBeaconPos",naturalBeaconPos);
            ListTag saved=new ListTag();for(CompoundTag tag:snapshots)saved.add(tag.copy());
            n.put("snapshots",saved);
            return n;
        }
    }
    /** Right-click a vanilla beacon within 128 blocks of the fracture anchor. */
    @SubscribeEvent
    public void onBeacon(PlayerInteractEvent.RightClickBlock event) {
        if(!(event.getLevel() instanceof ServerLevel level)
            || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        BlockPos pos=event.getPos();
        if(!level.getBlockState(pos).is(Blocks.BEACON)) return;
        State s=level.getDataStorage().computeIfAbsent(State::load,State::new,"blackfrequency_finale");
        if(!s.started || s.restored || s.restoring || s.snapshots.isEmpty()) return;
        if(pos.distSqr(new BlockPos(s.x,pos.getY(),s.z))>128d*128d) return;
        // Log only accepted player interactions; rejected distant beacons must
        // never count as successful activation in the real-client CI test.
        if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST PLAYER_BEACON_INTERACTION x={} y={} z={} natural={}",pos.getX(),pos.getY(),pos.getZ(),pos.asLong()==s.naturalBeaconPos);
        s.restoring=true;s.restoreIndex=s.snapshots.size()-1;s.restoreScan=s.restoreIndex;s.restoreTicks=0;s.restoredBlocks=0;s.skippedOccupied=0;s.beaconPos=pos.asLong();s.setDirty();
        for(var player:level.players()) {
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
            if(player.isSpectator()) continue;
            BlockPos playerPos=player.blockPosition();
            int ax=playerPos.getX()-s.x,az=playerPos.getZ()-s.z;
            if(ax*ax+az*az>(RADIUS-8)*(RADIUS-8)) continue;
            var facing=player.getDirection();
            int forwardX=facing.getStepX(),forwardZ=facing.getStepZ();
            for(int distance=4;distance<=8;distance++) {
                for(int side=0;side<=6;side++) {
                    int offset=side==0?0:(side%2==1?(side+1)/2:-(side/2));
                    int x=playerPos.getX()+forwardX*distance-forwardZ*offset;
                    int z=playerPos.getZ()+forwardZ*distance+forwardX*offset;
                    int dx=x-s.x,dz=z-s.z;
                    if(dx*dx+dz*dz>(RADIUS-4)*(RADIUS-4)) continue;
                    if(!level.hasChunk(x>>4,z>>4)) continue;
                    int y=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,x,z);
                    if(y<=level.getMinBuildHeight() || y>=level.getMaxBuildHeight()-2) continue;
                    BlockPos pos=new BlockPos(x,y,z);
                    BlockState support=level.getBlockState(pos.below());
                    if(!support.blocksMotion() || !support.getFluidState().isEmpty()
                        || !level.getBlockState(pos).isAir()
                        || !level.getBlockState(pos.above()).isAir()) continue;
                    if(!level.setBlock(pos,Blocks.BEACON.defaultBlockState(),3)) continue;
                    s.naturalBeaconPos=pos.asLong();
                    s.setDirty();
                    // A visible, bounded violet signal marks the newly spawned beacon.
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL,x+.5,y+1.2,z+.5,18,.35,.55,.35,.035);
                    if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST NATURAL_BEACON_PLACED x={} y={} z={}",x,y,z);
                    for(var nearby:level.players()) {
                        if(nearby.blockPosition().distSqr(pos)<128d*128d)
                            nearby.displayClientMessage(Component.literal(
                                "§d✦ Маяк стабилизации: §f"+x+" "+y+" "+z+" §7— нажмите ПКМ"),true);
                    }
                    return true;
                }
            }
        }
        return false;
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
        if(s.restoreTicks%40==0 && !s.snapshots.isEmpty()) {
            int percent=(int)(100d*(s.snapshots.size()-Math.max(0,s.restoreIndex+1))/(double)s.snapshots.size());
            for(var player:level.players())
                player.displayClientMessage(Component.literal("§5✦ §dСТАБИЛИЗАЦИЯ §f"+percent+"% §7— восстановлено "+s.restoredBlocks+" блоков"),true);
        }
        if(s.restoreTicks%10==0) {
            float remaining=s.snapshots.isEmpty()?0f:Math.max(0f,(s.restoreIndex+1f)/s.snapshots.size());
            for(var player:level.players()) RiftNetwork.send(player,remaining,false,0);
        }
        int restoredThisTick=0,checks=0;
        // A failed block write must not be attempted repeatedly in one tick.
        // Other loaded snapshots can still recover; retry this position next tick.
        java.util.Set<Long> failedWritesThisTick=new java.util.HashSet<>();
        while(s.restoreIndex>=0 && restoredThisTick<12 && checks<48) {
            // A single unloaded chunk must not block recovery in other loaded chunks.
            // Scan at most 48 candidates per tick and retain unloaded snapshots
            // in the pending prefix; never load chunks just to restore terrain.
            if(s.restoreScan<0 || s.restoreScan>s.restoreIndex) s.restoreScan=s.restoreIndex;
            int candidate=s.restoreScan;
            CompoundTag entry=s.snapshots.get(candidate);
            BlockPos pos=BlockPos.of(entry.getLong("pos"));
            checks++;
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
                    if(testWriteFailure || !level.setBlock(pos,original,2|16)) {
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
        if(s.restoreTicks%4==0) {
            BlockPos beacon=BlockPos.of(s.beaconPos);
            double angle=s.restoreTicks*.17;
            for(int k=0;k<8;k++) {
                double theta=angle+k*Math.PI*.25;
                double x=beacon.getX()+.5+Math.cos(theta)*3.4;
                double z=beacon.getZ()+.5+Math.sin(theta)*3.4;
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,x,beacon.getY()+.3+(s.restoreTicks%80)*.14,z,2,.12,.15,.12,.025);
            }
        }
        if(s.restoreTicks%10==0) {
            for(var player:level.players()) {
                BlockPos p=player.blockPosition();
                level.sendParticles(ParticleTypes.END_ROD,p.getX()+.5,p.getY()+1.5,p.getZ()+.5,12,3,2,3,.03);
            }
        }
        // Hold the final stabilization glow long enough to be visible in-game.
        if(s.restoreIndex<0 && s.restoreTicks>=100) {
            s.restoring=false;s.restored=true;s.finished=true;s.snapshots.clear();
            for(var player:level.players()) RiftNetwork.send(player,0f,false,0);
            if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_FINISHED restored={} destroyed={} skippedOccupied={} flowingReplaced={}",s.restoredBlocks,s.edits,s.skippedOccupied,s.ciFlowingReplaced);
            for(var player:level.players()) {
                player.displayClientMessage(Component.literal("§d✦ СТАБИЛИЗАЦИЯ 100% §7— восстановлено "+s.restoredBlocks+" блоков; пропущено "+s.skippedOccupied),true);
                player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(
                    s.skippedOccupied==0 ? "§dМИР ВОССТАНОВЛЕН" : "§dСТАБИЛИЗАЦИЯ ЗАВЕРШЕНА")));
                player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(
                    s.skippedOccupied==0 ? "§7Разлом закрывается" : "§7Изменённые блоки сохранены: "+s.skippedOccupied)));
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
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(20, 90, 35));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§8BLACK FREQUENCY")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7"+subtitle)));
                }
            }
            if(s.elapsed >= DAY*10) {
                s.started=true;s.setDirty();
                if(CI_TEST) LOGGER.info("BLACK_FREQUENCY_TEST FINALE_STARTED");
                // Anchor the fracture near a living player, not an abandoned world spawn.
                if (!level.players().isEmpty()) {
                    BlockPos anchor=level.players().get(0).blockPosition();
                    s.x=anchor.getX();s.z=anchor.getZ();
                }
                for(var player : level.players()) RiftNetwork.send(player,1f,true,0);
                for(var player : level.players()) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 120, 25));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§2ВЫСВОБОЖДЕНИЕ")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7Мир больше не цел.")));
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
                if (player.isCreative() || player.isSpectator()) continue;
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
                    player.setDeltaMovement(v.x+dx,Math.min(.43,v.y+lift),v.z+dz);
                    player.hurtMarked=true;
                    if(fade>.25) player.fallDistance=0;
                } else if (zone < -.68) {
                    var v=player.getDeltaMovement();
                    double drag=1.0-.06*fade;
                    player.setDeltaMovement(v.x*drag,Math.max(-.55,v.y-.09*fade),v.z*drag);
                    player.hurtMarked=true;
                }
            }
        }
        // The fracture can remain open for days; stop terrain edits once complete,
        // but only after the bounded gravity anomalies above have been processed.
        if(s.finished) return;
        // Allow the opening, green silence and sudden return to play before terrain removal.
        if (s.finaleTicks < 190) { if(s.finaleTicks%20==0)s.setDirty(); return; }
        if(CI_TEST && s.finaleTicks == 190) LOGGER.info("BLACK_FREQUENCY_TEST FRACTURE_PHASE_STARTED");
        int edits=0, checks=0;
        while(edits<EDIT_BUDGET && checks<2048 && !s.finished) {
            if(s.edits>=MAX_EDITS || s.cursorX>RADIUS) {s.finished=true;break;}
            int wx=s.x+s.cursorX,wz=s.z+s.cursorZ;
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
            BlockState original=level.getBlockState(pos);
            if(original.isAir() || original.hasBlockEntity()) continue;
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16)){
                CompoundTag snapshot=new CompoundTag();
                snapshot.putLong("pos",pos.asLong());
                snapshot.put("state",NbtUtils.writeBlockState(original));
                s.snapshots.add(snapshot);
                edits++;s.edits++;
                if(CI_TEST && s.edits==1) LOGGER.info("BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x={} y={} z={}", wx,y,wz);
            }
        }
        // Production recovery path: spawn a physical beacon in an existing loaded
        // chunk near a player. Never replace terrain or force-generate chunks.
        if(s.edits>=160 && s.naturalBeaconPos==Long.MIN_VALUE
            && (s.edits==160 || s.finaleTicks%40==0))
            tryPlaceRecoveryBeacon(level,s);
        if(s.naturalBeaconPos!=Long.MIN_VALUE && s.finaleTicks%40==0) {
            BlockPos beacon=BlockPos.of(s.naturalBeaconPos);
            if(level.hasChunk(beacon.getX()>>4,beacon.getZ()>>4)
                && level.getBlockState(beacon).is(Blocks.BEACON)) {
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,beacon.getX()+.5,beacon.getY()+1.2,beacon.getZ()+.5,6,.28,.45,.28,.015);
                if(s.finaleTicks%200==0) for(var player:level.players())
                    if(player.blockPosition().distSqr(beacon)<128d*128d)
                        player.displayClientMessage(Component.literal(
                            "§d✦ Маяк стабилизации: §f"+beacon.getX()+" "+beacon.getY()+" "+beacon.getZ()+" §7— нажмите ПКМ"),true);
            }
        }
        // CI-only rollback exercise; normal gameplay still needs a real beacon.
        if(CI_TEST && Boolean.getBoolean("blackfrequency.ciExerciseRestoration")
            && !s.restoring && s.beaconPos==0L && s.edits>=160 && !s.snapshots.isEmpty()
            && (!Boolean.getBoolean("blackfrequency.ciNaturalBeaconTest")
                || s.naturalBeaconPos!=Long.MIN_VALUE)) {
            boolean playerInteractionTest=Boolean.getBoolean("blackfrequency.ciPlayerBeaconTest");
            if(!playerInteractionTest) {
                s.restoring=true;
                s.restoreIndex=s.snapshots.size()-1;
                s.restoreScan=s.restoreIndex;
                s.restoreTicks=0;
                s.restoredBlocks=0;
                s.skippedOccupied=0;
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
            if(Boolean.getBoolean("blackfrequency.ciDeferredChunkTest")) {
                // Disposable CI world only: replace the last snapshot location
                // with a definitely unloaded chunk to exercise deferred recovery.
                BlockPos unloaded=new BlockPos(s.x+4096,level.getSeaLevel(),s.z+4096);
                s.snapshots.get(s.restoreIndex).putLong("pos",unloaded.asLong());
                LOGGER.info("BLACK_FREQUENCY_TEST DEFERRED_CHUNK_INJECTED x={} z={}",unloaded.getX(),unloaded.getZ());
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
                    for(int dz=-4;dz<=-2;dz++) for(int dx=-1;dx<=1;dx++) {
                        BlockPos floor=demoPos.offset(dx,-1,dz);
                        if(!level.hasChunk(floor.getX()>>4,floor.getZ()>>4)) continue;
                        level.setBlock(floor,Blocks.STONE.defaultBlockState(),3);
                        for(int dy=1;dy<=3;dy++)
                            level.setBlock(floor.above(dy),Blocks.AIR.defaultBlockState(),3);
                    }
                    demoPlayer.teleportTo(level,demoPos.getX()+.5,demoPos.getY(),
                        demoPos.getZ()-2.5,0f,20f);
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
                demoPlayer.connection.send(new ClientboundSetTitlesAnimationPacket(8,75,20));
                demoPlayer.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§5СТАБИЛИЗАЦИЯ")));
                demoPlayer.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§dМаяк запечатывает разлом")));
            }
            s.beaconPos=demoPos.asLong();
            if(playerInteractionTest)
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_READY_FOR_PLAYER x={} y={} z={}",demoPos.getX(),demoPos.getY(),demoPos.getZ());
            else
                LOGGER.info("BLACK_FREQUENCY_TEST BEACON_RESTORATION_STARTED");
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
