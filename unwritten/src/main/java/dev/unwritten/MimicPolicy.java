package dev.unwritten;
import java.util.random.RandomGenerator;
public final class MimicPolicy {
 public record Pattern(int offset,boolean xAxis,int duration){
  public Pattern{if(Math.abs((long)offset)<3||Math.abs((long)offset)>5||duration<300||duration>400)throw new IllegalArgumentException("Invalid mimic pattern");}
  public double x(double base){return base+(xAxis?offset:0);}
  public double z(double base){return base+(xAxis?0:offset);}
 }
 public static Pattern choose(RandomGenerator rng){
  int offset=3+rng.nextInt(3);
  return new Pattern(rng.nextBoolean()?offset:-offset,rng.nextBoolean(),300+rng.nextInt(101));
 }
 public static float yaw(float yaw){return (yaw+180f)%360f;}
 public static float pitch(float pitch){return -pitch;}
 public static boolean inRange(double distanceSquared){return distanceSquared>=0&&distanceSquared<=32;}
 private MimicPolicy(){}
}
