package dev.unwritten;
import java.io.*;
import java.util.*;
public class SacrificeLayoutTest {
 public static void main(String[] args)throws Exception{
  var cells=SacrificeLayout.cells();
  for(int direction=0;direction<4;direction++){
   var seen=new HashSet<String>();
   for(var c:cells){
    if(c.x()<0||c.x()>=5||c.y()<0||c.y()>=4||c.z()<0||c.z()>=16)throw new AssertionError("bounds");
    if(!seen.add(HelpCorridor.dx(c.x(),c.z(),direction)+":"+c.y()+":"+HelpCorridor.dz(c.x(),c.z(),direction)))throw new AssertionError("duplicate");
    if(c.x()==2&&c.z()<12&&c.y()>0)throw new AssertionError("walkway obstructed");
   }
  }
  if(cells.stream().filter(c->c.material()==SacrificeLayout.Material.ALTAR).count()!=1)throw new AssertionError("altar");
  int lines=0;for(int t=0;t<=1200;t++){int line=SacrificeLayout.dialogLine(t);if(line>=0){if(line!=lines++)throw new AssertionError("order");}}
  if(lines!=10||!SacrificeLayout.within(64)||SacrificeLayout.within(65)||SacrificeLayout.within(Double.NaN))throw new AssertionError("dialogue");
  var d=new HorrorDirector(7);var site=new HorrorDirector.RitualSite(10,65,-40);
  d.markRitualHeard();if(d.ritualHeard())throw new AssertionError("unplaced");
  d.rememberRitual(site);d.rememberRitual(new HorrorDirector.RitualSite(0,0,0));d.markRitualHeard();
  var bytes=new ByteArrayOutputStream();d.write(new DataOutputStream(bytes));
  var loaded=HorrorDirector.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),8);
  if(!loaded.ritualHeard()||!loaded.ritualSite().orElseThrow().equals(site))throw new AssertionError("persistent site");
 }
}
