package dev.unwritten;
import java.util.Random;
public class MimicPolicyTest {
 public static void main(String[] args){
  var rng=new Random(24);boolean x=false,z=false,min=false,max=false;
  for(int i=0;i<10000;i++){
   var p=MimicPolicy.choose(rng);
   double dx=p.x(10)-10,dz=p.z(-8)+8;
   if(p.xAxis()){x=true;if(dz!=0||dx!=p.offset())throw new AssertionError("x offset");}
   else{z=true;if(dx!=0||dz!=p.offset())throw new AssertionError("z offset");}
   min|=p.duration()==300;max|=p.duration()==400;
   if(!MimicPolicy.inRange(dx*dx+dz*dz))throw new AssertionError("range");
  }
  if(!x||!z||!min||!max||MimicPolicy.inRange(32.01)||!MimicPolicy.inRange(32)||MimicPolicy.inRange(Double.NaN))throw new AssertionError("bounds");
  if(MimicPolicy.yaw(270)!=90||MimicPolicy.pitch(-45)!=45)throw new AssertionError("mirror");
  var d=new HorrorDirector(42);
  var o=new HorrorDirector.Observation(true,true,true,false,0);
  for(int i=0;i<10000;i++)if(d.tick(o,false).isPresent())throw new AssertionError("busy encounter");
  if(d.nullPresent()||d.pressure()!=1000)throw new AssertionError("busy progression");
 }
}
