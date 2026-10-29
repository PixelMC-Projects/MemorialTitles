package cn.pixelmc.memorialtitles;
public final class ClientSmokeEntrypoint implements net.fabricmc.api.ClientModInitializer {
 public void onInitializeClient() { net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> ClientSmokeProbe.tick()); }
}
