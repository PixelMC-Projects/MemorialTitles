package cn.pixelmc.memorialtitles.forge;

import cn.pixelmc.memorialtitles.domain.Model.RichText;
import net.minecraft.network.chat.*;
import net.minecraft.ChatFormatting;

public final class Text {
  public static MutableComponent render(RichText text) {
    MutableComponent result = Component.empty();
    for (var g : text.glyphs())
      result.append(
          Component.literal(g.text())
              .setStyle(
                  Style.EMPTY
                      .withColor(g.color())
                      .withBold(g.bold())
                      .withItalic(g.italic())
                      .withUnderlined(g.underline())
                      .withStrikethrough(g.strike())));
    return result;
  }

  public static MutableComponent notification(RichText name, boolean granted) {
    ChatFormatting color = granted ? ChatFormatting.GREEN : ChatFormatting.RED;
    return Component.literal(granted ? "你已获得新的称号「" : "你的称号「")
        .withStyle(style -> style.withColor(color).withBold(false))
        .append(render(name))
        .append(Component.literal(granted ? "」" : "」被已移除")
            .withStyle(style -> style.withColor(color).withBold(false)));
  }

  private Text() {}
}
