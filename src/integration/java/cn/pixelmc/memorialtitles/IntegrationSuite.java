package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Runs against a disposable, real MariaDB. No mocked persistence. */
public final class IntegrationSuite {
  private static int assertions;
  private static final List<String> passed = new ArrayList<>();

  private static void check(boolean ok, String name) {
    assertions++;
    if (!ok) throw new AssertionError(name);
    passed.add(name);
    System.out.println("PASS " + name);
  }

  private static <T> T await(CompletableFuture<T> f) throws Exception {
    return f.get(20, TimeUnit.SECONDS);
  }

  private static void deny(CompletableFuture<?> f, String name) throws Exception {
    boolean denied = false;
    try {
      await(f);
    } catch (ExecutionException e) {
      denied = true;
    }
    check(denied, name);
  }

  private static Title title(String id, RichText name, boolean visible) {
    return new Title(id, name, "纪念说明", "香草纪元", "管理员发放", "纪念", 1, 7, visible, true, 0);
  }

  public static void main(String[] args) throws Exception {
    String base = System.getProperty("mt.testDb", "jdbc:mariadb://127.0.0.1:43307/");
    if (!base.matches("jdbc:mariadb://127\\.0\\.0\\.1:43307/"))
      throw new IllegalArgumentException(
          "Suite only permits isolated loopback test MariaDB on port 43307");
    String password = System.getenv("MT_TEST_PASSWORD");
    if (password == null) throw new IllegalStateException("Set MT_TEST_PASSWORD");
    String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10),
        dbName = "mt_it_" + suffix,
        restoredName = dbName + "_restore",
        user = "mt_" + suffix;
    Database root = new Database(new Database.Config(base, "root", password));
    try (Connection c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + dbName + " CHARACTER SET utf8mb4");
      Database.execute(c, "CREATE DATABASE " + restoredName + " CHARACTER SET utf8mb4");
      Database.execute(c, "CREATE USER '" + user + "'@'127.0.0.1' IDENTIFIED BY 'test_only'");
      Database.execute(c, "GRANT ALL ON " + dbName + ".* TO '" + user + "'@'127.0.0.1'");
    }
    TitleService.Actor console = TitleService.Actor.consoleActor();
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), alt = UUID.randomUUID();
    Database db = new Database(new Database.Config(base + dbName, user, "test_only"));
    try (TitleService s = new TitleService(db);
        TitleService second = new TitleService(db);
        TitleService restored =
            new TitleService(
                new Database(new Database.Config(base + restoredName, "root", password)))) {
      await(s.reconnect());
      await(second.reconnect());
      await(s.login(alice, "中文玩家"));
      await(s.login(bob, "same_name"));
      await(s.login(alt, "same_name"));
      check(s.snapshot().players().size() == 3, "equal names never merge distinct UUIDs");
      RichText rich =
          new RichText(
              List.of(new Segment("纪念🌟ABC", 0x112233, true, false, true, false)),
              List.of(new Gradient(List.of(0, 2, 4), 0xff0000, 0x0000ff)));
      check(
          rich.glyphs().size() == 6 && rich.glyphs().get(2).text().equals("🌟"),
          "gradient offsets preserve emoji code points");
      check(
          rich.glyphs().get(0).color() == 0xff0000
              && rich.glyphs().get(4).color() == 0x0000ff
              && rich.glyphs().get(1).color() == 0x112233
              && rich.glyphs().get(2).bold(),
          "selected gradient endpoints and unselected style preserved");
      await(s.saveTitle(console, title("hidden", rich, false), 0));
      var editedSelection =
          cn.pixelmc.memorialtitles.domain.TextEdits.afterEdit(
              List.of(new Gradient(List.of(0, 2, 3, 5, 7), 0xff0000, 0x0000ff)), 2, 3, 1);
      check(
          editedSelection.get(0).positions().equals(List.of(0, 2, 3, 5)),
          "shortening a segment drops removed characters and moves later gradient selections");
      check(
          cn.pixelmc.memorialtitles.domain.TextEdits.afterEdit(
                  List.of(new Gradient(List.of(2, 3), 0, 0xffffff)), 2, 2, 0)
              .isEmpty(),
          "deleting selected text safely removes empty gradient groups");
      await(s.saveTitle(console, title("reward", RichText.plain("首登纪念"), true), 0));
      await(denyAdminSave(s, bob));
      check(
          s.snapshot().catalogue(bob).stream().noneMatch(t -> t.id().equals("hidden")),
          "hidden title absent from unowned catalogue");
      await(s.authorize(console, alice, true));
      await(s.grant(TitleService.Actor.player(alice), List.of(alice), "hidden", "测试"));
      Owned original = s.snapshot().ownership(alice, "hidden");
      check(
          !original.publicCollection()
              && original.publicDisplay()
              && !s.snapshot().equipped().containsKey(alice),
          "award defaults private collection public display and no autoequip");
      Thread.sleep(5);
      await(s.grant(console, List.of(alice), "hidden", "duplicate"));
      check(
          s.snapshot().ownership(alice, "hidden").acquiredAt() == original.acquiredAt(),
          "duplicate award retains acquisition time");
      check(
          s.snapshot().collection(alice, bob, false).isEmpty(), "private collection stays private");
      await(s.privacy(alice, "hidden", true, true));
      check(
          s.snapshot().collection(alice, bob, false).size() == 1
              && s.snapshot().titles().get("hidden").description().equals("纪念说明"),
          "public collection shares complete hidden title details");
      await(s.equip(alice, "hidden"));
      check(s.snapshot().worn(alice) != null, "owner equips one title");
      await(s.privacy(alice, "hidden", true, false));
      check(
          s.snapshot().worn(alice) == null
              && s.snapshot().collection(alice, bob, false).size() == 1,
          "display privacy independent from collection privacy");
      await(s.privacy(alice, "hidden", false, true));
      check(
          s.snapshot().worn(alice) != null && s.snapshot().collection(alice, bob, false).isEmpty(),
          "worn text does not expose private collection");
      await(denyOwnedEquip(s, bob));
      Title old = s.snapshot().titles().get("hidden");
      Title edited =
          new Title(
              old.id(),
              RichText.plain("全体立即更新"),
              "新说明",
              old.source(),
              old.acquisition(),
              old.category(),
              old.order(),
              old.icon(),
              old.visible(),
              false,
              old.revision());
      await(s.saveTitle(console, edited, old.revision()));
      check(
          s.snapshot().worn(alice).name().plain().equals("全体立即更新")
              && s.snapshot().ownership(alice, "hidden").acquiredAt() == original.acquiredAt(),
          "definition edits update holders without changing acquisition");
      await(denyRevision(s, edited, old.revision()));
      await(s.equip(alice, ""));
      await(s.equip(alice, "hidden"));
      check(s.snapshot().worn(alice) != null, "retired title remains wearable");
      await(s.revoke(console, List.of(alice), "hidden", "误颁发"));
      check(
          s.snapshot().ownership(alice, "hidden") == null
              && !s.snapshot().equipped().containsKey(alice),
          "revocation atomically removes ownership and equipment");
      await(denyRetiredAward(s, console, alice));
      Title retired = s.snapshot().titles().get("hidden");
      await(
          s.saveTitle(
              console,
              new Title(
                  retired.id(),
                  retired.name(),
                  retired.description(),
                  retired.source(),
                  retired.acquisition(),
                  retired.category(),
                  retired.order(),
                  retired.icon(),
                  false,
                  true,
                  retired.revision()),
              retired.revision()));
      Thread.sleep(5);
      await(s.grant(console, List.of(alice), "hidden", "重新发放"));
      check(
          s.snapshot().ownership(alice, "hidden").acquiredAt() > original.acquiredAt(),
          "reaward only exposes current acquisition time");
      Settings st = s.snapshot().settings();
      await(
          s.settings(
              console,
              new Settings(
                  "reward",
                  st.head(),
                  st.chat(),
                  st.tab(),
                  st.headScale(),
                  st.headOffset(),
                  st.chatPrefix(),
                  st.retentionDays())));
      CompletableFuture.allOf(s.login(alice, "改名玩家"), second.login(alice, "改名玩家"))
          .get(20, TimeUnit.SECONDS);
      await(s.reconnect());
      check(
          s.snapshot().receipts().containsKey(new Key(alice, "reward"))
              && s.snapshot().ownership(alice, "reward") != null,
          "concurrent first login across instances commits one receipt");
      await(s.revoke(console, List.of(alice), "reward", "撤销"));
      await(s.login(alice, "改名玩家"));
      check(
          s.snapshot().ownership(alice, "reward") == null,
          "revoked firstlogin reward never regranted on reconnect");
      check(
          s.snapshot().admins().contains(alice) && s.snapshot().ownership(alice, "hidden") != null,
          "rename preserves identity ownership and authorization");
      await(s.authorize(console, alice, false));
      await(denyRevokedAdmin(s, alice));
      await(s.authorize(console, alice, true));
      Snapshot before = s.snapshot();
      Backup.Archive archive = await(s.export(console));
      Path folder = Path.of("build/reports/backups");
      Path backup = Backup.write(folder, archive);
      Backup.Archive read = Backup.read(backup);
      check(
          read.owned().size() == before.owned().size() && read.audit().size() > 5,
          "checksummed gzip export includes collections and complete audit");
      await(restored.reconnect());
      await(restored.restoreEmpty(console, read));
      check(
          restored.snapshot().equals(before),
          "actual backup restore reproduces full database snapshot");
      await(denyNonemptyRestore(restored, console, read));
      byte[] bytes = Files.readAllBytes(backup);
      bytes[bytes.length / 2] ^= 1;
      Path tampered = backup.resolveSibling("tampered.json.gz");
      Files.write(tampered, bytes);
      Files.copy(
          backup.resolveSibling(backup.getFileName() + ".sha256"),
          tampered.resolveSibling("tampered.json.gz.sha256"),
          StandardCopyOption.REPLACE_EXISTING);
      boolean refused = false;
      try {
        Backup.read(tampered);
      } catch (Exception e) {
        refused = true;
      }
      check(refused, "corrupted backup rejected before import");
      // Make the application credentials invalid while root stays connected for recovery.
      try (Connection c = root.open()) {
        Database.execute(c, "DROP USER '" + user + "'@'127.0.0.1'");
      }
      await(denyUnavailableReconnect(s));
      check(
          !s.healthy() && s.snapshot().equals(before),
          "database outage retains last committed readonly cache");
      await(denyOfflineWrite(s, alice));
      try (Connection c = root.open()) {
        Database.execute(c, "CREATE USER '" + user + "'@'127.0.0.1' IDENTIFIED BY 'test_only'");
        Database.execute(c, "GRANT ALL ON " + dbName + ".* TO '" + user + "'@'127.0.0.1'");
      }
      await(s.reconnect());
      check(s.healthy() && s.snapshot().equals(before), "database recovery reloads durable state");
      await(s.equip(alice, "hidden"));
      await(s.migrateIdentity(console, alice, bob));
      check(
          s.snapshot().ownership(bob, "hidden") != null
              && s.snapshot().ownership(alice, "hidden") == null
              && s.snapshot().admins().contains(alice)
              && !s.snapshot().admins().contains(bob),
          "explicit identity migration moves titles but never admin authority");
      try (TitleService restart = new TitleService(db)) {
        await(restart.reconnect());
        check(
            restart.snapshot().equals(s.snapshot()),
            "fresh service process state reads independent persistence");
      }
      int count = s.snapshot().titles().size();
      boolean invalid = false;
      try {
        await(
            s.reload(
                console,
                List.of(title("ok", RichText.plain("OK"), true)),
                new Settings("missing", true, true, true, 1, .3f, "[{title}] ", 30)));
      } catch (Exception e) {
        invalid = true;
      }
      check(
          invalid && s.snapshot().titles().size() == count,
          "invalid reload rolls back entire transaction");
    } finally {
      try (Connection c = root.open()) {
        Database.execute(c, "DROP DATABASE " + dbName);
        Database.execute(c, "DROP DATABASE " + restoredName);
        Database.execute(c, "DROP USER IF EXISTS '" + user + "'@'127.0.0.1'");
      }
    }
    Files.createDirectories(Path.of("build/reports"));
    Files.writeString(
        Path.of("build/reports/integration.json"),
        JSON.toJson(
            Map.of(
                "assertions",
                assertions,
                "passed",
                passed,
                "database",
                "MariaDB real loopback",
                "result",
                "PASS")));
    System.out.println("MEMORIALTITLES_INTEGRATION_PASS assertions=" + assertions);
  }

  private static CompletableFuture<Void> denyAdminSave(TitleService s, UUID id) throws Exception {
    deny(
        s.saveTitle(
            TitleService.Actor.player(id), title("unauthorized", RichText.plain("禁止"), true), 0),
        "unauthorized player cannot save definitions");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyOwnedEquip(TitleService s, UUID id) throws Exception {
    deny(s.equip(id, "hidden"), "nonowner cannot equip hidden title");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyRevision(TitleService s, Title t, long rev)
      throws Exception {
    deny(s.saveTitle(TitleService.Actor.consoleActor(), t, rev), "stale editor revision rejected");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyRetiredAward(
      TitleService s, TitleService.Actor c, UUID id) throws Exception {
    deny(s.grant(c, List.of(id), "hidden", ""), "retired title blocks new grants");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyRevokedAdmin(TitleService s, UUID id)
      throws Exception {
    deny(
        s.grant(TitleService.Actor.player(id), List.of(id), "hidden", ""),
        "revoked authorization denied inside transaction");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyNonemptyRestore(
      TitleService s, TitleService.Actor c, Backup.Archive a) throws Exception {
    deny(s.restoreEmpty(c, a), "nonempty database restore refused");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyUnavailableReconnect(TitleService s) throws Exception {
    deny(s.reconnect(), "database connection failure surfaces");
    return CompletableFuture.completedFuture(null);
  }

  private static CompletableFuture<Void> denyOfflineWrite(TitleService s, UUID id)
      throws Exception {
    deny(s.equip(id, "hidden"), "offline persistent mutation fails clearly");
    return CompletableFuture.completedFuture(null);
  }
}
