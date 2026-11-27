package dev.unwritten;
public final class OrePolicy {
 /** Original default is 0.01 percent, not 0.01 probability. */
 public static final double CHANCE=0.0001;
 public static boolean triggers(boolean nullPresent,boolean ore,double roll){
  return nullPresent&&ore&&roll>=0&&roll<CHANCE;
 }
 private OrePolicy(){}
}
