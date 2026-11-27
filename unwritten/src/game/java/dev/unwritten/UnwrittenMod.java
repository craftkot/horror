package dev.unwritten;

import java.util.*;
import java.nio.file.Path;
import java.io.IOException;
import com.mojang.logging.LogUtils;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
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
    private final LocalAnomalies anomalies = new LocalAnomalies();
    private final OreEncounter ores = new OreEncounter();
    private final ChaseEncounter chases = new ChaseEncounter();
    private final MimicEncounter mimics = new MimicEncounter();
    private final NarrativeEvents narrative = new NarrativeEvents();
    private static final TagKey<Block> ORES=TagKey.create(Registries.BLOCK,Identifier.fromNamespaceAndPath("c","ores"));
    public UnwrittenMod() {
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::stop);
        NeoForge.EVENT_BUS.addListener(this::stopping);
        NeoForge.EVENT_BUS.addListener(this::tame);
        NeoForge.EVENT_BUS.addListener(this::bonemeal);
        NeoForge.EVENT_BUS.addListener(this::sleep);
        NeoForge.EVENT_BUS.addListener(this::trample);
        NeoForge.EVENT_BUS.addListener(this::explosion);
        NeoForge.EVENT_BUS.addListener(chases::onJoin);
        NeoForge.EVENT_BUS.addListener(chases::onTarget);
        NeoForge.EVENT_BUS.addListener(chases::onDrops);
        NeoForge.EVENT_BUS.addListener(mimics::onJoin);
        NeoForge.EVENT_BUS.addListener(mimics::onDrops);
        NeoForge.EVENT_BUS.addListener(mimics::onDamage);
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST,this::oreBreak);
    }
    private void stop(ServerStoppedEvent event) { sessions.clear(); apparitions.clear(); anomalies.clear(); ores.clear(); chases.clear(); mimics.clear(); narrative.clear(); ticks=0; stateDirectory=null; }
    private void stopping(ServerStoppingEvent event) { sessions.forEach(this::save); anomalies.restoreAll(); ores.restoreAll(); mimics.clear(); chases.clear(); }
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
    private boolean eligible(ServerPlayer p){return stateDirectory!=null&&!p.isCreative()&&!p.isSpectator();}
    private void tame(AnimalTameEvent event){
        if(!event.isCanceled()&&event.getTamer() instanceof ServerPlayer p&&eligible(p))session(p).director().tame();
    }
    private void bonemeal(BonemealEvent event){
        if(!event.isCanceled()&&event.isValidBonemealTarget()&&event.getPlayer() instanceof ServerPlayer p&&eligible(p))session(p).director().bonemeal();
    }
    private void sleep(CanPlayerSleepEvent event){
        var p=event.getEntity();
        if(!eligible(p)||event.getProblem()!=null)return;
        var s=session(p);var pos=event.getPos();
        s.history().rememberBed(new PlayerHistory.Bed(p.level().dimension().identifier().toString(),pos.getX(),pos.getY(),pos.getZ()));
        switch(s.director().sleep(apparitions.hasWatcher(p)||chases.hasChase(p)||mimics.active(p))){
            case GUILT -> {event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);p.sendSystemMessage(Component.translatable("event.unwritten.sleep.guilt"));}
            case WATCHED -> {event.setProblem(Player.BedSleepingProblem.OTHER_PROBLEM);p.sendSystemMessage(Component.translatable("event.unwritten.sleep.watched"));}
            case CALL -> {p.sendSystemMessage(Component.translatable("event.unwritten.sleep.call"));narrative.help(p);}
            case ALLOW -> {}
        }
    }
    private void trample(BlockEvent.FarmlandTrampleEvent event){
        if(!event.isCanceled()&&event.getEntity() instanceof ServerPlayer p&&eligible(p))session(p).director().trample();
    }
    private void explosion(ExplosionEvent.Detonate event){
        if(event.getExplosion().getIndirectSourceEntity() instanceof ServerPlayer p&&eligible(p))session(p).director().explosion();
    }
    private void oreBreak(BreakBlockEvent event){
        if(event.isCanceled()||!(event.getPlayer() instanceof ServerPlayer p)||!eligible(p)||!p.isAlive())return;
        if(!event.getState().is(ORES)||chases.hasChase(p)||mimics.active(p))return;
        if(OrePolicy.triggers(session(p).director().nullPresent(),true,p.getRandom().nextDouble()))
            ores.queue(p,event.getPos(),event.getState());
    }
    private void tick(ServerTickEvent.Post event) {
        ++ticks;
        if(stateDirectory==null)stateDirectory=event.getServer().getWorldPath(LevelResource.ROOT).resolve("unwritten");
        chases.tick(event.getServer().getPlayerList().getPlayers());
        mimics.tick(event.getServer().getPlayerList().getPlayers(),chases);
        narrative.tick(event.getServer().getPlayerList().getPlayers(),apparitions,chases,mimics);
        apparitions.tick(event.getServer().getPlayerList().getPlayers(),chases);
        anomalies.tick(event.getServer().getPlayerList().getPlayers());
        ores.tick(event.getServer().getPlayerList().getPlayers(),apparitions);
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
            session.director().tick(obs,!chases.hasChase(p)&&!mimics.active(p)&&!narrative.busy(p)).ifPresent(decision -> play(p, decision));
        }
        var it=sessions.entrySet().iterator();
        while(it.hasNext()){
            var entry=it.next();
            if(!online.contains(entry.getKey())){save(entry.getKey(),entry.getValue());it.remove();}
            else if(ticks%1200==0)save(entry.getKey(),entry.getValue());
        }
    }
    private void play(ServerPlayer p, HorrorDirector.Decision decision) {
        switch(decision.event()) {
            case FOOTSTEPS -> Apparitions.soundBehind(p, SoundEvents.GRASS_STEP, 0.65f, 0.85f);
            case ECHO -> Apparitions.soundBehind(p, SoundEvents.AMBIENT_CAVE.value(), 0.45f, 0.75f);
            case DISTANT_SILHOUETTE -> apparitions.show(p,false);
            case FALSE_PRESENCE -> {
                var director=session(p).director();
                if(director.mineReady()&&!apparitions.hasWatcher(p)&&MineEncounter.nearMine(p)&&MinePolicy.roll(p.getRandom().nextDouble())){
                    if(apparitions.show(p,true,true)){director.mineStarted();break;}
                }
                Apparitions.soundBehind(p,SoundEvents.GRASS_STEP,0.9f,0.95f); apparitions.show(p,true);
            }
            case LOCAL_ANOMALY -> { if(!ReferenceEvents.markBase(p,session(p).history()))anomalies.show(p); }
            case BREATH -> Apparitions.soundBehind(p,SoundEvents.PLAYER_BREATH,1.0f,0.5f);
            case DOOR -> ReferenceEvents.toggleDoor(p);
            case TEXT -> p.sendSystemMessage(Component.translatable("event.unwritten.presence"));
            case NULL_TITLE -> NarrativeEvents.title(p);
            case COORDS -> narrative.coords(p);
            case MIMIC -> { if(!apparitions.hasWatcher(p))mimics.start(p); }
            case NULL_NEAR -> {
                var effect=BuiltInRegistries.MOB_EFFECT.getOrThrow(ResourceKey.create(Registries.MOB_EFFECT,Identifier.withDefaultNamespace("blindness")));
                if(!p.hasEffect(effect))p.addEffect(new MobEffectInstance(effect,120,0,false,false));
                Apparitions.soundBehind(p,SoundEvents.AMBIENT_CAVE.value(),0.8f,0.6f);
            }
            case NULL_WHISPER -> {
                int variant=p.getRandom().nextInt(3);
                p.sendSystemMessage(Component.translatable("commands.message.display.incoming",
                    Component.literal("Null"),Component.translatable("event.unwritten.null."+variant)).withStyle(ChatFormatting.GRAY,ChatFormatting.ITALIC));
            }
        }
    }
}
