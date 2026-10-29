package cn.pixelmc.memorialtitles.smoke;
import cn.pixelmc.memorialtitles.MemorialTitles;
@net.neoforged.fml.common.Mod("memorialtitles_smoke")
public final class SmokeEntrypoint {
 public SmokeEntrypoint() {
  if (net.neoforged.fml.loading.FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT) ClientSmokeEntrypoint.register();
  if (Boolean.getBoolean("mt.codecIntegration")) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::started);
  if (Boolean.getBoolean("mt.smoke")) net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::tick);
 }
 private void started(net.neoforged.neoforge.event.server.ServerStartedEvent event) {
  Thread.startVirtualThread(() -> {
   boolean passed = false;
   try {
    Class.forName("cn.pixelmc.memorialtitles.TitleDeletionIntegrationSuite").getMethod("main", String[].class).invoke(null, (Object)new String[0]);
    passed = true;
   } catch (Throwable error) { com.mojang.logging.LogUtils.getLogger().error("NeoForge codec integration failed", error); }
   try {
    java.nio.file.Path report = java.nio.file.Path.of("../build/reports/codec-runtime.json");
    java.nio.file.Files.createDirectories(report.getParent());
    java.nio.file.Files.writeString(report, "{\"passed\":" + passed + "}");
   } catch (Exception error) { throw new RuntimeException(error); }
   event.getServer().execute(() -> event.getServer().halt(false));
  });
 }
 private void tick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) { DisplayPositionsRuntimeProbe.tick(); }
}
