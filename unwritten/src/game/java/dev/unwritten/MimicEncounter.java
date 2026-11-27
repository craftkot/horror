package dev.unwritten;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Private vanilla-player projection. Server owns the movement policy, not a fake authenticated player. */
final class MimicEncounter {
 private static final List<EquipmentSlot> SLOTS=List.of(EquipmentSlot.MAINHAND,EquipmentSlot.OFFHAND,EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET);
 private final Random rng=new Random();
 private int nextId=-1000000000;
 private record Echo(ServerPlayer viewer,Object world,int entityId,UUID profileId,boolean ownProfile,ServerPlayer skinSource,MimicPolicy.Pattern pattern,Vec3 point,int ticks){}
 private final Map<UUID,Echo> active=new HashMap<>();
 boolean active(ServerPlayer p){return active.containsKey(p.getUUID());}
 static ServerPlayer chooseSource(ServerPlayer viewer,List<ServerPlayer> players,Random rng){
  var others=players.stream().filter(p->p!=viewer&&p.isAlive()&&!p.isSpectator()).toList();
  return others.isEmpty()?viewer:others.get(rng.nextInt(others.size()));
 }
 static void sendProfile(ServerPlayer viewer,UUID id,String name,ServerPlayer source,boolean listed){
  var profile=new GameProfile(id,name,source.getGameProfile().properties());
  var actions=EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);
  var packet=new ClientboundPlayerInfoUpdatePacket(actions,List.<ServerPlayer>of());
  packet.entries=List.of(new ClientboundPlayerInfoUpdatePacket.Entry(id,profile,listed,50,GameType.SURVIVAL,null,true,0,null));
  viewer.connection.send(packet);
 }
 private Vec3 point(ServerPlayer p,MimicPolicy.Pattern pattern){return new Vec3(pattern.x(p.getX()),p.getY(),pattern.z(p.getZ()));}
 private boolean clearPosition(ServerPlayer p,Vec3 v){
  var pos=BlockPos.containing(v);var level=p.level();
  if(!level.hasChunkAt(pos.getX(),pos.getZ())||!level.getBlockState(pos).isAir()||!level.getBlockState(pos.above()).isAir())return false;
  return level.noCollision(new AABB(v.x-.3,v.y,v.z-.3,v.x+.3,v.y+1.8,v.z+.3));
 }
 boolean start(ServerPlayer p){
  var source=chooseSource(p,p.level().getServer().getPlayerList().getPlayers(),rng);
  return start(p,UUID.randomUUID(),source.getGameProfile().name(),source,true);
 }
 boolean start(ServerPlayer p,UUID id,String name,ServerPlayer source){return start(p,id,name,source,false);}
 private boolean start(ServerPlayer p,UUID id,String name,ServerPlayer source,boolean ownProfile){
  if(active(p)||!p.isAlive()||p.isCreative()||p.isSpectator())return false;
  var type=BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.withDefaultNamespace("player"));
  if(type==null)return false;
  for(int i=0;i<12;i++){
   var pattern=MimicPolicy.choose(rng);var v=point(p,pattern);
   if(!clearPosition(p,v))continue;
   int entityId=nextId--;
   if(ownProfile)sendProfile(p,id,name,source,false);
   float yaw=MimicPolicy.yaw(p.getYRot()),pitch=MimicPolicy.pitch(p.getXRot());
   p.connection.send(new ClientboundAddEntityPacket(entityId,id,v.x,v.y,v.z,pitch,yaw,type,0,Vec3.ZERO,MimicPolicy.yaw(p.getYHeadRot())));
   var e=new Echo(p,p.level(),entityId,id,ownProfile,source,pattern,v,0);
   active.put(p.getUUID(),e);appearance(e);return true;
  }
  return false;
 }
 private void appearance(Echo e){
  var source=e.skinSource();
  // Preserve all enabled outer layers (hat, jacket, sleeves, pants and cape) and main arm.
  e.viewer().connection.send(new ClientboundSetEntityDataPacket(e.entityId(),List.of(
    SynchedEntityData.DataValue.create(Avatar.DATA_PLAYER_MODE_CUSTOMISATION,source.getEntityData().get(Avatar.DATA_PLAYER_MODE_CUSTOMISATION)),
    SynchedEntityData.DataValue.create(Avatar.DATA_PLAYER_MAIN_HAND,source.getEntityData().get(Avatar.DATA_PLAYER_MAIN_HAND)))));
  var equipment=new ArrayList<Pair<EquipmentSlot,ItemStack>>();
  for(var slot:SLOTS)equipment.add(Pair.of(slot,source.getItemBySlot(slot).copy()));
  e.viewer().connection.send(new ClientboundSetEquipmentPacket(e.entityId(),equipment));
 }
 void tick(List<ServerPlayer> players,ChaseEncounter chases){
  var it=active.entrySet().iterator();
  while(it.hasNext()){
   var entry=it.next();var e=entry.getValue();var p=e.viewer();
   if(!players.contains(p)){it.remove();continue;}
   if(p.level()!=e.world()||!p.isAlive()||p.isCreative()||p.isSpectator()||chases.hasChase(p)||!MimicPolicy.inRange(e.point().distanceToSqr(p.position()))){remove(e);it.remove();continue;}
   var v=point(p,e.pattern());
   if(!clearPosition(p,v)){remove(e);it.remove();continue;}
   if(e.ticks()>=e.pattern().duration()){
    var reveal=e.point();remove(e);it.remove();chases.start(p,reveal);continue;
   }
   if(e.ticks()%2==0){
    var change=new PositionMoveRotation(v,Vec3.ZERO,MimicPolicy.yaw(p.getYRot()),MimicPolicy.pitch(p.getXRot()));
    p.connection.send(ClientboundTeleportEntityPacket.teleport(e.entityId(),change,Set.of(),p.onGround()));
    var head=new ClientboundRotateHeadPacket(p,(byte)(MimicPolicy.yaw(p.getYHeadRot())*256f/360f));
    head.entityId=e.entityId();p.connection.send(head);
   }
   if(e.ticks()%20==0)appearance(e);
   entry.setValue(new Echo(p,e.world(),e.entityId(),e.profileId(),e.ownProfile(),e.skinSource(),e.pattern(),v,e.ticks()+1));
  }
 }
 void cancelIdentity(ServerPlayer p,UUID profileId){
  var e=active.get(p.getUUID());
  if(e!=null&&e.profileId().equals(profileId)){remove(e);active.remove(p.getUUID());}
 }
 private void remove(Echo e){
  if(e.viewer().level()==e.world())e.viewer().connection.send(new ClientboundRemoveEntitiesPacket(e.entityId()));
  if(e.ownProfile())e.viewer().connection.send(new ClientboundPlayerInfoRemovePacket(List.of(e.profileId())));
 }
 void restoreAll(){active.values().forEach(this::remove);active.clear();}
 void clear(){active.clear();nextId=-1000000000;}
}
