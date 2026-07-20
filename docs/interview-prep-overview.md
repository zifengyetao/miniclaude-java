# 资深 Agent 岗位冲刺总览

> 本文是跨会话入口。开始面试准备、学习复盘或项目改造前，先读本文，再读
> [进度记录](./interview-prep-progress.md)。

## 1. 候选人基线

- 7 年 Java 开发经验，硕士，具有华为、微博、阿里等大型互联网公司资深开发经历。
- 核心经历：推荐系统工程、分布式后端、DAG/工作流引擎。
- Agent 相关经历：在阿里落地过内部智能答疑 RAG，数据为数千条文本 QA；使用 LangChain 类 Java
  内部框架和内部向量存储，向量侧保存部分元数据，再回源数据库读取文档。
- 当前 RAG 短板：Chunk 使用框架默认能力，未做 Rerank，也没有形成检索层与生成层的系统化评测。
- 当前 Agent 短板：Durable Runtime、工具副作用语义、Context Engineering、Eval、MCP、
  Multi-Agent、Java 流式 Runtime、Coding Agent Harness。
- 技术偏好：Java 优先，但可使用 Python；求职方向接受 Agent 应用开发、Agent 平台和架构岗位。

## 2. 当前定位

当前不是“资深 Agent 工程师”，而是：

> 资深 Java/DAG/分布式工程师，具有真实 RAG 落地经历，正在补齐 Agent 特有工程体系。

目标定位：

1. 主投：Agent 平台 / Runtime / 企业智能体工程化。
2. 兼投：企业 Agent 应用、AI-First Java 后端。
3. 冲刺：Coding Agent Harness / Agent Eval 平台。
4. 暂不主投：模型训练、SFT/DPO/RLHF、CUDA、推理内核和纯算法研究岗。

推荐对外表述：

> 7 年大型互联网 Java/分布式系统经验，具备推荐系统、DAG 编排与生产 RAG 落地经历；
> 聚焦企业 Agent Runtime、工具治理、上下文工程与评测闭环，使用 Java/Spring 构建确定性控制面，
> 使用 Python 完成评测、数据实验和模型侧集成。

## 3. 能力诊断

| 能力域 | 当前判断 | 两个月目标 |
|--------|----------|------------|
| Java / 分布式工程 | 强 | 转化为 Agent 控制面、并发与可靠性故事 |
| DAG / 工作流 | 强 | 掌握 Durable Agent、暂停恢复、节点重放和版本语义 |
| RAG | 有真实项目但深度不足 | 独立完成 Chunk/Hybrid/Rerank/Eval 对照实验 |
| Agent 架构 | 初级 | 能设计 Harness-first 的生产级 Agent 平台 |
| Tool/MCP | 初级 | 掌握幂等、UNKNOWN、审批、权限、审计和 MCP Gateway |
| Context/Memory | 初级 | 完成可解释、可预算、可压缩、带来源与权限的 Context Pipeline |
| Eval/可观测 | 初级 | 完成数据集、回放、Scorer/Judge、回归门禁和失败归因 |
| Coding Harness | 初级 | 完成受限工作区、Patch/Test/Review/Resume 最小闭环 |

## 4. 核心学习模型

Agent 不是“LLM + 工具调用”，而是：

```text
确定性 Harness
  ├── Run/Step/Attempt 状态
  ├── 权限、审批、预算、超时、取消
  ├── Tool 契约、幂等、副作用台账
  ├── Context 装配、压缩、来源与权限
  ├── Eval、Trace、回放、发布门禁
  └── 概率模型负责语义理解、规划和局部决策
```

从既有经验迁移：

```text
DAG 编排       → 概率节点编排与 Durable Agent
分布式任务      → Run/Step/Attempt、租约、重试和恢复
RPC 接口        → 可审计、可授权、可重放的 Tool 契约
推荐召回与排序  → Context Selection、RAG 与模型路由
监控与实验      → Agent Eval、Trace 回放和发布门禁
```

## 5. 面试项目策略

当前仓库约能支撑“Java 企业 Agent 控制面原型”的资深故事，但不能称为生产级 Agent Runtime。

项目改造总原则：

- 不继续堆业务场景、UI、多 Agent 角色和治理状态名。
- 收缩到 `data-analyst` 一条黄金链路。
- 优先证明 Graph 真执行、进程崩溃恢复、工具事务语义和真实 Eval。
- 所有外部写操作保持 Fake，并经过审批、审计和 kill-switch。
- 简历只写实际运行得到的指标，不把建议阈值或两个月原型包装成生产数据。

项目细节见：

- [项目审查与改造优先级](./interview-prep-project-review.md)
- [八周学习路线](./interview-prep-roadmap.md)
- [资深 Agent 面试题库](./interview-prep-question-bank.md)
- [学习与项目进度](./interview-prep-progress.md)

## 6. 工作方式

每次学习或项目改造必须至少产生一种可验证证据：

- ADR 或系统设计图；
- 自动化测试或故障注入；
- 固定数据集与评测报告；
- 压测、成本或延迟结果；
- 失败样本分类与复盘；
- 模拟面试记录。

如果时间不足，缩小功能面，不删除 Eval、故障注入和架构答辩。

