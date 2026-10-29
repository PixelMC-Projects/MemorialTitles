package cn.pixelmc.memorialtitles;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.forge.ServerFiles;
import cn.pixelmc.memorialtitles.persistence.Database;
import cn.pixelmc.memorialtitles.service.TitleService;
import cn.pixelmc.memorialtitles.web.WebConfig;
import java.nio.file.*;
import java.util.*;

/** Disposable, loopback-only Forge fixture. No production or user client connections. */
public final class DisplayPositionsRuntimeFixture {
  public static final UUID PLAYER = UUID.fromString("192924d9-f626-416a-8ba8-c31d998d40ef");
  public static void main(String[] args) throws Exception {
    Path run = Path.of("run-display-locations").toAbsolutePath();
    Path config = run.resolve("config/memorialtitles");
    String password = Objects.requireNonNull(System.getenv("MT_TEST_PASSWORD"));
    if (args.length == 1 && args[0].equals("cleanup")) {
      String previous = Files.readString(run.resolve("fixture-db.txt")).trim();
      if (!previous.matches("mt_positions_runtime_[0-9a-f]{12}")) throw new IllegalStateException("Unexpected fixture database");
      Database root = new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43321/", "root", password));
      try (var c = root.open()) { Database.execute(c, "DROP DATABASE IF EXISTS " + previous); }
      System.out.println("DISPLAY_POSITIONS_RUNTIME_FIXTURE_REMOVED");
      return;
    }
    String name = "mt_positions_runtime_" + UUID.randomUUID().toString().replace("-", "").substring(0,12);
    Database root = new Database(new Database.Config("jdbc:mariadb://127.0.0.1:43321/", "root", password));
    try (var c = root.open()) { Database.execute(c, "CREATE DATABASE " + name + " CHARACTER SET utf8mb4"); }
    var database = new Database.Config("jdbc:mariadb://127.0.0.1:43321/" + name, "root", password);
    try (TitleService service = new TitleService(new Database(database))) {
      service.reconnect().get(); service.login(PLAYER, "PositionTest").get();
      for (int n = 0; n < 45; n++) {
        String id = String.format(Locale.ROOT, "position_%02d", n);
        service.saveTitle(TitleService.Actor.consoleActor(), new Title(id, RichText.plain("测试称号" + n),
            "称号详情保留", "local fixture", "", "test", 0, n, true, true, 0), 0).get();
        service.grant(TitleService.Actor.consoleActor(), List.of(PLAYER), id, "local fixture").get();
      }
      service.equip(PLAYER, "position_00").get();
    }
    Files.createDirectories(config);
    Files.writeString(config.resolve("server.json"), JSON.toJson(new ServerFiles.Config(database, run.resolve("backups").toString())));
    Files.writeString(config.resolve("web.json"), JSON.toJson(new WebConfig(false, "127.0.0.1", 18780, "", 60, List.of())));
    Files.writeString(run.resolve("eula.txt"), "eula=true\n");
    Files.writeString(run.resolve("server.properties"), "server-ip=127.0.0.1\nserver-port=25620\nonline-mode=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\nspawn-protection=0\n");
    Files.writeString(run.resolve("fixture-db.txt"), name);
    System.out.println("DISPLAY_POSITIONS_RUNTIME_FIXTURE_READY");
  }
}
