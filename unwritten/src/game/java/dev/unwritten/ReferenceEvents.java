package dev.unwritten;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;

/** Reimplementation of the nearest wooden door event, not copied source. */
final class ReferenceEvents {
 static boolean toggleDoor(ServerPlayer p){
  var level=p.level();var center=p.blockPosition();BlockPos nearest=null;double best=Double.POSITIVE_INFINITY;
  for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++){
   if(!level.hasChunkAt(center.getX()+x,center.getZ()+z))continue;
   for(int y=-12;y<=12;y++){
    double d=x*x+y*y+z*z;if(d>=best)continue;
    var pos=center.offset(x,y,z);var state=level.getBlockState(pos);
    if(state.is(BlockTags.WOODEN_DOORS)&&state.getBlock() instanceof DoorBlock){nearest=pos;best=d;}
   }
  }
  if(nearest==null)return false;
  var state=level.getBlockState(nearest);
  if(state.getBlock() instanceof DoorBlock door){door.setOpen(p,level,state,nearest,!state.getValue(DoorBlock.OPEN));return true;}
  return false;
 }
 static boolean markBase(ServerPlayer p,PlayerHistory history){
  var bed=history.bed().orElse(null);var level=p.level();
  if(bed==null||!bed.dimension().equals(level.dimension().identifier().toString()))return false;
  var center=new BlockPos(bed.x(),bed.y(),bed.z());
  if(!level.hasChunkAt(center.getX(),center.getZ())||!level.getBlockState(center).is(net.minecraft.tags.BlockTags.BEDS))return false;
  var torch=Objects.requireNonNull(BuiltInRegistries.BLOCK.getValue(Identifier.withDefaultNamespace("redstone_torch"))).defaultBlockState();
  for(int i=0;i<32;i++){
   var pos=center.offset(p.getRandom().nextInt(11)-5,p.getRandom().nextInt(5)-2,p.getRandom().nextInt(11)-5);
   if(!level.hasChunkAt(pos.getX(),pos.getZ()))continue;
   var delta=Vec3.atCenterOf(pos).subtract(p.getEyePosition());
   if(delta.lengthSqr()<100||delta.lengthSqr()>4096||p.getLookAngle().dot(delta.normalize())>-.15)continue;
   if(!level.getBlockState(pos).isAir()||!level.getBlockState(pos.below()).isSolidRender()||!torch.canSurvive(level,pos))continue;
   return level.setBlock(pos,torch,3);
  }
  return false;
 }
 private ReferenceEvents(){}
}
