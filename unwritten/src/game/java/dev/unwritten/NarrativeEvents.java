package dev.unwritten;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;

/** Targeted, bounded presentation; no OS windows, real disconnects or world edits. */
final class NarrativeEvents {
 private enum Kind { COORDS, HELP }
 private record Sequence(ServerPlayer player,Object world,Kind kind,int age){}
 private final Map<UUID,Sequence> active=new HashMap<>();
 boolean busy(ServerPlayer p){return active.containsKey(p.getUUID());}
 void coords(ServerPlayer p){active.putIfAbsent(p.getUUID(),new Sequence(p,p.level(),Kind.COORDS,0));}
 void help(ServerPlayer p){
  if(busy(p))return;
  active.put(p.getUUID(),new Sequence(p,p.level(),Kind.HELP,0));
  p.sendSystemMessage(Component.translatable("event.unwritten.help.request").withStyle(ChatFormatting.GRAY));
  Apparitions.soundBehind(p,SoundEvents.AMBIENT_CAVE.value(),.5f,.8f);
 }
 static void title(ServerPlayer p){
  p.connection.send(new ClientboundSetTitlesAnimationPacket(5,35,10));
  p.connection.send(new ClientboundSetTitleTextPacket(Component.translatable("event.unwritten.null.title").withStyle(ChatFormatting.DARK_RED)));
 }
 void tick(List<ServerPlayer> players,Apparitions apparitions,ChaseEncounter chases,MimicEncounter mimics){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var s=entry.getValue();var p=s.player();
   if(!players.contains(p)||p.level()!=s.world()||!p.isAlive()||p.isCreative()||p.isSpectator()){it.remove();continue;}
   int age=s.age();
   int duration=s.kind()==Kind.COORDS?NarrativePolicy.COORDS_TICKS:NarrativePolicy.HELP_TICKS;
   if(NarrativePolicy.expired(age,duration)){it.remove();continue;}
   if(s.kind()==Kind.COORDS&&NarrativePolicy.coordsFrame(age)){
    var pos=p.blockPosition();
    p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(Component.translatable("event.unwritten.coords",pos.getX(),pos.getY(),pos.getZ()).withStyle(ChatFormatting.GRAY)));
   }
   if(s.kind()==Kind.HELP&&NarrativePolicy.warning(age)){
    p.sendSystemMessage(Component.translatable("event.unwritten.help.warning").withStyle(ChatFormatting.GRAY));
    if(!chases.hasChase(p)&&!mimics.active(p))apparitions.show(p,true);
   }
   entry.setValue(new Sequence(p,s.world(),s.kind(),age+1));
  }
 }
 void clear(){active.clear();}
}
