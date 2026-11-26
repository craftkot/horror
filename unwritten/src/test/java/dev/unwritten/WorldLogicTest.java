package dev.unwritten;
import java.io.*;
import java.util.*;
public class WorldLogicTest {
 public static void main(String[] args)throws Exception{
  var h=new PlayerHistory();
  var a=PlayerHistory.Area.at("overworld",-1,-33);
  if(a.x()!=-1||a.z()!=-2)throw new AssertionError("negative coordinates");
  h.observe(a);h.observe(a);if(h.familiarity(a)!=1||h.dwell()!=1)throw new AssertionError("dwell");
  var bytes=new ByteArrayOutputStream();h.write(new DataOutputStream(bytes));
  if(PlayerHistory.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).familiarity(a)!=1)throw new AssertionError("round trip");
  for(int i=0;i<300;i++)h.observe(new PlayerHistory.Area("overworld",i,0));
  if(h.familiarity(a)!=0)throw new AssertionError("bounded history");
  var eye=new SpatialPlanner.Point(0,65,0);var dir=new SpatialPlanner.Point(1,0,0);
  for(boolean visible:new boolean[]{true,false}){
   var p=SpatialPlanner.hiddenPosition(eye,dir,new SpatialPlanner.WorldProbe(){
    public Optional<SpatialPlanner.Point> safeStandingPosition(SpatialPlanner.Point p){return Optional.of(p);}
    public boolean visible(SpatialPlanner.Point a,SpatialPlanner.Point b){return visible;}
   },new Random(12));
   if(visible?p.isPresent():p.isEmpty()||p.get().x()>=0)throw new AssertionError("visibility");
  }
 }
}
