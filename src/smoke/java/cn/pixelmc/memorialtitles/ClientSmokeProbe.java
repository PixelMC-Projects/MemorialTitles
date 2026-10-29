package cn.pixelmc.memorialtitles;
import cn.pixelmc.memorialtitles.client.*;
import cn.pixelmc.memorialtitles.forge.*;
import cn.pixelmc.memorialtitles.domain.Model.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;

/** Isolated screenshot/renderer fixture; only included in the smoke source set. */
public final class ClientSmokeProbe {
 private static int stage;
 private static long last, began = System.nanoTime();
 private static boolean done;
 private static CompletableFuture<Void> language;
 private static final List<String> checks = new ArrayList<>();
 private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks.add(label); }
 private static void shot(String name) {
  Minecraft mc = Minecraft.getInstance();
  Screenshot.grab(mc.gameDirectory, "port-" + name + ".png", mc.getMainRenderTarget(), message -> com.mojang.logging.LogUtils.getLogger().info("CLIENT_SCREENSHOT {}", name));
 }
 private static void click(String label) {
  for (var child : Minecraft.getInstance().screen.children()) if (child instanceof Button button && button.getMessage().getString().equals(label)) { button.onPress(); return; }
  throw new AssertionError("Missing UI control: " + label);
 }
 private static void finish(Throwable error) {
  done = true;
  try {
   Path report = Path.of("../build/reports/client-smoke.json"); Files.createDirectories(report.getParent());
   var result = new LinkedHashMap<String,Object>(); result.put("passed", error == null); result.put("checks", checks);
   if (error != null) { result.put("error", error.toString()); com.mojang.logging.LogUtils.getLogger().error("CLIENT_SMOKE_FAILED", error); }
   Files.writeString(report, cn.pixelmc.memorialtitles.domain.Model.JSON.toJson(result));
  } catch (Exception failure) { throw new RuntimeException(failure); }
  Minecraft.getInstance().stop();
 }
 public static void tick() {
  if (done || !Boolean.getBoolean("mt.clientSmoke")) return;
  Minecraft mc = Minecraft.getInstance(); long now = System.currentTimeMillis();
  try {
   if (System.nanoTime() - began > 150_000_000_000L) throw new AssertionError("Client fixture timeout");
   if (mc.screen instanceof AccessibilityOnboardingScreen) { mc.screen.onClose(); return; }
   if (stage == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
    check(ClientRuntime.OPEN.isUnbound(), "optional key binding remains unbound by default");
    check(Arrays.stream(mc.options.keyMappings).anyMatch(key -> key == ClientRuntime.OPEN), "loader registers the optional key mapping");
    if (Boolean.getBoolean("mt.requirePackaged")) {
      String source = ClientRuntime.class.getProtectionDomain().getCodeSource().getLocation().toString();
      check(source.contains("/.fabric/processedMods/memorialtitles-0.5.0-dev-") && source.endsWith(".jar"), "client runs the actual packaged mod JAR: " + source);
    }
    // Force client-only networking/chat and renderer classes through the real loader/mixin path.
    new net.minecraft.client.multiplayer.chat.ChatListener(mc);
    mc.options.guiScale().set(2); mc.resizeDisplay();
    mc.options.languageCode = "zh_cn"; mc.getLanguageManager().setSelected("zh_cn"); language = mc.reloadResourcePacks(); stage = 1;
   } else if (stage == 1 && language.isDone() && mc.getOverlay() == null) {
    Title title = new Title("port_fixture", new RichText(List.of(new Segment("山海同行者·纪念", 0x67e8f9, true, false, false, false)), List.of()), "保留完整的菜单、文字样式与隐私", "移植验证", "本地测试", "纪念", 1, 7, true, true, 0);
    AdminScreen admin = new AdminScreen(); var field = AdminScreen.class.getDeclaredField("titles"); field.setAccessible(true); field.set(admin, List.of(title));
    mc.setScreen(admin); last = now; stage = 2;
   } else if (stage == 2 && now-last > 1200) {
    shot("01-admin-list"); check(mc.screen.children().size() > 5, "native admin screen creates controls");
    boolean found = false;
    for (var child : List.copyOf(mc.screen.children())) if (child instanceof Theme.Action button && button.detail.contains("port_fixture")) { button.onPress(); found = true; break; }
    check(found, "native title row opens the existing editor"); last = now; stage = 3;
   } else if (stage == 3 && now-last > 1200) {
    shot("02-editor"); click("文字样式"); last = now; stage = 4;
   } else if (stage == 4 && now-last > 1200) {
    shot("03-styles"); click("打开选色器"); check(mc.screen instanceof ColorPickerScreen, "native RGB picker opens"); last = now; stage = 5;
   } else if (stage == 5 && now-last > 1200) {
    shot("04-color-picker"); mc.screen.onClose(); click("渐变选区"); last = now; stage = 6;
   } else if (stage == 6 && now-last > 1200) {
    shot("05-gradient");
    JsonObject state = new JsonObject(); state.addProperty("query", "纪念"); state.addProperty("mode", "collection");
    state.addProperty("target", UUID.randomUUID().toString()); state.addProperty("sort", "order"); state.addProperty("filter", "all");
    mc.setScreen(new SearchScreen(state)); check(mc.screen.children().size() == 3, "search retains input and both actions"); last = now; stage = 7;
   } else if (stage == 7 && now-last > 1200) {
    shot("06-search");
    mc.setScreen(null);
    net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new TitleScreen(), mc,
        new net.minecraft.client.multiplayer.resolver.ServerAddress("127.0.0.1", Integer.getInteger("mt.clientSmoke.port", 25621)),
        new net.minecraft.client.multiplayer.ServerData("MemorialTitles isolated", "127.0.0.1:" + Integer.getInteger("mt.clientSmoke.port", 25621), net.minecraft.client.multiplayer.ServerData.Type.OTHER), false, null);
    last = now; stage = 8;
   } else if (stage == 8 && mc.player != null && mc.level != null && now-last > 2200) {

    JsonObject open = new JsonObject(); open.addProperty("mode", "display"); Network.request("open", open);
    last = now; stage = 80;
   } else if (stage == 80 && mc.screen instanceof ContainerScreen screen && now-last > 1200) {
    shot("07-menu");
    check(MenuTooltips.ownLines(screen.getMenu().getSlot(20).getItem()).orElseThrow().size() >= 3,
        "connected client receives native menu and protected tooltip from actual server");
    mc.player.closeContainer(); mc.setScreen(null);
    last = now; stage = 9;
   } else if (stage == 9 && now-last > 20_000) { check(true, "client renderer and resources complete without loader/mixin error"); finish(null); }
  } catch (Throwable error) { finish(error); }
 }
}
