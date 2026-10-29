package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.MenuTooltips;
import cn.pixelmc.memorialtitles.forge.Text;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.memorialtitles.web.*;
import com.google.gson.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Exercises deletion using real HTTP and a disposable loopback MariaDB. */
public final class TitleDeletionIntegrationSuite {
  private static int checks;
  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }
  private static <T> T await(CompletableFuture<T> value) throws Exception {
    return value.get(25, TimeUnit.SECONDS);
  }
  private static final class Browser {
    final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    final String base = "http://127.0.0.1:18782";
    String csrf = "";
    HttpResponse<String> post(String path, Object body) throws Exception {
      return client.send(HttpRequest.newBuilder(URI.create(base + "/api" + path))
          .header("Content-Type", "application/json").header("Origin", base)
          .header("X-CSRF-Token", csrf)
          .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonObject json(HttpResponse<String> value) { return JsonParser.parseString(value.body()).getAsJsonObject(); }
  }

  public static void main(String[] args) throws Exception {
    net.minecraft.SharedConstants.tryDetectVersion();
    net.minecraft.server.Bootstrap.bootStrap();
    String secret = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43321/";
    String name = "mt_delete_" + UUID.randomUUID().toString().replace("-", "");
    Database root = new Database(new Database.Config(base, "root", secret));
    try (Connection c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
    Database db = new Database(new Database.Config(base + name, "root", secret));
    UUID admin = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
    var console = TitleService.Actor.consoleActor();
    Path folder = Path.of("build/title-delete-tests", name);
    Files.createDirectories(folder);
    RichText rich = new RichText(List.of(new Segment("删除测试🌟", 0x123456, false, false, false, false)),
        List.of(new Gradient(List.of(0,1,2), 0xff0000, 0x0000ff)));
    try (TitleService service = new TitleService(db)) {
      await(service.reconnect());
      for (var player : List.of(admin, alice, bob, carol)) await(service.login(player, "Player" + player.toString().substring(0,5)));
      await(service.authorize(console, admin, true));
      await(service.saveTitle(console, new Title("target", rich, "说明", "来源", "", "纪念", 0, 0, true, true, 0), 0));
      await(service.saveTitle(console, new Title("other", RichText.plain("其他称号"), "", "", "", "", 0, 0, true, true, 0), 0));
      await(service.settings(console, new Settings("target", true, true, true, 1, .3f, "[{title}] ", 14, "测试整合包", "2026-10-23")));
      await(service.login(bob, "Bob"));
      await(service.grant(console, List.of(alice), "target", ""));
      await(service.grant(console, List.of(alice), "other", ""));
      await(service.privacy(alice, "target", true, false));
      await(service.equip(alice, "target"));
      await(service.equip(bob, "target"));
      Owned original = service.snapshot().ownership(alice, "target");
      AdminWebServer.Host host = new AdminWebServer.Host() {
        public Set<UUID> online() { return Set.of(alice); }
        public void mirror() { try { Files.writeString(folder.resolve("definitions.json"), JSON.toJson(AdminWebServer.definitions(service.snapshot()))); } catch(Exception error) { throw new RuntimeException(error); } }
        public CompletableFuture<Path> backup(TitleService.Actor actor) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
        public CompletableFuture<Void> reload(TitleService.Actor actor) { return CompletableFuture.failedFuture(new UnsupportedOperationException()); }
      };
      try (AdminWebServer web = new AdminWebServer(service, host,
          new WebConfig(true,"127.0.0.1",18782,"http://127.0.0.1:18782",60,List.of()),folder,folder.resolve("backups"))) {
        web.accounts.create("delete_admin", admin, "Local_Delete_Test_Password_123");
        Browser browser = new Browser();
        check(browser.post("/title-delete/preview", Map.of("title","target")).statusCode()==401, "anonymous deletion preview is rejected");
        var login = browser.post("/login", Map.of("username","delete_admin","password","Local_Delete_Test_Password_123"));
        check(login.statusCode()==200, "authorized local web login succeeds");
        browser.csrf = browser.json(login).get("csrf").getAsString();
        String csrf = browser.csrf;
        browser.csrf="wrong";
        check(browser.post("/title-delete/preview", Map.of("title","target")).statusCode()==403, "deletion preview requires CSRF protection");
        browser.csrf=csrf;
        boolean denied=false;
        try { await(service.titleDeletePreview(TitleService.Actor.player(carol),"target")); } catch(ExecutionException expected) {denied=true;}
        check(denied,"ordinary players cannot preview or delete titles");
        var preview = browser.json(browser.post("/title-delete/preview", Map.of("title","target")));
        check(preview.get("holders").getAsInt()==2 && preview.get("equipped").getAsInt()==2
            && preview.get("receipts").getAsInt()==1 && preview.get("reward").getAsBoolean(), "preview reports all ownership equipment and first-login impacts");
        check(browser.post("/title-delete/confirm", Map.of("title","target","stamp",preview.get("stamp").getAsString(),"confirmName","wrong")).statusCode()==400,
            "exact title name is required for second confirmation");
        await(service.grant(console,List.of(carol),"target","new owner"));
        check(browser.post("/title-delete/confirm", Map.of("title","target","stamp",preview.get("stamp").getAsString(),"confirmName",rich.plain())).statusCode()==409,
            "changed holder population rejects stale confirmation");
        check(service.snapshot().titles().containsKey("target") && service.snapshot().owned().values().stream().filter(o->o.title().equals("target")).count()==3,
            "failed confirmation leaves definition and all holders intact");
        preview=browser.json(browser.post("/title-delete/preview",Map.of("title","target")));
        var response=browser.post("/title-delete/confirm",Map.of("title","target","stamp",preview.get("stamp").getAsString(),"confirmName",rich.plain()));
        check(response.statusCode()==200 && browser.json(response).get("removedHolders").getAsInt()==3,
            "confirmed web deletion clears all three holders in one operation");
        check(!service.snapshot().titles().containsKey("target") && service.snapshot().owned().values().stream().noneMatch(o->o.title().equals("target"))
            && service.snapshot().equipped().isEmpty(), "definition collection and active display references disappear together");
        check(service.snapshot().receipts().isEmpty() && service.snapshot().settings().reward().isEmpty(), "deleted first-login reward and its receipts are cleared");
        check(service.snapshot().players().size()==4 && service.snapshot().ownership(alice,"other")!=null
            && service.snapshot().settings().packName().equals("测试整合包"), "other titles player profiles and unrelated settings are preserved");
        check(!Files.readString(folder.resolve("definitions.json")).contains("删除测试"), "configuration mirror cannot re-import the deleted title");
        for(UUID id:List.of(alice,bob,carol)) {
          var notices=new Database(new Database.Config(base+name,"root",secret)).notifications(id).stream().filter(n->n.title().equals("target")).toList();
          check(notices.size()==1 && !notices.get(0).granted() && notices.get(0).name().equals(rich), "one persistent removal notice replaces stale grant notices " + id);
        }
        check(await(service.notifications(admin)).isEmpty(), "non-holder administrators receive no private removal notice");
        await(service.login(bob,"Bob"));
        check(service.snapshot().ownership(bob,"target")==null,"subsequent login does not recreate a deleted reward");
        var history=await(service.searchHistory(console,"","TITLE_DELETE","","target",0,100));
        check(history.total()==1,"deletion is recorded in searchable administrator history");
        var unit=await(service.changes(console,history.rows().get(0).id())).get(0);
        check(unit.allowed() && unit.changes().stream().anyMatch(d->d.key().equals("T|target")), "deletion has one selective recovery unit");
        await(service.settings(console,new Settings("",true,true,true,1,.3f,"[{title}] ",14,"后续修改", "2026-10-23")));
        await(service.undo(console,unit.id()));
        check(service.snapshot().ownership(alice,"target").equals(original)
            && service.snapshot().equipped().get(bob).equals("target") && service.snapshot().receipts().size()==1,
            "selective undo restores acquisition privacy equipment and receipt state");
        check(service.snapshot().settings().packName().equals("后续修改") && service.snapshot().settings().reward().equals("target"),
            "undo preserves later unrelated settings edits");
        var second=await(service.titleDeletePreview(console,"target"));
        await(service.deleteTitle(console,"target",second.stamp(),rich.plain()));
        var latest=await(service.searchHistory(console,"","TITLE_DELETE","","target",0,100)).rows().get(0);
        var conflict=await(service.changes(console,latest.id())).get(0);
        await(service.equip(alice,"other"));
        check(!await(service.changes(console,latest.id())).get(0).allowed(),"later equipment change is explained as an undo conflict");
        boolean blocked=false;
        try { await(service.undo(console,conflict.id())); } catch(ExecutionException expected) {blocked=true;}
        check(blocked && !service.snapshot().titles().containsKey("target") && service.snapshot().equipped().get(alice).equals("other"),
            "conflicting undo is atomic and does not overwrite later equipment");
      }
      ListTag lore=new ListTag();
      lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("本次获得：2026-10-23"), net.minecraft.core.RegistryAccess.EMPTY)));
      var data=MenuTooltips.encode(Text.render(rich),lore);
      lore.add(StringTag.valueOf(Component.Serializer.toJson(Component.literal("整合包无关说明"), net.minecraft.core.RegistryAccess.EMPTY)));
      var own=MenuTooltips.decode(data).orElseThrow();
      check(own.size()==2 && own.get(0).getString().equals(rich.plain()) && own.get(1).getString().startsWith("本次获得"),
          "menu tooltip snapshot retains title details and excludes later injected lore");
      check(own.get(0).getSiblings().get(0).getStyle().getColor().getValue()==0xff0000,
          "protected menu tooltip preserves the configured gradient");
      check(MenuTooltips.decode(new CompoundTag()).isEmpty(),"ordinary unmarked item data is left untouched");
      System.out.println("TITLE DELETION AND MENU TOOLTIP CHECKS PASSED: "+checks);
    } finally {
      try(Connection c=root.open()){Database.execute(c,"DROP DATABASE "+name);}
    }
  }
}
