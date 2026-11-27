package dev.unwritten;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.LightLayer;
/** Detect generated mineshaft pieces, not merely low altitude. Bounded sampling. */
final class MineEncounter {
 static boolean nearMine(ServerPlayer p){
  var level=p.level();var center=p.blockPosition();
  if(level.getBrightness(LightLayer.SKY,center)!=0)return false;
  // Only called on director opportunities. Do not query unloaded chunks.
  for(int x=-6;x<=6;x+=3)for(int z=-6;z<=6;z+=3){
   if(!level.hasChunkAt(center.getX()+x,center.getZ()+z))continue;
   for(int y=-6;y<=6;y+=3){
    var pos=center.offset(x,y,z);
    if(level.structureManager().getStructureWithPieceAt(pos,StructureTags.MINESHAFT).isValid())return true;
   }
  }
  return false;
 }
 private MineEncounter(){}
}
