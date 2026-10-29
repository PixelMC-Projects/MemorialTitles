package cn.pixelmc.memorialtitles.forge;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Server-authored menu text, kept separate from modpack tooltip additions. */
public final class MenuTooltips {
  public static final String KEY = "MemorialTitlesMenuTooltip";

  public static void capture(ItemStack stack, ListTag lore) {
    net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, stack, tag -> tag.put(KEY, encode(stack.getHoverName(), lore)));
  }

  public static CompoundTag encode(Component name, ListTag lore) {
    CompoundTag data = new CompoundTag();
    data.putString("Name", Component.Serializer.toJson(name, net.minecraft.core.RegistryAccess.EMPTY));
    data.put("Lore", lore.copy());
    return data;
  }

  public static Optional<List<Component>> ownLines(ItemStack stack) {
    CompoundTag data = stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
    if (stack.isEmpty() || !data.contains(KEY, Tag.TAG_COMPOUND)) return Optional.empty();
    return decode(data.getCompound(KEY));
  }

  public static Optional<List<Component>> decode(CompoundTag data) {
    if (!data.contains("Name", Tag.TAG_STRING) || !data.contains("Lore", Tag.TAG_LIST))
      return Optional.empty();
    try {
      List<Component> lines = new ArrayList<>();
      Component name = Component.Serializer.fromJson(data.getString("Name"), net.minecraft.core.RegistryAccess.EMPTY);
      if (name == null) return Optional.empty();
      lines.add(name);
      ListTag lore = data.getList("Lore", Tag.TAG_STRING);
      for (int i = 0; i < lore.size(); i++) {
        Component line = Component.Serializer.fromJson(lore.getString(i), net.minecraft.core.RegistryAccess.EMPTY);
        if (line == null) return Optional.empty();
        lines.add(line);
      }
      return Optional.of(List.copyOf(lines));
    } catch (RuntimeException invalid) {
      return Optional.empty();
    }
  }

  private MenuTooltips() {}
}
