package cn.pixelmc.memorialtitles.forge;

import cn.pixelmc.memorialtitles.domain.Model.*;
import com.mojang.logging.LogUtils;
import java.lang.reflect.*;
import java.util.*;

/** Optional TAB 5.2.1 public API adapter; no dependency on its platform internals. */
public final class TabAdapter {
  private record Applied(String previousCustom, String applied) {}

  private final Map<UUID, Applied> applied = new HashMap<>();
  private Object api, manager;
  private Class<?> tabPlayer;
  private boolean absent, failed;

  public boolean active() {
    return manager != null && !failed;
  }

  private Object call(Object target, String method, Class<?>[] types, Object... args)
      throws Exception {
    return target.getClass().getMethod(method, types).invoke(target, args);
  }

  public void update(ServerRuntime r) {
    if (absent || failed) return;
    try {
      Class<?> apiType;
      try {
        apiType = Class.forName("me.neznamy.tab.api.TabAPI");
      } catch (ClassNotFoundException e) {
        absent = true;
        return;
      }
      Object next = apiType.getMethod("getInstance").invoke(null);
      if (next == null) return;
      if (api != next) {
        api = next;
        manager = apiType.getMethod("getTabListFormatManager").invoke(api);
        tabPlayer = Class.forName("me.neznamy.tab.api.TabPlayer");
        applied.clear();
      }
      if (manager == null) return;
      Class<?> format = Class.forName("me.neznamy.tab.api.tablist.TabListFormatManager");
      Snapshot s = r.service.snapshot();
      Set<UUID> online = new HashSet<>();
      for (var player : r.server.getPlayerList().getPlayers()) {
        UUID id = player.getUUID();
        online.add(id);
        Object tp = apiType.getMethod("getPlayer", UUID.class).invoke(api, id);
        if (tp == null || !(boolean) tabPlayer.getMethod("isLoaded").invoke(tp)) continue;
        String custom = (String) format.getMethod("getCustomPrefix", tabPlayer).invoke(manager, tp);
        Applied old = applied.get(id);
        String baseline =
            old != null && Objects.equals(custom, old.applied) ? old.previousCustom : custom;
        Title title = s.displayed(id, DisplayPosition.TAB);
        if (title == null) {
          if (old != null && Objects.equals(custom, old.applied))
            format.getMethod("setPrefix", tabPlayer, String.class).invoke(manager, tp, baseline);
          applied.remove(id);
          continue;
        }
        String original =
            baseline != null
                ? baseline
                : (String) format.getMethod("getOriginalPrefix", tabPlayer).invoke(manager, tp);
        String suffix = "&r[" + legacy(title.name()) + "&r] " + (original == null ? "" : original);
        if (!suffix.equals(custom))
          format.getMethod("setPrefix", tabPlayer, String.class).invoke(manager, tp, suffix);
        applied.put(id, new Applied(baseline, suffix));
      }
      applied.keySet().retainAll(online);
    } catch (Exception e) {
      failed = true;
      LogUtils.getLogger().error("MemorialTitles TAB API适配失败，已停止写入TAB；称号核心继续工作", e);
    }
  }

  private static String legacy(RichText name) {
    StringBuilder out = new StringBuilder();
    for (Glyph g : name.glyphs()) {
      out.append("&r&#").append(String.format("%06x", g.color()));
      if (g.bold()) out.append("&l");
      if (g.italic()) out.append("&o");
      if (g.underline()) out.append("&n");
      if (g.strike()) out.append("&m");
      out.append(g.text().replace("&", "\\&").replace("%", "\\%"));
    }
    return out.toString();
  }
}
