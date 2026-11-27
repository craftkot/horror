package dev.unwritten;
import java.util.*;
/** New traversable layout inspired by the reference 3 x 4 x 20 Help corridor. */
public final class HelpCorridor {
 public static final int WIDTH=3, HEIGHT=4, LENGTH=20;
 public enum Material { STONE, RED_TORCH }
 public record Cell(int x,int y,int z,Material material){}
 private static final List<Cell> CELLS=create();
 private static List<Cell> create(){
  var cells=new ArrayList<Cell>();
  for(int z=0;z<LENGTH;z++){
   cells.add(new Cell(1,0,z,Material.STONE));
   cells.add(new Cell(1,3,z,Material.STONE));
   for(int y=1;y<=2;y++){
    cells.add(new Cell(0,y,z,Material.STONE));
    cells.add(new Cell(2,y,z,Material.STONE));
   }
  }
  cells.add(new Cell(1,1,10,Material.RED_TORCH));
  return List.copyOf(cells);
 }
 public static List<Cell> cells(){return CELLS;}
 public static boolean tryAt(int age){return age==20||age==100||age==180;}
 /** Local x is corridor width, local z is length. Four cardinal orientations. */
 public static int dx(int x,int z,int direction){return switch(direction){case 0->x;case 1->-z;case 2->-x;case 3->z;default->throw new IllegalArgumentException("direction");};}
 public static int dz(int x,int z,int direction){return switch(direction){case 0->z;case 1->x;case 2->-z;case 3->-x;default->throw new IllegalArgumentException("direction");};}
 private HelpCorridor(){}
}
