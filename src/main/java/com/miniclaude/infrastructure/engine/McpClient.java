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
 * MCP（Model Context Protocol）客户端 —— 通过 stdio 子进程与 MCP 服务器通信并暴露工具给 Agent。
 *
 * <p><b>核心职责</b></p>
 * <ul>
 *   <li>{@link McpConnection}：管理单个 MCP 服务器子进程，以换行分隔的 JSON-RPC 2.0 与 stdin/stdout 通信。</li>
 *   <li>{@link McpManager}：从配置文件加载多个 server 定义，批量连接、initialize 握手、发现工具，
 *       并以 {@code mcp__{serverName}__{toolName}} 前缀注册到 Agent 工具列表。</li>
 *   <li>工具调用路由：解析带前缀的工具名，将请求转发到对应 {@link McpConnection#callTool}。</li>
 * </ul>
 *
 * <p><b>在系统中的位置</b></p>
 * <ul>
 *   <li>包路径：{@code infrastructure/engine}。</li>
 *   <li>调用方：{@link Agent} 在首次 chat 时初始化 {@link McpManager#loadAndConnect()}。</li>
 *   <li>配置来源（按 merge 顺序）：{@code ~/.claude/settings.json}、
 *       项目 {@code .claude/settings.json}、{@code .mcp.json}。</li>
 *   <li>对应 Python 版 {@code mcp_client.py} 的 Java 移植。</li>
 * </ul>
 *
 * <p><b>通信模型</b></p>
 * <ul>
 *   <li>请求/响应：带 {@code id} 的 JSON-RPC；响应由后台 reader 线程匹配 {@code pending} 中的
 *       {@link CompletableFuture}。</li>
 *   <li>通知：无 {@code id}，不等待响应（如 {@code notifications/initialized}）。</li>
 *   <li>stderr 在独立 daemon 线程 drain，避免管道缓冲区满导致子进程阻塞。</li>
 * </ul>
 *
 * <p>本类为工具类容器，禁止实例化。</p>
 *
 * @see Agent
 */
public final class McpClient {

    /** 私有构造器：工具类不可实例化。 */
    private McpClient() {}

    /** 全局 Gson 实例，用于 JSON 序列化/反序列化 JSON-RPC 消息与工具参数。 */
    private static final Gson GSON = new Gson();

    // ─── 单 MCP 连接（每个 server 一个）──────────────────────────

    /**
     * 单个 MCP 服务器连接：封装子进程生命周期、JSON-RPC 收发与工具发现/调用。
     *
     * <p>实现 {@link AutoCloseable}，{@link #close()} 会强制终止进程并 reject 所有 pending 请求。</p>
     */
    public static class McpConnection implements AutoCloseable {
        /** 配置中的服务器逻辑名称，用于工具名前缀 {@code mcp__{serverName}__}。 */
        private final String serverName;
        /** 启动 MCP 服务器的可执行命令（如 {@code npx}、{@code node}）。 */
        private final String command;
        /** 命令行参数列表（不可变副本）。 */
        private final List<String> args;
        /** 附加环境变量（合并进 {@link ProcessBuilder} 的 environment）。 */
        private final Map<String, String> env;

        /** 已启动的子进程；{@code connect()} 前为 null。 */
        private Process process;
        /** 写入 JSON-RPC 到子进程 stdin 的 writer；需 synchronized 写入。 */
        private BufferedWriter stdin;
        /** 从 stdout 读取 JSON-RPC 响应的后台线程。 */
        private Thread readerThread;
        /** 单调递增的 JSON-RPC 请求 id 生成器。 */
        private final AtomicInteger nextId = new AtomicInteger(1);
        /** 待响应请求 id → CompletableFuture 映射；reader 线程完成或异常完成。 */
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonElement>> pending =
                new ConcurrentHashMap<>();
        /** 连接是否已关闭；关闭后 send* 方法拒绝或忽略。 */
        private volatile boolean closed = false;

        /**
         * 构造 MCP 连接描述（尚未启动进程）。
         *
         * @param serverName 服务器名称
         * @param command    启动命令
         * @param args       参数列表，可为 null（视为空列表）
         * @param env        环境变量，可为 null（视为空 Map）
         */
        public McpConnection(String serverName, String command,
                             List<String> args, Map<String, String> env) {
            this.serverName = serverName;
            this.command = command;
            this.args = args != null ? new ArrayList<>(args) : new ArrayList<>();
            this.env = env != null ? new HashMap<>(env) : new HashMap<>();
        }

        /**
         * 返回此连接对应的服务器名称。
         *
         * @return serverName
         * @sideeffects 无
         */
        public String getServerName() {
            return serverName;
        }

        /**
         * 启动 MCP 服务器子进程，开启 stderr drain 线程与 stdout JSON-RPC reader 线程。
         *
         * @throws IOException 进程启动或流创建失败
         * @sideeffects 启动子进程；创建两个 daemon 后台线程
         */
        public void connect() throws IOException {
            // 组装完整命令行
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

            // 启动 stdout 读循环线程
            readerThread = new Thread(this::readLoop, "mcp-stdout-" + serverName);
            readerThread.setDaemon(true);
            readerThread.start();
        }

        /**
         * 从 stdout 按行读取 JSON-RPC 响应，匹配 {@code pending} 中的请求 id 并完成 Future。
         *
         * <p>EOF 或 IOException 时 reject 所有 pending 请求。非 JSON、无 id、未知 id 的行静默忽略。</p>
         *
         * @sideeffects 修改 {@code pending}；可能 complete/completeExceptionally Future
         */
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
                    // 仅处理带非 null id 的响应（通知无 id）
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
                    // 错误响应 vs 成功 result
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

        /**
         * 发送 JSON-RPC 请求并阻塞等待响应（无超时）。
         *
         * @param method JSON-RPC method 名
         * @param params 参数对象，null 时发送空 {@link JsonObject}
         * @return 响应 {@code result} 字段的 {@link JsonElement}，可能为 null
         * @throws IllegalStateException 连接未建立或已关闭
         * @throws Exception             Future 等待被中断或 MCP 返回 error
         * @sideeffects 写入 stdin；在 {@code pending} 注册请求
         */
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

        /**
         * 发送无 params 的 JSON-RPC 请求（params 为空对象）。
         *
         * @param method JSON-RPC method
         * @return 响应 result
         * @throws Exception 同 {@link #sendRequest(String, JsonObject)}
         * @sideeffects 同 {@link #sendRequest(String, JsonObject)}
         */
        public JsonElement sendRequest(String method) throws Exception {
            return sendRequest(method, null);
        }

        /**
         * 发送 JSON-RPC 请求并在指定超时内等待响应。
         *
         * @param method  JSON-RPC method
         * @param params  参数
         * @param timeout 超时数值
         * @param unit    超时单位
         * @return 响应 result
         * @throws IllegalStateException 连接未打开
         * @throws TimeoutException      超时未收到响应
         * @throws Exception             其他等待/ MCP 错误
         * @sideeffects 超时时从 {@code pending} 移除 reqId，避免泄漏
         */
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

        /**
         * 发送 JSON-RPC 通知（无 id，不等待响应）；连接关闭时静默返回。
         *
         * @param method JSON-RPC method
         * @param params 参数，null 时用空对象
         * @sideeffects 可能写入 stdin；I/O 异常被忽略
         */
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

        /**
         * 执行 MCP initialize 握手（无超时）：{@code initialize} 请求 + {@code notifications/initialized} 通知。
         *
         * @throws Exception initialize 请求失败
         * @sideeffects 与子进程完成协议握手
         */
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

        /**
         * 带超时的 MCP initialize 握手。
         *
         * @param timeout 超时
         * @param unit    单位
         * @throws Exception 超时或 initialize 失败
         * @sideeffects 同 {@link #initialize()}
         */
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

        /**
         * 发现此 MCP 服务器提供的工具列表（无超时）。
         *
         * @return 工具 Map 列表，每项含 name/description/inputSchema/serverName
         * @throws Exception tools/list 失败
         * @sideeffects 向 MCP 服务器发送 tools/list 请求
         */
        public List<Map<String, Object>> listTools() throws Exception {
            return listTools(0, null);
        }

        /**
         * 发现工具列表，可选超时。
         *
         * @param timeout 大于 0 且 unit 非 null 时使用超时 sendRequest
         * @param unit    超时单位
         * @return 工具描述列表；result 格式异常时返回空列表
         * @throws Exception RPC 失败
         * @sideeffects 向 MCP 服务器发送 tools/list
         */
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
            // 解析每个 tool 定义
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

        /**
         * 调用 MCP 工具 {@code tools/call}，提取 content 中 type=text 的片段拼接返回。
         *
         * @param name 工具名（不含 mcp 前缀）
         * @param args 参数字典，null 视为空
         * @return 文本结果；无 text content 时返回 result 的 JSON 字符串或 {@code "null"}
         * @throws Exception RPC 或工具执行错误
         * @sideeffects 向 MCP 服务器发起 tools/call
         */
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

        /**
         * 关闭连接：标记 closed、中断 reader、关闭 stdin、强制销毁进程、reject 所有 pending。
         *
         * @sideeffects 终止子进程；完成或异常完成所有未决 Future
         */
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
     * MCP 连接与工具注册中心：一次性 {@link #loadAndConnect()}，供 Agent 获取工具定义与路由调用。
     *
     * <p>线程安全：{@link #loadAndConnect()} 与 {@link #disconnectAll()} 使用 synchronized；
     * 单连接内部通过 ConcurrentHashMap 与 stdin 锁保证 RPC 安全。</p>
     */
    public static class McpManager {
        /** 已成功连接的服务器名 → 连接实例。 */
        private final Map<String, McpConnection> connections = new HashMap<>();
        /** 所有已连接 server 的工具定义聚合列表（含 serverName 字段）。 */
        private final List<Map<String, Object>> tools = new ArrayList<>();
        /** 是否已执行过 loadAndConnect（含「无配置」的空连接尝试）。 */
        private boolean connected = false;

        /**
         * 读取 MCP 配置、连接各服务器、initialize、发现工具；重复调用时 no-op。
         *
         * <p>单个 server 连接失败仅打印日志并 close，不影响其他 server。</p>
         *
         * @sideeffects 可能启动多个子进程；修改 {@code connections}、{@code tools}、{@code connected}
         */
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

        /**
         * 返回 Anthropic Messages API 格式的工具定义列表（name 带 {@code mcp__} 前缀）。
         *
         * @return 工具 def 列表，每项含 name、description、input_schema
         * @sideeffects 无（只读内存中的 tools）
         */
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

        /**
         * 判断工具名是否为 MCP 前缀工具（{@code mcp__} 开头）。
         *
         * @param name 工具名
         * @return 是否为 MCP 工具
         * @sideeffects 无
         */
        public boolean isMcpTool(String name) {
            return name != null && name.startsWith("mcp__");
        }

        /**
         * 将带 {@code mcp__server__tool} 前缀的工具调用路由到对应连接并阻塞等待结果。
         *
         * @param prefixedName 完整工具名，至少含两个 {@code __} 分隔段
         * @param args         工具参数
         * @return MCP 工具返回的文本
         * @throws IllegalArgumentException 工具名格式非法
         * @throws RuntimeException         对应 server 未连接
         * @throws Exception                底层 callTool 失败
         * @sideeffects 向 MCP 子进程发起 tools/call
         */
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

        /**
         * 断开所有 MCP 连接并清空工具缓存，重置 connected 标志。
         *
         * @sideeffects 关闭所有子进程；清空 connections 与 tools
         */
        public synchronized void disconnectAll() {
            for (McpConnection conn : connections.values()) {
                conn.close();
            }
            connections.clear();
            tools.clear();
            connected = false;
        }

        // ─── 配置文件加载（private）────────────────────────────────

        /**
         * 从全局、项目 settings 与 .mcp.json 合并加载 mcpServers 配置。
         *
         * @return 服务器名 → 配置 Map（含 command/args/env 等）
         * @sideeffects 读取磁盘配置文件
         */
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

        /**
         * 解析单个 JSON 配置文件并 merge 到 target（后读文件覆盖同名 server）。
         *
         * @param path   配置文件路径
         * @param target 累积配置 Map
         * @sideeffects 可能修改 target；读失败或格式错误时静默跳过
         */
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
                // 支持根对象即 servers，或嵌套在 mcpServers 下
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

        /**
         * 将配置中的 args 字段转为 {@code List<String>}。
         *
         * @param o List 或 JsonArray，其他类型返回空列表
         * @return 字符串参数列表
         * @sideeffects 无
         */
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

        /**
         * 将配置中的 env 字段转为 {@code Map<String,String>}。
         *
         * @param o Map 或 JsonObject
         * @return 环境变量 Map
         * @sideeffects 无
         */
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

        /**
         * 将 {@link JsonObject} 递归转为 {@code Map<String,Object>}。
         *
         * @param obj JSON 对象
         * @return Java Map
         * @sideeffects 无
         */
        private static Map<String, Object> jsonObjectToMap(JsonObject obj) {
            Map<String, Object> map = new HashMap<>();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                map.put(e.getKey(), jsonElementToObject(e.getValue()));
            }
            return map;
        }

        /**
         * 将 JsonElement 转为 Anthropic input_schema 用的 Map；非 object 时返回空 object schema。
         *
         * @param el JSON 元素
         * @return Map 形式的 schema
         * @sideeffects 无
         */
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

        /**
         * 递归将 {@link JsonElement} 转为 Java 基本类型 / List / Map。
         *
         * @param el JSON 元素
         * @return 对应 Java 对象
         * @sideeffects 无
         */
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
