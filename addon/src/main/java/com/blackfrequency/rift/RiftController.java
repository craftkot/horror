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
    private static final long DAY = 24000L;
    private static final int RADIUS = 32;
    private static final int EDIT_BUDGET = 32;
    private static final int MAX_EDITS = 18000;

    private static final class State extends SavedData {
        long elapsed;
        int x, z, cursorX=-RADIUS, cursorZ=-RADIUS, cursorY;
        int edits;
        boolean started, finished, initialized;
        static State load(CompoundTag n) {
            State s = new State();
            s.elapsed=n.getLong("elapsed");
            s.x=n.getInt("x");s.z=n.getInt("z");
            s.cursorX=n.getInt("cx");s.cursorZ=n.getInt("cz");s.cursorY=n.getInt("cy");
            s.edits=n.getInt("edits");
            s.started=n.getBoolean("started");s.finished=n.getBoolean("finished");
            s.initialized=n.getBoolean("initialized");
            return s;
        }
        @Override public CompoundTag save(CompoundTag n) {
            n.putLong("elapsed",elapsed);n.putInt("x",x);n.putInt("z",z);
            n.putInt("cx",cursorX);n.putInt("cz",cursorZ);n.putInt("cy",cursorY);
            n.putInt("edits",edits);n.putBoolean("started",started);
            n.putBoolean("finished",finished);n.putBoolean("initialized",initialized);
            return n;
        }
    }
    @SubscribeEvent
    public void onTick(TickEvent.LevelTickEvent event) {
        if(event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)
           || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return;
        State s = level.getDataStorage().computeIfAbsent(State::load, State::new, "blackfrequency_finale");
        if(s.finished) return;
        if(!s.initialized) {
            s.initialized=true;
            BlockPos spawn=level.getSharedSpawnPos();s.x=spawn.getX();s.z=spawn.getZ();
            s.cursorY=level.getMinBuildHeight();s.setDirty();
        }
        if(!s.started) {
            s.elapsed++;
            // Narrative beats are delivered as on-screen titles, not chat messages.
            // No manual commands are needed.
            if(s.elapsed == DAY*3 || s.elapsed == DAY*6 || s.elapsed == DAY*9) {
                String subtitle = s.elapsed == DAY*3
                    ? "Ты уверен, что небо всегда было таким?"
                    : s.elapsed == DAY*6
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
                for(var player : level.players()) {
                    player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 120, 25));
                    player.connection.send(new ClientboundSetTitleTextPacket(Component.literal("§2ВЫСВОБОЖДЕНИЕ")));
                    player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§7Мир больше не цел.")));
                }
            }
            if(s.elapsed%200==0)s.setDirty();
            return;
        }
        int edits=0, checks=0;
        while(edits<EDIT_BUDGET && checks<2048 && !s.finished) {
            if(s.edits>=MAX_EDITS || s.cursorX>RADIUS) {s.finished=true;break;}
            int wx=s.x+s.cursorX,wz=s.z+s.cursorZ;
            if(!level.hasChunk(wx>>4,wz>>4)) break;
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
            double crack=Math.abs(Math.sin(u*2.1+Math.sin(v*1.7))*Math.cos(v*1.8-Math.sin(u*1.9)));
            if(crack>0.21 || dx*dx+dz*dz>RADIUS*RADIUS) continue;
            BlockPos pos=new BlockPos(wx,y,wz);
            if(level.getBlockState(pos).isAir()) continue;
            if(level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16)){edits++;s.edits++;}
        }
        // Persist the cursor every tick; restart must not rewind destructive edits.
        s.setDirty();
    }
}
