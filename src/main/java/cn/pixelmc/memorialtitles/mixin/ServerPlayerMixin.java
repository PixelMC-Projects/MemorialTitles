package cn.pixelmc.memorialtitles.mixin;
import cn.pixelmc.memorialtitles.forge.ServerRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
  @Inject(method="getTabListDisplayName", at=@At("RETURN"), cancellable=true)
  private void memorialtitles$tab(CallbackInfoReturnable<Component> callback) {
    ServerRuntime runtime = ServerRuntime.current;
    if (runtime != null) callback.setReturnValue(runtime.tabName((ServerPlayer)(Object)this, callback.getReturnValue()));
  }
}
