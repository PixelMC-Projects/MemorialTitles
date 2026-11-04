package cn.pixelmc.memorialtitles.api;

import cn.pixelmc.memorialtitles.domain.Model;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Optional native-player boundary. UUID must come from the authenticated game connection. */
public final class PlayerHubApi {
  private record Binding(TitleService service, Predicate<UUID> admitted) {}
  private static volatile Binding binding;
  private static final Map<UUID, long[]> REQUESTS = new HashMap<>();
  public static int protocolVersion() { return 1; }
  public static void attach(TitleService service, Predicate<UUID> admitted) {
    Binding previous = binding;
    if (previous != null) previous.service().attachHubAdmission(id -> false);
    if (service != null) service.attachHubAdmission(Objects.requireNonNull(admitted));
    binding = service == null ? null : new Binding(service, Objects.requireNonNull(admitted));
    synchronized (REQUESTS) { REQUESTS.clear(); }
  }
  private static void throttle(UUID player) {
    long now = System.nanoTime();
    synchronized (REQUESTS) {
      // Sliding lifetime is bounded even for repeated joins by arbitrary authenticated UUIDs.
      REQUESTS.entrySet().removeIf(item -> now - item.getValue()[0] >= TimeUnit.SECONDS.toNanos(10));
      if (!REQUESTS.containsKey(player) && REQUESTS.size() >= 4096)
        throw new IllegalStateException("称号请求繁忙，请稍后重试");
      long[] counter = REQUESTS.computeIfAbsent(player, ignored -> new long[] {now, 0});
      if (++counter[1] > 30) throw new IllegalStateException("称号操作过于频繁，请稍后重试（每10秒最多30次）");
    }
  }
  public static CompletableFuture<String> request(UUID player, String action, String payload) {
    try {
      Binding current = binding;
      if (current == null) throw new IllegalStateException("称号服务尚未启动");
      if (player == null || !current.admitted().test(player)) throw new SecurityException("玩家会话已失效，请重新打开");
      if (payload == null || payload.length() > 4096 || payload.getBytes(StandardCharsets.UTF_8).length > 4096)
        throw new IllegalArgumentException("称号请求过大");
      if (action == null || !PlayerHubView.ACTIONS.contains(action)) throw new IllegalArgumentException("不支持的称号操作");
      JsonElement parsed;
      try { parsed = JsonParser.parseString(payload); }
      catch (RuntimeException invalid) { throw new IllegalArgumentException("称号请求须为 JSON 对象"); }
      if (!parsed.isJsonObject()) throw new IllegalArgumentException("称号请求须为 JSON 对象");
      JsonObject body = parsed.getAsJsonObject();
      PlayerHubView.validate(action, body);
      throttle(player);
      return current.service().background(() -> {
        if (binding != current || !current.admitted().test(player)) throw new SecurityException("玩家会话已失效，请重新打开");
        return null;
      }).thenCompose(ignored -> current.service().playerHub(player, action, body))
          .handle((value, error) -> {
            if (error != null) {
              Throwable cause = error;
              while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
              String message = cause instanceof IllegalArgumentException || cause instanceof IllegalStateException || cause instanceof SecurityException
                  ? cause.getMessage() : "称号服务暂不可用，请稍后重试";
              throw new CompletionException(new IllegalStateException(message));
            }
            if (binding != current || !current.admitted().test(player)) throw new CompletionException(new IllegalStateException("玩家会话已失效，请重新打开"));
            return value.toString(); // Preserve explicit JSON null (for example terminal nextCursor).
          });
    } catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
  }
  private PlayerHubApi() {}
}
