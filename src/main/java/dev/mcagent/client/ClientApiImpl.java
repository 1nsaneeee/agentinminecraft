package dev.mcagent.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcagent.Json;
import dev.mcagent.McAgent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Implementation of {@link ClientApi}; only ever loaded on a physical client. */
public final class ClientApiImpl implements ClientApi {

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    @Override
    public boolean worldLoaded() {
        return mc().level != null && mc().player != null;
    }

    @Override
    public byte[] screenshotPng() throws Exception {
        Minecraft client = mc();
        if (client.level == null || client.player == null) {
            throw new IllegalStateException("no world loaded on the client");
        }
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
                try {
                    future.complete(image.asByteArray());
                } finally {
                    image.close();
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(20, TimeUnit.SECONDS);
    }

    @Override
    public JsonObject clientInfo() {
        Minecraft client = mc();
        JsonObject o = Json.obj();
        o.addProperty("window", client.getWindow().getWidth() + "x" + client.getWindow().getHeight());
        o.addProperty("guiScale", client.options.guiScale().get());
        o.addProperty("fov", client.options.fov().get());
        o.addProperty("fps", Minecraft.getInstance().getFps());
        o.addProperty("worldLoaded", worldLoaded());
        o.addProperty("screen", client.screen == null ? null : client.screen.getClass().getSimpleName());
        return o;
    }

    @Override
    public void openCommandBlock(String command) {
        // Reserved for future interactive flows; intentionally a no-op for now.
        McAgent.LOG.debug("[mcagent] openCommandBlock({}) ignored", command);
    }
}
