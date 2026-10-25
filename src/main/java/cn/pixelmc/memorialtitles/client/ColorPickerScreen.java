package cn.pixelmc.memorialtitles.client;

import static cn.pixelmc.memorialtitles.client.Theme.*;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ColorPickerScreen extends Screen {
  private final Screen parent;
  private final IntConsumer accept;
  private int color;
  private EditBox hex;
  private float uiScale = 1;

  public ColorPickerScreen(Screen parent, int initial, IntConsumer accept) {
    super(Component.literal("选择文字颜色"));
    this.parent = parent;
    this.color = initial;
    this.accept = accept;
  }

  @Override
  protected void init() {
    uiScale = Math.min(width / 680f, height / 380f);
    width = Math.round(width / uiScale);
    height = Math.round(height / uiScale);
    int x = width / 2 - 153, y = height / 2 - 103;
    for (int i = 0; i < 3; i++) {
      int shift = 16 - i * 8;
      String label = new String[] {"红  R", "绿  G", "蓝  B"}[i];
      addRenderableWidget(
          new AbstractSliderButton(
              x + 14,
              y + 44 + i * 29,
              278,
              20,
              Component.empty(),
              ((color >> shift) & 255) / 255d) {
            {
              updateMessage();
            }

            @Override
            protected void updateMessage() {
              setMessage(Component.literal(label + "   " + Math.round(value * 255)));
            }

            @Override
            protected void applyValue() {
              color = (color & ~(255 << shift)) | ((int) Math.round(value * 255) << shift);
              if (hex != null) hex.setValue(String.format("%06x", color));
            }

            @Override
            public void renderWidget(GuiGraphics g, int mx, int my, float dt) {
              g.fill(getX(), getY() + 8, getX() + width, getY() + 12, LINE);
              int thumb = getX() + (int) (value * (width - 5));
              g.fill(thumb, getY() + 4, thumb + 5, getY() + 17, GOLD);
              g.drawString(font, getMessage(), getX(), getY() - 5, TEXT, false);
            }
          });
    }
    hex =
        addRenderableWidget(
            new EditBox(font, x + 22, y + 139, 156, 16, Component.literal("颜色 HEX")));
    hex.setBordered(false);
    hex.setTextColor(TEXT);
    hex.setMaxLength(7);
    hex.setValue(String.format("%06x", color));
    addRenderableWidget(
        new Theme.Action(
            x + 192,
            y + 134,
            100,
            24,
            Component.literal("应用 HEX"),
            Tone.NORMAL,
            () -> {
              try {
                int c = Integer.parseInt(hex.getValue().replace("#", ""), 16);
                if (c >= 0 && c <= 0xffffff) {
                  color = c;
                  init(
                      minecraft,
                      minecraft.getWindow().getGuiScaledWidth(),
                      minecraft.getWindow().getGuiScaledHeight());
                }
              } catch (NumberFormatException ignored) {
              }
            }));
    addRenderableWidget(
        new Theme.Action(
            x + 14,
            y + 206,
            134,
            26,
            Component.literal("使用此颜色"),
            Tone.PRIMARY,
            () -> {
              accept.accept(color);
              minecraft.setScreen(parent);
            }));
    addRenderableWidget(
        new Theme.Action(
            x + 158, y + 206, 134, 26, Component.literal("取消"), Tone.QUIET, this::onClose));
  }

  @Override
  public void render(GuiGraphics g, int mx, int my, float dt) {
    g.pose().pushPose();
    g.pose().scale(uiScale, uiScale, 1);
    g.fill(0, 0, width, height, BG);
    int x = width / 2 - 153, y = height / 2 - 103;
    panel(g, x, y, 306, 247);
    g.drawString(font, title, x + 14, y + 16, GOLD, false);
    g.fill(x + 14, y + 134, x + 182, y + 158, BG);
    border(g, x + 14, y + 134, 168, 24, hex.isFocused() ? MINT : LINE);
    g.fill(x + 14, y + 171, x + 292, y + 192, 0xff000000 | color);
    super.render(g, (int) (mx / uiScale), (int) (my / uiScale), dt);
    g.pose().popPose();
  }

  @Override
  public boolean mouseClicked(double x, double y, int b) {
    return super.mouseClicked(x / uiScale, y / uiScale, b);
  }

  @Override
  public boolean mouseReleased(double x, double y, int b) {
    return super.mouseReleased(x / uiScale, y / uiScale, b);
  }

  @Override
  public boolean mouseDragged(double x, double y, int b, double dx, double dy) {
    return super.mouseDragged(x / uiScale, y / uiScale, b, dx / uiScale, dy / uiScale);
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
