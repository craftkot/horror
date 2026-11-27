package dev.unwritten;
public final class TimePolicy {
 public static final int FREEZE_TICKS=4000, FALSE_TICKS=200;
 public static long falseTime(long current,double roll){
  return current-Math.floorMod(current,24000L)+(roll<.7?18000:1000);
 }
 public static boolean expired(int age,boolean freeze){return age>=(freeze?FREEZE_TICKS:FALSE_TICKS);}
 private TimePolicy(){}
}
