package dev.unwritten;
import java.util.*;
import java.io.*;
/** Server-side policy; game adapter supplies observations once per second. */
public final class HorrorDirector {
 public enum Event { FOOTSTEPS, DISTANT_SILHOUETTE, ECHO, FALSE_PRESENCE, LOCAL_ANOMALY, BREATH, DOOR, TEXT, NULL_NEAR, NULL_WHISPER, MIMIC, NULL_TITLE, COORDS, FREEZE_SKY, FALSE_TIME, FALSE_JOIN, FALSE_KICK, FALSE_LEAVE }
 public record Observation(boolean dark, boolean alone, boolean underground, boolean safe, int moralDelta) {}
 public record Decision(Event event, int stage) {}
 private final Random random;
 private int pressure, karma, cooldown, quiet=120;
 private Event last;
 private int bonemealUses;
 private int sleepCooldown;
 private boolean nullPresent;
 private int mineCooldown;
 public enum Sleep { ALLOW, GUILT, WATCHED, CALL }
 public HorrorDirector(long seed){random=new Random(seed);}
 public Optional<Decision> tick(Observation o){return tick(o,true);}
 public Optional<Decision> tick(Observation o,boolean allowEvents){
  karma=Math.clamp(karma+Math.clamp(o.moralDelta(),-10,10),-100,100);
  pressure=Math.clamp(pressure+(o.dark()?2:0)+(o.alone()?1:0)+(o.underground()?1:0)-(o.safe()?4:0),0,1000);
  if(cooldown>0) cooldown--;
  if(sleepCooldown>0)sleepCooldown--;
  if(mineCooldown>0)mineCooldown--;
  if(quiet>0){quiet--;return Optional.empty();}
  if(!allowEvents||o.safe()||cooldown>0||pressure<60)return Optional.empty();
  int stage=Math.min(5,1+pressure/200);
  if(random.nextDouble()>0.008*stage*(1.0-karma/250.0))return Optional.empty();
  var choices=new ArrayList<>(List.of(Event.FOOTSTEPS,Event.ECHO,Event.BREATH,Event.DOOR));
  if(stage>=2)choices.add(Event.DISTANT_SILHOUETTE);
  if(stage>=3){choices.add(Event.FALSE_PRESENCE);choices.add(Event.TEXT);choices.add(Event.FALSE_TIME);}
  if(stage>=4)choices.add(Event.FREEZE_SKY);
  if(stage>=4){choices.add(Event.LOCAL_ANOMALY);choices.add(Event.NULL_NEAR);}
  if(nullPresent){choices.add(Event.NULL_WHISPER);choices.add(Event.NULL_TITLE);choices.add(Event.COORDS);}
  if(nullPresent&&stage>=4){choices.add(Event.MIMIC);choices.add(Event.FALSE_JOIN);choices.add(Event.FALSE_LEAVE);}
  if(nullPresent&&stage>=5)choices.add(Event.FALSE_KICK);
  if(choices.size()>1)choices.remove(last);
  last=choices.get(random.nextInt(choices.size()));
  if(last==Event.NULL_NEAR)nullPresent=true;
  cooldown=45+random.nextInt(76);
  pressure=Math.max(0,pressure-35);
  return Optional.of(new Decision(last,stage));
 }
 public boolean mineReady(){return mineCooldown==0;}
 public void mineStarted(){mineCooldown=MinePolicy.COOLDOWN_SECONDS;}
 public boolean nullPresent(){return nullPresent;}
 public Sleep sleep(boolean watched){
  if(sleepCooldown>0)return Sleep.ALLOW;
  Sleep result=watched?Sleep.WATCHED:karma<0?Sleep.GUILT:karma>0&&random.nextInt(10)==0?Sleep.CALL:Sleep.ALLOW;
  if(result!=Sleep.ALLOW)sleepCooldown=180;
  return result;
 }
 public void bonemeal(){if(++bonemealUses>=5){bonemealUses=0;adjustKarma(1);}}
 public void tame(){adjustKarma(1);}
 public void trample(){adjustKarma(-5);}
 public void explosion(){adjustKarma(-3);}
 public void adjustKarma(int delta){karma=Math.clamp(karma+Math.clamp(delta,-100,100),-100,100);}
 public void write(DataOutput out)throws IOException{
  out.writeInt(5);out.writeInt(pressure);out.writeInt(karma);out.writeInt(cooldown);out.writeInt(quiet);
  out.writeUTF(last==null?"":last.name());out.writeInt(bonemealUses);out.writeInt(sleepCooldown);out.writeBoolean(nullPresent);out.writeInt(mineCooldown);
 }
 public static HorrorDirector read(DataInput in,long seed)throws IOException{
  int version=in.readInt();if(version<1||version>5)throw new IOException("Unknown director version");
  var d=new HorrorDirector(seed);
  d.pressure=in.readInt();d.karma=in.readInt();d.cooldown=in.readInt();d.quiet=in.readInt();
  if(d.pressure<0||d.pressure>1000||Math.abs((long)d.karma)>100||d.cooldown<0||d.cooldown>120||d.quiet<0||d.quiet>120)throw new IOException("Invalid director state");
  var name=in.readUTF();
  try{d.last=name.isEmpty()?null:Event.valueOf(name);}catch(IllegalArgumentException ex){throw new IOException("Invalid event",ex);}
  if(version>=2){d.bonemealUses=in.readInt();if(d.bonemealUses<0||d.bonemealUses>=5)throw new IOException("Invalid bonemeal counter");}
  if(version>=3){d.sleepCooldown=in.readInt();if(d.sleepCooldown<0||d.sleepCooldown>180)throw new IOException("Invalid sleep cooldown");}
  if(version>=4)d.nullPresent=in.readBoolean();
  if(version>=5){d.mineCooldown=in.readInt();if(d.mineCooldown<0||d.mineCooldown>MinePolicy.COOLDOWN_SECONDS)throw new IOException("Invalid mine cooldown");}
  return d;
 }
 public int pressure(){return pressure;}
 public int karma(){return karma;}
}
