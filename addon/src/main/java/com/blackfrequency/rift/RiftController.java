package com.blackfrequency.rift;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.nbt.CompoundTag;
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
        boolean started, finished, initialized;
        static State load(CompoundTag n) {
            State s = new State();
            s.elapsed=n.getLong("elapsed");
            s.x=n.getInt("x");s.z=n.getInt("z");
            s.cursorX=n.getInt("cx");s.cursorZ=n.getInt("cz");s.cursorY=n.getInt("cy");
            s.edits=n.getInt("edits");s.finaleTicks=n.getInt("finaleTicks");
            s.started=n.getBoolean("started");s.finished=n.getBoolean("finished");
            s.initialized=n.getBoolean("initialized");
            return s;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putLong("elapsed",elapsed);n.putInt("x",x);n.putInt("z",z);
            n.putInt("cx",cursorX);n.putInt("cz",cursorZ);n.putInt("cy",cursorY);
            n.putInt("edits",edits);n.putInt("finaleTicks",finaleTicks);n.putBoolean("started",started);
            n.putBoolean("finished",finished);n.putBoolean("initialized",initialized);
            return n;
        }
    }
    @SubscribeEvent
    public void onTick(TickEvent.LevelTickEvent event) {
        if(event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
           || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        State s = level.getDataStorage().computeIfAbsent(State::load, State::new, "blackfrequency_finale");
        // Keep clients synchronized even on old worlds or after the finale.
        if(level.getGameTime()%(s.started && !s.finished ? 10L : 100L)==0) {
            float progress=s.finished ? 1f : Math.max(0f, Math.min(1f,(s.elapsed-3f*DAY)/(7f*DAY)));
            for(var player : level.players()) RiftNetwork.send(player,progress,s.started || s.finished, s.finaleTicks);
        }
        if(s.finished) return;
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
            if(level.getBlockState(pos).isAir()) continue;
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16)){
                edits++;s.edits++;
                if(CI_TEST && s.edits==1) LOGGER.info("BLACK_FREQUENCY_TEST FIRST_BLOCK_REMOVED x={} y={} z={}", wx,y,wz);
            }
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
