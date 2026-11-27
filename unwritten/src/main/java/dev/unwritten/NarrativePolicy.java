package dev.unwritten;
public final class NarrativePolicy {
 public static final int COORDS_TICKS=1200, HELP_TICKS=600, WARNING_TICK=500;
 public static boolean coordsFrame(int age){return age>=0&&age<COORDS_TICKS&&age%20==0;}
 public static boolean warning(int age){return age==WARNING_TICK;}
 public static boolean expired(int age,int duration){return age>=duration;}
 private NarrativePolicy(){}
}
