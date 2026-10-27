package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.memorialtitles.web.TitlesPlatformAdapter;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Real loopback MariaDB with a controlled Manager directory/admission boundary. */
public final class UnifiedPlatformIntegrationSuite {
  private static int checks;
  private static final TitleService.Actor ADMIN = TitleService.Actor.consoleActor();

  private static void check(boolean result, String label) {
    if (!result) throw new AssertionError(label);
    checks++;
    System.out.println("PASS " + label);
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(25, TimeUnit.SECONDS);
  }

  private static long count(Database db, String table) throws Exception {
    try (var c = db.open();
        var s = c.createStatement();
        var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
      r.next();
      return r.getLong(1);
    }
  }

  private static JsonObject identity(UUID id, String name, long joined) {
    JsonObject result = new JsonObject();
    result.addProperty("id", id.toString());
    result.addProperty("name", name);
    result.addProperty("firstJoinedAt", joined);
    result.addProperty("lastSeen", 12345);
    return result;
  }

  private static JsonObject admission(boolean admitted, boolean first) {
    JsonObject result = new JsonObject();
    result.addProperty("admitted", admitted);
    result.addProperty("eventId", "trusted-event");
    result.addProperty("firstGlobalLogin", first);
    result.addProperty("firstArchiveLogin", false);
    result.addProperty("archiveId", "archive-1");
    return result;
  }

  private static Title title(String id) {
    return new Title(id, RichText.plain(id), "", "", "", "", 0, 0, true, true, 0);
  }

  private static JsonObject json(HttpResponse<String> response) {
    return JsonParser.parseString(response.body()).getAsJsonObject();
  }

  public static void main(String[] args) throws Exception {
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43307/";
    String schema = "mt_unified_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Database root = new Database(new Database.Config(base, "root", password));
    try (var c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
    }
    Database db = new Database(new Database.Config(base + schema, "root", password));
    UUID old = UUID.randomUUID(),
        zero = UUID.randomUUID(),
        fresh = UUID.randomUUID(),
        rejected = UUID.randomUUID();
    Map<UUID, JsonObject> players = new LinkedHashMap<>();
    players.put(old, identity(old, "Renamed", 7));
    players.put(zero, identity(zero, "Alpha", 0));
    // Deliberately historical wall-clock time: trusted event flags alone decide eligibility.
    players.put(fresh, identity(fresh, "Fresh", 1));
    players.put(rejected, identity(rejected, "Rejected", 2));
    Map<UUID, JsonObject> contexts = new HashMap<>();
    contexts.put(old, admission(true, false));
    contexts.put(fresh, admission(true, true));
    contexts.put(rejected, admission(false, true));
    contexts.put(zero, admission(true, false));
    AtomicInteger directoryCalls = new AtomicInteger(), mirrorCalls = new AtomicInteger();
    Set<String> operations = ConcurrentHashMap.newKeySet();
    Function<JsonObject, JsonObject> platform =
        request -> {
          String op = request.get("op").getAsString();
          operations.add(op);
          UUID id =
              request.has("player") ? UUID.fromString(request.get("player").getAsString()) : null;
          JsonObject result = new JsonObject();
          switch (op) {
            case "profiles.list" -> {
              directoryCalls.incrementAndGet();
              result.add("players", JSON.toJsonTree(players.values()));
              result.addProperty("revision", 1);
            }
            case "profiles.get" -> result.add("profile", players.getOrDefault(id, null));
            case "profiles.loginContext" -> {
              return contexts.getOrDefault(id, new JsonObject());
            }
            case "avatars.version" -> result.addProperty("version", 91);
            case "titles.mirror" -> {
              mirrorCalls.incrementAndGet();
              result.addProperty("ok", true);
            }
            default -> throw new IllegalArgumentException("Unexpected platform operation " + op);
          }
          return result;
        };
    try (TitleService service = new TitleService(db)) {
      await(service.reconnect());
      await(service.login(old, "LegacyName"));
      await(service.saveTitle(ADMIN, title("reward"), 0));
      await(service.saveTitle(ADMIN, title("manual"), 0));
      Settings x = service.snapshot().settings();
      await(
          service.settings(
              ADMIN,
              new Settings(
                  "reward",
                  x.head(),
                  x.chat(),
                  x.tab(),
                  x.headScale(),
                  x.headOffset(),
                  x.chatPrefix(),
                  x.retentionDays())));
      service.attachPlatform(platform);
      check(
          service.snapshot().players().size() == 4 && count(db, "mt_players") == 1,
          "global roster projection includes zero-title players without creating plugin"
              + " participation rows");
      check(
          service.snapshot().players().get(old).name().equals("Renamed")
              && service.snapshot().players().get(zero).joinedAt() == 0,
          "global directory owns current names and preserves unknown historic timestamps");
      int before = directoryCalls.get();
      for (int i = 0; i < 100; i++) service.snapshot();
      check(
          directoryCalls.get() == before,
          "frequent snapshot reads reuse the bounded one-second identity cache");
      await(service.login(old, "UntrustedSuppliedName"));
      check(
          service.snapshot().ownership(old, "reward") == null && count(db, "mt_receipts") == 0,
          "existing global player is not treated as a new player when Titles first sees a login");
      check(
          service.snapshot().players().get(old).name().equals("Renamed"),
          "login never replaces trusted name with transport argument");
      await(service.login(fresh, "Fresh"));
      await(service.login(fresh, "Fresh"));
      check(
          service.snapshot().ownership(fresh, "reward") != null && count(db, "mt_receipts") == 1,
          "trusted first global login awards exactly once independent of wall-clock guesses");
      check(
          await(service.notifications(fresh)).size() == 1,
          "repeat login does not duplicate the reward notification");
      boolean denied = false;
      try {
        await(service.login(rejected, "Rejected"));
      } catch (ExecutionException error) {
        denied = true;
      }
      check(
          denied
              && count(db, "mt_players") == 2
              && service.snapshot().ownership(rejected, "reward") == null,
          "rejected admissions cannot materialize a player or receive a reward");
      await(service.grant(ADMIN, List.of(zero), "manual", "manual grant to directory-only player"));
      check(
          service.snapshot().ownership(zero, "manual") != null && count(db, "mt_receipts") == 1,
          "manual grant materializes only its FK cache without login rewards or receipts");
      await(service.toggleDisplayPosition(zero, DisplayPosition.TAB));
      await(service.setUnequippedReminder(zero, false));
      check(
          !service.snapshot().displayPreferences(zero).tab()
              && !service.snapshot().displayPreferences(zero).reminderEnabled(),
          "managed player display and reminder preferences remain durable");

      players.put(zero, identity(zero, "DirectoryRenamed", 0));
      service.attachPlatform(platform);
      check(
          await(service.searchHistory(ADMIN, "DirectoryRenamed", "", "", "", 0, 20)).total() > 0,
          "history search resolves current global names even when the legacy FK name cache is"
              + " older");
      players.put(zero, identity(zero, "Alpha", 0));

      TitlesPlatformAdapter adapter = new TitlesPlatformAdapter(service, platform);
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
      server.createContext(
          "/",
          exchange -> {
            try {
              if (!"owner-session".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
              }
              boolean handled =
                  adapter.handle(exchange, exchange.getRequestURI().getPath(), old, Set.of());
              if (!handled) exchange.sendResponseHeaders(404, -1);
            } catch (Exception error) {
              throw new RuntimeException(error);
            } finally {
              exchange.close();
            }
          });
      server.start();
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      HttpClient http = HttpClient.newHttpClient();
      try {
        var unauthorized =
            http.send(
                HttpRequest.newBuilder(URI.create(url + "/api/players")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        check(
            unauthorized.statusCode() == 401,
            "module routes are reachable only through the platform authentication boundary");
        var list =
            http.send(
                HttpRequest.newBuilder(URI.create(url + "/api/players"))
                    .header("Authorization", "owner-session")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonObject roster = json(list);
        check(
            list.statusCode() == 200 && roster.get("total").getAsInt() == 4,
            "managed HTTP list exposes the same complete global player roster");
        check(
            roster.getAsJsonArray("rows").get(0).getAsJsonObject().get("avatarVersion").getAsInt()
                == 91,
            "managed HTTP rows use platform avatar versions rather than a Titles cache");
        var backups =
            http.send(
                HttpRequest.newBuilder(URI.create(url + "/api/backups"))
                    .header("Authorization", "owner-session")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        check(
            backups.statusCode() == 404,
            "platform retains backup ownership instead of silently invoking legacy title backups");
        boolean onlineDeleteDenied = false;
        try {
          await(service.deletePreview(ADMIN, zero));
        } catch (ExecutionException expected) {
          onlineDeleteDenied = true;
        }
        check(
            onlineDeleteDenied,
            "plugin cleanup consults live trusted admission even before an online-list refresh");
        contexts.put(zero, admission(false, false));
        var preview = await(service.deletePreview(ADMIN, zero));
        check(
            preview.managed() && !preview.avatar(),
            "plugin deletion preview declares global identity/avatar preservation");
        await(service.deletePlayer(ADMIN, zero, preview.stamp(), "Alpha"));
        check(
            service.snapshot().players().containsKey(zero)
                && service.snapshot().ownership(zero, "manual") == null
                && players.containsKey(zero)
                && !operations.contains("avatars.delete"),
            "plugin cleanup removes title records while preserving the global identity and avatar");
        var audit =
            await(service.history(ADMIN, 100)).stream()
                .filter(a -> a.action().equals("PLAYER_DELETE"))
                .findFirst()
                .orElseThrow();
        var change = await(service.changes(ADMIN, audit.id())).get(0);
        check(
            change.allowed() && change.changes().stream().noneMatch(d -> d.key().startsWith("V|")),
            "managed cleanup retains selective undo without taking ownership of global avatar"
                + " data");
        await(service.undo(ADMIN, change.id()));
        check(
            service.snapshot().ownership(zero, "manual") != null
                && !service.snapshot().displayPreferences(zero).tab(),
            "selective undo restores plugin collection and preferences");
        contexts.put(zero, admission(true, false));
        await(service.login(zero, "Alpha"));
        check(
            service.snapshot().ownership(zero, "reward") == null,
            "cleanup and rejoin cannot reclassify an old player as a first login");
        check(
            TitlesPlatformAdapter.storageManifest().getAsJsonArray("tables").size() == 14,
            "manifest registers all durable title tables for unified backups");
      } finally {
        server.stop(0);
      }
      service.attachPlatform(null);
      UUID standalone = UUID.randomUUID();
      await(service.login(standalone, "Standalone"));
      check(
          service.snapshot().ownership(standalone, "reward") != null,
          "detached standalone mode retains its original first-participation reward behavior");
      System.out.println("PASS " + checks + " unified platform checks");
    } finally {
      try (var c = root.open()) {
        Database.execute(c, "DROP DATABASE " + schema);
      }
    }
  }
}
