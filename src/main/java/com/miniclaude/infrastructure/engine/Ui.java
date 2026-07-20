package com.miniclaude.infrastructure.engine;

import java.util.Map;

/**
 * 终端 UI 渲染工具类（ANSI 彩色控制台输出）。
 *
 * <p><b>职责</b>
 * <ul>
 *   <li>REPL 欢迎信息、用户提示符、Assistant 流式文本输出</li>
 *   <li>工具调用/结果的格式化展示（含 diff 高亮、截断长输出）</li>
 *   <li>Spinner 等待动画（API 调用期间）</li>
 *   <li>计划模式审批界面、子 Agent 起止框线提示</li>
 *   <li>错误、重试、费用、危险命令确认等信息提示</li>
 * </ul>
 *
 * <p><b>在系统中的位置</b>
 * 位于 {@code infrastructure/engine} 层的表现层（Presentation），不依赖 Spring Web 或 React。
 * 由 {@link Agent} 执行循环与 {@link CliMain} REPL 在交互过程中直接调用，
 * 输出目标为 {@link System#out}（标准输出）。
 *
 * <p><b>线程安全</b>
 * <ul>
 *   <li>除 Spinner 相关方法外，其余方法无共享可变状态，可从任意线程调用</li>
 *   <li>Spinner 通过 {@link #spinnerLock} 互斥访问 {@link #spinnerThread} 与
 *       {@link #spinnerStop}；同一时刻最多一个 Spinner 线程</li>
 *   <li>ANSI 常量均为 {@code static final}，线程安全</li>
 *   <li>非线程安全场景：多线程并发调用 {@code print*} 可能导致控制台输出交错
 *       （当前 CLI 为单线程 REPL，未做额外同步）</li>
 * </ul>
 *
 * <p><b>限制与约定</b>
 * <ul>
 *   <li>依赖终端支持 ANSI 转义码；不支持时可能显示乱码（无 TTY 检测）</li>
 *   <li>无第三方 TUI 库，不使用 curses/JLine</li>
 *   <li>工具结果默认截断至 500 字符；文件 diff 最多展示 40 行</li>
 *   <li>费用估算基于硬编码单价，与 {@link Agent} 内部计费逻辑对齐，非官方账单</li>
 *   <li>Emoji 图标依赖终端字体支持</li>
 * </ul>
 *
 * @see Agent
 * @see CliMain
 */
public final class Ui {

    /** ANSI：重置所有样式。 */
    private static final String RESET = "\u001B[0m";
    /** ANSI：粗体。 */
    private static final String BOLD = "\u001B[1m";
    /** ANSI：暗淡（次要文本）。 */
    private static final String DIM = "\u001B[2m";
    /** ANSI：前景红色（错误、删除行）。 */
    private static final String RED = "\u001B[31m";
    /** ANSI：前景绿色（用户提示符、新增行）。 */
    private static final String GREEN = "\u001B[32m";
    /** ANSI：前景黄色（工具调用、警告）。 */
    private static final String YELLOW = "\u001B[33m";
    /** ANSI：前景洋红（子 Agent 框线）。 */
    private static final String MAGENTA = "\u001B[35m";
    /** ANSI：前景青色（信息、diff 块头）。 */
    private static final String CYAN = "\u001B[36m";
    /** ANSI：前景白色（计划正文、命令文本）。 */
    private static final String WHITE = "\u001B[37m";

    /** Braille 点阵 Spinner 动画帧序列（10 帧循环）。 */
    private static final String[] SPINNER_FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };

    /** 当前 Spinner 后台线程；{@code null} 表示未运行。 */
    private static volatile Thread spinnerThread = null;
    /** Spinner 启动/停止的互斥锁。 */
    private static final Object spinnerLock = new Object();
    /** Spinner 线程的停止标志；{@code true} 时循环退出。 */
    private static volatile boolean spinnerStop = false;

    /** 工具类，禁止实例化。 */
    private Ui() {}

    // ─── 基础输出 ──────────────────────────────────────────────

    /**
     * 打印 REPL 启动时的欢迎信息与可用斜杠命令提示。
     *
     * @sideeffects 向 {@link System#out} 写入多行文本并换行
     */
    public static void printWelcome() {
        System.out.println();
        System.out.println("  " + BOLD + CYAN + "Mini Claude Code" + RESET
                + DIM + " — A minimal coding agent" + RESET);
        System.out.println();
        System.out.println(DIM + "  Type your request, or 'exit' to quit." + RESET);
        System.out.println(DIM + "  Commands: /clear /plan /cost /compact /memory /skills" + RESET);
        System.out.println();
    }

    /**
     * 打印用户输入提示符 {@code > }（绿色粗体），并 flush 以便立即显示。
     *
     * @sideeffects 写入提示符到标准输出
     */
    public static void printUserPrompt() {
        System.out.println();
        System.out.print(BOLD + GREEN + "> " + RESET);
        System.out.flush();
    }

    /**
     * 流式输出 Assistant 生成的文本片段（无前缀、不换行，由调用方控制分段）。
     *
     * @param text 待输出文本；{@code null} 时打印字面 {@code "null"}
     * @sideeffects 写入标准输出并 flush
     */
    public static void printAssistantText(String text) {
        System.out.print(text);
        System.out.flush();
    }

    /**
     * 显示工具调用摘要：Emoji 图标 + 工具名 + 参数简述（黄色主色、暗淡摘要）。
     *
     * @param name 工具名称，如 {@code "read_file"}
     * @param inp  工具输入参数 Map；{@code null} 时摘要为空
     * @sideeffects 向标准输出写入一行工具调用提示
     */
    public static void printToolCall(String name, Map<String, ?> inp) {
        String icon = getToolIcon(name);
        String summary = getToolSummary(name, inp);
        System.out.println();
        System.out.println("  " + YELLOW + icon + " " + name + RESET
                + DIM + " " + summary + RESET);
    }

    /**
     * 显示工具执行结果。
     *
     * <p>对 {@code edit_file} / {@code write_file} 且非 Error 的结果走 diff 高亮路径；
     * 其余工具结果截断至 500 字符并以暗淡样式逐行缩进输出。
     *
     * @param name   工具名称
     * @param result 工具返回的字符串结果；{@code null} 视为空串
     * @sideeffects 向标准输出写入格式化结果
     */
    public static void printToolResult(String name, String result) {
        // ── 文件变更类工具：diff 高亮展示 ──
        if (("edit_file".equals(name) || "write_file".equals(name))
                && result != null && !result.startsWith("Error")) {
            printFileChangeResult(result);
            return;
        }
        // ── 通用结果：截断 + 逐行缩进 ──
        int maxLen = 500;
        String truncated = result != null ? result : "";
        int totalLen = truncated.length();
        if (totalLen > maxLen) {
            truncated = truncated.substring(0, maxLen)
                    + "\n  ... (" + totalLen + " chars total)";
        }
        // split(..., -1) 保留末尾空行
        String[] parts = truncated.split("\n", -1);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                lines.append('\n');
            }
            lines.append("  ").append(parts[i]);
        }
        System.out.println(DIM + lines + RESET);
    }

    /**
     * 以 unified diff 风格高亮打印文件变更结果（私有辅助）。
     *
     * <p>首行通常为摘要（如文件名），后续行按 {@code @@} / {@code - } / {@code + } 前缀着色；
     * 最多展示 40 行 diff 内容，超出部分提示省略行数。
     *
     * @param result 工具返回的 diff 格式字符串
     * @sideeffects 向标准输出写入多行彩色 diff
     */
    private static void printFileChangeResult(String result) {
        String[] lines = result.split("\n", -1);
        // 第一行：文件路径或变更摘要
        System.out.println(DIM + "  " + lines[0] + RESET);

        int maxDisplay = 40;
        int contentLen = Math.max(0, lines.length - 1); // 除首行外的 diff 行数
        int displayCount = Math.min(contentLen, maxDisplay);

        // ── 逐行 diff 着色 ──
        for (int i = 0; i < displayCount; i++) {
            String line = lines[i + 1]; // 跳过首行摘要
            if (line.trim().isEmpty()) {
                continue; // 跳过纯空白行，减少视觉噪音
            }
            if (line.startsWith("@@")) {
                System.out.println(CYAN + "  " + line + RESET);   // hunk 头
            } else if (line.startsWith("- ")) {
                System.out.println(RED + "  " + line + RESET);     // 删除
            } else if (line.startsWith("+ ")) {
                System.out.println(GREEN + "  " + line + RESET);  // 新增
            } else {
                System.out.println(DIM + "  " + line + RESET);    // 上下文行
            }
        }
        // ── 超出展示上限时的省略提示 ──
        if (contentLen > maxDisplay) {
            System.out.println(DIM + "  ... (" + (contentLen - maxDisplay) + " more lines)" + RESET);
        }
    }

    /**
     * 打印错误信息（红色 {@code Error:} 前缀）。
     *
     * @param msg 错误描述文本
     * @sideeffects 向标准输出写入两行（空行 + 错误行）
     */
    public static void printError(String msg) {
        System.out.println();
        System.out.println("  " + RED + "Error: " + msg + RESET);
    }

    /**
     * 打印危险 Shell 命令的用户确认提示。
     *
     * @param command 待确认的完整命令字符串
     * @sideeffects 向标准输出写入警告行
     */
    public static void printConfirmation(String command) {
        System.out.println();
        System.out.println("  " + YELLOW + "⚠ Dangerous command:" + RESET
                + " " + WHITE + command + RESET);
    }

    /**
     * 打印回合结束分隔线（50 个 {@code ─} 字符，暗淡样式）。
     *
     * @sideeffects 向标准输出写入空行与分隔线
     */
    public static void printDivider() {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            bar.append('─');
        }
        System.out.println();
        System.out.println(DIM + "  " + bar + RESET);
    }

    /**
     * 打印 token 用量与估算费用（不含 prompt cache 明细）。
     *
     * @param inputTokens  输入 token 数
     * @param outputTokens 输出 token 数
     * @sideeffects 委托 {@link #printCost(int, int, int, int)}，cache 参数为 0
     */
    public static void printCost(int inputTokens, int outputTokens) {
        printCost(inputTokens, outputTokens, 0, 0);
    }

    /**
     * 打印 token 用量与估算费用（含 prompt cache 读/写统计）。
     *
     * <p>单价（每百万 token，美元）：
     * input $3、cache read $0.3（0.1×）、cache write $3.75（1.25×）、output $15。
     * 与 Agent 内部 {@code _get_current_cost_usd} 逻辑对齐。
     *
     * @param inputTokens     普通输入 token 数
     * @param outputTokens    输出 token 数
     * @param cacheRead       cache 命中读取 token 数
     * @param cacheCreation   cache 写入 token 数
     * @sideeffects 向标准输出写入费用摘要行
     */
    public static void printCost(int inputTokens, int outputTokens, int cacheRead, int cacheCreation) {
        // Cache read is billed 0.1x, cache write 1.25x (see agent _get_current_cost_usd).
        double total =
                (inputTokens / 1_000_000.0) * 3
                + (cacheRead / 1_000_000.0) * 0.3
                + (cacheCreation / 1_000_000.0) * 3.75
                + (outputTokens / 1_000_000.0) * 15;
        String cacheStr = cacheRead > 0 ? ", " + cacheRead + " cached" : "";
        System.out.println();
        System.out.printf(DIM + "  Tokens: %d in / %d out%s (~$%.4f)" + RESET + "%n",
                inputTokens, outputTokens, cacheStr, total);
    }

    /**
     * 打印 API 调用重试提示。
     *
     * @param attempt    当前重试次数（从 1 起）
     * @param maxRetries 最大重试次数
     * @param reason     重试原因简述
     * @sideeffects 向标准输出写入黄色重试行
     */
    public static void printRetry(int attempt, int maxRetries, String reason) {
        System.out.println();
        System.out.println("  " + YELLOW + "↻ Retry " + attempt + "/" + maxRetries
                + ": " + reason + RESET);
    }

    /**
     * 打印一般信息提示（青色 {@code ℹ} 前缀）。
     *
     * @param msg 信息文本
     * @sideeffects 向标准输出写入信息行
     */
    public static void printInfo(String msg) {
        System.out.println();
        System.out.println("  " + CYAN + "ℹ " + msg + RESET);
    }

    // ─── Spinner 等待动画 ───────────────────────────────────────

    /**
     * 启动默认标签为 {@code "Thinking"} 的 Spinner 等待动画。
     *
     * @sideeffects 若已有 Spinner 运行则忽略；否则启动守护线程
     */
    public static void startSpinner() {
        startSpinner("Thinking");
    }

    /**
     * 启动带自定义标签的 Spinner 等待动画。
     *
     * <p>Spinner 在独立守护线程中每 80ms 刷新一帧，使用 {@code \r} 覆写当前行。
     * 若已有 Spinner 在运行，本调用直接返回（不叠加多个 Spinner）。
     *
     * @param label 显示标签；{@code null} 时使用 {@code "Thinking"}
     * @sideeffects 可能创建并启动名为 {@code mini-claude-spinner} 的守护线程
     */
    public static void startSpinner(String label) {
        synchronized (spinnerLock) {
            // ── 防重入：已有 Spinner 则不重复启动 ──
            if (spinnerThread != null) {
                return;
            }
            spinnerStop = false;
            final String spinnerLabel = label != null ? label : "Thinking";
            Thread t = new Thread(() -> {
                int frame = 0;
                // 首帧：换行后显示 Spinner
                System.out.print("\n  " + SPINNER_FRAMES[0] + " " + spinnerLabel + "...");
                System.out.flush();
                while (!spinnerStop) {
                    try {
                        Thread.sleep(80); // 约 12.5 FPS
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    frame = (frame + 1) % SPINNER_FRAMES.length;
                    // \r 回到行首覆写，实现动画效果
                    System.out.print("\r  " + SPINNER_FRAMES[frame] + " " + spinnerLabel + "...");
                    System.out.flush();
                }
            }, "mini-claude-spinner");
            t.setDaemon(true); // 守护线程，JVM 退出时不阻塞
            spinnerThread = t;
            t.start();
        }
    }

    /**
     * 停止 Spinner 并清除当前行（ANSI {@code EL} 擦除至行尾）。
     *
     * <p>设置停止标志后最多等待 Spinner 线程 1 秒；超时仍清空线程引用。
     *
     * @sideeffects 中断 Spinner 循环、join 线程、擦除 Spinner 行
     */
    public static void stopSpinner() {
        synchronized (spinnerLock) {
            if (spinnerThread == null) {
                return;
            }
            spinnerStop = true;
            try {
                spinnerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            spinnerThread = null;
            // \u001B[K = EL（Erase in Line），清除 Spinner 残留
            System.out.print("\r\u001B[K");
            System.out.flush();
        }
    }

    // ─── 计划模式审批 UI ─────────────────────────────────────────

    /**
     * 展示待用户审批的计划内容（带框线标题，最多 60 行正文）。
     *
     * @param planContent 计划 Markdown/纯文本；{@code null} 视为空串
     * @sideeffects 向标准输出写入多行计划正文
     */
    public static void printPlanForApproval(String planContent) {
        System.out.println();
        System.out.println("  " + CYAN + "━━━ Plan for Approval ━━━" + RESET);
        String[] lines = (planContent != null ? planContent : "").split("\n", -1);
        int maxLines = 60;
        int limit = Math.min(lines.length, maxLines);
        for (int i = 0; i < limit; i++) {
            System.out.println("  " + WHITE + lines[i] + RESET);
        }
        // ── 计划过长时的省略提示 ──
        if (lines.length > maxLines) {
            System.out.println(DIM + "  ... (" + (lines.length - maxLines) + " more lines)" + RESET);
        }
        System.out.println("  " + CYAN + "━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        System.out.println();
    }

    /**
     * 打印计划审批的四个选项说明（对应 CliMain 读取 1–4 的用户输入）。
     *
     * @sideeffects 向标准输出写入 4 行选项说明
     */
    public static void printPlanApprovalOptions() {
        System.out.println("  " + YELLOW + "Choose an option:" + RESET);
        System.out.println("    " + WHITE + "1) Yes, clear context and execute" + RESET
                + DIM + " — fresh start with auto-accept edits" + RESET);
        System.out.println("    " + WHITE + "2) Yes, and execute" + RESET
                + DIM + " — keep context, auto-accept edits" + RESET);
        System.out.println("    " + WHITE + "3) Yes, manually approve edits" + RESET
                + DIM + " — keep context, confirm each edit" + RESET);
        System.out.println("    " + WHITE + "4) No, keep planning" + RESET
                + DIM + " — provide feedback to revise" + RESET);
    }

    // ─── 子 Agent 显示 ───────────────────────────────────────────

    /**
     * 子 Agent 开始执行时的框线提示（洋红色 {@code ┌─} 前缀）。
     *
     * @param agentType   子 Agent 类型，如 {@code "explore"}
     * @param description 任务描述（由父 Agent 传入）
     * @sideeffects 向标准输出写入起始框线
     */
    public static void printSubAgentStart(String agentType, String description) {
        System.out.println();
        System.out.println("  " + MAGENTA + "┌─ Sub-agent [" + agentType + "]: "
                + description + RESET);
    }

    /**
     * 子 Agent 完成时的框线提示（洋红色 {@code └─} 前缀）。
     *
     * @param agentType   子 Agent 类型
     * @param description 任务描述（当前仅用于 API 对称，显示中未再次输出）
     * @sideeffects 向标准输出写入结束框线
     */
    public static void printSubAgentEnd(String agentType, String description) {
        System.out.println("  " + MAGENTA + "└─ Sub-agent [" + agentType + "] completed" + RESET);
    }

    // ─── 工具图标与摘要（private）────────────────────────────────

    /**
     * 根据工具名返回对应的 Emoji 图标；未知工具使用默认锤子。
     *
     * @param name 工具名称；{@code null} 时返回 {@code 🔨}
     * @return 单字符 Emoji 字符串
     */
    private static String getToolIcon(String name) {
        if (name == null) {
            return "🔨";
        }
        // ── 工具名 → Emoji 映射 ──
        switch (name) {
            case "read_file":
                return "📖";
            case "write_file":
                return "✏️";
            case "edit_file":
                return "🔧";
            case "list_files":
                return "📁";
            case "grep_search":
                return "🔍";
            case "run_shell":
                return "💻";
            case "web_search":
            case "$web_search":
                return "🌐";
            case "web_fetch":
                return "🔗";
            case "skill":
                return "⚡";
            case "agent":
                return "🤖";
            default:
                return "🔨";
        }
    }

    /**
     * 从工具输入 Map 提取一行可读参数摘要，供 {@link #printToolCall} 展示。
     *
     * @param name 工具名称
     * @param inp  工具参数字典；{@code null} 返回空串
     * @return 简短摘要，如文件路径、grep 模式、截断后的 shell 命令等
     */
    private static String getToolSummary(String name, Map<String, ?> inp) {
        if (inp == null) {
            return "";
        }
        // ── 按工具类型分发摘要逻辑 ──
        if ("read_file".equals(name) || "write_file".equals(name) || "edit_file".equals(name)) {
            return str(inp.get("file_path"));
        }
        if ("list_files".equals(name)) {
            return str(inp.get("pattern"));
        }
        if ("grep_search".equals(name)) {
            return "\"" + str(inp.get("pattern")) + "\" in " + strOr(inp.get("path"), ".");
        }
        if ("run_shell".equals(name)) {
            String cmd = str(inp.get("command"));
            // 长命令截断，避免控制台单行过长
            return cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd;
        }
        if ("web_search".equals(name) || "$web_search".equals(name)) {
            if ("$web_search".equals(name)) {
                return "Kimi builtin search"; // 内置搜索无 query 参数时的固定文案
            }
            return str(inp.get("query"));
        }
        if ("web_fetch".equals(name)) {
            return str(inp.get("url"));
        }
        if ("skill".equals(name)) {
            return str(inp.get("skill_name"));
        }
        if ("agent".equals(name)) {
            return "[" + strOr(inp.get("type"), "general") + "] " + str(inp.get("description"));
        }
        return "";
    }

    /** 将对象转为字符串；{@code null} 返回空串。 */
    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    /** 将对象转为字符串；{@code null} 返回 {@code fallback}。 */
    private static String strOr(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }
}
