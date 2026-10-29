package cn.pixelmc.memorialtitles.forge;

import cn.pixelmc.memorialtitles.MemorialTitles;
import cn.pixelmc.memorialtitles.domain.Model.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;

public final class TitleMenu extends ChestMenu {
  private final ServerPlayer viewer;
  private final UUID target;
  private final String mode, query, sort, filter;
  private int page;
  private final Map<Integer, String> actions = new HashMap<>();
  private final SimpleContainer buttons;
  private final ServerRuntime runtime;

  private TitleMenu(
      int id,
      Inventory inv,
      SimpleContainer buttons,
      ServerPlayer viewer,
      UUID target,
      String mode,
      String query,
      String sort,
      String filter,
      int page) {
    super(MenuType.GENERIC_9x6, id, inv, buttons, 6);
    this.buttons = buttons;
    this.viewer = viewer;
    this.target = target;
    this.mode = mode;
    this.query = query;
    this.sort = sort;
    this.filter = filter;
    this.page = Math.max(0, page);
    runtime = ServerRuntime.current;
    refresh();
  }

  public static void open(
      ServerPlayer p,
      String mode,
      UUID target,
      String query,
      String sort,
      String filter,
      int page) {
    ServerRuntime r = ServerRuntime.current;
    if (r == null || r.service == null) {
      p.sendSystemMessage(Component.literal("称号服务尚未就绪"));
      return;
    }
    if (mode.equals("display") && !target.equals(p.getUUID())) {
      p.sendSystemMessage(Component.literal("只能设置自己的称号显示位置"));
      return;
    }
    String label =
        mode.equals("display") ? "称号显示位置" : mode.equals("people")
            ? "玩家公开收藏"
            : mode.equals("catalogue") ? "称号图鉴" : target.equals(p.getUUID()) ? "我的称号收藏" : "公开称号收藏";
    p.openMenu(
        new SimpleMenuProvider(
            (id, inv, player) ->
                new TitleMenu(
                    id, inv, new SimpleContainer(54), p, target, mode, query, sort, filter, page),
            Component.literal(label)));
  }

  public void refresh() {
    Snapshot s = runtime.service.snapshot();
    actions.clear();
    buttons.clearContent();
    if (mode.equals("display")) {
      refreshDisplayPreferences(s);
      broadcastChanges();
      return;
    }
    boolean ownCollection = mode.equals("collection") && target.equals(viewer.getUUID());
    int capacity = ownCollection ? 44 : 45;
    List<String> ids;
    if (mode.equals("people"))
      ids =
          s.players().values().stream()
              .filter(
                  p -> p.name().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
              .sorted(Comparator.comparing(Profile::name))
              .map(p -> p.id().toString())
              .toList();
    else {
      List<Title> list =
          mode.equals("catalogue")
              ? s.catalogue(viewer.getUUID())
              : s.collection(target, viewer.getUUID(), false).stream()
                  .map(o -> s.titles().get(o.title()))
                  .toList();
      Comparator<Title> cmp =
          switch (sort) {
            case "name" -> Comparator.comparing(t -> t.name().plain());
            case "date" ->
                Comparator.comparingLong(
                        (Title t) -> {
                          Owned o = s.ownership(target, t.id());
                          return o == null ? 0 : o.acquiredAt();
                        })
                    .reversed();
            default -> Comparator.comparingInt(Title::order).thenComparing(Title::id);
          };
      ids =
          list.stream()
              .filter(
                  t ->
                      (t.name().plain() + " " + t.id() + " " + t.category())
                          .toLowerCase(Locale.ROOT)
                          .contains(query.toLowerCase(Locale.ROOT)))
              .filter(t -> !filter.equals("owned") || s.ownership(viewer.getUUID(), t.id()) != null)
              .filter(t -> !filter.equals("retired") || !t.issuable())
              .sorted(cmp)
              .map(Title::id)
              .toList();
    }
    int pages = Math.max(1, (ids.size() + capacity - 1) / capacity);
    page = Math.min(page, pages - 1);
    for (int slot = 0; slot < capacity; slot++) {
      int at = page * capacity + slot;
      if (at >= ids.size()) break;
      String id = ids.get(at);
      actions.put(slot, id);
      if (mode.equals("people")) {
        Profile p = s.players().get(UUID.fromString(id));
        buttons.setItem(slot, item(Items.PLAYER_HEAD, p.name(), List.of("点击查看该玩家公开的收藏")));
        continue;
      }
      Title title = s.titles().get(id);
      Owned ownership = s.ownership(target, id);
      ItemStack stack = new ItemStack(MemorialTitles.TOKEN.get());
      stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Text.render(title.name()));
      net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> tag.putString("MemorialTitleId", id));
      stack.set(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(title.icon()));
      List<String> lore = new ArrayList<>();
      Collections.addAll(
          lore, title.description(), "来源：" + title.source(), "分类：" + title.category());
      if (ownership != null) {
        lore.add(
            "本次获得："
                + DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(ownership.acquiredAt())));
        lore.add(id.equals(s.equipped().get(target)) ? "已佩戴" : "已收藏");
        if (target.equals(viewer.getUUID()))
          Collections.addAll(
              lore,
              "收藏公开：" + ownership.publicCollection(),
              "佩戴展示：" + ownership.publicDisplay(),
              "左键：佩戴/卸下；右键：收藏公开开关",
              "Shift+左键：佩戴展示开关");
      } else lore.add("未获得");
      if (!title.issuable()) lore.add("已绝版");
      setLore(stack, lore);
      buttons.setItem(slot, stack);
    }
    button(45, Items.ARROW, "上一页", "prev");
    button(46, Items.CHEST, "我的收藏", "own");
    button(47, Items.BOOK, "全服图鉴", "catalogue");
    button(48, Items.PLAYER_HEAD, "他人公开收藏", "people");
    button(49, Items.COMPASS, "搜索：" + (query.isEmpty() ? "全部" : query), "search");
    button(50, Items.COMPARATOR, "排序：" + sort, "sort");
    button(51, Items.HOPPER, "筛选：" + filter, "filter");
    button(52, Items.ARROW, "下一页 " + (page + 1) + "/" + pages, "next");
    button(53, Items.BARRIER, runtime.service.healthy() ? "卸下当前称号" : "数据库离线：只读缓存", "unequip");
    if (ownCollection) {
      DisplayPreferences preferences = s.displayPreferences(viewer.getUUID());
      buttons.setItem(44, item(Items.NAME_TAG, "显示位置", List.of(
          "头顶：" + onOff(preferences.head()), "聊天：" + onOff(preferences.chat()),
          "TAB：" + onOff(preferences.tab()), "点击分别设置，仍受管理员总开关控制")));
      actions.put(44, "!display");
    }
    broadcastChanges();
  }

  private static String onOff(boolean value) { return value ? "开启" : "关闭"; }

  private void refreshDisplayPreferences(Snapshot s) {
    buttons.setItem(4, item(Items.NAME_TAG, "显示位置", List.of(
        "为自己的称号分别选择展示位置", "设置即时保存，换称号和重新登录后仍保留",
        "管理员总开关和称号的佩戴展示隐私设置优先",
        runtime.service.healthy() ? "点击下方按钮切换" : "数据库离线：目前仅可查看")));
    displayButton(s, 20, Items.GOLDEN_HELMET, "头顶", DisplayPosition.HEAD, s.settings().head());
    displayButton(s, 22, Items.PAPER, "聊天", DisplayPosition.CHAT, s.settings().chat());
    displayButton(s, 24, Items.WRITABLE_BOOK, "TAB", DisplayPosition.TAB, s.settings().tab());
    button(45, Items.ARROW, "返回我的收藏", "own");
    button(53, Items.BARRIER, "卸下当前称号", "unequip");
  }

  private void displayButton(Snapshot s, int slot, Item visual, String name, DisplayPosition position, boolean global) {
    boolean selected = s.displayPreferences(viewer.getUUID()).enabled(position);
    List<String> lore = new ArrayList<>(List.of(
        "我的选择：" + onOff(selected), "管理员总开关：" + onOff(global),
        "点击" + (selected ? "关闭" : "开启") + "此位置"));
    if (!global) lore.add("管理员已关闭：个人开启也不会显示");
    else if (s.displayed(viewer.getUUID(), position) == null)
      lore.add("还需佩戴称号，并开启该称号的佩戴展示");
    else lore.add("当前正在此位置展示称号");
    ItemStack stack = item(visual, name + "显示：" + onOff(selected), lore);
    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name + "显示：" + onOff(selected))
        .withStyle(selected ? ChatFormatting.GREEN : ChatFormatting.GRAY));
    setLore(stack, lore);
    buttons.setItem(slot, stack);
    actions.put(slot, "!position_" + position.name());
  }

  private void button(int slot, Item item, String name, String action) {
    buttons.setItem(slot, item(item, name, List.of()));
    actions.put(slot, "!" + action);
  }

  public static ItemStack item(Item item, String name, List<String> lore) {
    ItemStack stack = new ItemStack(MemorialTitles.menuIcon(item));
    stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.WHITE));
    setLore(stack, lore);
    return stack;
  }

  private static void setLore(ItemStack stack, List<String> lines) {
    ListTag lore = new ListTag();
    for (String line : lines)
      for (String part : line.split("\n", -1))
        lore.add(
            StringTag.valueOf(
                Component.Serializer.toJson(
                    Component.literal(part)
                        .withStyle(
                            part.equals("已绝版") ? ChatFormatting.GOLD : ChatFormatting.GRAY), net.minecraft.core.RegistryAccess.EMPTY)));
    java.util.List<Component> components = new java.util.ArrayList<>();
    for (int i = 0; i < lore.size(); i++) components.add(Component.Serializer.fromJson(lore.getString(i), net.minecraft.core.RegistryAccess.EMPTY));
    stack.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(components));
    MenuTooltips.capture(stack, lore);
  }

  @Override
  public void clicked(int slot, int mouse, ClickType type, Player player) {
    if (player != viewer
        || slot < 0
        || slot >= 54
        || !(type == ClickType.PICKUP || type == ClickType.QUICK_MOVE)) return;
    String action = actions.get(slot);
    if (action == null) return;
    if (action.startsWith("!")) {
      if (action.startsWith("!position_")) {
        if (mode.equals("display") && target.equals(viewer.getUUID()))
          result(runtime.service.toggleDisplayPosition(viewer.getUUID(),
              DisplayPosition.valueOf(action.substring("!position_".length()))));
        return;
      }
      switch (action.substring(1)) {
        case "prev" -> open(viewer, mode, target, query, sort, filter, Math.max(0, page - 1));
        case "next" -> open(viewer, mode, target, query, sort, filter, page + 1);
        case "own" -> open(viewer, "collection", viewer.getUUID(), "", sort, "all", 0);
        case "display" -> open(viewer, "display", viewer.getUUID(), "", sort, "all", 0);
        case "catalogue" -> open(viewer, "catalogue", viewer.getUUID(), "", sort, "all", 0);
        case "people" -> open(viewer, "people", viewer.getUUID(), "", sort, "all", 0);
        case "search" -> {
          viewer.closeContainer();
          Network.prompt(viewer, mode, target, sort, filter, query);
        }
        case "sort" ->
            open(
                viewer,
                mode,
                target,
                query,
                sort.equals("order") ? "name" : sort.equals("name") ? "date" : "order",
                filter,
                0);
        case "filter" ->
            open(
                viewer,
                mode,
                target,
                query,
                sort,
                filter.equals("all") ? "owned" : filter.equals("owned") ? "retired" : "all",
                0);
        case "unequip" -> result(runtime.service.equip(viewer.getUUID(), ""));
      }
      return;
    }
    if (mode.equals("people")) {
      open(viewer, "collection", UUID.fromString(action), "", sort, "all", 0);
      return;
    }
    if (!target.equals(viewer.getUUID())) return;
    Snapshot s = runtime.service.snapshot();
    Owned o = s.ownership(viewer.getUUID(), action);
    if (o == null) return;
    if (type == ClickType.QUICK_MOVE)
      result(
          runtime.service.privacy(
              viewer.getUUID(), action, o.publicCollection(), !o.publicDisplay()));
    else if (mouse == 1)
      result(
          runtime.service.privacy(
              viewer.getUUID(), action, !o.publicCollection(), o.publicDisplay()));
    else if (mouse == 0)
      result(
          runtime.service.equip(
              viewer.getUUID(), action.equals(s.equipped().get(viewer.getUUID())) ? "" : action));
  }

  private void result(CompletableFuture<?> future) {
    future.whenComplete(
        (v, error) ->
            runtime.server.execute(
                () -> {
                  if (error != null)
                    viewer.sendSystemMessage(Component.literal(ServerRuntime.safeError(error)));
                  else refresh();
                }));
  }

  @Override
  public ItemStack quickMoveStack(Player player, int slot) {
    return ItemStack.EMPTY;
  }

  @Override
  public boolean canDragTo(Slot slot) {
    return false;
  }

  @Override
  public boolean stillValid(Player player) {
    return player == viewer;
  }
}
