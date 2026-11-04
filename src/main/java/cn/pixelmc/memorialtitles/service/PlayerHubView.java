package cn.pixelmc.memorialtitles.service;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.domain.TitlePresentation;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit own-player projection. Never serialize Snapshot or another player's ownership. */
public final class PlayerHubView {
  public static final int PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;
  public static final int MAX_PAGE_BYTES = 450 * 1024;
  public static final Set<String> ACTIONS = Set.of("snapshot", "catalogue", "equip", "visibility", "displayPreferences");
  public static void validate(String action, JsonObject payload) {
    Set<String> keys = switch (action) {
      case "snapshot" -> Set.of();
      case "catalogue" -> Set.of("cursor", "limit");
      case "equip" -> Set.of("title", "expectedRevision");
      case "visibility" -> Set.of("visible", "expectedRevision");
      case "displayPreferences" -> Set.of("head", "chat", "tab", "expectedRevision");
      default -> throw new IllegalArgumentException("不支持的称号操作");
    };
    if (!keys.containsAll(payload.keySet())) throw new IllegalArgumentException("称号请求包含不支持的字段");
    if (action.equals("equip")) string(payload, "title", 64);
    if (action.equals("visibility")) bool(payload, "visible");
    if (action.equals("displayPreferences")) { bool(payload, "head"); bool(payload, "chat"); bool(payload, "tab"); }
    if (!Set.of("snapshot", "catalogue").contains(action)) string(payload, "expectedRevision", 64);
    if (action.equals("catalogue")) {
      string(payload, "cursor", 96);
      if (!payload.has("limit") || !payload.get("limit").isJsonPrimitive()
          || !payload.getAsJsonPrimitive("limit").isNumber()
          || !payload.get("limit").getAsString().matches("[1-9][0-9]{0,2}"))
        throw new IllegalArgumentException("称号每页数量须为 1–100");
      int limit = payload.get("limit").getAsInt();
      if (limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("称号每页数量须为 1–100");
    }
  }
  public static String string(JsonObject object, String key, int max) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
        || value.getAsString().length() > max) throw new IllegalArgumentException("称号请求缺少有效的 " + key);
    return value.getAsString();
  }
  public static boolean bool(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
      throw new IllegalArgumentException("称号请求缺少有效的 " + key);
    return value.getAsBoolean();
  }
  private static JsonObject full(Snapshot state, UUID player) {
    JsonObject out = new JsonObject();
    out.addProperty("equippedTitle", state.hasEquippedTitle(player) ? state.equipped().get(player) : "");
    DisplayPreferences preferences = state.displayPreferences(player);
    out.add("displayPreferences", JSON.toJsonTree(Map.of("head", preferences.head(), "chat", preferences.chat(), "tab", preferences.tab())));
    out.add("globalDisplay", JSON.toJsonTree(Map.of("head", state.settings().head(), "chat", state.settings().chat(), "tab", state.settings().tab())));
    JsonArray rows = new JsonArray();
    for (Title title : state.catalogue(player)) {
      JsonObject row = JSON.toJsonTree(title).getAsJsonObject();
      Owned owned = state.ownership(player, title.id());
      row.addProperty("owned", owned != null);
      row.addProperty("acquiredAt", owned == null ? 0 : owned.acquiredAt());
      row.addProperty("publicCollection", owned != null && owned.publicCollection());
      row.addProperty("publicDisplay", owned != null && owned.publicDisplay());
      row.add("palette", JSON.toJsonTree(TitlePresentation.resolve(title.presentation())));
      rows.add(row);
    }
    out.add("titles", rows);
    return out;
  }
  public static String revision(Snapshot state, UUID player) { return revision(full(state, player)); }
  private static String revision(JsonObject value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(JSON.toJson(value).getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
  }
  public static JsonObject page(Snapshot state, UUID player, String cursor, int limit) {
    JsonObject out = full(state, player);
    String revision = revision(out);
    int offset = 0;
    if (cursor != null) {
      String[] parts = cursor.split(":", -1);
      if (parts.length != 2 || !parts[0].equals(revision)) throw new IllegalStateException("称号资料已更新，请重新载入图鉴");
      try { offset = Integer.parseInt(parts[1]); }
      catch (NumberFormatException invalid) { throw new IllegalArgumentException("称号分页游标无效"); }
    }
    JsonArray all = out.getAsJsonArray("titles"), rows = new JsonArray();
    if (offset < 0 || offset > all.size()) throw new IllegalArgumentException("称号分页游标无效");
    int end = offset;
    // Leave room for envelope/revision/cursor and stop explicitly at the byte budget.
    int bytes = 2048;
    while (end < all.size() && end - offset < limit) {
      JsonElement row = all.get(end);
      int size = JSON.toJson(row).getBytes(StandardCharsets.UTF_8).length + 2048;
      if (size + 2048 > MAX_PAGE_BYTES) throw new IllegalStateException("单个称号资料过大，暂时无法载入图鉴");
      if (bytes + size > MAX_PAGE_BYTES) break;
      rows.add(row); bytes += size; end++;
    }
    out.add("titles", rows);
    out.addProperty("revision", revision);
    if (end < all.size()) out.addProperty("nextCursor", revision + ":" + end);
    else out.add("nextCursor", JsonNull.INSTANCE);
    if (JSON.toJson(out).getBytes(StandardCharsets.UTF_8).length > MAX_PAGE_BYTES)
      throw new IllegalStateException("称号页面超过安全大小，请减少每页数量");
    return out;
  }
  private PlayerHubView() {}
}
