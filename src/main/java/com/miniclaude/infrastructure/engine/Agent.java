package com.miniclaude.infrastructure.engine;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent 核心循环。
 *
 * <p>职责：驱动 LLM 多轮对话与工具调用，支持 Anthropic Messages API 与 OpenAI 兼容后端
 *（含 Kimi/Moonshot）、流式输出、四层上下文压缩、计划模式、子 Agent、MCP、
 * Memory prefetch、/goal、/loop、Auto Mode 与预算控制。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层核心，
 * 由 {@link CliMain} 构建并驱动；子 Agent 由本类 fork 轻量实例。
 * 对应 Python {@code agent.py} 的 Java 11 移植。
 */
public class Agent {

    /** 危险操作确认回调（CLI 提供 y/n 交互）。 */
    @FunctionalInterface
    public interface ConfirmFn {
        boolean confirm(String message);
    }

    /** 计划模式退出时的审批回调（返回 choice / feedback）。 */
    @FunctionalInterface
    public interface PlanApprovalFn {
        Map<String, String> approve(String planContent);
    }

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type MAP_TYPE =
            new TypeToken<Map<String, Object>>() {}.getType();
    private static final java.lang.reflect.Type LIST_MAP_TYPE =
            new TypeToken<List<Map<String, Object>>>() {}.getType();

    private static final Map<String, Integer> MODEL_CONTEXT;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("claude-opus-4-6", 200000);
        m.put("claude-sonnet-4-6", 200000);
        m.put("claude-sonnet-4-20250514", 200000);
        m.put("claude-haiku-4-5-20251001", 200000);
        m.put("claude-opus-4-20250514", 200000);
        m.put("gpt-4o", 128000);
        m.put("gpt-4o-mini", 128000);
        // Kimi / Moonshot (OpenAI-compatible)
        m.put("kimi-k2.5", 262144);
        m.put("kimi-k2.6", 262144);
        m.put("kimi-k2.7-code", 262144);
        m.put("kimi-latest", 262144);
        m.put("moonshot-v1-8k", 8192);
        m.put("moonshot-v1-32k", 32768);
        m.put("moonshot-v1-128k", 131072);
        m.put("moonshot-v1-auto", 131072);
        MODEL_CONTEXT = Collections.unmodifiableMap(m);
    }

    private static final Set<String> SNIPPABLE_TOOLS = setOf(
            "read_file", "grep_search", "list_files", "run_shell");
    private static final String SNIP_PLACEHOLDER = "[Content snipped - re-read if needed]";
    private static final double SNIP_THRESHOLD = 0.60;
    private static final double SNIP_HOT_OVERRIDE = 0.75;
    private static final long MICROCOMPACT_IDLE_S = 5 * 60;
    private static final int KEEP_RECENT_RESULTS = 3;

    // ─── 实例字段 ─────────────────────────────────────────────────

    public String permissionMode;
    public final boolean thinking;
    public final String model;
    public final boolean useOpenAI;
    public final boolean isSubAgent;
    public List<Map<String, Object>> tools;
    public final Double maxCostUsd;
    public final Integer maxTurns;
    public ConfirmFn confirmFn;
    public final int effectiveWindow;
    public final String sessionId;
    public final String sessionStartTime;

    public int totalInputTokens;
    public int totalOutputTokens;
    public int totalCacheReadTokens;
    public int totalCacheCreationTokens;
    public int lastInputTokenCount;
    public int currentTurns;
    public double lastApiCallTime;

    public Map<String, Object> activeGoal;
    public volatile boolean goalStop;
    public Map<String, Object> pendingWakeup;
    public volatile boolean loopStop;
    public boolean scheduleWakeupEnabled;

    public int autoConsecutiveDenials;
    public int autoTotalDenials;

    private final AtomicBoolean aborted = new AtomicBoolean(false);
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private final Set<String> confirmedPaths = ConcurrentHashMap.newKeySet();
    private String prePlanMode;
    private String planFilePath;
    private PlanApprovalFn planApprovalFn;
    private boolean contextCleared;

    private final String thinkingMode;
    private List<String> outputBuffer;

    private final Map<String, Long> readFileState = new HashMap<>();

    private final McpClient.McpManager mcpManager = new McpClient.McpManager();
    private boolean mcpInitialized;

    private final Set<String> alreadySurfacedMemories = new HashSet<>();
    private int sessionMemoryBytes;
    private Memory.MemoryPrefetch memoryPrefetch;

    private final List<Map<String, Object>> anthropicMessages = new ArrayList<>();
    private final List<Map<String, Object>> openaiMessages = new ArrayList<>();

    private String userContextReminder = "";
    private String staticSystemPrompt;
    private String dynamicSystemContext = "";
    private String baseSystemPrompt;
    private String systemPrompt;

    private final String apiBase;
    private final String anthropicBaseUrl;
    private final String apiKey;

    private final ExecutorService toolExecutor = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "mini-claude-tool");
        t.setDaemon(true);
        return t;
    });

    // ─── Builder 构建器 ───────────────────────────────────────────

    /** 流式构建 {@link Agent} 实例。 */
    public static final class Builder {
        private String permissionMode = "default";
        private String model = "claude-opus-4-6";
        private String apiBase;
        private String anthropicBaseUrl;
        private String apiKey;
        private boolean thinking;
        private Double maxCostUsd;
        private Integer maxTurns;
        private ConfirmFn confirmFn;
        private String customSystemPrompt;
        private List<Map<String, Object>> customTools;
        private boolean isSubAgent;

        public Builder permissionMode(String v) { this.permissionMode = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder apiBase(String v) { this.apiBase = v; return this; }
        public Builder anthropicBaseUrl(String v) { this.anthropicBaseUrl = v; return this; }
        public Builder apiKey(String v) { this.apiKey = v; return this; }
        public Builder thinking(boolean v) { this.thinking = v; return this; }
        public Builder maxCostUsd(Double v) { this.maxCostUsd = v; return this; }
        public Builder maxTurns(Integer v) { this.maxTurns = v; return this; }
        public Builder confirmFn(ConfirmFn v) { this.confirmFn = v; return this; }
        public Builder customSystemPrompt(String v) { this.customSystemPrompt = v; return this; }
        public Builder customTools(List<Map<String, Object>> v) { this.customTools = v; return this; }
        public Builder isSubAgent(boolean v) { this.isSubAgent = v; return this; }

        /** 构建 Agent 实例。 */
        public Agent build() {
            return new Agent(permissionMode, model, apiBase, anthropicBaseUrl, apiKey,
                    thinking, maxCostUsd, maxTurns, confirmFn, customSystemPrompt,
                    customTools, isSubAgent);
        }
    }

    /** 创建新的 {@link Builder}。 */
    public static Builder builder() {
        return new Builder();
    }

    public Agent(
            String permissionMode,
            String model,
            String apiBase,
            String anthropicBaseUrl,
            String apiKey,
            boolean thinking,
            Double maxCostUsd,
            Integer maxTurns,
            ConfirmFn confirmFn,
            String customSystemPrompt,
            List<Map<String, Object>> customTools,
            boolean isSubAgent) {

        this.permissionMode = permissionMode != null ? permissionMode : "default";
        this.thinking = thinking;
        this.model = model != null ? model : "claude-opus-4-6";
        this.apiBase = apiBase;
        this.anthropicBaseUrl = anthropicBaseUrl != null && !anthropicBaseUrl.isEmpty()
                ? stripTrailingSlash(anthropicBaseUrl)
                : "https://api.anthropic.com";
        this.apiKey = resolveApiKey(apiKey, apiBase != null && !apiBase.isEmpty());
        this.useOpenAI = apiBase != null && !apiBase.isEmpty();
        this.isSubAgent = isSubAgent;
        this.tools = customTools != null ? new ArrayList<>(customTools)
                : new ArrayList<>(Tools.TOOL_DEFINITIONS);
        this.maxCostUsd = maxCostUsd;
        this.maxTurns = maxTurns;
        this.confirmFn = confirmFn;
        this.effectiveWindow = getContextWindow(this.model) - 20000;
        this.sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.sessionStartTime = Instant.now().toString();
        this.thinkingMode = resolveThinkingMode();

        if (customSystemPrompt != null) {
            this.staticSystemPrompt = customSystemPrompt;
            this.dynamicSystemContext = "";
            this.userContextReminder = "";
        } else {
            this.staticSystemPrompt = Prompt.buildStaticSystemPrompt();
            this.dynamicSystemContext = Prompt.buildDynamicSystemContext();
            this.userContextReminder = Prompt.buildUserContextReminder();
        }
        this.baseSystemPrompt = (dynamicSystemContext != null && !dynamicSystemContext.isEmpty())
                ? staticSystemPrompt + "\n\n" + dynamicSystemContext
                : staticSystemPrompt;

        if ("plan".equals(this.permissionMode)) {
            this.planFilePath = generatePlanFilePath();
            this.systemPrompt = this.baseSystemPrompt + buildPlanModePrompt();
        } else {
            this.systemPrompt = this.baseSystemPrompt;
        }

        if (this.useOpenAI) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", buildOpenAISystemContent());
            openaiMessages.add(sys);
        }
    }

    /**
     * OpenAI/Kimi system message body.
     * On Moonshot, project context (CLAUDE.md) must live in the system role — putting a large
     * {@code <system-reminder>} on the first user message breaks {@code $web_search} tool selection.
     */
    private String buildOpenAISystemContent() {
        StringBuilder sb = new StringBuilder(systemPrompt != null ? systemPrompt : "");
        if (isKimiBackend()) {
            sb.append(KIMI_WEB_SEARCH_HINT);
            if (userContextReminder != null && !userContextReminder.isEmpty()) {
                sb.append("\n\n").append(userContextReminder);
            }
        }
        return sb.toString();
    }

    /**
     * Appended to system prompt on Moonshot so the model uses hosted search.
     * @see <a href="https://platform.kimi.com/docs/guide/use-web-search">Kimi $web_search</a>
     */
    private static final String KIMI_WEB_SEARCH_HINT =
            "\n\n# Kimi hosted web search (MANDATORY)\n"
                    + "Internet search tool name on this backend is exactly `$web_search` "
                    + "(type=builtin_function). It is ALREADY registered in your tools list.\n"
                    + "For live/current data (news, sales stats, prices, docs online): call `$web_search` "
                    + "as your FIRST action.\n"
                    + "Forbidden for web research on this backend:\n"
                    + "  - tool_search with query web_search / search (there is no deferred web_search tool)\n"
                    + "  - run_shell curl/wget against Google/Bing/DuckDuckGo\n"
                    + "  - web_fetch of search-engine result pages\n"
                    + "  - asking the user to search for you\n";

    /** Kimi/Moonshot 内置联网搜索工具名（见 platform.kimi.com 文档）。 */
    public static final String KIMI_BUILTIN_WEB_SEARCH = "$web_search";

    private boolean isKimiBackend() {
        if (apiBase == null || apiBase.isEmpty()) {
            return false;
        }
        String b = apiBase.toLowerCase(Locale.ROOT);
        return b.contains("moonshot") || b.contains("kimi.com") || b.contains("kimi.ai");
    }

    private static String resolveApiKey(String apiKey, boolean openai) {
        if (apiKey != null && !apiKey.isEmpty()) {
            return apiKey;
        }
        if (openai) {
            String k = firstEnv("OPENAI_API_KEY", "MOONSHOT_API_KEY", "KIMI_API_KEY", "ANTHROPIC_API_KEY");
            return k;
        }
        return firstEnv("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "MOONSHOT_API_KEY", "KIMI_API_KEY");
    }

    private static String firstEnv(String... names) {
        for (String name : names) {
            String v = System.getenv(name);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    private static String stripTrailingSlash(String s) {
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static int getContextWindow(String model) {
        if (model == null) {
            return 200000;
        }
        Integer w = MODEL_CONTEXT.get(model);
        if (w != null) {
            return w;
        }
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("kimi") || m.contains("moonshot")) {
            return 131072;
        }
        return 200000;
    }

    private static boolean modelSupportsThinking(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("claude-3-") || m.contains("3-5-") || m.contains("3-7-")) {
            return false;
        }
        return m.contains("claude") && (m.contains("opus") || m.contains("sonnet") || m.contains("haiku"));
    }

    private static boolean modelSupportsAdaptiveThinking(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        return m.contains("opus-4-6") || m.contains("sonnet-4-6");
    }

    private static int getMaxOutputTokens(String model) {
        String m = model.toLowerCase(Locale.ROOT);
        if (m.contains("opus-4-6")) return 64000;
        if (m.contains("sonnet-4-6")) return 32000;
        if (m.contains("opus-4") || m.contains("sonnet-4") || m.contains("haiku-4")) return 32000;
        return 16384;
    }

    private String resolveThinkingMode() {
        if (!thinking) return "disabled";
        if (!modelSupportsThinking(model)) return "disabled";
        if (modelSupportsAdaptiveThinking(model)) return "adaptive";
        return "enabled";
    }

    private static Set<String> setOf(String... items) {
        Set<String> s = new HashSet<>();
        Collections.addAll(s, items);
        return Collections.unmodifiableSet(s);
    }

    // ─── Anthropic prefix caching ─────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildAnthropicSystem() {
        String planSuffix = "plan".equals(permissionMode) ? buildPlanModePrompt() : "";
        String dynamicText = ((dynamicSystemContext != null ? dynamicSystemContext : "") + planSuffix).trim();
        List<Map<String, Object>> blocks = new ArrayList<>();
        Map<String, Object> staticBlock = new LinkedHashMap<>();
        staticBlock.put("type", "text");
        staticBlock.put("text", staticSystemPrompt);
        Map<String, Object> cc = new LinkedHashMap<>();
        cc.put("type", "ephemeral");
        staticBlock.put("cache_control", cc);
        blocks.add(staticBlock);
        if (!dynamicText.isEmpty()) {
            Map<String, Object> dyn = new LinkedHashMap<>();
            dyn.put("type", "text");
            dyn.put("text", dynamicText);
            blocks.add(dyn);
        }
        return blocks;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> withCacheBreakpoints(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        List<Map<String, Object>> out = deepCopyMessages(messages);
        Map<String, Object> last = out.get(out.size() - 1);
        Object raw = last.get("content");
        List<Map<String, Object>> content;
        if (raw instanceof String) {
            content = new ArrayList<>();
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("type", "text");
            t.put("text", raw);
            content.add(t);
        } else if (raw instanceof List) {
            content = new ArrayList<>();
            for (Object o : (List<?>) raw) {
                if (o instanceof Map) {
                    content.add(new LinkedHashMap<>((Map<String, Object>) o));
                }
            }
        } else {
            return out;
        }
        if (content.isEmpty()) {
            return out;
        }
        Map<String, Object> tail = content.get(content.size() - 1);
        Object type = tail.get("type");
        if (!"thinking".equals(type) && !"redacted_thinking".equals(type)) {
            Map<String, Object> cc = new LinkedHashMap<>();
            cc.put("type", "ephemeral");
            tail.put("cache_control", cc);
            Map<String, Object> newLast = new LinkedHashMap<>(last);
            newLast.put("content", content);
            out.set(out.size() - 1, newLast);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deepCopyMessages(List<Map<String, Object>> messages) {
        // Gson round-trip keeps nested maps/lists mutable and free of API metadata side effects.
        String json = GSON.toJson(messages);
        List<Map<String, Object>> copy = GSON.fromJson(json, LIST_MAP_TYPE);
        return copy != null ? copy : new ArrayList<>();
    }

    /** 当前是否正在处理一轮 chat（供 SIGINT 判断是否 abort）。 */
    public boolean isProcessing() {
        return processing.get();
    }

    /** 中断当前任务，并停止 /loop 与 /goal。 */
    public void abort() {
        aborted.set(true);
        loopStop = true;
        goalStop = true;
    }

    /** 设置危险命令确认回调。 */
    public void setConfirmFn(ConfirmFn fn) {
        this.confirmFn = fn;
    }

    /** 设置计划模式审批回调。 */
    public void setPlanApprovalFn(PlanApprovalFn fn) {
        this.planApprovalFn = fn;
    }

    // ─── 计划模式切换 ───────────────────────────────────────────

    /** 在 plan 模式与先前模式之间切换；返回当前 permissionMode。 */
    public String togglePlanMode() {
        if ("plan".equals(permissionMode)) {
            permissionMode = prePlanMode != null ? prePlanMode : "default";
            prePlanMode = null;
            planFilePath = null;
            systemPrompt = baseSystemPrompt;
            if (useOpenAI && !openaiMessages.isEmpty()) {
                openaiMessages.get(0).put("content", systemPrompt);
            }
            Ui.printInfo("Exited plan mode → " + permissionMode + " mode");
            return permissionMode;
        } else {
            prePlanMode = permissionMode;
            permissionMode = "plan";
            planFilePath = generatePlanFilePath();
            systemPrompt = baseSystemPrompt + buildPlanModePrompt();
            if (useOpenAI && !openaiMessages.isEmpty()) {
                openaiMessages.get(0).put("content", systemPrompt);
            }
            Ui.printInfo("Entered plan mode. Plan file: " + planFilePath);
            return "plan";
        }
    }

    /** 返回累计 input/output token 用量。 */
    public Map<String, Integer> getTokenUsage() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("input", totalInputTokens);
        m.put("output", totalOutputTokens);
        return m;
    }

    // ─── 主入口：chat / runOnce ─────────────────────────────────

    /**
     * 处理一条用户消息：初始化 MCP（首次）、执行 Anthropic 或 OpenAI 对话循环、
     * 自动保存会话。
     */
    public void chat(String userMessage) {
        if (!mcpInitialized && !isSubAgent) {
            mcpInitialized = true;
            try {
                mcpManager.loadAndConnect();
                List<Map<String, Object>> mcpDefs = mcpManager.getToolDefinitions();
                if (mcpDefs != null && !mcpDefs.isEmpty()) {
                    tools = new ArrayList<>(tools);
                    tools.addAll(mcpDefs);
                }
            } catch (Exception e) {
                System.out.println("[mcp] Init failed: " + e.getMessage());
                System.out.flush();
            }
        }

        aborted.set(false);
        processing.set(true);
        try {
            if (useOpenAI) {
                chatOpenAI(userMessage);
            } else {
                chatAnthropic(userMessage);
            }
        } finally {
            processing.set(false);
        }
        if (!isSubAgent) {
            Ui.printDivider();
            autoSave();
        }
    }

    /**
     * 单次运行并收集输出文本与 token 增量（子 Agent / 评估器用，不直接打印 UI）。
     */
    public Map<String, Object> runOnce(String prompt) {
        outputBuffer = new ArrayList<>();
        int prevIn = totalInputTokens;
        int prevOut = totalOutputTokens;
        chat(prompt);
        StringBuilder sb = new StringBuilder();
        for (String s : outputBuffer) {
            sb.append(s);
        }
        outputBuffer = null;
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("input", totalInputTokens - prevIn);
        tokens.put("output", totalOutputTokens - prevOut);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", sb.toString());
        result.put("tokens", tokens);
        return result;
    }

    /** 释放 MCP 连接与工具线程池。 */
    public void close() {
        if (mcpInitialized) {
            mcpManager.disconnectAll();
        }
        toolExecutor.shutdownNow();
    }

    private void emitText(String text) {
        if (outputBuffer != null) {
            outputBuffer.add(text);
        } else {
            Ui.printAssistantText(text);
        }
    }

    // ─── REPL 命令 ───────────────────────────────────────────────

    /** 清空对话历史与 token 统计。 */
    public void clearHistory() {
        anthropicMessages.clear();
        openaiMessages.clear();
        if (useOpenAI) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", buildOpenAISystemContent());
            openaiMessages.add(sys);
        }
        totalInputTokens = 0;
        totalOutputTokens = 0;
        totalCacheReadTokens = 0;
        totalCacheCreationTokens = 0;
        lastInputTokenCount = 0;
        Ui.printInfo("Conversation cleared.");
    }

    /** 显示 token 用量、估算费用与预算/轮次信息。 */
    public void showCost() {
        double total = getCurrentCostUsd();
        String budgetInfo = maxCostUsd != null ? String.format(" / $%s budget", maxCostUsd) : "";
        String turnInfo = maxTurns != null
                ? String.format(" | Turns: %d/%d", currentTurns, maxTurns) : "";
        int cached = totalCacheReadTokens;
        int billedInput = totalInputTokens + totalCacheCreationTokens + cached;
        int hitRate = billedInput > 0 ? (int) Math.round((cached * 100.0) / billedInput) : 0;
        String cacheInfo = (cached > 0 || totalCacheCreationTokens > 0)
                ? String.format("\n  Cache: %d read / %d write (%d%% of input from cache)",
                cached, totalCacheCreationTokens, hitRate)
                : "";
        Ui.printInfo(String.format("Tokens: %d in / %d out%s\n  Estimated cost: $%.4f%s%s",
                totalInputTokens, totalOutputTokens, cacheInfo, total, budgetInfo, turnInfo));
    }

    private double getCurrentCostUsd() {
        double M = 1_000_000.0;
        return (totalInputTokens / M) * 3
                + (totalCacheReadTokens / M) * 0.3
                + (totalCacheCreationTokens / M) * 3.75
                + (totalOutputTokens / M) * 15;
    }

    private Map<String, Object> checkBudget() {
        Map<String, Object> r = new LinkedHashMap<>();
        if (maxCostUsd != null && getCurrentCostUsd() >= maxCostUsd) {
            r.put("exceeded", true);
            r.put("reason", String.format("Cost limit reached ($%.4f >= $%s)",
                    getCurrentCostUsd(), maxCostUsd));
            return r;
        }
        if (maxTurns != null && currentTurns >= maxTurns) {
            r.put("exceeded", true);
            r.put("reason", String.format("Turn limit reached (%d >= %d)", currentTurns, maxTurns));
            return r;
        }
        r.put("exceeded", false);
        return r;
    }

    /** 手动触发对话压缩。 */
    public void compact() {
        compactConversation();
    }

    // ─── /goal 目标追踪 ─────────────────────────────────────────

    /** 激活 session 级目标；返回注入 Agent 的首条 directive。 */
    public String setGoal(String condition) {
        activeGoal = new LinkedHashMap<>();
        activeGoal.put("condition", condition);
        activeGoal.put("iterations", 0);
        activeGoal.put("started_at", System.currentTimeMillis() / 1000.0);
        activeGoal.put("last_reason", null);
        Ui.printInfo("◎ /goal active — Stop hook condition: \"" + condition + "\"");
        return Autonomy.goalDirective(condition);
    }

    /** 显示当前 /goal 状态。 */
    public void showGoal() {
        if (activeGoal == null) {
            Ui.printInfo("No active goal. Set one with /goal <condition>.");
            return;
        }
        double secs = System.currentTimeMillis() / 1000.0
                - ((Number) activeGoal.get("started_at")).doubleValue();
        Object lastReason = activeGoal.get("last_reason");
        String last = lastReason != null ? "\n  last reason: " + lastReason : "";
        Ui.printInfo("◎ /goal active\n  condition: " + activeGoal.get("condition") + "\n"
                + "  iterations: " + activeGoal.get("iterations") + "\n  elapsed: "
                + String.format("%.1f", secs) + "s" + last);
    }

    /** 按 directive 启动目标追踪循环，直至达成、不可能或中断。 */
    public void pursueGoal(String directive) {
        if (activeGoal == null) {
            return;
        }
        goalStop = false;
        try {
            chat(directive);
            while (activeGoal != null && !goalStop && !aborted.get()) {
                Autonomy.GoalVerdict verdict = evaluateGoal((String) activeGoal.get("condition"));
                if (verdict.ok) {
                    int turns = ((Number) activeGoal.get("iterations")).intValue() + 1;
                    double secs = System.currentTimeMillis() / 1000.0
                            - ((Number) activeGoal.get("started_at")).doubleValue();
                    String plural = turns == 1 ? "" : "s";
                    Ui.printInfo("✓ Goal achieved (" + turns + " turn" + plural + ", "
                            + String.format("%.1f", secs) + "s): " + verdict.reason);
                    break;
                }
                if (verdict.impossible) {
                    Ui.printInfo("Hooks: Prompt hook condition judged impossible: " + verdict.reason);
                    break;
                }
                activeGoal.put("iterations", ((Number) activeGoal.get("iterations")).intValue() + 1);
                activeGoal.put("last_reason", verdict.reason);
                Ui.printInfo("Hooks: Prompt hook condition was not met: " + verdict.reason);

                Map<String, Object> budget = checkBudget();
                if (Boolean.TRUE.equals(budget.get("exceeded"))) {
                    Ui.printInfo("Goal stopped: " + budget.get("reason"));
                    break;
                }
                if (((Number) activeGoal.get("iterations")).intValue() >= Autonomy.GOAL_MAX_ITERATIONS) {
                    Ui.printInfo("Goal stopped: reached " + Autonomy.GOAL_MAX_ITERATIONS
                            + " iterations without meeting the condition.");
                    break;
                }
                if (goalStop || aborted.get()) {
                    break;
                }
                chat("Hooks: Prompt hook condition was not met: " + verdict.reason
                        + "\n\nKeep working toward the goal.");
            }
            if (goalStop || aborted.get()) {
                Ui.printInfo("Goal pursuit interrupted.");
            }
        } finally {
            activeGoal = null;
        }
    }

    private Autonomy.GoalVerdict evaluateGoal(String condition) {
        String transcript = extractLastAssistantText();
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(msg("user", Autonomy.GOAL_TRANSCRIPT_FRAMING));
        messages.add(msg("assistant", transcript != null && !transcript.isEmpty()
                ? transcript : "(no assistant output)"));
        messages.add(msg("user", Autonomy.goalJudgeUserMessage(condition)));
        try {
            String raw = runEvaluatorQuery(Autonomy.GOAL_EVALUATOR_SYSTEM, messages);
            return Autonomy.parseGoalVerdict(raw);
        } catch (Exception e) {
            return new Autonomy.GoalVerdict(false, "evaluator error: " + e.getMessage(), false);
        }
    }

    private String runEvaluatorQuery(String system, List<Map<String, Object>> messages) {
        return ApiHttp.withRetry(() -> {
            try {
                if (!useOpenAI) {
                    return anthropicNonStream(system, messages, 512);
                }
                List<Map<String, Object>> oai = new ArrayList<>();
                oai.add(msg("system", system));
                oai.addAll(messages);
                return openaiNonStream(oai, 512);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 3);
    }

    private String runClassifierQuery(String system, String user, int maxTokens) {
        return ApiHttp.withRetry(() -> {
            try {
                if (!useOpenAI) {
                    List<Map<String, Object>> messages = new ArrayList<>();
                    messages.add(msg("user", user));
                    return anthropicNonStream(system, messages, maxTokens);
                }
                List<Map<String, Object>> oai = new ArrayList<>();
                oai.add(msg("system", system));
                oai.add(msg("user", user));
                return openaiNonStream(oai, maxTokens);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, 3);
    }

    private Memory.SideQueryFn buildSideQuery() {
        return (system, userMessage) -> {
            if (!useOpenAI) {
                List<Map<String, Object>> messages = new ArrayList<>();
                messages.add(msg("user", userMessage));
                return anthropicNonStream(system, messages, 256);
            }
            List<Map<String, Object>> oai = new ArrayList<>();
            oai.add(msg("system", system));
            oai.add(msg("user", userMessage));
            return openaiNonStream(oai, 256);
        };
    }

    @SuppressWarnings("unchecked")
    private String extractLastAssistantText() {
        if (useOpenAI) {
            for (int i = openaiMessages.size() - 1; i >= 0; i--) {
                Map<String, Object> m = openaiMessages.get(i);
                if ("assistant".equals(m.get("role")) && m.get("content") instanceof String) {
                    return (String) m.get("content");
                }
            }
            return "";
        }
        for (int i = anthropicMessages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = anthropicMessages.get(i);
            if (!"assistant".equals(m.get("role"))) {
                continue;
            }
            Object content = m.get("content");
            if (content instanceof String) {
                return (String) content;
            }
            if (content instanceof List) {
                StringBuilder sb = new StringBuilder();
                for (Object b : (List<?>) content) {
                    if (b instanceof Map) {
                        Map<String, Object> block = (Map<String, Object>) b;
                        if ("text".equals(block.get("type")) && block.get("text") != null) {
                            sb.append(block.get("text"));
                        }
                    }
                }
                return sb.toString();
            }
        }
        return "";
    }

    // ─── /loop 循环调度 ─────────────────────────────────────────

    /** 解析并执行 /loop 命令（固定间隔或 dynamic 自调度）。 */
    @SuppressWarnings("unchecked")
    public void runLoop(String rawInput) {
        Map<String, Object> spec = Autonomy.parseLoopInput(rawInput);
        if (spec.containsKey("error")) {
            Ui.printInfo(String.valueOf(spec.get("error")));
            return;
        }
        boolean wantsCloud =
                ("interval".equals(spec.get("mode"))
                        && ((Number) spec.get("interval_seconds")).intValue()
                        >= Autonomy.OFFER_CLOUD_THRESHOLD_SECONDS)
                        || Autonomy.isDailyWording(rawInput);
        if (wantsCloud) {
            Ui.printInfo(
                    "(Real Claude Code would offer to convert this to a persistent cloud schedule "
                            + "that keeps running after the session ends. This teaching build has no cloud "
                            + "backend — continuing in-session.)");
        }
        loopStop = false;
        try {
            if ("interval".equals(spec.get("mode"))) {
                runLoopInterval(spec);
            } else {
                runLoopDynamic(spec);
            }
        } catch (Exception e) {
            Ui.printInfo("Loop interrupted.");
        }
    }

    private void runLoopInterval(Map<String, Object> spec) {
        Ui.printInfo("⟳ /loop scheduled every " + spec.get("interval_label")
                + " (session-only, not persisted — dies when this process exits). Ctrl+C to stop.");
        int iterations = 0;
        while (!loopStop && !aborted.get()) {
            iterations++;
            Ui.printInfo("⟳ loop tick " + iterations);
            chat(String.valueOf(spec.get("prompt")));

            Map<String, Object> budget = checkBudget();
            if (Boolean.TRUE.equals(budget.get("exceeded"))) {
                Ui.printInfo("Loop stopped: " + budget.get("reason"));
                break;
            }
            if (maxTurns != null && iterations >= maxTurns) {
                Ui.printInfo("Loop stopped: tick limit reached (" + iterations + " >= " + maxTurns + ").");
                break;
            }
            if (iterations >= Autonomy.LOOP_MAX_ITERATIONS) {
                Ui.printInfo("Loop stopped: reached " + Autonomy.LOOP_MAX_ITERATIONS + " ticks.");
                break;
            }
            double seconds = ((Number) spec.get("interval_seconds")).doubleValue();
            if (interruptibleSleep(seconds)) {
                Ui.printInfo("Loop stopped.");
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void runLoopDynamic(Map<String, Object> spec) {
        Ui.printInfo(
                "⟳ /loop dynamic (self-paced) — the model schedules its own next run, or ends the "
                        + "loop. Ctrl+C to stop.");
        boolean hadTool = false;
        for (Map<String, Object> t : tools) {
            if ("schedule_wakeup".equals(t.get("name"))) {
                hadTool = true;
                break;
            }
        }
        if (!hadTool) {
            tools = new ArrayList<>(tools);
            tools.add(Tools.jsonToMap(Autonomy.SCHEDULE_WAKEUP_TOOL));
        }
        scheduleWakeupEnabled = true;
        String prompt = String.valueOf(spec.get("prompt"));
        int iterations = 0;
        try {
            while (!loopStop && !aborted.get()) {
                iterations++;
                pendingWakeup = null;
                chat(Autonomy.dynamicLoopDirective(prompt));

                if (pendingWakeup == null) {
                    String plural = iterations == 1 ? "" : "s";
                    Ui.printInfo("⟳ Loop converged after " + iterations + " tick" + plural
                            + " (model scheduled no wakeup).");
                    break;
                }
                Map<String, Object> budget = checkBudget();
                if (Boolean.TRUE.equals(budget.get("exceeded"))) {
                    Ui.printInfo("Loop stopped: " + budget.get("reason"));
                    break;
                }
                if (maxTurns != null && iterations >= maxTurns) {
                    Ui.printInfo("Loop stopped: tick limit reached (" + iterations
                            + " >= " + maxTurns + ").");
                    break;
                }
                if (iterations >= Autonomy.LOOP_MAX_ITERATIONS) {
                    Ui.printInfo("Loop stopped: reached " + Autonomy.LOOP_MAX_ITERATIONS + " ticks.");
                    break;
                }
                int delay = ((Number) pendingWakeup.get("delay_seconds")).intValue();
                Ui.printInfo("⟳ next run in " + delay + "s — " + pendingWakeup.get("reason"));
                Object nextPrompt = pendingWakeup.get("prompt");
                if (nextPrompt instanceof String && !((String) nextPrompt).isEmpty()) {
                    prompt = (String) nextPrompt;
                }
                if (interruptibleSleep(delay)) {
                    Ui.printInfo("Loop stopped.");
                    break;
                }
            }
        } finally {
            if (!hadTool) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> t : tools) {
                    if (!"schedule_wakeup".equals(t.get("name"))) {
                        filtered.add(t);
                    }
                }
                tools = filtered;
            }
            scheduleWakeupEnabled = false;
            pendingWakeup = null;
        }
    }

    private String executeScheduleWakeup(Map<String, Object> inp) {
        int delay = Autonomy.clampWakeupDelay(inp.get("delaySeconds"));
        String reason = inp.get("reason") instanceof String ? (String) inp.get("reason") : "";
        String prompt = inp.get("prompt") instanceof String ? (String) inp.get("prompt") : "";
        pendingWakeup = new LinkedHashMap<>();
        pendingWakeup.put("delay_seconds", delay);
        pendingWakeup.put("reason", reason);
        pendingWakeup.put("prompt", prompt);
        return "Wakeup scheduled in " + delay
                + "s. The loop will resume then; end your turn now.";
    }

    private boolean interruptibleSleep(double seconds) {
        long start = System.currentTimeMillis();
        long targetMs = (long) (seconds * 1000);
        while (System.currentTimeMillis() - start < targetMs) {
            if (loopStop || aborted.get()) {
                return true;
            }
            try {
                Thread.sleep(Math.min(200, targetMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    /** 停止 /loop 循环。 */
    public void stopLoop() {
        loopStop = true;
    }

    /** 停止 /goal 追踪。 */
    public void stopGoal() {
        goalStop = true;
    }

    // ─── Auto Mode 工具分类 ───────────────────────────────────────

    private Map<String, String> classifyToolCall(String toolName, Map<String, Object> inp) {
        Map<String, String> base = Tools.checkPermission(toolName, inp, "default", planFilePath);
        if ("deny".equals(base.get("action"))) {
            return base;
        }
        if (Autonomy.AUTO_MODE_FAST_PATH_TOOLS.contains(toolName)) {
            Map<String, String> allow = new HashMap<>();
            allow.put("action", "allow");
            return allow;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            return autoFallback(toolName + " (auto-mode classifier unavailable)");
        }
        Autonomy.BlockVerdict verdict;
        try {
            Map<String, Object> rules = Autonomy.loadAutoModeRules();
            List<Map<String, Object>> history = useOpenAI ? openaiMessages : anthropicMessages;
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("tool_name", toolName);
            pending.put("input", inp);
            String transcript = Autonomy.buildClassifierTranscript(history, pending);
            String system = Autonomy.buildClassifierSystem(rules);
            String claudeMd = Prompt.loadClaudeMd();
            String s1Raw = runClassifierQuery(system,
                    Autonomy.classifierUserMessage(rules, transcript,
                            String.valueOf(rules.get("suffix_stage1")), claudeMd), 256);
            Autonomy.BlockVerdict s1 = Autonomy.parseBlockVerdict(s1Raw);
            if (!s1.block) {
                verdict = s1;
            } else {
                String s2Raw = runClassifierQuery(system,
                        Autonomy.classifierUserMessage(rules, transcript,
                                String.valueOf(rules.get("suffix_stage2")), claudeMd), 1024);
                verdict = Autonomy.parseBlockVerdict(s2Raw);
            }
        } catch (Exception e) {
            verdict = new Autonomy.BlockVerdict(true, "classifier error: " + e.getMessage());
        }

        if (!verdict.block) {
            autoConsecutiveDenials = 0;
            Map<String, String> allow = new HashMap<>();
            allow.put("action", "allow");
            return allow;
        }
        autoConsecutiveDenials++;
        autoTotalDenials++;
        int maxConsec = Autonomy.DENIAL_LIMITS.get("max_consecutive");
        int maxTotal = Autonomy.DENIAL_LIMITS.get("max_total");
        if (autoConsecutiveDenials >= maxConsec || autoTotalDenials >= maxTotal) {
            Ui.printInfo("Auto Mode: denial limit reached — handing back to manual confirmation.");
            return autoFallback("[Auto Mode blocked] " + verdict.reason);
        }
        Map<String, String> deny = new HashMap<>();
        deny.put("action", "deny");
        deny.put("message", "[Auto Mode] " + verdict.reason);
        return deny;
    }

    private Map<String, String> autoFallback(String message) {
        if (confirmFn != null) {
            Map<String, String> r = new HashMap<>();
            r.put("action", "confirm");
            r.put("message", message);
            return r;
        }
        Map<String, String> r = new HashMap<>();
        r.put("action", "deny");
        r.put("message", message + " (headless — denied)");
        return r;
    }

    private String childPermissionMode() {
        if ("plan".equals(permissionMode)) return "plan";
        if ("auto".equals(permissionMode)) return "auto";
        return "bypassPermissions";
    }

    // ─── 会话持久化 ─────────────────────────────────────────────

    /** 从保存的会话数据恢复 anthropicMessages 或 openaiMessages。 */
    @SuppressWarnings("unchecked")
    public void restoreSession(Map<String, Object> data) {
        if (data.get("anthropicMessages") instanceof List) {
            anthropicMessages.clear();
            anthropicMessages.addAll((List<Map<String, Object>>) data.get("anthropicMessages"));
        }
        if (data.get("openaiMessages") instanceof List) {
            openaiMessages.clear();
            openaiMessages.addAll((List<Map<String, Object>>) data.get("openaiMessages"));
        }
        Ui.printInfo("Session restored (" + getMessageCount() + " messages).");
    }

    private int getMessageCount() {
        return useOpenAI ? openaiMessages.size() : anthropicMessages.size();
    }

    private void autoSave() {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("id", sessionId);
            metadata.put("model", model);
            metadata.put("cwd", Paths.get("").toAbsolutePath().toString());
            metadata.put("startTime", sessionStartTime);
            metadata.put("messageCount", getMessageCount());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("metadata", metadata);
            data.put("anthropicMessages", useOpenAI ? null : anthropicMessages);
            data.put("openaiMessages", useOpenAI ? openaiMessages : null);
            Session.saveSession(sessionId, data);
        } catch (Exception ignored) {
        }
    }

    // ─── 自动压缩 ───────────────────────────────────────────────

    private void checkAndCompact() {
        if (lastInputTokenCount > effectiveWindow * 0.85) {
            Ui.printInfo("Context window filling up, compacting conversation...");
            compactConversation();
        }
    }

    private void compactConversation() {
        if (useOpenAI) {
            compactOpenAI();
        } else {
            compactAnthropic();
        }
        Ui.printInfo("Conversation compacted.");
    }

    @SuppressWarnings("unchecked")
    private void compactAnthropic() {
        if (anthropicMessages.size() < 4) {
            return;
        }
        Map<String, Object> lastUserMsg = anthropicMessages.get(anthropicMessages.size() - 1);
        List<Map<String, Object>> summaryMessages = new ArrayList<>(anthropicMessages.subList(0, anthropicMessages.size() - 1));
        summaryMessages.add(msg("user",
                "Summarize the conversation so far in a concise paragraph, preserving key decisions, file paths, and context needed to continue the work."));
        String summaryText;
        try {
            summaryText = ApiHttp.withRetry(() -> {
                try {
                    return anthropicNonStream(
                            "You are a conversation summarizer. Be concise but preserve important details.",
                            summaryMessages, 2048);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 3);
        } catch (Exception e) {
            summaryText = "No summary available.";
        }
        anthropicMessages.clear();
        anthropicMessages.add(msg("user", "[Previous conversation summary]\n" + summaryText));
        anthropicMessages.add(msg("assistant",
                "Understood. I have the context from our previous conversation. How can I continue helping?"));
        if ("user".equals(lastUserMsg.get("role"))) {
            anthropicMessages.add(lastUserMsg);
        }
        lastInputTokenCount = 0;
    }

    @SuppressWarnings("unchecked")
    private void compactOpenAI() {
        if (openaiMessages.size() < 5) {
            return;
        }
        Map<String, Object> systemMsg = openaiMessages.get(0);
        Map<String, Object> lastUserMsg = openaiMessages.get(openaiMessages.size() - 1);
        List<Map<String, Object>> summaryMessages = new ArrayList<>();
        summaryMessages.add(msg("system",
                "You are a conversation summarizer. Be concise but preserve important details."));
        summaryMessages.addAll(openaiMessages.subList(1, openaiMessages.size() - 1));
        summaryMessages.add(msg("user",
                "Summarize the conversation so far in a concise paragraph, preserving key decisions, file paths, and context needed to continue the work."));
        String summaryText;
        try {
            summaryText = ApiHttp.withRetry(() -> {
                try {
                    return openaiNonStream(summaryMessages, 2048);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, 3);
        } catch (Exception e) {
            summaryText = "No summary available.";
        }
        openaiMessages.clear();
        openaiMessages.add(systemMsg);
        openaiMessages.add(msg("user", "[Previous conversation summary]\n" + summaryText));
        openaiMessages.add(msg("assistant",
                "Understood. I have the context from our previous conversation. How can I continue helping?"));
        if ("user".equals(lastUserMsg.get("role"))) {
            openaiMessages.add(lastUserMsg);
        }
        lastInputTokenCount = 0;
    }

    // ─── 多层上下文压缩 pipeline ────────────────────────────────

    private void runCompressionPipeline() {
        if (useOpenAI) {
            budgetToolResultsOpenAI();
            snipStaleResultsOpenAI();
            microcompactOpenAI();
        } else {
            budgetToolResultsAnthropic();
            snipStaleResultsAnthropic();
            microcompactAnthropic();
        }
    }

    @SuppressWarnings("unchecked")
    private void budgetToolResultsAnthropic() {
        double utilization = effectiveWindow > 0
                ? (double) lastInputTokenCount / effectiveWindow : 0;
        if (utilization < 0.5) return;
        int budget = utilization > 0.7 ? 15000 : 30000;
        for (Map<String, Object> msg : anthropicMessages) {
            if (!"user".equals(msg.get("role")) || !(msg.get("content") instanceof List)) continue;
            for (Object o : (List<?>) msg.get("content")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> block = (Map<String, Object>) o;
                if ("tool_result".equals(block.get("type")) && block.get("content") instanceof String) {
                    String c = (String) block.get("content");
                    if (c.length() > budget) {
                        int keep = (budget - 80) / 2;
                        block.put("content", c.substring(0, keep)
                                + "\n\n[... budgeted: " + (c.length() - keep * 2)
                                + " chars truncated ...]\n\n"
                                + c.substring(c.length() - keep));
                    }
                }
            }
        }
    }

    private void budgetToolResultsOpenAI() {
        double utilization = effectiveWindow > 0
                ? (double) lastInputTokenCount / effectiveWindow : 0;
        if (utilization < 0.5) return;
        int budget = utilization > 0.7 ? 15000 : 30000;
        for (Map<String, Object> msg : openaiMessages) {
            if ("tool".equals(msg.get("role")) && msg.get("content") instanceof String) {
                String c = (String) msg.get("content");
                if (c.length() > budget) {
                    int keep = (budget - 80) / 2;
                    msg.put("content", c.substring(0, keep)
                            + "\n\n[... budgeted: " + (c.length() - keep * 2)
                            + " chars truncated ...]\n\n"
                            + c.substring(c.length() - keep));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void snipStaleResultsAnthropic() {
        double utilization = effectiveWindow > 0
                ? (double) lastInputTokenCount / effectiveWindow : 0;
        boolean cacheHot = lastApiCallTime > 0
                && (System.currentTimeMillis() / 1000.0 - lastApiCallTime) < MICROCOMPACT_IDLE_S;
        if (cacheHot && utilization < SNIP_HOT_OVERRIDE) return;
        if (utilization < SNIP_THRESHOLD) return;

        List<Map<String, Object>> results = new ArrayList<>();
        for (int mi = 0; mi < anthropicMessages.size(); mi++) {
            Map<String, Object> msg = anthropicMessages.get(mi);
            if (!"user".equals(msg.get("role")) || !(msg.get("content") instanceof List)) continue;
            List<?> content = (List<?>) msg.get("content");
            for (int bi = 0; bi < content.size(); bi++) {
                Object o = content.get(bi);
                if (!(o instanceof Map)) continue;
                Map<String, Object> block = (Map<String, Object>) o;
                if ("tool_result".equals(block.get("type")) && block.get("content") instanceof String
                        && !SNIP_PLACEHOLDER.equals(block.get("content"))) {
                    String toolUseId = String.valueOf(block.get("tool_use_id"));
                    Map<String, Object> toolInfo = findToolUseById(toolUseId);
                    if (toolInfo != null && SNIPPABLE_TOOLS.contains(String.valueOf(toolInfo.get("name")))) {
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("mi", mi);
                        r.put("bi", bi);
                        r.put("name", toolInfo.get("name"));
                        Object input = toolInfo.get("input");
                        if (input instanceof Map) {
                            r.put("file_path", ((Map<?, ?>) input).get("file_path"));
                        }
                        results.add(r);
                    }
                }
            }
        }
        if (results.size() <= KEEP_RECENT_RESULTS) return;

        Set<Integer> toSnip = new HashSet<>();
        Map<String, List<Integer>> seenFiles = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            Map<String, Object> r = results.get(i);
            if ("read_file".equals(r.get("name")) && r.get("file_path") != null) {
                String fp = String.valueOf(r.get("file_path"));
                seenFiles.computeIfAbsent(fp, k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> indices : seenFiles.values()) {
            if (indices.size() > 1) {
                for (int j = 0; j < indices.size() - 1; j++) {
                    toSnip.add(indices.get(j));
                }
            }
        }
        int snipBefore = results.size() - KEEP_RECENT_RESULTS;
        for (int i = 0; i < snipBefore; i++) {
            toSnip.add(i);
        }
        for (int idx : toSnip) {
            Map<String, Object> r = results.get(idx);
            int mi = ((Number) r.get("mi")).intValue();
            int bi = ((Number) r.get("bi")).intValue();
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) anthropicMessages.get(mi).get("content");
            content.get(bi).put("content", SNIP_PLACEHOLDER);
        }
    }

    private void snipStaleResultsOpenAI() {
        double utilization = effectiveWindow > 0
                ? (double) lastInputTokenCount / effectiveWindow : 0;
        boolean cacheHot = lastApiCallTime > 0
                && (System.currentTimeMillis() / 1000.0 - lastApiCallTime) < MICROCOMPACT_IDLE_S;
        if (cacheHot && utilization < SNIP_HOT_OVERRIDE) return;
        if (utilization < SNIP_THRESHOLD) return;
        List<Integer> toolMsgs = new ArrayList<>();
        for (int i = 0; i < openaiMessages.size(); i++) {
            Map<String, Object> msg = openaiMessages.get(i);
            if ("tool".equals(msg.get("role")) && msg.get("content") instanceof String
                    && !SNIP_PLACEHOLDER.equals(msg.get("content"))) {
                toolMsgs.add(i);
            }
        }
        if (toolMsgs.size() <= KEEP_RECENT_RESULTS) return;
        int snipCount = toolMsgs.size() - KEEP_RECENT_RESULTS;
        for (int i = 0; i < snipCount; i++) {
            openaiMessages.get(toolMsgs.get(i)).put("content", SNIP_PLACEHOLDER);
        }
    }

    @SuppressWarnings("unchecked")
    private void microcompactAnthropic() {
        if (lastApiCallTime <= 0
                || (System.currentTimeMillis() / 1000.0 - lastApiCallTime) < MICROCOMPACT_IDLE_S) {
            return;
        }
        List<int[]> allResults = new ArrayList<>();
        for (int mi = 0; mi < anthropicMessages.size(); mi++) {
            Map<String, Object> msg = anthropicMessages.get(mi);
            if (!"user".equals(msg.get("role")) || !(msg.get("content") instanceof List)) continue;
            List<?> content = (List<?>) msg.get("content");
            for (int bi = 0; bi < content.size(); bi++) {
                Object o = content.get(bi);
                if (!(o instanceof Map)) continue;
                Map<String, Object> block = (Map<String, Object>) o;
                if ("tool_result".equals(block.get("type")) && block.get("content") instanceof String) {
                    String c = (String) block.get("content");
                    if (!SNIP_PLACEHOLDER.equals(c) && !"[Old result cleared]".equals(c)) {
                        allResults.add(new int[]{mi, bi});
                    }
                }
            }
        }
        int clearCount = allResults.size() - KEEP_RECENT_RESULTS;
        for (int i = 0; i < Math.max(0, clearCount); i++) {
            int[] loc = allResults.get(i);
            List<Map<String, Object>> content =
                    (List<Map<String, Object>>) anthropicMessages.get(loc[0]).get("content");
            content.get(loc[1]).put("content", "[Old result cleared]");
        }
    }

    private void microcompactOpenAI() {
        if (lastApiCallTime <= 0
                || (System.currentTimeMillis() / 1000.0 - lastApiCallTime) < MICROCOMPACT_IDLE_S) {
            return;
        }
        List<Integer> toolMsgs = new ArrayList<>();
        for (int i = 0; i < openaiMessages.size(); i++) {
            Map<String, Object> msg = openaiMessages.get(i);
            if ("tool".equals(msg.get("role")) && msg.get("content") instanceof String) {
                String c = (String) msg.get("content");
                if (!SNIP_PLACEHOLDER.equals(c) && !"[Old result cleared]".equals(c)) {
                    toolMsgs.add(i);
                }
            }
        }
        int clearCount = toolMsgs.size() - KEEP_RECENT_RESULTS;
        for (int i = 0; i < Math.max(0, clearCount); i++) {
            openaiMessages.get(toolMsgs.get(i)).put("content", "[Old result cleared]");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findToolUseById(String toolUseId) {
        for (Map<String, Object> msg : anthropicMessages) {
            if (!"assistant".equals(msg.get("role")) || !(msg.get("content") instanceof List)) continue;
            for (Object o : (List<?>) msg.get("content")) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> block = (Map<String, Object>) o;
                if ("tool_use".equals(block.get("type")) && toolUseId.equals(block.get("id"))) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("name", block.get("name"));
                    Object input = block.get("input");
                    info.put("input", input instanceof Map ? input : new HashMap<>());
                    return info;
                }
            }
        }
        return null;
    }

    // ─── 超大 tool result 落盘 ────────────────────────────────────

    private String persistLargeResult(String toolName, String result) {
        if (result == null) result = "";
        int threshold = 30 * 1024;
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= threshold) {
            return result;
        }
        try {
            Path d = Paths.get(System.getProperty("user.home"), ".mini-claude", "tool-results");
            Files.createDirectories(d);
            String filename = System.currentTimeMillis() + "-"
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                    + "-" + toolName + ".txt";
            Path filepath = d.resolve(filename);
            Files.write(filepath, bytes);
            String[] lines = result.split("\n", -1);
            StringBuilder preview = new StringBuilder();
            int limit = Math.min(200, lines.length);
            for (int i = 0; i < limit; i++) {
                if (i > 0) preview.append('\n');
                preview.append(lines[i]);
            }
            double sizeKb = bytes.length / 1024.0;
            return Tools.truncateResult(
                    "[Result too large (" + String.format("%.1f", sizeKb) + " KB, "
                            + lines.length + " lines). Full output saved to " + filepath
                            + ". You can use read_file to see the full result.]\n\n"
                            + "Preview (first 200 lines):\n" + preview);
        } catch (IOException e) {
            return Tools.truncateResult(result);
        }
    }

    // ─── 工具执行与权限 ───────────────────────────────────────────

    private String executeToolCall(String name, Map<String, Object> inp) {
        // Kimi builtin $web_search: echo arguments back — server runs the search.
        // See https://platform.kimi.com/docs/guide/use-web-search
        if (KIMI_BUILTIN_WEB_SEARCH.equals(name)) {
            return GSON.toJson(inp != null ? inp : Collections.emptyMap());
        }
        // Model often tool_searches for "web_search" because the prompt mentions it,
        // but on Moonshot that tool is replaced by builtin $web_search.
        if ("tool_search".equals(name) && isKimiBackend()) {
            String q = inp != null && inp.get("query") != null
                    ? String.valueOf(inp.get("query")).toLowerCase(Locale.ROOT) : "";
            if (q.contains("web_search") || q.contains("$web_search")
                    || (q.contains("web") && q.contains("search"))) {
                return "On Moonshot/Kimi, use the builtin tool `$web_search` directly — "
                        + "it is already in your tools list. There is no deferred `web_search` tool. "
                        + "Do not call tool_search for internet search; call `$web_search` now.";
            }
        }
        if ("enter_plan_mode".equals(name) || "exit_plan_mode".equals(name)) {
            return executePlanModeTool(name);
        }
        if ("agent".equals(name)) {
            return executeAgentTool(inp);
        }
        if ("skill".equals(name)) {
            return executeSkillTool(inp);
        }
        if ("schedule_wakeup".equals(name)) {
            if (!scheduleWakeupEnabled) {
                return "schedule_wakeup is only available during /loop dynamic mode.";
            }
            return executeScheduleWakeup(inp);
        }
        if (mcpManager.isMcpTool(name)) {
            try {
                return mcpManager.callTool(name, inp);
            } catch (Exception e) {
                return "MCP tool error: " + e.getMessage();
            }
        }
        return Tools.executeTool(name, inp, readFileState);
    }

    @SuppressWarnings("unchecked")
    private String executeSkillTool(Map<String, Object> inp) {
        String skillName = inp.get("skill_name") != null ? String.valueOf(inp.get("skill_name")) : "";
        String args = inp.get("args") != null ? String.valueOf(inp.get("args")) : "";
        Map<String, Object> result = Skills.executeSkill(skillName, args);
        if (result == null) {
            return "Unknown skill: " + skillName;
        }
        if ("fork".equals(result.get("context"))) {
            List<Map<String, Object>> subTools;
            Object allowed = result.get("allowed_tools");
            if (allowed instanceof List && !((List<?>) allowed).isEmpty()) {
                Set<String> names = new HashSet<>();
                for (Object o : (List<?>) allowed) {
                    names.add(String.valueOf(o));
                }
                subTools = new ArrayList<>();
                for (Map<String, Object> t : tools) {
                    String n = String.valueOf(t.get("name"));
                    if (names.contains(n) && !"schedule_wakeup".equals(n)) {
                        subTools.add(t);
                    }
                }
            } else {
                subTools = new ArrayList<>();
                for (Map<String, Object> t : tools) {
                    String n = String.valueOf(t.get("name"));
                    if (!"agent".equals(n) && !"schedule_wakeup".equals(n)) {
                        subTools.add(t);
                    }
                }
            }
            Ui.printSubAgentStart("skill-fork", skillName);
            Agent sub = Agent.builder()
                    .model(model)
                    .apiBase(useOpenAI ? apiBase : null)
                    .anthropicBaseUrl(useOpenAI ? null : anthropicBaseUrl)
                    .apiKey(apiKey)
                    .customSystemPrompt(String.valueOf(result.get("prompt")))
                    .customTools(subTools)
                    .isSubAgent(true)
                    .permissionMode(childPermissionMode())
                    .build();
            try {
                Map<String, Object> subResult = sub.runOnce(
                        args != null && !args.isEmpty() ? args : "Execute this skill task.");
                Map<String, Object> tokens = (Map<String, Object>) subResult.get("tokens");
                totalInputTokens += ((Number) tokens.get("input")).intValue();
                totalOutputTokens += ((Number) tokens.get("output")).intValue();
                Ui.printSubAgentEnd("skill-fork", skillName);
                Object text = subResult.get("text");
                return text != null && !String.valueOf(text).isEmpty()
                        ? String.valueOf(text) : "(Skill produced no output)";
            } catch (Exception e) {
                Ui.printSubAgentEnd("skill-fork", skillName);
                return "Skill fork error: " + e.getMessage();
            } finally {
                sub.close();
            }
        }
        return "[Skill \"" + skillName + "\" activated]\n\n" + result.get("prompt");
    }

    private String generatePlanFilePath() {
        try {
            Path d = Paths.get(System.getProperty("user.home"), ".claude", "plans");
            Files.createDirectories(d);
            return d.resolve("plan-" + sessionId + ".md").toString();
        } catch (IOException e) {
            return Paths.get(System.getProperty("user.home"), ".claude", "plans",
                    "plan-" + sessionId + ".md").toString();
        }
    }

    private String buildPlanModePrompt() {
        return "\n\n# Plan Mode Active\n\n"
                + "Plan mode is active. You MUST NOT make any edits (except the plan file below), run non-readonly tools, or make any changes to the system.\n\n"
                + "## Plan File: " + planFilePath + "\n"
                + "Write your plan incrementally to this file using write_file or edit_file. This is the ONLY file you are allowed to edit.\n\n"
                + "## Workflow\n"
                + "1. **Explore**: Read code to understand the task. Use read_file, list_files, grep_search.\n"
                + "2. **Design**: Design your implementation approach. Use the agent tool with type=\"plan\" if the task is complex.\n"
                + "3. **Write Plan**: Write a structured plan to the plan file including:\n"
                + "   - **Context**: Why this change is needed\n"
                + "   - **Steps**: Implementation steps with critical file paths\n"
                + "   - **Verification**: How to test the changes\n"
                + "4. **Exit**: Call exit_plan_mode when your plan is ready for user review.\n\n"
                + "IMPORTANT: When your plan is complete, you MUST call exit_plan_mode. Do NOT ask the user to approve — exit_plan_mode handles that.";
    }

    private String executePlanModeTool(String name) {
        if ("enter_plan_mode".equals(name)) {
            if ("plan".equals(permissionMode)) {
                return "Already in plan mode.";
            }
            prePlanMode = permissionMode;
            permissionMode = "plan";
            planFilePath = generatePlanFilePath();
            systemPrompt = baseSystemPrompt + buildPlanModePrompt();
            if (useOpenAI && !openaiMessages.isEmpty()) {
                openaiMessages.get(0).put("content", systemPrompt);
            }
            Ui.printInfo("Entered plan mode (read-only). Plan file: " + planFilePath);
            return "Entered plan mode. You are now in read-only mode.\n\nYour plan file: "
                    + planFilePath
                    + "\nWrite your plan to this file. This is the only file you can edit.\n\n"
                    + "When your plan is complete, call exit_plan_mode.";
        }
        if ("exit_plan_mode".equals(name)) {
            if (!"plan".equals(permissionMode)) {
                return "Not in plan mode.";
            }
            String planContent = "(No plan file found)";
            if (planFilePath != null) {
                Path p = Paths.get(planFilePath);
                if (Files.exists(p)) {
                    try {
                        planContent = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                    } catch (IOException ignored) {
                    }
                }
            }
            if (planApprovalFn != null) {
                Map<String, String> result = planApprovalFn.approve(planContent);
                String choice = result != null ? result.getOrDefault("choice", "manual-execute")
                        : "manual-execute";
                if ("keep-planning".equals(choice)) {
                    String feedback = result != null && result.get("feedback") != null
                            ? result.get("feedback") : "Please revise the plan.";
                    return "User rejected the plan and wants to keep planning.\n\n"
                            + "User feedback: " + feedback + "\n\n"
                            + "Please revise your plan based on this feedback. When done, call exit_plan_mode again.";
                }
                String targetMode;
                if ("clear-and-execute".equals(choice) || "execute".equals(choice)) {
                    targetMode = "acceptEdits";
                } else {
                    targetMode = prePlanMode != null ? prePlanMode : "default";
                }
                permissionMode = targetMode;
                prePlanMode = null;
                String savedPlanPath = planFilePath;
                planFilePath = null;
                systemPrompt = baseSystemPrompt;
                if (useOpenAI && !openaiMessages.isEmpty()) {
                    openaiMessages.get(0).put("content", systemPrompt);
                }
                if ("clear-and-execute".equals(choice)) {
                    clearHistoryKeepSystem();
                    contextCleared = true;
                    Ui.printInfo("Plan approved. Context cleared, executing in " + targetMode + " mode.");
                    return "User approved the plan. Context was cleared. Permission mode: "
                            + targetMode + "\n\nPlan file: " + savedPlanPath
                            + "\n\n## Approved Plan:\n" + planContent
                            + "\n\nProceed with implementation.";
                }
                Ui.printInfo("Plan approved. Executing in " + targetMode + " mode.");
                return "User approved the plan. Permission mode: " + targetMode
                        + "\n\n## Approved Plan:\n" + planContent
                        + "\n\nProceed with implementation.";
            }
            permissionMode = prePlanMode != null ? prePlanMode : "default";
            prePlanMode = null;
            planFilePath = null;
            systemPrompt = baseSystemPrompt;
            if (useOpenAI && !openaiMessages.isEmpty()) {
                openaiMessages.get(0).put("content", systemPrompt);
            }
            Ui.printInfo("Exited plan mode. Restored to " + permissionMode + " mode.");
            return "Exited plan mode. Permission mode restored to: " + permissionMode
                    + "\n\n## Your Plan:\n" + planContent;
        }
        return "Unknown plan mode tool: " + name;
    }

    private void clearHistoryKeepSystem() {
        anthropicMessages.clear();
        openaiMessages.clear();
        if (useOpenAI) {
            Map<String, Object> sys = new LinkedHashMap<>();
            sys.put("role", "system");
            sys.put("content", buildOpenAISystemContent());
            openaiMessages.add(sys);
        }
        lastInputTokenCount = 0;
    }

    @SuppressWarnings("unchecked")
    private String executeAgentTool(Map<String, Object> inp) {
        String agentType = inp.get("type") != null ? String.valueOf(inp.get("type")) : "general";
        String description = inp.get("description") != null
                ? String.valueOf(inp.get("description")) : "sub-agent task";
        String prompt = inp.get("prompt") != null ? String.valueOf(inp.get("prompt")) : "";

        Ui.printSubAgentStart(agentType, description);
        Subagent.SubAgentConfig config = Subagent.getSubAgentConfig(agentType);
        List<Map<String, Object>> subTools = resolveSubAgentTools(config);

        Agent sub = Agent.builder()
                .model(model)
                .apiBase(useOpenAI ? apiBase : null)
                .anthropicBaseUrl(useOpenAI ? null : anthropicBaseUrl)
                .apiKey(apiKey)
                .customSystemPrompt(config.systemPrompt)
                .customTools(subTools)
                .isSubAgent(true)
                .permissionMode(childPermissionMode())
                .build();
        try {
            Map<String, Object> result = sub.runOnce(prompt);
            Map<String, Object> tokens = (Map<String, Object>) result.get("tokens");
            totalInputTokens += ((Number) tokens.get("input")).intValue();
            totalOutputTokens += ((Number) tokens.get("output")).intValue();
            Ui.printSubAgentEnd(agentType, description);
            Object text = result.get("text");
            return text != null && !String.valueOf(text).isEmpty()
                    ? String.valueOf(text) : "(Sub-agent produced no output)";
        } catch (Exception e) {
            Ui.printSubAgentEnd(agentType, description);
            return "Sub-agent error: " + e.getMessage();
        } finally {
            sub.close();
        }
    }

    private List<Map<String, Object>> resolveSubAgentTools(Subagent.SubAgentConfig config) {
        List<Map<String, Object>> source = tools != null ? tools : Tools.TOOL_DEFINITIONS;
        List<Map<String, Object>> out = new ArrayList<>();
        if (config.allowedToolNames == null) {
            for (Map<String, Object> t : source) {
                String n = String.valueOf(t.get("name"));
                if (!"agent".equals(n) && !"schedule_wakeup".equals(n)) {
                    out.add(t);
                }
            }
        } else {
            Set<String> allowed = new HashSet<>(config.allowedToolNames);
            for (Map<String, Object> t : source) {
                if (allowed.contains(String.valueOf(t.get("name")))) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    // ─── Memory prefetch ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void consumeMemoryPrefetchIfReady(List<Map<String, Object>> messages) {
        Memory.MemoryPrefetch pf = memoryPrefetch;
        if (pf == null || !pf.isSettled() || pf.consumed) {
            return;
        }
        pf.consumed = true;
        try {
            List<Memory.RelevantMemory> memories = pf.future.getNow(null);
            if (memories == null || memories.isEmpty()) {
                return;
            }
            String injectionText = Memory.formatMemoriesForInjection(memories);
            Map<String, Object> last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && "user".equals(last.get("role"))) {
                Object content = last.get("content");
                if (content instanceof String || content == null) {
                    last.put("content", (content != null ? content : "") + "\n\n" + injectionText);
                } else if (content instanceof List) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "text");
                    block.put("text", injectionText);
                    ((List<Map<String, Object>>) content).add(block);
                }
            } else {
                messages.add(msg("user", injectionText));
            }
            for (Memory.RelevantMemory m : memories) {
                alreadySurfacedMemories.add(m.path);
                sessionMemoryBytes += m.content.getBytes(StandardCharsets.UTF_8).length;
            }
        } catch (Exception ignored) {
        }
    }

    private void startMemoryPrefetchForTurn(String userMessage, List<Map<String, Object>> messages) {
        consumeMemoryPrefetchIfReady(messages);
        if (isSubAgent) {
            return;
        }
        if (memoryPrefetch != null && !memoryPrefetch.isSettled()) {
            memoryPrefetch.future.cancel(true);
        }
        Memory.SideQueryFn sq = buildSideQuery();
        if (sq != null) {
            memoryPrefetch = Memory.startMemoryPrefetch(
                    userMessage, sq, alreadySurfacedMemories, sessionMemoryBytes);
        }
    }

    @SuppressWarnings("unchecked")
    private void pushAnthropicUserMessage(String content) {
        if (anthropicMessages.isEmpty() && userContextReminder != null && !userContextReminder.isEmpty()) {
            List<Map<String, Object>> blocks = new ArrayList<>();
            Map<String, Object> rem = new LinkedHashMap<>();
            rem.put("type", "text");
            rem.put("text", userContextReminder);
            blocks.add(rem);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "text");
            body.put("text", content);
            blocks.add(body);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", "user");
            m.put("content", blocks);
            anthropicMessages.add(m);
        } else {
            anthropicMessages.add(msg("user", content));
        }
    }

    private void pushOpenAIUserMessage(String content) {
        boolean isFirstUser = true;
        for (Map<String, Object> m : openaiMessages) {
            if ("user".equals(m.get("role"))) {
                isFirstUser = false;
                break;
            }
        }
        // Kimi: project context already in system (see buildOpenAISystemContent).
        // A large <system-reminder> on the first user message breaks $web_search tool calls.
        if (isFirstUser && !isKimiBackend()
                && userContextReminder != null && !userContextReminder.isEmpty()) {
            openaiMessages.add(msg("user", userContextReminder + "\n\n" + content));
        } else {
            openaiMessages.add(msg("user", content));
        }
    }

    // ─── Anthropic 对话循环 ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void chatAnthropic(String userMessage) {
        pushAnthropicUserMessage(userMessage);
        checkAndCompact();
        startMemoryPrefetchForTurn(userMessage, anthropicMessages);

        while (true) {
            if (aborted.get()) break;
            runCompressionPipeline();
            consumeMemoryPrefetchIfReady(anthropicMessages);

            if (!isSubAgent) {
                Ui.startSpinner();
            }

            Map<String, Future<String>> earlyExecutions = new ConcurrentHashMap<>();
            Consumer<Map<String, Object>> onToolBlock = block -> {
                String name = String.valueOf(block.get("name"));
                if ("auto".equals(permissionMode)
                        && !Autonomy.AUTO_MODE_FAST_PATH_TOOLS.contains(name)) {
                    return;
                }
                if (Tools.CONCURRENCY_SAFE_TOOLS.contains(name)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = block.get("input") instanceof Map
                            ? (Map<String, Object>) block.get("input") : new HashMap<>();
                    Map<String, String> perm = Tools.checkPermission(
                            name, input, permissionMode, planFilePath);
                    if ("allow".equals(perm.get("action"))) {
                        String id = String.valueOf(block.get("id"));
                        earlyExecutions.put(id, toolExecutor.submit(
                                () -> executeToolCall(name, input)));
                    }
                }
            };

            StreamResult response = callAnthropicStream(onToolBlock);

            if (!isSubAgent) {
                Ui.stopSpinner();
            }

            lastApiCallTime = System.currentTimeMillis() / 1000.0;
            totalInputTokens += response.inputTokens;
            totalCacheReadTokens += response.cacheReadTokens;
            totalCacheCreationTokens += response.cacheCreationTokens;
            totalOutputTokens += response.outputTokens;
            lastInputTokenCount = response.inputTokens + response.cacheReadTokens
                    + response.cacheCreationTokens + response.outputTokens;

            List<Map<String, Object>> toolUses = new ArrayList<>();
            for (Map<String, Object> b : response.content) {
                if ("tool_use".equals(b.get("type"))) {
                    toolUses.add(b);
                }
            }

            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", response.content);
            anthropicMessages.add(assistantMsg);

            if (toolUses.isEmpty()) {
                if (!isSubAgent) {
                    Ui.printCost(totalInputTokens, totalOutputTokens,
                            totalCacheReadTokens, totalCacheCreationTokens);
                }
                break;
            }

            currentTurns++;
            Map<String, Object> budget = checkBudget();
            if (Boolean.TRUE.equals(budget.get("exceeded"))) {
                Ui.printInfo("Budget exceeded: " + budget.get("reason"));
                for (Future<?> t : earlyExecutions.values()) {
                    t.cancel(true);
                }
                List<Map<String, Object>> refusals = new ArrayList<>();
                for (Map<String, Object> tu : toolUses) {
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", tu.get("id"));
                    tr.put("content", "Tool call not executed: " + budget.get("reason"));
                    refusals.add(tr);
                }
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("role", "user");
                um.put("content", refusals);
                anthropicMessages.add(um);
                break;
            }

            List<Map<String, Object>> toolResults = new ArrayList<>();
            boolean contextBreak = false;
            for (Map<String, Object> tu : toolUses) {
                if (contextBreak || aborted.get()) break;
                Map<String, Object> inp = tu.get("input") instanceof Map
                        ? new LinkedHashMap<>((Map<String, Object>) tu.get("input"))
                        : new HashMap<>();
                String tuName = String.valueOf(tu.get("name"));
                String tuId = String.valueOf(tu.get("id"));
                Ui.printToolCall(tuName, inp);

                Future<String> early = earlyExecutions.get(tuId);
                if (early != null) {
                    String raw;
                    try {
                        raw = early.get();
                    } catch (Exception e) {
                        raw = "Tool error: " + e.getMessage();
                    }
                    String res = persistLargeResult(tuName, raw);
                    Ui.printToolResult(tuName, res);
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", tuId);
                    tr.put("content", res);
                    toolResults.add(tr);
                    continue;
                }

                Map<String, String> perm;
                if ("auto".equals(permissionMode)) {
                    perm = classifyToolCall(tuName, inp);
                } else {
                    perm = Tools.checkPermission(tuName, inp, permissionMode, planFilePath);
                }
                if ("deny".equals(perm.get("action"))) {
                    Ui.printInfo("Denied: " + perm.getOrDefault("message", ""));
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("type", "tool_result");
                    tr.put("tool_use_id", tuId);
                    tr.put("content", "Action denied: " + perm.getOrDefault("message", ""));
                    toolResults.add(tr);
                    continue;
                }
                if ("confirm".equals(perm.get("action")) && perm.get("message") != null) {
                    boolean cacheable = !"auto".equals(permissionMode);
                    if (!cacheable || !confirmedPaths.contains(perm.get("message"))) {
                        if (!confirmDangerous(perm.get("message"))) {
                            Map<String, Object> tr = new LinkedHashMap<>();
                            tr.put("type", "tool_result");
                            tr.put("tool_use_id", tuId);
                            tr.put("content", "User denied this action.");
                            toolResults.add(tr);
                            continue;
                        }
                        if (cacheable) {
                            confirmedPaths.add(perm.get("message"));
                        }
                    }
                }

                String raw = executeToolCall(tuName, inp);
                String res = persistLargeResult(tuName, raw);
                Ui.printToolResult(tuName, res);

                if (contextCleared) {
                    contextCleared = false;
                    pushAnthropicUserMessage(res);
                    contextBreak = true;
                    break;
                }
                Map<String, Object> tr = new LinkedHashMap<>();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", tuId);
                tr.put("content", res);
                toolResults.add(tr);
            }

            if (!contextBreak && !toolResults.isEmpty()) {
                Map<String, Object> um = new LinkedHashMap<>();
                um.put("role", "user");
                um.put("content", toolResults);
                anthropicMessages.add(um);
            }
            contextCleared = false;
        }
    }

    // ─── Anthropic 流式 API ───────────────────────────────────────

    private static final class StreamResult {
        List<Map<String, Object>> content = new ArrayList<>();
        int inputTokens;
        int outputTokens;
        int cacheReadTokens;
        int cacheCreationTokens;
    }

    private StreamResult callAnthropicStream(Consumer<Map<String, Object>> onToolBlockComplete) {
        return ApiHttp.withRetry(() -> doAnthropicStream(onToolBlockComplete), 3);
    }

    @SuppressWarnings("unchecked")
    private StreamResult doAnthropicStream(Consumer<Map<String, Object>> onToolBlockComplete) {
        int maxOutput = getMaxOutputTokens(model);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", !"disabled".equals(thinkingMode) ? maxOutput : 16384);
        body.put("system", buildAnthropicSystem());
        body.put("tools", Tools.getActiveToolDefinitions(tools));
        body.put("messages", withCacheBreakpoints(anthropicMessages));
        body.put("stream", true);
        if ("adaptive".equals(thinkingMode) || "enabled".equals(thinkingMode)) {
            Map<String, Object> thinkingCfg = new LinkedHashMap<>();
            thinkingCfg.put("type", "enabled");
            thinkingCfg.put("budget_tokens", maxOutput - 1);
            body.put("thinking", thinkingCfg);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");
        headers.put("Accept", "text/event-stream");

        String url = anthropicBaseUrl + "/v1/messages";
        String json = GSON.toJson(body);

        try {
            java.net.http.HttpResponse<InputStream> resp =
                    ApiHttp.postJsonStream(url, json, headers);
            try (InputStream in = resp.body();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {

                StreamResult result = new StreamResult();
                Map<Integer, Map<String, Object>> blocksByIndex = new HashMap<>();
                Map<Integer, StringBuilder> toolJsonByIndex = new HashMap<>();
                boolean firstText = true;

                String line;
                while ((line = reader.readLine()) != null) {
                    if (aborted.get()) break;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    JsonObject event;
                    try {
                        event = JsonParser.parseString(data).getAsJsonObject();
                    } catch (Exception e) {
                        continue;
                    }
                    String type = event.has("type") ? event.get("type").getAsString() : "";

                    if ("message_start".equals(type) && event.has("message")) {
                        JsonObject message = event.getAsJsonObject("message");
                        if (message.has("usage")) {
                            JsonObject usage = message.getAsJsonObject("usage");
                            result.inputTokens = optInt(usage, "input_tokens");
                            result.cacheReadTokens = optInt(usage, "cache_read_input_tokens");
                            result.cacheCreationTokens = optInt(usage, "cache_creation_input_tokens");
                        }
                    } else if ("content_block_start".equals(type)) {
                        int index = event.has("index") ? event.get("index").getAsInt() : 0;
                        JsonObject cb = event.has("content_block")
                                ? event.getAsJsonObject("content_block") : null;
                        if (cb == null) continue;
                        String cbType = cb.has("type") ? cb.get("type").getAsString() : "";
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", cbType);
                        if ("text".equals(cbType)) {
                            block.put("text", cb.has("text") ? cb.get("text").getAsString() : "");
                        } else if ("thinking".equals(cbType)) {
                            block.put("thinking", cb.has("thinking")
                                    ? cb.get("thinking").getAsString() : "");
                        } else if ("tool_use".equals(cbType)) {
                            block.put("id", cb.has("id") ? cb.get("id").getAsString() : "");
                            block.put("name", cb.has("name") ? cb.get("name").getAsString() : "");
                            block.put("input", new LinkedHashMap<String, Object>());
                            toolJsonByIndex.put(index, new StringBuilder());
                        }
                        blocksByIndex.put(index, block);
                    } else if ("content_block_delta".equals(type)) {
                        int index = event.has("index") ? event.get("index").getAsInt() : 0;
                        JsonObject delta = event.has("delta")
                                ? event.getAsJsonObject("delta") : null;
                        if (delta == null) continue;
                        String deltaType = delta.has("type") ? delta.get("type").getAsString() : "";
                        Map<String, Object> block = blocksByIndex.get(index);
                        if ("text_delta".equals(deltaType) && delta.has("text")) {
                            String text = delta.get("text").getAsString();
                            if (firstText) {
                                Ui.stopSpinner();
                                emitText("\n");
                                firstText = false;
                            }
                            emitText(text);
                            if (block != null) {
                                block.put("text", String.valueOf(block.getOrDefault("text", "")) + text);
                            }
                        } else if ("thinking_delta".equals(deltaType) && delta.has("thinking")) {
                            String th = delta.get("thinking").getAsString();
                            if (firstText) {
                                Ui.stopSpinner();
                                emitText("\n  [thinking] ");
                                firstText = false;
                            }
                            emitText(th);
                            if (block != null) {
                                block.put("thinking",
                                        String.valueOf(block.getOrDefault("thinking", "")) + th);
                            }
                        } else if ("input_json_delta".equals(deltaType) && delta.has("partial_json")) {
                            StringBuilder sb = toolJsonByIndex.get(index);
                            if (sb != null) {
                                sb.append(delta.get("partial_json").getAsString());
                            }
                        }
                    } else if ("content_block_stop".equals(type)) {
                        int index = event.has("index") ? event.get("index").getAsInt() : 0;
                        Map<String, Object> block = blocksByIndex.get(index);
                        if (block != null && "tool_use".equals(block.get("type"))) {
                            StringBuilder sb = toolJsonByIndex.get(index);
                            Map<String, Object> parsed = new LinkedHashMap<>();
                            if (sb != null && sb.length() > 0) {
                                try {
                                    parsed = GSON.fromJson(sb.toString(), MAP_TYPE);
                                    if (parsed == null) parsed = new LinkedHashMap<>();
                                } catch (Exception e) {
                                    parsed = new LinkedHashMap<>();
                                }
                            }
                            block.put("input", parsed);
                            if (onToolBlockComplete != null) {
                                Map<String, Object> notify = new LinkedHashMap<>();
                                notify.put("type", "tool_use");
                                notify.put("id", block.get("id"));
                                notify.put("name", block.get("name"));
                                notify.put("input", parsed);
                                onToolBlockComplete.accept(notify);
                            }
                        }
                    } else if ("message_delta".equals(type)) {
                        if (event.has("usage")) {
                            JsonObject usage = event.getAsJsonObject("usage");
                            result.outputTokens = optInt(usage, "output_tokens");
                            if (usage.has("input_tokens")) {
                                result.inputTokens = optInt(usage, "input_tokens");
                            }
                            if (usage.has("cache_read_input_tokens")) {
                                result.cacheReadTokens = optInt(usage, "cache_read_input_tokens");
                            }
                            if (usage.has("cache_creation_input_tokens")) {
                                result.cacheCreationTokens =
                                        optInt(usage, "cache_creation_input_tokens");
                            }
                        }
                    } else if ("message_stop".equals(type)) {
                        // done
                    }
                }

                // Assemble content in index order, filter thinking
                List<Integer> indices = new ArrayList<>(blocksByIndex.keySet());
                Collections.sort(indices);
                for (int idx : indices) {
                    Map<String, Object> b = blocksByIndex.get(idx);
                    if (b != null && !"thinking".equals(b.get("type"))) {
                        result.content.add(b);
                    }
                }
                return result;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    // ─── OpenAI 兼容对话循环 ──────────────────────────────────────

    /**
     * OpenAI 兼容后端的主对话循环（Kimi / Moonshot / 任意 OpenAI-compatible API）。
     *
     * <h2>整体在干什么</h2>
     * 这是「用户发一句话 → Agent 可能连着调很多次工具 → 最终给出文本回复」的核心状态机。
     * 与 Anthropic 路径 {@code chatAnthropic} 对称，区别在于消息格式是 Chat Completions：
     * <ul>
     *   <li>{@code openaiMessages}：完整对话历史（system / user / assistant / tool）</li>
     *   <li>模型若返回 {@code tool_calls}，就本地执行工具，把 {@code role=tool} 结果塞回历史，再请求下一轮</li>
     *   <li>模型若不返回 tool_calls（只回文本），本轮结束</li>
     * </ul>
     *
     * <h2>一轮 while 循环 ≈ 一次「模型思考 +（可选）工具执行」</h2>
     * <pre>
     *   用户消息
     *      │
     *      ▼
     * ┌─ while(true) ─────────────────────────────────────────┐
     * │  1. 压缩上下文 / 吞并记忆预取                           │
     * │  2. callOpenAIStream()  —— 流式调 LLM                 │
     * │  3. 累计 token、把 assistant 消息写入历史               │
     * │  4. 若无 tool_calls → break（本轮对话结束）             │
     * │  5. 检查预算（maxTurns / maxCost）                      │
     * │  6. 逐个 tool_call：鉴权 → 入队                        │
     * │  7. 按「可并发 / 必须串行」分批执行工具                   │
     * │  8. 把 tool 结果写回 openaiMessages，继续 while        │
     * └───────────────────────────────────────────────────────┘
     * </pre>
     *
     * <h2>Kimi {@code $web_search} 特殊约定</h2>
     * Moonshot 内置联网搜索不是本地执行，而是：模型给出 tool_call → 我们把 arguments
     * <b>原样</b>当 tool 结果回传 → 服务端在下一轮请求里完成搜索。因此这里必须：
     * 保留原始 JSON 字符串（{@code rawArgs}），禁止 Gson 反序列化再序列化（会把
     * {@code 8084} 变成 {@code 8084.0}），且 assistant 消息里 tool_call 的
     * {@code type} 必须是 {@code builtin_function}（在流式组装处保证）。
     *
     * @param userMessage 本轮用户输入的纯文本（首轮还可能在 push 时附带上下文，见
     *                    {@link #pushOpenAIUserMessage}；Kimi 上项目上下文已放在 system）
     */
    @SuppressWarnings("unchecked")
    private void chatOpenAI(String userMessage) {
        // ─── 阶段 0：本轮入口准备 ─────────────────────────────────
        // 把用户话写入 openaiMessages。首条用户消息在非 Kimi 路径可能拼接
        // <system-reminder>（CLAUDE.md、日期等）；Kimi 上提醒已并入 system，这里只推纯文本。
        pushOpenAIUserMessage(userMessage);
        // 若上下文已接近窗口上限，做一次粗粒度 compact（摘要折叠旧消息），避免下一请求爆窗。
        checkAndCompact();
        // 异步预取与本轮问题相关的 MEMORY.md 片段；真正注入发生在循环内 consumeMemoryPrefetchIfReady。
        startMemoryPrefetchForTurn(userMessage, openaiMessages);

        // ─── 主循环：模型 ↔ 工具，直到模型不再要工具或被中止/超预算 ───
        while (true) {
            // 用户 Ctrl+C / abort() 置位后立刻退出，不再发下一轮 API。
            if (aborted.get()) break;

            // 多层压缩管道：微压缩、摘要、清上下文等；可能改写 openaiMessages 内容。
            runCompressionPipeline();
            // 若记忆预取已完成，把结果作为额外 user/system 旁注合入消息列表（只消费一次）。
            consumeMemoryPrefetchIfReady(openaiMessages);

            // 主 Agent 显示转圈；子 Agent 静默，避免嵌套 UI 干扰。
            if (!isSubAgent) {
                Ui.startSpinner();
            }

            // 真正打 Chat Completions（stream=true），内部 doOpenAIStream 拼 tools/messages、
            // 禁用 Kimi thinking、解析 SSE，最终折成「非流式形态」的 response Map：
            // { choices:[{ message:{role,content,tool_calls}, finish_reason }], usage:{} }
            Map<String, Object> response = callOpenAIStream();

            if (!isSubAgent) {
                Ui.stopSpinner();
            }

            // 记录上次 API 时间戳（秒），可供调度 /loop 等逻辑使用。
            lastApiCallTime = System.currentTimeMillis() / 1000.0;

            // ─── 阶段 1：记账 —— token / cache ────────────────────────────────
            // OpenAI usage：prompt_tokens / completion_tokens；若有缓存命中则记到 cacheRead。
            // 计费口径：可缓存的输入不重复计入 totalInputTokens（与 Anthropic 侧对称）。
            Map<String, Object> usage = response.get("usage") instanceof Map
                    ? (Map<String, Object>) response.get("usage") : null;
            if (usage != null) {
                int prompt = asInt(usage.get("prompt_tokens"));
                int cachedOa = Math.min(Math.max(asInt(usage.get("cached_tokens")), 0), prompt);
                int completion = asInt(usage.get("completion_tokens"));
                totalInputTokens += prompt - cachedOa;
                totalCacheReadTokens += cachedOa;
                totalOutputTokens += completion;
                // 用「本轮总 token」近似驱动后续是否需要压缩。
                lastInputTokenCount = prompt + completion;
            }

            // ─── 阶段 2：取出 assistant 消息并入历史 ─────────────────────────
            // 兼容空 choices / 异常结构，保证后续不会 NPE。
            List<Map<String, Object>> choices = response.get("choices") instanceof List
                    ? (List<Map<String, Object>>) response.get("choices") : Collections.emptyList();
            Map<String, Object> choice = choices.isEmpty() ? new HashMap<>() : choices.get(0);
            Map<String, Object> message = choice.get("message") instanceof Map
                    ? (Map<String, Object>) choice.get("message") : new LinkedHashMap<>();

            // 必须先把本轮 assistant（含 tool_calls）追加进历史，再执行工具；
            // 否则下一轮请求会缺少「模型当时发出的 tool_call_id」，服务端无法配对。
            openaiMessages.add(message);

            // ─── 阶段 3：无工具调用 → 纯文本收尾，跳出循环 ───────────────────
            List<Map<String, Object>> toolCalls = message.get("tool_calls") instanceof List
                    ? (List<Map<String, Object>>) message.get("tool_calls") : null;
            if (toolCalls == null || toolCalls.isEmpty()) {
                // 主 Agent 打印累计费用；子 Agent 的费用由父级汇总。
                if (!isSubAgent) {
                    Ui.printCost(totalInputTokens, totalOutputTokens,
                            totalCacheReadTokens, totalCacheCreationTokens);
                }
                break;
            }

            // ─── 阶段 4：预算闸门（轮次 / 美元上限）─────────────────────────
            // 每发生一批 tool_calls 计为 1 个 turn（不是每个工具各算一轮）。
            currentTurns++;
            Map<String, Object> budget = checkBudget();
            if (Boolean.TRUE.equals(budget.get("exceeded"))) {
                Ui.printInfo("Budget exceeded: " + budget.get("reason"));
                // 协议要求：每个 tool_call_id 都必须有一条 role=tool 的回复，
                // 否则下次请求会被视为非法对话状态。这里用「未执行」占位内容收尾。
                for (Map<String, Object> tc : toolCalls) {
                    if (tc.get("id") != null) {
                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("role", "tool");
                        tr.put("tool_call_id", tc.get("id"));
                        Map<String, Object> fn = tc.get("function") instanceof Map
                                ? (Map<String, Object>) tc.get("function") : null;
                        if (fn != null && fn.get("name") != null) {
                            // Kimi 等要求 tool 消息带 name，与 tool_call 对齐。
                            tr.put("name", fn.get("name"));
                        }
                        tr.put("content", "Tool call not executed: " + budget.get("reason"));
                        openaiMessages.add(tr);
                    }
                }
                break;
            }

            // ─── 阶段 5：鉴权与预处理 —— 生成「待执行清单」oaiChecked ─────────
            // 每项 ct 结构大致为：
            //   tc      = 原始 tool_call Map（含 id / type / function）
            //   fn      = 工具名字符串
            //   inp     = 解析后的参数 Map（给本地 Tools.execute 用）
            //   rawArgs = 参数原始 JSON 字符串（给 Kimi $web_search 原样回传）
            //   allowed = 是否允许执行
            //   result  = 若拒绝，提前写好的拒绝文案（稍后作为 tool content）
            List<Map<String, Object>> oaiChecked = new ArrayList<>();
            for (Map<String, Object> tc : toolCalls) {
                if (aborted.get()) break;

                // 只处理标准 function / Kimi builtin_function；其它 type 直接跳过。
                // （流式组装时 $web_search 会强制标成 builtin_function。）
                Object tcType = tc.get("type");
                if (tcType != null && !"function".equals(tcType) && !"builtin_function".equals(tcType)) {
                    continue;
                }

                Map<String, Object> fn = tc.get("function") instanceof Map
                        ? (Map<String, Object>) tc.get("function") : new HashMap<>();
                String fnName = String.valueOf(fn.get("name"));

                // 保留 arguments 原文：后续 $web_search 必须 echo 这份字符串，不能 round-trip。
                String rawArgs = fn.get("arguments") != null
                        ? String.valueOf(fn.get("arguments")) : "{}";
                Map<String, Object> inp;
                try {
                    // 本地工具执行需要 Map；解析失败则空 Map，避免整轮崩溃。
                    inp = GSON.fromJson(rawArgs, MAP_TYPE);
                    if (inp == null) inp = new HashMap<>();
                } catch (Exception e) {
                    inp = new HashMap<>();
                }
                Ui.printToolCall(fnName, inp);

                // 权限决策：
                //   - $web_search：服务端副作用，本地无写盘/无 shell → 一律 allow
                //   - permissionMode=auto：走 Autonomy 分类器
                //   - 其它：Tools.checkPermission（plan / yolo / acceptEdits / 危险命令确认…）
                Map<String, String> perm;
                if (KIMI_BUILTIN_WEB_SEARCH.equals(fnName)) {
                    perm = new HashMap<>();
                    perm.put("action", "allow");
                } else if ("auto".equals(permissionMode)) {
                    perm = classifyToolCall(fnName, inp);
                } else {
                    perm = Tools.checkPermission(fnName, inp, permissionMode, planFilePath);
                }

                Map<String, Object> ct = new LinkedHashMap<>();
                ct.put("tc", tc);
                ct.put("fn", fnName);
                ct.put("inp", inp);
                ct.put("rawArgs", rawArgs);

                if ("deny".equals(perm.get("action"))) {
                    // 拒绝也要进清单，稍后仍写入 role=tool，让模型知道被挡了什么。
                    Ui.printInfo("Denied: " + perm.getOrDefault("message", ""));
                    ct.put("allowed", false);
                    ct.put("result", "Action denied: " + perm.getOrDefault("message", ""));
                    oaiChecked.add(ct);
                    continue;
                }
                if ("confirm".equals(perm.get("action")) && perm.get("message") != null) {
                    // 危险操作需用户确认；同一条 confirm message 可缓存（非 auto 模式），
                    // 避免同一会话里反复问「要不要 rm」。
                    boolean cacheable = !"auto".equals(permissionMode);
                    if (!cacheable || !confirmedPaths.contains(perm.get("message"))) {
                        if (!confirmDangerous(perm.get("message"))) {
                            ct.put("allowed", false);
                            ct.put("result", "User denied this action.");
                            oaiChecked.add(ct);
                            continue;
                        }
                        if (cacheable) {
                            confirmedPaths.add(perm.get("message"));
                        }
                    }
                }
                ct.put("allowed", true);
                oaiChecked.add(ct);
            }

            // ─── 阶段 6：分批 —— 只读工具可并行，有副作用的必须串行 ───────────
            // 连续多个 CONCURRENCY_SAFE_TOOLS（read_file / grep / web_fetch…）合并进
            // concurrent=true 的同一 batch；遇到写文件、run_shell 等则新开串行 batch。
            // 这样既加速「多文件阅读」，又避免并行写同一文件互相踩踏。
            List<Map<String, Object>> oaiBatches = new ArrayList<>();
            for (Map<String, Object> ct : oaiChecked) {
                boolean safe = Boolean.TRUE.equals(ct.get("allowed"))
                        && Tools.CONCURRENCY_SAFE_TOOLS.contains(String.valueOf(ct.get("fn")));
                if (safe && !oaiBatches.isEmpty()
                        && Boolean.TRUE.equals(oaiBatches.get(oaiBatches.size() - 1).get("concurrent"))) {
                    ((List<Map<String, Object>>) oaiBatches.get(oaiBatches.size() - 1).get("items")).add(ct);
                } else {
                    Map<String, Object> batch = new LinkedHashMap<>();
                    batch.put("concurrent", safe);
                    List<Map<String, Object>> items = new ArrayList<>();
                    items.add(ct);
                    batch.put("items", items);
                    oaiBatches.add(batch);
                }
            }

            // ─── 阶段 7：按批执行，把结果写成 role=tool 消息 ─────────────────
            // oaiContextBreak：某工具触发「清空上下文」后，后续同轮工具不再执行，
            // 直接把大结果当新 user 消息推入，然后进入下一圈 while（重新问模型）。
            boolean oaiContextBreak = false;
            for (Map<String, Object> batch : oaiBatches) {
                if (oaiContextBreak || aborted.get()) break;
                List<Map<String, Object>> items =
                        (List<Map<String, Object>>) batch.get("items");

                if (Boolean.TRUE.equals(batch.get("concurrent"))) {
                    // —— 并行路径：线程池同时跑，再按完成顺序写回消息 ——
                    // 注意：写回顺序不一定等于模型发起 tool_calls 的顺序，但每条都带
                    // tool_call_id，模型侧可正确对齐。
                    List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
                    for (Map<String, Object> ctItem : items) {
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            String raw = executeToolCall(
                                    String.valueOf(ctItem.get("fn")),
                                    (Map<String, Object>) ctItem.get("inp"));
                            // 过大结果落盘并截断，避免把整份日志塞进上下文。
                            String res = persistLargeResult(String.valueOf(ctItem.get("fn")), raw);
                            Ui.printToolResult(String.valueOf(ctItem.get("fn")), res);
                            Map<String, Object> pair = new LinkedHashMap<>();
                            pair.put("ct", ctItem);
                            pair.put("res", res);
                            return pair;
                        }, toolExecutor));
                    }
                    for (CompletableFuture<Map<String, Object>> f : futures) {
                        try {
                            Map<String, Object> pair = f.get();
                            Map<String, Object> ctItem = (Map<String, Object>) pair.get("ct");
                            Map<String, Object> tc = (Map<String, Object>) ctItem.get("tc");
                            Map<String, Object> tr = new LinkedHashMap<>();
                            tr.put("role", "tool");
                            tr.put("tool_call_id", tc.get("id"));
                            tr.put("name", ctItem.get("fn"));
                            tr.put("content", pair.get("res"));
                            openaiMessages.add(tr);
                        } catch (Exception e) {
                            // 单工具失败不拖垮整轮；该 tool_call 会缺结果——极端情况下
                            // 模型可能抱怨，但通常其它工具结果已足够继续。
                        }
                    }
                } else {
                    // —— 串行路径：按清单顺序执行（含被拒绝的占位、以及 $web_search）——
                    for (Map<String, Object> ct : items) {
                        Map<String, Object> tc = (Map<String, Object>) ct.get("tc");
                        if (!Boolean.TRUE.equals(ct.get("allowed"))) {
                            // 鉴权阶段已写好 result，这里只负责按协议回传。
                            Map<String, Object> tr = new LinkedHashMap<>();
                            tr.put("role", "tool");
                            tr.put("tool_call_id", tc.get("id"));
                            tr.put("name", ct.get("fn"));
                            tr.put("content", ct.get("result"));
                            openaiMessages.add(tr);
                            continue;
                        }

                        String raw;
                        if (KIMI_BUILTIN_WEB_SEARCH.equals(String.valueOf(ct.get("fn")))) {
                            // 关键：不跑本地搜索，把模型给的 arguments JSON 原样当作「工具输出」。
                            // 下一轮 callOpenAIStream 时，Kimi 服务端看到这份 echo 后才真正检索。
                            raw = String.valueOf(ct.get("rawArgs"));
                        } else {
                            raw = executeToolCall(
                                    String.valueOf(ct.get("fn")),
                                    (Map<String, Object>) ct.get("inp"));
                        }

                        // $web_search 禁止 truncate/persist：任何改写都会破坏服务端校验。
                        String res = KIMI_BUILTIN_WEB_SEARCH.equals(String.valueOf(ct.get("fn")))
                                ? raw
                                : persistLargeResult(String.valueOf(ct.get("fn")), raw);
                        Ui.printToolResult(String.valueOf(ct.get("fn")), res);

                        // 部分工具（如超大输出策略）会置 contextCleared=true：
                        // 丢弃后续同批工具，把当前结果当新 user 消息，打断本圈执行。
                        if (contextCleared) {
                            contextCleared = false;
                            pushOpenAIUserMessage(res);
                            oaiContextBreak = true;
                            break;
                        }

                        Map<String, Object> tr = new LinkedHashMap<>();
                        tr.put("role", "tool");
                        tr.put("tool_call_id", tc.get("id"));
                        tr.put("name", ct.get("fn"));
                        tr.put("content", res);
                        openaiMessages.add(tr);
                    }
                }
            }
            // 本圈工具全部处理完（或中断）后清掉标志，避免泄漏到下一圈 while。
            contextCleared = false;
            // 此处不 break：带着新的 tool 结果回到 while 顶部，再次 callOpenAIStream，
            // 让模型根据工具输出继续推理（可能再调工具，也可能直接给最终回答）。
        }
    }

    private Map<String, Object> callOpenAIStream() {
        return ApiHttp.withRetry(this::doOpenAIStream, 3);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> doOpenAIStream() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        // Kimi $web_search can inflate context; allow a larger completion budget on Moonshot
        body.put("max_tokens", isKimiBackend() ? 32768 : 16384);
        body.put("tools", buildOpenAIToolsPayload());
        body.put("messages", openaiMessages);
        body.put("stream", true);
        Map<String, Object> streamOpts = new LinkedHashMap<>();
        streamOpts.put("include_usage", true);
        body.put("stream_options", streamOpts);
        // Docs: when using $web_search, thinking must be disabled
        if (isKimiBackend()) {
            Map<String, Object> thinking = new LinkedHashMap<>();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        headers.put("Accept", "text/event-stream");

        String base = stripTrailingSlash(apiBase);
        String url = base + "/chat/completions";
        String json = GSON.toJson(body);

        try {
            java.net.http.HttpResponse<InputStream> resp =
                    ApiHttp.postJsonStream(url, json, headers);
            try (InputStream in = resp.body();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {

                StringBuilder content = new StringBuilder();
                boolean firstText = true;
                Map<Integer, Map<String, Object>> toolCalls = new HashMap<>();
                String finishReason = "";
                Map<String, Object> usage = null;

                String line;
                while ((line = reader.readLine()) != null) {
                    if (aborted.get()) break;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    if (data.isEmpty()) continue;
                    JsonObject chunk;
                    try {
                        chunk = JsonParser.parseString(data).getAsJsonObject();
                    } catch (Exception e) {
                        continue;
                    }
                    if (chunk.has("usage") && chunk.get("usage").isJsonObject()) {
                        JsonObject u = chunk.getAsJsonObject("usage");
                        usage = new LinkedHashMap<>();
                        usage.put("prompt_tokens", optInt(u, "prompt_tokens"));
                        usage.put("completion_tokens", optInt(u, "completion_tokens"));
                        int cached = 0;
                        if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) {
                            cached = optInt(u.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
                        }
                        usage.put("cached_tokens", cached);
                    }
                    if (!chunk.has("choices") || !chunk.get("choices").isJsonArray()) continue;
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices.size() == 0) continue;
                    JsonObject ch0 = choices.get(0).getAsJsonObject();
                    if (ch0.has("finish_reason") && !ch0.get("finish_reason").isJsonNull()) {
                        finishReason = ch0.get("finish_reason").getAsString();
                    }
                    if (!ch0.has("delta") || !ch0.get("delta").isJsonObject()) continue;
                    JsonObject delta = ch0.getAsJsonObject("delta");
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String text = delta.get("content").getAsString();
                        if (firstText) {
                            Ui.stopSpinner();
                            emitText("\n");
                            firstText = false;
                        }
                        emitText(text);
                        content.append(text);
                    }
                    if (delta.has("tool_calls") && delta.get("tool_calls").isJsonArray()) {
                        for (JsonElement el : delta.getAsJsonArray("tool_calls")) {
                            JsonObject tc = el.getAsJsonObject();
                            int index = tc.has("index") ? tc.get("index").getAsInt() : 0;
                            Map<String, Object> existing = toolCalls.get(index);
                            if (existing != null) {
                                if (tc.has("type") && !tc.get("type").isJsonNull()
                                        && !tc.get("type").getAsString().isEmpty()) {
                                    existing.put("type", tc.get("type").getAsString());
                                }
                                if (tc.has("function") && tc.get("function").isJsonObject()) {
                                    JsonObject fn = tc.getAsJsonObject("function");
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                        existing.put("arguments",
                                                String.valueOf(existing.get("arguments"))
                                                        + fn.get("arguments").getAsString());
                                    }
                                    if (fn.has("name") && !fn.get("name").isJsonNull()
                                            && !fn.get("name").getAsString().isEmpty()) {
                                        existing.put("name", fn.get("name").getAsString());
                                    }
                                }
                                if (tc.has("id") && !tc.get("id").isJsonNull()
                                        && !tc.get("id").getAsString().isEmpty()) {
                                    existing.put("id", tc.get("id").getAsString());
                                }
                            } else {
                                Map<String, Object> neu = new LinkedHashMap<>();
                                neu.put("id", tc.has("id") && !tc.get("id").isJsonNull()
                                        ? tc.get("id").getAsString() : "");
                                // Preserve builtin_function for Kimi $web_search — forcing
                                // type=function breaks the next-turn hosted search execution.
                                neu.put("type", tc.has("type") && !tc.get("type").isJsonNull()
                                        ? tc.get("type").getAsString() : "function");
                                String name = "";
                                String args = "";
                                if (tc.has("function") && tc.get("function").isJsonObject()) {
                                    JsonObject fn = tc.getAsJsonObject("function");
                                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                        name = fn.get("name").getAsString();
                                    }
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                        args = fn.get("arguments").getAsString();
                                    }
                                }
                                neu.put("name", name);
                                neu.put("arguments", args);
                                toolCalls.put(index, neu);
                            }
                        }
                    }
                }

                List<Map<String, Object>> assembled = null;
                if (!toolCalls.isEmpty()) {
                    assembled = new ArrayList<>();
                    List<Integer> idxs = new ArrayList<>(toolCalls.keySet());
                    Collections.sort(idxs);
                    for (int idx : idxs) {
                        Map<String, Object> tc = toolCalls.get(idx);
                        Map<String, Object> fn = new LinkedHashMap<>();
                        String fnName = String.valueOf(tc.get("name"));
                        fn.put("name", fnName);
                        fn.put("arguments", tc.get("arguments"));
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", tc.get("id"));
                        String tcType = tc.get("type") != null
                                ? String.valueOf(tc.get("type")) : "function";
                        if (KIMI_BUILTIN_WEB_SEARCH.equals(fnName)
                                && !"builtin_function".equals(tcType)) {
                            tcType = "builtin_function";
                        }
                        item.put("type", tcType);
                        item.put("function", fn);
                        assembled.add(item);
                    }
                }

                Map<String, Object> message = new LinkedHashMap<>();
                message.put("role", "assistant");
                message.put("content", content.length() > 0 ? content.toString() : null);
                message.put("tool_calls", assembled);

                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("message", message);
                choice.put("finish_reason",
                        finishReason != null && !finishReason.isEmpty() ? finishReason : "stop");

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("choices", Collections.singletonList(choice));
                if (usage == null) {
                    usage = new LinkedHashMap<>();
                    usage.put("prompt_tokens", 0);
                    usage.put("completion_tokens", 0);
                }
                out.put("usage", usage);
                return out;
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException(e);
        }
    }

    // ─── 非流式 API 辅助 ──────────────────────────────────────────

    private String anthropicNonStream(String system, List<Map<String, Object>> messages, int maxTokens)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        body.put("temperature", 0);
        body.put("messages", messages);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", "2023-06-01");
        String resp = ApiHttp.postJson(anthropicBaseUrl + "/v1/messages", GSON.toJson(body), headers);
        JsonObject obj = JsonParser.parseString(resp).getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        if (obj.has("content") && obj.get("content").isJsonArray()) {
            for (JsonElement el : obj.getAsJsonArray("content")) {
                JsonObject b = el.getAsJsonObject();
                if (b.has("type") && "text".equals(b.get("type").getAsString()) && b.has("text")) {
                    sb.append(b.get("text").getAsString());
                }
            }
        }
        return sb.toString();
    }

    private String openaiNonStream(List<Map<String, Object>> messages, int maxTokens)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0);
        body.put("messages", messages);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + apiKey);
        String base = stripTrailingSlash(apiBase);
        String resp = ApiHttp.postJson(base + "/chat/completions", GSON.toJson(body), headers);
        JsonObject obj = JsonParser.parseString(resp).getAsJsonObject();
        if (obj.has("choices") && obj.getAsJsonArray("choices").size() > 0) {
            JsonObject msg = obj.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message");
            if (msg.has("content") && !msg.get("content").isJsonNull()) {
                return msg.get("content").getAsString();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toOpenAITools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.get("name"));
            fn.put("description", t.get("description"));
            fn.put("parameters", t.get("input_schema"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", fn);
            out.add(item);
        }
        return out;
    }

    /**
     * OpenAI-compatible tools payload. On Moonshot/Kimi, inject builtin
     * {@code $web_search} (first) and drop our local DuckDuckGo {@code web_search}
     * so the model uses the official hosted search.
     * @see <a href="https://platform.kimi.com/docs/guide/use-web-search">Kimi $web_search</a>
     */
    private List<Map<String, Object>> buildOpenAIToolsPayload() {
        List<Map<String, Object>> active = Tools.getActiveToolDefinitions(tools);
        if (isKimiBackend()) {
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> t : active) {
                if ("web_search".equals(String.valueOf(t.get("name")))) {
                    continue;
                }
                filtered.add(t);
            }
            List<Map<String, Object>> out = new ArrayList<>();
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", KIMI_BUILTIN_WEB_SEARCH);
            Map<String, Object> builtin = new LinkedHashMap<>();
            builtin.put("type", "builtin_function");
            builtin.put("function", fn);
            out.add(builtin); // first — prefer hosted search
            out.addAll(toOpenAITools(filtered));
            return out;
        }
        return toOpenAITools(active);
    }

    private boolean confirmDangerous(String command) {
        Ui.printConfirmation(command);
        if (confirmFn != null) {
            return confirmFn.confirm(command);
        }
        try {
            System.out.print("  Allow? (y/n): ");
            System.out.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
            String answer = br.readLine();
            return answer != null && answer.toLowerCase(Locale.ROOT).startsWith("y");
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    private static int optInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return 0;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int asInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o == null) return 0;
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }
}
