package dev.unwritten;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
/** A small, bounded, air-only structure placement. No chunk generation or terrain carving. */
final class RitualStructures {
 private record Placement(BlockPos pos,BlockState state){}
 private static BlockPos at(BlockPos origin,int x,int y,int z,int direction){
  return origin.offset(HelpCorridor.dx(x,z,direction),y,HelpCorridor.dz(x,z,direction));
 }
 static Optional<BlockPos> tryHelp(ServerPlayer p){
  var level=p.level();
  if(!level.dimension().identifier().toString().equals("minecraft:overworld"))return Optional.empty();
  var player=p.blockPosition();var rng=p.getRandom();
  for(int i=0;i<16;i++){
   double a=Math.toRadians(p.getYRot())+(rng.nextDouble()-.5)*Math.PI;
   int radius=24+rng.nextInt(13),direction=rng.nextInt(4);
   var center=player.offset((int)Math.round(Math.sin(a)*radius),0,(int)Math.round(-Math.cos(a)*radius));
   for(int y=2;y>=-2;y--){
    var origin=at(center.offset(0,y,0),-1,0,-10,direction);
    if(!available(p,level,origin,direction))continue;
    if(place(level,origin,direction))return Optional.of(at(origin,1,1,0,direction));
   }
  }
  return Optional.empty();
 }
 private static boolean available(ServerPlayer p,ServerLevel level,BlockPos origin,int direction){
  if(level.isOutsideBuildHeight(origin.below())||level.isOutsideBuildHeight(origin.above(3)))return false;
  // Include both open entrances in the clearance check.
  for(int x=0;x<HelpCorridor.WIDTH;x++)for(int z=-1;z<=HelpCorridor.LENGTH;z++){
   var base=at(origin,x,0,z,direction);
   if(!level.hasChunkAt(base.getX(),base.getZ())||!level.getWorldBorder().isWithinBounds(base))return false;
   if(x==1&&!level.getBlockState(base.below()).isSolidRender())return false;
   for(int y=0;y<HelpCorridor.HEIGHT;y++)if(!level.getBlockState(base.above(y)).isAir())return false;
  }
  var far=at(origin,2,3,HelpCorridor.LENGTH-1,direction);
  var box=new AABB(Math.min(origin.getX(),far.getX()),origin.getY(),Math.min(origin.getZ(),far.getZ()),Math.max(origin.getX(),far.getX())+1,origin.getY()+4,Math.max(origin.getZ(),far.getZ())+1);
  // Don't place around players or intersect entities. Keep at least 8 blocks clear.
  if(level.players().stream().anyMatch(other->box.inflate(8).contains(other.position())))return false;
  return level.noCollision(box);
 }
 private static boolean place(ServerLevel level,BlockPos origin,int direction){
  var placed=new ArrayList<Placement>();
  for(var c:HelpCorridor.cells()){
   var pos=at(origin,c.x(),c.y(),c.z(),direction);
   var state=(c.material()==HelpCorridor.Material.STONE?Blocks.COBBLED_DEEPSLATE:Blocks.REDSTONE_TORCH).defaultBlockState();
   if(!level.getBlockState(pos).isAir()||!state.canSurvive(level,pos)||!level.setBlock(pos,state,3)){
    // Only revert our exact blocks, never overwrite a change made by another handler.
    for(int i=placed.size()-1;i>=0;i--){
     var old=placed.get(i);
     if(level.getBlockState(old.pos()).equals(old.state()))level.setBlock(old.pos(),Blocks.AIR.defaultBlockState(),3);
    }
    return false;
   }
   placed.add(new Placement(pos,state));
  }
  return true;
 }
 private RitualStructures(){}
}
