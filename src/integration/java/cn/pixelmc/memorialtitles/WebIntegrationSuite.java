package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.*;
import cn.pixelmc.memorialtitles.web.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real HTTP + real MariaDB, in a new disposable database. No Forge state is mocked in production.
 */
public final class WebIntegrationSuite {
  private static final List<String> passed = new ArrayList<>();

  private static void check(boolean value, String label) {
    if (!value) throw new AssertionError(label);
    passed.add(label);
    System.out.println("PASS " + label);
  }

  private static <T> T waitFor(CompletableFuture<T> value) throws Exception {
    return value.get(30, TimeUnit.SECONDS);
  }

  private static Title title(String id, String name) {
    return new Title(id, RichText.plain(name), "说明", "香草纪元", "管理员发放", "纪念", 0, 7, false, true, 0);
  }

  private static String firstName(JsonObject page) {
    return page.getAsJsonArray("rows").get(0).getAsJsonObject().get("name").getAsString();
  }

  private static void listingChecks(Browser b, TitleService service, Database root, String dbName,
      UUID admin, UUID bob) throws Exception {
    var actor = TitleService.Actor.consoleActor();
    List<UUID> players = new ArrayList<>();
    for (int i = 45; i >= 1; i--) {
      UUID id = UUID.nameUUIDFromBytes(("listing-player-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      waitFor(service.login(id, "PagePlayer" + i));
      players.add(0, id);
    }
    var all = b.json(b.get("/api/players?q=PagePlayer&size=20&page=1&sort=name&direction=asc"));
    check(all.get("total").getAsInt() == 45 && firstName(all).equals("PagePlayer21"),
        "player natural-name sort covers the complete collection before pagination");
    check(firstName(b.json(b.get("/api/players?q=PagePlayer&size=20&page=1&sort=name&direction=desc"))).equals("PagePlayer25"),
        "descending player sort crosses page boundaries");
    JsonObject counts = all.getAsJsonObject("presenceCounts");
    check(counts.get("all").getAsInt() == service.snapshot().players().size()
        && counts.get("online").getAsInt() == 1
        && counts.get("offline").getAsInt() == service.snapshot().players().size() - 1,
        "presence counts cover all registered profiles independently of search and pagination");
    var online = b.json(b.get("/api/players?presence=online"));
    check(online.get("total").getAsInt() == 1
        && online.getAsJsonArray("rows").get(0).getAsJsonObject().get("id").getAsString().equals(admin.toString()),
        "online presence returns registered online players");
    check(b.json(b.get("/api/players?presence=offline&q=PagePlayer")).get("total").getAsInt() == 45,
        "offline presence filters the full collection");
    check(firstName(b.json(b.get("/api/players?sort=status&direction=desc"))).equals("Alpha"),
        "status reversal only reverses groups and keeps names ascending within a group");
    check(b.json(b.get("/api/players?online=true")).get("total").getAsInt() == 1,
        "legacy online parameter remains supported");
    waitFor(service.saveTitle(actor, title("z_sort_id", "AlphaCap"), 0));
    waitFor(service.saveTitle(actor, title("a_sort_id", "ZuluCap"), 0));
    waitFor(service.grant(actor, List.of(players.get(0)), "z_sort_id", "listing test"));
    waitFor(service.grant(actor, List.of(players.get(1)), "a_sort_id", "listing test"));
    waitFor(service.grant(actor, List.of(players.get(1)), "z_sort_id", "listing test"));
    waitFor(service.equip(players.get(0), "z_sort_id"));
    waitFor(service.equip(players.get(1), "a_sort_id"));
    check(firstName(b.json(b.get("/api/players?q=PagePlayer&sort=equipped&direction=asc"))).equals("PagePlayer1")
        && firstName(b.json(b.get("/api/players?q=PagePlayer&sort=equipped&direction=desc"))).equals("PagePlayer2"),
        "equipped sort uses title display names and leaves missing equipment last in both directions");
    check(firstName(b.json(b.get("/api/players?q=PagePlayer&sort=ownedCount&direction=desc"))).equals("PagePlayer2")
        && firstName(b.json(b.get("/api/players?q=PagePlayer&sort=ownedCount&direction=asc"))).equals("PagePlayer3"),
        "collection counts sort numerically and stabilize equal values by natural player name");
    try (Connection c = root.open(); var stmt = c.prepareStatement("UPDATE " + dbName + ".mt_players SET last_seen=? WHERE uuid=?")) {
      for (int i = 0; i < players.size(); i++) {
        stmt.setLong(1, i == 44 ? 0 : 100000L + i * 1000L);
        stmt.setString(2, players.get(i).toString()); stmt.executeUpdate();
      }
    }
    waitFor(service.reconnect());
    check(firstName(b.json(b.get("/api/players?q=PagePlayer&sort=lastSeen&direction=asc"))).equals("PagePlayer1")
        && firstName(b.json(b.get("/api/players?q=PagePlayer&sort=lastSeen&direction=desc"))).equals("PagePlayer44"),
        "last login sorts by real timestamps with unknown times last in both directions");
    var empty = b.json(b.get("/api/players?q=no-such-player&page=999"));
    check(empty.getAsJsonArray("rows").isEmpty() && empty.get("page").getAsInt() == 0
        && empty.getAsJsonObject("presenceCounts").equals(counts),
        "empty search clamps pagination and preserves complete presence counts");
    check(b.get("/api/players?presence=invalid").statusCode() == 400
        && b.get("/api/players?sort=invalid").statusCode() == 400
        && b.get("/api/players?direction=invalid").statusCode() == 400,
        "invalid presence and sort options are rejected explicitly");
    var history = b.json(b.get("/api/history?size=1"));
    var searched = b.json(b.get("/api/history?q=no-such-history&action=GRANT,REVOKE&size=1&page=999"));
    check(searched.get("total").getAsInt() == 0
        && searched.getAsJsonArray("categoryCounts").equals(history.getAsJsonArray("categoryCounts")),
        "history category totals are independent of keyword, selected category and page");
    var scoped = b.json(b.get("/api/history?player=" + bob + "&size=1"));
    var scopedSearch = b.json(b.get("/api/history?player=" + bob + "&q=missing&action=TITLE_SAVE&size=1"));
    check(scoped.getAsJsonArray("categoryCounts").equals(scopedSearch.getAsJsonArray("categoryCounts"))
        && scoped.getAsJsonArray("categoryCounts").get(0).getAsLong() == scoped.get("total").getAsLong(),
        "history category totals retain the current player's scope");
    try (Connection c = root.open()) {
      Database.execute(c, "UPDATE " + dbName + ".mt_audit SET at_ms=1999999999999 ORDER BY id ASC LIMIT 1");
    }
    var recent = b.json(b.get("/api/history?size=100")).getAsJsonArray("rows");
    long previous = Long.MAX_VALUE;
    boolean latestFirst = true;
    for (var row : recent) { long at = row.getAsJsonObject().get("at").getAsLong(); latestFirst &= at <= previous; previous = at; }
    check(latestFirst && recent.get(0).getAsJsonObject().get("at").getAsLong() == 1999999999999L,
        "history stays latest first by timestamp even when audit IDs have a different order");
  }

  private static final class Browser {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    final String base;
    String csrf = "";

    Browser(String base) {
      this.base = base;
    }

    HttpResponse<String> request(String path, Object body, boolean correctCsrf, String origin)
        throws Exception {
      var b =
          HttpRequest.newBuilder(URI.create(base + path)).timeout(java.time.Duration.ofSeconds(35));
      if (body == null) b.GET();
      else
        b.header("Content-Type", "application/json")
            .header("Origin", origin == null ? base : origin)
            .header("X-CSRF-Token", correctCsrf ? csrf : "wrong")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body)));
      return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(String path) throws Exception {
      return request(path, null, true, null);
    }

    HttpResponse<String> post(String path, Object body) throws Exception {
      return request(path, body, true, null);
    }

    JsonObject json(HttpResponse<String> response) {
      return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    HttpResponse<String> login(String password) throws Exception {
      var r = post("/api/login", Map.of("username", "owner", "password", password));
      if (r.statusCode() == 200) csrf = json(r).get("csrf").getAsString();
      return r;
    }
  }

  public static void main(String[] args) throws Exception {
    String password = System.getenv("MT_TEST_PASSWORD");
    if (password == null) throw new IllegalStateException("Set MT_TEST_PASSWORD");
    String dbName = "mt_web_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    Database root =
        new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43321/", "root", password));
    try (Connection c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4");
    }
    Path folder = Path.of("build/web-it", dbName);
    Files.createDirectories(folder);
    var console = TitleService.Actor.consoleActor();
    UUID admin = UUID.randomUUID(), bob = UUID.randomUUID();
    try (TitleService s =
        new TitleService(
            new Database(
                new Database.Config(
                    "jdbc:mariadb://127.0.0.1:43321/" + dbName, "root", password)))) {
      waitFor(s.reconnect());
      waitFor(s.login(admin, "网页管理员"));
      waitFor(s.login(bob, "测试玩家"));
      waitFor(s.authorize(console, admin, true));
      waitFor(s.saveTitle(console, title("old", "旧纪念"), 0));
      long originalCreated = s.snapshot().titles().get("old").createdAt();
      try (Connection c = root.open()) {
        Database.execute(c, "USE " + dbName);
        Database.execute(
            c, "UPDATE mt_titles SET definition=JSON_REMOVE(definition,'$.createdAt')");
        Database.execute(c, "DELETE FROM mt_schema WHERE version>=2");
      }
      waitFor(s.reconnect());
      check(
          s.snapshot().titles().get("old").createdAt() >= originalCreated
              && s.snapshot().titles().get("old").createdAt() - originalCreated < 1000,
          "legacy title creation dates migrate from earliest save audit");
      AtomicInteger changes = new AtomicInteger();
      s.listen(snapshot -> changes.incrementAndGet());
      AdminWebServer.Host host =
          new AdminWebServer.Host() {
            public Set<UUID> online() {
              return Set.of(admin);
            }

            public void mirror() {
              try {
                Files.writeString(
                    folder.resolve("definitions.json"),
                    JSON.toJson(AdminWebServer.definitions(s.snapshot())));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }

            public CompletableFuture<Path> backup(TitleService.Actor actor) {
              return s.export(actor)
                  .thenCompose(a -> s.background(() -> Backup.write(folder.resolve("backups"), a)));
            }

            public CompletableFuture<Void> reload(TitleService.Actor actor) {
              return s.background(
                      () ->
                          JSON.fromJson(
                              Files.readString(folder.resolve("definitions.json")),
                              AdminWebServer.Definitions.class))
                  .thenCompose(d -> s.reload(actor, d.titles(), d.settings()));
            }
          };
      try (AdminWebServer web =
          new AdminWebServer(
              s,
              host,
              new WebConfig(true, "127.0.0.1", 18781, "http://127.0.0.1:18781", 60, List.of()),
              folder,
              folder.resolve("backups"))) {
        web.accounts.create("owner", admin, "Web_Test_Password_123");
        check(
            !Files.readString(folder.resolve("web-accounts.json"))
                .contains("Web_Test_Password_123"),
            "password file contains salted hashes only");
        Browser b = new Browser("http://127.0.0.1:18781"), anonymous = new Browser(b.base);
        check(b.get("/").statusCode() == 200, "embedded production index is served");
        check(
            anonymous.get("/api/state").statusCode() == 401,
            "anonymous data access is rejected with JSON error");
        check(b.login("wrong").statusCode() == 401, "incorrect password denied");
        var login = b.login("Web_Test_Password_123");
        check(
            login.statusCode() == 200
                && login
                    .headers()
                    .firstValue("Set-Cookie")
                    .orElse("")
                    .contains("HttpOnly; SameSite=Strict"),
            "username and password create protected session cookie");
        var stateResponse = b.get("/api/state");
        check(
            stateResponse.statusCode() == 200 && !stateResponse.body().contains(password),
            "authenticated state contains no database credential (HTTP "
                + stateResponse.statusCode()
                + ")");
        Title fresh = title("web_title", "网页称号");
        Map<String, Object> save = Map.of("title", fresh, "expectedRevision", 0);
        check(
            b.request("/api/titles", save, false, null).statusCode() == 403
                && !s.snapshot().titles().containsKey(fresh.id()),
            "missing CSRF cannot mutate title state");
        check(
            b.request("/api/titles", save, true, "http://foreign.invalid").statusCode() == 403,
            "cross-origin mutation rejected");
        check(
            b.post("/api/titles", save).statusCode() == 200
                && s.snapshot().titles().containsKey(fresh.id()),
            "HTTP title creation commits to MariaDB");
        long creation = s.snapshot().titles().get(fresh.id()).createdAt();
        Title edited = s.snapshot().titles().get(fresh.id());
        check(
            b.post(
                            "/api/titles",
                            Map.of(
                                "title", edited.created(1), "expectedRevision", edited.revision()))
                        .statusCode()
                    == 200
                && s.snapshot().titles().get(fresh.id()).createdAt() == creation,
            "editing cannot rewrite a title creation date");
        JsonObject automatic = JSON.toJsonTree(title("ignored", "自动分配纪念")).getAsJsonObject();
        automatic.remove("id");
        var auto =
            b.json(b.post("/api/titles", Map.of("title", automatic, "expectedRevision", 0)))
                .getAsJsonObject("title");
        check(
            auto.get("id").getAsString().startsWith("title_")
                && auto.get("createdAt").getAsLong() > 0,
            "new web titles receive server-generated identifiers and creation dates");
        check(
            b.post("/api/titles", save).statusCode() == 409,
            "stale title revision cannot overwrite saved data");
        Map<String, Object> grant =
            Map.of(
                "players", List.of(bob), "title", "web_title", "reason", "网页发放测试", "mode", "grant");
        check(
            b.post("/api/grants", grant).statusCode() == 200
                && s.snapshot().ownership(bob, "web_title") != null,
            "HTTP grant creates durable collection");
        long acquired = s.snapshot().ownership(bob, "web_title").acquiredAt();
        check(
            b.json(b.post("/api/grants", grant)).get("count").getAsInt() == 0
                && s.snapshot().ownership(bob, "web_title").acquiredAt() == acquired,
            "duplicate grant does not reset acquisition time");
        check(
            b.json(b.get("/api/state")).getAsJsonObject("holderCounts").get("web_title").getAsInt()
                == 1,
            "archive counts current unique owners");
        check(
            b.post(
                            "/api/grants",
                            Map.of(
                                "players",
                                List.of(admin, UUID.randomUUID()),
                                "title",
                                "web_title",
                                "reason",
                                "atomic",
                                "mode",
                                "grant"))
                        .statusCode()
                    != 200
                && s.snapshot().ownership(admin, "web_title") == null,
            "batch with unknown identity rolls back entirely");
        var profile = b.json(b.get("/api/players/" + bob));
        check(
            profile.getAsJsonArray("owned").size() == 1
                && !profile
                    .getAsJsonArray("owned")
                    .get(0)
                    .getAsJsonObject()
                    .get("publicCollection")
                    .getAsBoolean(),
            "admin profile includes private collection in structured response");
        waitFor(s.equip(bob, "web_title"));
        check(
            b.post(
                            "/api/grants",
                            Map.of(
                                "players",
                                List.of(bob),
                                "title",
                                "web_title",
                                "reason",
                                "网页撤销测试",
                                "mode",
                                "revoke"))
                        .statusCode()
                    == 200
                && !s.snapshot().equipped().containsKey(bob),
            "HTTP revoke removes collection and worn title");
        check(
            !b.json(b.get("/api/state")).getAsJsonObject("holderCounts").has("web_title"),
            "revoked owners are removed from archive counts");
        UUID alpha = UUID.nameUUIDFromBytes("review-alpha".getBytes()),
            zulu = UUID.nameUUIDFromBytes("review-zulu".getBytes());
        waitFor(s.login(zulu, "Zulu"));
        waitFor(s.login(alpha, "Alpha"));
        var players = b.json(b.get("/api/players")).getAsJsonArray("rows");
        check(
            players.get(0).getAsJsonObject().get("id").getAsString().equals(admin.toString())
                && players.get(1).getAsJsonObject().get("name").getAsString().equals("Alpha"),
            "players sort online first and alphabetically within each group");
        var avatarRequest =
            HttpRequest.newBuilder(URI.create(b.base + "/api/avatars/" + alpha + ".png"))
                .GET()
                .build();
        var avatarResponse = b.http.send(avatarRequest, HttpResponse.BodyHandlers.ofByteArray());
        check(
            avatarResponse.statusCode() == 200
                && AvatarStore.decode(avatarResponse.body(), 8, true).getWidth() == 8,
            "offline players have UUID-matched default head thumbnails");
        var history = b.json(b.get("/api/history?action=GRANT,REVOKE&size=1&page=0"));
        check(
            history.getAsJsonArray("rows").size() == 1 && history.get("total").getAsInt() == 2,
            "history is SQL-paginated and filters multiple actions");
        check(
            b.json(
                        b.get(
                            "/api/history?q="
                                + URLEncoder.encode(
                                    "测试玩家", java.nio.charset.StandardCharsets.UTF_8)))
                    .get("total")
                    .getAsInt()
                >= 2,
            "history search resolves registered player names");
        Settings before = s.snapshot().settings(),
            next =
                new Settings(
                    "web_title",
                    true,
                    true,
                    true,
                    1.2f,
                    .4f,
                    "[{title}] ",
                    14,
                    "香草纪元 2.7.1",
                    "2026-10-23");
        check(
            b.post("/api/settings", Map.of("settings", next, "expected", before)).statusCode()
                == 200,
            "settings and first-login reward persist through HTTP");
        check(
            s.snapshot().settings().packName().equals("香草纪元 2.7.1")
                && s.snapshot().settings().packStartedOn().equals("2026-10-23"),
            "modpack name and opening date persist with settings");
        waitFor(s.login(bob, "测试玩家"));
        long received = s.snapshot().ownership(bob, "web_title").acquiredAt();
        var renamed =
            new Settings(
                next.reward(),
                next.head(),
                next.chat(),
                next.tab(),
                next.headScale(),
                next.headOffset(),
                next.chatPrefix(),
                next.retentionDays(),
                "整合包显示更名",
                "2026-10-22");
        check(
            b.post("/api/settings", Map.of("settings", renamed, "expected", next)).statusCode()
                == 200,
            "modpack display metadata can be edited independently");
        waitFor(s.login(bob, "测试玩家"));
        check(
            s.snapshot().ownership(bob, "web_title").acquiredAt() == received,
            "editing modpack metadata does not reissue first-login rewards");
        check(
            b.post("/api/settings", Map.of("settings", before, "expected", before)).statusCode()
                == 409,
            "stale settings edit is rejected");
        check(anonymous.get("/api/history/1/changes").statusCode()==401,"anonymous undo details are denied");
        check(b.post("/api/player-delete/preview",Map.of("player",admin)).statusCode()==409,"web account owner cannot be cleared through player UI");
        var erase=b.json(b.post("/api/player-delete/preview",Map.of("player",bob)));
        String eraseStamp=erase.get("stamp").getAsString();
        check(erase.get("name").getAsString().equals("测试玩家")&&erase.get("owned").getAsInt()>0,"HTTP deletion preview shows affected player and counts");
        check(b.post("/api/player-delete/confirm",Map.of("player",bob,"stamp",eraseStamp,"confirmName","wrong")).statusCode()==400,"HTTP deletion requires typed full player name");
        check(b.post("/api/player-delete/confirm",Map.of("player",bob,"stamp","stale","confirmName","测试玩家")).statusCode()==409,"stale deletion confirmation is refused");
        String savedCsrf=b.csrf;b.csrf="";
        check(b.post("/api/player-delete/confirm",Map.of("player",bob,"stamp",eraseStamp,"confirmName","测试玩家")).statusCode()==403,"deletion cannot bypass CSRF protection");b.csrf=savedCsrf;
        check(b.post("/api/player-delete/confirm",Map.of("player",bob,"stamp",eraseStamp,"confirmName","测试玩家")).statusCode()==200&&b.get("/api/players/"+bob).statusCode()==404&&b.get("/api/avatars/"+bob+".png").statusCode()==404,"confirmed deletion removes profile and avatar HTTP access");
        var erasedHistory=b.json(b.get("/api/history?action=PLAYER_DELETE&player="+bob));
        long eraseAudit=erasedHistory.getAsJsonArray("rows").get(0).getAsJsonObject().get("id").getAsLong();
        var recovery=b.json(b.get("/api/history/"+eraseAudit+"/changes")).getAsJsonArray("rows").get(0).getAsJsonObject();
        long recoveryId=recovery.get("id").getAsLong();
        check(recovery.get("allowed").getAsBoolean(),"HTTP cleanup record offers a single recoverable change");
        check(b.post("/api/undo",Map.of("change",recoveryId,"confirm",false)).statusCode()==400,"undo requires separate confirmation");
        check(b.post("/api/undo",Map.of("change",recoveryId,"confirm",true)).statusCode()==200&&b.get("/api/players/"+bob).statusCode()==200,"HTTP undo restores deleted player records");
        var duplicate=b.post("/api/undo",Map.of("change",recoveryId,"confirm",true));
        check(duplicate.statusCode()==409&&duplicate.body().contains("已撤回"),"repeated undo returns explicit conflict explanation");
        Title initialTitle=s.snapshot().titles().get("web_title");JsonObject editValue=JSON.toJsonTree(initialTitle).getAsJsonObject();editValue.addProperty("description","HTTP 可撤回说明");
        check(b.post("/api/titles",Map.of("title",editValue,"expectedRevision",initialTitle.revision())).statusCode()==200,"HTTP title field change is journaled");
        long editAudit=b.json(b.get("/api/history?action=TITLE_SAVE&size=1")).getAsJsonArray("rows").get(0).getAsJsonObject().get("id").getAsLong();
        long editChange=b.json(b.get("/api/history/"+editAudit+"/changes")).getAsJsonArray("rows").get(0).getAsJsonObject().get("id").getAsLong();
        initialTitle=s.snapshot().titles().get("web_title");editValue=JSON.toJsonTree(initialTitle).getAsJsonObject();editValue.addProperty("description","HTTP 后来又改了说明");b.post("/api/titles",Map.of("title",editValue,"expectedRevision",initialTitle.revision()));
        var collision=b.post("/api/undo",Map.of("change",editChange,"confirm",true));
        check(collision.statusCode()==409&&collision.body().contains("发生过变化")&&s.snapshot().titles().get("web_title").description().equals("HTTP 后来又改了说明"),"HTTP changed-field conflict explains refusal and keeps later value");
        var backup = b.json(b.post("/api/backup", Map.of()));
        String file = backup.get("file").getAsString();
        check(
            Files.exists(folder.resolve("backups").resolve(file))
                && b.get("/api/backups").statusCode() == 200,
            "complete backup created and listed");
        check(
            b.post("/api/backup-preview", Map.of("file", file)).statusCode() == 200,
            "backup checksum and restore preview are validated");
        check(
            b.get("/api/backups/" + file + "/checksum").statusCode() == 200,
            "checksum file is downloadable");
        check(
            b.post("/api/backup-preview", Map.of("file", "../outside.json.gz")).statusCode() == 400,
            "backup traversal request rejected");
        var d = new AdminWebServer.Definitions(1, List.of(title("imported", "配置称号")), next);
        var preview = b.json(b.post("/api/definitions/preview", Map.of("definitions", d)));
        String stamp = preview.get("stamp").getAsString();
        waitFor(s.saveTitle(console, title("another", "并发编辑"), 0));
        check(
            b.post("/api/definitions/apply", Map.of("definitions", d, "stamp", stamp)).statusCode()
                    == 409
                && !s.snapshot().titles().containsKey("imported"),
            "configuration changed after preview requires a new preview");
        stamp =
            b.json(b.post("/api/definitions/preview", Map.of("definitions", d)))
                .get("stamp")
                .getAsString();
        check(
            b.post("/api/definitions/apply", Map.of("definitions", d, "stamp", stamp)).statusCode()
                    == 200
                && s.snapshot().titles().containsKey("old"),
            "config import applies atomically and retains missing historical definitions");
        check(
            b.get("/api/definitions").statusCode() == 200
                && b.post("/api/reload", Map.of()).statusCode() == 200,
            "definition export and server-file reload work");
        check(changes.get() >= 8, "HTTP writes publish normal TitleService change events");
        web.accounts.password("owner", "Web_Reset_Password_456");
        check(
            b.get("/api/me").statusCode() == 401,
            "console password reset invalidates existing sessions");
        check(b.login("Web_Reset_Password_456").statusCode() == 200, "reset password can sign in");
        waitFor(s.authorize(console, admin, false));
        check(
            b.get("/api/state").statusCode() == 403
                && b.post("/api/grants", grant).statusCode() == 403,
            "revoked Minecraft administrator cannot read or mutate through active web session");
        waitFor(s.authorize(console, admin, true));
        check(
            b.post(
                        "/api/password",
                        Map.of("current", "wrong", "password", "Web_Changed_Password_789"))
                    .statusCode()
                == 400,
            "password change requires current password");
        check(
            b.post(
                            "/api/password",
                            Map.of(
                                "current",
                                "Web_Reset_Password_456",
                                "password",
                                "Web_Changed_Password_789"))
                        .statusCode()
                    == 200
                && b.get("/api/me").statusCode() == 401,
            "changing password invalidates all sessions");
        check(
            b.login("Web_Reset_Password_456").statusCode() == 401
                && b.login("Web_Changed_Password_789").statusCode() == 200,
            "only changed password works");
        listingChecks(b, s, root, dbName, admin, bob);
        check(
            b.post("/api/logout", Map.of()).statusCode() == 200
                && b.get("/api/me").statusCode() == 401,
            "logout destroys server session");
      }
    } finally {
      try (Connection c = root.open()) {
        Database.execute(c, "DROP DATABASE " + dbName);
      }
    }
    Path report = Path.of("build/reports/web-integration.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report, JSON.toJson(Map.of("result", "PASS", "checks", passed, "count", passed.size())));
    System.out.println("MEMORIALTITLES_WEB_INTEGRATION_PASS " + passed.size());
  }
}
