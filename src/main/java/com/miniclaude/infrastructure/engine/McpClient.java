package com.miniclaude.infrastructure.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP（Model Context Protocol）客户端。
 *
 * <p>职责：通过 stdio 连接 MCP 服务器，发现工具并以
 * {@code mcp__serverName__toolName} 前缀暴露给 Agent。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 由 {@link Agent} 在首次 chat 时初始化 {@link McpManager}。
 * 配置来源：{@code .claude/settings.json}、{@code ~/.claude/settings.json}、{@code .mcp.json}。
 * 对应 Python {@code mcp_client.py} 移植。
 */
public final class McpClient {

    private McpClient() {}

    private static final Gson GSON = new Gson();

    // ─── 单 MCP 连接（每个 server 一个）──────────────────────────

    /**
     * 管理单个 MCP 服务器进程，通过换行分隔的 JSON-RPC 与 stdin/stdout 通信。
     */
    public static class McpConnection implements AutoCloseable {
        private final String serverName;
        private final String command;
        private final List<String> args;
        private final Map<String, String> env;

        private Process process;
        private BufferedWriter stdin;
        private Thread readerThread;
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonElement>> pending =
                new ConcurrentHashMap<>();
        private volatile boolean closed = false;

        public McpConnection(String serverName, String command,
                             List<String> args, Map<String, String> env) {
            this.serverName = serverName;
            this.command = command;
            this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
            this.env = env != null ? new HashMap<>(env) : new HashMap<>();
        }

        /** 返回此连接对应的服务器名称。 */
        public String getServerName() {
            return serverName;
        }

        /** 启动服务器进程并开启 stdout 读取线程。 */
        public void connect() throws IOException {
            List<String> cmd = new ArrayList<>();
            cmd.add(command);
            cmd.addAll(args);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Map<String, String> procEnv = pb.environment();
            procEnv.putAll(env);

            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));

            // Drain stderr in background to avoid pipe buffer deadlock
            Thread stderrDrainer = new Thread(() -> {
                try (BufferedReader err = new BufferedReader(new InputStreamReader(
                        process.getErrorStream(), StandardCharsets.UTF_8))) {
                    while (err.readLine() != null) {
                        // discard
                    }
                } catch (IOException ignored) {
                }
            }, "mcp-stderr-" + serverName);
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();

            readerThread = new Thread(this::readLoop, "mcp-stdout-" + serverName);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        /** Read newline-delimited JSON-RPC responses from stdout. */
        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    JsonObject msg;
                    try {
                        JsonElement el = JsonParser.parseString(line);
                        if (!el.isJsonObject()) {
                            continue;
                        }
                        msg = el.getAsJsonObject();
                    } catch (Exception e) {
                        continue;
                    }
                    if (!msg.has("id") || msg.get("id").isJsonNull()) {
                        continue;
                    }
                    int msgId;
                    try {
                        msgId = msg.get("id").getAsInt();
                    } catch (Exception e) {
                        continue;
                    }
                    CompletableFuture<JsonElement> fut = pending.remove(msgId);
                    if (fut == null) {
                        continue;
                    }
                    if (msg.has("error")) {
                        JsonObject err = msg.getAsJsonObject("error");
                        String code = err.has("code") ? err.get("code").toString() : "?";
                        String message = err.has("message") ? err.get("message").getAsString() : "";
                        fut.completeExceptionally(
                                new RuntimeException("MCP error " + code + ": " + message));
                    } else {
                        fut.complete(msg.has("result") ? msg.get("result") : null);
                    }
                }
            } catch (IOException ignored) {
            } finally {
                // Reject pending requests on EOF
                for (Map.Entry<Integer, CompletableFuture<JsonElement>> e : pending.entrySet()) {
                    e.getValue().completeExceptionally(
                            new RuntimeException("MCP server '" + serverName + "' closed"));
                }
                pending.clear();
            }
        }

        /** 发送 JSON-RPC 请求并阻塞等待响应。 */
        public JsonElement sendRequest(String method, JsonObject params) throws Exception {
            if (process == null || stdin == null || closed) {
                throw new IllegalStateException("MCP connection not open");
            }
            int reqId = nextId.getAndIncrement();
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", reqId);
            msg.addProperty("method", method);
            msg.add("params", params != null ? params : new JsonObject());

            CompletableFuture<JsonElement> fut = new CompletableFuture<>();
            pending.put(reqId, fut);

            synchronized (stdin) {
                stdin.write(GSON.toJson(msg));
                stdin.write('\n');
                stdin.flush();
            }

            return fut.get();
        }

        /** 发送无 params 的 JSON-RPC 请求。 */
        public JsonElement sendRequest(String method) throws Exception {
            return sendRequest(method, null);
        }

        /** 发送带超时的 JSON-RPC 请求。 */
        public JsonElement sendRequest(String method, JsonObject params,
                                       long timeout, TimeUnit unit) throws Exception {
            if (process == null || stdin == null || closed) {
                throw new IllegalStateException("MCP connection not open");
            }
            int reqId = nextId.getAndIncrement();
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("id", reqId);
            msg.addProperty("method", method);
            msg.add("params", params != null ? params : new JsonObject());

            CompletableFuture<JsonElement> fut = new CompletableFuture<>();
            pending.put(reqId, fut);

            synchronized (stdin) {
                stdin.write(GSON.toJson(msg));
                stdin.write('\n');
                stdin.flush();
            }

            try {
                return fut.get(timeout, unit);
            } catch (TimeoutException e) {
                pending.remove(reqId);
                throw e;
            }
        }

        /** 发送 JSON-RPC 通知（不期待响应）。 */
        public void sendNotification(String method, JsonObject params) {
            if (process == null || stdin == null || closed) {
                return;
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("jsonrpc", "2.0");
            msg.addProperty("method", method);
            msg.add("params", params != null ? params : new JsonObject());
            try {
                synchronized (stdin) {
                    stdin.write(GSON.toJson(msg));
                    stdin.write('\n');
                    stdin.flush();
                }
            } catch (IOException ignored) {
            }
        }

        /** 执行 MCP initialize 握手。 */
        public void initialize() throws Exception {
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2024-11-05");
            params.add("capabilities", new JsonObject());
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "mini-claude");
            clientInfo.addProperty("version", "1.0.0");
            params.add("clientInfo", clientInfo);

            sendRequest("initialize", params);
            sendNotification("notifications/initialized", new JsonObject());
        }

        /** 带超时的 MCP initialize 握手。 */
        public void initialize(long timeout, TimeUnit unit) throws Exception {
            JsonObject params = new JsonObject();
            params.addProperty("protocolVersion", "2024-11-05");
            params.add("capabilities", new JsonObject());
            JsonObject clientInfo = new JsonObject();
            clientInfo.addProperty("name", "mini-claude");
            clientInfo.addProperty("version", "1.0.0");
            params.add("clientInfo", clientInfo);

            sendRequest("initialize", params, timeout, unit);
            sendNotification("notifications/initialized", new JsonObject());
        }

        /** 发现此服务器提供的工具列表。 */
        public List<Map<String, Object>> listTools() throws Exception {
            return listTools(0, null);
        }

        /** 带超时的工具列表发现。 */
        public List<Map<String, Object>> listTools(long timeout, TimeUnit unit) throws Exception {
            JsonElement result = (timeout > 0 && unit != null)
                    ? sendRequest("tools/list", new JsonObject(), timeout, unit)
                    : sendRequest("tools/list", new JsonObject());

            List<Map<String, Object>> tools = new ArrayList<>();
            if (result == null || !result.isJsonObject()) {
                return tools;
            }
            JsonObject obj = result.getAsJsonObject();
            if (!obj.has("tools") || !obj.get("tools").isJsonArray()) {
                return tools;
            }
            for (JsonElement el : obj.getAsJsonArray("tools")) {
                if (!el.isJsonObject()) continue;
                JsonObject t = el.getAsJsonObject();
                Map<String, Object> tool = new HashMap<>();
                tool.put("name", t.has("name") ? t.get("name").getAsString() : "");
                tool.put("description", t.has("description") && !t.get("description").isJsonNull()
                        ? t.get("description").getAsString() : "");
                tool.put("inputSchema", t.has("inputSchema") ? t.get("inputSchema") : null);
                tool.put("serverName", serverName);
                tools.add(tool);
            }
            return tools;
        }

        /** 调用 MCP 工具并返回文本结果。 */
        public String callTool(String name, Map<String, Object> args) throws Exception {
            JsonObject params = new JsonObject();
            params.addProperty("name", name);
            JsonObject arguments = new JsonObject();
            if (args != null) {
                for (Map.Entry<String, Object> e : args.entrySet()) {
                    arguments.add(e.getKey(), GSON.toJsonTree(e.getValue()));
                }
            }
            params.add("arguments", arguments);

            JsonElement result = sendRequest("tools/call", params);
            if (result != null && result.isJsonObject()) {
                JsonObject obj = result.getAsJsonObject();
                if (obj.has("content") && obj.get("content").isJsonArray()) {
                    JsonArray content = obj.getAsJsonArray("content");
                    List<String> texts = new ArrayList<>();
                    for (JsonElement c : content) {
                        if (c.isJsonObject()) {
                            JsonObject item = c.getAsJsonObject();
                            if (item.has("type") && "text".equals(item.get("type").getAsString())
                                    && item.has("text")) {
                                texts.add(item.get("text").getAsString());
                            }
                        }
                    }
                    return String.join("\n", texts);
                }
            }
            return result != null ? GSON.toJson(result) : "null";
        }

        /** 终止服务器进程并 reject 所有 pending 请求。 */
        @Override
        public void close() {
            closed = true;
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread = null;
            }
            if (stdin != null) {
                try {
                    stdin.close();
                } catch (IOException ignored) {
                }
                stdin = null;
            }
            if (process != null) {
                process.destroyForcibly();
                try {
                    process.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                process = null;
            }
            for (CompletableFuture<JsonElement> fut : pending.values()) {
                if (!fut.isDone()) {
                    fut.completeExceptionally(
                            new RuntimeException("MCP server '" + serverName + "' closed"));
                }
            }
            pending.clear();
        }
    }

    // ─── MCP 管理器 ─────────────────────────────────────────────

    /**
     * 管理所有 MCP 连接：{@link #loadAndConnect()} 一次，
     * 之后通过 {@link #getToolDefinitions()} 与 {@link #callTool(String, Map)} 集成到 Agent。
     */
    public static class McpManager {
        private final Map<String, McpConnection> connections = new HashMap<>();
        private final List<Map<String, Object>> tools = new ArrayList<>();
        private boolean connected = false;

        /** 读取配置、连接所有 MCP 服务器并发现工具。 */
        public synchronized void loadAndConnect() {
            if (connected) {
                return;
            }
            connected = true;

            Map<String, Map<String, Object>> configs = loadConfigs();
            if (configs.isEmpty()) {
                return;
            }

            long timeoutSec = 15;

            for (Map.Entry<String, Map<String, Object>> entry : configs.entrySet()) {
                String name = entry.getKey();
                Map<String, Object> cfg = entry.getValue();
                String command = String.valueOf(cfg.get("command"));
                List<String> args = toStringList(cfg.get("args"));
                Map<String, String> env = toStringMap(cfg.get("env"));

                McpConnection conn = new McpConnection(name, command, args, env);
                try {
                    conn.connect();
                    conn.initialize(timeoutSec, TimeUnit.SECONDS);
                    List<Map<String, Object>> serverTools =
                            conn.listTools(timeoutSec, TimeUnit.SECONDS);
                    connections.put(name, conn);
                    tools.addAll(serverTools);
                    System.out.println("[mcp] Connected to '" + name + "' — "
                            + serverTools.size() + " tools");
                    System.out.flush();
                } catch (Exception e) {
                    System.out.println("[mcp] Failed to connect to '" + name + "': " + e.getMessage());
                    System.out.flush();
                    conn.close();
                }
            }
        }

        /** 返回 Anthropic API 格式的工具定义（含 mcp__ 前缀）。 */
        public List<Map<String, Object>> getToolDefinitions() {
            List<Map<String, Object>> defs = new ArrayList<>();
            for (Map<String, Object> t : tools) {
                Map<String, Object> def = new HashMap<>();
                String serverName = String.valueOf(t.get("serverName"));
                String toolName = String.valueOf(t.get("name"));
                def.put("name", "mcp__" + serverName + "__" + toolName);
                Object desc = t.get("description");
                def.put("description",
                        (desc != null && !String.valueOf(desc).isEmpty())
                                ? String.valueOf(desc)
                                : "MCP tool " + toolName + " from " + serverName);
                Object schema = t.get("inputSchema");
                if (schema instanceof JsonElement) {
                    def.put("input_schema", jsonToMap((JsonElement) schema));
                } else if (schema instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) schema;
                    def.put("input_schema", m);
                } else {
                    Map<String, Object> empty = new HashMap<>();
                    empty.put("type", "object");
                    empty.put("properties", new HashMap<String, Object>());
                    def.put("input_schema", empty);
                }
                defs.add(def);
            }
            return defs;
        }

        /** 判断工具名是否为 MCP 前缀工具。 */
        public boolean isMcpTool(String name) {
            return name != null && name.startsWith("mcp__");
        }

        /** 将带前缀的工具调用路由到对应 MCP 服务器（阻塞）。 */
        public String callTool(String prefixedName, Map<String, Object> args) throws Exception {
            String[] parts = prefixedName.split("__", -1);
            if (parts.length < 3) {
                throw new IllegalArgumentException("Invalid MCP tool name: " + prefixedName);
            }
            String serverName = parts[1];
            // tool name might contain __
            StringBuilder toolNameSb = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) toolNameSb.append("__");
                toolNameSb.append(parts[i]);
            }
            String toolName = toolNameSb.toString();
            McpConnection conn = connections.get(serverName);
            if (conn == null) {
                throw new RuntimeException("MCP server '" + serverName + "' not connected");
            }
            return conn.callTool(toolName, args);
        }

        /** 断开所有 MCP 服务器连接。 */
        public synchronized void disconnectAll() {
            for (McpConnection conn : connections.values()) {
                conn.close();
            }
            connections.clear();
            tools.clear();
            connected = false;
        }

        // ─── 配置文件加载（private）────────────────────────────────

        private Map<String, Map<String, Object>> loadConfigs() {
            Map<String, Map<String, Object>> merged = new HashMap<>();

            // 1. Global: ~/.claude/settings.json
            Path globalPath = Paths.get(System.getProperty("user.home"), ".claude", "settings.json");
            mergeConfigFile(globalPath, merged);

            // 2. Project: .claude/settings.json (cwd)
            Path projectPath = Paths.get("").toAbsolutePath().resolve(".claude").resolve("settings.json");
            mergeConfigFile(projectPath, merged);

            // 3. Also check .mcp.json (Claude Code convention)
            Path mcpJsonPath = Paths.get("").toAbsolutePath().resolve(".mcp.json");
            mergeConfigFile(mcpJsonPath, merged);

            return merged;
        }

        private void mergeConfigFile(Path path, Map<String, Map<String, Object>> target) {
            if (!Files.exists(path)) {
                return;
            }
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                JsonElement rawEl = JsonParser.parseString(text);
                if (!rawEl.isJsonObject()) {
                    return;
                }
                JsonObject raw = rawEl.getAsJsonObject();
                JsonObject servers = raw.has("mcpServers") && raw.get("mcpServers").isJsonObject()
                        ? raw.getAsJsonObject("mcpServers")
                        : raw;

                for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
                    if (!e.getValue().isJsonObject()) continue;
                    JsonObject config = e.getValue().getAsJsonObject();
                    if (config.has("command")) {
                        target.put(e.getKey(), jsonObjectToMap(config));
                    }
                }
            } catch (Exception ignored) {
                // skip malformed config
            }
        }

        @SuppressWarnings("unchecked")
        private static List<String> toStringList(Object o) {
            List<String> list = new ArrayList<>();
            if (o instanceof List) {
                for (Object item : (List<?>) o) {
                    list.add(String.valueOf(item));
                }
            } else if (o instanceof JsonArray) {
                for (JsonElement el : (JsonArray) o) {
                    list.add(el.getAsString());
                }
            }
            return list;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, String> toStringMap(Object o) {
            Map<String, String> map = new HashMap<>();
            if (o instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                    map.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            } else if (o instanceof JsonObject) {
                for (Map.Entry<String, JsonElement> e : ((JsonObject) o).entrySet()) {
                    map.put(e.getKey(), e.getValue().getAsString());
                }
            }
            return map;
        }

        private static Map<String, Object> jsonObjectToMap(JsonObject obj) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                map.put(e.getKey(), jsonElementToObject(e.getValue()));
            }
            return map;
        }

        private static Map<String, Object> jsonToMap(JsonElement el) {
            if (el == null || el.isJsonNull()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("type", "object");
                empty.put("properties", new HashMap<String, Object>());
                return empty;
            }
            if (el.isJsonObject()) {
                return jsonObjectToMap(el.getAsJsonObject());
            }
            Map<String, Object> empty = new HashMap<>();
            empty.put("type", "object");
            empty.put("properties", new HashMap<String, Object>());
            return empty;
        }

        private static Object jsonElementToObject(JsonElement el) {
            if (el == null || el.isJsonNull()) return null;
            if (el.isJsonPrimitive()) {
                if (el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
                if (el.getAsJsonPrimitive().isNumber()) return el.getAsNumber();
                return el.getAsString();
            }
            if (el.isJsonArray()) {
                List<Object> list = new ArrayList<>();
                for (JsonElement item : el.getAsJsonArray()) {
                    list.add(jsonElementToObject(item));
                }
                return list;
            }
            if (el.isJsonObject()) {
                return jsonObjectToMap(el.getAsJsonObject());
            }
            return el.toString();
        }
    }
}
