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
 static net.minecraft.world.level.block.Block altarBlock(){
  return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.BLOCK,net.minecraft.resources.Identifier.withDefaultNamespace("red_stained_glass"))).value();
 }
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
 static Optional<BlockPos> trySacrifice(ServerPlayer p){
  var level=p.level();
  if(!level.dimension().identifier().toString().equals("minecraft:overworld"))return Optional.empty();
  var player=p.blockPosition();var rng=p.getRandom();
  for(int i=0;i<16;i++){
   double a=Math.toRadians(p.getYRot())+(rng.nextDouble()-.5)*Math.PI;
   int radius=24+rng.nextInt(13),direction=rng.nextInt(4);
   var center=player.offset((int)Math.round(Math.sin(a)*radius),0,(int)Math.round(-Math.cos(a)*radius));
   for(int y=1;y>=-2;y--){
    var origin=at(center.offset(0,y,0),-2,0,-8,direction);
    if(!sacrificeAvailable(level,origin,direction))continue;
    var plan=new ArrayList<Placement>();
    for(var cell:SacrificeLayout.cells()){
     var state=switch(cell.material()){
      case COBBLE -> Blocks.COBBLESTONE.defaultBlockState();
      case MOSS -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
      case WALL -> Blocks.COBBLESTONE_WALL.defaultBlockState();
      case BARS -> Blocks.IRON_BARS.defaultBlockState();
      case ALTAR -> altarBlock().defaultBlockState();
      case LANTERN -> Blocks.SOUL_LANTERN.defaultBlockState();
      case TORCH -> Blocks.REDSTONE_TORCH.defaultBlockState();
      case DUST -> Blocks.REDSTONE_WIRE.defaultBlockState();
     };
     plan.add(new Placement(at(origin,cell.x(),cell.y(),cell.z(),direction),state));
    }
    if(placePlan(level,plan))return Optional.of(at(origin,2,1,13,direction));
   }
  }
  return Optional.empty();
 }
 private static boolean sacrificeAvailable(ServerLevel level,BlockPos origin,int direction){
  if(level.isOutsideBuildHeight(origin.below())||level.isOutsideBuildHeight(origin.above(3)))return false;
  for(int x=-1;x<=SacrificeLayout.WIDTH;x++)for(int z=-1;z<=SacrificeLayout.LENGTH;z++){
   var base=at(origin,x,0,z,direction);
   if(!level.hasChunkAt(base.getX(),base.getZ())||!level.getWorldBorder().isWithinBounds(base))return false;
   if(x<0||x>=SacrificeLayout.WIDTH)continue;
   if(!level.getBlockState(base.below()).isSolidRender())return false;
   for(int y=0;y<SacrificeLayout.HEIGHT;y++)if(!level.getBlockState(base.above(y)).isAir())return false;
  }
  var far=at(origin,4,3,15,direction);
  var box=new AABB(Math.min(origin.getX(),far.getX()),origin.getY(),Math.min(origin.getZ(),far.getZ()),Math.max(origin.getX(),far.getX())+1,origin.getY()+4,Math.max(origin.getZ(),far.getZ())+1);
  return level.players().stream().noneMatch(p->box.inflate(8).contains(p.position()))&&level.noCollision(box);
 }
 private static boolean placePlan(ServerLevel level,List<Placement> plan){
  var done=new ArrayList<Placement>();
  for(var cell:plan){
   if(!level.getBlockState(cell.pos()).isAir()||!cell.state().canSurvive(level,cell.pos())||!level.setBlock(cell.pos(),cell.state(),3)){
    for(int i=done.size()-1;i>=0;i--){
     var old=done.get(i);
     // Wall, bar and dust connections update their properties as adjacent pieces are added.
     if(level.getBlockState(old.pos()).is(old.state().getBlock()))level.setBlock(old.pos(),Blocks.AIR.defaultBlockState(),3);
    }
    return false;
   }
   done.add(cell);
  }
  return true;
 }
 private RitualStructures(){}
}
