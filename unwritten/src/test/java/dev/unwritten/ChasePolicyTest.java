package dev.unwritten;
public class ChasePolicyTest {
 public static void main(String[] args){
  if(ChasePolicy.ends(599,99,4096,true,true))throw new AssertionError("early expiry");
  if(!ChasePolicy.ends(600,0,100,true,true)||!ChasePolicy.ends(10,100,100,true,true)||!ChasePolicy.ends(10,0,4097,true,true)||!ChasePolicy.ends(10,0,100,false,true)||!ChasePolicy.ends(10,0,100,true,false)||!ChasePolicy.ends(10,0,Double.NaN,true,true))throw new AssertionError("expiry");
 }
}
