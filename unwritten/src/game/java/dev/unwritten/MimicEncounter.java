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
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Movement adapter for the reference FakePlayerMimic; model remains a proxy. */
final class MimicEncounter {
 private static final String MARK="unwritten_mimic_proxy";
 private final Random rng=new Random();
 private record Echo(ServerPlayer player,Mob body,MimicPolicy.Pattern pattern,int ticks){}
 private final Map<UUID,Echo> active=new HashMap<>();
 private final Set<UUID> owned=new HashSet<>();
 boolean active(ServerPlayer p){return active.containsKey(p.getUUID());}
 private Vec3 point(ServerPlayer p,MimicPolicy.Pattern pattern){
  return new Vec3(pattern.x(p.getX()),p.getY(),pattern.z(p.getZ()));
 }
 private boolean clearPosition(ServerPlayer p,Vec3 v){
  var pos=BlockPos.containing(v);var level=p.level();
  return level.hasChunkAt(pos.getX(),pos.getZ())&&level.getBlockState(pos).isAir()&&level.getBlockState(pos.above()).isAir();
 }
 boolean start(ServerPlayer p){
  if(active(p)||!p.isAlive()||p.isCreative()||p.isSpectator())return false;
  var level=p.level();
  var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("husk"));
  if(type==null||!(type.create(level,EntitySpawnReason.EVENT) instanceof Mob body))return false;
  for(int i=0;i<12;i++){
   var pattern=MimicPolicy.choose(rng);var v=point(p,pattern);
   if(!clearPosition(p,v))continue;
   body.setPos(v.x,v.y,v.z);
   if(!level.noCollision(body))continue;
   body.setNoAi(true);body.setNoGravity(true);body.setSilent(true);body.setCanPickUpLoot(false);
   body.getPersistentData().putBoolean(MARK,true);
   rotate(body,p);
   owned.add(body.getUUID());
   if(!level.addFreshEntity(body)){owned.remove(body.getUUID());return false;}
   active.put(p.getUUID(),new Echo(p,body,pattern,0));return true;
  }
  body.discard();return false;
 }
 private void rotate(Mob body,ServerPlayer p){
  float yaw=MimicPolicy.yaw(p.getYRot());
  body.setYRot(yaw);body.setXRot(MimicPolicy.pitch(p.getXRot()));
  body.setYHeadRot(MimicPolicy.yaw(p.getYHeadRot()));body.setYBodyRot(yaw);
 }
 void tick(List<ServerPlayer> players,ChaseEncounter chases){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var e=it.next();var echo=e.getValue();var p=echo.player();var body=echo.body();
   if(!players.contains(p)||p.level()!=body.level()||!p.isAlive()||p.isCreative()||p.isSpectator()||body.isRemoved()||!body.isAlive()||chases.hasChase(p)||!MimicPolicy.inRange(body.distanceToSqr(p))){
    remove(echo);it.remove();continue;
   }
   var v=point(p,echo.pattern());
   if(!clearPosition(p,v)||!p.level().noCollision(body,body.getBoundingBox().move(v.subtract(body.position())))){
    remove(echo);it.remove();continue;
   }
   if(echo.ticks()>=echo.pattern().duration()){
    var reveal=body.position();remove(echo);it.remove();chases.start(p,reveal);continue;
   }
   body.setPos(v.x,v.y,v.z);body.setDeltaMovement(Vec3.ZERO);rotate(body,p);
   e.setValue(new Echo(p,body,echo.pattern(),echo.ticks()+1));
  }
 }
 void onJoin(EntityJoinLevelEvent event){
  if(!event.getLevel().isClientSide()&&event.getEntity().getPersistentData().contains(MARK)&&!owned.contains(event.getEntity().getUUID()))event.setCanceled(true);
 }
 void onDamage(LivingIncomingDamageEvent event){if(owned.contains(event.getEntity().getUUID()))event.setCanceled(true);}
 void onDrops(LivingDropsEvent event){if(event.getEntity().getPersistentData().contains(MARK))event.setCanceled(true);}
 private void remove(Echo e){owned.remove(e.body().getUUID());e.body().discard();}
 void clear(){active.values().forEach(this::remove);active.clear();owned.clear();}
}
