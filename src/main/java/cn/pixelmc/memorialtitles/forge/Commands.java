package cn.pixelmc.memorialtitles.forge;

import static cn.pixelmc.memorialtitles.domain.Model.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.minecraft.commands.Commands.*;

import cn.pixelmc.memorialtitles.service.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public final class Commands {
  public static boolean realConsole(CommandSourceStack source) {
    // Entity-free RCON, command blocks and player /execute stacks are not the server console.
    return source.source == source.getServer()
        && source.getEntity() == null
        && source.hasPermission(4)
        && source.getTextName().equals("Server");
  }

  private static TitleService.Actor who(CommandSourceStack s) {
    if (realConsole(s)) return TitleService.Actor.consoleActor();
    if (s.getPlayer() == null) throw new SecurityException("仅真实控制台或已授权玩家可用");
    return TitleService.Actor.player(s.getPlayer().getUUID());
  }

  private static ServerRuntime runtime() {
    ServerRuntime r = ServerRuntime.current;
    if (r == null || r.service == null) throw new IllegalStateException("称号服务未就绪");
    return r;
  }

  private static boolean management(CommandSourceStack s) {
    ServerRuntime r = ServerRuntime.current;
    return realConsole(s)
        || (r != null
            && r.service != null
            && s.getPlayer() != null
            && r.service.snapshot().admins().contains(s.getPlayer().getUUID()));
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    var root =
        literal("mt")
            .executes(
                c -> {
                  TitleMenu.open(
                      c.getSource().getPlayerOrException(),
                      "collection",
                      c.getSource().getPlayerOrException().getUUID(),
                      "",
                      "order",
                      "all",
                      0);
                  return 1;
                });
    root.then(
        literal("catalogue")
            .executes(
                c -> {
                  var p = c.getSource().getPlayerOrException();
                  TitleMenu.open(p, "catalogue", p.getUUID(), "", "order", "all", 0);
                  return 1;
                }));
    root.then(
        literal("view")
            .then(
                argument("player", word())
                    .suggests(
                        (c, b) ->
                            SharedSuggestionProvider.suggest(
                                runtime().service.snapshot().players().values().stream()
                                    .map(Profile::name),
                                b))
                    .executes(
                        c -> {
                          TitleMenu.open(
                              c.getSource().getPlayerOrException(),
                              "collection",
                              resolve(getString(c, "player")),
                              "",
                              "order",
                              "all",
                              0);
                          return 1;
                        })));
    root.then(
        literal("admin")
            .requires(Commands::management)
            .executes(
                c -> {
                  Network.admin(c.getSource().getPlayerOrException());
                  return 1;
                }));
    root.then(
        literal("web")
            .requires(Commands::management)
            .executes(
                c -> {
                  Network.admin(c.getSource().getPlayerOrException());
                  return 1;
                }));
    root.then(
        literal("equip")
            .then(
                titleArg()
                    .executes(
                        c ->
                            queue(
                                c.getSource(),
                                runtime()
                                    .service
                                    .equip(
                                        c.getSource().getPlayerOrException().getUUID(),
                                        getString(c, "title"))))));
    root.then(
        literal("unequip")
            .executes(
                c ->
                    queue(
                        c.getSource(),
                        runtime()
                            .service
                            .equip(c.getSource().getPlayerOrException().getUUID(), ""))));
    root.then(
        literal("privacy")
            .then(
                titleArg()
                    .then(
                        argument(
                                "collection",
                                com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .then(
                                argument(
                                        "display",
                                        com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                    .executes(
                                        c ->
                                            queue(
                                                c.getSource(),
                                                runtime()
                                                    .service
                                                    .privacy(
                                                        c.getSource()
                                                            .getPlayerOrException()
                                                            .getUUID(),
                                                        getString(c, "title"),
                                                        com.mojang.brigadier.arguments
                                                            .BoolArgumentType.getBool(
                                                            c, "collection"),
                                                        com.mojang.brigadier.arguments
                                                            .BoolArgumentType.getBool(
                                                            c, "display"))))))));
    for (boolean grant : new boolean[] {true, false}) {
      var reason =
          argument("reason", greedyString())
              .executes(
                  c -> {
                    var source = c.getSource();
                    var ids = targets(getString(c, "players"));
                    var actor = who(source);
                    String title = getString(c, "title"), why = getString(c, "reason");
                    return queue(
                        source,
                        grant
                            ? runtime().service.grant(actor, ids, title, why)
                            : runtime().service.revoke(actor, ids, title, why));
                  });
      root.then(
          literal(grant ? "grant" : "revoke")
              .requires(Commands::management)
              .then(argument("players", word()).then(titleArg().then(reason))));
    }
    root.then(
        literal("define")
            .requires(Commands::management)
            .then(
                argument("id", word())
                    .then(
                        argument("name", greedyString())
                            .executes(
                                c -> {
                                  Title old =
                                      runtime().service.snapshot().titles().get(getString(c, "id"));
                                  Title t =
                                      new Title(
                                          getString(c, "id"),
                                          RichText.plain(getString(c, "name")),
                                          old == null ? "" : old.description(),
                                          old == null ? "" : old.source(),
                                          old == null ? "管理员发放" : old.acquisition(),
                                          old == null ? "" : old.category(),
                                          old == null ? 0 : old.order(),
                                          old == null ? 0 : old.icon(),
                                          old == null || old.visible(),
                                          old == null || old.issuable(),
                                          old == null ? 0 : old.revision());
                                  return save(c, t);
                                }))));
    root.then(
        literal("style")
            .requires(Commands::management)
            .then(
                titleArg()
                    .then(
                        argument("json", greedyString())
                            .executes(
                                c -> {
                                  Title t =
                                      Objects.requireNonNull(
                                          runtime()
                                              .service
                                              .snapshot()
                                              .titles()
                                              .get(getString(c, "title")),
                                          "称号不存在");
                                  var j = JSON.toJsonTree(t).getAsJsonObject();
                                  j.add(
                                      "name",
                                      JSON.toJsonTree(
                                          JSON.fromJson(getString(c, "json"), RichText.class)));
                                  return save(c, JSON.fromJson(j, Title.class));
                                }))));
    root.then(
        literal("set")
            .requires(Commands::management)
            .then(
                titleArg()
                    .then(
                        argument("field", word())
                            .then(
                                argument("value", greedyString())
                                    .executes(
                                        c -> {
                                          Title t =
                                              Objects.requireNonNull(
                                                  runtime()
                                                      .service
                                                      .snapshot()
                                                      .titles()
                                                      .get(getString(c, "title")));
                                          String field = getString(c, "field"),
                                              value = getString(c, "value");
                                          if (!Set.of(
                                                  "description",
                                                  "source",
                                                  "acquisition",
                                                  "category",
                                                  "order",
                                                  "icon",
                                                  "visible",
                                                  "issuable")
                                              .contains(field))
                                            throw new IllegalArgumentException("字段无效");
                                          var j = JSON.toJsonTree(t).getAsJsonObject();
                                          if (Set.of("order", "icon").contains(field))
                                            j.addProperty(field, Integer.parseInt(value));
                                          else if (Set.of("visible", "issuable").contains(field)) {
                                            if (!value.equals("true") && !value.equals("false"))
                                              throw new IllegalArgumentException("需要true/false");
                                            j.addProperty(field, Boolean.parseBoolean(value));
                                          } else j.addProperty(field, value);
                                          return save(c, JSON.fromJson(j, Title.class));
                                        })))));
    root.then(
        literal("reward")
            .requires(Commands::management)
            .then(
                argument("titleOrNone", word())
                    .executes(
                        c -> {
                          Settings old = runtime().service.snapshot().settings();
                          String id = getString(c, "titleOrNone");
                          return changed(
                              c.getSource(),
                              runtime()
                                  .service
                                  .settings(
                                      who(c.getSource()),
                                      new Settings(
                                          id.equals("none") ? "" : id,
                                          old.head(),
                                          old.chat(),
                                          old.tab(),
                                          old.headScale(),
                                          old.headOffset(),
                                          old.chatPrefix(),
                                          old.retentionDays(),
                                          old.packName(),
                                          old.packStartedOn())));
                        })));
    root.then(
        literal("settings")
            .requires(Commands::management)
            .then(
                argument("json", greedyString())
                    .executes(
                        c ->
                            changed(
                                c.getSource(),
                                runtime()
                                    .service
                                    .settings(
                                        who(c.getSource()),
                                        JSON.fromJson(getString(c, "json"), Settings.class))))));
    root.then(
        literal("reload")
            .requires(Commands::management)
            .executes(c -> queue(c.getSource(), runtime().reload(who(c.getSource())))));
    root.then(
        literal("configexport")
            .requires(Commands::management)
            .executes(
                c ->
                    queue(
                        c.getSource(),
                        runtime()
                            .service
                            .background(
                                () -> {
                                  TitleService.requireAdmin(
                                      runtime().service.snapshot(), who(c.getSource()));
                                  runtime().files.exportDefinitions(runtime().service.snapshot());
                                  return "definitions.json";
                                }))));
    root.then(
        literal("backup")
            .requires(Commands::management)
            .executes(c -> queue(c.getSource(), runtime().backup(who(c.getSource())))));
    root.then(
        literal("history")
            .requires(Commands::management)
            .executes(
                c -> queue(c.getSource(), runtime().service.history(who(c.getSource()), 20))));
    root.then(
        literal("health")
            .requires(Commands::management)
            .executes(
                c -> {
                  var s = runtime().service;
                  c.getSource()
                      .sendSuccess(
                          () ->
                              Component.literal(
                                  "数据库："
                                      + (s.healthy() ? "在线" : "离线/缓存只读")
                                      + "；最近刷新："
                                      + s.lastSuccess()),
                          false);
                  return 1;
                }));
    root.then(
        literal("importpreview")
            .requires(Commands::management)
            .then(
                argument("file", word())
                    .executes(
                        c -> {
                          Path file = backupFile(getString(c, "file"));
                          return queue(
                              c.getSource(),
                              runtime()
                                  .service
                                  .background(() -> Backup.read(file))
                                  .thenCompose(
                                      a -> runtime().service.importPreview(who(c.getSource()), a)));
                        })));
    dispatcher.register(root);
    dispatcher.register(
        literal("mtconsole")
            .requires(Commands::realConsole)
            .then(
                literal("web-user")
                    .then(
                        argument("player", word())
                            .then(
                                argument("username", word())
                                    .executes(
                                        c -> {
                                          var r = runtime();
                                          if (r.web == null)
                                            throw new IllegalStateException("网页服务未启动");
                                          UUID id = resolve(getString(c, "player"));
                                          String username = getString(c, "username");
                                          return queue(
                                              c.getSource(),
                                              r.service.background(
                                                  () -> {
                                                    if (!r.service.snapshot().admins().contains(id))
                                                      throw new IllegalArgumentException(
                                                          "请先用 mtconsole grant 授予该玩家称号管理权");
                                                    String password =
                                                        cn.pixelmc.memorialtitles.web.AccountStore
                                                            .randomPassword();
                                                    r.web.accounts.create(username, id, password);
                                                    return "网页账号："
                                                        + cn.pixelmc.memorialtitles.web.AccountStore
                                                            .normalize(username)
                                                        + "；初始密码："
                                                        + password
                                                        + "；访问 "
                                                        + r.web.address()
                                                        + "。密码仅此处显示，可在网页修改。";
                                                  }));
                                        }))))
            .then(
                literal("web-password")
                    .then(
                        argument("username", word())
                            .executes(
                                c -> {
                                  var r = runtime();
                                  if (r.web == null) throw new IllegalStateException("网页服务未启动");
                                  String username = getString(c, "username");
                                  return queue(
                                      c.getSource(),
                                      r.service.background(
                                          () -> {
                                            String password =
                                                cn.pixelmc.memorialtitles.web.AccountStore
                                                    .randomPassword();
                                            r.web.accounts.password(username, password);
                                            return "新密码：" + password + "；旧会话已失效。";
                                          }));
                                })))
            .then(
                literal("web-delete")
                    .then(
                        argument("username", word())
                            .executes(
                                c -> {
                                  var r = runtime();
                                  if (r.web == null) throw new IllegalStateException("网页服务未启动");
                                  String username = getString(c, "username");
                                  return queue(
                                      c.getSource(),
                                      r.service.background(
                                          () -> {
                                            r.web.accounts.delete(username);
                                            return "网页账号已移除";
                                          }));
                                })))
            .then(
                literal("web-list")
                    .executes(
                        c -> {
                          var r = runtime();
                          if (r.web == null) throw new IllegalStateException("网页服务未启动");
                          c.getSource()
                              .sendSuccess(
                                  () -> Component.literal(String.join("\n", r.web.accounts.list())),
                                  false);
                          return 1;
                        }))
            .then(
                literal("grant")
                    .then(
                        argument("player", word())
                            .executes(
                                c ->
                                    queue(
                                        c.getSource(),
                                        runtime()
                                            .service
                                            .authorize(
                                                who(c.getSource()),
                                                resolve(getString(c, "player")),
                                                true)))))
            .then(
                literal("revoke")
                    .then(
                        argument("player", word())
                            .executes(
                                c ->
                                    queue(
                                        c.getSource(),
                                        runtime()
                                            .service
                                            .authorize(
                                                who(c.getSource()),
                                                resolve(getString(c, "player")),
                                                false)))))
            .then(
                literal("list")
                    .executes(
                        c -> {
                          c.getSource()
                              .sendSuccess(
                                  () ->
                                      Component.literal(
                                          runtime().service.snapshot().admins().toString()),
                                  false);
                          return 1;
                        }))
            .then(
                literal("migrate")
                    .then(
                        argument("from", word())
                            .then(
                                argument("to", word())
                                    .executes(
                                        c ->
                                            queue(
                                                c.getSource(),
                                                runtime()
                                                    .service
                                                    .migrateIdentity(
                                                        who(c.getSource()),
                                                        resolve(getString(c, "from")),
                                                        resolve(getString(c, "to"))))))))
            .then(
                literal("restore-empty")
                    .then(
                        argument("file", word())
                            .then(
                                literal("CONFIRM-INCLUDE-ADMIN-GRANTS")
                                    .executes(
                                        c ->
                                            queue(
                                                c.getSource(),
                                                runtime()
                                                    .service
                                                    .background(
                                                        () ->
                                                            Backup.read(
                                                                backupFile(getString(c, "file"))))
                                                    .thenCompose(
                                                        a ->
                                                            runtime()
                                                                .service
                                                                .restoreEmpty(
                                                                    who(c.getSource()), a))))))));
  }

  private static RequiredArgumentBuilder<CommandSourceStack, String> titleArg() {
    return argument("title", word())
        .suggests(
            (c, b) ->
                SharedSuggestionProvider.suggest(
                    runtime()
                        .service
                        .snapshot()
                        .catalogue(
                            c.getSource().getPlayer() == null
                                ? new UUID(0, 0)
                                : c.getSource().getPlayer().getUUID())
                        .stream()
                        .map(Title::id),
                    b));
  }

  private static int save(CommandContext<CommandSourceStack> c, Title title) {
    return changed(
        c.getSource(), runtime().service.saveTitle(who(c.getSource()), title, title.revision()));
  }

  private static int changed(CommandSourceStack source, CompletableFuture<?> result) {
    result.thenRun(() -> runtime().saveDefinitions());
    return queue(source, result);
  }

  public static UUID resolve(String text) {
    Snapshot s = runtime().service.snapshot();
    try {
      UUID id = UUID.fromString(text);
      if (s.players().containsKey(id)) return id;
    } catch (IllegalArgumentException ignored) {
    }
    List<UUID> ids =
        s.players().values().stream()
            .filter(p -> p.name().equalsIgnoreCase(text))
            .map(Profile::id)
            .toList();
    if (ids.size() != 1) throw new IllegalArgumentException("玩家不存在或同名身份不唯一，请指定已登记UUID：" + text);
    return ids.get(0);
  }

  public static List<UUID> targets(String text) {
    if (text.equals("*")) return List.copyOf(runtime().service.snapshot().players().keySet());
    return Arrays.stream(text.split(",")).map(Commands::resolve).toList();
  }

  private static Path backupFile(String file) {
    if (!file.matches("[a-zA-Z0-9_.-]+\\.json\\.gz"))
      throw new IllegalArgumentException("只接受备份目录内的文件名");
    return Path.of(runtime().files.config.backups()).resolve(file);
  }

  public static int queue(CommandSourceStack source, CompletableFuture<?> future) {
    source.sendSuccess(() -> Component.literal("请求已提交，等待数据库确认…"), false);
    future.whenComplete(
        (v, error) ->
            source
                .getServer()
                .execute(
                    () -> {
                      if (error != null)
                        source.sendFailure(Component.literal(ServerRuntime.safeError(error)));
                      else
                        source.sendSuccess(
                            () -> Component.literal("已完成" + (v == null ? "" : "：" + v)), false);
                    }));
    return 1;
  }

  private Commands() {}
}
