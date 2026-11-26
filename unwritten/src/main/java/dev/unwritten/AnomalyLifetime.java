package dev.unwritten;

/** Pure expiry rule; distances are squared and time is measured in ticks. */
public final class AnomalyLifetime {
 public static boolean expired(int age,double distanceSquared,boolean eligible,boolean sameWorld){
  return !eligible || !sameWorld || age>=200 || !Double.isFinite(distanceSquared) || distanceSquared<=64 || distanceSquared>=1024;
 }
 private AnomalyLifetime(){}
}
