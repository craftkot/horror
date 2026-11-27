package dev.unwritten.client;
import java.util.*;
import dev.unwritten.SocialPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Filter only the visual TAB list, never mutate real player-info or session state. */
@EventBusSubscriber(modid="unwritten",value=Dist.CLIENT)
public final class TabPresentation {
 private record Hidden(ClientPacketListener connection,UUID id,String name,int age){}
 private static Hidden hidden;
 public static void begin(UUID id){
  var mc=Minecraft.getInstance();var connection=mc.getConnection();
  if(hidden!=null||mc.player==null||connection==null||!mc.player.isAlive()||id.equals(mc.player.getUUID()))return;
  var info=connection.getListedOnlinePlayers().stream().filter(p->p.getProfile().id().equals(id)).findFirst().orElse(null);
  if(info==null)return;
  hidden=new Hidden(connection,id,info.getProfile().name(),0);
  announce(mc,"multiplayer.player.left",hidden.name());
 }
 public static List<PlayerInfo> filter(List<PlayerInfo> rows){
  var h=hidden;
  if(h==null||Minecraft.getInstance().getConnection()!=h.connection())return rows;
  return new ArrayList<>(rows.stream().filter(p->!p.getProfile().id().equals(h.id())).toList());
 }
 @SubscribeEvent
 public static void tick(ClientTickEvent.Post event){
  var h=hidden;if(h==null)return;
  var mc=Minecraft.getInstance();var connection=mc.getConnection();
  if(connection!=h.connection()||mc.player==null||!mc.player.isAlive()||mc.player.isSpectator()||mc.player.isCreative()){
   hidden=null;return;
  }
  // Respect an actual server departure or a real unlist. No fabricated return.
  if(connection.getListedOnlinePlayers().stream().noneMatch(p->p.getProfile().id().equals(h.id()))){hidden=null;return;}
  if(h.age()+1>=SocialPolicy.HIDE_TICKS){hidden=null;announce(mc,"multiplayer.player.joined",h.name());}
  else hidden=new Hidden(connection,h.id(),h.name(),h.age()+1);
 }
 private static void announce(Minecraft mc,String key,String name){mc.player.sendSystemMessage(Component.translatable(key,Component.literal(name)).withStyle(ChatFormatting.YELLOW));}
 private TabPresentation(){}
}
