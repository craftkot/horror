package dev.unwritten.client;
import dev.unwritten.SocialPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid="unwritten",value=Dist.CLIENT)
public final class SocialClient {
 @SubscribeEvent
 public static void register(RegisterClientPayloadHandlersEvent event){
  event.register(SocialPayload.TYPE,(payload,context)->{
   var mc=Minecraft.getInstance();
   if(mc.player==null||mc.level==null||mc.gui.screen()!=null||mc.gui.overlay()!=null)return;
   mc.gui.setScreen(new AlertScreen(()->mc.gui.setScreen(null),
       Component.translatable("disconnect.disconnected"),
       Component.translatable("event.unwritten.fakekick.reason"),
       Component.translatable("gui.back"),true));
  });
 }
 private SocialClient(){}
}
