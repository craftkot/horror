package dev.unwritten;
import java.util.*;
import java.io.*;
/** Bounded, per-player memory of visited areas and repeated routines. */
public final class PlayerHistory {
 public record Area(String dimension,int x,int z) {
  public Area { Objects.requireNonNull(dimension); }
  public static Area at(String dimension,int x,int z){return new Area(dimension,Math.floorDiv(x,32),Math.floorDiv(z,32));}
 }
 private final LinkedHashMap<Area,Integer> visits=new LinkedHashMap<>(16,.75f,true);
 public record Bed(String dimension,int x,int y,int z){public Bed{Objects.requireNonNull(dimension);}}
 private Bed bed;
 public void rememberBed(Bed value){bed=Objects.requireNonNull(value);}
 public Optional<Bed> bed(){return Optional.ofNullable(bed);}
 private Area current;
 private int dwell;
 public void observe(Area area){
  if(!area.equals(current)){current=area;dwell=0;visits.merge(area,1,(a,b)->Math.min(10000,a+b));}
  else dwell=Math.min(100000,dwell+1);
  while(visits.size()>256)visits.remove(visits.keySet().iterator().next());
 }
 public int familiarity(Area area){return visits.getOrDefault(area,0);}
 public int dwell(){return dwell;}
 public void write(DataOutput out)throws IOException{
  out.writeInt(2);out.writeInt(visits.size());
  for(var e:visits.entrySet()){out.writeUTF(e.getKey().dimension());out.writeInt(e.getKey().x());out.writeInt(e.getKey().z());out.writeInt(e.getValue());}
  out.writeBoolean(bed!=null);
  if(bed!=null){out.writeUTF(bed.dimension());out.writeInt(bed.x());out.writeInt(bed.y());out.writeInt(bed.z());}
 }
 public static PlayerHistory read(DataInput in)throws IOException{
  int version=in.readInt();if(version!=1&&version!=2)throw new IOException("Unknown history version");
  int count=in.readInt();if(count<0||count>256)throw new IOException("Invalid history size");
  var h=new PlayerHistory();
  for(int i=0;i<count;i++){var a=new Area(in.readUTF(),in.readInt(),in.readInt());int v=in.readInt();if(v<1||v>10000)throw new IOException("Invalid visits");h.visits.put(a,v);}
  if(version>=2&&in.readBoolean())h.bed=new Bed(in.readUTF(),in.readInt(),in.readInt(),in.readInt());
  return h;
 }
}
