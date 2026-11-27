package dev.unwritten;
import java.util.*;
/** Original safe design inspired by the 5x4x16 reference altar. */
public final class SacrificeLayout {
 public static final int WIDTH=5,HEIGHT=4,LENGTH=16,DIALOG_TICKS=1200;
 public enum Material { COBBLE,MOSS,WALL,BARS,ALTAR,LANTERN,TORCH,DUST }
 public record Cell(int x,int y,int z,Material material){}
 private static final List<Cell> CELLS=create();
 private static List<Cell> create(){
  var cells=new ArrayList<Cell>();
  for(int z=0;z<LENGTH;z++)for(int x=0;x<WIDTH;x++)
   cells.add(new Cell(x,0,z,(x+z*3)%7==0?Material.MOSS:Material.COBBLE));
  for(int z=1;z<15;z++){
   cells.add(new Cell(0,1,z,Material.WALL));cells.add(new Cell(4,1,z,Material.WALL));
  }
  for(int x=1;x<=3;x++)for(int z=12;z<=14;z++)
   cells.add(new Cell(x,1,z,x==2&&z==13?Material.ALTAR:Material.COBBLE));
  for(int z=12;z<=14;z++){
   cells.add(new Cell(1,2,z,Material.BARS));cells.add(new Cell(3,2,z,Material.BARS));
  }
  cells.add(new Cell(2,2,14,Material.BARS));
  cells.add(new Cell(1,1,10,Material.LANTERN));cells.add(new Cell(3,1,10,Material.TORCH));
  for(int z=3;z<=8;z++)cells.add(new Cell(z%2==0?1:3,1,z,Material.DUST));
  return List.copyOf(cells);
 }
 public static List<Cell> cells(){return CELLS;}
 public static boolean within(double distanceSquared){return distanceSquared>=0&&distanceSquared<=64;}
 public static int dialogLine(int age){return age>=100&&age<=1000&&age%100==0?age/100-1:-1;}
 private SacrificeLayout(){}
}
