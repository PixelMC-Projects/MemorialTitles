package cn.pixelmc.memorialtitles.smoke;
import cn.pixelmc.memorialtitles.MemorialTitles;
public final class ClientSmokeEntrypoint {
 public static void register() { net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> ClientSmokeProbe.tick()); }
}
