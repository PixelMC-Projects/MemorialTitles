package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.memorialtitles.web.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real authenticated HTTP and disposable MariaDB, never the production instance. */
public final class TitleHoldersIntegrationSuite {
  private static final List<String> checks = new ArrayList<>();
  private static final TitleService.Actor ADMIN = TitleService.Actor.consoleActor();
  private static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    checks.add(label); System.out.println("PASS " + label);
  }
  private static <T> T await(CompletableFuture<T> value) throws Exception { return value.get(25, TimeUnit.SECONDS); }
  private static JsonObject json(HttpResponse<String> r) { return JsonParser.parseString(r.body()).getAsJsonObject(); }
  private static Title title(String id) { return new Title(id, RichText.plain(id), "", "", "", "", 0, 0, false, true, 0); }
  private static final class Browser {
    final String base = "http://127.0.0.1:18768";
    final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    HttpResponse<String> get(String path) throws Exception {
      return http.send(HttpRequest.newBuilder(URI.create(base + "/api" + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> login(String name, String password) throws Exception {
      return http.send(HttpRequest.newBuilder(URI.create(base + "/api/login"))
          .header("Content-Type", "application/json").header("Origin", base)
          .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(Map.of("username", name, "password", password))))
          .build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  public static void main(String[] args) throws Exception {
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43307/";
    String name = "mt_holders_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
    Database root = new Database(new Database.Config(base, "root", password));
    try (var c = root.open()) { Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4"); }
    try (TitleService service = new TitleService(new Database(new Database.Config(base + name, "root", password)))) {
      await(service.reconnect());
      UUID admin = UUID.randomUUID(), alpha = UUID.randomUUID(), beta = UUID.randomUUID(), zebra = UUID.randomUUID();
      await(service.login(admin, "Admin")); await(service.authorize(ADMIN, admin, true));
      await(service.login(alpha, "Alpha")); await(service.login(beta, "beta")); await(service.login(zebra, "Zebra"));
      await(service.saveTitle(ADMIN, title("selected"), 0)); await(service.saveTitle(ADMIN, title("other"), 0));
      await(service.saveTitle(ADMIN, title("empty"), 0));
      List<UUID> members = new ArrayList<>(List.of(alpha, beta, zebra));
      for (int n=0;n<22;n++) {
        UUID id = UUID.randomUUID(); members.add(id);
        await(service.login(id, String.format(Locale.ROOT,"Member%02d",n)));
      }
      await(service.grant(ADMIN, members, "selected", "integration fixture"));
      await(service.grant(ADMIN, List.of(admin), "other", "integration fixture"));
      long originalTime = service.snapshot().ownership(alpha, "selected").acquiredAt();
      Path folder = Path.of("build/holder-tests", name); Files.createDirectories(folder);
      AdminWebServer.Host host = new AdminWebServer.Host() {
        public Set<UUID> online() { return Set.of(zebra); }
        public void mirror() {}
        public CompletableFuture<Path> backup(TitleService.Actor actor) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
        public CompletableFuture<Void> reload(TitleService.Actor actor) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
      };
      try (AdminWebServer web = new AdminWebServer(service, host,
          new WebConfig(true,"127.0.0.1",18768,"http://127.0.0.1:18768",60,List.of()),folder,folder.resolve("backups"))) {
        String login = AccountStore.randomPassword(); web.accounts.create("owner",admin,login);
        Browser browser = new Browser();
        check(browser.get("/title-holders?title=selected").statusCode()==401, "holder list requires authenticated administrator session");
        check(browser.login("owner",login).statusCode()==200, "administrator authenticates against real account store");
        var firstResponse = browser.get("/title-holders?title=selected");
        var first = json(firstResponse); JsonArray rows = first.getAsJsonArray("rows");
        check(firstResponse.statusCode()==200 && first.get("total").getAsInt()==25 && rows.size()==20,
            "first page includes private holders and reports all 25 owners without truncation");
        check(rows.get(0).getAsJsonObject().get("name").getAsString().equals("Alpha")
            && rows.get(1).getAsJsonObject().get("name").getAsString().equals("beta"),
            "names sort alphabetically ignoring case instead of prioritizing online Zebra");
        check(rows.get(0).getAsJsonObject().keySet().equals(Set.of("id","name","acquiredAt","avatarVersion")),
            "row exposes only identity acquisition time and cached avatar version with no online or privacy fields");
        check(rows.get(0).getAsJsonObject().get("id").getAsString().equals(alpha.toString())
            && rows.get(0).getAsJsonObject().get("acquiredAt").getAsLong()==originalTime,
            "row uses full stable UUID and current ownership acquisition timestamp");
        var second = json(browser.get("/title-holders?title=selected&page=1"));
        Set<UUID> found = new HashSet<>();
        for (JsonElement row : rows) found.add(UUID.fromString(row.getAsJsonObject().get("id").getAsString()));
        for (JsonElement row : second.getAsJsonArray("rows")) found.add(UUID.fromString(row.getAsJsonObject().get("id").getAsString()));
        check(second.getAsJsonArray("rows").size()==5 && found.equals(Set.copyOf(members)),
            "pagination visits every selected-title holder exactly once including the online player");
        check(json(browser.get("/title-holders?title=other")).get("total").getAsInt()==1
            && !found.contains(admin), "holders of another title are excluded");
        check(json(browser.get("/title-holders?title=empty")).getAsJsonArray("rows").isEmpty(),
            "existing title with no owners returns an empty list");
        check(browser.get("/title-holders?title=missing").statusCode()==404,
            "deleted or unknown title returns a useful not-found response");
        check(browser.get("/title-holders?title=selected&size=101").statusCode()==400,
            "page size cannot bypass the bounded response limit");
        check(json(browser.get("/title-holders?title=selected&page=999")).get("page").getAsInt()==1,
            "out-of-range page clamps to the final available page");
        await(service.revoke(ADMIN,List.of(alpha),"selected","fixture"));
        var refreshed = json(browser.get("/title-holders?title=selected&size=100"));
        check(refreshed.get("total").getAsInt()==24 && refreshed.getAsJsonArray("rows").asList().stream()
            .noneMatch(row -> row.getAsJsonObject().get("id").getAsString().equals(alpha.toString())),
            "refresh uses committed ownership changes without stale revoked holders");
        await(service.grant(ADMIN,List.of(alpha),"selected","fixture regrant"));
        var regranted = json(browser.get("/title-holders?title=selected")).getAsJsonArray("rows").get(0).getAsJsonObject();
        check(regranted.get("acquiredAt").getAsLong()>originalTime,
            "regrant displays this acquisition time rather than removed historical ownership");
        await(service.authorize(ADMIN,admin,false));
        check(browser.get("/title-holders?title=selected").statusCode()==403,
            "revoked administrator cannot read private holder list using an existing session");
      }
      Path report = Path.of("build/reports/title-holders.json"); Files.createDirectories(report.getParent());
      Files.writeString(report,JSON.toJson(Map.of("passed",true,"checks",checks)));
    } finally {
      try (var c = root.open()) { Database.execute(c,"DROP DATABASE " + name); }
    }
  }
}
