package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.Text;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

/** Real local persistence checks; no production server, player, or cloud test data. */
public final class NotificationIntegrationSuite {
  private static int checks;

  private static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    checks++;
    System.out.println("PASS " + message);
  }

  private static <T> T await(CompletableFuture<T> future) throws Exception {
    return future.get(20, TimeUnit.SECONDS);
  }

  public static void main(String[] args) throws Exception {
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43321/";
    String name = "mt_notice_" + UUID.randomUUID().toString().replace("-", "");
    Database root = new Database(new Database.Config(base, "root", password));
    try (Connection c = root.open()) {
      Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4");
    }
    Database db = new Database(new Database.Config(base + name, "root", password));
    UUID player = UUID.randomUUID(), other = UUID.randomUUID();
    var actor = TitleService.Actor.consoleActor();
    RichText rich = new RichText(
        List.of(new Segment("初来🌟", 0xffffff, false, false, false, false)),
        List.of(new Gradient(List.of(0, 1, 2), 0xff0000, 0x0000ff)));
    List<PlayerNotice> saved;
    try {
      try (TitleService service = new TitleService(db)) {
        await(service.reconnect());
        // Exercise upgrading an existing v3 schema, rather than only creating a fresh schema.
        try (Connection c = db.open()) {
          Database.execute(c, "DROP TABLE mt_display_preferences");
          Database.execute(c, "DROP TABLE mt_notifications");
          Database.execute(c, "DELETE FROM mt_schema WHERE version>=4");
        }
        await(service.reconnect());
        check(await(service.notifications(player)).isEmpty(), "v3 schema upgrade creates an empty outbox");
        Title title = new Title("welcome", rich, "", "", "", "", 0, 0, true, true, 0);
        await(service.saveTitle(actor, title, 0));
        await(service.settings(actor, new Settings("welcome", true, true, true, 1, .3f, "[{title}] ", 14)));
        await(service.login(player, "NoticeTester"));
        saved = await(service.notifications(player));
        check(saved.size() == 1 && saved.get(0).granted(), "first login reward creates one durable notice");
        check(!service.snapshot().equipped().containsKey(player), "first login reward does not autoequip");
        await(service.login(player, "NoticeTester"));
        await(service.grant(actor, List.of(player), "welcome", "duplicate"));
        await(service.privacy(player, "welcome", true, true));
        check(await(service.notifications(player)).size() == 1, "duplicate grants logins and privacy edits do not duplicate notices");
        await(service.revoke(actor, List.of(player), "welcome", "remove"));
        saved = await(service.notifications(player));
        check(saved.size() == 2 && !saved.get(1).granted(), "revoke queues a distinct ordered removal notice");
        check(await(service.notifications(other)).isEmpty(), "notices are private to their recipient");
        boolean failed = false;
        try {
          db.transaction(tx -> {
            tx.sql("INSERT INTO mt_owned VALUES(?,?,?,FALSE,TRUE)", player.toString(), "welcome", 1);
            throw new IllegalStateException("abort transaction");
          });
        } catch (IllegalStateException expected) {
          failed = true;
        }
        check(failed && await(service.notifications(player)).size() == 2, "rolled back changes leave no notification");
      }
      try (TitleService restarted = new TitleService(new Database(new Database.Config(base + name, "root", password)))) {
        await(restarted.reconnect());
        check(await(restarted.notifications(player)).equals(saved), "unread notices survive closing and recreating the service");
        boolean denied = false;
        try {
          await(restarted.acknowledgeNotifications(other, List.of(saved.get(0))));
        } catch (ExecutionException expected) {
          denied = true;
        }
        check(denied && await(restarted.notifications(player)).size() == 2, "acknowledgement cannot erase another player's notices");
        await(restarted.acknowledgeNotifications(player, List.of(saved.get(0))));
        check(await(restarted.notifications(player)).equals(List.of(saved.get(1))), "acknowledging one notice preserves the other");
        await(restarted.acknowledgeNotifications(player, List.of(saved.get(1))));
        check(await(restarted.notifications(player)).isEmpty(), "delivered notices are removed without repetition");
        await(restarted.grant(actor, List.of(player), "welcome", "grant again"));
        var preview = await(restarted.deletePreview(actor, player));
        await(restarted.deletePlayer(actor, player, preview.stamp(), "NoticeTester"));
        check(await(restarted.notifications(player)).isEmpty(), "deleting player information also clears their pending notices");
      }
      MutableComponent grant = Text.notification(rich, true), revoke = Text.notification(rich, false);
      check(grant.getString().equals("你已获得新的称号「初来🌟」")
          && revoke.getString().equals("你的称号「初来🌟」被已移除"), "notification text matches the requested wording");
      for (boolean granted : List.of(true, false)) {
        var component = Text.notification(rich, granted);
        int color = (granted ? ChatFormatting.GREEN : ChatFormatting.RED).getColor();
        check(component.getStyle().getColor().getValue() == color && !component.getStyle().isBold(),
            "notification surrounding text has the requested color and normal weight " + granted);
        var rendered = component.getSiblings().get(0).getSiblings();
        check(rendered.get(0).getStyle().getColor().getValue() == 0xff0000
                && rendered.get(2).getStyle().getColor().getValue() == 0x0000ff
                && !rendered.get(0).getStyle().isBold(),
            "title gradient and configured weight are preserved " + granted);
      }
      System.out.println("NOTIFICATION CHECKS PASSED: " + checks);
    } finally {
      try (Connection c = root.open()) {
        Database.execute(c, "DROP DATABASE " + name);
      }
    }
  }
}
