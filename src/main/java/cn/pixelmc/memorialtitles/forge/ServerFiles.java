package cn.pixelmc.memorialtitles.forge;

import static cn.pixelmc.memorialtitles.domain.Model.*;

import cn.pixelmc.memorialtitles.persistence.Database;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class ServerFiles {
  public record Config(Database.Config database, String backups, String mode) {
    public Config(Database.Config database,String backups){this(database,backups,"database");}
    public boolean remote(){return "manager".equals(mode);}
  }

  public record Definitions(int format, List<Title> titles, Settings settings) {}

  public final Path folder;
  public final Config config;

  public ServerFiles(Path root) throws IOException {
    folder = root.resolve("config/memorialtitles");
    Files.createDirectories(folder);
    Path path = folder.resolve("server.json");
    if (!Files.exists(path))
      atomic(
          path,
          JSON.toJson(
              new Config(
                  new Database.Config(
                      "jdbc:mariadb://127.0.0.1:3306/memorialtitles",
                      "memorialtitles",
                      "CHANGE_ME"),
                  root.toAbsolutePath()
                      .getParent()
                      .resolve("memorialtitles-data/backups")
                      .toString())));
    try (Reader reader = Files.newBufferedReader(path)) {
      config = JSON.fromJson(reader, Config.class);
    }
    if (config == null || config.database == null || config.backups == null)
      throw new IOException("server.json 配置不完整");
  }

  public Definitions readDefinitions() throws IOException {
    Path path = folder.resolve("definitions.json");
    if (Files.size(path) > 8 * 1024 * 1024) throw new IOException("定义配置超过8MiB");
    try (Reader reader = Files.newBufferedReader(path)) {
      Definitions d = JSON.fromJson(reader, Definitions.class);
      if (d == null || d.format != 1 || d.titles == null || d.settings == null)
        throw new IOException("定义配置格式无效");
      return d;
    }
  }

  public void exportDefinitions(Snapshot s) throws IOException {
    atomic(
        folder.resolve("definitions.json"),
        JSON.toJson(
            new Definitions(
                1,
                s.titles().values().stream().sorted(Comparator.comparing(Title::id)).toList(),
                s.settings())));
  }

  public static void atomic(Path path, String text) throws IOException {
    Files.createDirectories(path.toAbsolutePath().getParent());
    Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(tmp, text);
    try {
      Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
