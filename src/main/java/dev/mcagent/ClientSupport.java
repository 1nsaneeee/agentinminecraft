package dev.mcagent;

import dev.mcagent.client.ClientApi;

/**
 * Reflective entry point for client-only capabilities.
 *
 * <p>{@code ClientApiImpl} references {@code net.minecraft.client.*}, so it must
 * never be touched by class loading on a dedicated server. Loading it by name
 * behind a dist check keeps the common side clean.</p>
 */
public final class ClientSupport {
    private static final ClientApi API = load();

    private ClientSupport() {}

    private static ClientApi load() {
        if (!dev.mcagent.util.Dist2.isClient()) return null;
        try {
            Class<?> cls = Class.forName("dev.mcagent.client.ClientApiImpl");
            return (ClientApi) cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            McAgent.LOG.warn("[mcagent] client bridge unavailable: {}", t.toString());
            return null;
        }
    }

    public static ClientApi get() {
        return API;
    }

    public static boolean available() {
        return API != null;
    }
}
