package dev.unwritten;
import java.io.*;
public class MinePolicyTest {
 public static void main(String[] args)throws Exception{
  if(!MinePolicy.roll(0)||!MinePolicy.roll(.065)||MinePolicy.roll(.06501)||MinePolicy.roll(-1)||MinePolicy.roll(Double.NaN))throw new AssertionError("probability");
  var d=new HorrorDirector(123);
  if(!d.mineReady())throw new AssertionError("initial");
  d.mineStarted();
  var bytes=new ByteArrayOutputStream();d.write(new DataOutputStream(bytes));
  d=HorrorDirector.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),123);
  var o=new HorrorDirector.Observation(false,false,false,true,0);
  for(int i=0;i<409;i++)d.tick(o);
  if(d.mineReady())throw new AssertionError("early");
  d.tick(o);
  if(!d.mineReady())throw new AssertionError("cooldown");
 }
}
