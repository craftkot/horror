package dev.unwritten.client.mixin;
import java.util.List;
import dev.unwritten.client.TabPresentation;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {
 @Inject(method="getPlayerInfos",at=@At("RETURN"),cancellable=true)
 private void unwritten$hide(CallbackInfoReturnable<List<PlayerInfo>> cir){
  cir.setReturnValue(TabPresentation.filter(cir.getReturnValue()));
 }
}
