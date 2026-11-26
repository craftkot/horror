package dev.unwritten;
import java.io.*;
public class SleepTest {
 public static void main(String[] args)throws Exception{
  var d=new HorrorDirector(1);
  if(d.sleep(false)!=HorrorDirector.Sleep.ALLOW)throw new AssertionError("neutral");
  d.adjustKarma(-5);
  if(d.sleep(false)!=HorrorDirector.Sleep.GUILT||d.sleep(true)!=HorrorDirector.Sleep.ALLOW)throw new AssertionError("bounded denial");
  var h=new PlayerHistory();var bed=new PlayerHistory.Bed("overworld",3,64,-2);h.rememberBed(bed);
  var b=new ByteArrayOutputStream();var out=new DataOutputStream(b);d.write(out);h.write(out);
  var in=new DataInputStream(new ByteArrayInputStream(b.toByteArray()));
  d=HorrorDirector.read(in,2);h=PlayerHistory.read(in);
  if(!h.bed().orElseThrow().equals(bed)||d.sleep(false)!=HorrorDirector.Sleep.ALLOW)throw new AssertionError("persistence");
  for(int i=0;i<180;i++)d.tick(new HorrorDirector.Observation(false,false,false,true,0));
  if(d.sleep(true)!=HorrorDirector.Sleep.WATCHED)throw new AssertionError("watcher priority");
 }
}
