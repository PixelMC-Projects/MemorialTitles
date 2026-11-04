package cn.pixelmc.memorialtitles.domain;

import static cn.pixelmc.memorialtitles.domain.Model.JSON;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Canonical preset file is also imported by the web editor; clients receive resolved RGB. */
public final class TitlePresentation {
  private static final JsonObject PRESETS = load();
  private static JsonObject load() {
    try (InputStream input = TitlePresentation.class.getResourceAsStream("/titlePresentationPresets.json")) {
      if (input == null) throw new IOException("Missing title presentation presets");
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (IOException error) { throw new ExceptionInInitializerError(error); }
  }
  private static JsonObject find(String group, String id) {
    for (JsonElement item : PRESETS.getAsJsonArray(group)) {
      JsonObject value = item.getAsJsonObject();
      if (value.get("id").getAsString().equals(id)) return value;
    }
    throw new IllegalArgumentException("未知的称号卡片预设: " + group);
  }
  public static void validate(String badge, String rarity, String theme) {
    find("badges", badge); find("rarities", rarity); find("themes", theme);
  }
  public record Palette(int accent, int light, int rarityColor, String rarityLabel) {}
  public static Palette resolve(Model.Presentation presentation) {
    JsonObject theme = find("themes", presentation.theme()), rarity = find("rarities", presentation.rarity());
    return new Palette(theme.get("accent").getAsInt(), theme.get("light").getAsInt(),
        rarity.get("color").getAsInt(), rarity.get("label").getAsString());
  }
  private TitlePresentation() {}
}
