package dev.unwritten;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/** Real server-side chase, using a vanilla husk as a temporary entity/model. */
final class ChaseEncounter {
 private static final String MARK="unwritten_chase_proxy";
 private record Chase(ServerPlayer player,Mob mob,int age,int lost,Vec3 lastSeen){}
 private final Map<UUID,Chase> active=new HashMap<>();
 private final Map<UUID,UUID> owners=new HashMap<>();
 boolean hasChase(ServerPlayer p){return active.containsKey(p.getUUID());}
 boolean start(ServerPlayer p,Vec3 point){
  if(hasChase(p)||!p.isAlive()||p.isCreative()||p.isSpectator())return false;
  var level=p.level();var pos=BlockPos.containing(point);
  if(!level.hasChunkAt(pos.getX(),pos.getZ())||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir()||!level.getBlockState(pos.below()).isSolidRender())return false;
  var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("husk"));
  if(type==null||!(type.create(level,EntitySpawnReason.EVENT) instanceof Mob mob))return false;
  mob.setPos(point.x,point.y,point.z);
  if(!level.noCollision(mob))return false;
  mob.getPersistentData().putBoolean(MARK,true);mob.setCanPickUpLoot(false);
  owners.put(mob.getUUID(),p.getUUID());
  if(!level.addFreshEntity(mob)){owners.remove(mob.getUUID());return false;}
  active.put(p.getUUID(),new Chase(p,mob,0,0,p.position()));
  mob.setTarget(p);
  return true;
 }
 void tick(List<ServerPlayer> players){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var c=entry.getValue();var p=c.player();var mob=c.mob();
   boolean same=mob.level()==p.level();
   boolean eligible=players.contains(p)&&p.isAlive()&&!p.isCreative()&&!p.isSpectator()&&mob.isAlive()&&!mob.isRemoved();
   if(!eligible||!same){remove(c);it.remove();continue;}
   boolean visible=mob.getSensing().hasLineOfSight(p);
   int lost=visible?0:c.lost()+1;
   var last=visible?p.position():c.lastSeen();
   if(ChasePolicy.ends(c.age(),lost,mob.distanceToSqr(p),eligible,same)){remove(c);it.remove();continue;}
   if(c.age()%10==0){
    if(visible){mob.setTarget(p);mob.getNavigation().moveTo(p,1.2);}
    else {mob.setTarget(null);mob.getNavigation().moveTo(last.x,last.y,last.z,1.2);}
   }
   entry.setValue(new Chase(p,mob,c.age()+1,lost,last));
  }
 }
 void onJoin(EntityJoinLevelEvent event){
  if(!event.getLevel().isClientSide()&&event.getEntity().getPersistentData().contains(MARK)&&!owners.containsKey(event.getEntity().getUUID()))event.setCanceled(true);
 }
 void onTarget(LivingChangeTargetEvent event){
  if(!event.getEntity().getPersistentData().contains(MARK))return;
  var owner=owners.get(event.getEntity().getUUID());var next=event.getNewAboutToBeSetTarget();
  if(next!=null&&(owner==null||!owner.equals(next.getUUID())))event.setNewAboutToBeSetTarget(null);
 }
 void onDrops(LivingDropsEvent event){if(event.getEntity().getPersistentData().contains(MARK))event.setCanceled(true);}
 private void remove(Chase c){owners.remove(c.mob().getUUID());c.mob().discard();}
 void clear(){active.values().forEach(this::remove);active.clear();owners.clear();}
}
