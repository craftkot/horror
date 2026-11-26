package dev.unwritten;
public class DirectorTest {
 public static void main(String[] args){
  var d=new HorrorDirector(42);
  var dangerous=new HorrorDirector.Observation(true,true,true,false,-1);
  int previous=-1000, count=0;
  for(int i=0;i<10000;i++){
   var e=d.tick(dangerous);
   if(e.isPresent()){
    if(i<120||i-previous<45)throw new AssertionError("event interval");
    previous=i;count++;
   }
  }
  if(count==0||d.karma()!=-100)throw new AssertionError("progression");
  for(int i=0;i<500;i++)if(d.tick(new HorrorDirector.Observation(false,false,false,true,1)).isPresent())throw new AssertionError("safe area");
  if(d.pressure()!=0||d.karma()!=100)throw new AssertionError("recovery");
 }
}
