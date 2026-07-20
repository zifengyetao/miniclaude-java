# 资深 Agent 冲刺进度

> 这是跨会话的唯一进度事实源。每次完成学习、编码、评测或模拟面试后更新本文。
> 新会话先读 [总览](./interview-prep-overview.md)，再读本文，从“下一步”继续。

## 当前状态

- 阶段：Week 1 — Harness 与 Agent Loop。
- 状态：Shared Harness H1 完成、H2 Fake/Shadow 工具已接入；理论学习与口述答辩待进行。
- 最近更新：2026-07-20。
- 简历：按用户要求暂不处理。
- 可投入时间：工作日每天 6 小时，周末默认不安排；每周约 30 小时。
- 当前项目测试基线：`mvn test` 63/63 通过（2026-07-20 Harness H1/H2 改造后）。

## 已完成

- [x] 收集候选人背景和求职方向。
- [x] 完成第一轮 Agent 技术诊断。
- [x] 明确主投 Agent 平台/Runtime 与企业智能体工程化。
- [x] 完成截至 2026-07-20 的岗位样本调研。
- [x] 审查当前项目的面试含金量和架构硬伤。
- [x] 制定八周、约 240 小时的冲刺路线。
- [x] 建立 96 道资深 Agent 面试题库。
- [x] 将准备材料和进度机制落入仓库。

## 诊断记录

### 已确认优势

- 7 年 Java 与大厂资深开发经历。
- 推荐系统和 DAG/工作流工程经验。
- 有数千条 QA 的内部 RAG 实际落地经历。
- 能诚实区分做过、了解和不会，避免虚假包装。

### 已确认缺口

- RAG：Chunk、Rerank 和分层 Eval。
- Durable：Worker、Attempt、Replay、幂等、UNKNOWN 和副作用台账。
- Context：预算、压缩、来源、权限和跨会话 Memory。
- Tool/MCP：协议、授权、租户、审批和审计。
- Eval：Dataset、Runner、Scorer/Judge、回放和发布门禁。
- Runtime：Java 流式、背压、取消和 Trace。
- Coding Harness：Sandbox、Patch/Test/Review/Resume。

## 下一步

### Week 1：Harness 与 Agent Loop

开始前：

- [ ] 阅读 `interview-prep-roadmap.md` 第 1 周。
- [ ] 阅读 Anthropic《Building Effective Agents》。
- [ ] 阅读 ReAct 核心章节。
- [ ] 用自己的话解释 Workflow、Agent、Harness 的边界。

项目任务：

- [x] 为当前项目画出 `Run → Step → Attempt → Observation → Artifact` 数据模型。
- [x] 对照现有 `AgentRun`、Event 和 Checkpoint，列出缺失字段。
- [x] 写 ADR：为何使用 Harness-first，以及哪些决策属于 LLM、哪些必须确定性执行。
- [x] 建立首批 30 条 `data-analyst` 任务集，定义格式和成功判定。
- [x] 实现最小 `GraphRunner`，由 GraphSpec 驱动 Analyst 条件分支与恢复游标。
- [x] 新增共享 `DefaultAgentHarness` 动态 Agent Loop。
- [x] Data/Support/Coding 使用三个 Profile，共享 Context/Model/Tool/Policy/Stop/Event 控制面。
- [x] 增加 Tool 参数/调用顺序 Guard 和确定性完成验证。
- [x] 将现有 ScenarioPorts 注册成安全 Fake Harness Tools。
- [x] Harness 失败只形成 L0 Observation，不允许在线自改或直接发布。
- [x] 增加 ADR-002：Shared Harness 与受控自我升级。

面试训练：

- [ ] 回答题库 1～10。
- [ ] 完成题 9 的 Agent Loop 伪代码。
- [ ] 完成题 10 的统一 Agent Runtime 系统设计。
- [ ] 进行一次 45 分钟模拟面试并记录追问失效点。

### Week 1 完成标准

- [ ] 能在 10 分钟内讲清 DAG、Agent 和 Harness 的对应关系。
- [x] 有一份可评审的数据模型和 ADR。
- [x] 有 30 条版本化任务及确定性判定器。
- [ ] 题 1～10 的平均掌握度至少达到 2。

## 决策记录

### 2026-07-20：岗位方向

- 主投 Agent 平台/Runtime 和企业智能体工程化。
- Java 作为控制面主栈，Python 用于 Eval 和模型生态协作。
- 不把两个月用于模型训练、RL、CUDA 或推理内核。

### 2026-07-20：项目策略

- 当前项目约为 6.5/10 的面试原型。
- 平台核心采用 Shared Harness/Agent Loop，Graph 仅作为审批、监管和固定 Workflow 策略。
- Data/Support/Coding 不复制 Loop，通过 Profile、Tool、Policy 和 Verifier 区分。
- 自我升级限定为 Observation → Candidate → Eval → Review → Shadow/Canary → Promote/Rollback。
- 暂停增加 Graph DSL、UI、多 Agent 角色和无 Eval 的治理状态名。

### 2026-07-20：真实性边界

- RAG 项目不虚构 Rerank、检索指标或生产规模。
- 不把状态持久化描述为完整 Durable Execution。
- 不把 Fake Coding 场景描述为真实 Coding Harness。
- 不把建议验收阈值写成已取得结果。

### 2026-07-20：时间预算

- 工作日每天可投入 6 小时，周末可能无法投入。
- 计划调整为每周 30 小时、八周约 240 小时。
- 模拟面试和周复盘移至周五；周末只作自愿缓冲。
- 额外时间用于 Eval、故障注入和项目深度，不用于扩张场景。

## 每次会话结束时必须更新

```text
日期：
本次目标：
完成内容：
代码/文档证据：
评测结果：
新发现的问题：
题库掌握度变化：
架构决策：
下一步唯一优先事项：
```

## 更新日志

### 2026-07-20

- 完成候选人基线诊断、岗位调研、项目审查和八周路线。
- 创建总览、路线、市场、项目审查、题库和进度文档。
- 将时间预算调整为工作日 6 小时、周末默认不安排。
- 完成第一阶段项目改造：Analyst Graph 真执行、条件分支、Checkpoint 游标、Attempt 和状态哈希。
- 新增 30 条固定 Eval 及确定性 Runner。
- 补齐审批/终态原子提交、Graph/Hash/审批绑定、Report 原子发布与幂等；全量测试 48/48 通过。
- 完成 Shared Harness H1 与 H2 Fake/Shadow Tool：完成验证、参数/顺序 Guard、不可覆盖路由、
  服务端 SQL 上限、Context Workspace 绑定和稳定错误码。
- 全量测试 63/63 通过；两轮 Reviewer 复核无残留 critical/high。
- 下一步唯一优先事项：为 Harness 接入版本化 Model Turn Adapter 与 Shadow Eval，
  再进入持久 Resume、Tool Receipt/Effect Ledger 和 UNKNOWN。

