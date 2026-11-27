package dev.unwritten;
public class TimePolicyTest {
 public static void main(String[] args){
  if(TimePolicy.falseTime(48123,.699)!=66000||TimePolicy.falseTime(48123,.7)!=49000)throw new AssertionError("probability and day preservation");
  if(TimePolicy.falseTime(-1,.8)!=-23000)throw new AssertionError("negative clock");
  if(TimePolicy.expired(3999,true)||!TimePolicy.expired(4000,true)||TimePolicy.expired(199,false)||!TimePolicy.expired(200,false))throw new AssertionError("duration");
 }
}
