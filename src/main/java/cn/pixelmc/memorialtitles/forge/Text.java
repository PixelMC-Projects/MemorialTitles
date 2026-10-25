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

  public static MutableComponent unequippedReminder() {
    return reminderPart("您似乎还没有配置一个用于展示的称号,", 0xf2c66d)
        .append(reminderPart("使用", 0xb6c4d7))
        .append(reminderPart("/mt", 0x5dd4ed))
        .append(reminderPart("打开个人称号面板,输入", 0xb6c4d7))
        .append(reminderPart("/mt reminder off", 0x5dd4ed))
        .append(reminderPart("以永久关闭该提示", 0x80caa2));
  }

  private static MutableComponent reminderPart(String text, int color) {
    return Component.literal(text).setStyle(Style.EMPTY.withColor(color).withBold(false));
  }

  private Text() {}
}
