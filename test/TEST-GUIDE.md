# Mini Claude Java — 功能测试指南

手动测试 19 项功能。全部使用 `--yolo` 模式。

> 场景与主仓库 `test/TEST-GUIDE.md` 对齐；路径与启动命令已改为 Java 版。

## 准备

```bash
cd miniclaude-java

# 一键配置（MCP、Skills、CLAUDE.md、大文件）并打包
bash test/setup.sh
```

确保 API Key 已配置（任选其一）：

```bash
# 推荐：Kimi / Moonshot（国内）
export MOONSHOT_API_KEY=sk-xxx
# 默认 base=https://api.moonshot.cn/v1 ，默认模型=kimi-k2.5
# 国际站：export MOONSHOT_BASE_URL=https://api.moonshot.ai/v1

# Anthropic / 兼容中继
export ANTHROPIC_API_KEY=sk-xxx
export ANTHROPIC_BASE_URL=https://aihubmix.com   # 可选

# 或其它 OpenAI 兼容
export OPENAI_API_KEY=sk-xxx
export OPENAI_BASE_URL=https://aihubmix.com/v1
```

也可 `cp .env.example .env` 后填写；`setup.sh` 会尝试 source `.env`。

离线冒烟（无需 Key）：

```bash
javac -cp target/miniclaude-java-1.0.0.jar -d target/test-classes \
  src/test/java/com/miniclaude/infrastructure/engine/SmokeTest.java
java -cp target/test-classes:target/miniclaude-java-1.0.0.jar \
  com.miniclaude.infrastructure.engine.SmokeTest
```

---

## 启动方式

```bash
JAR=target/miniclaude-java-1.0.0.jar

# 交互式 REPL（推荐，能测 skill 和 REPL 命令）
java -jar $JAR --cli --yolo

# one-shot
java -jar $JAR --cli --yolo "你的提示词"
```

OpenAI 兼容后端示例：

```bash
java -jar $JAR --cli --api-base "$OPENAI_BASE_URL" --model gpt-4o-mini --yolo
```

---

## 测试项目

### 1. MCP 工具调用

**预期**：启动时看到 `[mcp] Connected to 'test' — 3 tools`（需本机有 `node`）

```
Use the MCP 'add' tool to compute 17+25, then use the 'echo' tool to echo "hello MCP", then use the 'timestamp' tool.
```

✅ add→`42`，echo→`hello MCP`，timestamp 为 Unix 时间戳；工具名带 `mcp__test__` 前缀

---

### 2. WebFetch

```
Fetch the URL https://httpbin.org/json and tell me the slideshow title.
```

✅ `Sample Slide Show`

```
Fetch https://example.com and tell me what the page is about.
```

✅ HTML 转纯文本

---

### 3. 并行工具执行

```
Read the files src/main/java/com/miniclaude/Frontmatter.java, src/main/java/com/miniclaude/Session.java, and src/main/java/com/miniclaude/Skills.java at the same time, then tell me each file's line count.
```

✅ 三个 `read_file` 几乎同时出现（非串行一个接一个）

---

### 4. 语义记忆召回

**保存记忆：**

```
Save these memories for me:
1. type=project, name="API migration", description="Moving from REST to GraphQL", content="We are migrating our API from REST to GraphQL. Deadline is end of Q2 2025."
2. type=feedback, name="code style", description="Prefers functional programming", content="User prefers functional patterns (map/filter/reduce) over for loops and OOP."
3. type=reference, name="staging server", description="Staging environment URL", content="Staging server: https://staging.example.com, credentials in 1Password."
```

**退出后新开 REPL**，用会触发工具的查询（给 prefetch 时间）：

```
Read the file pom.xml, then tell me: where can I deploy to test my changes?
```

✅ 召回 staging → `https://staging.example.com`

```
List the files in src/main/java/com/miniclaude/, then tell me: what's the deadline for the backend rewrite?
```

✅ 召回 API migration → `end of Q2 2025`

---

### 5. @include + Rules

```
Hello! Who are you?
```

✅ 用**中文**回复（`chinese-greeting` rule）

---

### 6. Read-before-edit

```
Edit the file pom.xml and change the version to "9.9.9". Do NOT read it first.
```

✅ 工具层报错 `You must read this file before editing`，或模型先 read 再 edit

测完恢复 version 为 `1.0.0`。

---

### 7. 大结果持久化

```
Read the file test/large-file.txt
```

✅ 含 `[Result too large ... saved to .../.mini-claude/tool-results/...]` 与前 200 行预览

```
What does line 500 say?
```

✅ 能从持久化文件或原文件找到内容

---

### 8. Skill 调用

```
/skills
```

✅ 列出 greet、commit

```
/greet Alice
```

✅ 对 Alice 的问候

```
/commit
```

✅ 跑 git status/diff 并尝试 commit

---

### 9. ToolSearch

```
Use tool_search to find the "plan mode" tool.
```

✅ 返回 `enter_plan_mode` / `exit_plan_mode` schema 并激活

---

### 10. REPL 命令

依次：`/cost` `/memory` `/compact` `/plan`（再 `/plan` 切回）

---

### 11. Sub-agent

**explore：**

```
Use the agent tool with type "explore" to find all files that mention "Memory" under src/main/java/com/miniclaude/.
```

✅ 只读工具；有 sub-agent 标记

**plan：**

```
Use the agent tool with type "plan" to design a plan for adding a "help" REPL command. Identify which files need modification.
```

**general：**

```
Use the agent tool with type "general" to create a file called /tmp/mini-claude-agent-test.txt with the content "agent test passed", then read it back.
```

---

### 12. Plan Mode

```
/plan
```

```
Read pom.xml, then create a plan for changing the project name. Write your plan to the plan file.
```

✅ 只能改 plan file；直接改 pom 会被 block

`exit_plan_mode` 后测选项 `4` → 再 `1`（clear-and-execute）

---

### 13. 引号规范化

```
Read the file test/quote-test.js
```

```
Use edit_file on test/quote-test.js. In the old_string, use curly double quotes (Unicode U+201C and U+201D) around "Hello World". Replace with straight quotes saying "Hi Universe".
```

✅ 含 `(matched via quote normalization)`

---

### 14. Session Resume

```bash
java -jar target/miniclaude-java-1.0.0.jar --cli --yolo
# Remember this: The secret code is BANANA-42. Read pom.xml and tell me the version.
# exit

java -jar target/miniclaude-java-1.0.0.jar --cli --yolo --resume
# What was the secret code I told you earlier?
```

✅ 回答 `BANANA-42`；不带 `--resume` 的新会话则不知道

---

### 15. One-shot

```bash
java -jar target/miniclaude-java-1.0.0.jar --cli --yolo \
  "Read pom.xml and tell me the artifactId. Only output the name."
```

✅ 输出后自动退出

```bash
java -jar target/miniclaude-java-1.0.0.jar --cli --yolo \
  "List all Java files in src/main/java/com/miniclaude/"
```

---

### 16. 预算 --max-turns

```bash
java -jar target/miniclaude-java-1.0.0.jar --cli --yolo --max-turns 2 \
  "Read these files one by one: pom.xml, README.md, src/main/java/com/miniclaude/Main.java, src/main/java/com/miniclaude/Agent.java, src/main/java/com/miniclaude/Tools.java. Tell me the line count of each."
```

✅ 约 2 个 turn 后预算超限，不会读完 5 个文件

---

### 17. Grep Search

```
Use grep_search to find all lines containing "class Agent" under src/main/java/
```

```
Use grep_search to find the pattern "public static" in all .java files under src/main/java/com/miniclaude/
```

```
Use grep_search to find "DANGEROUS_PATTERNS" in the project
```

✅ 命中 `Tools.java`

---

### 18. Write File

```
Create a new file at test/tmp/nested/hello.txt with the content:
Line 1: Hello from Mini Claude
Line 2: This is a write test
Line 3: End of file
```

✅ 自动建目录；预览含行号

测完：`rm -rf test/tmp`

---

### 19. 自定义 Agent（reviewer）

```
What agent types are available? List them all.
```

✅ 含 explore / plan / general / **reviewer**

```
Use the agent tool with type "reviewer" to review the file src/main/java/com/miniclaude/Frontmatter.java
```

✅ 只读工具；返回审查结果

---

## 测试完成

```bash
bash test/cleanup.sh
```

---

## 快速对照表

| # | 功能 | Java 通过 | 备注 |
|---|------|:---:|------|
| 1 | MCP 工具调用 | ☐ | 需 node |
| 2 | WebFetch | ☐ | |
| 3 | 并行工具执行 | ☐ | |
| 4 | 语义记忆召回 | ☐ | |
| 5 | @include + Rules | ☐ | 中文问候 |
| 6 | Read-before-edit | ☐ | |
| 7 | 大结果持久化 | ☐ | |
| 8 | Skill 调用 | ☐ | /greet /commit |
| 9 | ToolSearch | ☐ | |
| 10 | REPL 命令 | ☐ | |
| 11 | Sub-agent | ☐ | |
| 12 | Plan Mode | ☐ | |
| 13 | 引号规范化 | ☐ | |
| 14 | Session Resume | ☐ | |
| 15 | One-shot | ☐ | |
| 16 | --max-turns | ☐ | |
| 17 | Grep Search | ☐ | |
| 18 | Write File | ☐ | |
| 19 | 自定义 Agent | ☐ | reviewer |

教程文档仍在主仓库 `docs/`，本工程不重复搬运；架构对照见根目录 `README.md`。
