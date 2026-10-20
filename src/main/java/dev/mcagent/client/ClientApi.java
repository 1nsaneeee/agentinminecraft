package dev.mcagent.client;

import com.google.gson.JsonObject;

/**
 * Client-only capabilities, reached reflectively so the dedicated-server side
 * never loads a class that references {@code net.minecraft.client}.
 */
public interface ClientApi {
    boolean worldLoaded();

    byte[] screenshotPng() throws Exception;

    JsonObject clientInfo();

    void openCommandBlock(String command);
}
