package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.*;
import cn.pixelmc.memorialtitles.service.TitleService;
import com.mojang.logging.LogUtils;
import java.util.*;
import net.minecraft.commands.CommandSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.minecraft.world.inventory.ClickType;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Test source set only. Never packaged in the release mod. */
@Mod.EventBusSubscriber(modid = MemorialTitles.ID)
public final class RuntimeProbe {
  private static boolean started, finished;
  private static long readyAt;
  private static ServerPlayer tester;

  @SubscribeEvent
  public static void tick(TickEvent.ServerTickEvent e) {
    if (e.phase != TickEvent.Phase.END) return;
    ServerRuntime r = ServerRuntime.current;
    if (r == null || r.service == null || !r.service.healthy()) return;
    if (!started && !r.server.getPlayerList().getPlayers().isEmpty()) {
      started = true;
      tester = r.server.getPlayerList().getPlayers().get(0);
      UUID id = tester.getUUID();
      var console = TitleService.Actor.consoleActor();
      require(Commands.realConsole(r.server.createCommandSourceStack()), "actual console accepted");
      require(
          !Commands.realConsole(r.server.createCommandSourceStack().withSource(CommandSource.NULL)),
          "entityless privileged foreign source rejected");
      require(
          !Commands.realConsole(new RconConsoleSource(r.server).createCommandSourceStack()),
          "RCON is not actual console");
      require(
          !Commands.realConsole(r.server.getFunctions().getGameLoopSender()),
          "tick function cannot authorize");
      require(
          !Commands.realConsole(tester.createCommandSourceStack().withPermission(4)),
          "OP/player source cannot authorize");
      require(
          !r.server.getPlayerList().isOp(tester.getGameProfile()),
          "test administrator is a non-OP player");
      require(
          ServerRuntime.isFake(FakePlayerFactory.getMinecraft(r.server.overworld())),
          "Forge fake player excluded");
      try {
        Class<?> type =
            Class.forName("dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake");
        require(ServerPlayer.class.isAssignableFrom(type), "actual Curtain fake class verified");
      } catch (ClassNotFoundException ignored) {
      }
      Title title =
          new Title(
              "runtime_title",
              new RichText(
                  List.of(new Segment("山海同行者·第一季", 0xffffff, true, false, false, false)),
                  List.of(new Gradient(List.of(0, 1, 2, 3, 4), 0x67e8f9, 0xc084fc))),
              "第一季共同走过的旅程\n纪念我们的相遇。",
              "香草纪元",
              "管理员纪念发放",
              "纪念",
              1,
              7,
              false,
              true,
              0);
      r.service
          .login(id, tester.getGameProfile().getName())
          .thenCompose(
              v ->
                  r.service.saveTitle(
                      console,
                      title,
                      r.service.snapshot().titles().containsKey(title.id())
                          ? r.service.snapshot().titles().get(title.id()).revision()
                          : 0))
          .thenCompose(v -> r.service.authorize(console, id, true))
          .thenCompose(v -> r.service.grant(console, List.of(id), title.id(), "runtime probe"))
          .thenCompose(v -> r.service.privacy(id, title.id(), false, true))
          .thenCompose(v -> r.service.equip(id, title.id()))
          .thenCompose(v -> Boolean.getBoolean("mt.gallery") ? galleryData(r,id) : java.util.concurrent.CompletableFuture.completedFuture(null))
          .whenComplete(
              (v, error) ->
                  r.server.execute(
                      () -> {
                        if (error != null) throw new IllegalStateException(error);
                        if(Boolean.getBoolean("mt.gallery")) {
                          r.server.overworld().setDayTime(6000);
                          r.server.overworld().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false,r.server);
                          r.server.overworld().setWeatherParameters(60000,0,false,false);
                        }
                        readyAt = System.currentTimeMillis() + 2000;
                        finished = true;
                        LogUtils.getLogger().info("MT_RUNTIME_READY {}", id);
                      }));
    }
    if (finished && readyAt == 0 && r.server.getPlayerList().getPlayers().isEmpty())
      r.server.halt(false);
    if (finished && readyAt > 0 && System.currentTimeMillis() > readyAt) {
      readyAt = 0;
      TitleMenu.open(tester, "collection", tester.getUUID(), "", "order", "all", 0);
      TitleMenu menu = (TitleMenu) tester.containerMenu;
      for (ClickType type : ClickType.values())
        menu.clicked(type == ClickType.QUICK_MOVE ? 1 : 0, 2, type, tester);
      require(
          menu.getCarried().isEmpty() && tester.getInventory().isEmpty(),
          "all chest click modes cannot extract display token");
      tester.closeContainer();
      try {
        Class<?> apiType = Class.forName("me.neznamy.tab.api.TabAPI"),
            tpType = Class.forName("me.neznamy.tab.api.TabPlayer"),
            fm = Class.forName("me.neznamy.tab.api.tablist.TabListFormatManager");
        Object api = apiType.getMethod("getInstance").invoke(null),
            tp = apiType.getMethod("getPlayer", UUID.class).invoke(api, tester.getUUID()),
            mgr = apiType.getMethod("getTabListFormatManager").invoke(api);
        String suffix = (String) fm.getMethod("getCustomPrefix", tpType).invoke(mgr, tp);
        require(
            suffix != null && suffix.contains("山"),
            "actual TAB 5.2.1 contains title prefix before name");
      } catch (ClassNotFoundException ignored) {
      } catch (Exception error) {
        throw new IllegalStateException(error);
      }
      LogUtils.getLogger().info("MEMORIALTITLES_FORGE_RUNTIME_PASS");
    }
  }

  private static void require(boolean value, String name) {
    if (!value) throw new AssertionError(name);
    LogUtils.getLogger().info("MT_RUNTIME_PASS {}", name);
  }

  private static java.util.concurrent.CompletableFuture<Void> galleryData(ServerRuntime r, UUID owner) {
    var console=TitleService.Actor.consoleActor();
    UUID alex=UUID.fromString("f0063548-23c3-41f9-8e39-42801f495193"),mira=UUID.fromString("e8bc2f83-6116-480e-b619-82880bfc43dc");
    var future=r.service.login(alex,"Alex").thenCompose(v->r.service.login(mira,"Mira"));
    String[][] data={{"launch_memory","启程之约","当第一缕晨光照亮新的世界。","首登纪念"},{"architect","方寸筑梦","把想象搭成可以回家的地方。","建设纪念"},{"old_companion","旧日旅伴","有些旅程已经结束，同行的记忆仍在。","历史纪念"},{"sea_visitor","远海来信","从海风抵达的方向，继续下一段旅程。","活动纪念"}};
    int[] colors={0xe4ba76,0x75d6c2,0xc4a1ef,0x71b8ea};
    for(int n=0;n<data.length;n++) {
      String[] d=data[n];int color=colors[n];
      var title=new Title(d[0],new RichText(List.of(new Segment(d[1],color,false,false,false,false)),List.of()),d[2],"本地截图演示",d[3],"纪念",n+2,0,true,true,0);
      future=future.thenCompose(v->r.service.saveTitle(console,title,r.service.snapshot().titles().containsKey(title.id())?r.service.snapshot().titles().get(title.id()).revision():0));
      if(!d[0].equals("sea_visitor"))future=future.thenCompose(v->r.service.grant(console,List.of(owner,alex),title.id(),"截图演示资料")).thenApply(v->null);
    }
    return future.thenCompose(v->r.service.grant(console,List.of(alex),"runtime_title","公开隐藏称号演示"))
      .thenCompose(v->r.service.privacy(alex,"runtime_title",true,true))
      .thenCompose(v->r.service.privacy(alex,"launch_memory",true,true))
      .thenCompose(v->r.service.privacy(alex,"architect",true,true))
      .thenCompose(v->{Title t=r.service.snapshot().titles().get("old_companion");return r.service.saveTitle(console,new Title(t.id(),t.name(),t.description(),t.source(),t.acquisition(),t.category(),t.order(),t.icon(),true,false,t.revision()),t.revision());})
      .thenCompose(v->{var s=r.service.snapshot().settings();return r.service.settings(console,new Settings("launch_memory",s.head(),s.chat(),s.tab(),s.headScale(),s.headOffset(),s.chatPrefix(),s.retentionDays()));});
  }
}
