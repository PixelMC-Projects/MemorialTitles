package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.client.*;
import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.forge.Network;
import com.google.gson.*;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MemorialTitles.ID, value = Dist.CLIENT)
public final class VisualProbe {
  private static int stage;
  private static long last;
  private static boolean diagnosed;
  private static net.minecraft.client.player.RemotePlayer nameplatePlayer;
  private static CompletableFuture<Void> language;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent e) throws Exception {
    if (Boolean.getBoolean("mt.gallery") || Boolean.getBoolean("mt.webRuntime")) return;
    if (e.phase != TickEvent.Phase.END) return;
    Minecraft mc = Minecraft.getInstance();
    long now = System.currentTimeMillis();
    if (stage == 0 && mc.screen instanceof AccessibilityOnboardingScreen) {
      mc.screen.onClose();
      return;
    }
    if (stage == 11 && nameplatePlayer != null && mc.player != null)
      ClientRuntime.WORN.put(
          nameplatePlayer.getUUID(), ClientRuntime.WORN.get(mc.player.getUUID()));
    if (stage == 2 && !diagnosed && now - last > 5000) {
      diagnosed = true;
      shot("connection-stage");
      LogUtils.getLogger()
          .info(
              "MT_CONNECT_STATE screen={} connection={} player={}",
              mc.screen,
              mc.getConnection(),
              mc.player);
    }
    if (stage == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
      if (!ClientRuntime.OPEN.isUnbound()) throw new AssertionError("default shortcut occupied");
      mc.options.guiScale().set(3);
      mc.resizeDisplay();
      mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
      mc.options.languageCode = "zh_cn";
      mc.getLanguageManager().setSelected("zh_cn");
      language = mc.reloadResourcePacks();
      stage = 1;
    } else if (stage == 1 && language.isDone() && mc.getOverlay() == null) {
      ConnectScreen.startConnecting(
          new TitleScreen(),
          mc,
          new ServerAddress("127.0.0.1", 45565),
          new ServerData("MemorialTitles local", "127.0.0.1:45565", false),
          false);
      stage = 2;
      last = now;
    } else if (stage == 2
        && mc.player != null
        && ClientRuntime.WORN.containsKey(mc.player.getUUID())
        && now - last > 10000) {
      Network.request("adminOpen", new JsonObject());
      stage = 3;
      last = now;
    } else if (stage == 3 && mc.screen instanceof AdminScreen && now - last > 2000) {
      shot("admin-list");
      Network.request("adminTitles", object("page", 0));
      stage = 4;
      last = now;
    } else if (stage == 4 && mc.screen instanceof AdminScreen screen && now - last > 1200) {
      boolean found = false;
      for (var child : screen.children())
        if (child instanceof Theme.Action b && b.detail.contains("runtime_title")) {
          b.onPress();
          found = true;
          break;
        }
      if (!found) throw new AssertionError("title row missing");
      stage = 5;
      last = now;
    } else if (stage == 5 && now - last > 1000) {
      shot("admin-editor");
      click("文字样式");
      stage = 6;
      last = now;
    } else if (stage == 6 && now - last > 1000) {
      shot("admin-styles");
      click("打开选色器");
      stage = 61;
      last = now;
    } else if (stage == 61 && now - last > 1000) {
      shot("admin-color-picker");
      click("取消");
      stage = 62;
      last = now;
    } else if (stage == 62 && now - last > 1000) {
      click("渐变选区");
      stage = 7;
      last = now;
    } else if (stage == 7 && now - last > 1000) {
      shot("admin-gradient");
      click("应用选区");
      click("保存并同步");
      stage = 70;
      last = now;
    } else if (stage == 70 && mc.screen instanceof AdminScreen screen && now - last > 1000) {
      boolean saved =
          screen.children().stream()
              .anyMatch(w -> w instanceof Theme.Action b && b.detail.contains("runtime_title"));
      if (saved) {
        LogUtils.getLogger()
            .info("MT_ADMIN_SAVE_RUNTIME_PASS: GUI save acknowledged after server commit");
        mc.setScreen(null);
        JsonObject j = new JsonObject();
        j.addProperty("mode", "collection");
        Network.request("open", j);
        stage = 8;
        last = now;
      }
    } else if (stage == 8 && mc.screen instanceof ContainerScreen && now - last > 1000) {
      shot("player-chest");
      mc.player.closeContainer();
      mc.setScreen(null);
      mc.options.keyPlayerList.setDown(true);
      stage = 9;
      last = now;
    } else if (stage == 9 && now - last > 1500) {
      shot("tab-list");
      mc.options.keyPlayerList.setDown(false);
      LogUtils.getLogger()
          .info(
              "MEMORIALTITLES_CLIENT_RUNTIME_PASS: real local connection, display packet, admin"
                  + " forms and chest menu");
      mc.player.connection.sendChat("纪念称号运行测试");
      stage = 10;
      last = now;
    } else if (stage == 10 && now - last > 1500) {
      java.util.UUID id = java.util.UUID.fromString("167c6df2-47a1-42f8-8b75-754232f1bf9d");
      nameplatePlayer =
          new net.minecraft.client.player.RemotePlayer(
              mc.level, new com.mojang.authlib.GameProfile(id, "TitlePreview"));
      nameplatePlayer.moveTo(mc.player.getX(), mc.player.getY(), mc.player.getZ() + 5, 180, 0);
      nameplatePlayer.setNoGravity(true);
      mc.level.addPlayer(9001, nameplatePlayer);
      mc.player.setYRot(0);
      mc.player.yRotO = 0;
      mc.player.setXRot(0);
      mc.player.xRotO = 0;
      ClientRuntime.WORN.put(id, ClientRuntime.WORN.get(mc.player.getUUID()));
      var renderer = mc.getEntityRenderDispatcher().getRenderer(nameplatePlayer);
      var visible =
          net.minecraftforge.fml.util.ObfuscationReflectionHelper.findMethod(
              net.minecraft.client.renderer.entity.EntityRenderer.class,
              "m_6512_",
              net.minecraft.world.entity.Entity.class);
      if (!(boolean) visible.invoke(renderer, nameplatePlayer))
        throw new AssertionError("normal remote nameplate hidden");
      nameplatePlayer.setInvisible(true);
      if ((boolean) visible.invoke(renderer, nameplatePlayer))
        throw new AssertionError("invisible nameplate exposed");
      nameplatePlayer.setInvisible(false);
      LogUtils.getLogger()
          .info(
              "MT_NAMEPLATE_VISIBILITY_PASS: original renderer visibility and invisibility"
                  + " respected");
      stage = 11;
      last = now;
    } else if (stage == 11 && now - last > 1500) {
      shot("head-title");
      if (!ClientRuntime.head) throw new AssertionError("head adapter failed");
      LogUtils.getLogger().info("MEMORIALTITLES_HEAD_RUNTIME_PASS");
      mc.stop();
      stage = 12;
    }
    if (stage >= 2 && stage != 12 && now - last > 90000)
      throw new AssertionError("runtime stage timeout " + stage);
  }

  private static JsonObject object(String key, int val) {
    JsonObject j = new JsonObject();
    j.addProperty(key, val);
    return j;
  }

  private static void click(String label) {
    var mc = Minecraft.getInstance();
    if (mc.screen == null) return;
    for (var w : mc.screen.children())
      if (w instanceof Button b && b.getMessage().getString().equals(label)) {
        double scale = (double) mc.getWindow().getGuiScaledWidth() / mc.screen.width;
        mc.screen.mouseClicked(
            (b.getX() + b.getWidth() / 2d) * scale, (b.getY() + b.getHeight() / 2d) * scale, 0);
        mc.screen.mouseReleased(
            (b.getX() + b.getWidth() / 2d) * scale, (b.getY() + b.getHeight() / 2d) * scale, 0);
        return;
      }
    throw new AssertionError("Missing button " + label);
  }

  private static void shot(String name) throws Exception {
    Path path = Path.of("../build/reports/screenshots/" + name + ".png");
    Files.createDirectories(path.toAbsolutePath().getParent());
    try (var pixels = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
      pixels.writeToFile(path);
    }
    LogUtils.getLogger().info("MT_SCREENSHOT {}", path.toAbsolutePath());
  }
}
