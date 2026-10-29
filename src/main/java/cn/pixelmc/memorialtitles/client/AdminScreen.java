package cn.pixelmc.memorialtitles.client;

import static cn.pixelmc.memorialtitles.client.Theme.*;
import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.domain.TextEdits;
import cn.pixelmc.memorialtitles.forge.*;
import com.google.gson.*;
import java.util.*;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;

/** Native, resolution-independent archive workbench. Server remains authoritative. */
public final class AdminScreen extends Screen {
  private String view = "titles",
      tab = "main",
      status = "",
      query = "",
      operationTitle = "",
      reason = "管理员操作",
      textOutput = "";
  private int page, pages = 1, listOffset, segmentIndex, gradientIndex, outputOffset;
  private List<Title> titles = List.of();
  private List<Profile> players = List.of();
  private final Set<UUID> selected = new LinkedHashSet<>();
  private final Map<String, EditBox> fields = new LinkedHashMap<>();
  private final Map<String, String> labels = new LinkedHashMap<>();
  private Title editing;
  private Settings settings = Settings.defaults();
  private boolean visible = true, issuable = true, bold, italic, underline, strike, dirty;
  private List<Segment> segments = new ArrayList<>();
  private List<Gradient> gradients = new ArrayList<>();
  private String gradientPositions = "1", gradientFrom = "ffd166", gradientTo = "ef476f";
  private int x, w, right;
  private float uiScale = 1;

  public AdminScreen() {
    super(Component.literal("纪念称号管理"));
  }

  @Override
  protected void init() {
    uiScale = Math.min(width / 680f, height / 380f);
    width = Math.round(width / uiScale);
    height = Math.round(height / uiScale);
    layout();
    if (titles.isEmpty() && players.isEmpty() && editing == null) fetch();
  }

  private void layout() {
    clearWidgets();
    fields.clear();
    labels.clear();
    x = 128;
    right = width - 198;
    w = right - x - 14;
    String[][] nav = {
      {"titles", "称号档案"},
      {"players", "玩家收藏"},
      {"settings", "展示与奖励"},
      {"history", "操作历史"},
      {"ops", "数据管理"}
    };
    for (int i = 0; i < nav.length; i++) {
      String key = nav[i][0];
      var b = button(12, 104 + i * 34, 96, 28, nav[i][1], Tone.NAV, () -> navigate(key));
      b.selected =
          view.equals(key)
              || (key.equals("titles") && view.equals("edit"))
              || (key.equals("history") && view.equals("text"));
    }
    button(12, height - 46, 96, 24, "返回游戏", Tone.QUIET, this::onClose);
    switch (view) {
      case "titles" -> titleList();
      case "players" -> playerList();
      case "edit" -> editor();
      case "settings" -> settingsForm();
      case "ops" -> operations();
      case "history", "text" -> textView();
    }
  }

  private Theme.Action button(
      int bx, int by, int bw, int bh, String label, Tone tone, Runnable run) {
    return button(bx, by, bw, bh, Component.literal(label), tone, run);
  }

  private Theme.Action button(
      int bx, int by, int bw, int bh, Component label, Tone tone, Runnable run) {
    return addRenderableWidget(
        new Theme.Action(
            bx,
            by,
            bw,
            bh,
            label,
            tone,
            () -> {
              try {
                run.run();
              } catch (Exception e) {
                status = ServerRuntime.safeError(e);
              }
            }));
  }

  private EditBox field(String id, String label, String value, int y, int max) {
    return fieldAt(id, label, value, x + 14, y, w - 28, max);
  }

  private EditBox fieldAt(String id, String label, String value, int bx, int y, int bw, int max) {
    EditBox box = new EditBox(font, bx + 7, y + 6, bw - 14, 13, Component.literal(label));
    box.setBordered(false);
    box.setTextColor(TEXT);
    box.setTextColorUneditable(MUTED);
    box.setMaxLength(max);
    box.setValue(value);
    fields.put(id, box);
    labels.put(id, label);
    addRenderableWidget(box);
    return box;
  }

  private String value(String id, String fallback) {
    return fields.containsKey(id) ? fields.get(id).getValue() : fallback;
  }

  private void navigate(String next) {
    if (view.equals("edit")) capture();
    if (view.equals("players")) {
      operationTitle = value("awardTitle", operationTitle);
      reason = value("reason", reason);
    }
    if (view.equals("settings")) settings = captureSettings();
    if (dirty) {
      status = "有未保存的称号编辑，请保存或放弃修改后切换。";
      return;
    }
    view = next;
    tab = "main";
    page = 0;
    listOffset = 0;
    query = "";
    layout();
    fetch();
  }

  private void fetch() {
    JsonObject j = new JsonObject();
    j.addProperty("page", page);
    j.addProperty("query", query);
    switch (view) {
      case "titles" -> Network.request("adminTitles", j);
      case "players" -> Network.request("adminPlayers", j);
      case "settings" -> Network.request("adminSettingsGet", j);
      case "history" -> Network.request("adminHistory", j);
    }
  }

  private void titleList() {
    fieldAt("query", "搜索名称 / 稳定 ID", query, x + 14, 112, w - 108, 64);
    button(
        x + w - 84,
        112,
        70,
        24,
        "查找",
        Tone.NORMAL,
        () -> {
          query = value("query", "");
          page = 0;
          listOffset = 0;
          fetch();
        });
    int rows = Math.max(1, (height - 229) / 49);
    for (int i = 0; i < rows && listOffset + i < titles.size(); i++) {
      Title t = titles.get(listOffset + i);
      var row =
          button(
              x + 14, 153 + i * 49, w - 28, 44, Text.render(t.name()), Tone.NORMAL, () -> edit(t));
      row.detail =
          t.id() + "  /  " + (t.visible() ? "图鉴公开" : "图鉴隐藏") + (t.issuable() ? "" : "  /  已停发");
    }
    pagination(height - 68);
    button(
        right + 14,
        139,
        156,
        26,
        "+ 创建纪念称号",
        Tone.PRIMARY,
        () ->
            edit(
                new Title(
                    "new_title",
                    RichText.plain("新的纪念"),
                    "",
                    "",
                    "管理员发放",
                    "",
                    0,
                    0,
                    true,
                    true,
                    0)));
  }

  private void pagination(int y) {
    button(
        x + 14,
        y,
        60,
        23,
        "上一页",
        Tone.QUIET,
        () -> {
          page = Math.max(0, page - 1);
          listOffset = 0;
          fetch();
        });
    button(
        x + w - 74,
        y,
        60,
        23,
        "下一页",
        Tone.QUIET,
        () -> {
          page = Math.min(pages - 1, page + 1);
          listOffset = 0;
          fetch();
        });
  }

  private void edit(Title t) {
    editing = t;
    visible = t.visible();
    issuable = t.issuable();
    segments = new ArrayList<>(t.name().segments());
    gradients = new ArrayList<>(t.name().gradients());
    segmentIndex = 0;
    gradientIndex = 0;
    dirty = false;
    view = "edit";
    tab = "main";
    layout();
  }

  private void editor() {
    String[][] tabs = {
      {"main", "基本资料"}, {"meta", "来源 / 图标"}, {"text", "文字样式"}, {"gradient", "渐变选区"}
    };
    int bw = w / 4;
    for (int i = 0; i < tabs.length; i++) {
      String key = tabs[i][0];
      var b =
          button(
              x + i * bw,
              95,
              bw - 1,
              25,
              tabs[i][1],
              Tone.TAB,
              () -> {
                capture();
                tab = key;
                layout();
              });
      b.selected = tab.equals(key);
    }
    switch (tab) {
      case "main" -> {
        field("id", "稳定 ID", editing.id(), 153, 64).setEditable(editing.revision() == 0);
        field(
            "description",
            "称号说明 · 可用 \\n 换行",
            editing.description().replace("\n", "\\n"),
            199,
            2048);
        field("acquisition", "获取方式", editing.acquisition(), 245, 512);
        int half = (w - 32) / 2;
        button(
            x + 14,
            286,
            half,
            24,
            "图鉴可见 · " + (visible ? "开启" : "隐藏"),
            visible ? Tone.NORMAL : Tone.QUIET,
            () -> {
              capture();
              visible = !visible;
              dirty = true;
              layout();
            });
        button(
            x + 18 + half,
            286,
            half,
            24,
            "继续发放 · " + (issuable ? "允许" : "停发"),
            issuable ? Tone.NORMAL : Tone.QUIET,
            () -> {
              capture();
              issuable = !issuable;
              dirty = true;
              layout();
            });
      }
      case "meta" -> {
        field("source", "整合包 / 活动来源", editing.source(), 153, 256);
        field("category", "分类", editing.category(), 199, 128);
        int half = (w - 32) / 2;
        fieldAt("order", "排序值", Integer.toString(editing.order()), x + 14, 245, half, 12);
        fieldAt("icon", "图标编号", Integer.toString(editing.icon()), x + 18 + half, 245, half, 12);
      }
      case "text" -> segmentForm();
      case "gradient" -> gradientForm();
    }
    button(x, height - 39, 100, 27, "保存并同步", Tone.PRIMARY, this::save);
    button(
        x + 108,
        height - 39,
        82,
        27,
        "放弃修改",
        Tone.QUIET,
        () -> {
          dirty = false;
          view = "titles";
          editing = null;
          layout();
          fetch();
        });
  }

  private void segmentForm() {
    segmentIndex = Math.min(segmentIndex, segments.size() - 1);
    Segment s = segments.get(segmentIndex);
    bold = s.bold();
    italic = s.italic();
    underline = s.underline();
    strike = s.strike();
    int bw = (w - 34) / 4;
    button(x + 14, 139, bw, 22, "上一段", Tone.QUIET, () -> moveSegment(-1));
    button(x + 18 + bw, 139, bw, 22, "下一段", Tone.QUIET, () -> moveSegment(1));
    button(
        x + 22 + bw * 2,
        139,
        bw,
        22,
        "+ 新增",
        Tone.NORMAL,
        () -> {
          capture();
          if (segments.size() >= 32) throw new IllegalArgumentException("最多32段");
          segments.add(new Segment("新", 0xffffff, false, false, false, false));
          segmentIndex = segments.size() - 1;
          dirty = true;
          layout();
        });
    button(
        x + 26 + bw * 3,
        139,
        bw,
        22,
        "删除",
        Tone.DANGER,
        () -> {
          capture();
          if (segments.size() == 1) throw new IllegalArgumentException("至少保留一段");
          gradients =
              TextEdits.afterEdit(
                  gradients, segmentStart(), codepoints(segments.get(segmentIndex).text()), 0);
          segments.remove(segmentIndex);
          segmentIndex = Math.min(segmentIndex, segments.size() - 1);
          dirty = true;
          layout();
        });
    field(
        "segment",
        "第 " + (segmentIndex + 1) + " / " + segments.size() + " 段文字",
        s.text(),
        185,
        160);
    fieldAt("color", "文字颜色 HEX", String.format("%06x", s.color()), x + 14, 231, w - 123, 7);
    button(
        x + w - 99,
        231,
        85,
        24,
        "打开选色器",
        Tone.NORMAL,
        () -> {
          capture();
          Segment old = segments.get(segmentIndex);
          minecraft.setScreen(
              new ColorPickerScreen(
                  this,
                  old.color(),
                  color -> {
                    segments.set(
                        segmentIndex,
                        new Segment(
                            old.text(),
                            color,
                            old.bold(),
                            old.italic(),
                            old.underline(),
                            old.strike()));
                    updateDraft();
                    dirty = true;
                  }));
        });
    boolean[] on = {bold, italic, underline, strike};
    String[] names = {"粗体", "斜体", "下划线", "删除线"};
    int fw = (w - 40) / 4;
    for (int i = 0; i < 4; i++) {
      int n = i;
      var b =
          button(
              x + 14 + i * (fw + 4),
              268,
              fw,
              23,
              names[i],
              Tone.TAB,
              () -> {
                capture();
                Segment cur = segments.get(segmentIndex);
                segments.set(
                    segmentIndex,
                    new Segment(
                        cur.text(),
                        cur.color(),
                        n == 0 ? !cur.bold() : cur.bold(),
                        n == 1 ? !cur.italic() : cur.italic(),
                        n == 2 ? !cur.underline() : cur.underline(),
                        n == 3 ? !cur.strike() : cur.strike()));
                dirty = true;
                layout();
              });
      b.selected = on[i];
    }
    int[] colors = {0xffffff, 0xe4ba76, 0xed9292, 0x75d6c2, 0x71b8ea, 0xc4a1ef, 0xf29f67, 0x94adbc};
    int sw = (w - 35) / 8;
    for (int i = 0; i < colors.length; i++) {
      int c = colors[i];
      button(
          x + 14 + i * (sw + 1),
          300,
          sw,
          17,
          Component.literal("■").withStyle(Style.EMPTY.withColor(c)),
          Tone.QUIET,
          () -> fields.get("color").setValue(String.format("%06x", c)));
    }
  }

  private void moveSegment(int d) {
    capture();
    segmentIndex = Math.floorMod(segmentIndex + d, segments.size());
    layout();
  }

  private void gradientForm() {
    if (!gradients.isEmpty() && gradientIndex < gradients.size()) {
      Gradient g = gradients.get(gradientIndex);
      gradientPositions = positions(g.positions());
      gradientFrom = String.format("%06x", g.from());
      gradientTo = String.format("%06x", g.to());
    }
    field("positions", "参与文字 · 序号从 1 开始", gradientPositions, 153, 512);
    int half = (w - 32) / 2;
    fieldAt("from", "起始颜色 HEX", gradientFrom, x + 14, 206, half, 7);
    fieldAt("to", "结束颜色 HEX", gradientTo, x + 18 + half, 206, half, 7);
    button(x + 14, 237, half, 22, "选择起色", Tone.NORMAL, () -> pickGradient(true));
    button(x + 18 + half, 237, half, 22, "选择末色", Tone.NORMAL, () -> pickGradient(false));
    int bw = (w - 40) / 3;
    button(x + 14, 271, bw, 23, "应用选区", Tone.NORMAL, this::applyGradient);
    button(
        x + 18 + bw,
        271,
        bw,
        23,
        "+ 新建组",
        Tone.QUIET,
        () -> {
          gradientIndex = gradients.size();
          gradientPositions = "1";
          gradientFrom = "e4ba76";
          gradientTo = "75d6c2";
          layout();
        });
    button(
        x + 22 + bw * 2,
        271,
        bw,
        23,
        "删除该组",
        Tone.DANGER,
        () -> {
          if (gradientIndex < gradients.size()) gradients.remove(gradientIndex);
          gradientIndex = 0;
          dirty = true;
          layout();
        });
    button(
        x + 14,
        302,
        59,
        20,
        "上一组",
        Tone.QUIET,
        () -> {
          if (!gradients.isEmpty()) {
            gradientIndex = Math.floorMod(gradientIndex - 1, gradients.size());
            layout();
          }
        });
    button(
        x + w - 73,
        302,
        59,
        20,
        "下一组",
        Tone.QUIET,
        () -> {
          if (!gradients.isEmpty()) {
            gradientIndex = (gradientIndex + 1) % gradients.size();
            layout();
          }
        });
  }

  private void pickGradient(boolean from) {
    applyGradient();
    Gradient old = gradients.get(gradientIndex);
    minecraft.setScreen(
        new ColorPickerScreen(
            this,
            from ? old.from() : old.to(),
            color -> {
              gradients.set(
                  gradientIndex,
                  new Gradient(
                      old.positions(), from ? color : old.from(), from ? old.to() : color));
              dirty = true;
            }));
  }

  private static int hex(String s) {
    return Integer.parseInt(s.trim().replace("#", ""), 16);
  }

  private void updateDraft() {
    Title t = editing;
    editing =
        new Title(
            t.id(),
            new RichText(segments, gradients),
            t.description(),
            t.source(),
            t.acquisition(),
            t.category(),
            t.order(),
            t.icon(),
            visible,
            issuable,
            t.revision());
  }

  private void capture() {
    if (editing == null || !view.equals("edit")) return;
    if (tab.equals("text")) {
      Segment s = segments.get(segmentIndex);
      gradients =
          TextEdits.afterEdit(
              gradients,
              segmentStart(),
              codepoints(s.text()),
              codepoints(value("segment", s.text())));
      segments.set(
          segmentIndex,
          new Segment(
              value("segment", s.text()),
              hex(value("color", String.format("%06x", s.color()))),
              bold,
              italic,
              underline,
              strike));
    }
    Title old = editing;
    editing =
        new Title(
            value("id", old.id()),
            new RichText(segments, gradients),
            value("description", old.description().replace("\n", "\\n")).replace("\\n", "\n"),
            value("source", old.source()),
            value("acquisition", old.acquisition()),
            value("category", old.category()),
            Integer.parseInt(value("order", Integer.toString(old.order()))),
            Integer.parseInt(value("icon", Integer.toString(old.icon()))),
            visible,
            issuable,
            old.revision());
    dirty = dirty || !old.equals(editing);
  }

  private Gradient gradientFromFields() {
    List<Integer> ps = new ArrayList<>();
    for (String part : value("positions", "1").split(",")) {
      String[] end = part.trim().split("-");
      int a = Integer.parseInt(end[0]), b = end.length > 1 ? Integer.parseInt(end[1]) : a;
      if (b - a > 80 || a < 1 || b < a) throw new IllegalArgumentException("选区无效，例：1-3,5");
      for (int n = a; n <= b; n++) ps.add(n - 1);
    }
    return new Gradient(
        ps.stream().distinct().sorted().toList(),
        hex(value("from", "ffffff")),
        hex(value("to", "ffffff")));
  }

  private static String positions(List<Integer> list) {
    return list.stream()
        .map(i -> Integer.toString(i + 1))
        .reduce((a, b) -> a + "," + b)
        .orElse("1");
  }

  private void applyGradient() {
    Gradient g = gradientFromFields();
    List<Gradient> next = new ArrayList<>(gradients);
    if (gradientIndex < next.size()) next.set(gradientIndex, g);
    else next.add(g);
    new RichText(segments, next);
    gradients = next;
    dirty = true;
    capture();
    status = "渐变已写入草稿；保存后同步给持有者。";
  }

  private RichText preview() {
    List<Segment> ss = new ArrayList<>(segments);
    List<Gradient> gs = new ArrayList<>(gradients);
    if (tab.equals("text") && fields.containsKey("segment")) {
      Segment s = ss.get(segmentIndex);
      gs =
          TextEdits.afterEdit(
              gs, segmentStart(), codepoints(s.text()), codepoints(value("segment", s.text())));
      ss.set(
          segmentIndex,
          new Segment(
              value("segment", s.text()),
              hex(value("color", "ffffff")),
              bold,
              italic,
              underline,
              strike));
    }
    if (tab.equals("gradient") && fields.containsKey("positions")) {
      Gradient g = gradientFromFields();
      if (gradientIndex < gs.size()) gs.set(gradientIndex, g);
      else gs.add(g);
    }
    return new RichText(ss, gs);
  }

  private static int codepoints(String text) {
    return text.codePointCount(0, text.length());
  }

  private int segmentStart() {
    int start = 0;
    for (int i = 0; i < segmentIndex; i++) start += codepoints(segments.get(i).text());
    return start;
  }

  private void save() {
    if (tab.equals("gradient")) applyGradient();
    capture();
    JsonObject j = new JsonObject();
    j.add("title", JSON.toJsonTree(editing));
    Network.request("adminSave", j);
    status = "正在保存，等待数据库确认…";
  }

  private void playerList() {
    fieldAt("query", "搜索已登记玩家", query, x + 14, 112, w - 108, 64);
    button(
        x + w - 84,
        112,
        70,
        24,
        "查找",
        Tone.NORMAL,
        () -> {
          query = value("query", "");
          page = 0;
          listOffset = 0;
          fetch();
        });
    int count = Math.max(1, (height - 315) / 36);
    for (int i = 0; i < count && listOffset + i < players.size(); i++) {
      Profile p = players.get(listOffset + i);
      var b =
          button(
              x + 14,
              149 + i * 36,
              w - 91,
              31,
              (selected.contains(p.id()) ? "■ " : "□ ") + p.name(),
              Tone.NORMAL,
              () -> {
                capturePlayer();
                if (!selected.remove(p.id())) selected.add(p.id());
                layout();
              });
      b.selected = selected.contains(p.id());
      button(
          x + w - 72,
          149 + i * 36,
          58,
          31,
          "查看档案",
          Tone.QUIET,
          () -> {
            JsonObject j = new JsonObject();
            j.addProperty("player", p.id().toString());
            Network.request("adminCollection", j);
          });
    }
    int y = height - 143, half = (w - 32) / 2;
    fieldAt("awardTitle", "目标称号 ID", operationTitle, x + 14, y, half, 64);
    fieldAt("reason", "操作理由", reason, x + 18 + half, y, half, 512);
    button(x + 14, y + 32, half, 23, "发放给选中玩家", Tone.PRIMARY, () -> award(true));
    button(x + 18 + half, y + 32, half, 23, "撤销选中称号", Tone.DANGER, () -> award(false));
    pagination(height - 65);
    button(
        right + 14,
        139,
        156,
        24,
        "选择本页玩家",
        Tone.NORMAL,
        () -> {
          capturePlayer();
          players.forEach(p -> selected.add(p.id()));
          layout();
        });
    button(
        right + 14,
        171,
        156,
        24,
        "清空选择 · " + selected.size() + " 人",
        Tone.QUIET,
        () -> {
          capturePlayer();
          selected.clear();
          layout();
        });
  }

  private void capturePlayer() {
    operationTitle = value("awardTitle", operationTitle);
    reason = value("reason", reason);
  }

  private void award(boolean grant) {
    capturePlayer();
    JsonObject j = new JsonObject();
    j.addProperty("title", operationTitle);
    j.addProperty("reason", reason);
    j.add("players", JSON.toJsonTree(selected));
    Network.request(grant ? "adminGrant" : "adminRevoke", j);
  }

  private void settingsForm() {
    var first =
        button(
            x,
            95,
            w / 2 - 2,
            25,
            "展示 / 首登奖励",
            Tone.TAB,
            () -> {
              settings = captureSettings();
              tab = "main";
              layout();
            });
    first.selected = tab.equals("main");
    var second =
        button(
            x + w / 2 + 1,
            95,
            w / 2 - 1,
            25,
            "位置 / 备份",
            Tone.TAB,
            () -> {
              settings = captureSettings();
              tab = "extra";
              layout();
            });
    second.selected = tab.equals("extra");
    if (tab.equals("main")) {
      field("reward", "首登奖励称号 ID · 留空关闭", settings.reward(), 153, 64);
      field("prefix", "聊天前缀模板 · {title} 代入称号", settings.chatPrefix(), 206, 128);
      boolean[] on = {settings.head(), settings.chat(), settings.tab()};
      String[] names = {"头顶", "聊天", "TAB"};
      int bw = (w - 36) / 3;
      for (int i = 0; i < 3; i++) {
        int n = i;
        var b =
            button(
                x + 14 + i * (bw + 4),
                258,
                bw,
                35,
                names[i] + " · " + (on[i] ? "开启" : "关闭"),
                Tone.TAB,
                () -> {
                  settings = captureSettings();
                  settings =
                      new Settings(
                          settings.reward(),
                          n == 0 ? !settings.head() : settings.head(),
                          n == 1 ? !settings.chat() : settings.chat(),
                          n == 2 ? !settings.tab() : settings.tab(),
                          settings.headScale(),
                          settings.headOffset(),
                          settings.chatPrefix(),
                          settings.retentionDays());
                  layout();
                });
        b.selected = on[i];
      }
    } else {
      field("scale", "头顶缩放 · 0.25–2", Float.toString(settings.headScale()), 153, 10);
      field("offset", "头顶间距 · 0–2", Float.toString(settings.headOffset()), 206, 10);
      field("retention", "备份保留天数 · 1–3650", Integer.toString(settings.retentionDays()), 259, 6);
    }
    button(
        x,
        height - 39,
        100,
        27,
        "保存设置",
        Tone.PRIMARY,
        () -> {
          settings = captureSettings();
          JsonObject j = new JsonObject();
          j.add("settings", JSON.toJsonTree(settings));
          Network.request("adminSettingsSave", j);
        });
  }

  private Settings captureSettings() {
    return new Settings(
        value("reward", settings.reward()),
        settings.head(),
        settings.chat(),
        settings.tab(),
        Float.parseFloat(value("scale", Float.toString(settings.headScale()))),
        Float.parseFloat(value("offset", Float.toString(settings.headOffset()))),
        value("prefix", settings.chatPrefix()),
        Integer.parseInt(value("retention", Integer.toString(settings.retentionDays()))));
  }

  private void operations() {
    String[][] ops = {
      {"adminHealth", "查看数据库状态", "连接状态、资料数量和最近刷新"},
      {"adminBackup", "完整备份 / 导出", "包含收藏、授权、领奖记录和历史"},
      {"adminExport", "导出称号配置", "生成可编辑的定义配置文件"},
      {"adminReload", "热加载称号配置", "先校验，再整体应用；保留原有收藏"}
    };
    for (int i = 0; i < ops.length; i++) {
      String op = ops[i][0];
      var b =
          button(
              x + 14,
              111 + i * 45,
              w - 28,
              40,
              ops[i][1],
              Tone.NORMAL,
              () -> Network.request(op, new JsonObject()));
      b.detail = ops[i][2];
    }
    fieldAt("backupFile", "备份文件名", "", right + 14, 151, 156, 160);
    button(
        right + 14,
        188,
        156,
        25,
        "校验并预览导入",
        Tone.NORMAL,
        () -> {
          JsonObject j = new JsonObject();
          j.addProperty("file", value("backupFile", ""));
          Network.request("adminImportPreview", j);
        });
  }

  private void textView() {
    button(
        x + 14,
        height - 65,
        70,
        23,
        "向上翻阅",
        Tone.QUIET,
        () -> outputOffset = Math.max(0, outputOffset - 5));
    button(x + w - 84, height - 65, 70, 23, "向下翻阅", Tone.QUIET, () -> outputOffset += 5);
  }

  public void receive(String kind, JsonElement data) {
    switch (kind) {
      case "titles" -> {
        titles = Arrays.asList(JSON.fromJson(data.getAsJsonObject().get("rows"), Title[].class));
        page = data.getAsJsonObject().get("page").getAsInt();
        pages = data.getAsJsonObject().get("pages").getAsInt();
        if (view.equals("titles")) layout();
      }
      case "players" -> {
        players = Arrays.asList(JSON.fromJson(data.getAsJsonObject().get("rows"), Profile[].class));
        page = data.getAsJsonObject().get("page").getAsInt();
        pages = data.getAsJsonObject().get("pages").getAsInt();
        if (view.equals("players")) layout();
      }
      case "settings" -> {
        settings = JSON.fromJson(data, Settings.class);
        if (view.equals("settings")) layout();
      }
      case "ok" -> {
        status = data.getAsString();
        if (view.equals("edit")) {
          dirty = false;
          editing = null;
          view = "titles";
          layout();
          fetch();
        }
      }
      case "error" -> status = data.getAsString();
      case "text" -> {
        textOutput = data.getAsString();
        outputOffset = 0;
        view = "text";
        layout();
      }
    }
  }

  private String heading() {
    return switch (view) {
      case "titles" -> "称号档案";
      case "edit" -> editing.name().plain();
      case "players" -> "玩家收藏";
      case "settings" -> "展示与首登奖励";
      case "ops" -> "数据管理";
      default -> "操作记录";
    };
  }

  private String subtitle() {
    return switch (view) {
      case "titles" -> "每一份纪念，都有一段共同经历。";
      case "edit" -> "编辑档案  /  " + editing.id();
      case "players" -> "选择已登记玩家，批量管理称号归属。";
      case "settings" -> "控制称号的展示出口与新一轮首登奖励。";
      case "ops" -> "检查、备份与恢复长期保存的称号资料。";
      default -> "只有获授权的管理员可查看这些资料。";
    };
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float dt) {
    g.pose().pushPose();
    g.pose().scale(uiScale, uiScale, 1);
    int mouseX = (int) (mx / uiScale), mouseY = (int) (my / uiScale);
    g.fill(0, 0, width, height, BG);
    g.fill(0, 0, 120, height, SIDEBAR);
    g.fill(119, 0, 120, height, LINE);
    g.fill(18, 24, 43, 49, GOLD);
    g.drawCenteredString(
        font, Component.literal("M").withStyle(Style.EMPTY.withBold(true)), 30, 32, BG);
    g.drawString(font, "纪念称号", 51, 25, TEXT, false);
    g.drawString(font, "管理工作台", 51, 40, MUTED, false);
    g.fill(18, 72, 102, 73, LINE);
    g.drawString(font, "MEMORIAL / TITLES", 18, height - 77, MUTED, false);
    g.drawString(font, "永久收藏 · 此刻编辑", 18, height - 63, MUTED, false);
    g.drawString(
        font,
        Component.literal(heading()).withStyle(Style.EMPTY.withBold(true)),
        x,
        28,
        TEXT,
        false);
    g.drawString(font, subtitle(), x, 47, MUTED, false);
    g.fill(x, 72, width - 16, 73, LINE);
    g.drawString(font, view.equals("edit") ? "档案编辑" : "管理 / " + heading(), x, 81, GOLD, false);
    int top = (view.equals("edit") || view.equals("settings")) ? 130 : 96;
    panel(g, x, top, w, height - top - 45);
    panel(g, right, 96, 182, height - 141);
    drawSide(g);
    for (var e : fields.entrySet()) {
      EditBox b = e.getValue();
      int bx = b.getX() - 7, by = b.getY() - 6, bw = b.getWidth() + 14;
      g.fill(bx, by, bx + bw, by + 25, BG);
      border(g, bx, by, bw, 25, b.isFocused() ? MINT : LINE);
      g.drawString(font, labels.get(e.getKey()), bx, by - 12, MUTED, false);
    }
    if (view.equals("titles") || view.equals("players")) {
      g.drawCenteredString(font, (page + 1) + " / " + pages, x + w / 2, height - 60, MUTED);
      if ((view.equals("titles") ? titles : players).isEmpty()) {
        g.drawCenteredString(font, "这里还没有匹配的记录", x + w / 2, 186, TEXT);
        g.drawCenteredString(font, "试试其他关键词，或创建一份新纪念。", x + w / 2, 204, MUTED);
      }
    }
    if (view.equals("edit") && tab.equals("gradient")) {
      g.drawString(font, "例如 1-3,5；也可点击右侧头顶预览选字。", x + 14, 185, MUTED, false);
      g.drawCenteredString(font, "第 " + (gradientIndex + 1) + " 组", x + w / 2, 308, MUTED);
    }
    if (view.equals("text") || view.equals("history")) {
      var lines =
          font.split(Component.literal(textOutput.isEmpty() ? "正在读取操作记录…" : textOutput), w - 28);
      outputOffset = Math.min(outputOffset, Math.max(0, lines.size() - 1));
      for (int i = 0, max = Math.max(1, (height - 190) / 12);
          i < max && outputOffset + i < lines.size();
          i++) g.drawString(font, lines.get(outputOffset + i), x + 14, 112 + i * 12, TEXT, false);
    }
    if (!status.isEmpty()) {
      int sx = view.equals("edit") ? x + 202 : x;
      g.drawString(
          font,
          font.plainSubstrByWidth(status, width - sx - 16),
          sx,
          height - 23,
          status.contains("失败") || status.contains("无效") ? RED : GOLD,
          false);
    }
    super.render(g, mouseX, mouseY, dt);
    g.pose().popPose();
  }

  private void drawSide(GuiGraphics g) {
    g.drawString(font, view.equals("edit") ? "实时预览" : "工作提示", right + 14, 111, GOLD, false);
    g.fill(right + 14, 128, right + 168, 129, LINE);
    if (view.equals("edit")) {
      try {
        RichText rich = preview();
        Component name = Text.render(rich);
        String player =
            minecraft.player == null ? "玩家名" : minecraft.player.getGameProfile().getName();
        g.fill(right + 10, 140, right + 172, 210, BG);
        g.drawCenteredString(font, "头顶称号", right + 91, 149, MUTED);
        drawFit(g, name, right + 91, 170, 146);
        g.drawCenteredString(font, player, right + 91, 189, TEXT);
        g.drawString(font, "聊天 · 名字左侧", right + 14, 225, MUTED, false);
        Component line = Component.literal("[").append(name).append("] <" + player + "> 大家好");
        var lines = font.split(line, 154);
        for (int i = 0; i < Math.min(2, lines.size()); i++)
          g.drawString(font, lines.get(i), right + 14, 242 + i * 12, TEXT, false);
        g.drawString(font, "TAB · 名字左侧", right + 14, 276, MUTED, false);
        drawFit(g, Component.literal("[").append(name).append("] " + player), right + 91, 294, 154);
      } catch (Exception e) {
        drawParagraph(g, "预览暂不可用\n" + ServerRuntime.safeError(e), right + 14, 148, 154, RED);
      }
    } else {
      int y =
          switch (view) {
            case "titles" -> 187;
            case "players" -> 219;
            case "ops" -> 239;
            default -> 145;
          };
      String help =
          switch (view) {
            case "titles" -> "稳定的 ID 连接所有收藏。\n\n改名、改色与说明修改会立即同步给持有者。\n\n停发后，原有收藏仍可继续佩戴。";
            case "players" -> "收藏与佩戴分开控制。\n\n新发放默认不自动佩戴，收藏默认私密。\n\n管理员可查询完整档案。";
            case "ops" -> "备份包含私人收藏和管理授权。\n\n完整恢复需空数据库，由真实服务器控制台明确确认。";
            case "settings" -> "三个展示出口独立开关。\n\n聊天和 TAB 的称号固定在名字左侧。\n\n更换奖励称号后，真实玩家下次登录领取一次。";
            default -> "操作记录包含发放、撤销与管理变更。\n\n记录仅管理员可查，不对普通玩家公开。";
          };
      drawParagraph(g, help, right + 14, y, 154, MUTED);
    }
  }

  private void drawParagraph(GuiGraphics g, String text, int bx, int by, int bw, int color) {
    var lines = font.split(Component.literal(text), bw);
    for (int i = 0; i < lines.size() && by + i * 12 < height - 57; i++)
      g.drawString(font, lines.get(i), bx, by + i * 12, color, false);
  }

  private void drawFit(GuiGraphics g, Component text, int center, int y, int max) {
    float s = Math.min(1f, (float) max / Math.max(1, font.width(text)));
    g.pose().pushPose();
    g.pose().translate(center, y, 0);
    g.pose().scale(s, s, 1);
    g.drawCenteredString(font, text, 0, 0, TEXT);
    g.pose().popPose();
  }

  @Override
  public boolean mouseClicked(double px, double py, int button) {
    double bx = px / uiScale, by = py / uiScale;
    if (view.equals("edit")
        && tab.equals("gradient")
        && button == 0
        && bx >= right + 10
        && bx <= right + 172
        && by >= 166
        && by < 184) {
      try {
        RichText rich = new RichText(segments, List.of());
        var glyphs = rich.glyphs();
        float s = Math.min(1f, 146f / font.width(Text.render(rich)));
        float start = right + 91 - font.width(Text.render(rich)) * s / 2;
        for (int i = 0; i < glyphs.size(); i++) {
          Glyph p = glyphs.get(i);
          float gw =
              font.width(
                      Text.render(
                          new RichText(
                              List.of(
                                  new Segment(
                                      p.text(),
                                      p.color(),
                                      p.bold(),
                                      p.italic(),
                                      p.underline(),
                                      p.strike())),
                              List.of())))
                  * s;
          if (bx >= start && bx < start + gw) {
            Set<Integer> ps = new TreeSet<>(gradientFromFields().positions());
            if (!ps.remove(i)) ps.add(i);
            fields.get("positions").setValue(positions(new ArrayList<>(ps)));
            return true;
          }
          start += gw;
        }
      } catch (Exception e) {
        status = ServerRuntime.safeError(e);
      }
    }
    return super.mouseClicked(bx, by, button);
  }

  @Override
  public boolean mouseReleased(double bx, double by, int button) {
    return super.mouseReleased(bx / uiScale, by / uiScale, button);
  }

  @Override
  public boolean mouseDragged(double bx, double by, int button, double dx, double dy) {
    return super.mouseDragged(bx / uiScale, by / uiScale, button, dx / uiScale, dy / uiScale);
  }

  @Override
  public void mouseMoved(double bx, double by) {
    super.mouseMoved(bx / uiScale, by / uiScale);
  }

  @Override
  public boolean mouseScrolled(double bx, double by, double horizontal, double amount) {
    if (view.equals("titles") || view.equals("players")) {
      int count = view.equals("titles") ? titles.size() : players.size();
      listOffset =
          Math.max(0, Math.min(Math.max(0, count - 1), listOffset + (amount < 0 ? 1 : -1)));
      if (view.equals("players")) capturePlayer();
      layout();
      return true;
    }
    if (view.equals("text") || view.equals("history")) {
      outputOffset = Math.max(0, outputOffset + (amount < 0 ? 3 : -3));
      return true;
    }
    return super.mouseScrolled(bx / uiScale, by / uiScale, horizontal, amount);
  }

  // 1.21 Screen.render invokes renderBackground before its widgets. The custom
  // screen already paints its background, so keep the final widget pass transparent.
  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
