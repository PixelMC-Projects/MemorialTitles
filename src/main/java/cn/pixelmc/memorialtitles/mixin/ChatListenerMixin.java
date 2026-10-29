package cn.pixelmc.memorialtitles.mixin;
import cn.pixelmc.memorialtitles.client.ClientRuntime;
import com.mojang.authlib.GameProfile;
import java.time.Instant;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
/** Decorates the rendered message after trust evaluation, retaining the original signature/tag. */
@Mixin(ChatListener.class)
public abstract class ChatListenerMixin {
 @Redirect(method="showMessageToPlayer", at=@At(value="INVOKE", target="Lnet/minecraft/client/gui/components/ChatComponent;addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V"))
 private void memorialtitles$chat(ChatComponent hud, Component decorated, MessageSignature signature, GuiMessageTag tag,
     ChatType.Bound bound, PlayerChatMessage signed, Component message, GameProfile sender, boolean secure, Instant received) {
   hud.addMessage(ClientRuntime.chat(decorated, sender.getId()), signature, tag);
 }
}
