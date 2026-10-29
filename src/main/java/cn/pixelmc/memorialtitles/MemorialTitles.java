package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.forge.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

public final class MemorialTitles implements net.fabricmc.api.ModInitializer {
  public static final String ID = "memorialtitles";
  public static final java.util.function.Supplier<Item> TOKEN = menu("title_token");
  public static final java.util.function.Supplier<Item> MENU_ARROW = menu("menu_arrow");
  public static final java.util.function.Supplier<Item> MENU_COLLECTION = menu("menu_collection");
  public static final java.util.function.Supplier<Item> MENU_CATALOGUE = menu("menu_catalogue");
  public static final java.util.function.Supplier<Item> MENU_PEOPLE = menu("menu_people");
  public static final java.util.function.Supplier<Item> MENU_SEARCH = menu("menu_search");
  public static final java.util.function.Supplier<Item> MENU_SORT = menu("menu_sort");
  public static final java.util.function.Supplier<Item> MENU_FILTER = menu("menu_filter");
  public static final java.util.function.Supplier<Item> MENU_UNEQUIP = menu("menu_unequip");
  public static final java.util.function.Supplier<Item> MENU_DISPLAY = menu("menu_display");
  public static final java.util.function.Supplier<Item> MENU_HEAD_DISPLAY = menu("menu_head_display");
  public static final java.util.function.Supplier<Item> MENU_CHAT_DISPLAY = menu("menu_chat_display");
  public static final java.util.function.Supplier<Item> MENU_TAB_DISPLAY = menu("menu_tab_display");

  private static java.util.function.Supplier<Item> menu(String name) {
    Item item = net.minecraft.core.Registry.register(net.minecraft.core.registries.BuiltInRegistries.ITEM, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ID, name), new Item(new Item.Properties().stacksTo(1)));
    return () -> item;
  }

  public static Item menuIcon(Item visual) {
    if (visual == Items.ARROW) return MENU_ARROW.get();
    if (visual == Items.CHEST) return MENU_COLLECTION.get();
    if (visual == Items.BOOK) return MENU_CATALOGUE.get();
    if (visual == Items.PLAYER_HEAD) return MENU_PEOPLE.get();
    if (visual == Items.COMPASS) return MENU_SEARCH.get();
    if (visual == Items.COMPARATOR) return MENU_SORT.get();
    if (visual == Items.HOPPER) return MENU_FILTER.get();
    if (visual == Items.BARRIER) return MENU_UNEQUIP.get();
    if (visual == Items.NAME_TAG) return MENU_DISPLAY.get();
    if (visual == Items.GOLDEN_HELMET) return MENU_HEAD_DISPLAY.get();
    if (visual == Items.PAPER) return MENU_CHAT_DISPLAY.get();
    if (visual == Items.WRITABLE_BOOK) return MENU_TAB_DISPLAY.get();
    return visual;
  }

  @Override public void onInitialize() {
    cn.pixelmc.memorialtitles.service.RemoteGateway.register();
    Network.register();
    ServerRuntime runtime = new ServerRuntime();
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(runtime::starting);
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime.stopping());
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> runtime.tick());
    net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> cn.pixelmc.memorialtitles.forge.Commands.register(dispatcher));
    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> runtime.login(handler.player));
    net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> runtime.logout(handler.player));
  }
}
