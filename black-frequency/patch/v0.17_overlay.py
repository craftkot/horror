#!/usr/bin/env python3
"""v0.17: watcher encounters in darkness and at zero sanity/morality.
Apply AFTER v0.16, preserve existing watcher / all previous scenes.
"""
from pathlib import Path
ROOT = Path("black-frequency/project")
JAVA = ROOT / "src/main/java/com/blackfrequency/hollowsignal"

def edit(path, old, new):
    s=path.read_text(encoding="utf-8")
    n=s.count(old)
    assert n==1,(str(path), old[:100], n)
    path.write_text(s.replace(old,new,1),encoding="utf-8")

def fresh(path, source):
    p=JAVA/path
    p.parent.mkdir(parents=True,exist_ok=True)
    assert not p.exists(), str(p)
    p.write_text(source.strip()+"\n",encoding="utf-8")

# The trapped host first gets green feedback, then the entity tears it away.
# Both the message and the fracture render on the client, not in real chat.
screen=JAVA/"client/ClientRuptureQte.java"
edit(screen,'"ТРЕЩИНЫ НЕ ОСТАНОВИТЬ"','"УЖЕ ПОЗДНО"')

fresh("world/EntityEncounters.java", r'''
package com.blackfrequency.hollowsignal.world;

import com.blackfrequency.hollowsignal.HorrorConfig;
import com.blackfrequency.hollowsignal.entity.WatcherEntity;
import com.blackfrequency.hollowsignal.registry.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Real Watcher when both stats reach zero, private client-only apparition
 * otherwise. No client command can spawn anything: server owns all schedules.
 *
 * Sleep encounter deliberately not implemented; dream rules are not decided.
 */
public final class EntityEncounters {
    private static final String MARKER = "bf_release_taunter";
    private static final String[] TAUNTS = {
        "Сдайся", "Ты ведь устал", "Не сопротивляйся",
        "Ты не сможешь", "Уже поздно", "Просто сдайся"
    };

    private final Map<UUID, Encounter> active = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private static final class Encounter {
        final WatcherEntity watcher;
        final boolean physical;
        final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        final long started;
        long lastTaunt;
        int tauntNumber;
        Encounter(WatcherEntity watcher, boolean physical, ServerLevel level, long now) {
            this.watcher = watcher;
            this.physical = physical;
            this.dimension = level.dimension();
            this.started = now;
            this.lastTaunt = now;
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        if (!HorrorConfig.ENABLED.get() || !HorrorConfig.PSYCHOLOGY_SYSTEM.get()
                || !HorrorConfig.WATCHERS.get() || !player.isAlive()
                || player.isSpectator() || player.isCreative()) {
            clear(player);
            return;
        }
        ServerLevel level=player.serverLevel();
        long now=level.getGameTime();
        if (now % 10 != 0) return;

        UUID id=player.getUUID();
        PsychologyData data=PsychologyData.get(player.getServer());
        PsychologyData.Mind mind=data.mind(player);
        boolean zero=mind.sanity==0 && mind.morality==0;
        boolean cinematic=data.ruptureActive() && data.ruptureStage==0;
        if (cinematic || player.isSleeping()) {
            clear(player);
            return;
        }

        Encounter old=active.get(id);
        if (old!=null) {
            if (old.dimension!=level.dimension() || !player.isAlive()
                    || now-old.started>20L*(old.physical?38:85)
                    || (!old.physical && isWatched(player,old.watcher))
                    || (old.physical && !old.watcher.isAlive())) {
                clear(player);
            } else {
                if (old.physical) {
                    if (now-old.lastTaunt >= 20*5) taunt(player,old,now);
                }
                return;
            }
        }
        // Each player has a private encounter cooldown and never accumulates a horde.
        if (now<cooldowns.getOrDefault(id,0L)) return;
        cooldowns.put(id,now+20L*(zero?17:mind.sanity<35?28:50));
        if (!zero) {
            // Darkness may produce a private apparition even before the global rupture.
            if (mind.sanity>80 || level.getMaxLocalRawBrightness(player.blockPosition())>7) return;
        }
        WatcherEntity watcher=makeWatcher(player);
        if (watcher==null) return;
        watcher.setNoAi(true);
        watcher.setSilent(true);
        watcher.setInvulnerable(true);
        if (zero) {
            // With BOTH values at zero the apparition becomes physically present,
            // and all other players in that dimension may see its taunting name.
            watcher.setCustomName(Component.literal("Сдайся").withStyle(ChatFormatting.DARK_RED));
            watcher.setCustomNameVisible(true);
            if (!level.addFreshEntity(watcher)) return;
            watcher.addTag(MARKER);
        } else {
            // Do NOT add to ServerLevel. Only the targeted client's connection
            // receives Forge entity-spawn and later remove packets.
            player.connection.send(NetworkHooks.getEntitySpawningPacket(watcher));
        }
        active.put(id,new Encounter(watcher,zero,level,now));
        player.playNotifySound(SoundEvents.WARDEN_HEARTBEAT,
                SoundSource.AMBIENT,.55f,.7f);
    }

    private WatcherEntity makeWatcher(ServerPlayer player) {
        ServerLevel level=player.serverLevel();
        var rng=player.getRandom();
        Vec3 view=player.getLookAngle();
        for (int i=0;i<18;i++) {
            // Mostly behind the player; peripheral appearances on later attempts.
            double angle=Math.atan2(view.z,view.x)+Math.PI+(rng.nextDouble()-.5)*1.6;
            double distance=7+rng.nextInt(7);
            int x=net.minecraft.util.Mth.floor(player.getX()+Math.cos(angle)*distance);
            int z=net.minecraft.util.Mth.floor(player.getZ()+Math.sin(angle)*distance);
            BlockPos pos=new BlockPos(x,player.blockPosition().getY(),z);
            if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) continue;
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()
                    || !level.getBlockState(pos.below()).isSolidRender(level,pos.below())) continue;
            if (level.getMaxLocalRawBrightness(pos)>9 && level.getMaxLocalRawBrightness(player.blockPosition())<=7) continue;
            WatcherEntity watcher=ModEntities.WATCHER.get().create(level);
            if (watcher==null) return null;
            watcher.moveTo(x+.5,pos.getY(),z+.5,rng.nextFloat()*360,0);
            if (level.noCollision(watcher)) return watcher;
        }
        return null;
    }

    private boolean isWatched(ServerPlayer p, WatcherEntity watcher) {
        Vec3 to=watcher.getEyePosition().subtract(p.getEyePosition());
        if (to.lengthSqr()>48*48 || to.lengthSqr()<.01) return false;
        // A sufficiently close look at the silhouette ends the private apparition.
        return p.getLookAngle().normalize().dot(to.normalize())>.964
                && p.hasLineOfSight(watcher);
    }

    private void taunt(ServerPlayer player, Encounter encounter, long now) {
        encounter.lastTaunt=now;
        String taunt=TAUNTS[encounter.tauntNumber++%TAUNTS.length];
        encounter.watcher.setCustomName(Component.literal(taunt)
                .withStyle(ChatFormatting.DARK_RED));
        encounter.watcher.setCustomNameVisible(true);
        // Target receives a quiet localized whisper instead of chat spam.
        player.displayClientMessage(Component.literal("§8[Шёпот] §4"+taunt),true);
        player.playNotifySound(SoundEvents.WARDEN_HEARTBEAT,SoundSource.AMBIENT,.24f,.7f);
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) { clear(p); cooldowns.remove(p.getUUID()); }
    }
    @SubscribeEvent
    public void changeDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) clear(p);
    }
    @SubscribeEvent
    public void respawn(PlayerEvent.PlayerRespawnEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) clear(p);
    }

    @SubscribeEvent
    public void orphanCleanup(EntityJoinLevelEvent e) {
        // A real taunter must never remain indefinitely after a restart.
        // Newly spawned entities are tagged only AFTER their join event.
        if (e.getEntity().getTags().contains(MARKER)) e.setCanceled(true);
    }

    private void clear(ServerPlayer player) {
        Encounter e=active.remove(player.getUUID());
        if (e==null) return;
        if (e.physical) e.watcher.discard();
        else player.connection.send(new ClientboundRemoveEntitiesPacket(e.watcher.getId()));
    }
}
''')

mod=JAVA/"BlackFrequency.java"
edit(mod,
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RuptureCinematicSystem());\n',
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.RuptureCinematicSystem());\n'
     '        MinecraftForge.EVENT_BUS.register(new com.blackfrequency.hollowsignal.world.EntityEncounters());\n')
props=ROOT/"gradle.properties"
edit(props,"0.16.0","0.17.0")
print("PASS v0.17: red fracture says УЖЕ ПОЗДНО; private darkness watchers + taunting zero-state real encounters")
