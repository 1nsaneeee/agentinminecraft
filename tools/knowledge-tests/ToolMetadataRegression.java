package dev.mcagent;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ToolMetadataRegression {
    private static int checks;
    private static McpTool tool(String name) {
        return new McpTool() {
            public String name() { return name; }
            public String description() { return name; }
            public JsonObject inputSchema() { return ToolRegistry.schema(); }
            public McpResult call(JsonObject args) { return McpResult.text(name); }
        };
    }
    private static JsonObject metadata(String owner) { JsonObject out = Json.obj(); out.addProperty("owner", owner); return out; }
    public static void main(String[] args) throws Exception {
        List<String> seen = new ArrayList<>(); ToolRegistry[] holder = new ToolRegistry[1];
        ToolRegistry registry = new ToolRegistry((original, input, meta) -> {
            String owner = meta == null ? "none" : meta.get("owner").getAsString(); seen.add(original.name() + ":" + owner);
            if (original.name().equals("outer")) {
                holder[0].call("leaf", Json.obj(), metadata("inner"));
                holder[0].call("leaf", Json.obj());
            }
            if (original.name().equals("fail")) throw new IllegalStateException("expected test failure");
            if (meta != null) meta.addProperty("changed", true);
            return original.call(input);
        }); holder[0] = registry;
        for (String name : List.of("leaf", "outer", "fail")) registry.add(tool(name));
        JsonObject inputMeta = metadata("caller"); registry.call("leaf", Json.obj(), inputMeta);
        check(!inputMeta.has("changed"), "gate cannot mutate caller metadata");
        registry.get("leaf").call(Json.obj());
        check(seen.get(seen.size() - 1).equals("leaf:none"), "metadata removed after success");
        seen.clear(); registry.call("outer", Json.obj(), metadata("parent"));
        check(seen.equals(List.of("outer:parent", "leaf:inner", "leaf:parent")), "nested metadata restores the outer context");
        try { registry.call("fail", Json.obj(), metadata("failed")); throw new AssertionError("must throw"); }
        catch (IllegalStateException expected) { check(expected.getMessage().equals("expected test failure"), "real gate failures propagate"); }
        registry.call("leaf", Json.obj());
        check(seen.get(seen.size() - 1).equals("leaf:none"), "metadata removed after exception");
        JsonObject invalid = Json.obj(); invalid.addProperty("extra", true);
        check(registry.call("leaf", invalid, metadata("invalid")).toJson().has("isError"), "metadata does not bypass argument validation");
        registry.call("leaf", Json.obj());
        check(seen.get(seen.size() - 1).equals("leaf:none"), "invalid arguments leave no metadata behind");
        check(registry.call("missing", Json.obj(), metadata("unknown")).toJson().has("isError"), "unknown tool remains an error");
        registry.call("leaf", Json.obj());
        check(seen.get(seen.size() - 1).equals("leaf:none"), "unknown tool leaves no metadata behind");
        CyclicBarrier barrier = new CyclicBarrier(2); AtomicReference<Throwable> failure = new AtomicReference<>();
        ToolRegistry parallel = new ToolRegistry((original, input, meta) -> {
            String before = meta.get("owner").getAsString(); barrier.await(2, TimeUnit.SECONDS);
            if (!before.equals(original.name()) || !before.equals(meta.get("owner").getAsString())) throw new AssertionError("cross-thread metadata leak");
            return original.call(input);
        }); parallel.add(tool("first")); parallel.add(tool("second"));
        Thread a = request(parallel, "first", failure), b = request(parallel, "second", failure);
        a.start(); b.start(); a.join(3000); b.join(3000);
        check(!a.isAlive() && !b.isAlive() && failure.get() == null, "concurrent requests keep distinct metadata");
        System.out.println("TOOL_METADATA_CHECKS=" + checks);
    }
    private static Thread request(ToolRegistry registry, String name, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { registry.call(name, Json.obj(), metadata(name)); } catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "metadata-" + name); thread.setDaemon(true); return thread;
    }
    private static void check(boolean passed, String name) { if (!passed) throw new AssertionError(name); checks++; }
}
