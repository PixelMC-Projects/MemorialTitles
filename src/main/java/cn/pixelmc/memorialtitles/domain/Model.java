package cn.pixelmc.memorialtitles.domain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;

/** Platform-independent data. All offsets are visible Unicode code-point offsets, zero-based. */
public final class Model {
  public static final Gson JSON =
      new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

  public record Segment(
      String text, int color, boolean bold, boolean italic, boolean underline, boolean strike) {
    public Segment {
      Objects.requireNonNull(text);
      if (color < 0 || color > 0xffffff) throw new IllegalArgumentException("颜色超出 RGB 范围");
    }
  }

  public record Gradient(List<Integer> positions, int from, int to) {
    public Gradient {
      positions = List.copyOf(positions);
      if (from < 0 || from > 0xffffff || to < 0 || to > 0xffffff)
        throw new IllegalArgumentException("渐变颜色无效");
    }
  }

  public record Glyph(
      String text, int color, boolean bold, boolean italic, boolean underline, boolean strike) {}

  public record RichText(List<Segment> segments, List<Gradient> gradients) {
    public RichText {
      segments = List.copyOf(segments);
      gradients = List.copyOf(gradients);
      int size = segments.stream().mapToInt(s -> s.text.codePointCount(0, s.text.length())).sum();
      if (size < 1 || size > 80 || segments.size() > 32 || gradients.size() > 16)
        throw new IllegalArgumentException("称号须为 1–80 个字符，最多 32 段/16 组渐变");
      Set<Integer> used = new HashSet<>();
      for (Gradient g : gradients) {
        if (g.positions.isEmpty()) throw new IllegalArgumentException("渐变没有选中文字");
        int last = -1;
        for (int pos : g.positions) {
          if (pos < 0 || pos >= size || pos <= last || !used.add(pos))
            throw new IllegalArgumentException("渐变选区无效或重叠");
          last = pos;
        }
      }
      if (segments.stream()
          .anyMatch(
              s -> s.text.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7)))
        throw new IllegalArgumentException("请使用结构化样式，不可含控制字符或 §");
    }

    public static RichText plain(String text) {
      return new RichText(
          List.of(new Segment(text, 0xffffff, false, false, false, false)), List.of());
    }

    public String plain() {
      return segments.stream().map(Segment::text).reduce("", String::concat);
    }

    public List<Glyph> glyphs() {
      List<Glyph> out = new ArrayList<>();
      for (Segment s : segments)
        s.text
            .codePoints()
            .forEach(
                cp ->
                    out.add(
                        new Glyph(
                            new String(Character.toChars(cp)),
                            s.color,
                            s.bold,
                            s.italic,
                            s.underline,
                            s.strike)));
      for (Gradient g : gradients)
        for (int n = 0; n < g.positions.size(); n++) {
          int i = g.positions.get(n);
          Glyph old = out.get(i);
          double t = g.positions.size() == 1 ? 0 : (double) n / (g.positions.size() - 1);
          int color = 0;
          for (int shift : new int[] {16, 8, 0})
            color |=
                ((int)
                        Math.round(
                            ((g.from >> shift) & 255) * (1 - t) + ((g.to >> shift) & 255) * t))
                    << shift;
          out.set(i, new Glyph(old.text, color, old.bold, old.italic, old.underline, old.strike));
        }
      return List.copyOf(out);
    }
  }

  public record Title(
      String id,
      RichText name,
      String description,
      String source,
      String acquisition,
      String category,
      int order,
      int icon,
      boolean visible,
      boolean issuable,
      long revision,
      long createdAt) {
    public Title(
        String id,
        RichText name,
        String description,
        String source,
        String acquisition,
        String category,
        int order,
        int icon,
        boolean visible,
        boolean issuable,
        long revision) {
      this(
          id,
          name,
          description,
          source,
          acquisition,
          category,
          order,
          icon,
          visible,
          issuable,
          revision,
          0);
    }

    public Title {
      if (id == null || !id.matches("[a-z0-9][a-z0-9_.-]{0,63}"))
        throw new IllegalArgumentException("ID 仅允许小写字母、数字、_.-，最多64字");
      Objects.requireNonNull(name);
      check(description, 2048);
      check(source, 256);
      check(acquisition, 512);
      check(category, 128);
      if (icon < 0) throw new IllegalArgumentException("图标编号不可为负");
      if (createdAt < 0) throw new IllegalArgumentException("创建日期无效");
    }

    public Title revised(long value) {
      return new Title(
          id,
          name,
          description,
          source,
          acquisition,
          category,
          order,
          icon,
          visible,
          issuable,
          value,
          createdAt);
    }

    public Title created(long value) {
      return new Title(
          id,
          name,
          description,
          source,
          acquisition,
          category,
          order,
          icon,
          visible,
          issuable,
          revision,
          value);
    }
  }

  public record Profile(UUID id, String name, long joinedAt, long lastSeen) {
    public Profile {
      Objects.requireNonNull(id);
      check(name, 64);
    }
  }

  public record Key(UUID player, String title) {}

  public record Owned(
      UUID player,
      String title,
      long acquiredAt,
      boolean publicCollection,
      boolean publicDisplay) {}

  public record Receipt(UUID player, String title, long claimedAt) {}

  public record Audit(
      long id, long at, String actor, String action, String target, String title, String reason) {}

  public record Settings(
      String reward,
      boolean head,
      boolean chat,
      boolean tab,
      float headScale,
      float headOffset,
      String chatPrefix,
      int retentionDays,
      String packName,
      String packStartedOn) {
    public Settings(
        String reward,
        boolean head,
        boolean chat,
        boolean tab,
        float headScale,
        float headOffset,
        String chatPrefix,
        int retentionDays) {
      this(reward, head, chat, tab, headScale, headOffset, chatPrefix, retentionDays, "", "");
    }

    public Settings {
      reward = reward == null ? "" : reward;
      packName = packName == null ? "" : packName;
      packStartedOn = packStartedOn == null ? "" : packStartedOn;
      check(packName, 256);
      if (!packStartedOn.isEmpty()) {
        if (!packStartedOn.matches("\\d{4}-\\d{2}-\\d{2}"))
          throw new IllegalArgumentException("开档日期须为年-月-日");
        try {
          java.time.LocalDate.parse(packStartedOn);
        } catch (java.time.DateTimeException e) {
          throw new IllegalArgumentException("开档日期无效");
        }
      }
      check(chatPrefix, 128);
      if (!Float.isFinite(headScale)
          || headScale < .25
          || headScale > 2
          || !Float.isFinite(headOffset)
          || headOffset < 0
          || headOffset > 2
          || retentionDays < 1
          || retentionDays > 3650) throw new IllegalArgumentException("显示比例/间距/保留天数无效");
    }

    public static Settings defaults() {
      return new Settings("", true, true, true, 1f, .3f, "[{title}] ", 30);
    }
  }

  public record PlayerNotice(
      long id, UUID player, String title, RichText name, boolean granted, long createdAt) {}

  public enum DisplayPosition { HEAD, CHAT, TAB }

  public record DisplayPreferences(boolean head, boolean chat, boolean tab) {
    public static DisplayPreferences defaults() { return new DisplayPreferences(true, true, true); }
    public boolean enabled(DisplayPosition position) {
      return switch (position) { case HEAD -> head; case CHAT -> chat; case TAB -> tab; };
    }
    public DisplayPreferences with(DisplayPosition position, boolean value) {
      return switch (position) {
        case HEAD -> new DisplayPreferences(value, chat, tab);
        case CHAT -> new DisplayPreferences(head, value, tab);
        case TAB -> new DisplayPreferences(head, chat, value);
      };
    }
  }

  public record Snapshot(
      Map<String, Title> titles,
      Map<UUID, Profile> players,
      Map<Key, Owned> owned,
      Map<UUID, String> equipped,
      Set<UUID> admins,
      Map<Key, Receipt> receipts,
      Settings settings,
      Map<UUID, DisplayPreferences> displayPreferences) {
    public Snapshot(
        Map<String, Title> titles, Map<UUID, Profile> players, Map<Key, Owned> owned,
        Map<UUID, String> equipped, Set<UUID> admins, Map<Key, Receipt> receipts, Settings settings) {
      this(titles, players, owned, equipped, admins, receipts, settings, Map.of());
    }

    public Snapshot {
      titles = Map.copyOf(titles);
      players = Map.copyOf(players);
      owned = Map.copyOf(owned);
      equipped = Map.copyOf(equipped);
      admins = Set.copyOf(admins);
      receipts = Map.copyOf(receipts);
      Objects.requireNonNull(settings);
      displayPreferences = Map.copyOf(displayPreferences);
    }

    public static Snapshot empty() {
      return new Snapshot(
          Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Map.of(), Settings.defaults());
    }

    public Owned ownership(UUID p, String t) {
      return owned.get(new Key(p, t));
    }

    public Title worn(UUID player) {
      String id = equipped.get(player);
      Owned item = ownership(player, id);
      return item != null && item.publicDisplay ? titles.get(id) : null;
    }

    public DisplayPreferences displayPreferences(UUID player) {
      return displayPreferences.getOrDefault(player, DisplayPreferences.defaults());
    }

    public boolean positionAllowed(UUID player, DisplayPosition position) {
      boolean global = switch (position) {
        case HEAD -> settings.head;
        case CHAT -> settings.chat;
        case TAB -> settings.tab;
      };
      return global && displayPreferences(player).enabled(position);
    }

    public Title displayed(UUID player, DisplayPosition position) {
      return positionAllowed(player, position) ? worn(player) : null;
    }

    public List<Owned> collection(UUID target, UUID viewer, boolean admin) {
      return owned.values().stream()
          .filter(
              o ->
                  o.player.equals(target) && (admin || target.equals(viewer) || o.publicCollection))
          .sorted(Comparator.comparing(Owned::title))
          .toList();
    }

    public List<Title> catalogue(UUID viewer) {
      return titles.values().stream()
          .filter(t -> t.visible || ownership(viewer, t.id) != null)
          .sorted(Comparator.comparingInt(Title::order).thenComparing(Title::id))
          .toList();
    }
  }

  public static void check(String s, int max) {
    if (s == null || s.length() > max || s.indexOf('\u0000') >= 0)
      throw new IllegalArgumentException("文字为空或超过长度限制 " + max);
  }

  private Model() {}
}
