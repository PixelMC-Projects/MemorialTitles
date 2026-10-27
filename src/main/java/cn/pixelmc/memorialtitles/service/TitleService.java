package cn.pixelmc.memorialtitles.service;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.ChangeJournal;
import cn.pixelmc.memorialtitles.persistence.Database;
import com.google.gson.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class TitleService implements AutoCloseable {
  public record Actor(UUID player, String name, boolean console, boolean trustedApi) {
    public static Actor player(UUID id) {
      return new Actor(id, id.toString(), false, false);
    }

    public static Actor consoleActor() {
      return new Actor(null, "console", true, false);
    }

    public static Actor api(String modid) {
      return new Actor(null, "api:" + modid, false, true);
    }

    String auditName() {
      return player == null ? name : player.toString();
    }
  }

  private final Database db;
  private final RemoteGateway remote;
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(128),
          r -> {
            Thread t = new Thread(r, "MemorialTitles-DB");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final List<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<>();
  private volatile Snapshot snapshot = Snapshot.empty();
  private volatile boolean healthy;
  private volatile long lastSuccess;
  private volatile ChangeJournal.Assets assets = ChangeJournal.NO_ASSETS;
  private volatile Predicate<UUID> online = id -> false;
  private volatile Function<JsonObject, JsonObject> platform;
  private volatile Map<UUID, Profile> directory = Map.of();
  private volatile long directoryExpires;

  /** Binds the Manager-owned identity directory; null preserves standalone operation. */
  public void attachPlatform(Function<JsonObject, JsonObject> value) {
    platform = value;
    directory = Map.of();
    directoryExpires = 0;
  }

  public boolean managed() {
    return platform != null;
  }

  private JsonObject platform(String op, UUID player) {
    JsonObject request = new JsonObject();
    request.addProperty("op", op);
    if (player != null) request.addProperty("player", player.toString());
    JsonObject response =
        Objects.requireNonNull(platform.apply(request), "Manager response missing");
    if (response.has("error")) throw new IllegalStateException(response.get("error").getAsString());
    return response;
  }

  private static Profile profile(JsonObject value) {
    return new Profile(
        UUID.fromString(value.get("id").getAsString()),
        value.get("name").getAsString(),
        timestamp(value, "firstJoinedAt"),
        timestamp(value, "lastSeen"));
  }

  private static long timestamp(JsonObject value, String key) {
    return !value.has(key) || value.get(key).isJsonNull() ? 0 : value.get(key).getAsLong();
  }

  private Map<UUID, Profile> directory() {
    if (!managed()) return Map.of();
    long now = System.nanoTime();
    if (directoryExpires - now > 0) return directory;
    synchronized (this) {
      if (directoryExpires - now > 0) return directory;
      Map<UUID, Profile> next = new HashMap<>();
      for (JsonElement item : platform("profiles.list", null).getAsJsonArray("players")) {
        Profile p = profile(item.getAsJsonObject());
        if (next.put(p.id(), p) != null)
          throw new IllegalStateException("Manager directory contains duplicate UUID");
      }
      directory = Map.copyOf(next);
      directoryExpires = now + TimeUnit.SECONDS.toNanos(1);
      return directory;
    }
  }

  private Snapshot project(Snapshot source) {
    if (!managed()) return source;
    // Legacy rows are FK/business caches, never the authority for current names or avatars.
    Map<UUID, Profile> players = new HashMap<>(source.players());
    players.putAll(directory());
    return new Snapshot(
        source.titles(),
        players,
        source.owned(),
        source.equipped(),
        source.admins(),
        source.receipts(),
        source.settings(),
        source.displayPreferences());
  }

  private Profile globalProfile(UUID id) {
    JsonElement value = platform("profiles.get", id).get("profile");
    if (value == null || value.isJsonNull())
      throw new IllegalArgumentException("玩家未在 Manager 登记: " + id);
    return profile(value.getAsJsonObject());
  }

  public void attachPlayerAssets(ChangeJournal.Assets value, Predicate<UUID> online) {
    this.assets = value;
    this.online = online;
  }

  public TitleService(Database db) {
    this.db = db;
    this.remote = null;
  }

  private TitleService(RemoteGateway gateway) {
    this.db = null;
    this.remote = gateway;
  }

  public static TitleService remoteService() {
    return new TitleService(new RemoteGateway());
  }

  public boolean remote() {
    return remote != null;
  }

  private <T> CompletableFuture<T> remoteCall(
      String method, java.lang.reflect.Type type, Object... arguments) {
    JsonArray args = new JsonArray();
    for (Object argument : arguments) args.add(JSON.toJsonTree(argument));
    return remote
        .call(method, args)
        .handle(
            (response, error) -> {
              if (error != null) {
                healthy = false;
                throw new CompletionException(error);
              }
              boolean remoteHealthy =
                  !response.has("healthy") || response.get("healthy").getAsBoolean();
              if (!remoteHealthy) {
                healthy = false;
                throw new CompletionException(new IllegalStateException("称号数据库暂不可用"));
              }
              if (response.has("snapshot") && !response.get("snapshot").isJsonNull())
                publish(JSON.fromJson(response.get("snapshot"), SnapshotTransfer.class).snapshot());
              healthy = true;
              remote.confirm(response);
              if (response.has("error"))
                throw new CompletionException(
                    new IllegalStateException(response.get("error").getAsString()));
              return type == Void.class ? null : JSON.<T>fromJson(response.get("result"), type);
            });
  }

  public CompletableFuture<Void> remoteAvatar(UUID id, String nonce, String png) {
    return remoteCall("avatarUpload", Void.class, id, nonce, png);
  }

  public CompletableFuture<java.nio.file.Path> remoteBackup(Actor actor) {
    return this.<String>remoteCall("backendBackup", String.class, actor)
        .thenApply(java.nio.file.Path::of);
  }

  public CompletableFuture<Void> remoteReload(Actor actor) {
    return remoteCall("backendReload", Void.class, actor);
  }

  public Snapshot snapshot() {
    return project(snapshot);
  }

  public boolean healthy() {
    return healthy;
  }

  public long lastSuccess() {
    return lastSuccess;
  }

  public void listen(Consumer<Snapshot> listener) {
    listeners.add(listener);
  }

  public CompletableFuture<List<PlayerNotice>> notifications(UUID player) {
    if (remote != null)
      return remoteCall(
          "notifications",
          new com.google.gson.reflect.TypeToken<List<PlayerNotice>>() {}.getType(),
          player);

    return background(() -> db.notifications(player));
  }

  public CompletableFuture<Void> acknowledgeNotifications(
      UUID player, List<PlayerNotice> messages) {
    if (remote != null) return remoteCall("acknowledgeNotifications", Void.class, player, messages);

    return background(
        () -> {
          db.acknowledgeNotifications(player, messages);
          return null;
        });
  }

  private void publish(Snapshot next) {
    boolean changed = !next.equals(snapshot);
    snapshot = next;
    healthy = true;
    lastSuccess = System.currentTimeMillis();
    if (!changed) return;
    for (var l : listeners)
      try {
        l.accept(next);
      } catch (RuntimeException ignored) {
      }
  }

  public <T> CompletableFuture<T> background(Callable<T> call) {
    CompletableFuture<T> f = new CompletableFuture<>();
    try {
      worker.execute(
          () -> {
            try {
              f.complete(call.call());
            } catch (Throwable e) {
              if (e instanceof SQLException) healthy = false;
              f.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      f.completeExceptionally(new IllegalStateException("称号服务队列已满或正在关闭，请稍后重试"));
    }
    return f;
  }

  public CompletableFuture<Void> reconnect() {
    if (remote != null) return remoteCall("reconnect", Void.class);

    return background(
        () -> {
          try {
            db.migrate();
            publish(db.read());
            return null;
          } catch (Exception e) {
            healthy = false;
            throw e;
          }
        });
  }

  private <T> CompletableFuture<T> write(Database.Work<T> job) {
    return background(
        () -> {
          if (!healthy) throw new IllegalStateException("称号数据库离线，当前仅可查看缓存");
          var result = db.transaction(job);
          publish(result.snapshot());
          return result.result();
        });
  }

  public static void requireAdmin(Snapshot s, Actor actor) {
    if (!actor.console
        && !actor.trustedApi
        && (actor.player == null || !s.admins().contains(actor.player)))
      throw new SecurityException("没有称号管理授权（OP 不能代替授权）");
  }

  public CompletableFuture<Void> login(UUID id, String name) {
    if (remote != null) return remoteCall("login", Void.class, id, name);

    return write(
        tx -> {
          JsonObject context = managed() ? platform("profiles.loginContext", id) : null;
          if (context != null
              && (!context.has("admitted")
                  || !context.get("admitted").getAsBoolean()
                  || !context.has("eventId")
                  || context.get("eventId").isJsonNull()
                  || context.get("eventId").getAsString().isBlank()))
            throw new SecurityException("玩家尚未通过 Manager 的真实登录校验");
          long now = System.currentTimeMillis();
          Profile p = managed() ? globalProfile(id) : new Profile(id, name, now, now);
          tx.sql(
              "INSERT INTO mt_players(uuid,name,joined_at,last_seen) VALUES(?,?,?,?) ON DUPLICATE"
                  + " KEY UPDATE name=VALUES(name),last_seen=VALUES(last_seen)",
              id.toString(),
              p.name(),
              p.joinedAt(),
              p.lastSeen());
          Snapshot s = tx.snapshot();
          String reward = s.settings().reward();
          Title t = s.titles().get(reward);
          // Existing policy is once per configured reward title, including old players after a
          // reward change. Only this trusted login path may issue it; directory imports never do.
          if (t != null && t.issuable() && !s.receipts().containsKey(new Key(id, reward))) {
            grant(tx, s, Actor.api("first-login"), id, t, "首次登录奖励");
            tx.sql(
                "INSERT INTO mt_receipts(player,title,claimed_at) VALUES(?,?,?)",
                id.toString(),
                reward,
                now);
          }
          return null;
        });
  }

  public CompletableFuture<Void> authorize(Actor actor, UUID id, boolean value) {
    if (remote != null) return remoteCall("authorize", Void.class, actor, id, value);

    return write(
        tx -> {
          if (!actor.console) throw new SecurityException("仅真实服务器控制台可改变授权");
          Snapshot s = tx.snapshot();
          known(tx, s, id);
          if (value) tx.sql("INSERT IGNORE INTO mt_admins(player) VALUES(?)", id.toString());
          else tx.sql("DELETE FROM mt_admins WHERE player=?", id.toString());
          tx.audit(
              actor.auditName(), value ? "ADMIN_GRANT" : "ADMIN_REVOKE", id.toString(), "", "");
          return null;
        });
  }

  public CompletableFuture<Void> saveTitle(Actor actor, Title title, long expectedRevision) {
    if (remote != null) return remoteCall("saveTitle", Void.class, actor, title, expectedRevision);

    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          Title old = s.titles().get(title.id());
          long revision = old == null ? 0 : old.revision();
          if (revision != expectedRevision) throw new IllegalStateException("称号已被其他编辑更新，请重新载入");
          tx.title(
              title
                  .created(old == null ? System.currentTimeMillis() : old.createdAt())
                  .revised(revision + 1));
          tx.audit(actor.auditName(), "TITLE_SAVE", "", title.id(), "");
          return null;
        });
  }

  public CompletableFuture<Integer> grant(
      Actor actor, Collection<UUID> targets, String title, String reason) {
    if (remote != null) return remoteCall("grant", Integer.class, actor, targets, title, reason);

    List<UUID> ids = targets(targets);
    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          Title t = definition(s, title);
          if (!t.issuable()) throw new IllegalStateException("此称号已停发");
          int count = 0;
          for (UUID id : ids) {
            known(tx, s, id);
            if (grant(tx, s, actor, id, t, reason)) count++;
          }
          return count;
        });
  }

  public record TitleDeletePreview(
      String id,
      RichText name,
      int holders,
      int equipped,
      int receipts,
      boolean reward,
      String stamp) {}

  private TitleDeletePreview titleDeletion(Database.Tx tx, Actor actor, String id)
      throws Exception {
    Snapshot s = tx.snapshot();
    requireAdmin(s, actor);
    Title title = definition(s, id);
    var owned =
        s.owned().values().stream()
            .filter(o -> o.title().equals(id))
            .sorted(Comparator.comparing(o -> o.player().toString()))
            .toList();
    var receipts =
        s.receipts().values().stream()
            .filter(r -> r.title().equals(id))
            .sorted(Comparator.comparing(r -> r.player().toString()))
            .toList();
    Map<String, String> equipped = new TreeMap<>();
    s.equipped()
        .forEach(
            (player, value) -> {
              if (value.equals(id)) equipped.put(player.toString(), value);
            });
    boolean reward = s.settings().reward().equals(id);
    JsonObject impact = new JsonObject();
    impact.add("title", JSON.toJsonTree(title));
    impact.add("owned", JSON.toJsonTree(owned));
    impact.add("equipped", JSON.toJsonTree(equipped));
    impact.add("receipts", JSON.toJsonTree(receipts));
    impact.addProperty("reward", reward);
    impact.addProperty("epoch", ChangeJournal.version(tx, "G|title|" + id));
    String stamp =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJson(impact).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return new TitleDeletePreview(
        id, title.name(), owned.size(), equipped.size(), receipts.size(), reward, stamp);
  }

  public CompletableFuture<TitleDeletePreview> titleDeletePreview(Actor actor, String id) {
    if (remote != null)
      return remoteCall("titleDeletePreview", TitleDeletePreview.class, actor, id);

    return background(() -> db.transaction(tx -> titleDeletion(tx, actor, id)).result());
  }

  public CompletableFuture<Integer> deleteTitle(
      Actor actor, String id, String stamp, String confirmName) {
    if (remote != null)
      return remoteCall("deleteTitle", Integer.class, actor, id, stamp, confirmName);

    return write(
        tx -> {
          TitleDeletePreview preview = titleDeletion(tx, actor, id);
          if (!preview.stamp().equals(stamp))
            throw new IllegalStateException("称号或相关持有、佩戴、首登奖励已发生变化，请重新预览后确认");
          if (!preview.name().plain().equals(confirmName))
            throw new IllegalArgumentException("请输入完整称号名称，确认删除该称号");
          Snapshot before = tx.snapshot();
          tx.sql("DELETE FROM mt_equipped WHERE title=?", id);
          tx.sql("DELETE FROM mt_owned WHERE title=?", id);
          tx.sql("DELETE FROM mt_receipts WHERE title=?", id);
          // An unread grant for a now-deleted title must not be displayed before its removal
          // notice.
          tx.sql("DELETE FROM mt_notifications WHERE title=? AND granted=TRUE", id);
          if (preview.reward()) {
            Settings x = before.settings();
            tx.settings(
                new Settings(
                    "",
                    x.head(),
                    x.chat(),
                    x.tab(),
                    x.headScale(),
                    x.headOffset(),
                    x.chatPrefix(),
                    x.retentionDays(),
                    x.packName(),
                    x.packStartedOn()));
          }
          tx.sql("DELETE FROM mt_titles WHERE id=?", id);
          long audit =
              tx.audit(
                  actor.auditName(),
                  "TITLE_DELETE",
                  "",
                  id,
                  "删除称号「" + preview.name().plain() + "」，清除" + preview.holders() + "名玩家持有及相关记录");
          tx.customJournal = true;
          tx.changeUnits.add(
              new ChangeJournal.Unit(
                  audit,
                  "删除称号「" + preview.name().plain() + "」及关联记录",
                  "",
                  id,
                  List.copyOf(ChangeJournal.diff(before, tx.snapshot()))));
          return preview.holders();
        });
  }

  private static boolean grant(
      Database.Tx tx, Snapshot s, Actor actor, UUID id, Title t, String reason)
      throws SQLException {
    if (s.ownership(id, t.id()) != null) return false;
    tx.sql(
        "INSERT INTO mt_owned(player,title,acquired_at,collection_public,display_public)"
            + " VALUES(?,?,?,FALSE,TRUE)",
        id.toString(),
        t.id(),
        System.currentTimeMillis());
    tx.audit(actor.auditName(), "GRANT", id.toString(), t.id(), reason);
    return true;
  }

  public CompletableFuture<Integer> revoke(
      Actor actor, Collection<UUID> targets, String title, String reason) {
    if (remote != null) return remoteCall("revoke", Integer.class, actor, targets, title, reason);

    List<UUID> ids = targets(targets);
    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          definition(s, title);
          int count = 0;
          for (UUID id : ids) {
            known(tx, s, id);
            if (tx.sql("DELETE FROM mt_owned WHERE player=? AND title=?", id.toString(), title)
                > 0) {
              count++;
              tx.audit(actor.auditName(), "REVOKE", id.toString(), title, reason);
            }
          }
          return count;
        });
  }

  public CompletableFuture<Void> equip(UUID id, String title) {
    if (remote != null) return remoteCall("equip", Void.class, id, title);

    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          known(tx, s, id);
          if (title.isEmpty()) tx.sql("DELETE FROM mt_equipped WHERE player=?", id.toString());
          else {
            owned(s, id, title);
            tx.sql(
                "INSERT INTO mt_equipped(player,title) VALUES(?,?) ON DUPLICATE KEY UPDATE"
                    + " title=VALUES(title)",
                id.toString(),
                title);
          }
          tx.audit(id.toString(), "EQUIP", id.toString(), title, "");
          return null;
        });
  }

  public CompletableFuture<Void> privacy(
      UUID id, String title, boolean collection, boolean display) {
    if (remote != null) return remoteCall("privacy", Void.class, id, title, collection, display);

    return write(
        tx -> {
          owned(tx.snapshot(), id, title);
          tx.sql(
              "UPDATE mt_owned SET collection_public=?,display_public=? WHERE player=? AND title=?",
              collection,
              display,
              id.toString(),
              title);
          tx.audit(id.toString(), "PRIVACY", id.toString(), title, collection + "/" + display);
          return null;
        });
  }

  /** The caller's authenticated identity comes from the server-side menu, not a client UUID. */
  public CompletableFuture<Void> toggleDisplayPosition(UUID player, DisplayPosition position) {
    if (remote != null) return remoteCall("toggleDisplayPosition", Void.class, player, position);

    Objects.requireNonNull(position);
    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          known(tx, s, player);
          DisplayPreferences before = s.displayPreferences(player);
          DisplayPreferences after = before.with(position, !before.enabled(position));
          tx.displayPreferences(player, after);
          tx.audit(
              player.toString(),
              "DISPLAY_POSITION",
              player.toString(),
              "",
              position.name().toLowerCase(Locale.ROOT) + "=" + after.enabled(position));
          return null;
        });
  }

  /** Only the authenticated player's command may supply this UUID. */
  public CompletableFuture<Void> setUnequippedReminder(UUID player, boolean enabled) {
    if (remote != null) return remoteCall("setUnequippedReminder", Void.class, player, enabled);
    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          known(tx, s, player);
          DisplayPreferences before = s.displayPreferences(player);
          if (before.reminderEnabled() == enabled) return null;
          tx.displayPreferences(player, before.withReminderEnabled(enabled));
          tx.audit(player.toString(), "REMINDER", player.toString(), "", "enabled=" + enabled);
          return null;
        });
  }

  public CompletableFuture<Void> settings(Actor actor, Settings settings) {
    if (remote != null) return remoteCall("settings", Void.class, actor, settings);

    return settings(actor, settings, null);
  }

  public CompletableFuture<Void> settings(Actor actor, Settings settings, Settings expected) {
    if (remote != null) return remoteCall("settings", Void.class, actor, settings, expected);

    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          if (expected != null && !expected.equals(s.settings()))
            throw new IllegalStateException("设置已被其他管理员更新，请重新载入");
          if (!settings.reward().isEmpty()) {
            Title t = definition(s, settings.reward());
            if (!t.issuable()) throw new IllegalArgumentException("首登奖励不可使用停发称号");
          }
          tx.settings(settings);
          tx.audit(actor.auditName(), "SETTINGS", "", "", "");
          return null;
        });
  }

  public CompletableFuture<Void> reload(Actor actor, List<Title> titles, Settings settings) {
    if (remote != null) return remoteCall("reload", Void.class, actor, titles, settings);

    return reload(actor, titles, settings, null);
  }

  public static String definitionsStamp(Snapshot s) {
    String value =
        JSON.toJson(s.titles().values().stream().sorted(Comparator.comparing(Title::id)).toList())
            + JSON.toJson(s.settings());
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public CompletableFuture<Void> reload(
      Actor actor, List<Title> titles, Settings settings, String expectedStamp) {
    if (remote != null)
      return remoteCall("reload", Void.class, actor, titles, settings, expectedStamp);

    List<Title> immutable = List.copyOf(titles);
    Set<String> unique = new HashSet<>();
    for (Title t : titles)
      if (!unique.add(t.id())) throw new IllegalArgumentException("重复称号 ID: " + t.id());
    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          if (expectedStamp != null && !expectedStamp.equals(definitionsStamp(s)))
            throw new IllegalStateException("预览后配置已被更新，请重新预览再导入");
          for (Title t : immutable) {
            Title old = s.titles().get(t.id());
            tx.title(
                t.created(
                        old == null
                            ? (t.createdAt() > 0 ? t.createdAt() : System.currentTimeMillis())
                            : old.createdAt())
                    .revised(old == null ? 1 : old.revision() + 1));
          }
          // Missing entries are retained: removing a config row never destroys collections.
          if (!settings.reward().isEmpty()) {
            Title reward = tx.snapshot().titles().get(settings.reward());
            if (reward == null || !reward.issuable())
              throw new IllegalArgumentException("首登奖励不存在或已停发");
          }
          tx.settings(settings);
          tx.audit(actor.auditName(), "RELOAD", "", "", immutable.size() + " definitions");
          return null;
        });
  }

  public CompletableFuture<List<Audit>> history(Actor actor, int limit) {
    if (remote != null)
      return remoteCall(
          "history",
          new com.google.gson.reflect.TypeToken<List<Audit>>() {}.getType(),
          actor,
          limit);

    return background(
        () ->
            db.transaction(
                    tx -> {
                      requireAdmin(tx.snapshot(), actor);
                      return tx.history(limit);
                    })
                .result());
  }

  public record HistoryPage(
      List<Audit> rows, long total, int page, int size, List<Long> categoryCounts) {}

  public CompletableFuture<HistoryPage> searchHistory(
      Actor actor, String query, String action, String player, String title, int page, int size) {
    if (remote != null)
      return remoteCall(
          "searchHistory", HistoryPage.class, actor, query, action, player, title, page, size);

    if (query.length() > 128
        || action.length() > 64
        || player.length() > 36
        || title.length() > 64
        || page < 0
        || page > 100000
        || size < 1
        || size > 100)
      return CompletableFuture.failedFuture(new IllegalArgumentException("历史查询范围无效"));
    return background(
        () ->
            db.transaction(
                    tx -> {
                      requireAdmin(tx.snapshot(), actor);
                      String globalMatches = "";
                      if (managed() && !query.isEmpty()) {
                        String needle = query.toLowerCase(Locale.ROOT);
                        globalMatches =
                            directory().values().stream()
                                .filter(p -> p.name().toLowerCase(Locale.ROOT).contains(needle))
                                .map(p -> p.id().toString())
                                .collect(java.util.stream.Collectors.joining(","));
                      }
                      String where =
                          " WHERE NOT EXISTS (SELECT 1 FROM mt_audit_hidden h WHERE"
                              + " h.audit_id=a.id) AND (?='' OR FIND_IN_SET(a.action,?)>0) AND"
                              + " (?='' OR a.target=?) AND (?='' OR a.title=?) AND (?='' OR"
                              + " INSTR(CONVERT(CONCAT_WS('"
                              + " ',a.actor,a.target,a.title,a.reason,a.action,p.name,t.name,d.definition)"
                              + " USING utf8mb4) COLLATE utf8mb4_unicode_ci, CONVERT(? USING"
                              + " utf8mb4) COLLATE utf8mb4_unicode_ci)>0 OR"
                              + " FIND_IN_SET(a.actor,?)>0 OR FIND_IN_SET(a.target,?)>0)";
                      String from =
                          " FROM mt_audit a LEFT JOIN mt_players p ON p.uuid=a.actor LEFT JOIN"
                              + " mt_players t ON t.uuid=a.target LEFT JOIN mt_titles d ON"
                              + " d.id=a.title";
                      Object[] values = {
                        action,
                        action,
                        player,
                        player,
                        title,
                        title,
                        query,
                        query,
                        globalMatches,
                        globalMatches
                      };
                      // Category totals cover this player/title scope, independently of search,
                      // selected action, and pagination. Hidden recovery audits stay protected.
                      List<Long> categoryCounts;
                      try (var stmt =
                          tx.connection.prepareStatement(
                              "SELECT COUNT(*), COALESCE(SUM(a.action IN ('GRANT','REVOKE')),0),"
                                  + " COALESCE(SUM(a.action IN"
                                  + " ('TITLE_SAVE','TITLE_DELETE','RELOAD')),0) FROM mt_audit a"
                                  + " WHERE NOT EXISTS (SELECT 1 FROM mt_audit_hidden h WHERE"
                                  + " h.audit_id=a.id) AND (?='' OR a.target=?) AND (?='' OR"
                                  + " a.title=?)")) {
                        stmt.setString(1, player);
                        stmt.setString(2, player);
                        stmt.setString(3, title);
                        stmt.setString(4, title);
                        try (var rs = stmt.executeQuery()) {
                          rs.next();
                          categoryCounts = List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3));
                        }
                      }
                      long total;
                      try (var stmt =
                          tx.connection.prepareStatement("SELECT COUNT(*)" + from + where)) {
                        for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
                        try (var rs = stmt.executeQuery()) {
                          rs.next();
                          total = rs.getLong(1);
                        }
                      }
                      int actual = (int) Math.min(page, Math.max(0, (total - 1) / size));
                      List<Audit> rows = new ArrayList<>();
                      try (var stmt =
                          tx.connection.prepareStatement(
                              "SELECT a.*"
                                  + from
                                  + where
                                  + " ORDER BY a.at_ms DESC, a.id DESC LIMIT ? OFFSET ?")) {
                        for (int i = 0; i < values.length; i++) stmt.setObject(i + 1, values[i]);
                        stmt.setInt(values.length + 1, size);
                        stmt.setLong(values.length + 2, (long) actual * size);
                        try (var rs = stmt.executeQuery()) {
                          while (rs.next())
                            rows.add(
                                new Audit(
                                    rs.getLong("id"),
                                    rs.getLong("at_ms"),
                                    rs.getString("actor"),
                                    rs.getString("action"),
                                    rs.getString("target"),
                                    rs.getString("title"),
                                    rs.getString("reason")));
                        }
                      }
                      return new HistoryPage(
                          List.copyOf(rows), total, actual, size, categoryCounts);
                    })
                .result());
  }

  public CompletableFuture<Void> migrateIdentity(Actor actor, UUID from, UUID to) {
    if (remote != null) return remoteCall("migrateIdentity", Void.class, actor, from, to);

    return write(
        tx -> {
          if (!actor.console) throw new SecurityException("身份迁移仅允许控制台");
          Snapshot s = tx.snapshot();
          known(tx, s, from);
          known(tx, s, to);
          if (from.equals(to)) throw new IllegalArgumentException("源与目标相同");
          if (s.owned().values().stream().anyMatch(o -> o.player().equals(to))
              || s.receipts().values().stream().anyMatch(r -> r.player().equals(to))
              || s.displayPreferences().containsKey(to))
            throw new IllegalArgumentException("目标身份有记录，请先处理冲突");
          if (s.displayPreferences().containsKey(from))
            tx.displayPreferences(to, s.displayPreferences(from));
          tx.sql("DELETE FROM mt_display_preferences WHERE player=?", from.toString());
          tx.sql(
              "INSERT INTO mt_owned SELECT ?,title,acquired_at,collection_public,display_public"
                  + " FROM mt_owned WHERE player=?",
              to.toString(),
              from.toString());
          tx.sql(
              "INSERT INTO mt_receipts SELECT ?,title,claimed_at FROM mt_receipts WHERE player=?",
              to.toString(),
              from.toString());
          tx.sql(
              "INSERT INTO mt_equipped SELECT ?,title FROM mt_equipped WHERE player=?",
              to.toString(),
              from.toString());
          tx.sql("DELETE FROM mt_owned WHERE player=?", from.toString());
          tx.sql("DELETE FROM mt_receipts WHERE player=?", from.toString());
          // Admin grants deliberately stay with the old UUID. Console must revoke/grant explicitly.
          tx.audit(actor.auditName(), "IDENTITY_MIGRATE", from.toString(), "", to.toString());
          return null;
        });
  }

  public CompletableFuture<List<ChangeJournal.View>> changes(Actor actor, long audit) {
    if (remote != null)
      return remoteCall(
          "changes",
          new com.google.gson.reflect.TypeToken<List<ChangeJournal.View>>() {}.getType(),
          actor,
          audit);

    return background(
        () ->
            db.transaction(
                    tx -> {
                      Snapshot s = tx.snapshot();
                      requireAdmin(s, actor);
                      List<ChangeJournal.View> out = new ArrayList<>();
                      for (var entry : ChangeJournal.entries(tx, audit)) {
                        var view = ChangeJournal.view(tx, s, entry, assets);
                        if (entry.changes().stream().anyMatch(d -> d.key().startsWith("P|"))
                            && !entry.target().isEmpty()
                            && online.test(UUID.fromString(entry.target()))) {
                          var conflicts = new ArrayList<>(view.conflicts());
                          conflicts.add("目标玩家当前在线，请离线后再撤回资料清理");
                          view =
                              new ChangeJournal.View(
                                  view.id(),
                                  view.label(),
                                  view.target(),
                                  view.title(),
                                  view.undone(),
                                  false,
                                  conflicts,
                                  view.changes());
                        }
                        out.add(view);
                      }
                      return List.copyOf(out);
                    })
                .result());
  }

  public CompletableFuture<Void> undo(Actor actor, long change) {
    if (remote != null) return remoteCall("undo", Void.class, actor, change);

    return write(
        tx -> {
          Snapshot s = tx.snapshot();
          requireAdmin(s, actor);
          var entry = ChangeJournal.get(tx, change);
          if (entry.changes().stream().anyMatch(d -> d.key().startsWith("P|"))
              && !entry.target().isEmpty()
              && online.test(UUID.fromString(entry.target())))
            throw new IllegalStateException("目标玩家当前在线，请离线后再撤回资料清理");
          ChangeJournal.apply(tx, entry, assets);
          long audit =
              tx.audit(
                  actor.auditName(),
                  "UNDO",
                  entry.target(),
                  entry.title(),
                  "撤回一项改动：" + entry.label());
          tx.sql("UPDATE mt_changes SET reverted_by=? WHERE id=? AND reverted_by=0", audit, change);
          tx.customJournal = true;
          tx.changeUnits.add(
              new ChangeJournal.Unit(
                  audit,
                  "撤回：" + entry.label(),
                  entry.target(),
                  entry.title(),
                  entry.changes().stream()
                      .map(d -> new ChangeJournal.Delta(d.key(), d.after(), d.before()))
                      .toList()));
          return null;
        });
  }

  public record DeletePreview(
      UUID player,
      String name,
      int owned,
      int receipts,
      int history,
      boolean avatar,
      boolean managed,
      String stamp) {}

  private record DeleteData(DeletePreview preview, List<Long> history, JsonElement avatar) {}

  private DeleteData deletion(Database.Tx tx, Actor actor, UUID id) throws Exception {
    Snapshot s = project(tx.snapshot());
    requireAdmin(s, actor);
    if (!s.players().containsKey(id)) throw new IllegalArgumentException("玩家未登记: " + id);
    if (s.admins().contains(id))
      throw new IllegalStateException("此玩家仍有管理授权，本入口不删除网页账号或管理身份；请先在控制台处理授权");
    boolean activeAdmission = false;
    if (managed()) {
      JsonObject context = platform("profiles.loginContext", id);
      activeAdmission = context.has("admitted") && context.get("admitted").getAsBoolean();
    }
    if (online.test(id) || activeAdmission) throw new IllegalStateException("玩家当前在线，请先离线，再清除资料");
    Profile p = s.players().get(id);
    var owned =
        s.owned().values().stream()
            .filter(o -> o.player().equals(id))
            .sorted(Comparator.comparing(Owned::title))
            .toList();
    var receipts =
        s.receipts().values().stream()
            .filter(r -> r.player().equals(id))
            .sorted(Comparator.comparing(Receipt::title))
            .toList();
    List<Long> history = new ArrayList<>();
    try (var stmt =
        tx.connection.prepareStatement(
            "SELECT a.id FROM mt_audit a WHERE (a.target=? OR a.actor=?) AND NOT EXISTS (SELECT 1"
                + " FROM mt_audit_hidden h WHERE h.audit_id=a.id) ORDER BY a.id")) {
      stmt.setString(1, id.toString());
      stmt.setString(2, id.toString());
      try (var r = stmt.executeQuery()) {
        while (r.next()) history.add(r.getLong(1));
      }
    }
    JsonElement avatar = managed() ? JsonNull.INSTANCE : assets.read(id);
    Set<String> referenced = new TreeSet<>();
    owned.forEach(o -> referenced.add(o.title()));
    receipts.forEach(r -> referenced.add(r.title()));
    JsonObject value = new JsonObject();
    value.add("profile", JSON.toJsonTree(p));
    value.add("displayPreferences", JSON.toJsonTree(s.displayPreferences(id)));
    value.add("owned", JSON.toJsonTree(owned));
    value.add("receipts", JSON.toJsonTree(receipts));
    value.add("equipped", ChangeJournal.json(s.equipped().get(id)));
    value.add("history", JSON.toJsonTree(history));
    value.add("avatar", avatar);
    value.add("titles", JSON.toJsonTree(referenced.stream().map(s.titles()::get).toList()));
    value.addProperty("epoch", ChangeJournal.version(tx, "G|player|" + id));
    String stamp =
        HexFormat.of()
            .formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    return new DeleteData(
        new DeletePreview(
            id,
            p.name(),
            owned.size(),
            receipts.size(),
            history.size(),
            !avatar.isJsonNull(),
            managed(),
            stamp),
        history,
        avatar);
  }

  public CompletableFuture<DeletePreview> deletePreview(Actor actor, UUID id) {
    if (remote != null) return remoteCall("deletePreview", DeletePreview.class, actor, id);

    return background(() -> db.transaction(tx -> deletion(tx, actor, id).preview()).result());
  }

  public CompletableFuture<Void> deletePlayer(
      Actor actor, UUID id, String stamp, String confirmName) {
    if (remote != null)
      return remoteCall("deletePlayer", Void.class, actor, id, stamp, confirmName);

    return write(
        tx -> {
          DeleteData data = deletion(tx, actor, id);
          if (!data.preview().stamp().equals(stamp))
            throw new IllegalStateException("预览后玩家资料、相关称号或历史已发生变化，请重新确认");
          if (!data.preview().name().equals(confirmName))
            throw new IllegalArgumentException("请输入完整玩家名，确认清除该玩家资料");
          Snapshot before = tx.snapshot();
          List<ChangeJournal.Delta> extra = new ArrayList<>();
          for (long audit : data.history()) {
            tx.sql("INSERT INTO mt_audit_hidden VALUES(?)", audit);
            extra.add(
                new ChangeJournal.Delta(
                    "H|" + audit, ChangeJournal.json(false), ChangeJournal.json(true)));
          }
          tx.sql("DELETE FROM mt_equipped WHERE player=?", id.toString());
          tx.sql("DELETE FROM mt_owned WHERE player=?", id.toString());
          tx.sql("DELETE FROM mt_receipts WHERE player=?", id.toString());
          tx.sql("DELETE FROM mt_players WHERE uuid=?", id.toString());
          if (!managed()) {
            tx.rollbackHooks.add(() -> assets.playerPresent(id, true));
            tx.rollbackHooks.add(assets.swap(id, data.avatar(), JsonNull.INSTANCE));
            extra.add(new ChangeJournal.Delta("V|" + id, data.avatar(), JsonNull.INSTANCE));
          }
          long audit =
              tx.audit(
                  actor.auditName(),
                  "PLAYER_DELETE",
                  id.toString(),
                  "",
                  "清除 "
                      + data.preview().name()
                      + (managed()
                          ? " 的称号、领奖和相关历史；保留全局身份、头像与受保护的恢复记录"
                          : " 的玩家档案、称号、领奖、头像及相关历史；保留受保护的恢复记录"));
          var changes = new ArrayList<>(ChangeJournal.diff(before, tx.snapshot()));
          changes.addAll(extra);
          tx.customJournal = true;
          tx.changeUnits.add(
              new ChangeJournal.Unit(
                  audit,
                  "清除 " + data.preview().name() + " 的全部称号资料",
                  id.toString(),
                  "",
                  List.copyOf(changes)));
          return null;
        });
  }

  public CompletableFuture<Backup.Archive> export(Actor actor) {
    if (remote != null) return remoteCall("export", Backup.Archive.class, actor);

    return background(
        () ->
            db.transaction(
                    tx -> {
                      requireAdmin(tx.snapshot(), actor);
                      return Backup.capture(tx);
                    })
                .result());
  }

  public CompletableFuture<String> importPreview(Actor actor, Backup.Archive archive) {
    if (remote != null) return remoteCall("importPreview", String.class, actor, archive);

    Backup.validate(archive);
    return background(
        () ->
            db.transaction(
                    tx -> {
                      Snapshot s = tx.snapshot();
                      requireAdmin(s, actor);
                      return Backup.preview(s, archive);
                    })
                .result());
  }

  public CompletableFuture<Void> restoreEmpty(Actor actor, Backup.Archive archive) {
    if (remote != null) return remoteCall("restoreEmpty", Void.class, actor, archive);

    Backup.validate(archive);
    return write(
        tx -> {
          if (!actor.console) throw new SecurityException("完整恢复仅控制台可执行");
          Snapshot s = tx.snapshot();
          if (!s.titles().isEmpty() || !s.players().isEmpty() || !tx.history(1).isEmpty())
            throw new IllegalStateException("完整恢复只允许空数据库，以免覆盖现有数据");
          Backup.restore(tx, archive);
          return null;
        });
  }

  private static List<UUID> targets(Collection<UUID> ids) {
    var result = List.copyOf(new LinkedHashSet<>(ids));
    if (result.isEmpty() || result.size() > 1000)
      throw new IllegalArgumentException("单次选择1–1000名玩家");
    return result;
  }

  private void known(Database.Tx tx, Snapshot s, UUID id) throws SQLException {
    if (managed()) {
      Profile p = globalProfile(id);
      // Materialize only for an actual business operation, without login/reward side effects.
      tx.sql(
          "INSERT INTO mt_players(uuid,name,joined_at,last_seen) VALUES(?,?,?,?)"
              + " ON DUPLICATE KEY UPDATE name=VALUES(name),last_seen=VALUES(last_seen)",
          id.toString(),
          p.name(),
          p.joinedAt(),
          p.lastSeen());
    } else if (!s.players().containsKey(id)) throw new IllegalArgumentException("玩家未登记: " + id);
  }

  private static Title definition(Snapshot s, String id) {
    Title t = s.titles().get(id);
    if (t == null) throw new IllegalArgumentException("称号不存在: " + id);
    return t;
  }

  private static Owned owned(Snapshot s, UUID id, String t) {
    Owned o = s.ownership(id, t);
    if (o == null) throw new SecurityException("尚未获得该称号");
    return o;
  }

  @Override
  public void close() {
    worker.shutdown();
    try {
      if (!worker.awaitTermination(15, TimeUnit.SECONDS)) worker.shutdownNow();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      worker.shutdownNow();
    }
  }
}
