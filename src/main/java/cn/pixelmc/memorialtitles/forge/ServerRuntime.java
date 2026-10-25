package cn.pixelmc.memorialtitles.forge;

import cn.pixelmc.memorialtitles.api.MemorialTitlesApi;
import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.*;
import com.mojang.logging.LogUtils;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.*;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.*;
import net.minecraftforge.eventbus.api.*;

public final class ServerRuntime {
  public static volatile ServerRuntime current;
  public TitleService service;
  public ServerFiles files;
  public MinecraftServer server;
  public cn.pixelmc.memorialtitles.web.AdminWebServer web;
  private volatile Set<UUID> online = Set.of();
  private long nextRefresh, nextBackup;
  private boolean refreshing, backingUp;
  private final TabAdapter tab = new TabAdapter();
  private long nextTab;
  private long nextReminderCheck = System.nanoTime();
  private final Map<UUID, String> pendingLogins = new HashMap<>();
  private static final long JOIN_NOTIFICATION_DELAY = TimeUnit.SECONDS.toNanos(10);
  private final Map<UUID, NotificationSession> notificationSessions = new HashMap<>();

  private static final class NotificationSession {
    final ServerPlayer player;
    final long readyAfter;
    long pollAfter;
    boolean polling;
    final UnequippedTitleReminder reminder;

    NotificationSession(ServerPlayer player, long now) {
      this.player = player;
      reminder = new UnequippedTitleReminder(now);
      readyAfter = now + JOIN_NOTIFICATION_DELAY;
      pollAfter = readyAfter;
    }
  }

  @SubscribeEvent
  public void starting(ServerStartedEvent e) {
    current = this;
    server = e.getServer();
    try{Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi").getMethod("onCallback",String.class,java.util.function.BiConsumer.class).invoke(null,"memorialtitles",(java.util.function.BiConsumer<String,String>)(op,json)->{var runtime=cn.pixelmc.memorialtitles.forge.ServerRuntime.current;if(runtime!=null&&op.equals("avatarRequest")){var data=com.google.gson.JsonParser.parseString(json).getAsJsonObject();var player=runtime.server.getPlayerList().getPlayer(java.util.UUID.fromString(data.get("player").getAsString()));if(player!=null)cn.pixelmc.memorialtitles.forge.Network.reply(player,"avatarRequest",data.get("nonce").getAsString());}});}catch(ReflectiveOperationException ignored){}
    try {
      files = new ServerFiles(server.getServerDirectory().toPath().toAbsolutePath());
      service = files.config.remote() ? TitleService.remoteService() : new TitleService(new Database(files.config.database()));
      MemorialTitlesApi.attach(service);
      service.listen(
          s ->
              server.execute(
                  () -> {
                    long noticeNow = System.nanoTime();
                    for (NotificationSession session : notificationSessions.values())
                      session.pollAfter = Math.min(session.pollAfter,
                          Math.max(noticeNow, session.readyAfter));
                    Network.syncDisplays(this);
                    tab.update(this);
                    for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                      p.refreshTabListName();
                      if (p.containerMenu instanceof TitleMenu menu) menu.refresh();
                    }
                  }));
      try {
        var config = cn.pixelmc.memorialtitles.web.WebConfig.load(files.folder);
        if (config.enabled() && !files.config.remote()) {
          web =
              new cn.pixelmc.memorialtitles.web.AdminWebServer(
                  service,
                  new cn.pixelmc.memorialtitles.web.AdminWebServer.Host() {
                    public Set<UUID> online() {
                      return online;
                    }

                    public void mirror() {
                      saveDefinitions();
                    }

                    public CompletableFuture<Path> backup(TitleService.Actor actor) {
    if(service.remote())return service.remoteBackup(actor);
                      return ServerRuntime.this.backup(actor);
                    }

                    public CompletableFuture<Void> reload(TitleService.Actor actor) {
    if(service.remote())return service.remoteReload(actor);
                      return ServerRuntime.this.reload(actor);
                    }

                    public void requestAvatar(UUID id, String nonce) {
                      server.execute(
                          () -> {
                            ServerPlayer p = server.getPlayerList().getPlayer(id);
                            if (p != null && !isFake(p)) Network.reply(p, "avatarRequest", nonce);
                          });
                    }
                  },
                  config,
                  files.folder,
                  Path.of(files.config.backups()));
          LogUtils.getLogger()
              .info("MemorialTitles 网页管理台已启动：{}（账号由 mtconsole web-user 创建）", web.address());
        }
      } catch (Exception | LinkageError webError) {
        LogUtils.getLogger().error("MemorialTitles 网页服务启动失败，游戏和称号服务继续运行", webError);
      }
      refresh();
    } catch (Exception error) {
      LogUtils.getLogger().error("MemorialTitles 配置无效，称号服务未启动（游戏继续运行）", error);
    }
    nextBackup = System.currentTimeMillis() + 60000;
  }

  @SubscribeEvent
  public void stopping(ServerStoppingEvent e) {
    notificationSessions.clear();
    if (web != null) web.close();
    if (service != null) service.close();
    MemorialTitlesApi.attach(null);
    current = null;
  }

  @SubscribeEvent
  public void register(RegisterCommandsEvent e) {
    Commands.register(e.getDispatcher());
  }

  @SubscribeEvent(priority = EventPriority.LOWEST)
  public void login(PlayerEvent.PlayerLoggedInEvent e) {
    if (!(e.getEntity() instanceof ServerPlayer p) || isFake(p)) return;
    if(files!=null && files.config.remote() && !RemoteGateway.admitted(p.getUUID())) return;
    acceptedLogin(p);
  }

  private void acceptedLogin(ServerPlayer p) {
    var joined = new HashSet<>(online);
    joined.add(p.getUUID());
    online = Set.copyOf(joined);
    notificationSessions.put(p.getUUID(), new NotificationSession(p, System.nanoTime()));
    pendingLogins.put(p.getUUID(), p.getGameProfile().getName());
    tryLogin(p.getUUID(), p.getGameProfile().getName());
    if (service != null) Network.syncDisplays(this);
  }

  @SubscribeEvent
  public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
    notificationSessions.remove(e.getEntity().getUUID());
    pendingLogins.remove(e.getEntity().getUUID());
    if (web != null) web.avatars.logout(e.getEntity().getUUID());
    var left = new HashSet<>(online);
    left.remove(e.getEntity().getUUID());
    online = Set.copyOf(left);
    Network.forget(e.getEntity().getUUID());
  }

  public static boolean isFake(ServerPlayer p) {
    if (p instanceof FakePlayer) return true;
    for (Class<?> type = p.getClass(); type != null; type = type.getSuperclass())
      if (Set.of(
              "dev.dubhe.curtain.features.player.patches.EntityPlayerMPFake",
              "carpet.patches.EntityPlayerMPFake")
          .contains(type.getName())) return true;
    return false;
  }

  private void tryLogin(UUID id, String name) {
    if (service == null || !service.healthy()) return;
    service
        .login(id, name)
        .whenComplete(
            (v, error) ->
                server.execute(
                    () -> {
                      if (error == null) {
                        pendingLogins.remove(id);
                        ServerPlayer p = server.getPlayerList().getPlayer(id);
                        if (p != null) {
                          if (web != null) web.avatars.login(id);
                          Network.syncDisplays(this);
                        }
                      }
                    }));
  }

  private void refresh() {
    if (service == null || refreshing) return;
    refreshing = true;
    boolean wasHealthy = service.healthy();
    service
        .reconnect()
        .whenComplete(
            (v, error) ->
                server.execute(
                    () -> {
                      refreshing = false;
                      if (error != null) {
                        LogUtils.getLogger()
                            .warn("MemorialTitles 数据库暂不可用，30秒后重连：{}", safeError(error));
                        return;
                      }
                      if (!wasHealthy)
                        LogUtils.getLogger()
                            .info(
                                "MemorialTitles 数据库已连接，{} 个称号，{} 名玩家",
                                service.snapshot().titles().size(),
                                service.snapshot().players().size());
                      if (!Files.exists(files.folder.resolve("definitions.json")))
                        service.background(
                            () -> {
                              files.exportDefinitions(service.snapshot());
                              return null;
                            });
                      new HashMap<>(pendingLogins).forEach(this::tryLogin);
                    }));
  }

  @SubscribeEvent
  public void tick(TickEvent.ServerTickEvent e) {
    if (e.phase != TickEvent.Phase.END || server == null || service == null) return;
    long now = System.currentTimeMillis();
    long monotonicNow = System.nanoTime();
    pollNotifications(monotonicNow);
    remindUnequipped(monotonicNow);
    if (now >= nextTab) {
      nextTab = now + 1000;
      tab.update(this);
    }
    if (now >= nextRefresh) {
      nextRefresh = now + (service.remote()?2000:30000);
      refresh();
    }
    if (!service.remote() && now >= nextBackup && !backingUp && service.healthy()) {
      backingUp = true;
      backup(TitleService.Actor.consoleActor())
          .whenComplete(
              (p, error) ->
                  server.execute(
                      () -> {
                        backingUp = false;
                        if (error == null)
                          nextBackup =
                              LocalDate.now()
                                  .plusDays(1)
                                  .atTime(4, 0)
                                  .atZone(ZoneId.systemDefault())
                                  .toInstant()
                                  .toEpochMilli();
                        else {
                          nextBackup = System.currentTimeMillis() + 60000;
                          LogUtils.getLogger().warn("MemorialTitles 自动备份失败：{}", safeError(error));
                        }
                      }));
    }
  }

  public CompletableFuture<Path> backup(TitleService.Actor actor) {
    if(service.remote())return service.remoteBackup(actor);
    return service
        .export(actor)
        .thenCompose(
            a ->
                service.background(
                    () -> {
                      Path folder = Path.of(files.config.backups());
                      Path p = Backup.write(folder, a);
                      Backup.retain(folder, a.settings().retentionDays());
                      return p;
                    }));
  }

  private void pollNotifications(long now) {
    if (!service.healthy()) return;
    for (var entry : notificationSessions.entrySet()) {
      UUID id = entry.getKey();
      NotificationSession session = entry.getValue();
      if (session.polling || now - session.pollAfter < 0) continue;
      session.polling = true;
      service.notifications(id).whenComplete((messages, error) -> server.execute(() -> {
        if (!currentSession(id, session)) return;
        if (error != null) {
          finishNotificationPoll(session, 30);
          return;
        }
        for (PlayerNotice message : messages) {
          Title latest = service.snapshot().titles().get(message.title());
          session.player.sendSystemMessage(Text.notification(
              latest == null ? message.name() : latest.name(), message.granted()));
        }
        if (messages.isEmpty()) {
          finishNotificationPoll(session, 30);
          return;
        }
        service.acknowledgeNotifications(id, messages).whenComplete((ignored, ackError) ->
            server.execute(() -> {
              if (currentSession(id, session))
                finishNotificationPoll(session, ackError == null ? 1 : 30);
            }));
      }));
    }
  }

  private void remindUnequipped(long now) {
    if (now - nextReminderCheck < 0) return;
    nextReminderCheck = now + TimeUnit.SECONDS.toNanos(1);
    if (!service.healthy()) return;
    Snapshot snapshot = service.snapshot();
    for (var entry : notificationSessions.entrySet()) {
      UUID id = entry.getKey();
      NotificationSession session = entry.getValue();
      // Admission and the persisted player preferences must be confirmed before any reminder.
      if (!currentSession(id, session) || pendingLogins.containsKey(id)
          || !snapshot.players().containsKey(id) || !session.reminder.claim(now)) continue;
      if (UnequippedTitleReminder.shouldRemind(snapshot, id))
        session.player.sendSystemMessage(Text.unequippedReminder());
    }
  }

  private boolean currentSession(UUID id, NotificationSession session) {
    return current == this && notificationSessions.get(id) == session
        && server.getPlayerList().getPlayer(id) == session.player;
  }

  private static void finishNotificationPoll(NotificationSession session, int delaySeconds) {
    session.polling = false;
    session.pollAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(delaySeconds);
  }

  public CompletableFuture<Void> reload(TitleService.Actor actor) {
    if(service.remote())return service.remoteReload(actor);
    return service
        .background(() -> files.readDefinitions())
        .thenCompose(d -> service.reload(actor, d.titles(), d.settings()));
  }

  public void saveDefinitions() {
    service
        .background(
            () -> {
              files.exportDefinitions(service.snapshot());
              return null;
            })
        .exceptionally(
            e -> {
              LogUtils.getLogger().warn("称号已写入数据库，但配置镜像写入失败：{}", safeError(e));
              return null;
            });
  }

  @SubscribeEvent
  public void tabName(PlayerEvent.TabListNameFormat e) {
    if (service == null || tab.active() || !service.snapshot().settings().tab()) return;
    Title t = service.snapshot().displayed(e.getEntity().getUUID(), DisplayPosition.TAB);
    if (t != null)
      e.setDisplayName(
          Component.literal("[")
              .append(Text.render(t.name()))
              .append("] ")
              .append(
                  e.getDisplayName() == null
                      ? e.getEntity().getDisplayName()
                      : e.getDisplayName()));
  }

  public static String safeError(Throwable error) {
    while (error instanceof CompletionException || error instanceof ExecutionException)
      error = error.getCause();
    if (error instanceof java.sql.SQLException) return "数据库连接/事务失败（详情请检查数据库服务与服务端配置）";
    String msg = error.getMessage();
    return msg == null ? error.getClass().getSimpleName() : msg;
  }
}
