package dev.unwritten;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;

public final class PlayerStateStore {
 public record State(HorrorDirector director,PlayerHistory history){}
 public static State fresh(long seed){return new State(new HorrorDirector(seed),new PlayerHistory());}
 public static State load(Path dir,UUID id,long seed)throws IOException{
  Path file=dir.resolve(id+".dat");
  if(!Files.exists(file))return fresh(seed);
  if(Files.size(file)>1048576)throw new IOException("Oversized player state");
  try(var in=new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))){
   if(in.readInt()!=0x554e5731)throw new IOException("Invalid player state magic");
   var s=new State(HorrorDirector.read(in,seed),PlayerHistory.read(in));
   if(in.read()!=-1)throw new IOException("Unexpected trailing data");
   return s;
  }
 }
 public static void save(Path dir,UUID id,State state)throws IOException{
  Files.createDirectories(dir);
  Path target=dir.resolve(id+".dat");
  Path temp=Files.createTempFile(dir,"unwritten-",".tmp");
  try{
   try(var out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))){
    out.writeInt(0x554e5731);state.director().write(out);state.history().write(out);
   }
   try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
   catch(AtomicMoveNotSupportedException e){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
  }finally{Files.deleteIfExists(temp);}
 }
 private PlayerStateStore(){}
}
