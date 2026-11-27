package dev.unwritten.client;
import java.util.*;
import dev.unwritten.SkinLinks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.PlayerSkin;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Reuse the donor's vanilla skin object; no external lookup, download or account spoof. */
@EventBusSubscriber(modid="unwritten",value=Dist.CLIENT)
public final class SkinPresentation {
 private static final SkinLinks links=new SkinLinks();
 private static final Map<UUID,PlayerSkin> cached=new HashMap<>();
 private static ClientPacketListener session;
 private static ClientPacketListener connection(){
  var now=Minecraft.getInstance().getConnection();
  if(now!=session){links.clear();cached.clear();session=now;}
  return now;
 }
 public static void bind(UUID alias,UUID source){
  var connection=connection();
  if(connection==null||connection.getPlayerInfo(alias)==null||connection.getPlayerInfo(source)==null)return;
  var local=Minecraft.getInstance().player;
  if(local!=null&&alias.equals(local.getUUID()))return;
  if(links.bind(alias,source))resolve(alias);
 }
 public static PlayerSkin resolve(UUID alias){
  var connection=connection();var source=links.source(alias);
  if(connection==null||source==null)return null;
  var donor=connection.getPlayerInfo(source);
  if(donor!=null){
   var skin=donor.getSkin();
   if(skin!=null)cached.put(alias,skin);
  }
  return cached.get(alias);
 }
 @SubscribeEvent
 public static void tick(ClientTickEvent.Post event){
  var connection=connection();
  if(connection==null)return;
  links.retain(id->connection.getPlayerInfo(id)!=null);
  cached.keySet().removeIf(id->links.source(id)==null);
 }
 private SkinPresentation(){}
}
