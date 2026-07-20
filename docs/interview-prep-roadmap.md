# 八周资深 Agent 冲刺路线

总预算按每周 30 小时设计：工作日每天 6 小时，周末默认不安排，共约 240 小时。

## 固定周节奏

- 周一 6h：官方文档/论文 3h，理论整理与阅读卡 2h，口述复盘 1h。
- 周二 6h：ADR/实验设计 2h，核心编码 4h。
- 周三 6h：核心编码与测试。
- 周四 6h：集成、Eval、压测或故障注入。
- 周五 6h：模拟面试 2h，题库 2h，复盘和进度更新 2h。
- 周末：默认不安排；只作为自愿缓冲，不计入计划交付。

每篇资料只回答四件事：解决什么问题、依赖什么假设、适用边界是什么、如何映射到当前项目。

新增时间用于加深测试、评测、故障注入和答辩，不扩大业务场景或框架数量。

## 第 1 周：Harness 与 Agent Loop

### 目标

将 DAG/分布式经验迁移为 Agent 控制面语言，理解 Agent 是确定性 Harness 包裹的概率执行节点。

### 学习

- Workflow、Agent、Harness 的边界。
- ReAct 与显式状态机的对应关系。
- Run、Step、Attempt、Artifact、Observation 数据模型。
- 预算、终止、超时、审批和失败分类。

### 作业

- 实现显式 Java Agent Loop，不依赖高级框架隐藏循环。
- 接入检索、计算和工单草稿三个 Fake 工具。
- 每一步写结构化事件，支持离线轨迹回放。
- 建立 30 条任务的初始 Eval 集。
- 输出 ADR：为什么确定性 Harness 必须包住概率模型。

### 验收

- 轨迹完整率 100%。
- 死循环与重复调用能被预算或终止规则拦截。
- 未经审批的 Fake 写操作执行次数为 0。
- 任一失败能归类到模型、工具、策略、数据或基础设施。

## 第 2 周：RAG 检索与评测

### 目标

把“做过 RAG”升级为能独立定位 Chunk、召回、排序和生成问题。

### 学习

- Chunk 边界、长度、重叠和语义完整性。
- BM25、Dense、Hybrid、Cross-encoder Rerank。
- Recall@K、MRR、nDCG@K、Context Precision/Recall。
- 可回答、不可回答、证据冲突与引用。

### 作业

- 使用公开数据或匿名化数据构造至少 200 条测试集。
- 比较三种 Chunk、BM25/Dense/Hybrid、是否 Rerank。
- 保存 Query、候选 Chunk、分数、最终引用和失败标签。
- 对最差 20 条样本做根因分析。

### 验收

- 完成至少 `3 × 3 × 2` 组可复现实验。
- 相对 Baseline，Recall@10 或 nDCG@10 提升至少 10%。
- 引用证据正确率至少 90%，不可回答拒答率至少 85%。
- 同时报告效果、p95 延迟和 Token/成本。

## 第 3 周：Context Engineering 与 Memory

### 目标

把“把更多内容塞入 Prompt”升级为有预算、来源、权限和时效的 Context Pipeline。

### 学习

- Working、Episodic、Semantic Memory 的边界。
- Just-in-time Retrieval、Compaction、Structured Memory。
- Lost-in-the-middle、上下文污染和过期记忆。
- 指令、事实、工具结果和用户输入的信任分层。

### 作业

- 实现 `ContextAssembler`，每个条目包含来源、时间、权限、Token 成本和置信度。
- 实现选择、去重、排序、压缩和丢弃原因记录。
- 长任务结束时生成结构化 Checkpoint。
- 注入 30 条冲突、过期、越权和恶意上下文。

### 验收

- 平均输入 Token 降低至少 35%。
- 任务成功率下降不超过 2 个百分点。
- 过期或越权记忆进入最终上下文次数为 0。
- 跨会话恢复后关键任务状态保持率至少 95%。

## 第 4 周：Durable Agent 与工具事务

### 目标

掌握长时间 Agent Run 的恢复、幂等、不确定结果和副作用治理。

### 学习

- Event History、Deterministic Replay、Workflow/Activity 边界。
- At-least-once、At-most-once 与业务 Exactly-once Effect。
- 超时不等于失败：`UNKNOWN` 状态。
- 幂等键、Effect Ledger、查询确认、补偿和人工介入。
- Lease、Heartbeat、Backoff/Jitter、Cancellation、版本升级。

### 作业

- 为 Worker 增加 Lease、Heartbeat 和任务接管。
- 定义 `ToolExecutionEnvelope`：Effect Class、Idempotency Key、Deadline、Retry、Approval Binding、Receipt。
- 注入进程崩溃、响应丢失、工具超时和重复投递。
- 实现 `PENDING/SUCCEEDED/FAILED/UNKNOWN/COMPENSATING`。
- 增加审批等待、恢复、取消和 Kill-switch。

### 验收

- 1000 次重复投递测试，重复外部副作用为 0。
- 任意步骤崩溃后可从节点游标和持久状态恢复。
- `UNKNOWN` 不被错误映射成 `FAILED`，未确认写操作不盲目重试。
- Kill-switch 生效时间不超过 1 秒。

## 第 5 周：MCP 与工具平台安全

### 目标

把框架私有 Function Calling 升级为有协议、权限和治理边界的平台工具能力。

### 学习

- MCP Host、Client、Server 和能力协商。
- Tools、Resources、Prompts，stdio 与 Streamable HTTP。
- MCP 与 Function Calling、REST/RPC 的区别。
- 用户授权、最小权限、租户隔离、审计和 Schema 演进。
- MCP 是连接协议，不是认证或安全边界。

### 作业

- 实现 Java MCP Server：只读知识资源、检索工具、Fake 写工具。
- 实现 Java MCP Client，接入统一 Tool Gateway。
- 贯通 Tenant、Subject、Trace、Run、Approval 和 Idempotency Key。
- 增加 Allowlist、参数校验、输出大小限制及注入测试。

### 验收

- 跨租户和未授权调用拦截率 100%。
- 写操作均可关联用户、Run、审批和幂等键。
- 超大工具结果不会直接灌入上下文。
- 连接中断不会导致隐式重复写。

## 第 6 周：Multi-Agent 取舍

### 目标

掌握常见协作模式，但坚持默认单 Agent，只有评测证明收益后才拆分。

### 学习

- Supervisor、Handoff、Parallel Fan-out、Blackboard。
- Agent 间消息契约、共享状态 Owner 和权限。
- 错误放大、上下文复制、成本和延迟。
- 独立 Reviewer 与模型自我反思的差异。

### 作业

在相同测试集上对比：

1. 单 Agent；
2. Planner + Executor；
3. Planner + Executors + Reviewer。

记录成功率、Token、成本、p95、工具调用数、错误相关性和 Reviewer 误杀/漏检。

### 验收

- 至少 50 条相同任务对照实验。
- 只有成功率提高至少 8 个百分点或风险显著下降时才接受 Multi-Agent。
- 子 Agent 受全局预算和权限约束，不能无限繁殖。
- 无收益时明确结论为“不上线”。

## 第 7 周：Java 流式 Agent Runtime

### 目标

从模型 Token 流升级到包含步骤、工具、审批、工件、错误和终态的 Agent 事件流。

### 学习

- SSE、WebSocket、Reactive Streams。
- Token Stream 与 State/Event Stream 的区别。
- 背压、慢消费者、取消传播和资源释放。
- Run → Step → Model/Tool Attempt 的 Trace 模型。
- 断线续传、事件序号、去重和成本归因。

### 作业

- 实现 `Flux<AgentEvent>`。
- 事件覆盖 Token、Step、Tool、Approval、Artifact、Error、Done。
- SSE 支持 Event ID 与断线续传。
- 取消传播到模型、工具和 Workflow。
- 接入 Trace 和结构化日志，完成 100 并发与慢消费者测试。

### 验收

- Runtime 自身首事件 p95 开销不超过 100ms。
- 100 并发无 Terminal Event 丢失。
- 取消传播不超过 1 秒。
- 慢消费者不会导致无限内存增长。
- 不暴露模型内部思维链。

## 第 8 周：Coding Agent Harness 与答辩

### 目标

把前七周能力收敛成可隔离、可验证、可恢复的 Coding Agent 作品。

### 学习

- Repo、Filesystem、Shell、Patch、Test、Browser/Logs 工具边界。
- Worktree/Container Sandbox。
- 长任务分段、Progress Artifact 和 Checkpoint。
- SWE-bench 的任务设计与评测思想。
- 秘密泄露、命令注入、网络和资源隔离。

### 作业

- 每个任务使用独立 Worktree 或受限容器。
- 只允许白名单命令和目录。
- 支持检索代码、修改、测试、生成 Diff 和失败恢复。
- 建立 10 个真实 Java 缺陷任务的小型 Benchmark。
- 输出架构图、SLO、容量估算、威胁模型和演进路线。

### 验收

- 10 个任务至少完成 8 个，并通过隐藏回归检查。
- 任务间文件、进程和凭证隔离。
- 越权目录写入和危险命令拦截率 100%。
- 45 分钟内完成一次端到端架构答辩。

## 必读一手资料

### Agent 与 Harness

- [ReAct](https://iclr.cc/virtual/2023/oral/12647)
- [Anthropic: Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents)
- [Anthropic: Effective Harnesses for Long-Running Agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- [OpenAI: Harness Engineering](https://openai.com/index/harness-engineering/)

### RAG 与 Context

- [RAG 原始论文](https://proceedings.neurips.cc/paper_files/paper/2020/hash/6b493230205f780e1bc26945df7481e5-Abstract.html)
- [BEIR](https://arxiv.org/abs/2104.08663)
- [RAGAS](https://aclanthology.org/2024.eacl-demo.16/)
- [Lost in the Middle](https://aclanthology.org/2024.tacl-1.9/)
- [Anthropic: Context Engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)

### Durable 与工具语义

- [Temporal Workflows](https://docs.temporal.io/workflows)
- [Temporal Workflow Determinism](https://docs.temporal.io/workflow-definition)
- [AWS: Making Retries Safe with Idempotent APIs](https://aws.amazon.com/builders-library/making-retries-safe-with-idempotent-APIs/)
- [AWS: Timeouts, Retries and Backoff with Jitter](https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/)

### MCP、Eval 与 Coding Agent

- [MCP 2025-11-25 正式规范](https://modelcontextprotocol.io/specification/2025-11-25)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [Anthropic: Demystifying Evals for AI Agents](https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents)
- [SWE-bench](https://github.com/SWE-bench/SWE-bench)

## 明确不投入的内容

- Transformer/Attention 数学从零推导。
- Python 入门课和 Prompt 技巧合集。
- 同时学习 LangChain、LangGraph、CrewAI、AutoGen 等多个框架 API。
- 从零训练 Embedding、Reranker 或大模型。
- 无 Eval 支撑的复杂 Multi-Agent Demo。
- SFT/DPO/RLHF、CUDA、推理内核和大规模训练。
- 继续堆业务场景、UI 大屏或“自进化”状态名。

