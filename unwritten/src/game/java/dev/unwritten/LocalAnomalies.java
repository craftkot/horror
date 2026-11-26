package dev.unwritten;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Private, reversible visual anomalies. Never calls setBlock or writes world chunks. */
final class LocalAnomalies {
 private final Random random=new Random();
 private record Illusion(ServerPlayer player,Object world,BlockPos pos,int age){}
 private final Map<UUID,Illusion> active=new HashMap<>();
 void show(ServerPlayer p){
  if(active.containsKey(p.getUUID()))return;
  var level=p.level();
  var marker=Objects.requireNonNull(BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("obsidian"))).defaultBlockState();
  double yaw=Math.toRadians(p.getYRot());
  for(int i=0;i<20;i++){
   double a=yaw+Math.PI+(random.nextDouble()-.5)*1.6;
   double r=11+random.nextDouble()*7;
   int x=(int)Math.floor(p.getX()+Math.sin(a)*r),z=(int)Math.floor(p.getZ()-Math.cos(a)*r);
   if(!level.hasChunkAt(x,z))continue;
   for(int dy=2;dy>=-3;dy--){
    var pos=new BlockPos(x,p.blockPosition().getY()+dy,z);
    if(!level.getBlockState(pos.below()).isSolidRender()||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir())continue;
    p.connection.send(new ClientboundBlockUpdatePacket(pos,marker));
    active.put(p.getUUID(),new Illusion(p,level,pos,0));
    return;
   }
  }
 }
 void tick(List<ServerPlayer> players){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var a=entry.getValue();var p=a.player();
   if(!players.contains(p)||p.level()!=a.world()){it.remove();continue;}
   double distance=p.position().distanceToSqr(Vec3.atCenterOf(a.pos()));
   if(AnomalyLifetime.expired(a.age(),distance,p.isAlive()&&!p.isCreative()&&!p.isSpectator(),true)){
    restore(a);it.remove();
   }else entry.setValue(new Illusion(p,a.world(),a.pos(),a.age()+1));
  }
 }
 private void restore(Illusion a){
  var p=a.player();var level=p.level();
  if(level==a.world()&&level.hasChunkAt(a.pos().getX(),a.pos().getZ()))
   p.connection.send(new ClientboundBlockUpdatePacket(a.pos(),level.getBlockState(a.pos())));
 }
 void restoreAll(){active.values().forEach(this::restore);active.clear();}
 void clear(){active.clear();}
}
