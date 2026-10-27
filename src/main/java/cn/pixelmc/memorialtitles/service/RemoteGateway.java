package cn.pixelmc.memorialtitles.service;

import static cn.pixelmc.memorialtitles.domain.Model.JSON;

import com.google.gson.*;
import java.util.concurrent.*;

/** Optional Manager transport. No hard dependency on its JAR, and no extra public listener. */
public final class RemoteGateway {
  private volatile String revision = "";

  public void confirm(JsonObject response) {
    if (response.has("revision")) revision = response.get("revision").getAsString();
  }

  public CompletableFuture<JsonObject> call(String method, JsonArray args) {
    try {
      JsonObject body = new JsonObject();
      body.add("args", args);
      body.addProperty("revision", revision);
      Class<?> api = Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi");
      @SuppressWarnings("unchecked")
      CompletableFuture<String> response =
          (CompletableFuture<String>)
              api.getMethod("call", String.class, String.class, String.class)
                  .invoke(null, "memorialtitles", method, JSON.toJson(body));
      return response.thenApply(value -> JsonParser.parseString(value).getAsJsonObject());
    } catch (Throwable e) {
      return CompletableFuture.failedFuture(
          new IllegalStateException("Manager桥接不可用；请安装并配置PixelMC Manager", e));
    }
  }

  public static void register() {
    try {
      Class<?> api = Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi");
      api.getMethod("register", String.class, String.class, String.class, String.class)
          .invoke(null, "memorialtitles", "MemorialTitles", "0.5.0-dev", "称号档案,玩家收藏,首登奖励,操作记录");
      api.getMethod("registerStorage", String.class, String.class)
          .invoke(
              null,
              "memorialtitles",
              JSON.toJson(cn.pixelmc.memorialtitles.web.TitlesPlatformAdapter.storageManifest()));
    } catch (ClassNotFoundException absent) {
      // Manager is optional on standalone servers and on every game client.
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("统一存储需要 PixelMC Manager 0.6.0 或更新的兼容版本", error);
    }
  }

  public static String publicUrl() {
    try {
      return (String)
          Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi")
              .getMethod("publicUrl")
              .invoke(null);
    } catch (ReflectiveOperationException e) {
      return "";
    }
  }

  public static boolean admitted(java.util.UUID id) {
    try {
      return (boolean)
          Class.forName("com.pixelmc.pixelmcmanager.api.ManagerBridgeApi")
              .getMethod("admitted", java.util.UUID.class)
              .invoke(null, id);
    } catch (ReflectiveOperationException e) {
      return false;
    }
  }
}
