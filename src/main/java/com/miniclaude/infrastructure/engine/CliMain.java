package com.miniclaude.infrastructure.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CLI 入口与交互式 REPL。
 *
 * <p>职责：解析命令行参数、解析 API 配置、启动 {@link Agent}，
 * 运行 REPL 循环（含 /clear、/goal、/loop、skills 等命令），
 * 处理 SIGINT 与用户确认回调。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层应用入口，
 * 对应 Python {@code __main__.py}；由 {@code run.sh} 或 {@code java -jar} 启动。
 */
public final class CliMain {

    private CliMain() {}

    // ─── CLI 参数解析 ───────────────────────────────────────────

    /** 命令行解析结果。 */
    public static final class CliArgs {
        public List<String> prompt = new ArrayList<>();
        public boolean yolo;
        public boolean plan;
        public boolean acceptEdits;
        public boolean dontAsk;
        public boolean auto;
        public boolean thinking;
        public String model;
        public String apiBase;
        public boolean resume;
        public Double maxCost;
        public Integer maxTurns;
        public boolean help;
    }

    /** 解析 {@code argv} 为 {@link CliArgs}；未知选项时打印错误并 exit(1)。 */
    public static CliArgs parseArgs(String[] argv) {
        CliArgs args = new CliArgs();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            switch (a) {
                case "--yolo":
                case "-y":
                    args.yolo = true;
                    break;
                case "--plan":
                    args.plan = true;
                    break;
                case "--accept-edits":
                    args.acceptEdits = true;
                    break;
                case "--dont-ask":
                    args.dontAsk = true;
                    break;
                case "--auto":
                    args.auto = true;
                    break;
                case "--thinking":
                    args.thinking = true;
                    break;
                case "--model":
                case "-m":
                    if (i + 1 < argv.length) {
                        args.model = argv[++i];
                    }
                    break;
                case "--api-base":
                    if (i + 1 < argv.length) {
                        args.apiBase = argv[++i];
                    }
                    break;
                case "--resume":
                    args.resume = true;
                    break;
                case "--max-cost":
                    if (i + 1 < argv.length) {
                        try {
                            args.maxCost = Double.parseDouble(argv[++i]);
                        } catch (NumberFormatException e) {
                            Ui.printError("Invalid --max-cost value");
                            System.exit(1);
                        }
                    }
                    break;
                case "--max-turns":
                    if (i + 1 < argv.length) {
                        try {
                            args.maxTurns = Integer.parseInt(argv[++i]);
                        } catch (NumberFormatException e) {
                            Ui.printError("Invalid --max-turns value");
                            System.exit(1);
                        }
                    }
                    break;
                case "--help":
                case "-h":
                    args.help = true;
                    break;
                default:
                    if (a.startsWith("-")) {
                        Ui.printError("Unknown option: " + a);
                        System.exit(1);
                    }
                    args.prompt.add(a);
                    break;
            }
        }
        return args;
    }

    /** 将 CLI 标志映射为 Agent 权限模式字符串。 */
    public static String resolvePermissionMode(CliArgs args) {
        if (args.yolo) {
            return "bypassPermissions";
        }
        if (args.plan) {
            return "plan";
        }
        if (args.acceptEdits) {
            return "acceptEdits";
        }
        if (args.dontAsk) {
            return "dontAsk";
        }
        if (args.auto) {
            return "auto";
        }
        return "default";
    }

    static void printHelp() {
        System.out.println("\n"
                + "Usage: mini-claude [options] [prompt]\n"
                + "\n"
                + "Options:\n"
                + "  --yolo, -y          Skip all confirmation prompts (bypassPermissions mode)\n"
                + "  --plan              Plan mode: read-only, describe changes without executing\n"
                + "  --accept-edits      Auto-approve file edits, still confirm dangerous shell\n"
                + "  --dont-ask          Auto-deny anything needing confirmation (for CI)\n"
                + "  --auto              Auto Mode: an LLM classifier judges each action instead of asking\n"
                + "  --thinking          Enable extended thinking (Anthropic only)\n"
                + "  --model, -m         Model to use (default depends on backend; see env below)\n"
                + "  --api-base URL      OpenAI-compatible API base (Kimi / OpenAI / relays)\n"
                + "  --resume            Resume the last session\n"
                + "  --max-cost USD      Stop when estimated cost exceeds this amount\n"
                + "  --max-turns N       Stop after N agentic turns\n"
                + "  --help, -h          Show this help\n"
                + "\n"
                + "API keys (priority):\n"
                + "  MOONSHOT_API_KEY / KIMI_API_KEY  → Kimi (OpenAI-compatible)\n"
                + "      default base: https://api.moonshot.cn/v1  (override: MOONSHOT_BASE_URL)\n"
                + "      default model: kimi-k2.5  (override: --model or MINI_CLAUDE_MODEL)\n"
                + "  OPENAI_API_KEY (+ OPENAI_BASE_URL) → OpenAI-compatible\n"
                + "  ANTHROPIC_API_KEY (+ ANTHROPIC_BASE_URL) → Anthropic Messages API\n"
                + "\n"
                + "REPL commands:\n"
                + "  /clear              Clear conversation history\n"
                + "  /plan               Toggle plan mode (read-only <-> normal)\n"
                + "  /cost               Show token usage and cost\n"
                + "  /compact            Manually compact conversation\n"
                + "  /goal <condition>   Pursue a goal across turns until an evaluator judges it met\n"
                + "  /goal               Show the active goal's status\n"
                + "  /loop [interval] <prompt>  Re-run a prompt on an interval (5m/2h) or self-paced\n"
                + "  /memory             List saved memories\n"
                + "  /skills             List available skills\n"
                + "  /<skill-name>       Invoke a skill (e.g. /commit \"fix types\")\n"
                + "\n"
                + "Examples:\n"
                + "  mini-claude \"fix the bug in src/app.ts\"\n"
                + "  mini-claude --yolo \"run all tests and fix failures\"\n"
                + "  mini-claude --plan \"how would you refactor this?\"\n"
                + "  MOONSHOT_API_KEY=sk-xxx mini-claude --yolo \"hello\"\n"
                + "  MOONSHOT_API_KEY=sk-xxx mini-claude -m kimi-k2.5 --yolo \"list java files\"\n"
                + "  OPENAI_API_KEY=sk-xxx mini-claude --api-base https://aihubmix.com/v1 --model gpt-4o \"hello\"\n"
                + "  mini-claude --resume\n"
                + "  mini-claude  # starts interactive REPL\n");
    }

    // ─── 控制台输入（private）────────────────────────────────────

    private static final BufferedReader STDIN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    /** Read a line from the console, or {@code null} on EOF. */
    static String readLine(String prompt) throws IOException {
        java.io.Console console = System.console();
        if (console != null) {
            String line = console.readLine("%s", prompt);
            return line; // null on EOF
        }
        if (!prompt.isEmpty()) {
            System.out.print(prompt);
            System.out.flush();
        }
        return STDIN.readLine();
    }

    static boolean confirmFn(String message) {
        try {
            String answer = readLine("  Allow? (y/n): ");
            if (answer == null) {
                return false;
            }
            return answer.toLowerCase().startsWith("y");
        } catch (IOException e) {
            return false;
        }
    }

    static Map<String, String> planApprovalFn(String planContent) {
        Ui.printPlanForApproval(planContent);
        Ui.printPlanApprovalOptions();
        while (true) {
            String choice;
            try {
                choice = readLine("  Enter choice (1-4): ");
            } catch (IOException e) {
                Map<String, String> result = new HashMap<>();
                result.put("choice", "manual-execute");
                return result;
            }
            if (choice == null) {
                Map<String, String> result = new HashMap<>();
                result.put("choice", "manual-execute");
                return result;
            }
            choice = choice.trim();
            Map<String, String> result = new HashMap<>();
            switch (choice) {
                case "1":
                    result.put("choice", "clear-and-execute");
                    return result;
                case "2":
                    result.put("choice", "execute");
                    return result;
                case "3":
                    result.put("choice", "manual-execute");
                    return result;
                case "4":
                    String feedback = "";
                    try {
                        String fb = readLine("  Feedback (what to change): ");
                        if (fb != null) {
                            feedback = fb.trim();
                        }
                    } catch (IOException ignored) {
                        // empty feedback
                    }
                    result.put("choice", "keep-planning");
                    if (!feedback.isEmpty()) {
                        result.put("feedback", feedback);
                    }
                    return result;
                default:
                    System.out.println("  Invalid choice. Enter 1, 2, 3, or 4.");
            }
        }
    }

    // ─── SIGINT 处理（反射 sun.misc.Signal）──────────────────────

    /**
     * 通过 {@code sun.misc.Signal} 注册 SIGINT 处理器（OpenJDK 11）。
     * 新版 JDK 可能需要 {@code --add-exports}；不可用时静默忽略。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void installSigintHandler(Agent agent, AtomicInteger sigintCount) {
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object intSignal = signalClass.getConstructor(String.class).newInstance("INT");

            Object handler = java.lang.reflect.Proxy.newProxyInstance(
                    handlerClass.getClassLoader(),
                    new Class<?>[]{handlerClass},
                    (proxy, method, args) -> {
                        if ("handle".equals(method.getName())) {
                            onSigint(agent, sigintCount);
                        }
                        return null;
                    });

            Method handleMethod = signalClass.getMethod("handle", signalClass, handlerClass);
            handleMethod.invoke(null, intSignal, handler);
        } catch (Throwable ignored) {
            // Signal API unavailable — Ctrl+C may kill the process
        }
    }

    static void onSigint(Agent agent, AtomicInteger sigintCount) {
        agent.stopLoop();
        agent.stopGoal();
        // isProcessing tracks the live task; abort mid-task when active.
        if (agent.isProcessing()) {
            agent.abort();
            System.out.println("\n  (interrupted)");
            sigintCount.set(0);
            Ui.printUserPrompt();
        } else {
            int count = sigintCount.incrementAndGet();
            if (count >= 2) {
                System.out.println("\nBye!\n");
                System.exit(0);
            }
            System.out.println("\n  Press Ctrl+C again to exit.");
            Ui.printUserPrompt();
        }
    }

    // ─── REPL 主循环 ────────────────────────────────────────────

    static void runRepl(Agent agent) {
        agent.setConfirmFn(CliMain::confirmFn);
        agent.setPlanApprovalFn(CliMain::planApprovalFn);

        AtomicInteger sigintCount = new AtomicInteger(0);
        installSigintHandler(agent, sigintCount);

        Ui.printWelcome();

        try {
            while (true) {
                Ui.printUserPrompt();
                String line;
                try {
                    line = readLine("");
                } catch (IOException e) {
                    System.out.println("\nBye!\n");
                    break;
                }
                if (line == null) {
                    System.out.println("\nBye!\n");
                    break;
                }

                String inp = line.trim();
                sigintCount.set(0);

                if (inp.isEmpty()) {
                    continue;
                }
                if ("exit".equals(inp) || "quit".equals(inp)) {
                    System.out.println("\nBye!\n");
                    break;
                }

                // REPL commands
                if ("/clear".equals(inp)) {
                    agent.clearHistory();
                    continue;
                }
                if ("/plan".equals(inp)) {
                    agent.togglePlanMode();
                    continue;
                }
                if ("/cost".equals(inp)) {
                    agent.showCost();
                    continue;
                }
                if ("/compact".equals(inp)) {
                    try {
                        agent.compact();
                    } catch (Exception e) {
                        Ui.printError(e.getMessage() != null ? e.getMessage() : e.toString());
                    }
                    continue;
                }
                if ("/goal".equals(inp) || inp.startsWith("/goal ")) {
                    String condition = inp.substring("/goal".length()).trim();
                    if (condition.isEmpty()) {
                        agent.showGoal();
                        continue;
                    }
                    String directive = agent.setGoal(condition);
                    try {
                        agent.pursueGoal(directive);
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                        if (!msg.toLowerCase().contains("abort")) {
                            Ui.printError(msg);
                        }
                    }
                    continue;
                }
                if ("/loop".equals(inp) || inp.startsWith("/loop ")) {
                    String rest = inp.substring("/loop".length()).trim();
                    try {
                        agent.runLoop(rest);
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                        if (!msg.toLowerCase().contains("abort")) {
                            Ui.printError(msg);
                        }
                    }
                    continue;
                }
                if ("/memory".equals(inp)) {
                    List<Memory.MemoryEntry> memories = Memory.listMemories();
                    if (memories.isEmpty()) {
                        Ui.printInfo("No memories saved yet.");
                    } else {
                        Ui.printInfo(memories.size() + " memories:");
                        for (Memory.MemoryEntry m : memories) {
                            System.out.println("    [" + m.type + "] " + m.name + " — " + m.description);
                        }
                    }
                    continue;
                }
                if ("/skills".equals(inp)) {
                    List<Skills.SkillDefinition> skills = Skills.discoverSkills();
                    if (skills.isEmpty()) {
                        Ui.printInfo("No skills found. Add skills to .claude/skills/<name>/SKILL.md");
                    } else {
                        Ui.printInfo(skills.size() + " skills:");
                        for (Skills.SkillDefinition s : skills) {
                            String tag = s.userInvocable ? "/" + s.name : s.name;
                            System.out.println("    " + tag + " (" + s.source + ") — " + s.description);
                        }
                    }
                    continue;
                }

                // Skill invocation: /<skill-name> [args]
                if (inp.startsWith("/")) {
                    int spaceIdx = inp.indexOf(' ');
                    String cmdName = spaceIdx > 0 ? inp.substring(1, spaceIdx) : inp.substring(1);
                    String cmdArgs = spaceIdx > 0 ? inp.substring(spaceIdx + 1) : "";
                    Skills.SkillDefinition skill = Skills.getSkillByName(cmdName);
                    if (skill != null && skill.userInvocable) {
                        Ui.printInfo("Invoking skill: " + skill.name);
                        try {
                            if ("fork".equals(skill.context)) {
                                Map<String, Object> result = Skills.executeSkill(skill.name, cmdArgs);
                                if (result != null) {
                                    String argsLabel = cmdArgs.isEmpty() ? "(none)" : cmdArgs;
                                    agent.chat("Use the skill tool to invoke \"" + skill.name
                                            + "\" with args: " + argsLabel);
                                }
                            } else {
                                String resolved = Skills.resolveSkillPrompt(skill, cmdArgs);
                                agent.chat(resolved);
                            }
                        } catch (Exception e) {
                            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                            if (!msg.toLowerCase().contains("abort")) {
                                Ui.printError(msg);
                            }
                        }
                        continue;
                    }
                }

                // Normal chat
                try {
                    agent.chat(inp);
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (!msg.toLowerCase().contains("abort")) {
                        Ui.printError(msg);
                    }
                }
            }
        } finally {
            // Loop exited (EOF / exit / quit) — release MCP subprocesses
            agent.close();
        }
    }

    // ─── main 入口 ──────────────────────────────────────────────

    /** 程序入口：解析参数、构建 Agent、单次对话或 REPL。 */
    public static void main(String[] argv) {
        CliArgs args = parseArgs(argv);

        if (args.help) {
            printHelp();
            System.exit(0);
        }

        String permissionMode = resolvePermissionMode(args);
        ApiConfig api = resolveApiConfig(args);

        if (api.apiKey == null || api.apiKey.isEmpty()) {
            Ui.printError(
                    "API key is required.\n"
                    + "  Kimi / Moonshot:  MOONSHOT_API_KEY or KIMI_API_KEY\n"
                    + "      (default base https://api.moonshot.cn/v1; override with MOONSHOT_BASE_URL)\n"
                    + "  OpenAI-compatible: OPENAI_API_KEY + OPENAI_BASE_URL (or --api-base)\n"
                    + "  Anthropic:         ANTHROPIC_API_KEY (+ optional ANTHROPIC_BASE_URL)");
            System.exit(1);
        }

        String model = args.model != null
                ? args.model
                : envOr("MINI_CLAUDE_MODEL", api.defaultModel);

        Agent.Builder builder = Agent.builder()
                .permissionMode(permissionMode)
                .model(model)
                .thinking(args.thinking)
                .apiKey(api.apiKey);

        if (args.maxCost != null) {
            builder.maxCostUsd(args.maxCost);
        }
        if (args.maxTurns != null) {
            builder.maxTurns(args.maxTurns);
        }
        if (api.useOpenAI) {
            builder.apiBase(api.apiBase);
        } else if (api.apiBase != null && !api.apiBase.isEmpty()) {
            builder.anthropicBaseUrl(api.apiBase);
        }

        Agent agent = builder.build();

        // Resume session
        if (args.resume) {
            String sessionId = Session.getLatestSessionId();
            if (sessionId != null) {
                Map<String, Object> session = Session.loadSession(sessionId);
                if (session != null) {
                    Map<String, Object> restore = new HashMap<>();
                    if (session.containsKey("anthropicMessages")) {
                        restore.put("anthropicMessages", session.get("anthropicMessages"));
                    }
                    if (session.containsKey("openaiMessages")) {
                        restore.put("openaiMessages", session.get("openaiMessages"));
                    }
                    agent.restoreSession(restore);
                } else {
                    Ui.printInfo("No session found to resume.");
                }
            } else {
                Ui.printInfo("No previous sessions found.");
            }
        }

        String prompt = args.prompt.isEmpty() ? null : String.join(" ", args.prompt);

        if (prompt != null) {
            // One-shot mode — always release MCP subprocesses on the way out
            try {
                try {
                    agent.chat(prompt);
                } finally {
                    agent.close();
                }
            } catch (Exception e) {
                Ui.printError(e.getMessage() != null ? e.getMessage() : e.toString());
                System.exit(1);
            }
        } else {
            runRepl(agent);
        }
    }

    private static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /** 解析后的 API 后端凭证与默认模型。 */
    public static final class ApiConfig {
        public final String apiKey;
        public final String apiBase;
        public final boolean useOpenAI;
        public final String defaultModel;
        public final String providerLabel;

        public ApiConfig(String apiKey, String apiBase, boolean useOpenAI,
                  String defaultModel, String providerLabel) {
            this.apiKey = apiKey;
            this.apiBase = apiBase;
            this.useOpenAI = useOpenAI;
            this.defaultModel = defaultModel;
            this.providerLabel = providerLabel;
        }
    }

    /** 中国大陆 Moonshot 默认 endpoint；可用 MOONSHOT_BASE_URL / KIMI_BASE_URL 覆盖。 */
    static final String DEFAULT_MOONSHOT_BASE = "https://api.moonshot.cn/v1";
    static final String DEFAULT_KIMI_MODEL = "kimi-k2.6";
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-4-6";

    /**
     * 按优先级解析 API 配置：
     * 1) --api-base + 任意可用 key（OpenAI 兼容）
     * 2) OPENAI_API_KEY + OPENAI_BASE_URL
     * 3) MOONSHOT_API_KEY / KIMI_API_KEY
     * 4) ANTHROPIC_API_KEY
     * 5) 仅 OPENAI_API_KEY
     */
    public static ApiConfig resolveApiConfig(CliArgs args) {
        String openaiKey = System.getenv("OPENAI_API_KEY");
        String openaiBase = System.getenv("OPENAI_BASE_URL");
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String anthropicBase = System.getenv("ANTHROPIC_BASE_URL");
        String moonshotKey = firstNonEmpty(
                System.getenv("MOONSHOT_API_KEY"),
                System.getenv("KIMI_API_KEY"));
        String moonshotBase = firstNonEmpty(
                System.getenv("MOONSHOT_BASE_URL"),
                System.getenv("KIMI_BASE_URL"),
                System.getenv("MOONSHOT_API_BASE"));

        String cliBase = args.apiBase;

        // Explicit --api-base forces OpenAI-compatible path
        if (cliBase != null && !cliBase.isEmpty()) {
            String key = firstNonEmpty(openaiKey, moonshotKey, anthropicKey);
            String defModel = moonshotKey != null ? DEFAULT_KIMI_MODEL : "gpt-4o";
            return new ApiConfig(key, cliBase, true, defModel, "openai-compatible");
        }

        if (openaiKey != null && !openaiKey.isEmpty()
                && openaiBase != null && !openaiBase.isEmpty()) {
            return new ApiConfig(openaiKey, openaiBase, true, "gpt-4o", "openai");
        }

        if (moonshotKey != null && !moonshotKey.isEmpty()) {
            String base = (moonshotBase != null && !moonshotBase.isEmpty())
                    ? moonshotBase
                    : DEFAULT_MOONSHOT_BASE;
            return new ApiConfig(moonshotKey, base, true, DEFAULT_KIMI_MODEL, "kimi");
        }

        if (anthropicKey != null && !anthropicKey.isEmpty()) {
            return new ApiConfig(anthropicKey, anthropicBase, false,
                    DEFAULT_ANTHROPIC_MODEL, "anthropic");
        }

        if (openaiKey != null && !openaiKey.isEmpty()) {
            // Key without base — caller should pass --api-base; still mark as OpenAI path
            return new ApiConfig(openaiKey, openaiBase, openaiBase != null && !openaiBase.isEmpty(),
                    "gpt-4o", "openai");
        }

        return new ApiConfig(null, null, false, DEFAULT_ANTHROPIC_MODEL, "none");
    }
}
