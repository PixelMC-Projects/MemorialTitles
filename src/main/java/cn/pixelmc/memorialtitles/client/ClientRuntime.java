package cn.pixelmc.memorialtitles.client;

import cn.pixelmc.memorialtitles.MemorialTitles;
import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.forge.*;
import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.client.*;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

public final class ClientRuntime implements net.fabricmc.api.ClientModInitializer {
  public static final KeyMapping OPEN =
      new KeyMapping(
          "key.memorialtitles.open",
          InputConstants.UNKNOWN.getValue(),
          "key.categories.memorialtitles");
  public static final Map<UUID, RichText> WORN = new HashMap<>();
  private static final Set<UUID> HIDDEN_HEAD = new HashSet<>(), HIDDEN_CHAT = new HashSet<>();
  public static boolean head;
  public static boolean chat;
  public static String chatPrefix = "[{title}] ";
  public static float scale = 1, offset = .3f;
  private static Method visible;

  public static void receive(String json) {
    JsonObject j = JsonParser.parseString(json).getAsJsonObject();
    String kind = j.get("kind").getAsString();
    JsonElement data = j.get("data");
    Minecraft mc = Minecraft.getInstance();
    switch (kind) {
      case "display" -> {
        Network.DisplayPage page = ModelJson(data, Network.DisplayPage.class);
        if (page.reset()) { WORN.clear(); HIDDEN_HEAD.clear(); HIDDEN_CHAT.clear(); }
        head = page.head();
        chat = page.chat();
        chatPrefix = page.chatPrefix();
        scale = page.scale();
        offset = page.offset();
        for (var row : page.rows()) {
          WORN.put(row.player(), row.text());
          if (row.head()) HIDDEN_HEAD.remove(row.player()); else HIDDEN_HEAD.add(row.player());
          if (row.chat()) HIDDEN_CHAT.remove(row.player()); else HIDDEN_CHAT.add(row.player());
        }
      }
      case "avatarRequest" -> AvatarCapture.request(data.getAsString());
      case "adminOpen" -> mc.setScreen(new AdminScreen());
      case "prompt" -> mc.setScreen(new SearchScreen(data.getAsJsonObject()));
      default -> {
        if (mc.screen instanceof AdminScreen screen) screen.receive(kind, data);
        else if (mc.player != null && (kind.equals("error") || kind.equals("ok")))
          mc.player.sendSystemMessage(Component.literal(data.getAsString()));
      }
    }
  }

  private static <T> T ModelJson(JsonElement data, Class<T> type) {
    return cn.pixelmc.memorialtitles.domain.Model.JSON.fromJson(data, type);
  }

  public static void tick() {
    Minecraft mc = Minecraft.getInstance();
    AvatarCapture.tick(mc);
    if (mc.getConnection() == null) {
      WORN.clear();
      HIDDEN_HEAD.clear();
      HIDDEN_CHAT.clear();
      head = false;
      chat = false;
      return;
    }
    while (OPEN.consumeClick()) {
      JsonObject j = new JsonObject();
      j.addProperty("mode", "collection");
      Network.request("open", j);
    }
  }

  public static void name(Player p, com.mojang.blaze3d.vertex.PoseStack pose,
      net.minecraft.client.renderer.MultiBufferSource buffers, int light) {
    if (!head || HIDDEN_HEAD.contains(p.getUUID())) return;
    RichText rich = WORN.get(p.getUUID());
    if (rich == null) return;
    Minecraft mc = Minecraft.getInstance();
    if (mc.getEntityRenderDispatcher().distanceToSqr(p) > 4096) return;
    var text = Text.render(rich);
    Font font = mc.font;
    boolean seeThrough = !p.isDiscrete();
    float scoreboardOffset = p.getScoreboard().getDisplayObjective(2) != null
        && mc.getEntityRenderDispatcher().distanceToSqr(p) < 100 ? .25f : 0;
    pose.pushPose();
    pose.translate(0, p.getNameTagOffsetY() + offset + scoreboardOffset, 0);
    pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
    pose.scale(-.025f * scale, -.025f * scale, .025f * scale);
    float x = -font.width(text) / 2f;
    int background = (int) (mc.options.getBackgroundOpacity(.25f) * 255) << 24;
    font.drawInBatch(text, x, 0, 0x20ffffff, false, pose.last().pose(), buffers,
        seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, background, light);
    if (seeThrough) font.drawInBatch(text, x, 0, -1, false, pose.last().pose(), buffers,
        Font.DisplayMode.NORMAL, 0, light);
    pose.popPose();
  }

  public static Component chat(Component message, UUID sender) {
    if (!chat || HIDDEN_CHAT.contains(sender)) return message;
    RichText title = WORN.get(sender);
    if (title == null) return message;
    String[] parts = chatPrefix.split("\\{title}", -1);
    var prefix = Component.literal(parts[0]).append(Text.render(title));
    if (parts.length > 1) prefix.append(parts[1]);
    return prefix.append(message);
  }

  @Override public void onInitializeClient() {
    net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper.registerKeyBinding(OPEN);
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(Network.RESPONSE,
        (client, handler, buffer, sender) -> { String json = buffer.readUtf(262144); client.execute(() -> receive(json)); });
    net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
      Minecraft mc = Minecraft.getInstance();
      if (mc.screen instanceof AbstractContainerScreen<?> screen && screen.getMenu() instanceof ChestMenu)
        MenuTooltips.ownLines(stack).ifPresent(own -> { lines.clear(); lines.addAll(own); });
    });
  }

  public ClientRuntime() {}
}
