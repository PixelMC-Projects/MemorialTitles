package cn.pixelmc.memorialtitles.domain;

import cn.pixelmc.memorialtitles.domain.Model.Gradient;
import java.util.ArrayList;
import java.util.List;

/** Keep selected characters in following segments stable when a text segment changes length. */
public final class TextEdits {
  public static List<Gradient> afterEdit(
      List<Gradient> groups, int start, int oldSize, int newSize) {
    if (start < 0 || oldSize < 0 || newSize < 0)
      throw new IllegalArgumentException("Invalid edit range");
    List<Gradient> result = new ArrayList<>();
    for (Gradient g : groups) {
      List<Integer> positions = new ArrayList<>();
      for (int p : g.positions()) {
        if (p < start) positions.add(p);
        else if (p >= start + oldSize) positions.add(p + newSize - oldSize);
        else if (p - start < newSize) positions.add(p);
      }
      if (!positions.isEmpty()) result.add(new Gradient(positions, g.from(), g.to()));
    }
    return result;
  }

  private TextEdits() {}
}
