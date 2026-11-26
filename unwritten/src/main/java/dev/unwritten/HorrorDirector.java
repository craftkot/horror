package dev.unwritten;
import java.util.*;
/** Server-side policy; game adapter supplies observations once per second. */
public final class HorrorDirector {
 public enum Event { FOOTSTEPS, DISTANT_SILHOUETTE, ECHO, FALSE_PRESENCE, LOCAL_ANOMALY }
 public record Observation(boolean dark, boolean alone, boolean underground, boolean safe, int moralDelta) {}
 public record Decision(Event event, int stage) {}
 private final Random random;
 private int pressure, karma, cooldown, quiet=120;
 private Event last;
 public HorrorDirector(long seed){random=new Random(seed);}
 public Optional<Decision> tick(Observation o){
  karma=Math.clamp(karma+Math.clamp(o.moralDelta(),-10,10),-100,100);
  pressure=Math.clamp(pressure+(o.dark()?2:0)+(o.alone()?1:0)+(o.underground()?1:0)-(o.safe()?4:0),0,1000);
  if(cooldown>0) cooldown--;
  if(quiet>0){quiet--;return Optional.empty();}
  if(o.safe()||cooldown>0||pressure<60)return Optional.empty();
  int stage=Math.min(5,1+pressure/200);
  if(random.nextDouble()>0.008*stage)return Optional.empty();
  var choices=new ArrayList<>(List.of(Event.FOOTSTEPS,Event.ECHO));
  if(stage>=2)choices.add(Event.DISTANT_SILHOUETTE);
  if(stage>=3)choices.add(Event.FALSE_PRESENCE);
  if(stage>=4)choices.add(Event.LOCAL_ANOMALY);
  if(choices.size()>1)choices.remove(last);
  last=choices.get(random.nextInt(choices.size()));
  cooldown=45+random.nextInt(76);
  pressure=Math.max(0,pressure-35);
  return Optional.of(new Decision(last,stage));
 }
 public int pressure(){return pressure;}
 public int karma(){return karma;}
}
