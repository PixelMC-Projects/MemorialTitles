package cn.pixelmc.memorialtitles.mixin;
import cn.pixelmc.memorialtitles.client.ClientRuntime;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {
  @Shadow protected abstract boolean shouldShowName(T entity);
  @Inject(method="renderNameTag", at=@At("HEAD"))
  private void memorialtitles$name(T entity, Component name, PoseStack pose, MultiBufferSource buffers, int light, float partialTick, CallbackInfo ci) {
    if (entity instanceof Player player && shouldShowName(entity)) ClientRuntime.name(player, pose, buffers, light);
  }
}
