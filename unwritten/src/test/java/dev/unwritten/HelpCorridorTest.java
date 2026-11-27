package dev.unwritten;
import java.util.*;
import java.io.*;
public class HelpCorridorTest {
 public static void main(String[] args)throws Exception{
  var cells=HelpCorridor.cells();
  if(cells.size()!=121||cells.stream().filter(c->c.material()==HelpCorridor.Material.RED_TORCH).count()!=1)throw new AssertionError("materials");
  for(int r=0;r<4;r++){
   var seen=new HashSet<String>();
   for(var c:cells){
    if(c.x()<0||c.x()>=3||c.y()<0||c.y()>=4||c.z()<0||c.z()>=20)throw new AssertionError("bounds");
    if(!seen.add(HelpCorridor.dx(c.x(),c.z(),r)+":"+c.y()+":"+HelpCorridor.dz(c.x(),c.z(),r)))throw new AssertionError("overlap");
    if(c.x()==1&&c.y()>=1&&c.y()<=2&&c.material()==HelpCorridor.Material.STONE)throw new AssertionError("blocked walkway");
   }
  }
  if(!HelpCorridor.tryAt(20)||!HelpCorridor.tryAt(100)||!HelpCorridor.tryAt(180)||HelpCorridor.tryAt(181))throw new AssertionError("bounded retries");
  var d=new HorrorDirector(7);if(d.helpBuilt())throw new AssertionError("initial");d.markHelpBuilt();
  var bytes=new ByteArrayOutputStream();d.write(new DataOutputStream(bytes));
  if(!HorrorDirector.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),8).helpBuilt())throw new AssertionError("saved structure");
 }
}
