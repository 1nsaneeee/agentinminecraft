package dev.mcagent.fakeplayer;

import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.net.SocketAddress;

/**
 * A {@link Connection} with no socket behind it.
 *
 * <p>{@code ServerGamePacketListenerImpl} insists on a Connection, and the real one
 * writes into a netty channel that only exists for an actual TCP client. Here every
 * outbound packet is dropped on the floor and the inbound side is never delivered,
 * which is exactly right for a player that has no client: the server still runs the
 * full player lifecycle - inventory, food, damage, block breaking, chunk tickets -
 * but nothing is ever sent anywhere.</p>
 *
 * <p>Every override below corresponds to a method that would otherwise dereference the
 * missing channel. If a future Minecraft version adds another such path, the symptom
 * will be an NPE from inside Connection and the fix is one more override here.</p>
 */
public final class FakeConnection extends Connection {

    private static final SocketAddress LOOPBACK = new InetSocketAddress("127.0.0.1", 0);

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        attachPlaceholderChannel(this);
    }

    /**
     * Somewhere inside the login path something calls {@code channel().attr(...)} on us.
     * The field is private and there is no setter, so an empty EmbeddedChannel is put
     * there directly. Nothing is ever written to it - every send override above drops
     * the packet before it could reach a channel.
     */
    private static void attachPlaceholderChannel(Connection connection) {
        try {
            Field channel = Connection.class.getDeclaredField("channel");
            channel.setAccessible(true);
            channel.set(connection, new EmbeddedChannel());
        } catch (Throwable t) {
            dev.mcagent.McAgent.LOG.error("[mcagent] could not attach the placeholder channel", t);
        }
    }

    @Override public void send(Packet<?> packet) {}

    @Override public void send(Packet<?> packet, PacketSendListener listener) {}

    @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}

    @Override public void flushChannel() {}

    @Override public void tick() {}

    @Override protected void tickSecond() {}

    @Override public void disconnect(Component reason) {}

    @Override public void disconnect(DisconnectionDetails details) {}

    @Override public boolean isMemoryConnection() {
        return true;
    }

    @Override public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    @Override public String getLoggableAddress(boolean logIps) {
        return "agent-local";
    }

    /** Nothing is ever written, so outbound protocol setup is a no-op too. */
    @Override public void setupOutboundProtocol(
            net.minecraft.network.ProtocolInfo<?> info) {}

    /** Nothing is ever read off this connection, so protocol setup is a no-op. */
    @Override public <T extends PacketListener> void setupInboundProtocol(
            net.minecraft.network.ProtocolInfo<T> info, T listener) {}
}
