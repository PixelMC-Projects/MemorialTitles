package cn.pixelmc.memorialtitles.persistence;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import com.google.gson.*;
import java.sql.*;
import java.util.*;

/** Stores inverse patches for individual changes, never a world/database snapshot. */
public final class ChangeJournal {
  public record Delta(String key, JsonElement before, JsonElement after) {
    public Delta {
      before = before == null ? JsonNull.INSTANCE : before;
      after = after == null ? JsonNull.INSTANCE : after;
    }
  }

  public record Unit(long audit, String label, String target, String title, List<Delta> changes) {}

  public record Entry(
      long id,
      long audit,
      String label,
      String target,
      String title,
      List<Delta> changes,
      Map<String, Long> guards,
      long revertedBy) {}

  public record Export(List<Entry> entries, Map<String, Long> versions, List<Long> hidden) {}

  public record View(
      long id,
      String label,
      String target,
      String title,
      boolean undone,
      boolean allowed,
      List<String> conflicts,
      List<Delta> changes) {}

  public interface Assets {
    JsonElement read(UUID id) throws Exception;

    Runnable swap(UUID id, JsonElement expected, JsonElement desired) throws Exception;

    default void playerPresent(UUID id, boolean present) {}
  }

  public static final Assets NO_ASSETS =
      new Assets() {
        public JsonElement read(UUID id) {
          return JsonNull.INSTANCE;
        }

        public Runnable swap(UUID id, JsonElement expected, JsonElement desired) {
          if (!desired.isJsonNull()) throw new IllegalStateException("头像存储未就绪，不能恢复包含头像的改动");
          return () -> {};
        }
      };
  private static final Set<String> ELIGIBLE =
      Set.of("TITLE_SAVE", "GRANT", "REVOKE", "EQUIP", "PRIVACY", "DISPLAY_POSITION", "SETTINGS", "RELOAD");
  private static final Map<String, String> LABELS =
      Map.ofEntries(
          Map.entry("name", "称号文字与样式"),
          Map.entry("description", "称号说明"),
          Map.entry("source", "来源 / 整合包"),
          Map.entry("acquisition", "获取说明"),
          Map.entry("category", "分类"),
          Map.entry("order", "游戏内排序"),
          Map.entry("icon", "图标"),
          Map.entry("visible", "图鉴可见性"),
          Map.entry("issuable", "是否绝版"),
          Map.entry("reward", "首登奖励"),
          Map.entry("head", "头顶展示"),
          Map.entry("chat", "聊天展示"),
          Map.entry("tab", "TAB 展示"),
          Map.entry("headScale", "头顶缩放"),
          Map.entry("headOffset", "头顶间距"),
          Map.entry("chatPrefix", "聊天格式"),
          Map.entry("retentionDays", "备份保留天数"),
          Map.entry("packName", "整合包名称"),
          Map.entry("packStartedOn", "开档日期"),
          Map.entry("publicCollection", "收藏隐私"),
          Map.entry("publicDisplay", "佩戴展示隐私"));

  public static JsonElement json(Object value) {
    return value == null ? JsonNull.INSTANCE : JSON.toJsonTree(value);
  }

  private static boolean same(Object a, Object b) {
    return Objects.equals(a, b);
  }

  private static String own(Key k) {
    return "O|" + k.player() + "|" + k.title();
  }

  private static <T> Set<T> union(Collection<T> a, Collection<T> b) {
    Set<T> out = new LinkedHashSet<>(a);
    out.addAll(b);
    return out;
  }

  private static void add(List<Delta> out, String key, Object a, Object b) {
    if (!same(a, b)) out.add(new Delta(key, json(a), json(b)));
  }

  public static List<Delta> diff(Snapshot a, Snapshot b) {
    List<Delta> out = new ArrayList<>();
    for (String id : union(a.titles().keySet(), b.titles().keySet())) {
      Title x = a.titles().get(id), y = b.titles().get(id);
      if (x == null || y == null) add(out, "T|" + id, x, y);
      else {
        JsonObject before = json(x).getAsJsonObject(), after = json(y).getAsJsonObject();
        for (String field :
            List.of(
                "name",
                "description",
                "source",
                "acquisition",
                "category",
                "order",
                "icon",
                "visible",
                "issuable")) add(out, "T|" + id + "|" + field, before.get(field), after.get(field));
      }
    }
    for (UUID id : union(a.players().keySet(), b.players().keySet()))
      add(out, "P|" + id, a.players().get(id), b.players().get(id));
    for (UUID id : union(a.players().keySet(), b.players().keySet()))
      for (DisplayPosition position : DisplayPosition.values())
        add(out, "D|" + id + "|" + position.name().toLowerCase(Locale.ROOT),
            a.players().containsKey(id) ? a.displayPreferences(id).enabled(position) : null,
            b.players().containsKey(id) ? b.displayPreferences(id).enabled(position) : null);
    for (Key k : union(a.owned().keySet(), b.owned().keySet())) {
      Owned x = a.owned().get(k), y = b.owned().get(k);
      if (x == null || y == null || x.acquiredAt() != y.acquiredAt()) add(out, own(k), x, y);
      else {
        add(out, own(k) + "|publicCollection", x.publicCollection(), y.publicCollection());
        add(out, own(k) + "|publicDisplay", x.publicDisplay(), y.publicDisplay());
      }
    }
    for (UUID id : union(a.equipped().keySet(), b.equipped().keySet()))
      add(out, "E|" + id, a.equipped().get(id), b.equipped().get(id));
    for (Key k : union(a.receipts().keySet(), b.receipts().keySet()))
      add(out, "R|" + k.player() + "|" + k.title(), a.receipts().get(k), b.receipts().get(k));
    JsonObject x = json(a.settings()).getAsJsonObject(), y = json(b.settings()).getAsJsonObject();
    for (String field : x.keySet()) add(out, "S|" + field, x.get(field), y.get(field));
    return out;
  }

  public static void capture(Database.Tx tx, Snapshot before, Snapshot after) throws Exception {
    if (tx.skipJournal) return;
    List<Delta> deltas = diff(before, after);
    Set<String> touched = new LinkedHashSet<>();
    for (Delta d : deltas) touched.add(d.key());
    for (Unit unit : tx.changeUnits) for (Delta d : unit.changes()) touched.add(d.key());
    for (String id : union(before.titles().keySet(), after.titles().keySet())) {
      Title a = before.titles().get(id), b = after.titles().get(id);
      if (!same(a, b)) touched.add("G|title|" + id);
      if (a == null || b == null || a.createdAt() != b.createdAt())
        if (!same(a, b)) touched.add("C|title|" + id);
    }
    for (UUID id : union(before.players().keySet(), after.players().keySet())) {
      Profile a = before.players().get(id), b = after.players().get(id);
      if (!same(a, b)) touched.add("G|player|" + id);
      if ((a == null || b == null || a.joinedAt() != b.joinedAt()) && !same(a, b))
        touched.add("C|player|" + id);
    }
    for (Key k : union(before.owned().keySet(), after.owned().keySet())) {
      Owned a = before.owned().get(k), b = after.owned().get(k);
      if (!same(a, b)) {
        touched.add("G|owned|" + k.player() + "|" + k.title());
        touched.add("G|player|" + k.player());
      }
      if ((a == null || b == null || a.acquiredAt() != b.acquiredAt()) && !same(a, b))
        touched.add("C|owned|" + k.player() + "|" + k.title());
    }
    for (Delta d : deltas) {
      String[] p = d.key().split("\\|");
      if (Set.of("E", "R", "D").contains(p[0])) touched.add("G|player|" + p[1]);
    }
    for (UUID id : union(before.admins(), after.admins()))
      if (before.admins().contains(id) != after.admins().contains(id))
        touched.add("G|player|" + id);
    for (String key : touched)
      tx.sql(
          "INSERT INTO mt_cell_versions(cell_key,version) VALUES(?,1) ON DUPLICATE KEY UPDATE"
              + " version=version+1",
          key);
    List<Unit> units = new ArrayList<>(tx.changeUnits);
    if (!tx.customJournal) {
      Map<String, List<Delta>> groups = new LinkedHashMap<>();
      Map<String, Audit> origins = new HashMap<>();
      for (Delta d : deltas) {
        Audit audit = origin(tx.emitted, d);
        if (audit == null) continue;
        String[] p = d.key().split("\\|");
        boolean field =
            p[0].equals("S")
                || p[0].equals("D")
                || (p[0].equals("T") && p.length == 3)
                || (p[0].equals("O") && p.length == 4);
        String key = audit.id() + ":" + (field ? d.key() : "action");
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
        origins.put(key, audit);
      }
      for (var g : groups.entrySet()) {
        Audit a = origins.get(g.getKey());
        List<Delta> ds = g.getValue();
        units.add(new Unit(a.id(), label(a, ds), a.target(), a.title(), List.copyOf(ds)));
      }
    }
    for (Unit u : units) {
      Map<String, Long> guards = new TreeMap<>();
      for (Delta d : u.changes()) {
        putGuard(tx, guards, d.key());
        String[] p = d.key().split("\\|");
        switch (p[0]) {
          case "T" -> putGuard(tx, guards, (p.length == 2 ? "G" : "C") + "|title|" + p[1]);
          case "P" -> putGuard(tx, guards, "G|player|" + p[1]);
          case "D" -> putGuard(tx, guards, "C|player|" + p[1]);
          case "O" -> {
            putGuard(tx, guards, (p.length == 3 ? "G" : "C") + "|owned|" + p[1] + "|" + p[2]);
            putGuard(tx, guards, "C|player|" + p[1]);
            putGuard(tx, guards, "G|title|" + p[2]);
          }
          case "R" -> {
            putGuard(tx, guards, "C|player|" + p[1]);
            putGuard(tx, guards, "G|title|" + p[2]);
          }
          case "E" -> {
            putGuard(tx, guards, "C|player|" + p[1]);
            for (JsonElement v : List.of(d.before(), d.after()))
              if (!v.isJsonNull()) putGuard(tx, guards, "G|title|" + v.getAsString());
          }
          case "S" -> {
            if (p[1].equals("reward"))
              for (JsonElement v : List.of(d.before(), d.after()))
                if (!v.isJsonNull() && !v.getAsString().isEmpty())
                  putGuard(tx, guards, "G|title|" + v.getAsString());
          }
          default -> {}
        }
      }
      Entry e = new Entry(0, u.audit(), u.label(), u.target(), u.title(), u.changes(), guards, 0);
      tx.sql(
          "INSERT INTO mt_changes(audit_id,payload,reverted_by) VALUES(?,?,0)",
          u.audit(),
          JSON.toJson(e));
    }
  }

  private static Audit origin(List<Audit> audits, Delta d) {
    String[] p = d.key().split("\\|");
    for (int i = audits.size() - 1; i >= 0; i--) {
      Audit a = audits.get(i);
      if (!ELIGIBLE.contains(a.action())) continue;
      if (p[0].equals("T")
          && ((a.title().equals(p[1]) && a.action().equals("TITLE_SAVE"))
              || a.action().equals("RELOAD"))) return a;
      if (p[0].equals("S") && Set.of("SETTINGS", "RELOAD").contains(a.action())) return a;
      if (p[0].equals("D") && a.target().equals(p[1]) && a.action().equals("DISPLAY_POSITION")) return a;
      if (Set.of("O", "R").contains(p[0])
          && a.target().equals(p[1])
          && a.title().equals(p[2])
          && Set.of("GRANT", "REVOKE", "PRIVACY").contains(a.action())) return a;
      if (p[0].equals("E")
          && a.target().equals(p[1])
          && Set.of("EQUIP", "REVOKE").contains(a.action())) return a;
    }
    return null;
  }

  private static String label(Audit a, List<Delta> ds) {
    if (a.action().equals("DISPLAY_POSITION") && ds.size() == 1)
      return "个人" + LABELS.getOrDefault(ds.get(0).key().split("\\|")[2], "显示位置");
    if (ds.size() == 1) {
      String[] p = ds.get(0).key().split("\\|");
      if ((p[0].equals("T") && p.length == 3)
          || p[0].equals("S")
          || (p[0].equals("O") && p.length == 4))
        return LABELS.getOrDefault(p[p.length - 1], "资料修改");
    }
    return switch (a.action()) {
      case "GRANT" -> "本次发放";
      case "REVOKE" -> "本次收回及自动卸下";
      case "EQUIP" -> "本次佩戴变更";
      default -> "本次新建称号";
    };
  }

  private static void putGuard(Database.Tx tx, Map<String, Long> guards, String key)
      throws SQLException {
    guards.put(key, version(tx, key));
  }

  public static long version(Database.Tx tx, String key) throws SQLException {
    try (var p =
        tx.connection.prepareStatement("SELECT version FROM mt_cell_versions WHERE cell_key=?")) {
      p.setString(1, key);
      try (var r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  public static List<Entry> entries(Database.Tx tx, long audit) throws SQLException {
    List<Entry> out = new ArrayList<>();
    try (var p =
        tx.connection.prepareStatement(
            "SELECT id,audit_id,payload,reverted_by FROM mt_changes WHERE audit_id=? ORDER BY"
                + " id")) {
      p.setLong(1, audit);
      try (var r = p.executeQuery()) {
        while (r.next()) out.add(entry(r));
      }
    }
    return out;
  }

  public static Entry get(Database.Tx tx, long id) throws SQLException {
    try (var p =
        tx.connection.prepareStatement(
            "SELECT id,audit_id,payload,reverted_by FROM mt_changes WHERE id=?")) {
      p.setLong(1, id);
      try (var r = p.executeQuery()) {
        if (!r.next()) throw new IllegalArgumentException("没有这项改动记录；旧记录未保存撤回数据");
        return entry(r);
      }
    }
  }

  private static Entry entry(ResultSet r) throws SQLException {
    Entry v = JSON.fromJson(r.getString("payload"), Entry.class);
    return new Entry(
        r.getLong("id"),
        r.getLong("audit_id"),
        v.label(),
        v.target(),
        v.title(),
        v.changes(),
        v.guards(),
        r.getLong("reverted_by"));
  }

  public static List<String> conflicts(Database.Tx tx, Snapshot s, Entry e, Assets assets)
      throws Exception {
    List<String> out = new ArrayList<>();
    if (e.revertedBy() != 0) out.add("此项已撤回，不能重复执行");
    for (var g : e.guards().entrySet())
      if (version(tx, g.getKey()) != g.getValue())
        out.add(conflictName(g.getKey()) + "在这次操作之后发生过变化");
    for (Delta d : e.changes())
      if (!same(read(tx, s, d.key(), assets), d.after()))
        out.add(conflictName(d.key()) + "当前值与操作完成时不同");
    if (out.isEmpty())
      try {
        validate(target(s, e.changes()));
      } catch (IllegalStateException | IllegalArgumentException ex) {
        out.add(ex.getMessage());
      }
    return out.stream().distinct().toList();
  }

  private static String conflictName(String key) {
    String[] p = key.split("\\|");
    return switch (p[0]) {
      case "T" -> p.length == 3 ? LABELS.getOrDefault(p[2], "称号资料") : "称号资料";
      case "S" -> LABELS.getOrDefault(p[1], "设置");
      case "P" -> "玩家档案";
      case "D" -> "个人" + LABELS.getOrDefault(p[2], "显示位置");
      case "O" -> "称号持有记录";
      case "E" -> "玩家佩戴状态";
      case "R" -> "首登领奖记录";
      case "H" -> "相关操作历史";
      case "V" -> "头像缓存";
      default -> key.contains("title") ? "相关称号" : "相关玩家资料";
    };
  }

  public static View view(Database.Tx tx, Snapshot s, Entry e, Assets assets) throws Exception {
    List<String> issues = conflicts(tx, s, e, assets);
    return new View(
        e.id(),
        e.label(),
        e.target(),
        e.title(),
        e.revertedBy() != 0,
        issues.isEmpty(),
        issues,
        e.changes().stream()
            .map(
                d ->
                    d.key().startsWith("V|")
                        ? new Delta(
                            d.key(),
                            d.before().isJsonNull() ? JsonNull.INSTANCE : json("已缓存"),
                            d.after().isJsonNull() ? JsonNull.INSTANCE : json("已缓存"))
                        : d)
            .toList());
  }

  public static JsonElement read(Database.Tx tx, Snapshot s, String key, Assets assets)
      throws Exception {
    String[] p = key.split("\\|");
    return switch (p[0]) {
      case "T" -> {
        Title t = s.titles().get(p[1]);
        yield t == null
            ? JsonNull.INSTANCE
            : p.length == 2 ? json(t) : json(t).getAsJsonObject().get(p[2]);
      }
      case "P" -> json(s.players().get(UUID.fromString(p[1])));
      case "D" -> {
        UUID id = UUID.fromString(p[1]);
        yield s.players().containsKey(id)
            ? json(s.displayPreferences(id).enabled(DisplayPosition.valueOf(p[2].toUpperCase(Locale.ROOT))))
            : JsonNull.INSTANCE;
      }
      case "O" -> {
        Owned o = s.ownership(UUID.fromString(p[1]), p[2]);
        yield o == null
            ? JsonNull.INSTANCE
            : p.length == 3 ? json(o) : json(o).getAsJsonObject().get(p[3]);
      }
      case "E" -> json(s.equipped().get(UUID.fromString(p[1])));
      case "R" -> json(s.receipts().get(new Key(UUID.fromString(p[1]), p[2])));
      case "S" -> json(s.settings()).getAsJsonObject().get(p[1]);
      case "V" -> assets.read(UUID.fromString(p[1]));
      case "H" -> {
        try (var q =
            tx.connection.prepareStatement(
                "SELECT audit_id FROM mt_audit_hidden WHERE audit_id=?")) {
          q.setLong(1, Long.parseLong(p[1]));
          try (var r = q.executeQuery()) {
            yield json(r.next());
          }
        }
      }
      default -> throw new IllegalArgumentException("无法识别的改动类型");
    };
  }

  private static Snapshot target(Snapshot s, List<Delta> ds) {
    Map<String, Title> ts = new HashMap<>(s.titles());
    Map<UUID, Profile> ps = new HashMap<>(s.players());
    Map<Key, Owned> os = new HashMap<>(s.owned());
    Map<UUID, String> es = new HashMap<>(s.equipped());
    Map<Key, Receipt> rs = new HashMap<>(s.receipts());
    Map<UUID, DisplayPreferences> preferences = new HashMap<>(s.displayPreferences());
    JsonObject settings = json(s.settings()).getAsJsonObject();
    for (Delta d : ds) {
      String[] p = d.key().split("\\|");
      JsonElement v = d.before();
      switch (p[0]) {
        case "T" -> {
          if (p.length == 2) {
            if (v.isJsonNull()) ts.remove(p[1]);
            else ts.put(p[1], JSON.fromJson(v, Title.class));
          } else {
            Title old = ts.get(p[1]);
            if (old == null) throw new IllegalStateException("相关称号已经不存在");
            JsonObject j = json(old).getAsJsonObject();
            j.add(p[2], v);
            ts.put(p[1], JSON.fromJson(j, Title.class).revised(old.revision() + 1));
          }
        }
        case "P" -> {
          UUID id = UUID.fromString(p[1]);
          if (v.isJsonNull()) { ps.remove(id); preferences.remove(id); }
          else ps.put(id, JSON.fromJson(v, Profile.class));
        }
        case "D" -> {
          UUID id = UUID.fromString(p[1]);
          if (v.isJsonNull()) preferences.remove(id);
          else {
            if (!ps.containsKey(id)) throw new IllegalStateException("相关玩家档案已不存在，不能恢复显示位置");
            preferences.put(id, preferences.getOrDefault(id, DisplayPreferences.defaults()).with(
                DisplayPosition.valueOf(p[2].toUpperCase(Locale.ROOT)), v.getAsBoolean()));
          }
        }
        case "O" -> {
          Key k = new Key(UUID.fromString(p[1]), p[2]);
          if (p.length == 3) {
            if (v.isJsonNull()) os.remove(k);
            else os.put(k, JSON.fromJson(v, Owned.class));
          } else {
            Owned old = os.get(k);
            if (old == null) throw new IllegalStateException("对应称号已不再持有");
            JsonObject j = json(old).getAsJsonObject();
            j.add(p[3], v);
            os.put(k, JSON.fromJson(j, Owned.class));
          }
        }
        case "E" -> {
          UUID id = UUID.fromString(p[1]);
          if (v.isJsonNull()) es.remove(id);
          else es.put(id, v.getAsString());
        }
        case "R" -> {
          Key k = new Key(UUID.fromString(p[1]), p[2]);
          if (v.isJsonNull()) rs.remove(k);
          else rs.put(k, JSON.fromJson(v, Receipt.class));
        }
        case "S" -> settings.add(p[1], v);
        default -> {}
      }
    }
    return new Snapshot(ts, ps, os, es, s.admins(), rs, JSON.fromJson(settings, Settings.class), preferences);
  }

  private static void validate(Snapshot s) {
    for (Owned o : s.owned().values())
      if (!s.players().containsKey(o.player()) || !s.titles().containsKey(o.title()))
        throw new IllegalStateException("仍有持有记录引用相关玩家或称号，请先处理后续变动");
    for (Receipt r : s.receipts().values())
      if (!s.players().containsKey(r.player()) || !s.titles().containsKey(r.title()))
        throw new IllegalStateException("首登领奖记录仍引用相关玩家或称号");
    for (var e : s.equipped().entrySet())
      if (!s.owned().containsKey(new Key(e.getKey(), e.getValue())))
        throw new IllegalStateException("后续佩戴记录依赖相关持有状态，不能直接撤回");
    if (!s.players().keySet().containsAll(s.admins()))
      throw new IllegalStateException("该玩家仍有管理授权，不能删除其身份");
    if (!s.players().keySet().containsAll(s.displayPreferences().keySet()))
      throw new IllegalStateException("显示位置仍引用相关玩家");
    if (!s.settings().reward().isEmpty() && !s.titles().containsKey(s.settings().reward()))
      throw new IllegalStateException("首登奖励仍引用这个称号，请先修改奖励设置");
  }

  public static void apply(Database.Tx tx, Entry entry, Assets assets) throws Exception {
    Snapshot current = tx.snapshot();
    List<String> issues = conflicts(tx, current, entry, assets);
    if (!issues.isEmpty()) throw new IllegalStateException("不能撤回：" + String.join("；", issues));
    Snapshot desired = target(current, entry.changes());
    Set<String> titleIds = new LinkedHashSet<>();
    Set<UUID> playerIds = new LinkedHashSet<>(), equipIds = new LinkedHashSet<>(), displayIds = new LinkedHashSet<>();
    Set<Key> ownIds = new LinkedHashSet<>(), receiptIds = new LinkedHashSet<>();
    boolean settings = false;
    for (Delta d : entry.changes()) {
      String[] p = d.key().split("\\|");
      switch (p[0]) {
        case "T" -> titleIds.add(p[1]);
        case "P" -> playerIds.add(UUID.fromString(p[1]));
        case "D" -> displayIds.add(UUID.fromString(p[1]));
        case "O" -> ownIds.add(new Key(UUID.fromString(p[1]), p[2]));
        case "R" -> receiptIds.add(new Key(UUID.fromString(p[1]), p[2]));
        case "E" -> equipIds.add(UUID.fromString(p[1]));
        case "S" -> settings = true;
        default -> {}
      }
    }
    for (UUID id : equipIds) tx.sql("DELETE FROM mt_equipped WHERE player=?", id.toString());
    for (String id : titleIds)
      if (desired.titles().containsKey(id)) tx.title(desired.titles().get(id));
    for (UUID id : playerIds)
      if (desired.players().containsKey(id)) {
        Profile p = desired.players().get(id);
        tx.sql(
            "INSERT INTO mt_players(uuid,name,joined_at,last_seen) VALUES(?,?,?,?) ON DUPLICATE KEY"
                + " UPDATE"
                + " name=VALUES(name),joined_at=VALUES(joined_at),last_seen=VALUES(last_seen)",
            id.toString(),
            p.name(),
            p.joinedAt(),
            p.lastSeen());
      }
    for (UUID id : displayIds) {
      if (desired.players().containsKey(id)) tx.displayPreferences(id, desired.displayPreferences(id));
      else tx.sql("DELETE FROM mt_display_preferences WHERE player=?", id.toString());
    }
    for (Key k : ownIds) {
      Owned o = desired.owned().get(k);
      if (o != null)
        tx.sql(
            "INSERT INTO mt_owned VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                + " acquired_at=VALUES(acquired_at),collection_public=VALUES(collection_public),display_public=VALUES(display_public)",
            k.player().toString(),
            k.title(),
            o.acquiredAt(),
            o.publicCollection(),
            o.publicDisplay());
    }
    for (Key k : receiptIds) {
      Receipt r = desired.receipts().get(k);
      if (r != null)
        tx.sql(
            "INSERT INTO mt_receipts VALUES(?,?,?) ON DUPLICATE KEY UPDATE"
                + " claimed_at=VALUES(claimed_at)",
            k.player().toString(),
            k.title(),
            r.claimedAt());
    }
    for (UUID id : equipIds)
      if (desired.equipped().containsKey(id))
        tx.sql("INSERT INTO mt_equipped VALUES(?,?)", id.toString(), desired.equipped().get(id));
    for (Key k : ownIds)
      if (!desired.owned().containsKey(k))
        tx.sql("DELETE FROM mt_owned WHERE player=? AND title=?", k.player().toString(), k.title());
    for (Key k : receiptIds)
      if (!desired.receipts().containsKey(k))
        tx.sql(
            "DELETE FROM mt_receipts WHERE player=? AND title=?", k.player().toString(), k.title());
    if (settings) tx.settings(desired.settings());
    for (UUID id : playerIds)
      if (!desired.players().containsKey(id))
        tx.sql("DELETE FROM mt_players WHERE uuid=?", id.toString());
    for (String id : titleIds)
      if (!desired.titles().containsKey(id)) tx.sql("DELETE FROM mt_titles WHERE id=?", id);
    for (UUID id : playerIds)
      tx.rollbackHooks.add(() -> assets.playerPresent(id, current.players().containsKey(id)));
    for (Delta d : entry.changes()) {
      String[] p = d.key().split("\\|");
      if (p[0].equals("H")) {
        if (d.before().getAsBoolean())
          tx.sql("INSERT IGNORE INTO mt_audit_hidden VALUES(?)", Long.parseLong(p[1]));
        else tx.sql("DELETE FROM mt_audit_hidden WHERE audit_id=?", Long.parseLong(p[1]));
      } else if (p[0].equals("V")) {
        tx.rollbackHooks.add(assets.swap(UUID.fromString(p[1]), d.after(), d.before()));
      }
    }
    for (UUID id : playerIds) assets.playerPresent(id, desired.players().containsKey(id));
  }

  public static Export export(Database.Tx tx) throws SQLException {
    List<Entry> entries = new ArrayList<>();
    Map<String, Long> versions = new TreeMap<>();
    List<Long> hidden = new ArrayList<>();
    try (var s = tx.connection.createStatement();
        var r =
            s.executeQuery("SELECT id,audit_id,payload,reverted_by FROM mt_changes ORDER BY id")) {
      while (r.next()) entries.add(entry(r));
    }
    try (var s = tx.connection.createStatement();
        var r = s.executeQuery("SELECT cell_key,version FROM mt_cell_versions")) {
      while (r.next()) versions.put(r.getString(1), r.getLong(2));
    }
    try (var s = tx.connection.createStatement();
        var r = s.executeQuery("SELECT audit_id FROM mt_audit_hidden")) {
      while (r.next()) hidden.add(r.getLong(1));
    }
    return new Export(entries, versions, hidden);
  }

  public static void restore(Database.Tx tx, Export data) throws SQLException {
    if (data == null) return;
    for (var v : data.versions().entrySet())
      tx.sql("INSERT INTO mt_cell_versions VALUES(?,?)", v.getKey(), v.getValue());
    for (Entry e : data.entries())
      tx.sql(
          "INSERT INTO mt_changes(id,audit_id,payload,reverted_by) VALUES(?,?,?,?)",
          e.id(),
          e.audit(),
          JSON.toJson(e),
          e.revertedBy());
    for (long id : data.hidden()) tx.sql("INSERT INTO mt_audit_hidden VALUES(?)", id);
  }

  private ChangeJournal() {}
}
