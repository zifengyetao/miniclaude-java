package com.miniclaude.infrastructure.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Engine 模块离线冒烟测试。
 *
 * <p>职责：在不依赖 API Key 的前提下，验证 Frontmatter、Tools、Autonomy、
 * Session、Prompt、Subagent、CliMain 等核心模块的基本行为。
 *
 * <p>在系统中的位置：{@code src/test/.../engine}，
 * 可通过 {@code main} 直接运行，失败时 exit(1)。
 */
public final class SmokeTest {
    private static int passed = 0;
    private static int failed = 0;

    /** 运行全部 smoke 用例并打印汇总；有失败则 exit(1)。 */
    public static void main(String[] args) throws Exception {
        testFrontmatter();
        testToolsListAndRead();
        testPermission();
        testAutonomy();
        testSession();
        testPrompt();
        testSubagent();
        testCliArgs();

        System.out.println();
        System.out.println("Smoke results: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ─── 断言辅助 ─────────────────────────────────────────────────

    private static void ok(String name, boolean cond, String detail) {
        if (cond) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name + " — " + detail);
        }
    }

    // ─── 各模块测试 ───────────────────────────────────────────────

    private static void testFrontmatter() {
        System.out.println("[Frontmatter]");
        String raw = "---\nname: demo\ntype: project\ndescription: hello\n---\n\nBody here.";
        Frontmatter.FrontmatterResult r = Frontmatter.parseFrontmatter(raw);
        ok("parse meta name", "demo".equals(r.meta.get("name")), String.valueOf(r.meta));
        ok("parse body", "Body here.".equals(r.body), r.body);
        String fmt = Frontmatter.formatFrontmatter(r.meta, r.body);
        ok("roundtrip starts with ---", fmt.startsWith("---\n"), fmt.substring(0, Math.min(20, fmt.length())));
    }

    private static void testToolsListAndRead() throws Exception {
        System.out.println("[Tools]");
        ok("tool defs >= 10", Tools.TOOL_DEFINITIONS.size() >= 10,
                "size=" + Tools.TOOL_DEFINITIONS.size());
        ok("deferred tools present", Tools.getDeferredToolNames().contains("enter_plan_mode"),
                Tools.getDeferredToolNames().toString());

        Path tmp = Files.createTempFile("mini-claude-smoke-", ".txt");
        Files.writeString(tmp, "line1\nline2\nline3\n");
        Map<String, Object> inp = new HashMap<>();
        inp.put("file_path", tmp.toString());
        String content = Tools.executeTool("read_file", inp);
        ok("read_file numbered", content.contains("1 | line1") && content.contains("3 | line3"), content);

        Map<String, Object> listInp = new HashMap<>();
        listInp.put("pattern", "*.java");
        listInp.put("path", "src/main/java/com/miniclaude");
        String listed = Tools.executeTool("list_files", listInp);
        ok("list_files finds Agent.java", listed.contains("Agent.java"), listed);

        Map<String, Object> grepInp = new HashMap<>();
        grepInp.put("pattern", "class Agent");
        grepInp.put("path", "src/main/java/com/miniclaude");
        grepInp.put("include", "*.java");
        String grepped = Tools.executeTool("grep_search", grepInp);
        ok("grep_search finds Agent", grepped.contains("Agent.java"), grepped);

        Map<String, Object> shellInp = new HashMap<>();
        shellInp.put("command", "echo smoke-ok");
        String shell = Tools.executeTool("run_shell", shellInp);
        ok("run_shell", shell.contains("smoke-ok"), shell);

        ok("dangerous rm", Tools.isDangerous("rm -rf /tmp/x"), "expected true");
        ok("safe echo", !Tools.isDangerous("echo hi"), "expected false");

        Files.deleteIfExists(tmp);
    }

    private static void testPermission() {
        System.out.println("[Permission]");
        Map<String, Object> read = new HashMap<>();
        read.put("file_path", "README.md");
        Map<String, String> p1 = Tools.checkPermission("read_file", read, "default", null);
        ok("read allowed", "allow".equals(p1.get("action")), String.valueOf(p1));

        Map<String, Object> shell = new HashMap<>();
        shell.put("command", "rm -rf /tmp/x");
        Map<String, String> p2 = Tools.checkPermission("run_shell", shell, "default", null);
        ok("dangerous confirm", "confirm".equals(p2.get("action")), String.valueOf(p2));

        Map<String, String> p3 = Tools.checkPermission("run_shell", shell, "bypassPermissions", null);
        ok("yolo allow", "allow".equals(p3.get("action")), String.valueOf(p3));

        Map<String, Object> write = new HashMap<>();
        write.put("file_path", "/tmp/something-new-xyz.txt");
        write.put("content", "x");
        Map<String, String> p4 = Tools.checkPermission("write_file", write, "plan", null);
        ok("plan blocks write", "deny".equals(p4.get("action")), String.valueOf(p4));
    }

    private static void testAutonomy() {
        System.out.println("[Autonomy]");
        Autonomy.GoalVerdict gv = Autonomy.parseGoalVerdict("{\"ok\": true, \"reason\": \"done\"}");
        ok("goal verdict ok", gv.ok && "done".equals(gv.reason), gv.reason);

        Autonomy.GoalVerdict bad = Autonomy.parseGoalVerdict("not json");
        ok("goal fail-closed", !bad.ok, bad.reason);

        Map<String, Object> loop = Autonomy.parseLoopInput("5m check status");
        ok("loop interval", "interval".equals(loop.get("mode")) && Integer.valueOf(300).equals(loop.get("interval_seconds")),
                String.valueOf(loop));

        Map<String, Object> dyn = Autonomy.parseLoopInput("watch the build");
        ok("loop dynamic", "dynamic".equals(dyn.get("mode")), String.valueOf(dyn));

        ok("clamp wakeup", Autonomy.clampWakeupDelay(10) == 60, String.valueOf(Autonomy.clampWakeupDelay(10)));
        ok("clamp upper", Autonomy.clampWakeupDelay(99999) == 3600, String.valueOf(Autonomy.clampWakeupDelay(99999)));

        Autonomy.BlockVerdict bv = Autonomy.parseBlockVerdict("<block>no</block>");
        ok("classifier allow", !bv.block, bv.reason);

        Autonomy.BlockVerdict block = Autonomy.parseBlockVerdict("<block>yes</block><reason>risky</reason>");
        ok("classifier block", block.block && block.reason.contains("risky"), block.reason);

        Map<String, Object> rules = Autonomy.loadAutoModeRules();
        ok("auto rules loaded", rules.containsKey("system_skeleton") && rules.containsKey("allow"),
                rules.keySet().toString());
    }

    private static void testSession() throws Exception {
        System.out.println("[Session]");
        String id = "smoke-test-" + System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> meta = new HashMap<>();
        meta.put("id", id);
        meta.put("startTime", "2099-01-01T00:00:00Z");
        meta.put("model", "test");
        data.put("metadata", meta);
        Session.saveSession(id, data);
        Map<String, Object> loaded = Session.loadSession(id);
        ok("session roundtrip", loaded != null && id.equals(((Map<?, ?>) loaded.get("metadata")).get("id")),
                String.valueOf(loaded));
        Files.deleteIfExists(Session.SESSION_DIR.resolve(id + ".json"));
    }

    private static void testPrompt() {
        System.out.println("[Prompt]");
        String sys = Prompt.buildSystemPrompt();
        ok("system prompt non-empty", sys.length() > 500, "len=" + sys.length());
        ok("has environment", sys.contains("Working directory:"), sys.substring(0, Math.min(200, sys.length())));
        String reminder = Prompt.buildUserContextReminder();
        ok("user reminder", reminder.contains("<system-reminder>") && reminder.contains("currentDate"),
                reminder.substring(0, Math.min(120, reminder.length())));
    }

    private static void testSubagent() {
        System.out.println("[Subagent]");
        Subagent.SubAgentConfig explore = Subagent.getSubAgentConfig("explore");
        ok("explore readonly", explore.allowedToolNames != null
                        && explore.allowedToolNames.contains("read_file")
                        && !explore.allowedToolNames.contains("write_file"),
                String.valueOf(explore.allowedToolNames));
        Subagent.SubAgentConfig general = Subagent.getSubAgentConfig("general");
        ok("general all-except-agent", general.allowedToolNames == null, String.valueOf(general.allowedToolNames));
    }

    private static void testCliArgs() {
        System.out.println("[CliMain CLI]");
        CliMain.CliArgs a = CliMain.parseArgs(new String[]{"--yolo", "--model", "gpt-4o", "hello", "world"});
        ok("yolo flag", a.yolo, "yolo=" + a.yolo);
        ok("model", "gpt-4o".equals(a.model), a.model);
        ok("prompt join", a.prompt.size() == 2, a.prompt.toString());
        ok("perm yolo", "bypassPermissions".equals(CliMain.resolvePermissionMode(a)), CliMain.resolvePermissionMode(a));
    }
}
