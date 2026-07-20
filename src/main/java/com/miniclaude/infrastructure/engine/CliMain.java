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
 * CLI 入口与交互式 REPL 实现。
 *
 * <p>职责：
 * <ul>
 *   <li>解析命令行参数（{@link CliArgs}）与 API 后端配置（{@link ApiConfig}）</li>
 *   <li>构建并启动 {@link Agent}（单次对话或 REPL）</li>
 *   <li>运行 REPL 主循环，处理 slash 命令（{@code /clear}、{@code /goal}、{@code /loop}、skills 等）</li>
 *   <li>提供用户确认回调（{@link #confirmFn}、{@link #planApprovalFn}）与 SIGINT 处理</li>
 * </ul>
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层应用入口，
 * 对应 Python {@code __main__.py}；由 {@code run.sh} 或 {@code java -jar} 启动。
 *
 * <p>运行模式：
 * <ul>
 *   <li><strong>One-shot</strong>：命令行附带 prompt 时执行单次 {@link Agent#chat} 后退出</li>
 *   <li><strong>REPL</strong>：无 prompt 时进入交互循环直至 EOF / exit / quit</li>
 * </ul>
 *
 * @see Agent
 * @see Ui
 */
public final class CliMain {

    /** 工具类禁止实例化；入口为 {@link #main(String[])}。 */
    private CliMain() {}

    // ─── CLI 参数解析 ───────────────────────────────────────────

    /**
     * 命令行解析结果容器。
     *
     * <p>由 {@link #parseArgs(String[])} 填充；字段均为公开可变，供 {@link #main} 读取。
     */
    public static final class CliArgs {
        /**  positional prompt 片段列表（非选项 argv 尾部）。 */
        public List<String> prompt = new ArrayList<>();
        /** {@code --yolo / -y}：跳过所有确认（bypassPermissions）。 */
        public boolean yolo;
        /** {@code --plan}：只读计划模式。 */
        public boolean plan;
        /** {@code --accept-edits}：自动批准文件编辑。 */
        public boolean acceptEdits;
        /** {@code --dont-ask}：自动拒绝需确认的操作（CI 场景）。 */
        public boolean dontAsk;
        /** {@code --auto}：Auto Mode，LLM 分类器判定每次工具调用。 */
        public boolean auto;
        /** {@code --thinking}：启用 extended thinking（Anthropic）。 */
        public boolean thinking;
        /** {@code --model / -m} 指定的模型 ID。 */
        public String model;
        /** {@code --api-base} 指定的 OpenAI 兼容 API base URL。 */
        public String apiBase;
        /** {@code --resume}：恢复最近一次会话。 */
        public boolean resume;
        /** {@code --max-cost}：估算成本上限（USD）。 */
        public Double maxCost;
        /** {@code --max-turns}：Agent 轮次上限。 */
        public Integer maxTurns;
        /** {@code --help / -h}：打印帮助后退出。 */
        public boolean help;
    }

    /**
     * 解析 {@code argv} 为 {@link CliArgs}。
     *
     * <p>未知以 {@code -} 开头的选项会打印错误并 {@link System#exit(int) 1}；
     * {@code --max-cost} / {@code --max-turns} 数值非法时同样 exit(1)。
     *
     * @param argv 命令行参数数组（通常为 {@code main} 的 args）
     * @return 填充完毕的 {@link CliArgs}（不会为 null）
     * @throws 无受检异常；非法选项或数值时直接 {@code System.exit(1)}
     * @sideeffects 非法输入时向 stderr 打印错误并终止 JVM
     */
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
                    // 非选项 token 归入 positional prompt
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

    /**
     * 将 CLI 权限相关标志映射为 Agent {@code permissionMode} 字符串。
     *
     * <p>优先级（先匹配先生效）：yolo → plan → acceptEdits → dontAsk → auto → default。
     *
     * @param args 已解析的命令行参数
     * @return Agent 权限模式字符串（如 {@code bypassPermissions}、{@code auto}）
     * @sideeffects 无 I/O
     */
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

    /**
     * 向 stdout 打印完整 CLI 帮助文本（用法、选项、环境变量、REPL 命令、示例）。
     *
     * @sideeffects 写入 {@link System#out}
     */
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

    /** 无 Console 时的 stdin 缓冲读取器（UTF-8）。 */
    private static final BufferedReader STDIN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    /**
     * 从控制台读取一行用户输入。
     *
     * <p>优先使用 {@link System#console()}；不可用时回退到 {@link #STDIN} 并手动 print prompt。
     *
     * @param prompt 提示前缀；空串时不额外 print（REPL 已由 Ui 打印提示符）
     * @return 用户输入行；EOF 时返回 {@code null}
     * @throws IOException 底层读取失败
     * @sideeffects 可能向 stdout 写入 prompt；阻塞等待用户输入
     */
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

    /**
     * Agent 工具确认回调：询问用户是否允许当前操作。
     *
     * @param message 待确认操作的描述（当前实现未展示，仅固定 y/n 提示）
     * @return 用户输入以 {@code y} 开头（忽略大小写）时返回 {@code true}
     * @sideeffects 向 stdout 打印确认提示；阻塞等待输入
     */
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

    /**
     * Plan 模式审批回调：展示计划并收集用户选择（1–4）。
     *
     * <p>返回 Map 含 {@code choice} 键：
     * {@code clear-and-execute}、{@code execute}、{@code manual-execute}、{@code keep-planning}；
     * 选项 4 可附带 {@code feedback}。
     *
     * @param planContent 待审批的计划 Markdown 文本
     * @return 用户选择结果 Map（不会为 null）
     * @sideeffects 打印计划与选项；循环直至合法选择或 IO/EOF
     */
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
     * 通过 {@code sun.misc.Signal} 注册 SIGINT（Ctrl+C）处理器（OpenJDK 11）。
     *
     * <p>新版 JDK 可能限制 {@code sun.misc} 访问；不可用时静默忽略，
     * 此时 Ctrl+C 可能直接终止进程。
     *
     * @param agent        当前 Agent 实例，用于 stop/abort
     * @param sigintCount  双击 Ctrl+C 退出计数器（由 {@link #onSigint} 维护）
     * @sideeffects 成功时替换 JVM 默认 SIGINT 处理器；失败无操作
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

    /**
     * SIGINT 实际处理逻辑。
     *
     * <p>行为：
     * <ul>
     *   <li>始终停止 loop 与 goal</li>
     *   <li>若 Agent 正在处理任务：abort 并打印 interrupted，重置 sigint 计数</li>
     *   <li>若空闲：第一次提示再按 Ctrl+C 退出；第二次 {@code System.exit(0)}</li>
     * </ul>
     *
     * @param agent       Agent 实例
     * @param sigintCount 连续 SIGINT 计数（空闲态下递增）
     * @sideeffects 可能 abort Agent、打印 UI、{@code System.exit(0)}
     */
    static void onSigint(Agent agent, AtomicInteger sigintCount) {
        agent.stopLoop();
        agent.stopGoal();
        // isProcessing 表示当前是否有 live task；有则 mid-task abort
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

    /**
     * 运行交互式 REPL 主循环。
     *
     * <p>注册确认回调与 SIGINT 处理器，循环读取用户输入并分发：
     * slash 命令、skill 调用或普通 {@link Agent#chat}。
     * 退出时（EOF / exit / quit / break）在 {@code finally} 中 {@link Agent#close()} 释放 MCP 子进程。
     *
     * @param agent 已构建的 Agent 实例
     * @sideeffects 阻塞 stdin；多次调用 Agent API；退出时 close Agent
     */
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
                // 每轮有效输入后重置 SIGINT 双击计数
                sigintCount.set(0);

                if (inp.isEmpty()) {
                    continue;
                }
                if ("exit".equals(inp) || "quit".equals(inp)) {
                    System.out.println("\nBye!\n");
                    break;
                }

                // ── REPL slash 命令 ──
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

                // ── Skill 调用：/<skill-name> [args] ──
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

                // ── 普通对话 ──
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
            // 循环退出（EOF / exit / quit）— 释放 MCP 子进程等资源
            agent.close();
        }
    }

    // ─── main 入口 ──────────────────────────────────────────────

    /**
     * 程序入口：解析参数、解析 API、构建 Agent，执行单次对话或 REPL。
     *
     * <p>流程：
     * <ol>
     *   <li>{@link #parseArgs} → help 则打印并 exit(0)</li>
     *   <li>{@link #resolvePermissionMode} + {@link #resolveApiConfig} → 无 key 则 exit(1)</li>
     *   <li>构建 {@link Agent}；可选 {@code --resume} 恢复会话</li>
     *   <li>有 positional prompt → one-shot {@link Agent#chat}；否则 {@link #runRepl}</li>
     * </ol>
     *
     * @param argv 命令行参数
     * @sideeffects 可能 {@code System.exit}；one-shot 或 REPL 期间大量 I/O 与网络调用
     */
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

        // 模型：CLI --model 优先，否则环境变量，否则 provider 默认
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

        // ── 可选：恢复上次会话 ──
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
            // One-shot 模式 — 退出路径上始终 release MCP 子进程
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

    /**
     * 读取环境变量，空则返回默认值。
     *
     * @param name         环境变量名
     * @param defaultValue 未设置或空串时的回退值
     * @return 非空环境值或 defaultValue
     * @sideeffects 无 I/O（仅 {@link System#getenv}）
     */
    private static String envOr(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v != null && !v.isEmpty()) ? v : defaultValue;
    }

    /**
     * 返回参数列表中第一个非 null 且非空字符串的值。
     *
     * @param values 候选字符串（可变参数）
     * @return 首个有效值；均无则 {@code null}
     * @sideeffects 无 I/O
     */
    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 解析后的 API 后端凭证与默认模型信息。
     *
     * <p>由 {@link #resolveApiConfig(CliArgs)} 构造；字段均为 final 不可变。
     */
    public static final class ApiConfig {
        /** API 密钥（可能为 null，表示未配置）。 */
        public final String apiKey;
        /** API base URL（OpenAI 兼容或 Anthropic base）。 */
        public final String apiBase;
        /** {@code true} 时使用 OpenAI 兼容 Chat Completions 路径。 */
        public final boolean useOpenAI;
        /** 该 provider 的默认模型 ID。 */
        public final String defaultModel;
        /** 人类可读的 provider 标签（如 kimi、anthropic、none）。 */
        public final String providerLabel;

        /**
         * 构造 API 配置快照。
         *
         * @param apiKey         API 密钥
         * @param apiBase        base URL
         * @param useOpenAI      是否 OpenAI 兼容协议
         * @param defaultModel   默认模型
         * @param providerLabel  provider 标识
         * @sideeffects 无
         */
        public ApiConfig(String apiKey, String apiBase, boolean useOpenAI,
                  String defaultModel, String providerLabel) {
            this.apiKey = apiKey;
            this.apiBase = apiBase;
            this.useOpenAI = useOpenAI;
            this.defaultModel = defaultModel;
            this.providerLabel = providerLabel;
        }
    }

    /** 中国大陆 Moonshot 默认 OpenAI 兼容 endpoint。 */
    static final String DEFAULT_MOONSHOT_BASE = "https://api.moonshot.cn/v1";
    /** Kimi 默认模型 ID（无 {@code --model} / {@code MINI_CLAUDE_MODEL} 时使用）。 */
    static final String DEFAULT_KIMI_MODEL = "kimi-k2.6";
    /** Anthropic 默认模型 ID。 */
    static final String DEFAULT_ANTHROPIC_MODEL = "claude-opus-4-6";

    /**
     * 按优先级解析 API 配置。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@code --api-base} + 任意可用 key → OpenAI 兼容</li>
     *   <li>{@code OPENAI_API_KEY} + {@code OPENAI_BASE_URL}</li>
     *   <li>{@code MOONSHOT_API_KEY} / {@code KIMI_API_KEY}（含 base 默认值）</li>
     *   <li>{@code ANTHROPIC_API_KEY}</li>
     *   <li>仅 {@code OPENAI_API_KEY}（无 base 时需 CLI 传 {@code --api-base}）</li>
     *   <li>均无 → {@code apiKey=null}，{@code providerLabel=none}</li>
     * </ol>
     *
     * @param args 已解析 CLI 参数（主要读取 {@code apiBase}）
     * @return {@link ApiConfig} 快照（不会为 null）
     * @sideeffects 读取进程环境变量；无文件 I/O
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

        // 显式 --api-base 强制走 OpenAI 兼容路径
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
            // 仅有 key 无 base — 调用方应传 --api-base；仍标记 OpenAI 路径
            return new ApiConfig(openaiKey, openaiBase, openaiBase != null && !openaiBase.isEmpty(),
                    "gpt-4o", "openai");
        }

        return new ApiConfig(null, null, false, DEFAULT_ANTHROPIC_MODEL, "none");
    }
}
