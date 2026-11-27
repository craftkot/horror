package dev.unwritten;
import java.util.*;
import java.util.random.RandomGenerator;
public final class SocialPolicy {
 public static final int DURATION=600;
 private static final List<String> NAMES=List.of("Null","Absent_","Player_404","NobodyHere","Unknown_");
 public static Optional<String> choose(Collection<String> occupied,RandomGenerator rng){
  var names=new ArrayList<>(NAMES);
  names.removeIf(n->occupied.stream().anyMatch(n::equalsIgnoreCase));
  return names.isEmpty()?Optional.empty():Optional.of(names.get(rng.nextInt(names.size())));
 }
 public static final int MIMIC_AT=120, HIDE_TICKS=400;
 public static boolean canHide(boolean self,boolean sameWorld,double distanceSquared){return !self&&(!sameWorld||Double.isFinite(distanceSquared)&&distanceSquared>1024);}
 public static boolean shouldReveal(int age){return age==MIMIC_AT;}
 public static boolean expired(int age){return age>=DURATION;}
 private SocialPolicy(){}
}
