package dev.unwritten;
import java.util.*;
public class SkinLinksTest {
 private static UUID id(long n){return new UUID(0,n);}
 public static void main(String[] args){
  var links=new SkinLinks();
  if(links.bind(id(1),id(1))||!links.bind(id(1),id(2))||!links.bind(id(1),id(2))||links.bind(id(1),id(3)))throw new AssertionError("alias identity");
  if(links.bind(id(2),id(3))||links.bind(id(3),id(1))||links.bind(id(2),id(1)))throw new AssertionError("chaining");
  for(int n=3;n<=9;n++)if(!links.bind(id(n),id(2)))throw new AssertionError("capacity");
  if(links.bind(id(10),id(2)))throw new AssertionError("unbounded");
  links.retain(x->x.equals(id(1)));
  if(links.source(id(3))!=null||!id(2).equals(links.source(id(1)))||!links.bind(id(10),id(2)))throw new AssertionError("pruning");
  links.clear();
  if(links.source(id(1))!=null||links.source(id(10))!=null)throw new AssertionError("connection cleanup");
 }
}
