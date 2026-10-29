package cn.pixelmc.memorialtitles;
public final class SmokeEntrypoint implements net.fabricmc.api.ModInitializer {
 public void onInitialize() {
  if (Boolean.getBoolean("mt.smoke")) net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> DisplayPositionsRuntimeProbe.tick());
 }
}
