package cn.pixelmc.memorialtitles.service;

import java.util.*;
import java.util.function.*;

/** Server-thread queue for the gap between a loader JOIN and asynchronous Manager admission. */
public final class PendingAdmissions<P> {
  private final Map<UUID, P> waiting = new HashMap<>();
  public void await(UUID id, P player) { waiting.put(id, player); }
  public void forget(UUID id, P player) { waiting.remove(id, player); }
  public void clear() { waiting.clear(); }
  public int size() { return waiting.size(); }
  public void drain(BiPredicate<UUID, P> live, Predicate<UUID> admitted, Consumer<P> accept) {
    for (var iterator = waiting.entrySet().iterator(); iterator.hasNext();) {
      var entry = iterator.next();
      if (!live.test(entry.getKey(), entry.getValue())) {
        iterator.remove();
      } else if (admitted.test(entry.getKey())) {
        P player = entry.getValue();
        iterator.remove();
        accept.accept(player);
      }
    }
  }
}
