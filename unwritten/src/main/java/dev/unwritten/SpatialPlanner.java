package dev.unwritten;
import java.util.*;
public final class SpatialPlanner {
 public record Point(double x,double y,double z){
  Point add(Point p){return new Point(x+p.x,y+p.y,z+p.z);}
  Point scale(double s){return new Point(x*s,y*s,z*s);}
  double dot(Point p){return x*p.x+y*p.y+z*p.z;}
  Point unit(){double n=Math.sqrt(dot(this));if(!Double.isFinite(n)||n<1e-8)throw new IllegalArgumentException("Invalid direction");return scale(1/n);}
 }
 public interface WorldProbe {
  Optional<Point> safeStandingPosition(Point candidate);
  boolean visible(Point eye,Point target);
 }
 public static Optional<Point> hiddenPosition(Point eye,Point direction,WorldProbe world,Random random){
  Point forward=direction.unit();
  for(int i=0;i<24;i++){
   double a=random.nextDouble()*Math.PI*2,r=14+random.nextDouble()*18;
   var p=world.safeStandingPosition(eye.add(new Point(Math.cos(a)*r,-1.6,Math.sin(a)*r)));
   if(p.isEmpty())continue;
   Point target=p.get().add(new Point(0,1.6,0));
   Point delta=target.add(eye.scale(-1));
   double distance=delta.dot(delta);
   if(distance<196||distance>1600)continue;
   if(delta.unit().dot(forward)>-.15||world.visible(eye,target))continue;
   return p;
  }
  return Optional.empty();
 }
 private SpatialPlanner(){}
}
