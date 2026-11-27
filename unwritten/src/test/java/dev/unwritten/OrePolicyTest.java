package dev.unwritten;
import java.io.*;
public class OrePolicyTest {
 public static void main(String[] args)throws Exception{
  if(OrePolicy.triggers(false,true,0)||OrePolicy.triggers(true,false,0)||!OrePolicy.triggers(true,true,.000099)||OrePolicy.triggers(true,true,.0001)||OrePolicy.triggers(true,true,Double.NaN))throw new AssertionError("ore gates");
  var d=new HorrorDirector(42);
  var o=new HorrorDirector.Observation(true,true,true,false,0);
  boolean near=false;
  for(int i=0;i<100000;i++){
   var e=d.tick(o);
   if(e.isEmpty())continue;
   if(e.get().event()==HorrorDirector.Event.NULL_WHISPER&&!near)throw new AssertionError("whisper before presence");
   if(e.get().event()==HorrorDirector.Event.NULL_NEAR){near=true;break;}
  }
  if(!near||!d.nullPresent())throw new AssertionError("activation");
  var bytes=new ByteArrayOutputStream();d.write(new DataOutputStream(bytes));
  if(!HorrorDirector.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),43).nullPresent())throw new AssertionError("saved presence");
 }
}
