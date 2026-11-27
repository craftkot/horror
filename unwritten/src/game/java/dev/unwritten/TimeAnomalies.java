package dev.unwritten;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;

/** Visual clock override only. Actual clocks, gamerules and saved world data are untouched. */
final class TimeAnomalies {
 private record State(ServerPlayer player,Object world,Holder<WorldClock> clock,long time,boolean freeze,int age){}
 private final Map<UUID,State> active=new HashMap<>();
 private boolean eligible(ServerPlayer p){return p.isAlive()&&!p.isCreative()&&!p.isSpectator()&&p.level().dimension().identifier().toString().equals("minecraft:overworld");}
 boolean start(ServerPlayer p,boolean freeze){
  if(!eligible(p)||active.containsKey(p.getUUID()))return false;
  var packet=p.level().clockManager().createFullSyncPacket();
  for(var entry:packet.clockUpdates().entrySet()){
   if(!entry.getKey().is(WorldClocks.OVERWORLD))continue;
   var clock=entry.getValue();
   if(freeze&&clock.rate()==0)return false;
   long time=freeze?clock.totalTicks():TimePolicy.falseTime(clock.totalTicks(),p.getRandom().nextDouble());
   var s=new State(p,p.level(),entry.getKey(),time,freeze,0);
   active.put(p.getUUID(),s);send(s);return true;
  }
  return false;
 }
 private void send(State s){
  var full=s.player().level().clockManager().createFullSyncPacket();
  s.player().connection.send(new ClientboundSetTimePacket(full.gameTime(),Map.of(s.clock(),new ClockNetworkState(s.time(),0f,0f))));
 }
 private void restore(ServerPlayer p){p.connection.send(p.level().clockManager().createFullSyncPacket());}
 void tick(List<ServerPlayer> players){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var e=it.next();var s=e.getValue();var p=s.player();
   if(!players.contains(p)){it.remove();continue;}
   if(p.level()!=s.world()||!eligible(p)||TimePolicy.expired(s.age()+1,s.freeze())){restore(p);it.remove();continue;}
   var next=new State(p,s.world(),s.clock(),s.time(),s.freeze(),s.age()+1);
   if(next.age()%20==0)send(next);
   e.setValue(next);
  }
 }
 void restoreAll(){active.values().forEach(s->restore(s.player()));active.clear();}
 void clear(){active.clear();}
}
