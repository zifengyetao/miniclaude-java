package com.miniclaude.infrastructure.engine;

import java.util.Map;

/**
 * 终端 UI 渲染。
 *
 * <p>职责：在控制台输出彩色文本、工具调用摘要、diff 高亮、Spinner、
 * 计划审批界面及子 Agent 起止提示；使用 ANSI 转义码，无第三方 TUI 库。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层的表现层，
 * 由 {@link Agent}、{@link CliMain} 在交互过程中调用。
 */
public final class Ui {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";
    private static final String WHITE = "\u001B[37m";

    private static final String[] SPINNER_FRAMES = {
            "⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"
    };

    private static volatile Thread spinnerThread = null;
    private static final Object spinnerLock = new Object();
    private static volatile boolean spinnerStop = false;

    private Ui() {}

    // ─── 基础输出 ──────────────────────────────────────────────

    /** 打印 REPL 欢迎信息与可用命令提示。 */
    public static void printWelcome() {
        System.out.println();
        System.out.println("  " + BOLD + CYAN + "Mini Claude Code" + RESET
                + DIM + " — A minimal coding agent" + RESET);
        System.out.println();
        System.out.println(DIM + "  Type your request, or 'exit' to quit." + RESET);
        System.out.println(DIM + "  Commands: /clear /plan /cost /compact /memory /skills" + RESET);
        System.out.println();
    }

    /** 打印用户输入提示符 {@code > }。 */
    public static void printUserPrompt() {
        System.out.println();
        System.out.print(BOLD + GREEN + "> " + RESET);
        System.out.flush();
    }

    /** 流式输出 Assistant 文本（不换行前缀）。 */
    public static void printAssistantText(String text) {
        System.out.print(text);
        System.out.flush();
    }

    /** 显示工具调用名称、图标与参数摘要。 */
    public static void printToolCall(String name, Map<String, ?> inp) {
        String icon = getToolIcon(name);
        String summary = getToolSummary(name, inp);
        System.out.println();
        System.out.println("  " + YELLOW + icon + " " + name + RESET
                + DIM + " " + summary + RESET);
    }

    /** 显示工具执行结果；文件编辑类结果以 diff 形式高亮。 */
    public static void printToolResult(String name, String result) {
        if (("edit_file".equals(name) || "write_file".equals(name))
                && result != null && !result.startsWith("Error")) {
            printFileChangeResult(result);
            return;
        }
        int maxLen = 500;
        String truncated = result != null ? result : "";
        int totalLen = truncated.length();
        if (totalLen > maxLen) {
            truncated = truncated.substring(0, maxLen)
                    + "\n  ... (" + totalLen + " chars total)";
        }
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

    private static void printFileChangeResult(String result) {
        String[] lines = result.split("\n", -1);
        System.out.println(DIM + "  " + lines[0] + RESET);

        int maxDisplay = 40;
        int contentLen = Math.max(0, lines.length - 1);
        int displayCount = Math.min(contentLen, maxDisplay);

        for (int i = 0; i < displayCount; i++) {
            String line = lines[i + 1];
            if (line.trim().isEmpty()) {
                continue;
            }
            if (line.startsWith("@@")) {
                System.out.println(CYAN + "  " + line + RESET);
            } else if (line.startsWith("- ")) {
                System.out.println(RED + "  " + line + RESET);
            } else if (line.startsWith("+ ")) {
                System.out.println(GREEN + "  " + line + RESET);
            } else {
                System.out.println(DIM + "  " + line + RESET);
            }
        }
        if (contentLen > maxDisplay) {
            System.out.println(DIM + "  ... (" + (contentLen - maxDisplay) + " more lines)" + RESET);
        }
    }

    /** 打印错误信息（红色前缀）。 */
    public static void printError(String msg) {
        System.out.println();
        System.out.println("  " + RED + "Error: " + msg + RESET);
    }

    /** 打印危险命令确认提示。 */
    public static void printConfirmation(String command) {
        System.out.println();
        System.out.println("  " + YELLOW + "⚠ Dangerous command:" + RESET
                + " " + WHITE + command + RESET);
    }

    /** 打印回合结束分隔线。 */
    public static void printDivider() {
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            bar.append('─');
        }
        System.out.println();
        System.out.println(DIM + "  " + bar + RESET);
    }

    /** 打印 token 用量与估算费用（不含 cache 明细）。 */
    public static void printCost(int inputTokens, int outputTokens) {
        printCost(inputTokens, outputTokens, 0, 0);
    }

    /** 打印 token 用量与估算费用（含 prompt cache 读/写统计）。 */
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

    /** 打印 API 重试提示。 */
    public static void printRetry(int attempt, int maxRetries, String reason) {
        System.out.println();
        System.out.println("  " + YELLOW + "↻ Retry " + attempt + "/" + maxRetries
                + ": " + reason + RESET);
    }

    /** 打印一般信息提示（青色 ℹ 前缀）。 */
    public static void printInfo(String msg) {
        System.out.println();
        System.out.println("  " + CYAN + "ℹ " + msg + RESET);
    }

    // ─── Spinner 等待动画 ───────────────────────────────────────

    /** 启动默认「Thinking」Spinner。 */
    public static void startSpinner() {
        startSpinner("Thinking");
    }

    /** 启动带自定义标签的 Spinner。 */
    public static void startSpinner(String label) {
        synchronized (spinnerLock) {
            if (spinnerThread != null) {
                return;
            }
            spinnerStop = false;
            final String spinnerLabel = label != null ? label : "Thinking";
            Thread t = new Thread(() -> {
                int frame = 0;
                System.out.print("\n  " + SPINNER_FRAMES[0] + " " + spinnerLabel + "...");
                System.out.flush();
                while (!spinnerStop) {
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    frame = (frame + 1) % SPINNER_FRAMES.length;
                    System.out.print("\r  " + SPINNER_FRAMES[frame] + " " + spinnerLabel + "...");
                    System.out.flush();
                }
            }, "mini-claude-spinner");
            t.setDaemon(true);
            spinnerThread = t;
            t.start();
        }
    }

    /** 停止 Spinner 并清除当前行。 */
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
            System.out.print("\r\u001B[K");
            System.out.flush();
        }
    }

    // ─── 计划模式审批 UI ─────────────────────────────────────────

    /** 展示待审批的计划内容（最多 60 行）。 */
    public static void printPlanForApproval(String planContent) {
        System.out.println();
        System.out.println("  " + CYAN + "━━━ Plan for Approval ━━━" + RESET);
        String[] lines = (planContent != null ? planContent : "").split("\n", -1);
        int maxLines = 60;
        int limit = Math.min(lines.length, maxLines);
        for (int i = 0; i < limit; i++) {
            System.out.println("  " + WHITE + lines[i] + RESET);
        }
        if (lines.length > maxLines) {
            System.out.println(DIM + "  ... (" + (lines.length - maxLines) + " more lines)" + RESET);
        }
        System.out.println("  " + CYAN + "━━━━━━━━━━━━━━━━━━━━━━━━" + RESET);
        System.out.println();
    }

    /** 打印计划审批的四个选项说明。 */
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

    /** 子 Agent 开始执行时的框线提示。 */
    public static void printSubAgentStart(String agentType, String description) {
        System.out.println();
        System.out.println("  " + MAGENTA + "┌─ Sub-agent [" + agentType + "]: "
                + description + RESET);
    }

    /** 子 Agent 完成时的框线提示。 */
    public static void printSubAgentEnd(String agentType, String description) {
        System.out.println("  " + MAGENTA + "└─ Sub-agent [" + agentType + "] completed" + RESET);
    }

    // ─── 工具图标与摘要（private）────────────────────────────────

    private static String getToolIcon(String name) {
        if (name == null) {
            return "🔨";
        }
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

    private static String getToolSummary(String name, Map<String, ?> inp) {
        if (inp == null) {
            return "";
        }
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
            return cmd.length() > 60 ? cmd.substring(0, 60) + "..." : cmd;
        }
        if ("web_search".equals(name) || "$web_search".equals(name)) {
            if ("$web_search".equals(name)) {
                return "Kimi builtin search";
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

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String strOr(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }
}
