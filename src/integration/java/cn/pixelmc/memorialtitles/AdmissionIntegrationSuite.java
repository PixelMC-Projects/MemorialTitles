package cn.pixelmc.memorialtitles;
import cn.pixelmc.memorialtitles.service.PendingAdmissions;
import java.util.*;
public final class AdmissionIntegrationSuite {
 private static int checks;
 private static void check(boolean result, String name) { if (!result) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
 public static void main(String[] args) {
  var queue = new PendingAdmissions<Object>();
  UUID id = UUID.randomUUID(); Object old = new Object(), current = new Object();
  Map<UUID,Object> online = new HashMap<>(); Set<UUID> admitted = new HashSet<>(); List<Object> accepted = new ArrayList<>();
  var live = (java.util.function.BiPredicate<UUID,Object>) (uuid, player) -> online.get(uuid) == player;
  online.put(id, old); queue.await(id, old); queue.await(id, old);
  queue.drain(live, admitted::contains, accepted::add);
  check(accepted.isEmpty() && queue.size() == 1, "JOIN before asynchronous admission creates no login and deduplicates");
  admitted.add(id); queue.drain(live, admitted::contains, accepted::add); queue.drain(live, admitted::contains, accepted::add);
  check(accepted.equals(List.of(old)) && queue.size() == 0, "delayed admission is consumed exactly once");
  admitted.clear(); accepted.clear(); queue.await(id, old); online.remove(id); admitted.add(id);
  queue.drain(live, admitted::contains, accepted::add);
  check(accepted.isEmpty() && queue.size() == 0, "disconnect before admission discards candidate without login");
  admitted.clear(); online.put(id, current); queue.await(id, old);
  queue.drain(live, admitted::contains, accepted::add);
  check(queue.size() == 0, "same UUID reconnect cannot admit an old player object");
  queue.await(id, current); queue.forget(id, old);
  check(queue.size() == 1, "stale disconnect cannot remove the new player candidate");
  admitted.add(id); queue.drain(live, admitted::contains, accepted::add);
  check(accepted.equals(List.of(current)), "only the live new player is admitted");
  queue.await(id, current); queue.forget(id, current);
  check(queue.size() == 0, "normal disconnect removes pending admission");
  queue.await(id, current); queue.clear();
  check(queue.size() == 0, "server shutdown clears candidates");
  System.out.println("Admission checks passed: " + checks);
 }
}
