package cn.pixelmc.memorialtitles.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Native Minecraft controls with a restrained archive palette. */
public final class Theme {
  public static final int BG = 0xff0f1923,
      SIDEBAR = 0xff142430,
      SURFACE = 0xff1a2c39,
      RAISED = 0xff243b4b,
      LINE = 0xff354f60,
      TEXT = 0xffe5edf2,
      MUTED = 0xff94adbc,
      GOLD = 0xffe4ba76,
      MINT = 0xff75d6c2,
      RED = 0xffed9292;

  public enum Tone {
    NORMAL,
    PRIMARY,
    QUIET,
    DANGER,
    NAV,
    TAB
  }

  public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
    g.fill(x, y, x + w, y + 1, color);
    g.fill(x, y + h - 1, x + w, y + h, color);
    g.fill(x, y, x + 1, y + h, color);
    g.fill(x + w - 1, y, x + w, y + h, color);
  }

  public static void panel(GuiGraphics g, int x, int y, int w, int h) {
    g.fill(x, y, x + w, y + h, SURFACE);
    border(g, x, y, w, h, LINE);
  }

  public static class Action extends Button {
    public Tone tone;
    public boolean selected;
    public String detail = "";

    public Action(int x, int y, int w, int h, Component label, Tone tone, Runnable action) {
      super(x, y, w, h, label, b -> action.run(), DEFAULT_NARRATION);
      this.tone = tone;
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mx, int my, float dt) {
      var font = Minecraft.getInstance().font;
      boolean hover = isHoveredOrFocused();
      int fill = selected ? RAISED : hover ? 0xff2b4556 : SURFACE;
      if (tone == Tone.PRIMARY) fill = active ? (hover ? 0xfff0ce99 : GOLD) : LINE;
      if (tone == Tone.QUIET || tone == Tone.NAV || tone == Tone.TAB)
        fill = selected || hover ? RAISED : SIDEBAR;
      g.fill(getX(), getY(), getX() + width, getY() + height, fill);
      if (tone == Tone.NORMAL || tone == Tone.DANGER || tone == Tone.PRIMARY || isFocused())
        border(
            g,
            getX(),
            getY(),
            width,
            height,
            isFocused() ? MINT : tone == Tone.PRIMARY ? GOLD : LINE);
      if (selected && (tone == Tone.NAV || tone == Tone.TAB))
        g.fill(getX(), getY() + height - 2, getX() + width, getY() + height, GOLD);
      int color =
          !active
              ? MUTED
              : tone == Tone.PRIMARY ? BG : tone == Tone.DANGER ? RED : selected ? GOLD : TEXT;
      if (detail.isEmpty())
        g.drawCenteredString(
            font, getMessage(), getX() + width / 2, getY() + (height - 8) / 2, color);
      else {
        g.drawString(font, getMessage(), getX() + 12, getY() + 8, color, false);
        g.drawString(
            font,
            font.plainSubstrByWidth(detail, width - 25),
            getX() + 12,
            getY() + 24,
            MUTED,
            false);
      }
    }
  }

  private Theme() {}
}
