package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Reproduce the production database's mixed column defaults, then verify both fixes. */
public final class HistoryCollationIntegrationSuite {
  private static int checks;
  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }
  private static <T> T await(CompletableFuture<T> value) throws Exception {
    return value.get(20, TimeUnit.SECONDS);
  }
  private static final String LEGACY =
      "SELECT COUNT(*) FROM mt_audit a LEFT JOIN mt_players p ON p.uuid=a.actor"
          + " LEFT JOIN mt_players t ON t.uuid=a.target LEFT JOIN mt_titles d ON d.id=a.title"
          + " WHERE (?='' OR INSTR(CONCAT_WS(' ',a.actor,a.target,a.title,a.reason,a.action,"
          + "p.name,t.name,d.definition),?)>0)";

  public static void main(String[] args) throws Exception {
    String base = "jdbc:mariadb://127.0.0.1:43321/";
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String name = "mt_collation_" + UUID.randomUUID().toString().replace("-", "");
    Database root = new Database(new Database.Config(base, "root", password));
    try (Connection c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    }
    Database db = new Database(new Database.Config(base + name, "root", password));
    var actor = TitleService.Actor.consoleActor();
    UUID player = UUID.randomUUID();
    try (TitleService service = new TitleService(db)) {
      await(service.reconnect());
      await(service.login(player, "TestPlayer"));
      Title title = new Title("test_title", RichText.plain("纪念🌟"), "", "", "", "", 0, 0, true, true, 0);
      await(service.saveTitle(actor, title, 0));
      await(service.grant(actor, List.of(player), "test_title", "中文奖励🌟"));
      try (Connection c = db.open(); PreparedStatement p = c.prepareStatement(LEGACY)) {
        p.setString(1, ""); p.setString(2, "");
        boolean reproduced = false;
        try (ResultSet ignored = p.executeQuery()) {
          // A differently configured local test database must not silently pass the reproduction.
        } catch (SQLException error) {
          reproduced = error.getErrorCode() == 1267;
        }
        check(reproduced, "original query reproduces production error 1267");
      }
      var all = await(service.searchHistory(actor, "", "", "", "", 0, 100));
      check(all.total() == 2 && all.rows().size() == 2, "empty search works with mixed collations");
      check(await(service.searchHistory(actor, "中文奖励", "", "", "", 0, 100)).total() == 1,
          "Chinese reason search works");
      check(await(service.searchHistory(actor, "纪念🌟", "", "", "", 0, 100)).total() == 2,
          "formatted title definition and emoji search works");
      check(await(service.searchHistory(actor, "testplayer", "", "", "", 0, 100)).total() == 1,
          "player name search remains case insensitive");
      check(await(service.searchHistory(actor, "", "GRANT,REVOKE", player.toString(), "test_title", 0, 100)).total() == 1,
          "action player and title filters work together");
      check(await(service.searchHistory(actor, "missing", "", "", "", 0, 100)).total() == 0,
          "nonmatching search remains empty");
      check(await(service.searchHistory(actor, "", "", "", "", 1, 1)).rows().size() == 1,
          "paged rows and count queries use matching collation rules");
      try (Connection c = db.open()) {
        Database.execute(c, "SET SESSION lock_wait_timeout=3");
        Database.execute(c, "ALTER TABLE mt_titles MODIFY definition LONGTEXT CHARACTER SET utf8mb4"
            + " COLLATE utf8mb4_general_ci NOT NULL, ALGORITHM=INPLACE, LOCK=NONE");
        try (PreparedStatement p = c.prepareStatement(LEGACY)) {
          p.setString(1, ""); p.setString(2, "");
          try (ResultSet r = p.executeQuery()) {
            r.next();
            check(r.getLong(1) == 2, "online column fix repairs the currently loaded legacy query");
          }
        }
      }
      check(await(service.searchHistory(actor, "中文奖励", "", "", "", 0, 100)).total() == 1,
          "new query also works after the online column fix");
      check(service.snapshot().ownership(player, "test_title") != null,
          "online collation change preserves title ownership");
      System.out.println("HISTORY COLLATION CHECKS PASSED: " + checks);
    } finally {
      try (Connection c = root.open()) {
        Database.execute(c, "DROP DATABASE " + name);
      }
    }
  }
}
