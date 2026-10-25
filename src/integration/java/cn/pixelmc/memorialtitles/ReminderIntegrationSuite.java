package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.Commands;
import cn.pixelmc.memorialtitles.forge.Text;
import cn.pixelmc.memorialtitles.persistence.*;
import cn.pixelmc.memorialtitles.service.*;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.CommandSourceStack;

/** Disposable local SQL coverage plus exact scheduling and privacy regression checks. */
public final class ReminderIntegrationSuite {
  private static final TitleService.Actor ADMIN = TitleService.Actor.consoleActor();
  private static final List<String> checks = new ArrayList<>();
  private static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
    checks.add(label); System.out.println("PASS " + label);
  }
  private static <T> T await(CompletableFuture<T> task) throws Exception { return task.get(25, TimeUnit.SECONDS); }
  private static void deny(CompletableFuture<?> task, String label) throws Exception {
    boolean denied = false;
    try { await(task); } catch (ExecutionException expected) { denied = true; }
    check(denied, label);
  }
  private static long change(TitleService service) throws Exception {
    long audit = await(service.history(ADMIN, 1000)).stream().filter(a -> a.action().equals("REMINDER")).findFirst().orElseThrow().id();
    return await(service.changes(ADMIN, audit)).get(0).id();
  }

  public static void main(String[] args) throws Exception {
    long joined = TimeUnit.SECONDS.toNanos(200), ready = joined + UnequippedTitleReminder.JOIN_DELAY;
    UnequippedTitleReminder timer = new UnequippedTitleReminder(joined);
    check(!timer.claim(ready - 1), "no reminder before ten seconds after admission");
    check(timer.claim(ready) && !timer.claim(ready), "first deadline is ten seconds and can fire only once");
    check(!timer.claim(ready + UnequippedTitleReminder.INTERVAL - 1)
        && timer.claim(ready + UnequippedTitleReminder.INTERVAL), "next deadline is thirty real minutes later");
    long delayed = ready + 5 * UnequippedTitleReminder.INTERVAL;
    check(timer.claim(delayed) && !timer.claim(delayed)
        && !timer.claim(delayed + UnequippedTitleReminder.INTERVAL - 1), "stalls never burst a backlog of reminders");
    check(!new UnequippedTitleReminder(delayed).claim(delayed + UnequippedTitleReminder.JOIN_DELAY - 1), "rejoining starts a new ten-second delay");
    DisplayPreferences oldPreference = JSON.fromJson("{head:false,chat:true,tab:false}", DisplayPreferences.class);
    check(oldPreference.reminderEnabled(), "missing old JSON field defaults to reminder enabled");
    var text = Text.unequippedReminder();
    check(text.getString().equals("您似乎还没有配置一个用于展示的称号,使用/mt打开个人称号面板,输入/mt reminder off以永久关闭该提示"), "reminder has the requested text and concrete permanent-off command");
    check(text.getStyle().getColor().getValue() == 0xf2c66d
        && text.getSiblings().get(1).getStyle().getColor().getValue() == 0x5dd4ed
        && text.getSiblings().get(3).getStyle().getColor().getValue() == 0x5dd4ed
        && !text.getStyle().isBold() && text.getSiblings().stream().noneMatch(v -> v.getStyle().isBold()),
        "commands are cyan, prose is gold/slate/green, and no part is bold");
    var dispatcher = new CommandDispatcher<CommandSourceStack>(); Commands.register(dispatcher);
    var command = dispatcher.getRoot().getChild("mt").getChild("reminder");
    check(command.getChild("off") != null && command.getChild("on") != null
        && command.getChild("off").getChildren().isEmpty(), "self-only reminder commands have no player or UUID target argument");

    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    String base = "jdbc:mariadb://127.0.0.1:43307/", suffix = UUID.randomUUID().toString().replace("-", "");
    List<String> names = List.of("mt_reminder_" + suffix, "mt_reminder_restore_" + suffix, "mt_reminder_legacy_" + suffix);
    Database root = new Database(new Database.Config(base, "root", password));
    try (var c = root.open()) { for (String name : names) Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4"); }
    Database db = new Database(new Database.Config(base + names.get(0), "root", password));
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    try {
      try (TitleService s = new TitleService(db)) {
        await(s.reconnect()); await(s.login(alice, "ReminderAlice")); await(s.login(bob, "ReminderBob"));
        check(s.snapshot().displayPreferences(alice).reminderEnabled() && UnequippedTitleReminder.shouldRemind(s.snapshot(), alice), "new registered player without a title starts with reminders enabled");
        await(s.toggleDisplayPosition(alice, DisplayPosition.HEAD));
        try (var c = db.open()) {
          Database.execute(c, "ALTER TABLE mt_display_preferences DROP COLUMN reminder_disabled");
          Database.execute(c, "DELETE FROM mt_schema WHERE version>=6");
        }
        await(s.reconnect()); await(s.reconnect());
        check(!s.snapshot().displayPreferences(alice).head() && s.snapshot().displayPreferences(alice).reminderEnabled(), "v5 migration preserves existing positions and enables reminders; repeated migration is safe");
        await(s.setUnequippedReminder(alice, false));
        check(!UnequippedTitleReminder.shouldRemind(s.snapshot(), alice)
            && s.snapshot().displayPreferences(bob).reminderEnabled(), "permanent off only affects the requesting UUID");
        long disabledChange = change(s);
        await(s.toggleDisplayPosition(alice, DisplayPosition.CHAT));
        await(s.undo(ADMIN, disabledChange));
        check(s.snapshot().displayPreferences(alice).reminderEnabled() && !s.snapshot().displayPreferences(alice).chat(), "undoing reminder change preserves a later independent display-position choice");
        await(s.setUnequippedReminder(alice, false)); long staleChange = change(s);
        await(s.setUnequippedReminder(alice, true));
        deny(s.undo(ADMIN, staleChange), "newer change to the same reminder preference blocks stale undo");
        await(s.setUnequippedReminder(alice, false));
        await(s.toggleDisplayPosition(alice, DisplayPosition.TAB));
        await(s.login(alice, "ReminderAliceRenamed"));
        try (TitleService restarted = new TitleService(db)) {
          await(restarted.reconnect());
          check(!restarted.snapshot().displayPreferences(alice).reminderEnabled(), "permanent off survives service restart, rejoin and player rename");
        }
        UUID unknown = UUID.randomUUID(); deny(s.setUnequippedReminder(unknown, false), "unknown player cannot be registered by a reminder command");
        check(!s.snapshot().players().containsKey(unknown), "denied preference update leaves no unauthenticated player record");
        await(s.saveTitle(ADMIN, new Title("reminder_title", RichText.plain("测试称号"), "", "", "", "", 0, 0, true, true, 0), 0));
        await(s.grant(ADMIN, List.of(alice), "reminder_title", "test")); await(s.equip(alice, "reminder_title"));
        await(s.setUnequippedReminder(alice, true));
        boolean matrix = true;
        for (boolean publicDisplay : List.of(false, true)) {
          await(s.privacy(alice, "reminder_title", false, publicDisplay)); Snapshot live = s.snapshot(); Settings settings = live.settings();
          for (int global = 0; global < 8; global++) for (int personal = 0; personal < 8; personal++) {
            Settings globals = new Settings(settings.reward(), (global & 1) != 0, (global & 2) != 0, (global & 4) != 0,
                settings.headScale(), settings.headOffset(), settings.chatPrefix(), settings.retentionDays(), settings.packName(), settings.packStartedOn());
            Snapshot sample = new Snapshot(live.titles(), live.players(), live.owned(), live.equipped(), live.admins(), live.receipts(), globals,
                Map.of(alice, new DisplayPreferences((personal & 1) != 0, (personal & 2) != 0, (personal & 4) != 0)));
            matrix &= sample.hasEquippedTitle(alice) && !UnequippedTitleReminder.shouldRemind(sample, alice);
          }
        }
        check(matrix, "all 128 privacy/global/personal visibility combinations remain worn and suppress reminders, including all outlets disabled");
        Snapshot live = s.snapshot();
        Snapshot stale = new Snapshot(live.titles(), live.players(), live.owned(), Map.of(alice, "missing"), live.admins(), live.receipts(), live.settings());
        check(!stale.hasEquippedTitle(alice) && UnequippedTitleReminder.shouldRemind(stale, alice), "invalid equipment IDs cannot hide the absence of a usable title");
        await(s.equip(alice, "")); check(UnequippedTitleReminder.shouldRemind(s.snapshot(), alice), "unequipping restores reminder eligibility");
        long noticeCount = await(s.notifications(alice)).size();
        await(s.setUnequippedReminder(alice, false)); await(s.setUnequippedReminder(alice, false));
        check(await(s.notifications(alice)).size() == noticeCount, "reminder choices never create fake grant/revoke notifications");
        Backup.Archive archive = await(s.export(ADMIN));
        check(archive.format() == 4, "new backups use format four so old readers cannot silently discard reminder preferences");
        Backup.Archive read = Backup.read(Backup.write(Path.of("build/reminder-tests", suffix), archive));
        try (TitleService restored = new TitleService(new Database(new Database.Config(base + names.get(1), "root", password)))) {
          await(restored.reconnect()); await(restored.restoreEmpty(ADMIN, read));
          check(!restored.snapshot().displayPreferences(alice).reminderEnabled()
              && restored.snapshot().displayPreferences().equals(s.snapshot().displayPreferences()), "compressed backup round-trip preserves off and all display choices");
          await(restored.undo(ADMIN, change(restored)));
          check(restored.snapshot().displayPreferences(alice).reminderEnabled(), "reminder selective undo survives backup and restoration");
        }
        Backup.Archive legacy = new Backup.Archive(3, archive.createdAt(), archive.titles(), archive.players(), archive.owned(), archive.equipped(), archive.admins(),
            archive.receipts(), archive.settings(), archive.audit(), new ChangeJournal.Export(List.of(), Map.of(), List.of()), archive.displayPreferences());
        JsonObject legacyJson = JSON.toJsonTree(legacy).getAsJsonObject();
        legacyJson.getAsJsonArray("displayPreferences").forEach(v -> v.getAsJsonObject().getAsJsonObject("preferences").remove("reminderDisabled"));
        try (TitleService restored = new TitleService(new Database(new Database.Config(base + names.get(2), "root", password)))) {
          await(restored.reconnect()); await(restored.restoreEmpty(ADMIN, JSON.fromJson(legacyJson, Backup.Archive.class)));
          check(restored.snapshot().displayPreferences(alice).reminderEnabled()
              && restored.snapshot().displayPreferences(alice).head() == s.snapshot().displayPreferences(alice).head(), "old format-three backup defaults reminders on while keeping existing positions");
        }
        var preview = await(s.deletePreview(ADMIN, alice)); await(s.setUnequippedReminder(alice, true));
        deny(s.deletePlayer(ADMIN, alice, preview.stamp(), "ReminderAliceRenamed"), "changing reminder preference invalidates an old player-erasure confirmation");
        await(s.setUnequippedReminder(alice, false)); preview = await(s.deletePreview(ADMIN, alice));
        await(s.deletePlayer(ADMIN, alice, preview.stamp(), "ReminderAliceRenamed"));
        long deleteAudit = await(s.history(ADMIN, 1000)).stream().filter(a -> a.action().equals("PLAYER_DELETE")).findFirst().orElseThrow().id();
        await(s.undo(ADMIN, await(s.changes(ADMIN, deleteAudit)).get(0).id()));
        check(!s.snapshot().displayPreferences(alice).reminderEnabled(), "player-erasure undo restores the permanent-off preference");
        await(s.migrateIdentity(ADMIN, alice, bob));
        check(!s.snapshot().displayPreferences(bob).reminderEnabled() && s.snapshot().displayPreferences(alice).reminderEnabled(), "explicit UUID migration moves reminder preferences without merging identities");
      }
      try (TitleService unavailable = new TitleService(db)) {
        long before = System.nanoTime(); CompletableFuture<Void> failure = unavailable.setUnequippedReminder(alice, false);
        check(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "unavailable database does not block the calling game thread");
        deny(failure, "unavailable database does not confirm a permanent preference write");
      }
      Files.createDirectories(Path.of("build/reports"));
      Files.writeString(Path.of("build/reports/unequipped-reminder.json"), JSON.toJson(Map.of("checks", checks, "passed", checks.size(),
          "visibilityMatrix", 128, "schema", 6, "backupFormat", 4, "productionModified", false)));
      System.out.println("REMINDER CHECKS PASSED: " + checks.size());
    } finally { try (var c = root.open()) { for (String name : names) Database.execute(c, "DROP DATABASE IF EXISTS " + name); } }
  }
}
