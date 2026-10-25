package cn.pixelmc.memorialtitles.api;

import cn.pixelmc.memorialtitles.domain.Model.*;
import cn.pixelmc.memorialtitles.service.TitleService;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Trusted server-mod API. All futures finish after commit; do not join them on the game thread. */
public final class MemorialTitlesApi {
  private static volatile TitleService service;

  public static void attach(TitleService value) {
    service = value;
  }

  private static TitleService service() {
    if (service == null) throw new IllegalStateException("MemorialTitles server is not running");
    return service;
  }

  public static Snapshot snapshot() {
    return service().snapshot();
  }

  public static CompletableFuture<Integer> grant(
      String callingMod, UUID player, String title, String reason) {
    return service().grant(TitleService.Actor.api(callingMod), List.of(player), title, reason);
  }

  public static CompletableFuture<Integer> revoke(
      String callingMod, UUID player, String title, String reason) {
    return service().revoke(TitleService.Actor.api(callingMod), List.of(player), title, reason);
  }

  public static CompletableFuture<Void> equip(UUID player, String title) {
    return service().equip(player, title);
  }

  public static void onChange(Consumer<Snapshot> listener) {
    service().listen(listener);
  }

  private MemorialTitlesApi() {}
}
