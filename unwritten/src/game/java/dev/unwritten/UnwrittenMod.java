package dev.unwritten;

import java.util.*;
import java.nio.file.Path;
import java.io.IOException;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod("unwritten")
public final class UnwrittenMod {
    private Path stateDirectory;
    private final Map<UUID, PlayerStateStore.State> sessions = new HashMap<>();
    private int ticks;
    private final Apparitions apparitions = new Apparitions();
    public UnwrittenMod() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        NeoForge.EVENT_BUS.addListener(this::death);
    }
    private void stop(ServerStoppedEvent event) { sessions.clear(); apparitions.clear(); ticks=0; stateDirectory=null; }
    private void stopping(ServerStoppingEvent event) { sessions.forEach(this::save); }
    private void save(UUID id, PlayerStateStore.State state) {
        if(stateDirectory==null)return;
        try { PlayerStateStore.save(stateDirectory,id,state); }
        catch(IOException ex){LogUtils.getLogger().warn("Unwritten: could not save state for {}",id,ex);}
    }
    private PlayerStateStore.State session(ServerPlayer p) {
        return sessions.computeIfAbsent(p.getUUID(),id->{
            long seed=id.getMostSignificantBits() ^ System.nanoTime();
            try{return PlayerStateStore.load(stateDirectory,id,seed);}
            catch(IOException ex){LogUtils.getLogger().warn("Unwritten: could not read state for {}",id,ex);return PlayerStateStore.fresh(seed);}
        });
    }
    private void death(LivingDeathEvent event) {
        if(stateDirectory==null)return;
        if(event.getSource().getEntity() instanceof ServerPlayer p && !p.isCreative() && !p.isSpectator()){
            var category=event.getEntity().getType().getCategory().getName();
            if(category.equals("monster"))session(p).director().adjustKarma(1);
            else if(category.equals("creature")||category.equals("water_creature"))session(p).director().adjustKarma(-3);
        }
    }
    private void tick(ServerTickEvent.Post event) {
        ++ticks;
        if(stateDirectory==null)stateDirectory=event.getServer().getWorldPath(LevelResource.ROOT).resolve("unwritten");
        apparitions.tick(event.getServer().getPlayerList().getPlayers());
        if (ticks % 20 != 0) return;
        var players = event.getServer().getPlayerList().getPlayers();
        var online = new HashSet<UUID>();
        for (ServerPlayer p : players) {
            online.add(p.getUUID());
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            var session = session(p);
            var pos = p.blockPosition();
            var level = p.level();
            session.history().observe(PlayerHistory.Area.at(level.dimension().identifier().toString(),pos.getX(),pos.getZ()));
            boolean dark = level.getMaxLocalRawBrightness(pos) < 7;
            boolean underground = pos.getY() < 50 && !level.canSeeSky(pos);
            boolean alone = players.stream().noneMatch(other -> other != p && other.level() == level && other.distanceToSqr(p) < 1024);
            boolean safe = !dark && session.history().dwell() > 20;
            var obs = new HorrorDirector.Observation(dark,alone,underground,safe,0);
            session.director().tick(obs).ifPresent(decision -> play(p, decision));
        }
        var it=sessions.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next();
            if(!online.contains(entry.getKey())){save(entry.getKey(),entry.getValue());it.remove();}
            else if(ticks%1200==0)save(entry.getKey(),entry.getValue());
        }
    }
    private void play(ServerPlayer p, HorrorDirector.Decision decision) {
        // Local anomaly is still reserved for a later implementation.
        switch(decision.event()) {
            case FOOTSTEPS -> Apparitions.soundBehind(p, SoundEvents.GRASS_STEP, 0.65f, 0.85f);
            case ECHO -> Apparitions.soundBehind(p, SoundEvents.AMBIENT_CAVE.value(), 0.45f, 0.75f);
            case DISTANT_SILHOUETTE -> apparitions.show(p);
            case FALSE_PRESENCE -> { Apparitions.soundBehind(p,SoundEvents.GRASS_STEP,0.9f,0.95f); apparitions.show(p); }
            default -> { }
        }
    }
}
