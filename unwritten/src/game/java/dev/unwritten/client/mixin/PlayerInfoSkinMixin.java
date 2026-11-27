package dev.unwritten.client.mixin;
import dev.unwritten.client.SkinPresentation;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoSkinMixin {
 @Inject(method="getSkin",at=@At("HEAD"),cancellable=true)
 private void unwritten$skin(CallbackInfoReturnable<PlayerSkin> cir){
  var info=(PlayerInfo)(Object)this;
  var skin=SkinPresentation.resolve(info.getProfile().id());
  if(skin!=null)cir.setReturnValue(skin);
 }
}
