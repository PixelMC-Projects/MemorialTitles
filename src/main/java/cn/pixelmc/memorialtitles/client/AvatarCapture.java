package cn.pixelmc.memorialtitles.client;

import cn.pixelmc.memorialtitles.forge.Network;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Base64;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;

/** Reads the player's basic skin only when the authenticated server requests a fallback. */
public final class AvatarCapture {
  private static String pending;
  private static long next, expires;
  private static byte[] previous;

  public static void request(String nonce) {
    pending = nonce;
    next = System.currentTimeMillis() + 3000;
    expires = next + 30000;
  }

  public static byte[] lastSent() {
    return previous == null ? null : previous.clone();
  }

  public static void tick(Minecraft mc) {
    if (mc.getConnection() == null) {
      pending = null;
      previous = null;
      return;
    }
    if (mc.player == null) return;
    long now = System.currentTimeMillis();
    if (pending == null || now < next) return;
    if (now > expires) {
      pending = null;
      return;
    }
    next = now + 2000;
    int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
    try {
      var texture = mc.getTextureManager().getTexture(mc.player.getSkin().texture());
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getId());
      int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
      int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
      if (w < 64 || w > 1024 || w % 64 != 0 || h < w / 2 || h > 1024) return;
      try (var skin = new NativeImage(w, h, false);
          var head = new NativeImage(8, 8, false)) {
        skin.downloadTexture(0, false);
        int scale = w / 64;
        for (int y = 0; y < 8; y++)
          for (int x = 0; x < 8; x++) {
            int base = skin.getPixelRGBA((8 + x) * scale, (8 + y) * scale);
            int hat = skin.getPixelRGBA((40 + x) * scale, (8 + y) * scale),
                alpha = hat >>> 24,
                color = 0xff000000;
            for (int shift : new int[] {0, 8, 16})
              color |=
                  ((((hat >> shift) & 255) * alpha + ((base >> shift) & 255) * (255 - alpha) + 127)
                          / 255)
                      << shift;
            head.setPixelRGBA(x, y, color);
          }
        byte[] bytes = head.asByteArray();
        var data = new JsonObject();
        data.addProperty("nonce", pending);
        data.addProperty("png", Base64.getEncoder().encodeToString(bytes));
        Network.request("avatar", data);
        previous = bytes;
        pending = null;
      }
    } catch (Exception ignored) {
      /* A later short retry or the durable/default cache remains available. */
    } finally {
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, binding);
    }
  }

  private AvatarCapture() {}
}
