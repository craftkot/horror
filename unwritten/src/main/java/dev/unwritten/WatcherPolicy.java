package dev.unwritten;
/** Shared rules: God watcher lifetime and Circuit-style reactions. */
public final class WatcherPolicy {
 public enum Action { WAIT, EXPIRE, SPOTTED, CLOSE }
 public static Action decide(int age,double distanceSquared,int seen,boolean visible,boolean stalk,boolean eligible){
  if(!eligible||age>=300||!Double.isFinite(distanceSquared)||distanceSquared>2500)return Action.EXPIRE;
  if(stalk&&visible&&distanceSquared<=64)return Action.CLOSE;
  if(visible&&seen>=12)return stalk?Action.SPOTTED:Action.EXPIRE;
  if(distanceSquared<=9)return Action.EXPIRE;
  return Action.WAIT;
 }
 private WatcherPolicy(){}
}
