package cn.pixelmc.memorialtitles.web;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** Tests source identity, background login and durable official-skin cache behavior. */
public final class AvatarIntegrationSuite {
  private static final List<String> checks = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    Path folder = Path.of("build/avatar-it/" + UUID.randomUUID());
    UUID id = UUID.randomUUID(), offline = UUID.nameUUIDFromBytes("offline-avatar-test".getBytes());
    var skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
    for (int y = 8; y < 16; y++) for (int x = 8; x < 16; x++) skin.setRGB(x, y, 0xff123456);
    skin.setRGB(40, 8, 0xffffaa00);
    var head = AvatarStore.compose(skin);
    check(
        head.getRGB(0, 0) == 0xffffaa00 && head.getRGB(1, 1) == 0xff123456,
        "face and hat composition");
    byte[] png = png(skin);
    var fixture = new Fixture(png);
    byte[] cached;
    try (var avatars = new AvatarStore(folder, fixture::fetch)) {
      byte[] defaultHead = avatars.head(offline);
      avatars.login(offline);
      idle(avatars);
      check(
          AvatarStore.decode(defaultHead, 8, true).getWidth() == 8 && fixture.profiles.get() == 0,
          "offline UUID uses default without lookup");
      // Old development caches may have come from a client replacement skin.
      Files.createDirectories(folder);
      Files.write(folder.resolve(offline + ".png"), png(head));
      check(
          Arrays.equals(defaultHead, avatars.head(offline)),
          "legacy mixed-source cache is ignored");

      fixture.entered = new CountDownLatch(1);
      fixture.release = new CountDownLatch(1);
      var call = CompletableFuture.runAsync(() -> avatars.login(id));
      try {
        call.get(1, TimeUnit.SECONDS);
        check(
            fixture.entered.await(1, TimeUnit.SECONDS) && avatars.version(id) == 0,
            "login returns while skin download is pending");
      } finally {
        fixture.release.countDown();
      }
      idle(avatars);
      cached = avatars.head(id);
      check(
          AvatarStore.decode(cached, 8, true).getRGB(0, 0) == 0xffffaa00
              && Files.exists(folder.resolve("mojang/" + id + ".png")),
          "official head persists on server");
      long version = avatars.version(id);
      avatars.login(id);
      idle(avatars);
      check(avatars.version(id) == version, "same official skin keeps cache version");

      fixture.fail = true;
      avatars.login(id);
      idle(avatars);
      check(Arrays.equals(cached, avatars.head(id)), "service failure keeps last official head");
      fixture.fail = false;
      fixture.url = "https://example.com/texture/" + "b".repeat(64);
      int textures = fixture.textures.get();
      avatars.login(id);
      idle(avatars);
      check(
          fixture.textures.get() == textures && Arrays.equals(cached, avatars.head(id)),
          "non-Mojang texture URL is rejected");
      fixture.url = "http://textures.minecraft.net/texture/" + "a".repeat(64);
      fixture.wrongId = true;
      avatars.login(id);
      idle(avatars);
      check(Arrays.equals(cached, avatars.head(id)), "different profile identity is rejected");
      fixture.wrongId = false;
      fixture.image = new byte[] {1, 2, 3};
      avatars.login(id);
      idle(avatars);
      check(Arrays.equals(cached, avatars.head(id)), "invalid skin image keeps official cache");
      fixture.image = png;
    }
    try (var avatars = new AvatarStore(folder, fixture::fetch)) {
      check(Arrays.equals(cached, avatars.head(id)), "official cache survives restart and logout");
      skin.setRGB(9, 9, 0xff67e8f9);
      fixture.image = png(skin);
      avatars.login(id);
      idle(avatars);
      check(
          AvatarStore.decode(avatars.head(id), 8, true).getRGB(1, 1) == 0xff67e8f9,
          "next login refreshes changed Mojang skin");
      fixture.defaultSkin = true;
      avatars.login(id);
      idle(avatars);
      try (var input =
          AvatarStore.class.getResourceAsStream(
              "/web/default-heads/" + Math.floorMod(id.hashCode(), 18) + ".png")) {
        var expected = AvatarStore.decode(input.readAllBytes(), 8, true);
        var actual = AvatarStore.decode(avatars.head(id), 8, true);
        check(
            Arrays.equals(
                expected.getRGB(0, 0, 8, 8, null, 0, 8), actual.getRGB(0, 0, 8, 8, null, 0, 8)),
            "removing official custom skin restores UUID default");
      }
    }
    Path fallbackFolder = Path.of("build/avatar-it/" + UUID.randomUUID());
    var nonce = new AtomicReference<String>();
    fixture.defaultSkin = false;
    fixture.fail = false;
    String clientHead = Base64.getEncoder().encodeToString(png(head));
    byte[] clientCached;
    try (var avatars =
        new AvatarStore(fallbackFolder, fixture::fetch, (player, token) -> nonce.set(token))) {
      avatars.login(id);
      idle(avatars);
      check(nonce.get() == null, "official success never requests client fallback");
      byte[] official = avatars.head(id);
      fixture.fail = true;
      avatars.login(id);
      idle(avatars);
      check(
          nonce.get() != null && Arrays.equals(official, avatars.head(id)),
          "official failure requests fallback while keeping cache");
      avatars.uploadFallback(id, "wrong request", clientHead);
      idle(avatars);
      check(
          Arrays.equals(official, avatars.head(id)),
          "unrequested client upload cannot overwrite official head");
      avatars.uploadFallback(UUID.randomUUID(), nonce.get(), clientHead);
      idle(avatars);
      check(
          Arrays.equals(official, avatars.head(id)),
          "fallback request is bound to its player UUID");
      avatars.uploadFallback(id, nonce.get(), clientHead);
      idle(avatars);
      clientCached = avatars.head(id);
      check(
          AvatarStore.decode(clientCached, 8, true).getRGB(1, 1) == 0xff123456
              && Files.exists(fallbackFolder.resolve("client/" + id + ".png")),
          "requested client fallback becomes durable current head");
      avatars.uploadFallback(id, nonce.get(), Base64.getEncoder().encodeToString(png(skin)));
      idle(avatars);
      check(
          Arrays.equals(clientCached, avatars.head(id)),
          "consumed fallback request cannot be reused");
    }
    try (var avatars =
        new AvatarStore(fallbackFolder, fixture::fetch, (player, token) -> nonce.set(token))) {
      check(
          Arrays.equals(clientCached, avatars.head(id)),
          "client fallback selection survives restart");
      fixture.fail = false;
      avatars.login(id);
      idle(avatars);
      check(
          AvatarStore.decode(avatars.head(id), 8, true).getRGB(1, 1) == 0xff67e8f9,
          "official service recovery replaces client fallback");
      fixture.fail = true;
      avatars.login(id);
      idle(avatars);
      String stale = nonce.get();
      avatars.logout(id);
      avatars.uploadFallback(id, stale, clientHead);
      idle(avatars);
      check(
          AvatarStore.decode(avatars.head(id), 8, true).getRGB(1, 1) == 0xff67e8f9,
          "logout invalidates outstanding fallback request");
    }
    Path pendingFolder = Path.of("build/avatar-it/" + UUID.randomUUID());
    fixture.fail = false;
    fixture.entered = new CountDownLatch(1);
    fixture.release = new CountDownLatch(1);
    try (var avatars = new AvatarStore(pendingFolder, fixture::fetch)) {
      avatars.login(id);
      try {
        check(fixture.entered.await(1, TimeUnit.SECONDS), "official fetch is in flight before cleanup");
        avatars.swap(id, JsonNull.INSTANCE, JsonNull.INSTANCE);
      } finally {
        fixture.release.countDown();
      }
      idle(avatars);
      check(avatars.read(id).isJsonNull(), "cleanup prevents in-flight download resurrecting avatar");
      avatars.playerPresent(id, true);
      avatars.login(id);
      idle(avatars);
      check(!avatars.read(id).isJsonNull(), "authenticated re-login can refresh avatar after cleanup");
    }
    var report = new JsonObject();
    report.addProperty("result", "PASS");
    report.addProperty("source", "Mojang first, authenticated requested client fallback");
    report.addProperty("checks", checks.size());
    report.add("details", new Gson().toJsonTree(checks));
    Files.writeString(
        Path.of("build/reports/avatar-integration.json"),
        new GsonBuilder().setPrettyPrinting().create().toJson(report));
    System.out.println(
        "MEMORIALTITLES_AVATAR_PASS " + checks.size() + " source and fallback checks");
  }

  private static void check(boolean passed, String detail) {
    if (!passed) throw new AssertionError(detail);
    checks.add(detail);
  }

  private static byte[] png(BufferedImage image) throws Exception {
    var output = new ByteArrayOutputStream();
    ImageIO.write(image, "png", output);
    return output.toByteArray();
  }

  private static void idle(AvatarStore avatars) throws Exception {
    var field = AvatarStore.class.getDeclaredField("worker");
    field.setAccessible(true);
    ((ExecutorService) field.get(avatars)).submit(() -> {}).get(4, TimeUnit.SECONDS);
  }

  private static final class Fixture {
    final AtomicInteger profiles = new AtomicInteger(), textures = new AtomicInteger();
    byte[] image;
    String url = "http://textures.minecraft.net/texture/" + "a".repeat(64);
    boolean fail, wrongId, defaultSkin;
    CountDownLatch entered, release;

    Fixture(byte[] image) {
      this.image = image;
    }

    byte[] fetch(URI uri, int limit) throws Exception {
      if (uri.getHost().equals("sessionserver.mojang.com")) {
        profiles.incrementAndGet();
        if (entered != null) entered.countDown();
        if (release != null && !release.await(3, TimeUnit.SECONDS))
          throw new IOException("blocked fixture");
        if (fail) throw new IOException("skin service unavailable");
        String id =
            wrongId
                ? UUID.randomUUID().toString().replace("-", "")
                : uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
        var payload = new JsonObject();
        payload.addProperty("profileId", id);
        var data = new JsonObject();
        if (!defaultSkin) {
          var skin = new JsonObject();
          skin.addProperty("url", url);
          data.add("SKIN", skin);
        }
        payload.add("textures", data);
        var profile = new JsonObject();
        profile.addProperty("id", id);
        var properties = new JsonArray();
        var value = new JsonObject();
        value.addProperty("name", "textures");
        value.addProperty(
            "value",
            Base64.getEncoder()
                .encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8)));
        properties.add(value);
        profile.add("properties", properties);
        return profile.toString().getBytes(StandardCharsets.UTF_8);
      }
      if (!uri.getHost().equals("textures.minecraft.net") || !uri.getScheme().equals("https"))
        throw new AssertionError("Unexpected skin request " + uri);
      textures.incrementAndGet();
      return image;
    }
  }
}
