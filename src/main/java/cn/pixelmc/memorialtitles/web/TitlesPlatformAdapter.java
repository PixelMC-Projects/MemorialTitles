package cn.pixelmc.memorialtitles.web;

import static cn.pixelmc.memorialtitles.domain.Model.JSON;

import cn.pixelmc.memorialtitles.persistence.ChangeJournal;
import cn.pixelmc.memorialtitles.service.TitleService;
import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Title business routes hosted inside Manager's authenticated HTTP boundary. No listener or
 * accounts.
 */
public final class TitlesPlatformAdapter {
  private final TitleService service;
  private final Function<JsonObject, JsonObject> platform;
  private final AdminWebServer routes;
  private volatile Set<UUID> online = Set.of();

  public TitlesPlatformAdapter(TitleService service, Function<JsonObject, JsonObject> platform) {
    this.service = Objects.requireNonNull(service);
    this.platform = Objects.requireNonNull(platform);
    service.attachPlatform(platform);
    service.attachPlayerAssets(ChangeJournal.NO_ASSETS, id -> online.contains(id));
    routes =
        new AdminWebServer(
            service,
            new AdminWebServer.Host() {
              public Set<UUID> online() {
                return online;
              }

              public void mirror() {
                call("titles.mirror", null);
              }

              public CompletableFuture<Path> backup(TitleService.Actor actor) {
                return CompletableFuture.failedFuture(
                    new IllegalStateException("请通过 Manager 统一备份"));
              }

              public CompletableFuture<Void> reload(TitleService.Actor actor) {
                try {
                  JsonObject value = call("titles.definitions", null);
                  AdminWebServer.Definitions definitions =
                      JSON.fromJson(value, AdminWebServer.Definitions.class);
                  return service
                      .reload(
                          actor,
                          Objects.requireNonNull(definitions.titles()),
                          Objects.requireNonNull(definitions.settings()),
                          TitleService.definitionsStamp(service.snapshot()))
                      .thenRun(this::mirror);
                } catch (RuntimeException error) {
                  return CompletableFuture.failedFuture(error);
                }
              }
            },
            id -> {
              JsonObject response = call("avatars.version", id);
              return response.has("version") ? response.get("version").getAsLong() : 0;
            });
  }

  private JsonObject call(String op, UUID id) {
    JsonObject request = new JsonObject();
    request.addProperty("op", op);
    if (id != null) request.addProperty("player", id.toString());
    JsonObject result = Objects.requireNonNull(platform.apply(request), "Manager response missing");
    if (result.has("error")) throw new IllegalStateException(result.get("error").getAsString());
    return result;
  }

  /**
   * The caller must authenticate actor, check current permissions, and validate POST origin/CSRF.
   */
  public boolean handle(HttpExchange exchange, String path, UUID actor, Set<UUID> online)
      throws Exception {
    if (!modulePath(path)) return false;
    this.online = Set.copyOf(Objects.requireNonNull(online));
    try {
      if (actor == null) throw new SecurityException("缺少已验证的管理员身份");
      routes.moduleApi(
          exchange, path, new TitleService.Actor(actor, actor.toString(), false, true));
    } catch (Throwable error) {
      AdminWebServer.moduleFailure(exchange, error);
    }
    return true;
  }

  private static boolean modulePath(String path) {
    return Set.of(
                "/api/state",
                "/api/titles",
                "/api/title-holders",
                "/api/title-delete/preview",
                "/api/title-delete/confirm",
                "/api/players",
                "/api/player-delete/preview",
                "/api/player-delete/confirm",
                "/api/grants",
                "/api/settings",
                "/api/history",
                "/api/undo",
                "/api/definitions",
                "/api/definitions/preview",
                "/api/definitions/apply",
                "/api/reload")
            .contains(path)
        || path.startsWith("/api/players/")
        || path.startsWith("/api/history/");
  }

  /** Schema ownership is explicit so platform backup includes every durable title table. */
  public static JsonObject storageManifest() {
    JsonObject manifest = new JsonObject();
    manifest.addProperty("version", 1);
    manifest.addProperty("scope", "global");
    manifest.addProperty("label", "纪念称号");
    manifest.addProperty("name", "MemorialTitles");
    manifest.addProperty("page", "titles");
    manifest.addProperty("preference", "titles");
    manifest.add(
        "tables",
        JSON.toJsonTree(
            List.of(
                "mt_schema",
                "mt_state",
                "mt_titles",
                "mt_players",
                "mt_owned",
                "mt_equipped",
                "mt_receipts",
                "mt_admins",
                "mt_audit",
                "mt_cell_versions",
                "mt_changes",
                "mt_audit_hidden",
                "mt_notifications",
                "mt_display_preferences")));
    manifest.add("fields", new JsonArray());
    manifest.add("capabilities", JSON.toJsonTree(List.of("client-avatar")));
    return manifest;
  }
}
