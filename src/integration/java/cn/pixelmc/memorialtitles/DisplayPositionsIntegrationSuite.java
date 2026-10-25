package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.Network;
import cn.pixelmc.memorialtitles.persistence.*;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real disposable MariaDB coverage; never connects to the production database. */
public final class DisplayPositionsIntegrationSuite {
  private static final TitleService.Actor ADMIN = TitleService.Actor.consoleActor();
  private static final List<String> checks = new ArrayList<>();
  private static <T> T await(CompletableFuture<T> task) throws Exception { return task.get(25, TimeUnit.SECONDS); }
  private static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    checks.add(label); System.out.println("PASS " + label);
  }
  private static void deny(CompletableFuture<?> task, String label) throws Exception {
    boolean denied = false;
    try { await(task); } catch (ExecutionException expected) { denied = true; }
    check(denied, label);
  }
  private static Title title(String id) {
    return new Title(id, RichText.plain("称号" + id), "", "", "", "", 0, 0, true, true, 0);
  }
  private static void preferences(TitleService s, UUID id, DisplayPreferences desired) throws Exception {
    for (DisplayPosition p : DisplayPosition.values())
      if (s.snapshot().displayPreferences(id).enabled(p) != desired.enabled(p)) await(s.toggleDisplayPosition(id, p));
  }
  private static Settings globals(Snapshot s, boolean head, boolean chat, boolean tab) {
    Settings old = s.settings();
    return new Settings(old.reward(), head, chat, tab, old.headScale(), old.headOffset(), old.chatPrefix(), old.retentionDays(), old.packName(), old.packStartedOn());
  }
  private static long lastChange(TitleService s, String action) throws Exception {
    long audit = await(s.history(ADMIN, 1000)).stream().filter(a -> a.action().equals(action)).findFirst().orElseThrow().id();
    return await(s.changes(ADMIN, audit)).get(0).id();
  }

  public static void main(String[] args) throws Exception {
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43307/";
    String name = "mt_positions_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    List<String> names = List.of(name, name + "_restored", name + "_legacy");
    Database root = new Database(new Database.Config(base, "root", password));
    try (var c = root.open()) {
      for (String n : names) Database.execute(c, "CREATE DATABASE " + n + " CHARACTER SET utf8mb4");
    }
    Database db = new Database(new Database.Config(base + name, "root", password));
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    try {
      db.migrate();
      db.transaction(tx -> { tx.sql("INSERT INTO mt_players VALUES(?,?,?,?)", alice.toString(), "Alice", 1234L, 5678L); return null; });
      try (var c = db.open()) {
        Database.execute(c, "DROP TABLE mt_display_preferences");
        Database.execute(c, "DELETE FROM mt_schema WHERE version>=5");
      }
      db.migrate();
      Snapshot migrated = db.read();
      check(migrated.players().get(alice).joinedAt() == 1234 && migrated.displayPreferences(alice).equals(DisplayPreferences.defaults()),
          "schema 4 migration preserves identity and defaults existing players to three enabled positions");
      db.migrate();
      check(db.read().displayPreferences().isEmpty(), "migration is repeatable without adding default preference rows");

      try (TitleService s = new TitleService(db)) {
        await(s.reconnect()); await(s.login(alice, "Alice")); await(s.login(bob, "Bob"));
        await(s.saveTitle(ADMIN, title("alpha"), 0)); await(s.saveTitle(ADMIN, title("beta"), 0));
        await(s.grant(ADMIN, List.of(alice, bob), "alpha", "")); await(s.grant(ADMIN, List.of(alice), "beta", ""));
        await(s.equip(alice, "alpha")); await(s.equip(bob, "alpha"));
        Snapshot baseSnapshot = s.snapshot();
        boolean matrix = true, wire = true;
        for (int global = 0; global < 8; global++) for (int personal = 0; personal < 8; personal++) {
          DisplayPreferences prefs = new DisplayPreferences((personal & 1) != 0, (personal & 2) != 0, (personal & 4) != 0);
          Snapshot snapshot = new Snapshot(baseSnapshot.titles(), baseSnapshot.players(), baseSnapshot.owned(),
              baseSnapshot.equipped(), baseSnapshot.admins(), baseSnapshot.receipts(),
              globals(baseSnapshot, (global & 1) != 0, (global & 2) != 0, (global & 4) != 0), Map.of(alice, prefs));
          for (DisplayPosition p : DisplayPosition.values()) {
            int mask = 1 << p.ordinal();
            boolean expected = (global & personal & mask) != 0;
            matrix &= (snapshot.displayed(alice, p) != null) == expected;
          }
          Network.Display row = Network.Display.forPlayer(snapshot, alice);
          boolean h = (global & personal & 1) != 0, c = (global & personal & 2) != 0;
          wire &= (row == null) == (!h && !c);
          if (row != null) wire &= row.head() == h && row.chat() == c && JSON.fromJson(JSON.toJson(row), Network.Display.class).equals(row);
        }
        check(matrix, "all 64 admin/personal combinations enforce administrator AND player for head chat and TAB");
        check(wire, "all 64 combinations publish only eligible head/chat text and retain independent flags through JSON");
        long notices = await(s.notifications(alice)).size();
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        check(s.snapshot().displayed(alice, DisplayPosition.HEAD) == null && s.snapshot().displayed(alice, DisplayPosition.CHAT) != null
            && s.snapshot().displayed(alice, DisplayPosition.TAB) != null, "turning off head leaves chat and TAB enabled");
        check(s.snapshot().displayPreferences(bob).equals(DisplayPreferences.defaults()), "player preferences never change another player");
        await(s.equip(alice, "beta")); await(s.login(alice, "AliceRenamed"));
        check(!s.snapshot().displayPreferences(alice).head(), "equip changes login and rename preserve UUID-bound choices");
        try (TitleService second = new TitleService(db)) {
          await(second.reconnect());
          check(second.snapshot().displayPreferences(alice).equals(s.snapshot().displayPreferences(alice)), "new service instance restores preferences from MariaDB");
        }
        await(s.settings(ADMIN, globals(s.snapshot(), false, false, false)));
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        check(s.snapshot().displayPreferences(alice).head() && Arrays.stream(DisplayPosition.values()).allMatch(p -> s.snapshot().displayed(alice, p) == null),
            "player can retain enabled choices while administrator forbids every outlet");
        await(s.settings(ADMIN, globals(s.snapshot(), true, true, true)));
        check(Arrays.stream(DisplayPosition.values()).allMatch(p -> s.snapshot().displayed(alice, p) != null), "administrator re-enable uses saved player choices");
        await(s.privacy(alice, "beta", false, false));
        check(Arrays.stream(DisplayPosition.values()).allMatch(p -> s.snapshot().displayed(alice, p) == null)
            && Network.Display.forPlayer(s.snapshot(), alice) == null, "per-title display privacy still overrides all three personal choices");
        await(s.privacy(alice, "beta", false, true));
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        long headChange = lastChange(s, "DISPLAY_POSITION");
        await(s.toggleDisplayPosition(alice, DisplayPosition.CHAT));
        await(s.undo(ADMIN, headChange));
        check(s.snapshot().displayPreferences(alice).head() && !s.snapshot().displayPreferences(alice).chat(),
            "selective undo restores only head without overwriting a later chat change");
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        long conflicting = lastChange(s, "DISPLAY_POSITION");
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        deny(s.undo(ADMIN, conflicting), "same-position later change blocks stale undo even if toggled back");
        UUID unknown = UUID.randomUUID();
        deny(s.toggleDisplayPosition(unknown, DisplayPosition.HEAD), "unknown UUID cannot create a player from a preference update");
        check(!s.snapshot().players().containsKey(unknown), "failed preference request never registers an unauthenticated identity");

        preferences(s, alice, new DisplayPreferences(false, true, false));
        preferences(s, bob, new DisplayPreferences(true, false, true));
        check(await(s.notifications(alice)).size() == notices, "position changes produce no fake title acquisition or removal notices");
        Backup.Archive archive = await(s.export(ADMIN));
        check(archive.format() == 4 && archive.displayPreferences().size() == 2, "backup format 4 includes non-default player preferences");
        Path backup = Backup.write(Path.of("build/position-tests", name), archive);
        Backup.Archive read = Backup.read(backup);
        try (TitleService restored = new TitleService(new Database(new Database.Config(base + names.get(1), "root", password)))) {
          await(restored.reconnect()); await(restored.restoreEmpty(ADMIN, read));
          check(restored.snapshot().displayPreferences().equals(s.snapshot().displayPreferences()), "compressed backup restores exact independent choices for every player");
          long undo = lastChange(restored, "DISPLAY_POSITION");
          await(restored.undo(ADMIN, undo));
          check(!restored.snapshot().displayPreferences().equals(s.snapshot().displayPreferences()), "position undo remains valid after backup and restore");
        }
        Backup.Archive old = new Backup.Archive(2, archive.createdAt(), archive.titles(), archive.players(), archive.owned(),
            archive.equipped(), archive.admins(), archive.receipts(), archive.settings(), archive.audit(),
            new ChangeJournal.Export(List.of(), Map.of(), List.of()));
        JsonObject oldJson = JSON.toJsonTree(old).getAsJsonObject(); oldJson.remove("displayPreferences");
        Backup.Archive oldRead = JSON.fromJson(oldJson, Backup.Archive.class);
        try (TitleService legacy = new TitleService(new Database(new Database.Config(base + names.get(2), "root", password)))) {
          await(legacy.reconnect()); await(legacy.restoreEmpty(ADMIN, oldRead));
          check(legacy.snapshot().displayPreferences().isEmpty() && legacy.snapshot().displayPreferences(alice).equals(DisplayPreferences.defaults()),
              "legacy format 2 without preferences restores enabled defaults");
        }
        var preview = await(s.deletePreview(ADMIN, alice));
        await(s.toggleDisplayPosition(alice, DisplayPosition.CHAT));
        deny(s.deletePlayer(ADMIN, alice, preview.stamp(), "AliceRenamed"), "player erasure preview expires when display preferences change");
        DisplayPreferences beforeClear = s.snapshot().displayPreferences(alice);
        preview = await(s.deletePreview(ADMIN, alice));
        await(s.deletePlayer(ADMIN, alice, preview.stamp(), "AliceRenamed"));
        check(!s.snapshot().displayPreferences().containsKey(alice), "player cleanup cascades preference removal");
        await(s.undo(ADMIN, lastChange(s, "PLAYER_DELETE")));
        check(s.snapshot().displayPreferences(alice).equals(beforeClear), "player cleanup undo restores preferences along with the profile");
        UUID from = UUID.randomUUID(), to = UUID.randomUUID();
        await(s.login(from, "MigrationSource")); await(s.login(to, "MigrationTarget"));
        await(s.toggleDisplayPosition(from, DisplayPosition.TAB));
        await(s.migrateIdentity(ADMIN, from, to));
        check(!s.snapshot().displayPreferences(to).tab() && s.snapshot().displayPreferences(from).tab(), "explicit identity migration moves choices and clears the old identity choices");
        await(s.toggleDisplayPosition(from, DisplayPosition.HEAD));
        deny(s.migrateIdentity(ADMIN, from, to), "identity migration refuses to overwrite target display preferences");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("checks", checks); report.put("passed", checks.size()); report.put("matrixCombinations", 64);
        report.put("schema", 6); report.put("backupFormat", 4); report.put("productionModified", false);
        Files.createDirectories(Path.of("build/reports"));
        Files.writeString(Path.of("build/reports/display-positions.json"), JSON.toJson(report));
      }
    } finally {
      try (var c = root.open()) { for (String n : names) Database.execute(c, "DROP DATABASE IF EXISTS " + n); }
    }
    System.out.println("Display position checks passed: " + checks.size());
  }
}
