package cn.pixelmc.memorialtitles.client;

import cn.pixelmc.memorialtitles.forge.Network;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class SearchScreen extends Screen {
  private final JsonObject state;
  private EditBox query;

  public SearchScreen(JsonObject state) {
    super(Component.literal("搜索称号 / 玩家"));
    this.state = state.deepCopy();
  }

  @Override
  protected void init() {
    query =
        addRenderableWidget(
            new EditBox(
                font, width / 2 - 140, height / 2 - 15, 280, 20, Component.literal("搜索内容")));
    query.setMaxLength(64);
    query.setValue(state.get("query").getAsString());
    addRenderableWidget(
        Button.builder(Component.literal("搜索"), b -> search())
            .bounds(width / 2 - 100, height / 2 + 20, 95, 20)
            .build());
    addRenderableWidget(
        Button.builder(
                Component.literal("清空筛选"),
                b -> {
                  query.setValue("");
                  search();
                })
            .bounds(width / 2 + 5, height / 2 + 20, 95, 20)
            .build());
    setInitialFocus(query);
  }

  private void search() {
    state.addProperty("query", query.getValue());
    Network.request("open", state);
    minecraft.setScreen(null);
  }

  @Override
  public boolean keyPressed(int key, int scan, int mods) {
    if (key == 257) {
      search();
      return true;
    }
    return super.keyPressed(key, scan, mods);
  }

  @Override
  public void render(GuiGraphics g, int x, int y, float dt) {
    g.fillGradient(0, 0, width, height, 0xc0101010, 0xd0101010);
    g.drawCenteredString(font, title, width / 2, height / 2 - 42, 0xffffff);
    super.render(g, x, y, dt);
  }

  // 1.21 Screen.render invokes renderBackground before its widgets. The custom
  // screen already paints its background, so keep the final widget pass transparent.
  @Override
  public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

  @Override
  public boolean isPauseScreen() {
    return false;
  }
}
