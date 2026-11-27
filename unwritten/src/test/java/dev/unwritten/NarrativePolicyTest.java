package dev.unwritten;
public class NarrativePolicyTest {
 public static void main(String[] args){
  int frames=0,warnings=0;
  for(int i=0;i<1201;i++){if(NarrativePolicy.coordsFrame(i))frames++;if(NarrativePolicy.warning(i))warnings++;}
  if(frames!=60||warnings!=1||NarrativePolicy.warning(499)||NarrativePolicy.coordsFrame(1200))throw new AssertionError("timing");
  if(NarrativePolicy.expired(599,600)||!NarrativePolicy.expired(600,600))throw new AssertionError("expiration");
 }
}
