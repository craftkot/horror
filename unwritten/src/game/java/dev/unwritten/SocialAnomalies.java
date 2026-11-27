package dev.unwritten;
import java.util.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/** Only the intended viewer receives these entries. Never creates a real server player. */
final class SocialAnomalies {
 private record Ghost(ServerPlayer viewer,UUID id,String name,ServerPlayer skinSource,int age){}
 private final Map<UUID,Ghost> active=new HashMap<>();
 private final Random rng=new Random();
 boolean busy(ServerPlayer viewer){return active.containsKey(viewer.getUUID());}
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
  var candidates=online.stream().filter(p->SocialPolicy.canHide(p==viewer,p.level()==viewer.level(),p.distanceToSqr(viewer))&&!p.isSpectator()).toList();
  if(candidates.isEmpty())return;
  var target=candidates.get(rng.nextInt(candidates.size()));
  net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(viewer,new TabPayload(target.getUUID()));
 }
 void tick(List<ServerPlayer> online,MimicEncounter mimics,ChaseEncounter chases,Apparitions apparitions){
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
 private void remove(Ghost g,boolean announce){
  g.viewer().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(g.id())));
  if(announce)g.viewer().sendSystemMessage(Component.translatable("multiplayer.player.left",Component.literal(g.name())).withStyle(ChatFormatting.YELLOW));
 }
 void restoreAll(){active.values().forEach(g->remove(g,false));active.clear();}
 void clear(){active.clear();}
}
