package dev.unwritten;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.ChatFormatting;

/** Targeted narrative, with optional air-only Help structure placement. */
final class NarrativeEvents {
 private enum Kind { COORDS, HELP, RITUAL }
 private record Sequence(ServerPlayer player,Object world,Kind kind,int age,HorrorDirector director,BlockPos corridor){}
 private final Map<UUID,Sequence> active=new HashMap<>();
 boolean busy(ServerPlayer p){return active.containsKey(p.getUUID());}
 void coords(ServerPlayer p){active.putIfAbsent(p.getUUID(),new Sequence(p,p.level(),Kind.COORDS,0,null,null));}
 void help(ServerPlayer p,HorrorDirector director){
  if(busy(p))return;
  active.put(p.getUUID(),new Sequence(p,p.level(),Kind.HELP,0,director,null));
  p.sendSystemMessage(Component.translatable("event.unwritten.help.request").withStyle(ChatFormatting.GRAY));
  Apparitions.soundBehind(p,SoundEvents.AMBIENT_CAVE.value(),.5f,.8f);
 }
 void discoverRitual(ServerPlayer p,HorrorDirector director){
  if(busy(p)||director.ritualHeard()||!p.level().dimension().identifier().toString().equals("minecraft:overworld"))return;
  var site=director.ritualSite().orElse(null);if(site==null)return;
  var anchor=new BlockPos(site.x(),site.y(),site.z());
  if(!p.level().hasChunkAt(anchor.getX(),anchor.getZ())||!SacrificeLayout.within(p.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5)))return;
  if(!p.level().getBlockState(anchor).is(net.minecraft.world.level.block.Blocks.RED_STAINED_GLASS))return;
  active.put(p.getUUID(),new Sequence(p,p.level(),Kind.RITUAL,0,director,anchor));
  Apparitions.soundBehind(p,SoundEvents.AMBIENT_CAVE.value(),.8f,.55f);
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
   int duration=switch(s.kind()){case COORDS->NarrativePolicy.COORDS_TICKS;case HELP->NarrativePolicy.HELP_TICKS;case RITUAL->SacrificeLayout.DIALOG_TICKS;};
   if(s.kind()==Kind.RITUAL){
    var anchor=s.corridor();
    if(!p.level().hasChunkAt(anchor.getX(),anchor.getZ())||!SacrificeLayout.within(p.distanceToSqr(anchor.getX()+.5,anchor.getY()+.5,anchor.getZ()+.5))||!p.level().getBlockState(anchor).is(net.minecraft.world.level.block.Blocks.RED_STAINED_GLASS)){it.remove();continue;}
    if(age>=duration){s.director().markRitualHeard();it.remove();continue;}
    int line=SacrificeLayout.dialogLine(age);
    if(line>=0)p.sendSystemMessage(Component.translatable("event.unwritten.ritual.line."+line).withStyle(ChatFormatting.GRAY));
   }
   if(NarrativePolicy.expired(age,duration)){it.remove();continue;}
   if(s.kind()==Kind.COORDS&&NarrativePolicy.coordsFrame(age)){
    var pos=p.blockPosition();
    p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket(Component.translatable("event.unwritten.coords",pos.getX(),pos.getY(),pos.getZ()).withStyle(ChatFormatting.GRAY)));
   }
   var corridor=s.corridor();
   if(s.kind()==Kind.HELP&&!s.director().helpBuilt()&&HelpCorridor.tryAt(age)&&!p.isSleeping()){
    corridor=RitualStructures.tryHelp(p).orElse(null);
    if(corridor!=null){
     s.director().markHelpBuilt();
     p.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvents.AMBIENT_CAVE.value()),SoundSource.AMBIENT,corridor.getX()+.5,corridor.getY()+.5,corridor.getZ()+.5,.9f,.6f,p.getRandom().nextLong()));
     p.sendSystemMessage(Component.translatable("event.unwritten.help.location",corridor.getX(),corridor.getY(),corridor.getZ()).withStyle(ChatFormatting.GRAY));
    }
   }
   if(s.kind()==Kind.HELP&&NarrativePolicy.warning(age)){
    p.sendSystemMessage(Component.translatable("event.unwritten.help.warning").withStyle(ChatFormatting.GRAY));
    if(!chases.hasChase(p)&&!mimics.active(p))apparitions.show(p,true);
   }
   entry.setValue(new Sequence(p,s.world(),s.kind(),age+1,s.director(),corridor));
  }
 }
 void clear(){active.clear();}
}
