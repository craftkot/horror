package dev.unwritten;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** First disguise adapter: client-only ore afterimage, then watcher reveal.
 * Never changes the real mined block or its drops. */
final class OreEncounter {
 private record Encounter(ServerPlayer player,Object world,BlockPos pos,BlockState ore,int age){}
 private final Map<UUID,Encounter> active=new HashMap<>();
 void queue(ServerPlayer p,BlockPos pos,BlockState ore){
  active.putIfAbsent(p.getUUID(),new Encounter(p,p.level(),pos.immutable(),ore,0));
 }
 void tick(List<ServerPlayer> players,Apparitions apparitions){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var e=entry.getValue();var p=e.player();
   if(!players.contains(p)||p.level()!=e.world()){it.remove();continue;}
   var level=p.level();
   if(!level.hasChunkAt(e.pos().getX(),e.pos().getZ())){it.remove();continue;}
   if(!p.isAlive()||p.isCreative()||p.isSpectator()||p.getEyePosition().distanceToSqr(Vec3.atCenterOf(e.pos()))>144){
    restore(e);it.remove();continue;
   }
   if(e.age()==2){
    if(!level.getBlockState(e.pos()).isAir()){it.remove();continue;}
    p.connection.send(new ClientboundBlockUpdatePacket(e.pos(),e.ore()));
   }
   if(e.age()>=22){
    restore(e);Apparitions.soundBehind(p,SoundEvents.PLAYER_BREATH,.9f,.6f);apparitions.show(p,true);it.remove();
   }else entry.setValue(new Encounter(p,e.world(),e.pos(),e.ore(),e.age()+1));
  }
 }
 private void restore(Encounter e){
  var level=e.player().level();
  if(e.age()>=2&&level==e.world()&&level.hasChunkAt(e.pos().getX(),e.pos().getZ()))
    e.player().connection.send(new ClientboundBlockUpdatePacket(e.pos(),level.getBlockState(e.pos())));
 }
 void restoreAll(){active.values().forEach(this::restore);active.clear();}
 void clear(){active.clear();}
}
