package cn.pixelmc.memorialtitles.persistence;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import java.sql.*;
import java.util.*;

/** One connection per bounded background job. No JDBC objects escape their worker. */
public final class Database {
  public record Config(String url, String user, String password) {
    public Config {
      if (url == null || !url.startsWith("jdbc:mariadb://"))
        throw new IllegalArgumentException("需要 jdbc:mariadb:// URL");
      Objects.requireNonNull(user);
      Objects.requireNonNull(password);
    }
  }

  private final Config config;

  public Database(Config config) {
    this.config = config;
  }

  public Connection open() throws SQLException {
    // Explicit construction also works with Forge's module loader and the packaged JDBC driver.
    Properties props = new Properties();
    props.setProperty("user", config.user);
    props.setProperty("password", config.password);
    props.setProperty("connectTimeout", "3000");
    props.setProperty("socketTimeout", "10000");
    props.setProperty("allowLocalInfile", "false");
    Connection c = new org.mariadb.jdbc.Driver().connect(config.url, props);
    if (c == null) throw new SQLException("Unsupported database URL");
    c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    return c;
  }

  public void migrate() throws SQLException {
    try (Connection c = open()) {
      try (PreparedStatement p =
          c.prepareStatement("SELECT GET_LOCK('memorialtitles_schema_v1',10)")) {
        try (ResultSet r = p.executeQuery()) {
          if (!r.next() || r.getInt(1) != 1)
            throw new SQLException("Schema migration lock unavailable");
        }
      }
      try {
        execute(c, "CREATE TABLE IF NOT EXISTS mt_schema (version INT PRIMARY KEY) ENGINE=InnoDB");
        int current = 0;
        try (Statement s = c.createStatement();
            ResultSet r = s.executeQuery("SELECT MAX(version) FROM mt_schema")) {
          if (r.next()) current = r.getInt(1);
        }
        if (current > 5) throw new SQLException("Database schema is newer than this mod");
        if (current == 0) {
          String[] ddl = {
            "CREATE TABLE IF NOT EXISTS mt_state (id INT PRIMARY KEY, settings LONGTEXT NOT NULL)"
                + " ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_titles (id VARCHAR(64) CHARACTER SET ascii COLLATE"
                + " ascii_bin PRIMARY KEY, definition LONGTEXT NOT NULL) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_players (uuid CHAR(36) CHARACTER SET ascii PRIMARY KEY,"
                + " name VARCHAR(64) NOT NULL, joined_at BIGINT NOT NULL, last_seen BIGINT NOT"
                + " NULL, INDEX(name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS mt_owned (player CHAR(36) CHARACTER SET ascii NOT NULL,"
                + " title VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, acquired_at"
                + " BIGINT NOT NULL, collection_public BOOLEAN NOT NULL DEFAULT FALSE,"
                + " display_public BOOLEAN NOT NULL DEFAULT TRUE, PRIMARY KEY(player,title),"
                + " FOREIGN KEY(player) REFERENCES mt_players(uuid), FOREIGN KEY(title) REFERENCES"
                + " mt_titles(id)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_equipped (player CHAR(36) CHARACTER SET ascii PRIMARY"
                + " KEY, title VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, FOREIGN"
                + " KEY(player,title) REFERENCES mt_owned(player,title) ON DELETE CASCADE)"
                + " ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_receipts (player CHAR(36) CHARACTER SET ascii NOT NULL,"
                + " title VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, claimed_at"
                + " BIGINT NOT NULL, PRIMARY KEY(player,title), FOREIGN KEY(player) REFERENCES"
                + " mt_players(uuid), FOREIGN KEY(title) REFERENCES mt_titles(id)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_admins (player CHAR(36) CHARACTER SET ascii PRIMARY KEY,"
                + " FOREIGN KEY(player) REFERENCES mt_players(uuid)) ENGINE=InnoDB",
            "CREATE TABLE IF NOT EXISTS mt_audit (id BIGINT AUTO_INCREMENT PRIMARY KEY, at_ms"
                + " BIGINT NOT NULL, actor VARCHAR(128) NOT NULL, action VARCHAR(64) NOT NULL,"
                + " target VARCHAR(128) NOT NULL, title VARCHAR(64) NOT NULL, reason TEXT NOT NULL,"
                + " INDEX(at_ms)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
          };
          for (String sql : ddl) execute(c, sql);
          update(
              c,
              "INSERT IGNORE INTO mt_state(id,settings) VALUES(1,?)",
              JSON.toJson(Settings.defaults()));
          execute(c, "INSERT IGNORE INTO mt_schema(version) VALUES(1)");
        }
        if (current < 2) {
          c.setAutoCommit(false);
          try {
            Map<String, Long> dates = new HashMap<>();
            try (Statement stmt = c.createStatement();
                ResultSet r =
                    stmt.executeQuery(
                        "SELECT title, MIN(at_ms) AS created_at FROM mt_audit WHERE"
                            + " action='TITLE_SAVE' AND title<>'' GROUP BY title")) {
              while (r.next()) dates.put(r.getString("title"), r.getLong("created_at"));
            }
            List<Title> old = new ArrayList<>();
            try (Statement stmt = c.createStatement();
                ResultSet r = stmt.executeQuery("SELECT definition FROM mt_titles")) {
              while (r.next()) old.add(JSON.fromJson(r.getString(1), Title.class));
            }
            for (Title title : old)
              if (title.createdAt() == 0 && dates.containsKey(title.id()))
                update(
                    c,
                    "UPDATE mt_titles SET definition=? WHERE id=?",
                    JSON.toJson(title.created(dates.get(title.id()))),
                    title.id());
            execute(c, "INSERT IGNORE INTO mt_schema(version) VALUES(2)");
            c.commit();
          } catch (Exception e) {
            c.rollback();
            throw e;
          } finally {
            c.setAutoCommit(true);
          }
        }
        if (current < 3) {
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS mt_cell_versions (cell_key VARCHAR(256) CHARACTER SET"
                  + " ascii COLLATE ascii_bin PRIMARY KEY, version BIGINT NOT NULL) ENGINE=InnoDB");
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS mt_changes (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                  + " audit_id BIGINT NOT NULL, payload LONGTEXT NOT NULL, reverted_by BIGINT NOT"
                  + " NULL DEFAULT 0, INDEX(audit_id)) ENGINE=InnoDB");
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS mt_audit_hidden (audit_id BIGINT PRIMARY KEY)"
                  + " ENGINE=InnoDB");
          execute(c, "INSERT IGNORE INTO mt_schema(version) VALUES(3)");
        }
        if (current < 4) {
          execute(
              c,
              "CREATE TABLE IF NOT EXISTS mt_notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY,"
                  + " player CHAR(36) CHARACTER SET ascii NOT NULL, title VARCHAR(64) CHARACTER SET"
                  + " ascii COLLATE ascii_bin NOT NULL, title_name LONGTEXT NOT NULL, granted BOOLEAN"
                  + " NOT NULL, created_at BIGINT NOT NULL, INDEX(player,id), FOREIGN KEY(player)"
                  + " REFERENCES mt_players(uuid) ON DELETE CASCADE) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
          execute(c, "INSERT IGNORE INTO mt_schema(version) VALUES(4)");
        }
        if (current < 5) {
          execute(c,
              "CREATE TABLE IF NOT EXISTS mt_display_preferences (player CHAR(36) CHARACTER SET"
                  + " ascii PRIMARY KEY, head BOOLEAN NOT NULL DEFAULT TRUE, chat BOOLEAN NOT NULL"
                  + " DEFAULT TRUE, tab BOOLEAN NOT NULL DEFAULT TRUE, FOREIGN KEY(player)"
                  + " REFERENCES mt_players(uuid) ON DELETE CASCADE) ENGINE=InnoDB");
          execute(c, "INSERT IGNORE INTO mt_schema(version) VALUES(5)");
        }
      } finally {
        execute(c, "SELECT RELEASE_LOCK('memorialtitles_schema_v1')");
      }
    }
  }

  public record Commit<T>(T result, Snapshot snapshot) {}

  @FunctionalInterface
  public interface Work<T> {
    T run(Tx tx) throws Exception;
  }

  public <T> Commit<T> transaction(Work<T> work) throws Exception {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      Tx tx = new Tx(c);
      try {
        // All instances sharing this database serialize writes; authorization is checked under this
        // lock.
        try (Statement s = c.createStatement();
            ResultSet r = s.executeQuery("SELECT id FROM mt_state WHERE id=1 FOR UPDATE")) {
          if (!r.next()) throw new SQLException("Schema not initialized");
        }
        Snapshot before = tx.snapshot();
        T result = work.run(tx);
        Snapshot next = tx.snapshot();
        ChangeJournal.capture(tx, before, next);
        captureNotifications(tx, before, next);
        c.commit();
        return new Commit<>(result, next);
      } catch (Exception error) {
        try {
          c.rollback();
        } catch (SQLException rollback) {
          error.addSuppressed(rollback);
        }
        for (int i = tx.rollbackHooks.size() - 1; i >= 0; i--)
          try {
            tx.rollbackHooks.get(i).run();
          } catch (RuntimeException failure) {
            error.addSuppressed(failure);
          }
        throw error;
      }
    }
  }

  public Snapshot read() throws Exception {
    return transaction(Tx::snapshot).result;
  }

  private static void captureNotifications(Tx tx, Snapshot before, Snapshot next)
      throws SQLException {
    Set<Key> changed = new HashSet<>(before.owned().keySet());
    changed.addAll(next.owned().keySet());
    for (Key key : changed) {
      boolean had = before.owned().containsKey(key), has = next.owned().containsKey(key);
      if (had == has || !next.players().containsKey(key.player())) continue;
      Title title = (has ? next : before).titles().get(key.title());
      if (title == null) continue;
      tx.sql(
          "INSERT INTO mt_notifications(player,title,title_name,granted,created_at) VALUES(?,?,?,?,?)",
          key.player().toString(), key.title(), JSON.toJson(title.name()), has,
          System.currentTimeMillis());
    }
  }

  public List<PlayerNotice> notifications(UUID player) throws SQLException {
    try (Connection c = open();
        PreparedStatement p = c.prepareStatement(
            "SELECT id,title,title_name,granted,created_at FROM mt_notifications WHERE player=?"
                + " ORDER BY id LIMIT 20")) {
      p.setString(1, player.toString());
      try (ResultSet r = p.executeQuery()) {
        List<PlayerNotice> out = new ArrayList<>();
        while (r.next())
          out.add(new PlayerNotice(r.getLong(1), player, r.getString(2),
              JSON.fromJson(r.getString(3), RichText.class), r.getBoolean(4), r.getLong(5)));
        return List.copyOf(out);
      }
    }
  }

  public void acknowledgeNotifications(UUID player, List<PlayerNotice> messages) throws SQLException {
    if (messages.isEmpty()) return;
    if (messages.size() > 20 || messages.stream().anyMatch(n -> !n.player().equals(player)))
      throw new IllegalArgumentException("Invalid notification acknowledgement");
    String placeholders = String.join(",", Collections.nCopies(messages.size(), "?"));
    try (Connection c = open();
        PreparedStatement p = c.prepareStatement(
            "DELETE FROM mt_notifications WHERE player=? AND id IN (" + placeholders + ")")) {
      p.setString(1, player.toString());
      for (int i = 0; i < messages.size(); i++) p.setLong(i + 2, messages.get(i).id());
      p.executeUpdate();
    }
  }

  public static void execute(Connection c, String sql) throws SQLException {
    try (Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  public static int update(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      return p.executeUpdate();
    }
  }

  public static final class Tx {
    public final Connection connection;
    public final List<Audit> emitted = new ArrayList<>();
    public final List<ChangeJournal.Unit> changeUnits = new ArrayList<>();
    public final List<Runnable> rollbackHooks = new ArrayList<>();
    public boolean customJournal, skipJournal;

    private Tx(Connection c) {
      connection = c;
    }

    public int sql(String sql, Object... args) throws SQLException {
      return update(connection, sql, args);
    }

    public long audit(String actor, String action, String target, String title, String reason)
        throws SQLException {
      check(actor, 128);
      check(action, 64);
      check(target, 128);
      check(title, 64);
      check(reason, 2048);
      long now = System.currentTimeMillis();
      sql(
          "INSERT INTO mt_audit(at_ms,actor,action,target,title,reason) VALUES(?,?,?,?,?,?)",
          now,
          actor,
          action,
          target,
          title,
          reason);
      long id;
      try (var stmt = connection.createStatement();
          var r = stmt.executeQuery("SELECT LAST_INSERT_ID()")) {
        r.next();
        id = r.getLong(1);
      }
      emitted.add(new Audit(id, now, actor, action, target, title, reason));
      return id;
    }

    public List<Audit> history(int limit) throws SQLException {
      return history(limit, false);
    }

    public List<Audit> history(int limit, boolean includeHidden) throws SQLException {
      List<Audit> list = new ArrayList<>();
      try (PreparedStatement p =
          connection.prepareStatement(
              "SELECT * FROM mt_audit a "
                  + (includeHidden
                      ? ""
                      : "WHERE NOT EXISTS (SELECT 1 FROM mt_audit_hidden h WHERE h.audit_id=a.id) ")
                  + "ORDER BY id DESC LIMIT ?")) {
        p.setInt(1, Math.max(1, Math.min(limit, 1000000)));
        try (ResultSet r = p.executeQuery()) {
          while (r.next())
            list.add(
                new Audit(
                    r.getLong("id"),
                    r.getLong("at_ms"),
                    r.getString("actor"),
                    r.getString("action"),
                    r.getString("target"),
                    r.getString("title"),
                    r.getString("reason")));
        }
      }
      return list;
    }

    public Snapshot snapshot() throws SQLException {
      Map<String, Title> titles = new HashMap<>();
      Map<UUID, Profile> players = new HashMap<>();
      Map<Key, Owned> owned = new HashMap<>();
      Map<UUID, String> equipped = new HashMap<>();
      Set<UUID> admins = new HashSet<>();
      Map<Key, Receipt> receipts = new HashMap<>();
      Map<UUID, DisplayPreferences> displayPreferences = new HashMap<>();
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_titles")) {
        while (r.next()) {
          Title t = JSON.fromJson(r.getString("definition"), Title.class);
          if (!r.getString("id").equals(t.id())) throw new SQLException("Title ID mismatch");
          titles.put(t.id(), t);
        }
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_players")) {
        while (r.next()) {
          UUID id = UUID.fromString(r.getString("uuid"));
          players.put(
              id,
              new Profile(id, r.getString("name"), r.getLong("joined_at"), r.getLong("last_seen")));
        }
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_owned")) {
        while (r.next()) {
          Owned o =
              new Owned(
                  UUID.fromString(r.getString("player")),
                  r.getString("title"),
                  r.getLong("acquired_at"),
                  r.getBoolean("collection_public"),
                  r.getBoolean("display_public"));
          owned.put(new Key(o.player(), o.title()), o);
        }
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_equipped")) {
        while (r.next()) equipped.put(UUID.fromString(r.getString("player")), r.getString("title"));
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_admins")) {
        while (r.next()) admins.add(UUID.fromString(r.getString("player")));
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT * FROM mt_receipts")) {
        while (r.next()) {
          Receipt v =
              new Receipt(
                  UUID.fromString(r.getString("player")),
                  r.getString("title"),
                  r.getLong("claimed_at"));
          receipts.put(new Key(v.player(), v.title()), v);
        }
      }
      Settings settings;
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT player,head,chat,tab FROM mt_display_preferences")) {
        while (r.next()) displayPreferences.put(UUID.fromString(r.getString(1)),
            new DisplayPreferences(r.getBoolean(2), r.getBoolean(3), r.getBoolean(4)));
      }
      try (Statement s = connection.createStatement();
          ResultSet r = s.executeQuery("SELECT settings FROM mt_state WHERE id=1")) {
        if (!r.next()) throw new SQLException("State missing");
        settings = JSON.fromJson(r.getString(1), Settings.class);
      }
      return new Snapshot(titles, players, owned, equipped, admins, receipts, settings, displayPreferences);
    }

    public void displayPreferences(UUID player, DisplayPreferences preferences) throws SQLException {
      if (preferences.equals(DisplayPreferences.defaults())) {
        sql("DELETE FROM mt_display_preferences WHERE player=?", player.toString());
        return;
      }
      sql("INSERT INTO mt_display_preferences(player,head,chat,tab) VALUES(?,?,?,?)"
          + " ON DUPLICATE KEY UPDATE head=VALUES(head),chat=VALUES(chat),tab=VALUES(tab)",
          player.toString(), preferences.head(), preferences.chat(), preferences.tab());
    }

    public void title(Title t) throws SQLException {
      sql(
          "INSERT INTO mt_titles(id,definition) VALUES(?,?) ON DUPLICATE KEY UPDATE"
              + " definition=VALUES(definition)",
          t.id(),
          JSON.toJson(t));
    }

    public void settings(Settings settings) throws SQLException {
      sql("UPDATE mt_state SET settings=? WHERE id=1", JSON.toJson(settings));
    }
  }
}
