package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.forge.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

@Mod(MemorialTitles.ID)
public final class MemorialTitles {
  public static final String ID = "memorialtitles";
  private static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(net.minecraft.core.registries.BuiltInRegistries.ITEM, ID);
  public static final DeferredHolder<Item, Item> TOKEN =
      ITEMS.register("title_token", () -> new Item(new Item.Properties().stacksTo(1)));
  public static final DeferredHolder<Item, Item> MENU_ARROW = menu("menu_arrow");
  public static final DeferredHolder<Item, Item> MENU_COLLECTION = menu("menu_collection");
  public static final DeferredHolder<Item, Item> MENU_CATALOGUE = menu("menu_catalogue");
  public static final DeferredHolder<Item, Item> MENU_PEOPLE = menu("menu_people");
  public static final DeferredHolder<Item, Item> MENU_SEARCH = menu("menu_search");
  public static final DeferredHolder<Item, Item> MENU_SORT = menu("menu_sort");
  public static final DeferredHolder<Item, Item> MENU_FILTER = menu("menu_filter");
  public static final DeferredHolder<Item, Item> MENU_UNEQUIP = menu("menu_unequip");
  public static final DeferredHolder<Item, Item> MENU_DISPLAY = menu("menu_display");
  public static final DeferredHolder<Item, Item> MENU_HEAD_DISPLAY = menu("menu_head_display");
  public static final DeferredHolder<Item, Item> MENU_CHAT_DISPLAY = menu("menu_chat_display");
  public static final DeferredHolder<Item, Item> MENU_TAB_DISPLAY = menu("menu_tab_display");

  private static DeferredHolder<Item, Item> menu(String name) {
    return ITEMS.register(name, () -> new Item(new Item.Properties().stacksTo(1)));
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

  public MemorialTitles(IEventBus modBus) {
    cn.pixelmc.memorialtitles.service.RemoteGateway.register();
    ITEMS.register(modBus);
    modBus.addListener(Network::register);
    NeoForge.EVENT_BUS.register(new ServerRuntime());
  }
}
