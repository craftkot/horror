package dev.unwritten;
import net.minecraft.core.BlockPos;
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
 private ReferenceEvents(){}
}
