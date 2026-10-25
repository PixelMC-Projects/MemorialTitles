package cn.pixelmc.memorialtitles.service;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.ChangeJournal;
import cn.pixelmc.memorialtitles.persistence.Database;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.*;

public final class Backup {
  public record Equipped(UUID player, String title) {}
  public record PlayerDisplay(UUID player, DisplayPreferences preferences) {}

  public record Archive(
      int format,
      long createdAt,
      List<Title> titles,
      List<Profile> players,
      List<Owned> owned,
      List<Equipped> equipped,
      List<UUID> admins,
      List<Receipt> receipts,
      Settings settings,
      List<Audit> audit,
      ChangeJournal.Export journal,
      List<PlayerDisplay> displayPreferences) {
    public Archive {
      displayPreferences = displayPreferences == null ? List.of() : List.copyOf(displayPreferences);
    }
    public Archive(int format, long createdAt, List<Title> titles, List<Profile> players,
        List<Owned> owned, List<Equipped> equipped, List<UUID> admins, List<Receipt> receipts,
        Settings settings, List<Audit> audit, ChangeJournal.Export journal) {
      this(format, createdAt, titles, players, owned, equipped, admins, receipts, settings, audit,
          journal, List.of());
    }
  }

  public static Archive capture(Database.Tx tx) throws Exception {
    Snapshot s = tx.snapshot();
    List<Audit> history = tx.history(1000000, true);
    if (history.size() == 1000000) throw new IOException("审计超过导出安全上限，请使用数据库原生备份，禁止静默截断");
    return new Archive(
        3,
        System.currentTimeMillis(),
        List.copyOf(s.titles().values()),
        List.copyOf(s.players().values()),
        List.copyOf(s.owned().values()),
        s.equipped().entrySet().stream().map(e -> new Equipped(e.getKey(), e.getValue())).toList(),
        List.copyOf(s.admins()),
        List.copyOf(s.receipts().values()),
        s.settings(),
        history,
        ChangeJournal.export(tx),
        s.displayPreferences().entrySet().stream().map(e -> new PlayerDisplay(e.getKey(), e.getValue())).toList());
  }

  public static void validate(Archive a) {
    if (a == null || a.format < 1 || a.format > 3)
      throw new IllegalArgumentException("不支持的导出格式");
    Objects.requireNonNull(a.settings);
    Set<String> ts = new HashSet<>();
    Set<UUID> ps = new HashSet<>();
    Set<Key> os = new HashSet<>();
    Set<Key> rs = new HashSet<>();
    Set<UUID> es = new HashSet<>();
    Set<Long> audits = new HashSet<>();
    for (Title t : a.titles) if (!ts.add(t.id())) throw new IllegalArgumentException("重复称号");
    for (Profile p : a.players) if (!ps.add(p.id())) throw new IllegalArgumentException("重复玩家");
    for (Owned o : a.owned)
      if (!ps.contains(o.player())
          || !ts.contains(o.title())
          || !os.add(new Key(o.player(), o.title()))
          || o.acquiredAt() < 0) throw new IllegalArgumentException("持有记录无效");
    for (Receipt r : a.receipts)
      if (!ps.contains(r.player())
          || !ts.contains(r.title())
          || !rs.add(new Key(r.player(), r.title()))) throw new IllegalArgumentException("领奖记录无效");
    for (Equipped e : a.equipped)
      if (!os.contains(new Key(e.player, e.title)) || !es.add(e.player))
        throw new IllegalArgumentException("佩戴记录无效");
    if (new HashSet<>(a.admins).size() != a.admins.size() || !ps.containsAll(a.admins))
      throw new IllegalArgumentException("授权记录无效");
    if (!a.settings.reward().isEmpty() && !ts.contains(a.settings.reward()))
      throw new IllegalArgumentException("奖励称号不存在");
    for (Audit h : a.audit) {
      if (h.id() < 1 || !audits.add(h.id())) throw new IllegalArgumentException("审计ID无效");
      check(h.actor(), 128);
      check(h.action(), 64);
      check(h.target(), 128);
      check(h.title(), 64);
      check(h.reason(), 2048);
    }
    Set<UUID> displays = new HashSet<>();
    for (PlayerDisplay entry : a.displayPreferences)
      if (!ps.contains(entry.player()) || entry.preferences() == null || !displays.add(entry.player()))
        throw new IllegalArgumentException("玩家显示位置记录无效");
    if (a.format < 3 && !a.displayPreferences.isEmpty())
      throw new IllegalArgumentException("旧格式不支持玩家显示位置");
    if (a.format >= 2) {
      Objects.requireNonNull(a.journal, "缺少撤回记录");
      Set<Long> ids = new HashSet<>();
      for (var e : a.journal.entries()) {
        if (e.id() < 1
            || !ids.add(e.id())
            || !audits.contains(e.audit())
            || e.changes() == null
            || e.guards() == null
            || e.changes().isEmpty()) throw new IllegalArgumentException("撤回记录无效");
        for (var d : e.changes())
          if (d.key() == null
              || d.key().length() > 256
              || !Set.of("T", "P", "O", "E", "R", "S", "H", "V", "D").contains(d.key().split("\\|")[0])
              || d.before() == null
              || d.after() == null) throw new IllegalArgumentException("撤回改动无效");
      }
      if (!audits.containsAll(a.journal.hidden())) throw new IllegalArgumentException("隐藏历史无效");
    }
  }

  public static String preview(Snapshot s, Archive a) {
    long conflicts =
        a.titles.stream()
            .filter(t -> s.titles().containsKey(t.id()) && !s.titles().get(t.id()).equals(t))
            .count();
    return "格式"
        + a.format
        + "；称号 "
        + a.titles.size()
        + "，玩家 "
        + a.players.size()
        + "，持有 "
        + a.owned.size()
        + "，审计 "
        + a.audit.size()
        + "；同ID定义冲突 "
        + conflicts
        + "。完整恢复要求空库，包含 "
        + a.admins.size()
        + " 条管理授权，必须由控制台明确执行。";
  }

  public static void restore(Database.Tx tx, Archive a) throws Exception {
    validate(a);
    tx.skipJournal = true;
    for (Title t : a.titles) tx.title(t);
    for (Profile p : a.players)
      tx.sql(
          "INSERT INTO mt_players VALUES(?,?,?,?)",
          p.id().toString(),
          p.name(),
          p.joinedAt(),
          p.lastSeen());
    for (PlayerDisplay entry : a.displayPreferences) tx.displayPreferences(entry.player(), entry.preferences());
    for (Owned o : a.owned)
      tx.sql(
          "INSERT INTO mt_owned VALUES(?,?,?,?,?)",
          o.player().toString(),
          o.title(),
          o.acquiredAt(),
          o.publicCollection(),
          o.publicDisplay());
    for (Equipped e : a.equipped)
      tx.sql("INSERT INTO mt_equipped VALUES(?,?)", e.player.toString(), e.title);
    for (Receipt r : a.receipts)
      tx.sql(
          "INSERT INTO mt_receipts VALUES(?,?,?)", r.player().toString(), r.title(), r.claimedAt());
    for (UUID id : a.admins) tx.sql("INSERT INTO mt_admins VALUES(?)", id.toString());
    for (Audit h : a.audit)
      tx.sql(
          "INSERT INTO mt_audit(id,at_ms,actor,action,target,title,reason) VALUES(?,?,?,?,?,?,?)",
          h.id(),
          h.at(),
          h.actor(),
          h.action(),
          h.target(),
          h.title(),
          h.reason());
    tx.settings(a.settings);
    ChangeJournal.restore(tx, a.journal);
  }

  public static Path write(Path folder, Archive archive) throws Exception {
    validate(archive);
    Files.createDirectories(folder);
    String stamp =
        DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now());
    Path
        file =
            folder.resolve(
                "memorialtitles-"
                    + stamp
                    + "-"
                    + UUID.randomUUID().toString().substring(0, 8)
                    + ".json.gz"),
        tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try (OutputStream raw = Files.newOutputStream(tmp, StandardOpenOption.CREATE_NEW);
        Writer writer = new OutputStreamWriter(new GZIPOutputStream(raw), StandardCharsets.UTF_8)) {
      JSON.toJson(archive, writer);
    }
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, file);
    }
    Files.writeString(
        file.resolveSibling(file.getFileName() + ".sha256"),
        digest(file) + "  " + file.getFileName() + "\n",
        StandardOpenOption.CREATE_NEW);
    return file;
  }

  public static Archive read(Path file) throws Exception {
    if (Files.size(file) > 64 * 1024 * 1024L) throw new IOException("压缩备份超过64MiB");
    Path hash = file.resolveSibling(file.getFileName() + ".sha256");
    if (!Files.isRegularFile(hash) || !Files.readString(hash).startsWith(digest(file) + "  "))
      throw new IOException("备份SHA-256缺失或不匹配");
    byte[] data;
    try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
      data = input.readNBytes(128 * 1024 * 1024 + 1);
    }
    if (data.length > 128 * 1024 * 1024) throw new IOException("解压数据超过128MiB");
    Archive result = JSON.fromJson(new String(data, StandardCharsets.UTF_8), Archive.class);
    validate(result);
    return result;
  }

  public static String digest(Path path) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    try (InputStream in = Files.newInputStream(path)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) md.update(b, 0, n);
    }
    return HexFormat.of().formatHex(md.digest());
  }

  public static void retain(Path folder, int days) throws IOException {
    long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
    if (!Files.isDirectory(folder)) return;
    try (var files = Files.list(folder)) {
      for (Path file :
          files
              .filter(
                  p ->
                      p.getFileName()
                          .toString()
                          .matches(
                              "memorialtitles-[0-9]{8}-[0-9]{6}-[0-9]{3}-[a-f0-9]{8}\\.json\\.gz"))
              .toList())
        if (Files.getLastModifiedTime(file).toMillis() < cutoff) {
          Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".sha256"));
          Files.delete(file);
        }
    }
  }

  private Backup() {}
}
