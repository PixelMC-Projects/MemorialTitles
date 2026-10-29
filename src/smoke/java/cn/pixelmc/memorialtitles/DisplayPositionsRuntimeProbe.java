package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.*;
import cn.pixelmc.memorialtitles.service.TitleService;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;




/** Runs only in the dedicated local test source set. Never packaged in the mod. */

public final class DisplayPositionsRuntimeProbe {
  private static final List<String> checks = new ArrayList<>();
  private static final UUID PLAYER = UUID.fromString("192924d9-f626-416a-8ba8-c31d998d40ef");
  private static final long STARTED = System.nanoTime();
  private static int stage;
  private static boolean finished;
  private static long later;
  private static ServerPlayer player;
  private static TitleMenu menu;
  private static CompletableFuture<?> pending;

  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
    checks.add(description); LogUtils.getLogger().info("POSITIONS PASS {}", description);
  }
  private static TitleMenu opened() {
    check(player.containerMenu instanceof TitleMenu, "server opens a real registered chest menu");
    return (TitleMenu) player.containerMenu;
  }
  private static String lore(ItemStack stack) {
    return stack.getOrCreateTagElement("display").getList("Lore", Tag.TAG_STRING).toString();
  }
  private static void finish(ServerRuntime runtime, Throwable error) {
    finished = true;
    try {
      Path report = Path.of("../build/reports/display-positions-runtime.json");
      Files.createDirectories(report.getParent());
      Map<String,Object> result = new LinkedHashMap<>();
      result.put("passed", error == null); result.put("checks", checks);
      if (error != null) result.put("error", error.toString());
      Files.writeString(report, JSON.toJson(result));
    } catch (Exception e) { LogUtils.getLogger().error("Runtime report failed", e); }
    if (error != null) LogUtils.getLogger().error("Display positions runtime verification failed", error);
    else LogUtils.getLogger().info("DISPLAY_POSITIONS_RUNTIME_PASSED {}", checks.size());
    runtime.server.halt(false);
  }

  public static void tick() {
    if (finished) return;
    ServerRuntime runtime = ServerRuntime.current;
    if (runtime == null || runtime.service == null) return;
    try {
      if (System.nanoTime() - STARTED > 120_000_000_000L) throw new AssertionError("runtime timeout");
      if (!runtime.service.healthy()) return;
      if (pending != null) { if (!pending.isDone()) return; pending.join(); pending = null; }
      if (System.nanoTime() < later) return;
      switch (stage++) {
        case 0 -> {
          player = new ServerPlayer(runtime.server, runtime.server.overworld(), new GameProfile(PLAYER, "PositionTest"));
          player.connection = new ServerGamePacketListenerImpl(runtime.server, new Connection(PacketFlow.SERVERBOUND), player);
          TitleMenu.open(player, "collection", PLAYER, "", "order", "all", 0);
          menu = opened();
          check(menu.getSlot(44).getItem().is(MemorialTitles.MENU_DISPLAY.get()), "own collection reserves slot 44 for display locations");
          check(java.util.stream.IntStream.range(0, 44).allMatch(i -> menu.getSlot(i).getItem().is(MemorialTitles.TOKEN.get())), "44 owned titles fit the first page without overwriting controls");
          menu.clicked(52, 0, ClickType.PICKUP, player); menu = opened();
          check(menu.getSlot(0).getItem().is(MemorialTitles.TOKEN.get()) && menu.getSlot(1).getItem().isEmpty(), "45th owned title appears on the next page");
          menu.clicked(44, 0, ClickType.PICKUP, player); menu = opened();
          check(menu.getSlot(20).getItem().is(MemorialTitles.MENU_HEAD_DISPLAY.get())
              && menu.getSlot(22).getItem().is(MemorialTitles.MENU_CHAT_DISPLAY.get())
              && menu.getSlot(24).getItem().is(MemorialTitles.MENU_TAB_DISPLAY.get()), "three controls use their registered standalone menu items");
          check(lore(menu.getSlot(20).getItem()).contains("我的选择：开启"), "new player sees enabled defaults in actual menu lore");
          menu.clicked(20, 0, ClickType.PICKUP, player); later = System.nanoTime() + 500_000_000L;
        }
        case 1 -> {
          check(!runtime.service.snapshot().displayPreferences(PLAYER).head()
              && runtime.service.snapshot().displayPreferences(PLAYER).chat()
              && runtime.service.snapshot().displayPreferences(PLAYER).tab(), "actual click switches only head in persistent service state");
          check(menu.getSlot(20).getItem().getHoverName().getString().contains("关闭"), "async click refresh updates the same menu label");
          Settings s = runtime.service.snapshot().settings();
          pending = runtime.service.settings(TitleService.Actor.consoleActor(), new Settings(s.reward(), s.head(), false, s.tab(), s.headScale(), s.headOffset(), s.chatPrefix(), s.retentionDays(), s.packName(), s.packStartedOn()));
        }
        case 2 -> {
          menu.refresh();
          check(lore(menu.getSlot(22).getItem()).contains("管理员已关闭")
              && runtime.service.snapshot().displayPreferences(PLAYER).chat(), "administrator denial is explained while preserving personal enabled choice");
          ServerPlayer stranger = new ServerPlayer(runtime.server, runtime.server.overworld(), new GameProfile(UUID.randomUUID(), "OtherPlayer"));
          menu.clicked(24, 0, ClickType.PICKUP, stranger);
          check(runtime.service.snapshot().displayPreferences(PLAYER).tab(), "different player cannot activate another player's menu");
          TitleMenu.open(player, "display", stranger.getUUID(), "", "order", "all", 0);
          check(player.containerMenu == menu, "display settings cannot open for another UUID");
          menu.clicked(45, 0, ClickType.PICKUP, player); menu = opened();
          check(lore(menu.getSlot(0).getItem()).contains("称号详情保留"), "title details remain intact on returning to collection");
          finish(runtime, null);
        }
        default -> throw new AssertionError("unexpected runtime stage");
      }
    } catch (Throwable error) { finish(runtime, error); }
  }
}
