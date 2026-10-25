package cn.pixelmc.memorialtitles.service;

import cn.pixelmc.memorialtitles.domain.Model.Snapshot;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One monotonic deadline per admitted online session; eligibility only reads the cached snapshot. */
public final class UnequippedTitleReminder {
  public static final long JOIN_DELAY = TimeUnit.SECONDS.toNanos(10);
  public static final long INTERVAL = TimeUnit.MINUTES.toNanos(30);
  private long next;

  public UnequippedTitleReminder(long joinedAt) {
    next = joinedAt + JOIN_DELAY;
  }

  /** Claims one scheduled check, never a burst of overdue messages after a stall. */
  public boolean claim(long now) {
    if (now - next < 0) return false;
    next = now + INTERVAL;
    return true;
  }

  public static boolean shouldRemind(Snapshot snapshot, UUID player) {
    return snapshot.players().containsKey(player) && !snapshot.hasEquippedTitle(player)
        && snapshot.displayPreferences(player).reminderEnabled();
  }
}
