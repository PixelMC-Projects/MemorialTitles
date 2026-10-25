package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.client.AvatarCapture;
import cn.pixelmc.memorialtitles.client.ClientRuntime;
import cn.pixelmc.memorialtitles.forge.Network;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MemorialTitles.ID, value = Dist.CLIENT)
public final class WebVisualProbe {
  private static int phase;
  private static long since = System.currentTimeMillis(), captureAt;
  private static boolean diagnosed;

  @SubscribeEvent
  public static void tick(TickEvent.ClientTickEvent event) throws Exception {
    if (!Boolean.getBoolean("mt.webRuntime") || event.phase != TickEvent.Phase.END) return;
    Minecraft mc = Minecraft.getInstance();
    long now = System.currentTimeMillis();
    if (now - since > 120000) throw new IllegalStateException("Web runtime client sync timed out");
    if (phase == 0 && mc.screen instanceof AccessibilityOnboardingScreen) {
      mc.screen.onClose();
      return;
    }
    if (phase == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
      mc.options.guiScale().set(2);
      mc.resizeDisplay();
      ConnectScreen.startConnecting(
          new TitleScreen(),
          mc,
          new ServerAddress("127.0.0.1", 45569),
          new ServerData("Web runtime verification", "127.0.0.1:45569", false),
          false);
      phase = 1;
      return;
    }
    if (phase == 1 && !diagnosed && now - since > 25000) {
      diagnosed = true;
      Path file = Path.of("../docs/screenshots/web-runtime-connection.png");
      Files.createDirectories(file.getParent());
      try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
        shot.writeToFile(file);
      }
      LogUtils.getLogger()
          .info(
              "WEB_CONNECTION_STATE screen={} title={} connection={}",
              mc.screen == null ? "none" : mc.screen.getClass().getSimpleName(),
              mc.screen == null ? "none" : mc.screen.getTitle().getString(),
              mc.getConnection());
    }
    if (mc.player == null || mc.getConnection() == null) return;
    var rich = ClientRuntime.WORN.get(mc.player.getUUID());
    if (phase == 1
        && rich != null
        && rich.plain().equals("网页同步前")
        && AvatarCapture.lastSent() != null) {
      Files.write(Path.of("../docs/screenshots/web-runtime-avatar.png"), AvatarCapture.lastSent());
      LogUtils.getLogger().info("WEB_CLIENT_CONNECTED");
      phase = 2;
    }
    if (phase == 2
        && rich != null
        && rich.plain().equals("网页同步完成")
        && Math.abs(ClientRuntime.scale - 1.25f) < .001) {
      var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
      if (info == null
          || info.getTabListDisplayName() == null
          || !info.getTabListDisplayName().getString().contains("网页同步完成")) return;
      if (rich.glyphs().get(0).color() != 0x67e8f9)
        throw new AssertionError("gradient did not reach real client");
      mc.setScreen(null);
      mc.options.keyPlayerList.setDown(true);
      captureAt = now + 1200;
      phase = 3;
    }
    if (phase == 3 && now > captureAt) {
      Path file = Path.of("../docs/screenshots/web-runtime-client.png");
      Files.createDirectories(file.getParent());
      try (var shot = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
        shot.writeToFile(file);
      }
      mc.options.keyPlayerList.setDown(false);
      var data = new com.google.gson.JsonObject();
      data.addProperty("mode", "collection");
      Network.request("open", data);
      phase = 4;
    }
    if (phase == 4
        && mc.screen instanceof net.minecraft.client.gui.screens.inventory.ContainerScreen) {
      boolean gold = false;
      for (var slot : mc.player.containerMenu.slots) {
        var stack = slot.getItem();
        if (!stack.getHoverName().getString().equals("网页同步完成")) continue;
        var lore = stack.getOrCreateTagElement("display").getList("Lore", 8);
        for (int i = 0; i < lore.size(); i++) {
          var line = net.minecraft.network.chat.Component.Serializer.fromJson(lore.getString(i));
          if (line.getString().startsWith("ID："))
            throw new AssertionError("Internal ID still shown in player lore");
          if (line.getString().equals("已绝版"))
            gold =
                line.getStyle().getColor() != null
                    && line.getStyle().getColor().getValue() == 0xffaa00;
        }
      }
      if (!gold) throw new AssertionError("Retired title did not have gold lore");
      LogUtils.getLogger()
          .info(
              "MEMORIALTITLES_WEB_CLIENT_PASS display+settings+actualTAB+goldLore+fallbackAvatar");
      phase = 5;
      mc.stop();
    }
  }
}
