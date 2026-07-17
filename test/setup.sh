#!/bin/bash
# 测试环境一键配置（Java 版）
# 用法: cd miniclaude-java && bash test/setup.sh

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== Mini Claude Java 测试环境配置 ==="

# 1. MCP 测试服务器配置
cat > .mcp.json << 'EOF'
{
  "mcpServers": {
    "test": {
      "command": "node",
      "args": ["test/mcp-server.cjs"]
    }
  }
}
EOF
echo "✓ 创建 .mcp.json (MCP 测试服务器，需要本机有 node)"

# 2. 同步 test/skills → .claude/skills
mkdir -p .claude/skills
cp -R test/skills/* .claude/skills/
echo "✓ 安装测试 skills (greet, commit)"

# 3. CLAUDE.md + rules（含 @include）
mkdir -p .claude/rules
echo "When the user greets you, respond in Chinese (中文)." > .claude/rules/chinese-greeting.md
cat > CLAUDE.md << 'EOF'
# Test Project Rules

@./.claude/rules/chinese-greeting.md

This is a test project for mini-claude-java feature validation.
EOF
echo "✓ 创建 CLAUDE.md (含 @include) 和 rules"

# 4. 大文件（大结果持久化）
python3 -c "
lines = [f'Line {i}: Test data for persistence validation - padding text here.' for i in range(1000)]
open('test/large-file.txt', 'w').write(chr(10).join(lines))
"
echo "✓ 创建 test/large-file.txt (约 75KB)"

# 5. 引号规范化测试文件
cat > test/quote-test.js << 'EOF'
const greeting = "Hello World";
const name = 'Alice';
EOF
echo "✓ 创建 test/quote-test.js"

# 6. 自定义 agent
mkdir -p .claude/agents
cat > .claude/agents/reviewer.md << 'EOF'
---
name: reviewer
description: Code review specialist — analyzes code quality and suggests improvements
allowed-tools: read_file,list_files,grep_search
---
You are a code review specialist. Analyze the given code for:
1. Code quality issues
2. Potential bugs
3. Style inconsistencies
4. Missing error handling

Be concise. Only report actual issues, not stylistic preferences.
Return a structured review with severity levels: [critical], [warning], [info].
EOF
echo "✓ 创建 .claude/agents/reviewer.md"

# 7. 构建 jar
if command -v mvn >/dev/null 2>&1; then
  mvn -q -DskipTests package
  echo "✓ mvn package 完成 → target/miniclaude-java-1.0.0.jar"
else
  echo "⚠ 未找到 mvn，请先手动: mvn -q package"
fi

# 8. 检查 API Key
if [ -f .env ]; then
  echo "✓ .env 已存在"
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

if [ -n "${ANTHROPIC_API_KEY:-}" ] || [ -n "${OPENAI_API_KEY:-}" ]; then
  echo "✓ 检测到 API Key 环境变量"
else
  echo "⚠ 未检测到 API Key。可复制 .env.example → .env 后填写，或 export："
  echo "  ANTHROPIC_API_KEY=...   (+ 可选 ANTHROPIC_BASE_URL)"
  echo "  或 OPENAI_API_KEY=... + OPENAI_BASE_URL=..."
fi

echo ""
echo "=== 配置完成 ==="
echo "启动 HTTP:  ./run.sh"
echo "或 CLI:     ./run.sh --cli --yolo"
echo "清理:  bash test/cleanup.sh"
