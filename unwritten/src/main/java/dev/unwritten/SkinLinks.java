package dev.unwritten;
import java.util.*;
import java.util.function.Predicate;
/** Connection-scoped alias mapping. Only synthetic profiles are keys; no chained aliases. */
public final class SkinLinks {
 private static final int LIMIT=8;
 private final Map<UUID,UUID> links=new HashMap<>();
 public boolean bind(UUID alias,UUID source){
  if(alias==null||source==null||alias.equals(source)||links.containsKey(source)||links.containsValue(alias))return false;
  if(links.containsKey(alias))return links.get(alias).equals(source);
  if(links.size()>=LIMIT)return false;
  links.put(alias,source);return true;
 }
 public UUID source(UUID alias){return links.get(alias);}
 public void retain(Predicate<UUID> present){links.keySet().removeIf(id->!present.test(id));}
 public void clear(){links.clear();}
}
