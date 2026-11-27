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
 public static boolean expired(int age){return age>=DURATION;}
 private SocialPolicy(){}
}
