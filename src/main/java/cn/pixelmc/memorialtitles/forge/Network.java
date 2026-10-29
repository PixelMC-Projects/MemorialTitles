package cn.pixelmc.memorialtitles.forge;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.MemorialTitles;
import cn.pixelmc.memorialtitles.client.ClientRuntime;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class Network {
  public static final ResourceLocation REQUEST = ResourceLocation.fromNamespaceAndPath(MemorialTitles.ID, "request");
  public static final ResourceLocation RESPONSE = ResourceLocation.fromNamespaceAndPath(MemorialTitles.ID, "response");
  private static final Map<UUID, long[]> RATE = new HashMap<>();
  public record Request(String json) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
    public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MemorialTitles.ID, "request"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Request> CODEC =
        net.minecraft.network.codec.StreamCodec.of((buffer, value) -> buffer.writeUtf(value.json, 32767), buffer -> new Request(buffer.readUtf(32767)));
    @Override public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() { return TYPE; }
  }
  public record Response(String json) implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
    public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(MemorialTitles.ID, "response"));
    public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, Response> CODEC =
        net.minecraft.network.codec.StreamCodec.of((buffer, value) -> buffer.writeUtf(value.json, 262144), buffer -> new Response(buffer.readUtf(262144)));
    @Override public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() { return TYPE; }
  }
  public static void register() {
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playC2S().register(Request.TYPE, Request.CODEC);
    net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(Response.TYPE, Response.CODEC);
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(Request.TYPE,
        (payload, context) -> context.server().execute(() -> handle(payload, context.player())));
  }
  public static void request(String operation, JsonObject data) {
    data.addProperty("op", operation);
    net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(new Request(JSON.toJson(data)));
  }
  public static void reply(ServerPlayer p, String kind, Object value) {
    if (p.connection == null || !net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, Response.TYPE)) return;
    JsonObject out = new JsonObject(); out.addProperty("kind", kind); out.add("data", JSON.toJsonTree(value));
    String json = JSON.toJson(out);
    if (json.length() > 262144) throw new IllegalArgumentException("响应太大，请缩小筛选范围");
    net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p, new Response(json));
  }
  public static void admin(ServerPlayer p) {
    TitleService.requireAdmin(
        ServerRuntime.current.service.snapshot(), TitleService.Actor.player(p.getUUID()));
    var web = ServerRuntime.current.web;
    String address = web == null ? cn.pixelmc.memorialtitles.service.RemoteGateway.publicUrl() : web.address();
    if(address.isBlank())throw new IllegalStateException("请从PixelMC Manager独立管理台操作；公共地址尚未配置");
    var message =
        net.minecraft.network.chat.Component.literal("称号管理台：" + address + "。请使用控制台创建的账号登录。");
    if (!address.contains("<"))
      message.withStyle(
          style ->
              style
                  .withUnderlined(true)
                  .withClickEvent(
                      new net.minecraft.network.chat.ClickEvent(
                          net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, address)));
    p.sendSystemMessage(message);
  }

  public static void prompt(
      ServerPlayer p, String mode, UUID target, String sort, String filter, String query) {
    JsonObject j = new JsonObject();
    j.addProperty("mode", mode);
    j.addProperty("target", target.toString());
    j.addProperty("sort", sort);
    j.addProperty("filter", filter);
    j.addProperty("query", query);
    reply(p, "prompt", j);
  }

  public static void forget(UUID id) {
    RATE.remove(id);
  }

  private static boolean rate(UUID id) {
    long now = System.currentTimeMillis();
    long[] state = RATE.computeIfAbsent(id, k -> new long[] {now, 0});
    if (now - state[0] >= 1000) {
      state[0] = now;
      state[1] = 0;
    }
    return ++state[1] <= 12;
  }

  private static void handle(Request req, ServerPlayer p) {
    if (p == null || !rate(p.getUUID())) return;
    ServerRuntime r = ServerRuntime.current;
    if (r == null || r.service == null) return;
    try {
      validateDepth(req.json);
      JsonObject j = JsonParser.parseString(req.json).getAsJsonObject();
      String op = string(j, "op", 40);
      if (op.equals("avatar")) {
        if(r.service.remote() && !ServerRuntime.isFake(p) && j.has("nonce"))r.service.remoteAvatar(p.getUUID(),string(j,"nonce",64),string(j,"png",8192));
        if (r.web != null && !ServerRuntime.isFake(p) && j.has("nonce"))
          r.web.avatars.uploadFallback(p.getUUID(), string(j, "nonce", 64), string(j, "png", 8192));
        return;
      }
      TitleService.Actor actor = TitleService.Actor.player(p.getUUID());
      Snapshot s = r.service.snapshot();
      if (op.equals("open")) {
        String mode = string(j, "mode", 20);
        if (!Set.of("collection", "catalogue", "people", "display").contains(mode))
          throw new IllegalArgumentException("菜单模式无效");
        UUID target = j.has("target") ? UUID.fromString(string(j, "target", 36)) : p.getUUID();
        if (mode.equals("display") && !target.equals(p.getUUID()))
          throw new IllegalArgumentException("只能设置自己的称号显示位置");
        if (!target.equals(p.getUUID()) && !s.players().containsKey(target))
          throw new IllegalArgumentException("未知玩家");
        TitleMenu.open(
            p,
            mode,
            target,
            opt(j, "query", "", 64),
            opt(j, "sort", "order", 16),
            opt(j, "filter", "all", 16),
            j.has("page") ? Math.max(0, j.get("page").getAsInt()) : 0);
        return;
      }
      TitleService.requireAdmin(s, actor);
      switch (op) {
        case "adminOpen" -> admin(p);
        case "adminTitles" -> {
          String q = opt(j, "query", "", 64).toLowerCase(Locale.ROOT);
          List<Title> all =
              s.titles().values().stream()
                  .filter(t -> (t.id() + t.name().plain()).toLowerCase(Locale.ROOT).contains(q))
                  .sorted(Comparator.comparing(Title::id))
                  .toList();
          int page =
              Math.min(Math.max(0, j.get("page").getAsInt()), Math.max(0, (all.size() - 1) / 8));
          JsonObject out = new JsonObject();
          out.add(
              "rows", JSON.toJsonTree(all.subList(page * 8, Math.min(all.size(), page * 8 + 8))));
          out.addProperty("page", page);
          out.addProperty("pages", Math.max(1, (all.size() + 7) / 8));
          reply(p, "titles", out);
        }
        case "adminSave" -> {
          Title t = JSON.fromJson(j.get("title"), Title.class);
          ack(p, r.service.saveTitle(actor, t, t.revision()), true);
        }
        case "adminPlayers" -> {
          String q = opt(j, "query", "", 64).toLowerCase(Locale.ROOT);
          List<Profile> all =
              s.players().values().stream()
                  .filter(v -> (v.name() + v.id()).toLowerCase(Locale.ROOT).contains(q))
                  .sorted(Comparator.comparing(Profile::name))
                  .toList();
          int page =
              Math.min(Math.max(0, j.get("page").getAsInt()), Math.max(0, (all.size() - 1) / 8));
          JsonObject out = new JsonObject();
          out.add(
              "rows", JSON.toJsonTree(all.subList(page * 8, Math.min(all.size(), page * 8 + 8))));
          out.addProperty("page", page);
          out.addProperty("pages", Math.max(1, (all.size() + 7) / 8));
          reply(p, "players", out);
        }
        case "adminCollection" -> {
          UUID id = UUID.fromString(string(j, "player", 36));
          JsonObject out = new JsonObject();
          out.add("owned", JSON.toJsonTree(s.collection(id, p.getUUID(), true)));
          out.addProperty("equipped", s.equipped().getOrDefault(id, ""));
          reply(p, "text", JSON.toJson(out));
        }
        case "adminGrant", "adminRevoke" -> {
          JsonArray a = j.getAsJsonArray("players");
          if (a.size() > 1000) throw new IllegalArgumentException("最多1000人");
          List<UUID> ids = new ArrayList<>();
          for (JsonElement e : a) ids.add(UUID.fromString(e.getAsString()));
          String title = string(j, "title", 64), reason = string(j, "reason", 2048);
          ack(
              p,
              op.equals("adminGrant")
                  ? r.service.grant(actor, ids, title, reason)
                  : r.service.revoke(actor, ids, title, reason),
              false);
        }
        case "adminSettingsGet" -> reply(p, "settings", s.settings());
        case "adminSettingsSave" ->
            ack(
                p,
                r.service.settings(actor, JSON.fromJson(j.get("settings"), Settings.class)),
                true);
        case "adminHistory" ->
            r.service
                .history(actor, 30)
                .whenComplete(
                    (v, e) ->
                        r.server.execute(
                            () -> {
                              if (e != null) reply(p, "error", ServerRuntime.safeError(e));
                              else if (r.service.snapshot().admins().contains(p.getUUID()))
                                reply(p, "text", JSON.toJson(v));
                            }));
        case "adminHealth" ->
            reply(
                p,
                "text",
                "数据库："
                    + (r.service.healthy() ? "在线" : "离线，缓存只读")
                    + "；称号 "
                    + s.titles().size()
                    + "；已登记玩家 "
                    + s.players().size()
                    + "；最近成功刷新 "
                    + sTime(r.service.lastSuccess()));
        case "adminBackup" -> ack(p, r.backup(actor), false);
        case "adminReload" -> ack(p, r.reload(actor), false);
        case "adminExport" ->
            ack(
                p,
                r.service.background(
                    () -> {
                      TitleService.requireAdmin(r.service.snapshot(), actor);
                      r.files.exportDefinitions(r.service.snapshot());
                      return "已导出 definitions.json（完整数据请用备份）";
                    }),
                false);
        case "adminImportPreview" -> {
          String name = string(j, "file", 160);
          if (!name.matches("[a-zA-Z0-9_.-]+\\.json\\.gz"))
            throw new IllegalArgumentException("备份文件名无效");
          Path file = Path.of(r.files.config.backups()).resolve(name);
          ack(
              p,
              r.service
                  .background(() -> Backup.read(file))
                  .thenCompose(a -> r.service.importPreview(actor, a)),
              false);
        }
        default -> throw new IllegalArgumentException("未知操作");
      }
    } catch (Exception error) {
      reply(p, "error", ServerRuntime.safeError(error));
    }
  }

  private static String sTime(long at) {
    return java.time.Instant.ofEpochMilli(at).toString();
  }

  private static void validateDepth(String json) {
    int depth = 0;
    boolean quoted = false, escaped = false;
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      if (quoted) {
        if (escaped) escaped = false;
        else if (c == '\\') escaped = true;
        else if (c == '"') quoted = false;
      } else if (c == '"') quoted = true;
      else if (c == '{' || c == '[') {
        if (++depth > 24) throw new IllegalArgumentException("请求嵌套过深");
      } else if (c == '}' || c == ']') depth--;
    }
  }

  private static String string(JsonObject j, String key, int max) {
    if (!j.has(key) || !j.get(key).isJsonPrimitive())
      throw new IllegalArgumentException("缺少字段 " + key);
    String s = j.get(key).getAsString();
    check(s, max);
    return s;
  }

  private static String opt(JsonObject j, String key, String fallback, int max) {
    return j.has(key) ? string(j, key, max) : fallback;
  }

  private static void ack(ServerPlayer p, CompletableFuture<?> future, boolean definitions) {
    ServerRuntime r = ServerRuntime.current;
    future.whenComplete(
        (v, e) ->
            r.server.execute(
                () -> {
                  if (e == null && definitions) r.saveDefinitions();
                  if (p.connection != null)
                    reply(
                        p,
                        e == null ? "ok" : "error",
                        e == null
                            ? (v == null ? "已保存并同步" : v.toString())
                            : ServerRuntime.safeError(e));
                }));
  }

  public record Display(UUID player, RichText text, boolean head, boolean chat) {
    public static Display forPlayer(Snapshot snapshot, UUID player) {
      Title title = snapshot.worn(player);
      boolean head = snapshot.positionAllowed(player, DisplayPosition.HEAD);
      boolean chat = snapshot.positionAllowed(player, DisplayPosition.CHAT);
      return title == null || (!head && !chat) ? null : new Display(player, title.name(), head, chat);
    }
  }

  public record DisplayPage(
      boolean reset,
      boolean head,
      boolean chat,
      boolean tab,
      float scale,
      float offset,
      String chatPrefix,
      List<Display> rows) {}

  public static void syncDisplays(ServerRuntime runtime) {
    Snapshot s = runtime.service.snapshot();
    List<Display> rows = new ArrayList<>();
    for (ServerPlayer p : runtime.server.getPlayerList().getPlayers()) {
      Display display = Display.forPlayer(s, p.getUUID());
      if (display != null) rows.add(display);
    }
    // Only equipped text is broadcast. Private collections, hidden definitions and audit never go
    // out here.
    for (int from = 0; from < Math.max(1, rows.size()); from += 24) {
      int end = Math.min(rows.size(), from + 24);
      DisplayPage page =
          new DisplayPage(
              from == 0,
              s.settings().head(),
              s.settings().chat(),
              s.settings().tab(),
              s.settings().headScale(),
              s.settings().headOffset(),
              s.settings().chatPrefix(),
              rows.subList(from, end));
      for (ServerPlayer p : runtime.server.getPlayerList().getPlayers())
        if (!ServerRuntime.isFake(p)) reply(p, "display", page);
    }
  }

  private Network() {}
}
