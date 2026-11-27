package dev.unwritten;
import java.util.*;
public class SocialPolicyTest {
 public static void main(String[] args){
  var r=new Random(1);
  for(int i=0;i<100;i++){
   var name=SocialPolicy.choose(List.of("nUlL","NobodyHere"),r).orElseThrow();
   if(name.equalsIgnoreCase("Null")||name.equalsIgnoreCase("NobodyHere")||!name.matches("[A-Za-z0-9_]{1,16}"))throw new AssertionError("name");
  }
  if(SocialPolicy.choose(List.of("Null","Absent_","Player_404","NobodyHere","Unknown_"),r).isPresent())throw new AssertionError("collision");
  if(SocialPolicy.expired(599)||!SocialPolicy.expired(600))throw new AssertionError("expiry");
 }
}
