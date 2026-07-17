#!/bin/bash
# 清理测试产生的临时文件（保留已提交的 .claude 配置骨架）
# 用法: cd miniclaude-java && bash test/cleanup.sh

set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "=== 清理测试环境 ==="

rm -f test/quote-test.js
rm -rf test/tmp
rm -rf ~/.mini-claude/projects/*/memory/*
rm -rf ~/.mini-claude/tool-results/
rm -f /tmp/mini-claude-agent-test.txt

# 不删除已纳入仓库的 .claude / CLAUDE.md / .mcp.json
# 若需要恢复到 setup 前的测试态，可重新运行 bash test/setup.sh

echo "✓ 清理完成（临时文件 / 记忆 / tool-results）"
