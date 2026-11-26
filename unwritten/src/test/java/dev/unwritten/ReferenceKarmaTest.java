package dev.unwritten;
import java.io.*;
public class ReferenceKarmaTest {
 public static void main(String[] args)throws Exception{
  var d=new HorrorDirector(1);
  for(int i=0;i<4;i++)d.bonemeal();
  if(d.karma()!=0)throw new AssertionError("early bonemeal reward");
  var buffer=new ByteArrayOutputStream();d.write(new DataOutputStream(buffer));
  d=HorrorDirector.read(new DataInputStream(new ByteArrayInputStream(buffer.toByteArray())),2);
  d.bonemeal();if(d.karma()!=1)throw new AssertionError("fifth use");
  d.tame();d.trample();d.explosion();
  if(d.karma()!=-6)throw new AssertionError("reference karma");
 }
}
