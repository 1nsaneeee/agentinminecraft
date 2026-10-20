package dev.mcagent.mixin;

import dev.mcagent.fakeplayer.FakeConnection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets mod payloads reach the agent's fake player.
 *
 * <p>NeoForge refuses to send a mod payload to a client that has not negotiated that
 * channel: {@code NetworkRegistry.checkPacket} throws
 * "Payload X may not be sent to the client!". A fake player has no client, so it never
 * negotiated anything, and the first mod that syncs data on join kills the login -
 * KubeJS does exactly that with {@code kubejs:sync_server_data}.</p>
 *
 * <p>That check exists to protect real clients from receiving data they cannot decode.
 * A {@link FakeConnection} decodes nothing: every outbound packet is dropped before it
 * reaches a channel. The check therefore buys nothing for it while blocking the whole
 * login, so it is skipped for that one connection type and nothing else.</p>
 *
 * <p>{@code remap = false} because NeoForge is not remapped.</p>
 */
@Mixin(value = NetworkRegistry.class, remap = false)
public abstract class NetworkRegistryMixin {

    @Inject(method = "checkPacket", at = @At("HEAD"), cancellable = true)
    private static void mcagent$skipCheckForFakePlayer(Packet<?> packet,
                                                       ServerCommonPacketListener listener,
                                                       CallbackInfo ci) {
        if (listener != null && listener.getConnection() instanceof FakeConnection) {
            ci.cancel();
        }
    }
}
