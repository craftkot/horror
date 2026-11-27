package dev.unwritten;
import java.util.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.PacketDistributor;

/** Only the intended viewer receives these entries. Never creates a real server player. */
final class SocialAnomalies {
 private record Ghost(ServerPlayer viewer,UUID id,String name,ServerPlayer skinSource,int age){}
 private final Map<UUID,Ghost> active=new HashMap<>();
 private record Hidden(ServerPlayer viewer,ServerPlayer target,Object world,UUID copyId,int age,boolean started){}
 private final Map<UUID,Hidden> hidden=new HashMap<>();
 private final Random rng=new Random();
 boolean busy(ServerPlayer viewer){return active.containsKey(viewer.getUUID())||hidden.containsKey(viewer.getUUID());}
 void join(ServerPlayer viewer,List<ServerPlayer> online){
  if(busy(viewer))return;
  var names=online.stream().map(p->p.getGameProfile().name()).toList();
  SocialPolicy.choose(names,rng).ifPresent(name->{
   UUID id=UUID.randomUUID();
   var source=MimicEncounter.chooseSource(viewer,online,rng);
   MimicEncounter.sendProfile(viewer,id,name,source,true);
   viewer.sendSystemMessage(Component.translatable("multiplayer.player.joined",Component.literal(name)).withStyle(ChatFormatting.YELLOW));
   active.put(viewer.getUUID(),new Ghost(viewer,id,name,source,0));
  });
 }
 void hideReal(ServerPlayer viewer,List<ServerPlayer> online){
  if(busy(viewer))return;
  var candidates=online.stream().filter(p->SocialPolicy.canHide(p==viewer,p.level()==viewer.level(),p.distanceToSqr(viewer))&&p.isAlive()&&!p.isSpectator()).toList();
  if(candidates.isEmpty())return;
  var target=candidates.get(rng.nextInt(candidates.size()));
  hidden.put(viewer.getUUID(),new Hidden(viewer,target,viewer.level(),UUID.randomUUID(),0,false));
  PacketDistributor.sendToPlayer(viewer,new TabPayload(target.getUUID(),true,false));
 }
 void tick(List<ServerPlayer> online,MimicEncounter mimics,ChaseEncounter chases,Apparitions apparitions){
  tickHidden(online,mimics,chases,apparitions);
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var e=it.next();var ghost=e.getValue();var p=ghost.viewer();
   if(!online.contains(p)){it.remove();continue;}
   boolean conflict=online.stream().anyMatch(real->real.getGameProfile().name().equalsIgnoreCase(ghost.name()));
   if(!p.isAlive()||p.isCreative()||p.isSpectator()||conflict||SocialPolicy.expired(ghost.age()+1)){
    mimics.cancelIdentity(p,ghost.id());
    remove(ghost,!conflict&&p.isAlive()&&!p.isCreative()&&!p.isSpectator());it.remove();
   }else {
    if(SocialPolicy.shouldReveal(ghost.age())&&!mimics.active(p)&&!chases.hasChase(p)&&!apparitions.hasWatcher(p))mimics.start(p,ghost.id(),ghost.name(),ghost.skinSource());
    e.setValue(new Ghost(p,ghost.id(),ghost.name(),ghost.skinSource(),ghost.age()+1));
   }
  }
 }
 private void tickHidden(List<ServerPlayer> online,MimicEncounter mimics,ChaseEncounter chases,Apparitions apparitions){
  var it=hidden.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var h=entry.getValue();var p=h.viewer();var target=h.target();
   if(!online.contains(p)){it.remove();continue;}
   boolean connected=online.contains(target);
   boolean eligible=p.isAlive()&&!p.isCreative()&&!p.isSpectator()&&p.level()==h.world();
   boolean far=connected&&target.isAlive()&&!target.isSpectator()&&SocialPolicy.canHide(false,p.level()==target.level(),p.distanceToSqr(target));
   boolean stillCopy=mimics.activeIdentity(p,h.copyId());
   if(!eligible||!far||SocialPolicy.endHidden(h.age(),h.started(),stillCopy)){
    mimics.cancelIdentity(p,h.copyId());
    PacketDistributor.sendToPlayer(p,new TabPayload(target.getUUID(),false,connected&&eligible));
    it.remove();continue;
   }
   boolean started=h.started();
   if(!started&&SocialPolicy.copyOpportunity(h.age())&&!mimics.active(p)&&!chases.hasChase(p)&&!apparitions.hasWatcher(p)){
    started=mimics.startCopy(p,h.copyId(),target);
   }
   entry.setValue(new Hidden(p,target,h.world(),h.copyId(),h.age()+1,started));
  }
 }
 private void remove(Ghost g,boolean announce){
  g.viewer().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(g.id())));
  if(announce)g.viewer().sendSystemMessage(Component.translatable("multiplayer.player.left",Component.literal(g.name())).withStyle(ChatFormatting.YELLOW));
 }
 void restoreAll(){active.values().forEach(g->remove(g,false));active.clear();hidden.values().forEach(h->PacketDistributor.sendToPlayer(h.viewer(),new TabPayload(h.target().getUUID(),false,false)));hidden.clear();}
 void clear(){active.clear();hidden.clear();}
}
