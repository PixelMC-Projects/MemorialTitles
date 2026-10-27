package cn.pixelmc.memorialtitles.web;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Embedded same-origin administrator console. SQL work stays in TitleService's bounded worker. */
public final class AdminWebServer implements AutoCloseable {
  public interface Host {
    Set<UUID> online();

    void mirror();

    CompletableFuture<Path> backup(TitleService.Actor actor);

    CompletableFuture<Void> reload(TitleService.Actor actor);

    default void requestAvatar(UUID id, String nonce) {}

    default boolean platformAvailable() {
      return false;
    }

    default boolean bridgeAuthority(HttpExchange e, String path) {
      return false;
    }

    default boolean platformAdmin(UUID id) {
      return false;
    }

    default boolean publicRoute(HttpExchange e, String path) throws Exception {
      return false;
    }

    default int httpThreads() {
      return 4;
    }

    default boolean adminRoute(
        HttpExchange e,
        String path,
        TitleService.Actor actor,
        java.util.function.BooleanSupplier authorized)
        throws Exception {
      return false;
    }
  }

  private record Session(
      String username, UUID player, long accountVersion, String csrf, long expires) {}

  private record PlayerRow(
      UUID id,
      String name,
      long joinedAt,
      long lastSeen,
      long ownedCount,
      String equipped,
      boolean online,
      boolean admin,
      long avatarVersion) {}

  private record Rate(long start, int count) {}

  private record TitleHolder(UUID id, String name, long acquiredAt, long avatarVersion) {}

  private static final class Failure extends RuntimeException {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private final TitleService service;
  private final Host host;
  private final WebConfig config;
  private final Path backupFolder;
  public final AccountStore accounts;
  public final AvatarStore avatars;
  private final HttpServer http;
  private final ExecutorService executor;
  private final Map<String, Session> sessions = new ConcurrentHashMap<>();
  private final Map<String, Rate> rates = new HashMap<>();
  private static final SecureRandom RANDOM = new SecureRandom();

  public AdminWebServer(
      TitleService service, Host host, WebConfig config, Path accountFolder, Path backupFolder)
      throws Exception {
    this.service = service;
    this.host = host;
    this.config = config;
    this.backupFolder = backupFolder.toAbsolutePath().normalize();
    accounts = new AccountStore(accountFolder);
    avatars = new AvatarStore(accountFolder.resolve("avatars"), host::requestAvatar);
    service.attachPlayerAssets(avatars, id -> host.online().contains(id));
    http = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 32);
    executor =
        new ThreadPoolExecutor(
            Math.max(4, Math.min(16, host.httpThreads())),
            Math.max(4, Math.min(16, host.httpThreads())),
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64),
            r -> {
              Thread t = new Thread(r, "MemorialTitles-Web");
              t.setDaemon(true);
              return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    http.setExecutor(executor);
    http.createContext("/", this::handle);
    http.start();
  }

  AdminWebServer(
      TitleService service, Host host, java.util.function.ToLongFunction<UUID> avatarVersion) {
    this.service = service;
    this.host = host;
    this.platformAvatarVersion = avatarVersion;
    this.config = null;
    this.backupFolder = null;
    this.accounts = null;
    this.avatars = null;
    this.http = null;
    this.executor = null;
  }

  private java.util.function.ToLongFunction<UUID> platformAvatarVersion;

  private long avatarVersion(UUID player) {
    return platformAvatarVersion == null
        ? avatars.version(player)
        : platformAvatarVersion.applyAsLong(player);
  }

  public int port() {
    return http.getAddress().getPort();
  }

  public String address() {
    return config.publicUrl().isEmpty() ? "http://<服务器IP>:" + port() : config.publicUrl();
  }

  private static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String digest(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private synchronized void rate(String key, int limit) {
    long now = System.currentTimeMillis();
    if (rates.size() > 4096) rates.entrySet().removeIf(e -> now - e.getValue().start() > 60000);
    if (rates.size() > 8192) throw new Failure(429, "请求过多，请稍后重试");
    Rate old = rates.get(key);
    Rate next =
        old == null || now - old.start() > 60000
            ? new Rate(now, 1)
            : new Rate(old.start(), old.count() + 1);
    rates.put(key, next);
    if (next.count() > limit) throw new Failure(429, "请求过于频繁，请一分钟后再试");
  }

  private void handle(HttpExchange exchange) {
    try {
      headers(exchange);
      String path = exchange.getRequestURI().getPath();
      if (!validHost(exchange.getRequestHeaders().getFirst("Host"))
          && !host.bridgeAuthority(exchange, path)) throw new Failure(403, "访问地址未允许，请使用服务器IP和端口");
      if (host.publicRoute(exchange, path)) return;
      if (path.startsWith("/api/")) {
        rate("api:" + exchange.getRemoteAddress().getAddress().getHostAddress(), 600);
        if (!Set.of("GET", "POST").contains(exchange.getRequestMethod()))
          throw new Failure(405, "不支持的请求方法");
        if (exchange.getRequestMethod().equals("POST")) {
          String origin = exchange.getRequestHeaders().getFirst("Origin");
          String local = "http://" + exchange.getRequestHeaders().getFirst("Host");
          if (origin == null
              || !(origin.equals(local)
                  || origin.equals(config.publicUrl())
                  || config.extraOrigins().contains(origin)))
            throw new Failure(403, "请求来源不匹配，请从管理台本身操作");
          if (!Optional.ofNullable(exchange.getRequestHeaders().getFirst("Content-Type"))
              .orElse("")
              .toLowerCase(Locale.ROOT)
              .startsWith("application/json")) throw new Failure(415, "请使用JSON请求");
        }
        api(exchange, path);
      } else {
        if (!exchange.getRequestMethod().equals("GET")) throw new Failure(405, "不支持的请求方法");
        assets(exchange, path);
      }
    } catch (Throwable error) {
      // Exchange may already be closed after a client disconnect; never expose stack
      // traces/secrets.
      try {
        Throwable cause = error;
        while (cause.getCause() != null
            && (cause instanceof CompletionException
                || cause instanceof ExecutionException
                || cause instanceof java.lang.reflect.InvocationTargetException))
          cause = cause.getCause();
        int code =
            cause instanceof Failure f
                ? f.status
                : cause instanceof SecurityException
                    ? 403
                    : cause instanceof IllegalArgumentException
                            || cause instanceof JsonParseException
                        ? 400
                        : cause instanceof TimeoutException
                            ? 504
                            : cause instanceof java.sql.SQLException ? 503 : 409;
        String message =
            cause instanceof java.sql.SQLException
                ? "数据库连接或事务失败，请检查服务端数据库状态"
                : cause instanceof Failure
                        || cause instanceof IllegalArgumentException
                        || cause instanceof IllegalStateException
                        || cause instanceof SecurityException
                    ? Objects.toString(cause.getMessage(), "请求无效")
                    : "操作失败，请查看服务端日志或稍后重试";
        json(exchange, code, Map.of("error", message));
      } catch (Exception ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  static void moduleFailure(HttpExchange exchange, Throwable error) throws IOException {
    Throwable cause = error;
    while (cause.getCause() != null
        && (cause instanceof CompletionException
            || cause instanceof ExecutionException
            || cause instanceof java.lang.reflect.InvocationTargetException))
      cause = cause.getCause();
    int status =
        cause instanceof Failure f
            ? f.status
            : cause instanceof SecurityException
                ? 403
                : cause instanceof IllegalArgumentException || cause instanceof JsonParseException
                    ? 400
                    : cause instanceof TimeoutException
                        ? 504
                        : cause instanceof java.sql.SQLException ? 503 : 409;
    String message =
        cause instanceof java.sql.SQLException
            ? "数据库连接或事务失败，请检查服务端数据库状态"
            : cause instanceof Failure
                    || cause instanceof IllegalArgumentException
                    || cause instanceof IllegalStateException
                    || cause instanceof SecurityException
                ? Objects.toString(cause.getMessage(), "请求无效")
                : "操作失败，请查看服务端日志或稍后重试";
    json(exchange, status, Map.of("error", message));
  }

  private void headers(HttpExchange e) {
    e.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    e.getResponseHeaders().set("Referrer-Policy", "no-referrer");
    e.getResponseHeaders().set("X-Frame-Options", "DENY");
    e.getResponseHeaders().set("Cache-Control", "no-store");
    e.getResponseHeaders()
        .set(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src"
                + " 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';"
                + " object-src 'none'; base-uri 'none'; form-action 'self'");
  }

  private boolean validHost(String authority) {
    if (authority == null
        || authority.contains("@")
        || authority.contains("/")
        || authority.contains("\\")) return false;
    if (!config.publicUrl().isEmpty()
        && authority.equalsIgnoreCase(URI.create(config.publicUrl()).getRawAuthority()))
      return true;
    try {
      URI uri = URI.create("http://" + authority);
      String host = uri.getHost();
      if (uri.getPort() != port() || host == null) return false;
      if (host.equals("localhost") || host.equals("[::1]")) return true;
      if (host.matches("\\d{1,3}(\\.\\d{1,3}){3}"))
        return Arrays.stream(host.split("\\.")).allMatch(n -> Integer.parseInt(n) <= 255);
      return host.matches("\\[[a-fA-F0-9:]+\\]");
    } catch (Exception e) {
      return false;
    }
  }

  private void api(HttpExchange e, String path) throws Exception {
    boolean post = e.getRequestMethod().equals("POST");
    if (path.equals("/api/login") && post) {
      rate("login:" + e.getRemoteAddress().getAddress().getHostAddress(), 10);
      if (!service.healthy() && !host.platformAvailable()) throw new Failure(503, "数据库暂不可用，请稍后登录");
      JsonObject body = body(e);
      AccountStore.Account account =
          accounts.verify(text(body, "username", 64), text(body, "password", 256));
      if (account == null
          || !(host.platformAdmin(account.player())
              || service.snapshot().admins().contains(account.player())))
        throw new Failure(401, "用户名、密码或管理授权无效");
      long now = System.currentTimeMillis();
      sessions.entrySet().removeIf(entry -> entry.getValue().expires() < now);
      if (sessions.size() >= 512) throw new Failure(503, "会话数量已满，请稍后登录");
      String raw = token();
      Session session =
          new Session(
              account.username(),
              account.player(),
              account.version(),
              token(),
              now + config.sessionMinutes() * 60000L);
      sessions.put(digest(raw), session);
      cookie(e, raw, config.sessionMinutes() * 60);
      json(e, 200, me(session));
      return;
    }
    Session session = session(e);
    if (post
        && !MessageDigest.isEqual(
            session.csrf().getBytes(StandardCharsets.UTF_8),
            Optional.ofNullable(e.getRequestHeaders().getFirst("X-CSRF-Token"))
                .orElse("")
                .getBytes(StandardCharsets.UTF_8))) throw new Failure(403, "会话验证失败，请重新登录");
    if (path.equals("/api/logout") && post) {
      sessions.remove(sessionKey(e));
      cookie(e, "", 0);
      json(e, 200, Map.of("ok", true));
      return;
    }
    AccountStore.Account account = accounts.get(session.username());
    if (account == null || account.version() != session.accountVersion())
      throw new Failure(401, "密码已变更或账号已移除，请重新登录");
    TitleService.Actor actor =
        host.platformAdmin(session.player())
            ? new TitleService.Actor(session.player(), session.username(), false, true)
            : TitleService.Actor.player(session.player());
    TitleService.requireAdmin(service.snapshot(), actor);
    Snapshot s = service.snapshot();
    Map<String, String> q = query(e);
    if (path.equals("/api/me") && !post) {
      json(e, 200, me(session));
      return;
    }
    if (path.equals("/api/password") && post) {
      JsonObject b = body(e);
      if (!accounts.changePassword(
          session.username(), text(b, "current", 256), text(b, "password", 128)))
        throw new Failure(400, "当前密码不正确");
      sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(session.username()));
      cookie(e, "", 0);
      json(e, 200, Map.of("ok", true));
      return;
    }
    if (host.adminRoute(
        e,
        path,
        actor,
        () -> {
          AccountStore.Account current = accounts.get(session.username());
          return current != null
              && current.version() == session.accountVersion()
              && session.expires() > System.currentTimeMillis()
              && sessions.containsValue(session)
              && (host.platformAdmin(session.player())
                  || service.snapshot().admins().contains(session.player()));
        })) return;
    moduleApi(e, path, actor);
  }

  /** Invoked only after the platform authenticates the actor and validates CSRF. */
  void moduleApi(HttpExchange e, String path, TitleService.Actor actor) throws Exception {
    TitleService.requireAdmin(service.snapshot(), actor);
    boolean post = e.getRequestMethod().equals("POST");
    if (!post && !e.getRequestMethod().equals("GET")) throw new Failure(405, "不支持的请求方法");
    Snapshot s = service.snapshot();
    Map<String, String> q = query(e);
    if (path.equals("/api/state") && !post) {
      Map<String, Long> holders = new HashMap<>();
      s.owned().values().forEach(o -> holders.merge(o.title(), 1L, Long::sum));
      json(
          e,
          200,
          Map.of(
              "titles",
              s.titles().values().stream()
                  .sorted(
                      Comparator.comparingLong(Title::createdAt)
                          .reversed()
                          .thenComparing(Title::id))
                  .toList(),
              "holderCounts",
              holders,
              "settings",
              s.settings(),
              "health",
              health(s),
              "stamp",
              TitleService.definitionsStamp(s),
              "identities",
              s.players().values().stream()
                  .collect(
                      java.util.stream.Collectors.toMap(p -> p.id().toString(), Profile::name))));
      return;
    }
    if (path.equals("/api/titles") && post) {
      JsonObject b = body(e);
      JsonObject value = b.getAsJsonObject("title");
      long revision = b.get("expectedRevision").getAsLong();
      if (revision < 0) throw new IllegalArgumentException("修订号无效");
      if (!value.has("id") || value.get("id").getAsString().isBlank()) {
        if (revision != 0) throw new IllegalArgumentException("称号资料已失效，请重新载入");
        value.addProperty("id", "title_" + UUID.randomUUID().toString().replace("-", ""));
      }
      Title title = JSON.fromJson(value, Title.class);
      await(service.saveTitle(actor, title, revision));
      host.mirror();
      json(e, 200, Map.of("title", service.snapshot().titles().get(title.id())));
      return;
    }
    if (path.equals("/api/title-holders") && !post) {
      String title = q.getOrDefault("title", "");
      if (title.length() > 64) throw new IllegalArgumentException("称号标识过长");
      if (!s.titles().containsKey(title)) throw new Failure(404, "称号已不存在，请刷新称号档案");
      var collator = java.text.Collator.getInstance(Locale.CHINA);
      collator.setStrength(java.text.Collator.PRIMARY);
      List<TitleHolder> all =
          s.owned().values().stream()
              .filter(o -> o.title().equals(title))
              .map(
                  o -> {
                    Profile p = s.players().get(o.player());
                    return new TitleHolder(p.id(), p.name(), o.acquiredAt(), avatarVersion(p.id()));
                  })
              .sorted(
                  Comparator.comparing(TitleHolder::name, collator)
                      .thenComparing(TitleHolder::name)
                      .thenComparing(TitleHolder::id))
              .toList();
      int size = integer(q, "size", 20, 1, 100),
          page = Math.min(integer(q, "page", 0, 0, 100000), Math.max(0, (all.size() - 1) / size));
      json(
          e,
          200,
          Map.of(
              "rows",
              all.subList(page * size, Math.min(all.size(), (page + 1) * size)),
              "total",
              all.size(),
              "page",
              page,
              "size",
              size));
      return;
    }
    if (path.equals("/api/title-delete/preview") && post) {
      JsonObject b = body(e);
      json(e, 200, await(service.titleDeletePreview(actor, text(b, "title", 64))));
      return;
    }
    if (path.equals("/api/title-delete/confirm") && post) {
      JsonObject b = body(e);
      int removed =
          await(
              service.deleteTitle(
                  actor, text(b, "title", 64), text(b, "stamp", 64), text(b, "confirmName", 160)));
      host.mirror();
      json(e, 200, Map.of("ok", true, "removedHolders", removed));
      return;
    }
    if (path.startsWith("/api/avatars/") && !post) {
      String value = path.substring("/api/avatars/".length());
      if (!value.endsWith(".png")) throw new Failure(404, "头像不存在");
      UUID id = UUID.fromString(value.substring(0, value.length() - 4));
      if (!s.players().containsKey(id)) throw new Failure(404, "玩家未登记");
      bytes(e, 200, avatars.head(id), "image/png");
      return;
    }
    if (path.equals("/api/players") && !post) {
      String search = q.getOrDefault("q", "").toLowerCase(Locale.ROOT);
      if (search.length() > 128) throw new IllegalArgumentException("搜索文字过长");
      String presence =
          q.getOrDefault(
              "presence", q.getOrDefault("online", "false").equals("true") ? "online" : "all");
      String sort = q.getOrDefault("sort", "status"),
          direction = q.getOrDefault("direction", "asc");
      if (!Set.of("all", "online", "offline").contains(presence)
          || !Set.of("status", "name", "ownedCount", "equipped", "lastSeen").contains(sort)
          || !Set.of("asc", "desc").contains(direction))
        throw new IllegalArgumentException("玩家分类或排序无效");
      Set<UUID> online = host.online();
      Map<UUID, Long> counts = new HashMap<>();
      s.owned().values().forEach(o -> counts.merge(o.player(), 1L, Long::sum));
      var collator = java.text.Collator.getInstance(Locale.CHINA);
      collator.setStrength(java.text.Collator.PRIMARY);
      Comparator<PlayerRow> byName = (a, b) -> compareNames(a.name(), b.name(), collator);
      byName = byName.thenComparing(PlayerRow::name).thenComparing(PlayerRow::id);
      boolean descending = direction.equals("desc");
      Comparator<PlayerRow> order =
          switch (sort) {
            case "name" ->
                (a, b) -> compareNames(a.name(), b.name(), collator) * (descending ? -1 : 1);
            case "ownedCount" -> Comparator.comparingLong(PlayerRow::ownedCount);
            case "lastSeen" ->
                (a, b) ->
                    compareOptional(
                        a.lastSeen() <= 0 ? null : a.lastSeen(),
                        b.lastSeen() <= 0 ? null : b.lastSeen(),
                        descending);
            case "equipped" ->
                (a, b) ->
                    compareTitleNames(
                        s.titles().get(a.equipped()),
                        s.titles().get(b.equipped()),
                        collator,
                        descending);
            default -> Comparator.comparing(PlayerRow::online).reversed();
          };
      if (descending && (sort.equals("ownedCount") || sort.equals("status")))
        order = order.reversed();
      order = order.thenComparing(byName);
      long onlineCount = s.players().keySet().stream().filter(online::contains).count();
      List<PlayerRow> all =
          s.players().values().stream()
              .filter(p -> (p.name() + p.id()).toLowerCase(Locale.ROOT).contains(search))
              .filter(
                  p ->
                      presence.equals("all")
                          || online.contains(p.id()) == presence.equals("online"))
              .map(
                  p ->
                      new PlayerRow(
                          p.id(),
                          p.name(),
                          p.joinedAt(),
                          p.lastSeen(),
                          counts.getOrDefault(p.id(), 0L),
                          s.equipped().getOrDefault(p.id(), ""),
                          online.contains(p.id()),
                          s.admins().contains(p.id()),
                          avatarVersion(p.id())))
              .sorted(order)
              .toList();
      int size = integer(q, "size", 20, 1, 100),
          page = Math.min(integer(q, "page", 0, 0, 100000), Math.max(0, (all.size() - 1) / size));
      json(
          e,
          200,
          Map.of(
              "rows",
              all.subList(page * size, Math.min(all.size(), (page + 1) * size)),
              "total",
              all.size(),
              "page",
              page,
              "size",
              size,
              "presenceCounts",
              Map.of(
                  "all",
                  s.players().size(),
                  "online",
                  onlineCount,
                  "offline",
                  s.players().size() - onlineCount)));
      return;
    }
    if (path.equals("/api/player-delete/preview") && post) {
      JsonObject b = body(e);
      UUID id = UUID.fromString(text(b, "player", 36));
      if (accounts != null && accounts.list().stream().anyMatch(a -> a.endsWith(" → " + id)))
        throw new Failure(409, "该玩家关联网页账号，不属于本次玩家资料清理范围");
      json(e, 200, await(service.deletePreview(actor, id)));
      return;
    }
    if (path.equals("/api/player-delete/confirm") && post) {
      JsonObject b = body(e);
      UUID id = UUID.fromString(text(b, "player", 36));
      if (accounts != null && accounts.list().stream().anyMatch(a -> a.endsWith(" → " + id)))
        throw new Failure(409, "该玩家关联网页账号，不能从玩家资料入口清除");
      await(service.deletePlayer(actor, id, text(b, "stamp", 64), text(b, "confirmName", 64)));
      json(e, 200, Map.of("ok", true));
      return;
    }
    if (path.startsWith("/api/players/") && !post) {
      UUID id = UUID.fromString(path.substring("/api/players/".length()));
      Profile profile = s.players().get(id);
      if (profile == null) throw new Failure(404, "玩家未登记");
      json(
          e,
          200,
          Map.of(
              "profile",
              profile,
              "owned",
              s.collection(id, actor.player(), true),
              "equipped",
              s.equipped().getOrDefault(id, ""),
              "online",
              host.online().contains(id),
              "avatarVersion",
              avatarVersion(id),
              "receipts",
              s.receipts().values().stream().filter(v -> v.player().equals(id)).toList()));
      return;
    }
    if (path.equals("/api/grants") && post) {
      JsonObject b = body(e);
      List<UUID> ids = new ArrayList<>();
      for (JsonElement id : b.getAsJsonArray("players")) {
        if (ids.size() >= 1000) throw new IllegalArgumentException("单次最多1000名玩家");
        ids.add(UUID.fromString(id.getAsString()));
      }
      String mode = text(b, "mode", 10);
      if (!Set.of("grant", "revoke").contains(mode)) throw new IllegalArgumentException("操作类型无效");
      int count =
          await(
              mode.equals("grant")
                  ? service.grant(actor, ids, text(b, "title", 64), text(b, "reason", 2048))
                  : service.revoke(actor, ids, text(b, "title", 64), text(b, "reason", 2048)));
      json(e, 200, Map.of("count", count));
      return;
    }
    if (path.equals("/api/settings") && post) {
      JsonObject b = body(e);
      Settings expected =
          Objects.requireNonNull(JSON.fromJson(b.get("expected"), Settings.class), "须提供原设置");
      await(service.settings(actor, JSON.fromJson(b.get("settings"), Settings.class), expected));
      host.mirror();
      json(e, 200, Map.of("settings", service.snapshot().settings()));
      return;
    }
    if (path.equals("/api/history") && !post) {
      json(
          e,
          200,
          await(
              service.searchHistory(
                  actor,
                  q.getOrDefault("q", ""),
                  q.getOrDefault("action", ""),
                  q.getOrDefault("player", ""),
                  q.getOrDefault("title", ""),
                  integer(q, "page", 0, 0, 100000),
                  integer(q, "size", 20, 1, 100))));
      return;
    }
    if (path.startsWith("/api/history/") && path.endsWith("/changes") && !post) {
      long audit =
          Long.parseLong(
              path.substring("/api/history/".length(), path.length() - "/changes".length()));
      json(e, 200, Map.of("rows", await(service.changes(actor, audit))));
      return;
    }
    if (path.equals("/api/undo") && post) {
      JsonObject b = body(e);
      if (!b.has("confirm") || !b.get("confirm").getAsBoolean())
        throw new Failure(400, "请先确认要撤回的具体改动");
      await(service.undo(actor, b.get("change").getAsLong()));
      host.mirror();
      json(e, 200, Map.of("ok", true));
      return;
    }
    if (path.equals("/api/backups") && !post) {
      json(e, 200, listBackups());
      return;
    }
    if (path.equals("/api/backup") && post) {
      Path file = await(host.backup(actor));
      json(e, 200, Map.of("file", file.getFileName().toString()));
      return;
    }
    if (path.startsWith("/api/backups/") && !post) {
      String tail = path.substring("/api/backups/".length());
      boolean checksum = tail.endsWith("/checksum");
      String suffix = checksum ? "/checksum" : "/download";
      if (!tail.endsWith(suffix)) throw new Failure(404, "下载不存在");
      String name = tail.substring(0, tail.length() - suffix.length());
      Path file = backupFile(name);
      if (checksum) file = file.resolveSibling(file.getFileName() + ".sha256");
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) > (checksum ? 4096 : 64L * 1024 * 1024))
        throw new Failure(404, "备份或校验文件不存在");
      e.getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=\"" + file.getFileName() + "\"");
      bytes(
          e,
          200,
          Files.readAllBytes(file),
          checksum ? "text/plain; charset=utf-8" : "application/gzip");
      return;
    }
    if (path.equals("/api/backup-preview") && post) {
      String name = text(body(e), "file", 160);
      Backup.Archive archive = await(service.background(() -> Backup.read(backupFile(name))));
      String message = await(service.importPreview(actor, archive));
      long conflicts =
          archive.titles().stream()
              .filter(t -> s.titles().containsKey(t.id()) && !s.titles().get(t.id()).equals(t))
              .count();
      json(
          e,
          200,
          Map.of(
              "message",
              message,
              "titles",
              archive.titles().size(),
              "players",
              archive.players().size(),
              "owned",
              archive.owned().size(),
              "audit",
              archive.audit().size(),
              "admins",
              archive.admins().size(),
              "conflicts",
              conflicts,
              "createdAt",
              archive.createdAt()));
      return;
    }
    if (path.equals("/api/definitions") && !post) {
      e.getResponseHeaders()
          .set("Content-Disposition", "attachment; filename=\"definitions.json\"");
      json(e, 200, definitions(s));
      return;
    }
    if ((path.equals("/api/definitions/preview") || path.equals("/api/definitions/apply"))
        && post) {
      JsonObject b = body(e);
      Definitions d = JSON.fromJson(b.get("definitions"), Definitions.class);
      validateDefinitions(s, d);
      if (path.endsWith("/apply")) {
        await(service.reload(actor, d.titles(), d.settings(), text(b, "stamp", 64)));
        host.mirror();
        json(e, 200, Map.of("ok", true));
      } else {
        long added = d.titles().stream().filter(t -> !s.titles().containsKey(t.id())).count();
        json(
            e,
            200,
            Map.of(
                "stamp",
                TitleService.definitionsStamp(s),
                "added",
                added,
                "updated",
                d.titles().size() - added,
                "retained",
                s.titles().keySet().stream()
                    .filter(id -> d.titles().stream().noneMatch(t -> t.id().equals(id)))
                    .count(),
                "settingsChanged",
                !d.settings().equals(s.settings())));
      }
      return;
    }
    if (path.equals("/api/reload") && post) {
      await(host.reload(actor));
      json(e, 200, Map.of("ok", true));
      return;
    }
    throw new Failure(404, "接口不存在");
  }

  public record Definitions(int format, List<Title> titles, Settings settings) {}

  public static Definitions definitions(Snapshot s) {
    return new Definitions(
        1,
        s.titles().values().stream().sorted(Comparator.comparing(Title::id)).toList(),
        s.settings());
  }

  private static void validateDefinitions(Snapshot s, Definitions d) {
    if (d == null
        || d.format() != 1
        || d.titles() == null
        || d.settings() == null
        || d.titles().size() > 10000) throw new IllegalArgumentException("配置格式无效");
    Set<String> ids = new HashSet<>();
    Map<String, Title> merged = new HashMap<>(s.titles());
    for (Title t : d.titles()) {
      if (!ids.add(t.id())) throw new IllegalArgumentException("重复称号ID：" + t.id());
      merged.put(t.id(), t);
    }
    if (!d.settings().reward().isEmpty()) {
      Title t = merged.get(d.settings().reward());
      if (t == null || !t.issuable()) throw new IllegalArgumentException("首登奖励不存在或已停发");
    }
  }

  private Map<String, Object> health(Snapshot s) {
    return Map.of(
        "database",
        service.healthy(),
        "lastSuccess",
        service.lastSuccess(),
        "titles",
        s.titles().size(),
        "players",
        s.players().size(),
        "online",
        host.online().size(),
        "managed",
        service.managed(),
        "version",
        "0.5.0-dev");
  }

  // Match the web console's pinyin/letter ordering, including natural numeric names.
  private static int compareNames(String a, String b, java.text.Collator collator) {
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      boolean digits = Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j));
      int ai = i + 1, bj = j + 1;
      while (ai < a.length() && Character.isDigit(a.charAt(ai)) == Character.isDigit(a.charAt(i)))
        ai++;
      while (bj < b.length() && Character.isDigit(b.charAt(bj)) == Character.isDigit(b.charAt(j)))
        bj++;
      int comparison =
          digits
              ? new java.math.BigInteger(a.substring(i, ai))
                  .compareTo(new java.math.BigInteger(b.substring(j, bj)))
              : collator.compare(a.substring(i, ai), b.substring(j, bj));
      if (comparison != 0) return comparison;
      i = ai;
      j = bj;
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private static <T extends Comparable<T>> int compareOptional(T a, T b, boolean descending) {
    if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
    return a.compareTo(b) * (descending ? -1 : 1);
  }

  private static int compareTitleNames(
      Title a, Title b, java.text.Collator collator, boolean descending) {
    String left = a == null ? "" : a.name().plain(), right = b == null ? "" : b.name().plain();
    if (left.isEmpty() || right.isEmpty())
      return left.isEmpty() == right.isEmpty() ? 0 : left.isEmpty() ? 1 : -1;
    return compareNames(left, right, collator) * (descending ? -1 : 1);
  }

  private Map<String, Object> me(Session session) {
    Profile p = service.snapshot().players().get(session.player());
    return Map.of(
        "username",
        session.username(),
        "player",
        session.player(),
        "playerName",
        p == null ? session.player().toString() : p.name(),
        "csrf",
        session.csrf(),
        "expiresAt",
        session.expires());
  }

  private Session session(HttpExchange e) {
    Session s = sessions.get(sessionKey(e));
    if (s == null || s.expires() < System.currentTimeMillis()) {
      sessions.remove(sessionKey(e));
      throw new Failure(401, "请登录管理控制台");
    }
    return s;
  }

  private String sessionKey(HttpExchange e) {
    for (String pair :
        Optional.ofNullable(e.getRequestHeaders().getFirst("Cookie")).orElse("").split(";")) {
      String value = pair.trim();
      String prefix = "MTSESSION_" + port() + "=";
      if (value.startsWith(prefix)) {
        String raw = value.substring(prefix.length());
        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\""))
          raw = raw.substring(1, raw.length() - 1);
        return digest(raw);
      }
    }
    return "";
  }

  private void cookie(HttpExchange e, String value, int seconds) {
    e.getResponseHeaders()
        .add(
            "Set-Cookie",
            "MTSESSION_"
                + port()
                + "="
                + value
                + "; Path=/; HttpOnly; SameSite=Strict; Max-Age="
                + seconds
                + (config.publicUrl().startsWith("https://") ? "; Secure" : ""));
  }

  private List<Map<String, Object>> listBackups() throws Exception {
    if (!Files.isDirectory(backupFolder)) return List.of();
    try (var files = Files.list(backupFolder)) {
      List<Map<String, Object>> out = new ArrayList<>();
      for (Path p :
          files
              .filter(
                  f ->
                      f.getFileName().toString().matches("[a-zA-Z0-9_.-]+\\.json\\.gz")
                          && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.reverseOrder())
              .limit(2000)
              .toList())
        out.add(
            Map.of(
                "file",
                p.getFileName().toString(),
                "bytes",
                Files.size(p),
                "modified",
                Files.getLastModifiedTime(p).toMillis(),
                "checksum",
                Files.isRegularFile(
                    p.resolveSibling(p.getFileName() + ".sha256"), LinkOption.NOFOLLOW_LINKS)));
      return List.copyOf(out);
    }
  }

  private Path backupFile(String name) {
    if (!name.matches("[a-zA-Z0-9_.-]+\\.json\\.gz"))
      throw new IllegalArgumentException("只接受备份目录内的备份文件名");
    Path path = backupFolder.resolve(name).normalize();
    if (!path.getParent().equals(backupFolder)
        || Files.isSymbolicLink(path)
        || Files.isSymbolicLink(path.resolveSibling(path.getFileName() + ".sha256")))
      throw new IllegalArgumentException("备份路径无效");
    return path;
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(30, TimeUnit.SECONDS);
  }

  private static Map<String, String> query(HttpExchange e) {
    Map<String, String> out = new HashMap<>();
    String raw = e.getRequestURI().getRawQuery();
    if (raw != null)
      for (String pair : raw.split("&")) {
        String[] v = pair.split("=", 2);
        out.put(
            URLDecoder.decode(v[0], StandardCharsets.UTF_8),
            v.length == 1 ? "" : URLDecoder.decode(v[1], StandardCharsets.UTF_8));
      }
    return out;
  }

  private static int integer(Map<String, String> q, String name, int fallback, int min, int max) {
    int value = Integer.parseInt(q.getOrDefault(name, Integer.toString(fallback)));
    if (value < min || value > max) throw new IllegalArgumentException("分页范围无效");
    return value;
  }

  private static String text(JsonObject b, String key, int max) {
    if (b == null
        || !b.has(key)
        || !b.get(key).isJsonPrimitive()
        || !b.getAsJsonPrimitive(key).isString())
      throw new IllegalArgumentException("缺少文字字段：" + key);
    String value = b.get(key).getAsString();
    check(value, max);
    return value;
  }

  private static JsonObject body(HttpExchange e) throws Exception {
    byte[] bytes = e.getRequestBody().readNBytes(8 * 1024 * 1024 + 1);
    if (bytes.length > 8 * 1024 * 1024) throw new Failure(413, "请求超过8MiB");
    String raw = new String(bytes, StandardCharsets.UTF_8);
    int depth = 0;
    boolean string = false, escape = false;
    for (char c : raw.toCharArray()) {
      if (string) {
        if (escape) escape = false;
        else if (c == '\\') escape = true;
        else if (c == '\"') string = false;
      } else if (c == '\"') string = true;
      else if (c == '{' || c == '[') {
        if (++depth > 32) throw new IllegalArgumentException("JSON嵌套过深");
      } else if (c == '}' || c == ']') depth--;
    }
    return JsonParser.parseString(raw).getAsJsonObject();
  }

  private void assets(HttpExchange e, String path) throws Exception {
    if (path.contains("..") || path.contains("\\") || path.indexOf('\0') >= 0)
      throw new Failure(404, "页面不存在");
    if (path.equals("/") || !path.substring(path.lastIndexOf('/') + 1).contains("."))
      path = "/index.html";
    String type =
        path.endsWith(".html")
            ? "text/html; charset=utf-8"
            : path.endsWith(".js")
                ? "text/javascript; charset=utf-8"
                : path.endsWith(".css")
                    ? "text/css; charset=utf-8"
                    : path.endsWith(".woff2")
                        ? "font/woff2"
                        : path.endsWith(".svg")
                            ? "image/svg+xml"
                            : path.endsWith(".png") ? "image/png" : "application/octet-stream";
    try (InputStream stream = AdminWebServer.class.getResourceAsStream("/web" + path)) {
      if (stream == null) throw new Failure(404, "网页资源未找到，请使用包含网页的完整模组JAR");
      if (path.startsWith("/assets/"))
        e.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
      bytes(e, 200, stream.readAllBytes(), type);
    }
  }

  private static void json(HttpExchange e, int status, Object value) throws IOException {
    bytes(
        e,
        status,
        JSON.toJson(value).getBytes(StandardCharsets.UTF_8),
        "application/json; charset=utf-8");
  }

  private static void bytes(HttpExchange e, int status, byte[] value, String type)
      throws IOException {
    e.getResponseHeaders().set("Content-Type", type);
    e.sendResponseHeaders(status, value.length);
    try (OutputStream out = e.getResponseBody()) {
      out.write(value);
    }
  }

  @Override
  public void close() {
    if (http != null) http.stop(1);
    if (executor != null) executor.shutdownNow();
    if (avatars != null) avatars.close();
    sessions.clear();
  }
}
