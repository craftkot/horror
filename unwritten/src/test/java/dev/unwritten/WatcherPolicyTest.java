package dev.unwritten;
import static dev.unwritten.WatcherPolicy.Action.*;
public class WatcherPolicyTest {
 public static void main(String[] args){
  check(WAIT,299,400,0,false,false,true);
  check(EXPIRE,300,400,0,false,false,true);
  check(WAIT,100,100,20,false,true,true);
  check(WAIT,100,64,0,false,true,true);
  check(CLOSE,100,64,0,true,true,true);
  check(SPOTTED,100,400,12,true,true,true);
  check(EXPIRE,100,400,12,true,false,true);
  check(EXPIRE,100,400,12,true,true,false);
  check(EXPIRE,100,Double.NaN,0,false,false,true);
 }
 static void check(WatcherPolicy.Action expected,int age,double distance,int seen,boolean visible,boolean stalk,boolean eligible){
  if(WatcherPolicy.decide(age,distance,seen,visible,stalk,eligible)!=expected)throw new AssertionError(expected);
 }
}
