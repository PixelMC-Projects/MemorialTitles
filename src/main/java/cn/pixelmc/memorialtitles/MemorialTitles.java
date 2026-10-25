package cn.pixelmc.memorialtitles;

import cn.pixelmc.memorialtitles.forge.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(MemorialTitles.ID)
public final class MemorialTitles {
  public static final String ID = "memorialtitles";
  private static final DeferredRegister<Item> ITEMS =
      DeferredRegister.create(ForgeRegistries.ITEMS, ID);
  public static final RegistryObject<Item> TOKEN =
      ITEMS.register("title_token", () -> new Item(new Item.Properties().stacksTo(1)));
  public static final RegistryObject<Item> MENU_ARROW = menu("menu_arrow");
  public static final RegistryObject<Item> MENU_COLLECTION = menu("menu_collection");
  public static final RegistryObject<Item> MENU_CATALOGUE = menu("menu_catalogue");
  public static final RegistryObject<Item> MENU_PEOPLE = menu("menu_people");
  public static final RegistryObject<Item> MENU_SEARCH = menu("menu_search");
  public static final RegistryObject<Item> MENU_SORT = menu("menu_sort");
  public static final RegistryObject<Item> MENU_FILTER = menu("menu_filter");
  public static final RegistryObject<Item> MENU_UNEQUIP = menu("menu_unequip");
  public static final RegistryObject<Item> MENU_DISPLAY = menu("menu_display");
  public static final RegistryObject<Item> MENU_HEAD_DISPLAY = menu("menu_head_display");
  public static final RegistryObject<Item> MENU_CHAT_DISPLAY = menu("menu_chat_display");
  public static final RegistryObject<Item> MENU_TAB_DISPLAY = menu("menu_tab_display");

  private static RegistryObject<Item> menu(String name) {
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

  public MemorialTitles() {
    cn.pixelmc.memorialtitles.service.RemoteGateway.register();
    ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
    Network.register();
    MinecraftForge.EVENT_BUS.register(new ServerRuntime());
  }
}
