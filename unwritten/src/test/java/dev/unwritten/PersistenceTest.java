package dev.unwritten;
import java.nio.file.*;
import java.util.*;
import java.io.*;
public class PersistenceTest {
 public static void main(String[] args)throws Exception{
  var dir=Files.createTempDirectory("unwritten-test");
  try{
   var id=UUID.randomUUID();var other=UUID.randomUUID();
   var state=PlayerStateStore.load(dir,id,42);
   state.director().adjustKarma(-30);
   for(int i=0;i<60;i++)state.director().tick(new HorrorDirector.Observation(true,true,true,false,0));
   var area=PlayerHistory.Area.at("overworld",42,-42);state.history().observe(area);
   PlayerStateStore.save(dir,id,state);
   var restored=PlayerStateStore.load(dir,id,43);
   if(restored.director().karma()!=-30||restored.director().pressure()!=240||restored.history().familiarity(area)!=1)throw new AssertionError("restore");
   if(PlayerStateStore.load(dir,other,43).director().karma()!=0)throw new AssertionError("isolation");
   Files.write(dir.resolve(id+".dat"),new byte[]{0,1});
   try{PlayerStateStore.load(dir,id,42);throw new AssertionError("accepted corrupt data");}catch(IOException expected){}
  }finally{try(var files=Files.list(dir)){for(var f:files.toList())Files.delete(f);}Files.delete(dir);}
 }
}
