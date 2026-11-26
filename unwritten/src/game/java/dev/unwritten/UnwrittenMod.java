package dev.unwritten;

import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod("unwritten")
public final class UnwrittenMod {
    private record Session(HorrorDirector director, PlayerHistory history) {}
    private final Map<UUID, Session> sessions = new HashMap<>();
    private int ticks;
    public UnwrittenMod() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
    }
    private void stop(ServerStoppedEvent event) { sessions.clear(); ticks=0; }
    private void tick(ServerTickEvent.Post event) {
        if (++ticks % 20 != 0) return;
        var players = event.getServer().getPlayerList().getPlayers();
        var online = new HashSet<UUID>();
        for (ServerPlayer p : players) {
            online.add(p.getUUID());
            if (!p.isAlive() || p.isSpectator() || p.isCreative()) continue;
            var session = sessions.computeIfAbsent(p.getUUID(), id ->
                new Session(new HorrorDirector(id.getMostSignificantBits() ^ System.nanoTime()), new PlayerHistory()));
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
        sessions.keySet().retainAll(online);
    }
    private void play(ServerPlayer p, HorrorDirector.Decision decision) {
        // Only implemented effects are audible; higher-level events are deferred until their handlers exist.
        switch(decision.event()) {
            case FOOTSTEPS -> p.playNotifySound(SoundEvents.GRASS_STEP, SoundSource.AMBIENT, 0.65f, 0.85f);
            case ECHO -> p.playNotifySound(SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.45f, 0.75f);
            default -> { }
        }
    }
}
