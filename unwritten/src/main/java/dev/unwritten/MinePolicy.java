package dev.unwritten;
public final class MinePolicy {
 public static final int COOLDOWN_SECONDS=8200/20;
 public static boolean roll(double value){return value>=0&&value<=0.065;}
 private MinePolicy(){}
}
