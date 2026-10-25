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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import com.mojang.datafixers.util.Either;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;

@Mod.EventBusSubscriber(modid = MemorialTitles.ID, value = Dist.CLIENT)
public final class ClientRuntime {
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

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) {
    Minecraft mc = Minecraft.getInstance();
    if (e.phase != TickEvent.Phase.END) return;
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

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void name(RenderNameTagEvent e) {
    if (!head || e.getResult() == Event.Result.DENY || !(e.getEntity() instanceof Player p)) return;
    if (HIDDEN_HEAD.contains(p.getUUID())) return;
    RichText rich = WORN.get(p.getUUID());
    if (rich == null) return;
    Minecraft mc = Minecraft.getInstance();
    try {
      if (visible == null)
        visible =
            ObfuscationReflectionHelper.findMethod(EntityRenderer.class, "m_6512_", Entity.class);
      if (!(boolean) visible.invoke(e.getEntityRenderer(), p)) return;
    } catch (ReflectiveOperationException error) {
      head = false;
      com.mojang.logging.LogUtils.getLogger().error("MemorialTitles 头顶可见性适配失败，已停用该出口", error);
      return;
    }
    if (!ForgeHooksClient.isNameplateInRenderDistance(
        p, mc.getEntityRenderDispatcher().distanceToSqr(p))) return;
    var pose = e.getPoseStack();
    var text = Text.render(rich);
    Font font = mc.font;
    boolean seeThrough = !p.isDiscrete();
    float scoreboardOffset =
        p.getScoreboard().getDisplayObjective(2) != null
                && mc.getEntityRenderDispatcher().distanceToSqr(p) < 100
            ? .25f
            : 0;
    pose.pushPose();
    pose.translate(0, p.getNameTagOffsetY() + offset + scoreboardOffset, 0);
    pose.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
    pose.scale(-.025f * scale, -.025f * scale, .025f * scale);
    float x = -font.width(text) / 2f;
    int background = (int) (mc.options.getBackgroundOpacity(.25f) * 255) << 24;
    font.drawInBatch(
        text,
        x,
        0,
        0x20ffffff,
        false,
        pose.last().pose(),
        e.getMultiBufferSource(),
        seeThrough ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL,
        background,
        e.getPackedLight());
    if (seeThrough)
      font.drawInBatch(
          text,
          x,
          0,
          -1,
          false,
          pose.last().pose(),
          e.getMultiBufferSource(),
          Font.DisplayMode.NORMAL,
          0,
          e.getPackedLight());
    pose.popPose();
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void chat(ClientChatReceivedEvent e) {
    if (!chat || e.isSystem() || e.isCanceled()) return;
    if (HIDDEN_CHAT.contains(e.getSender())) return;
    RichText title = WORN.get(e.getSender());
    if (title == null) return;
    String[] parts = chatPrefix.split("\\{title}", -1);
    var prefix = Component.literal(parts[0]).append(Text.render(title));
    if (parts.length > 1) prefix.append(parts[1]);
    // Prefix the decorated display, preserving the original signed message and sender.
    e.setMessage(prefix.append(e.getMessage()));
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void menuTooltip(RenderTooltipEvent.GatherComponents event) {
    Minecraft mc = Minecraft.getInstance();
    if (!(mc.screen instanceof AbstractContainerScreen<?> screen)
        || !(screen.getMenu() instanceof ChestMenu)) return;
    MenuTooltips.ownLines(event.getItemStack()).ifPresent(lines -> {
      event.getTooltipElements().clear();
      lines.forEach(line -> event.getTooltipElements().add(Either.left(line)));
    });
  }

  @Mod.EventBusSubscriber(
      modid = MemorialTitles.ID,
      value = Dist.CLIENT,
      bus = Mod.EventBusSubscriber.Bus.MOD)
  public static final class Keys {
    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent e) {
      e.register(OPEN);
    }
  }

  private ClientRuntime() {}
}
