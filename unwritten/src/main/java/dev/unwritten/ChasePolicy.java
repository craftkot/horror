package dev.unwritten;
/** Bounded adaptation of the original 64-block chase and last-seen tracking. */
public final class ChasePolicy {
 public static final int MAX_AGE=600, LOST_LIMIT=100;
 public static boolean ends(int age,int lost,double distanceSquared,boolean eligible,boolean sameWorld){
  return !eligible||!sameWorld||age>=MAX_AGE||lost>=LOST_LIMIT||!Double.isFinite(distanceSquared)||distanceSquared>64*64;
 }
 private ChasePolicy(){}
}
