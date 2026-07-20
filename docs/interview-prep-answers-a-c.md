# 资深 Agent 面试参考答案（A～C）

> 本文是题 1～30 的标准参考答案，用于建立资深 Agent 平台 / Runtime 面试的答题骨架，并非唯一答案。面试时应根据追问取舍展开，不建议逐字背诵。涉及当前仓库时严格按现状表述：它是 Java 企业 Agent 控制面原型；Legacy Chat 状态仍在 JVM；Shared Harness 处于 H1/H2 Fake/Shadow 阶段；Data Analyst 的 Graph/JDBC durable 语义相对完整；外部适配器均为 Fake。

## 目录

- [A. Agent 与 Harness 基础（1～10）](#a-agent-与-harness-基础1-10)
  - [1. Agent Loop 与普通 LLM API 调用的本质区别是什么？](#1-agent-loop-与普通-llm-api-调用的本质区别是什么)
  - [2. Workflow、Agent、Harness 三者分别负责什么，边界如何确定？](#2-workflowagentharness-三者分别负责什么边界如何确定)
  - [3. ReAct、Plan-and-Execute、Reflection 分别解决什么问题，有什么失败模式？](#3-reactplan-and-executereflection-分别解决什么问题有什么失败模式)
  - [4. 如何定义 Run、Step、Attempt、Observation、Artifact 和 Final Result？](#4-如何定义-runstepattemptobservationartifact-和-final-result)
  - [5. Agent 的终止条件如何设计，怎样区分完成、失败、暂停和预算耗尽？](#5-agent-的终止条件如何设计怎样区分完成失败暂停和预算耗尽)
  - [6. 为什么 Coding Agent 可以自主规划，但仍必须被确定性 Harness 包住？](#6-为什么-coding-agent-可以自主规划但仍必须被确定性-harness-包住)
  - [7. 哪些任务不应该使用 Agent，而应该用普通代码、规则或 DAG？](#7-反例哪些任务不应该使用-agent而应该用普通代码规则或-dag)
  - [8. Agent 重复调用同一工具并陷入循环，如何诊断和阻断？](#8-故障agent-重复调用同一工具并陷入循环如何诊断和阻断)
  - [9. 写一个支持预算、取消和 Tool Calling 的最小 Agent Loop。](#9-伪代码写一个支持预算取消和-tool-calling-的最小-agent-loop)
  - [10. 设计一个支持 Chat、长任务和人工审批的统一 Agent Runtime。](#10-系统设计设计一个支持-chat长任务和人工审批的统一-agent-runtime)
- [B. RAG 与检索（11～22）](#b-rag-与检索11-22)
  - [11. Chunk Size、Overlap 和文档结构分别如何影响召回与生成？](#11-chunk-sizeoverlap-和文档结构分别如何影响召回与生成)
  - [12. Fixed-size、Recursive、Semantic、Parent-child Chunk 各适合什么数据？](#12-fixed-sizerecursivesemanticparent-child-chunk-各适合什么数据)
  - [13. BM25、Dense Retrieval 和 Hybrid Search 的优缺点是什么？](#13-bm25dense-retrieval-和-hybrid-search-的优缺点是什么)
  - [14. Reranker 解决什么问题，为什么不能替代召回？](#14-reranker-解决什么问题为什么不能替代召回)
  - [15. Recall@K、MRR、nDCG@K 分别衡量什么？](#15-recallkmrrndcgk-分别衡量什么)
  - [16. 如何把 RAG 错误分为索引、召回、排序、上下文装配和生成错误？](#16-如何把-rag-错误分为索引召回排序上下文装配和生成错误)
  - [17. Top-K 没有正确文档，如何逐层排查？](#17-故障top-k-没有正确文档如何逐层排查)
  - [18. 正确文档已经召回，模型仍然回答错误，如何定位？](#18-故障正确文档已经召回模型仍然回答错误如何定位)
  - [19. 如何处理不可回答问题、冲突证据和过期知识？](#19-如何处理不可回答问题冲突证据和过期知识)
  - [20. 如何实现文档 ACL、租户隔离、增量索引和删除传播？](#20-如何实现文档-acl租户隔离增量索引和删除传播)
  - [21. 如何证明增加 Reranker 值得额外的延迟和成本？](#21-如何证明增加-reranker-值得额外的延迟和成本)
  - [22. 设计一个百万级企业知识库 RAG，覆盖评测、引用和权限。](#22-系统设计设计一个百万级企业知识库-rag覆盖评测引用和权限)
- [C. Context Engineering 与 Memory（23～30）](#c-context-engineering-与-memory23-30)
  - [23. Prompt Engineering 与 Context Engineering 的区别是什么？](#23-prompt-engineering-与-context-engineering-的区别是什么)
  - [24. Working、Episodic、Semantic Memory 如何划分？](#24-workingepisodicsemantic-memory-如何划分)
  - [25. 长上下文、检索和摘要分别适合什么场景？](#25-长上下文检索和摘要分别适合什么场景)
  - [26. 如何给 System Instruction、用户输入、检索文档和 Tool Result 划分信任等级？](#26-如何给-system-instruction用户输入检索文档和-tool-result-划分信任等级)
  - [27. Context Item 应记录哪些元数据，如何决定纳入或丢弃？](#27-context-item-应记录哪些元数据如何决定纳入或丢弃)
  - [28. Coding Agent 工作两小时后上下文溢出，如何压缩且不丢失任务状态？](#28-故障coding-agent-工作两小时后上下文溢出如何压缩且不丢失任务状态)
  - [29. 旧记忆污染当前任务并触发错误工具调用，如何防止和修复？](#29-故障旧记忆污染当前任务并触发错误工具调用如何防止和修复)
  - [30. 设计一个支持跨会话恢复、权限和 Token Budget 的 Context Pipeline。](#30-系统设计设计一个支持跨会话恢复权限和-token-budget-的-context-pipeline)

## A. Agent 与 Harness 基础（1～10）

### 1. Agent Loop 与普通 LLM API 调用的本质区别是什么？

普通 LLM 调用是一次有界的“输入→生成”，调用方预先决定流程；Agent Loop 则让模型依据 Observation 在多轮中选择下一动作、参数和停止时机，因此执行路径是运行时涌现的。核心不变量不是“模型足够聪明”，而是每一轮都受工具权限、预算、超时、取消、状态机和完成验证约束，外部副作用不能由文本直接触发。

它适合需要探索和反馈修正的开放任务，不适合确定性计算或固定审批链。失败包括循环、幻觉工具、预算失控和部分副作用，需靠 Step/Attempt、幂等键、策略 Gate、Checkpoint 和人工暂停恢复。指标至少看任务成功率、平均轮数、无进展率、工具错误率、p95 延迟与单任务成本。可把既有 DAG 经验迁移为控制面，但要承认路径不再完全预知。

### 2. Workflow、Agent、Harness 三者分别负责什么，边界如何确定？

Workflow 用显式节点和转移承载可预测流程，适合审批、监管、补偿和固定发布链；Agent 让模型处理语义理解、动态规划及局部决策；Harness 是包住二者的确定性运行时，统一负责 Context、Tool、Policy、Budget、Trace、Eval、暂停恢复和终态验证。边界原则是：需要审计复现或涉及权限、副作用的决策归 Harness/Workflow；难以穷举、可通过反馈修正的认知决策交给 Agent。

反例是把 Coding 探索硬塞进复杂 Graph，最终 Graph 退化为编程语言；另一反例是让模型决定是否绕过审批。当前项目正采用这种划分：Shared Harness H1/H2 尚在 Fake/Shadow，GraphRunner 只在 Data Analyst 等显式链路更成熟。指标看路径变异度、人工介入率、策略拒绝率、恢复成功率和各层失败归因。

### 3. ReAct、Plan-and-Execute、Reflection 分别解决什么问题，有什么失败模式？

ReAct 交替“决策—工具—观察”，适合信息逐步显露的任务，但容易短视、重复调用和被恶意 Observation 带偏。Plan-and-Execute 先拆解再执行，适合长任务和可并行子目标；缺点是初始计划基于不完整信息，若缺少重规划会僵化，若频繁重规划又增加成本。Reflection 用结果或评审反馈做二次修正，能提高复杂产物质量，但自评与生成同源，可能自信地强化同一错误。

三者不是互斥框架：可用粗粒度计划、ReAct 执行、独立 Verifier 触发有限反思。硬约束是反思不能修改权限和安全规则，重试必须有新证据及上限。失败后保存轨迹、定位首次偏航点，再回到最近有效状态。指标包括计划完成率、重规划率、重复动作率、Reflection 净提升、额外 Token/延迟。候选人的 DAG 经验适合讲计划依赖，但不能把概率执行等同确定性 DAG。

### 4. 如何定义 Run、Step、Attempt、Observation、Artifact 和 Final Result？

Run 是一次用户目标的持久执行实例，绑定 tenant、user、版本、预算和终态；Step 是有业务语义的逻辑动作；Attempt 是 Step 的一次物理执行，携带 worker、序号、deadline 和错误，重试只新增 Attempt，不覆盖历史。Observation 是模型可消费的受控结果或失败事实；Artifact 是可独立引用、下载和审计的持久产物；Final Result 是 Run 的终态声明及对 Artifact 的引用，不应只是最后一段模型文本。

不变量包括 ID 稳定、状态单向合法迁移、输入输出 Hash 可核验、Artifact 发布与成功终态保持原子语义。崩溃后从已提交边界恢复；副作用不确定时进入 UNKNOWN 而非盲重试。指标看 Step/Attempt 比、重试成功率、重复 Artifact 数、恢复点新鲜度和终态不一致数。当前 Data Analyst 已有节点游标、attempt、Hash 和原子报告发布，但尚非完整 Worker Runtime。

### 5. Agent 的终止条件如何设计，怎样区分完成、失败、暂停和预算耗尽？

终止必须由模型信号与确定性 Stop Controller 共同决定。模型说“完成”只是候选信号，Harness 还要验证必要工具、引用、产物和安全条件。`SUCCEEDED` 表示可验证目标已满足；`FAILED` 表示不可恢复错误或重试耗尽；`WAITING_APPROVAL/PAUSED` 表示保存了可恢复游标并等待外部事件；`CANCELLED` 是用户或策略主动终止；`BUDGET_EXHAUSTED` 应作为独立原因记录，即使外部状态可映射为失败。

轮数、Token、成本、墙钟和工具次数都要设软硬阈值；接近软阈值时压缩上下文或要求收敛，触达硬阈值立即停止新动作。暂停前必须持久化版本、上下文摘要及待审批动作，恢复时重验权限和时效。指标包括各终态占比、假完成率、预算耗尽率、暂停时长、恢复成功率及取消传播延迟。

### 6. 为什么 Coding Agent 可以自主规划，但仍必须被确定性 Harness 包住？

Coding 任务的搜索路径、故障假设和修改方案很难预先画成 DAG，因此应允许模型动态规划；但文件系统、Shell、Git、网络和密钥都是真实能力，模型会受 Prompt Injection、错误推理和测试投机影响。Harness 必须固定工作区边界、命令 Allowlist、网络策略、预算、取消、Patch Apply、测试与独立 Review，且禁止写 main/master、force push、跳过 Hook 和生产部署。

恢复时保存计划、已读证据、Patch、测试结果和 repo revision；重复执行命令要区分只读与副作用，写操作需幂等或人工确认。完成条件不能只是“模型认为修好了”，而是变更范围合理、目标测试及回归通过、测试未被削弱、无秘密泄漏。指标看 Patch 接受率、Pass@1、回归率、危险命令拦截率、人工介入率和每任务成本。当前仓库 Coding 工具仍是 Fake，不能宣称已具备真实 Harness 完成率。

### 7. **[反例]** 哪些任务不应该使用 Agent，而应该用普通代码、规则或 DAG？

输入输出明确、算法已知、错误代价高且必须可证明的任务，优先普通代码；例如税费计算、权限判定、SQL 只读校验。步骤固定但需要暂停、审批、补偿和审计的任务，优先 DAG/Workflow；例如四眼审批和发布流程。高频低延迟、数据极少、规则稳定的请求也不应承担模型延迟与不确定性。若传统方案已达到目标，Agent 只会扩大故障面。

判断可用四个问题：路径是否真的不可穷举、语义判断是否必要、错误能否验证恢复、收益是否覆盖成本风险。反例是让 Agent 自主决定交易风控硬限额，或用多轮模型做字符串格式转换。指标应比较基线的成功率、p95、成本、可解释性和事故半径，而非只看 Demo 效果。候选人的推荐/DAG 背景可自然说明：模型负责候选理解，规则与工作流守住不变量。

### 8. **[故障]** Agent 重复调用同一工具并陷入循环，如何诊断和阻断？

先按 `run→turn→tool call→observation` 重建轨迹，确认是模型没看到结果、结果不可解析、工具返回瞬态错误、Context 压缩丢失状态，还是完成条件永远不可达。比较归一化后的工具名、参数 Hash、Observation Hash 和状态版本；若参数与结果连续相同且没有状态增量，可判定无进展循环，而不能仅按调用次数猜测。

即时阻断用最大轮数/工具数、相同调用去重、无进展计数器和熔断；写工具还须查幂等键与 Effect Ledger，结果 UNKNOWN 时禁止盲重试。修复可增加结构化错误码、把关键 Observation 固定进上下文、明确完成 Verifier，必要时暂停人工。恢复从最后有效 Checkpoint 继续，不重放已确认副作用。指标看重复调用率、循环终止时延、误杀率、每工具错误分布及预算浪费。

### 9. **[伪代码]** 写一个支持预算、取消和 Tool Calling 的最小 Agent Loop。

核心顺序必须固定：每轮先检查取消与硬预算，再装配上下文并调用模型；模型返回终稿时先做确定性验证，返回工具调用时逐个经过 Schema、Allowlist、Policy、预算与副作用检查，最后追加结构化 Observation。异常不能吞掉，写工具超时要进入 UNKNOWN。伪代码可口述为：

```text
while state.running:
  guardNotCancelled(run); budget.guard(state)
  ctx = context.assemble(run, state)
  turn = model.complete(ctx, deadline)
  account(turn.usage); emit(MODEL_COMPLETED)
  if turn.final:
    return verifier.pass(turn, state) ? succeed(turn) : continueWithFeedback()
  for call in turn.toolCalls:
    validateSchemaAndProfile(call); decision = policy.check(call, run)
    if decision == APPROVAL: return checkpointAndPause(call)
    if decision == DENY: appendObservation(call, DENIED); continue
    result = tools.execute(call, idempotencyKey(run, call), deadline)
    appendObservation(call, normalize(result)); account(result)
fail(LIMIT_OR_ERROR)
```

Checkpoint 至少落在暂停、已确认副作用和终态边界；取消需传播到模型与工具。指标包括取消延迟、预算超限数、策略绕过数、UNKNOWN 数、平均轮数和终态验证失败率。

### 10. **[系统设计]** 设计一个支持 Chat、长任务和人工审批的统一 Agent Runtime。

组件上分为 API/SSE Gateway、Run Service、Scheduler/Worker、共享 Harness、Model Gateway、Tool Gateway、Policy/Approval Service、Context/Memory Service、Artifact Store、JDBC/Event Store 与 Trace/Eval。Chat 和长任务共用 Run 语义：Chat 可同步等待短时结果并流式返回，超过阈值转异步；长任务由 Worker 领取；审批由 Harness 在工具执行前持久暂停，回调只提交审批事实，恢复时重新校验版本、权限、参数 Hash 和时效。

主链路是 `create Run→pin profile/model/tool version→assemble context→model turn→policy→tool→observation→checkpoint→verify→terminal`。数据模型至少有 Run、Step、Attempt、Event、Checkpoint、Approval、ToolReceipt/Effect、Artifact，主键携带 tenant，所有外部写绑定 caller-generated idempotency key。状态机单向迁移，终态和 Artifact 发布原子提交，事件通过 outbox 推送；不保存模型思维链。

故障恢复依赖 Worker lease、heartbeat、fencing token 和可接管队列。模型调用可安全重试但需记录 Attempt；写工具超时进入 UNKNOWN，先查询确认，再决定重试、补偿或人工。审批重复回调幂等，SSE 用 event id 续传，取消传播到 Worker、模型和工具。版本升级时运行中 Run 固定旧版本，新 Run 才使用新版本。

容量上将长连接与执行 Worker 解耦，按租户限流；估算并发 Run、平均轮数、模型 QPS、工具 QPS、事件写放大和 Artifact 容量，队列按优先级与 deadline 调度。核心指标为任务成功率、排队/p95 端到端延迟、每 Run Token/成本、恢复成功率、审批等待时长、重复副作用数和 SSE 重连丢失数。映射当前项目只能说 Data Analyst 已有较完整 Graph/JDBC 基线，Legacy Chat 仍在 JVM，Shared Harness 尚未成为生产统一 Runtime。

## B. RAG 与检索（11～22）

### 11. Chunk Size、Overlap 和文档结构分别如何影响召回与生成？

Chunk 太小会切断定义、条件和指代，虽然命中更精确，却缺少生成所需语境；太大会稀释关键词/向量表示，占用 Token 并把无关内容带给模型。Overlap 能缓解跨边界信息丢失，但会增加索引、召回重复和排序偏置。比固定字符数更重要的是尊重标题、段落、表格、代码块和版本边界，并保留 parent、section、offset 等元数据。

没有通用最优值，应按问题类型做 Chunk 参数矩阵，对同一文档保持可追溯版本。失败时区分“相关片段未入索引”和“命中片段但上下文不完整”，必要时命中 child 后扩展 parent。指标看 Recall@K、上下文精确率、重复率、平均 Token、引用完整性和答案正确率。候选人已有数千 QA RAG 经历，但使用默认 Chunk，应诚实表述为可进一步补做对照实验，而非声称已有最优参数。

### 12. Fixed-size、Recursive、Semantic、Parent-child Chunk 各适合什么数据？

Fixed-size 简单、吞吐稳定，适合结构弱、长度均匀的日志或短文本，但容易切断语义。Recursive 按标题、段落、句子逐级切分，适合 Markdown、手册等半结构文档，是常见工程默认。Semantic 依据主题变化切分，适合长叙事或多主题内容，但依赖模型、成本高且版本稳定性较差。Parent-child 用小块召回、大块供生成，适合法规、产品手册和代码文档，但会提高存储及上下文装配复杂度。

选择要由数据结构和查询粒度驱动，而非追逐算法名；表格、代码和 FAQ 还应使用专门解析器。索引失败需保留原文 offset 和 parser version，支持按版本重建及回滚。指标比较不同策略的 Recall@K、引用跨度、重复 Token、索引耗时、存储放大和端到端成功率。已有 QA 数据可把“一问一答”视作天然结构单元，再与递归切分做基线实验。

### 13. BM25、Dense Retrieval 和 Hybrid Search 的优缺点是什么？

BM25 擅长精确词、编号、专有名词和错误码，成本低、可解释，但不理解同义改写。Dense 能匹配语义相近表达，对自然语言问法更稳，却可能漏掉罕见实体，受 Embedding 版本和领域漂移影响。Hybrid 同时取两路候选，再用 RRF 或归一化分数融合，通常提高覆盖率，但增加索引、调参与延迟复杂度；两路高度相关时收益可能很小。

不变量是 ACL 必须在候选生成阶段生效，不能先跨租户召回再靠模型忽略。某一路故障可降级到另一路，但要暴露降级标记，不能静默改变质量。指标按查询类型分层看 Recall@K、MRR/nDCG、零结果率、融合贡献率、p95 延迟和成本。可借用推荐系统“多路召回—融合—排序”的经验解释，但检索目标更强调证据覆盖与权限，而非点击率。

### 14. Reranker 解决什么问题，为什么不能替代召回？

Reranker 对较小候选集联合建模 query-document 相关性，能纠正向量距离或 BM25 分数不可比、把真正相关证据提前，尤其适合语义细粒度判断。但它只能重排已召回内容，正确文档不在候选集时无能为力；因此召回优化的是覆盖率，Rerank 优化的是前排精度，两层目标不同。

Cross-encoder 质量高但延迟和成本大，轻量模型或规则更快；候选集过大则吞吐不可控。失败时应保留原始召回排名、Rerank 分数和版本，支持旁路回滚，超时可降级原排序。指标同时看候选 Recall@K、Rerank 后 MRR/nDCG、答案正确率、p95 增量和单请求成本。当前候选人的真实 RAG 未做 Rerank，只能讲设计与待验证方案，不能包装成落地结果。

### 15. Recall@K、MRR、nDCG@K 分别衡量什么？

Recall@K 衡量前 K 个结果是否覆盖全部相关文档，适合评价召回层，但不关心相关文档排第几。MRR 只看第一个相关结果的倒数排名，适合“找到一个正确答案即可”的 FAQ；多个证据都重要时会忽略后续结果。nDCG@K 同时考虑多级相关性与位置折损，适合有强弱相关标注、需要评价整体排序的场景。

三者都依赖可靠 qrels；若标注不完整，系统召回了未标注但有效的证据，会被误判。应按问题类型、租户和时间切片，并联合端到端答案、引用及不可回答指标，不能用单一离线分数替代业务成功。失败时抽检 false negative、更新标注版本并报告置信区间。指标还应带索引/模型版本、K 值、样本数和 p95 延迟，避免不同实验不可比。

### 16. 如何把 RAG 错误分为索引、召回、排序、上下文装配和生成错误？

应保存可回放的分层证据。索引错误是文档缺失、解析错误、Chunk/Embedding/ACL 元数据不正确；召回错误是正确 chunk 在索引中却未进入候选；排序错误是已进入候选但未到可用 Top-K；上下文装配错误是正确证据被预算裁掉、去重合并破坏或权限过滤错误；生成错误是模型已看到充分证据仍曲解、漏引或编造。

归因时固定 query、索引快照、检索与 Prompt 版本，从原文→chunk→候选→排序→最终 context→answer 逐层做 oracle 替换：手工注入正确证据后若仍错，问题更靠后。恢复可回滚版本或重建索引，不能一律调大 Top-K。指标为各层失败占比、阶段转化率、unsupported claim、citation precision、索引新鲜度和回归修复率。

### 17. **[故障]** Top-K 没有正确文档，如何逐层排查？

先确认“正确文档”确实存在且对当前用户可见，再查 ingestion 状态、parser 输出、chunk 边界、删除标记、Embedding 版本和租户/ACL；随后用文档 ID 直查索引，确认不是索引缺失。若存在，分别跑 BM25 与 Dense，观察 query 规范化、专名、语言、过滤条件和候选 K；最后检查融合分数、去重及时间衰减是否把它挤出 Top-K。

不要第一步就增大 K，这可能掩盖 ACL 或解析故障并抬高生成噪声。修复后对故障 query 加入回归集，必要时重切分、补关键词字段、更新 Embedding 或 Hybrid 融合；索引切换使用版本别名并可回滚。指标看受影响文档比例、zero-hit、候选 Recall@K、各路召回贡献、索引延迟和修复前后端到端成功率。

### 18. **[故障]** 正确文档已经召回，模型仍然回答错误，如何定位？

先确认正确文档是否真正进入最终 Prompt，而不只是出现在候选日志；检查是否被 Token Budget 裁掉、被重复片段淹没、顺序靠后、引用 ID 错配或与其他证据冲突。然后固定最终 Context 重放生成，审查指令是否明确要求基于证据、不足则拒答，以及输出结构、表格解析和长上下文注意力是否有问题。

用 oracle 实验逐步移除干扰证据、把正确片段前置、换模型或要求抽取后再回答，可区分装配与生成问题。修复可能是证据去重、上下文分区、冲突检测、两阶段“抽取→综合”或确定性引用验证，而不一定换更大模型。指标看 context recall/precision、faithfulness、citation precision、冲突样本成功率、重放方差、Token 与延迟。

### 19. 如何处理不可回答问题、冲突证据和过期知识？

系统应把“拒答”定义为合法成功类型，而不是逼模型总给结论。先通过检索覆盖、证据质量和答案支持度判断可回答性；证据不足时说明缺失信息并建议澄清。冲突证据要保留来源、版本、生效时间和权威级别，展示分歧或按明确规则选择，不能让模型静默平均。过期知识通过 `valid_from/to`、文档状态和索引新鲜度过滤。

删除或更新必须传播到检索缓存与派生摘要；传播未完成时可临时降权或熔断相关知识域。高风险领域应把低置信、冲突或过期结果转人工。指标包括正确拒答率、错误拒答率、冲突识别率、过期证据引用率、unsupported claim 和知识更新 SLA。推荐系统里的时效特征可迁移，但 RAG 还必须给出可审计引用。

### 20. 如何实现文档 ACL、租户隔离、增量索引和删除传播？

租户是存储、索引、缓存和日志的一级分区键；每个 chunk 继承文档 ACL、owner、classification 和版本。查询时携带经过认证的 principal，在检索阶段做服务端 pre-filter，不能先召回敏感内容再让模型过滤。索引写入采用 outbox/CDC 事件，携带 document version 和幂等 event ID；旧事件不得覆盖新版本。

增量链路为解析→chunk→embedding→新版本可见，成功后原子切换；失败进入 DLQ 并保留旧可用版本。删除使用 tombstone，立即阻断查询，再异步清理向量、倒排、缓存和派生摘要，并可证明传播完成。恢复依赖事件重放和版本对账。指标看跨租户泄漏数、ACL 过滤正确率、索引新鲜度、积压、删除传播 p95、DLQ 数和版本不一致数。

### 21. 如何证明增加 Reranker 值得额外的延迟和成本？

先建立无 Reranker 的固定基线，冻结召回候选和数据集，再比较离线 MRR/nDCG、Top-K 上下文精确率及端到端答案/引用质量；样本要按专名、语义改写、多证据等查询分层，并用 bootstrap 置信区间判断提升是否稳定。随后 Shadow 记录真实查询上的排序差异，不影响用户，确认收益集中在哪些流量。

发布时可仅对低置信或高价值请求启用，并设延迟/成本预算和超时降级。若质量提升未超过业务最小有意义差异，或 p95、吞吐、费用恶化不可接受，就不应上线。指标至少报告任务成功率增量、MRR/nDCG 增量、p95 增量、每千次成本、超时率及单位质量提升成本。对当前经历只能提出实验设计，因为尚无 Rerank 真实数据。

### 22. **[系统设计]** 设计一个百万级企业知识库 RAG，覆盖评测、引用和权限。

组件包括文档接入/病毒扫描、Parser/Chunker、元数据与 ACL 服务、Embedding Worker、倒排与向量索引、版本化 Index Registry、Query Gateway、Hybrid Retriever、可选 Reranker、Context Builder、LLM Gateway、Citation Verifier、缓存、Eval/Trace 平台。原文放对象存储，文档/Chunk 元数据放关系库，索引按 tenant 或安全域分片；密钥与模型访问经统一网关。

写链路由 outbox/CDC 驱动：文档版本入库后解析、切分、Embedding、双索引构建，校验完整性后原子切换别名。查询链路先鉴权和 query 分类，再在 ACL pre-filter 下执行 BM25+Dense、融合/Rerank、去重和 Token Budget 装配；生成必须输出 chunk citation，Verifier 检查引用存在、可见且能支持 claim。不可回答、冲突或高风险请求转澄清/人工。

数据模型至少有 Document、DocumentVersion、Chunk、ACLBinding、IndexVersion、IngestionJob、QueryTrace、Citation 和 EvalCase。所有记录带 tenant、source、content hash、parser/embedding version、valid time。删除先写 tombstone 并实时阻断读取，再清理索引、缓存和摘要；Worker 重试幂等，DLQ 可重放，定期对账原文、元数据和双索引。索引版本可蓝绿回滚。

容量按百万文档乘平均 chunk 数估算向量数、Embedding 吞吐和存储放大，查询侧按峰值 QPS、候选 K、Rerank 批量与模型并发限流；冷热分层和 tenant 配额防止 noisy neighbor。评测使用版本化 Golden/Hidden/线上抽样集，分层报告 Recall@K、nDCG、答案正确率、faithfulness、citation precision/recall、ACL 泄漏、索引新鲜度、p95 和成本。候选人的推荐经验可映射到多路召回和排序，现有 RAG 经历则应明确规模与未做 Rerank/Eval 的边界。

## C. Context Engineering 与 Memory（23～30）

### 23. Prompt Engineering 与 Context Engineering 的区别是什么？

Prompt Engineering 主要优化指令的表达、结构和示例，让模型知道“怎么做”；Context Engineering 管理模型在某一轮到底能看到什么，包括系统规则、用户输入、检索证据、工具结果、记忆、预算、来源、权限和压缩策略。前者偏模板，后者是贯穿运行时的数据管道与安全边界，目标是以有限 Token 提供最相关、可信、可追溯的信息。

只改 Prompt 无法解决过期记忆、越权文档或工具结果过大；只堆长上下文也会提高噪声、成本和注入风险。故障时要能解释每个 Context Item 为何纳入或丢弃，并按版本回放。指标看 context precision/recall、Token 利用率、截断率、来源覆盖、注入拦截率、任务成功率和成本。当前项目 Context 闭环仍弱，Shared Harness 仅有预算基线，不能说已生产化。

### 24. Working、Episodic、Semantic Memory 如何划分？

Working Memory 是当前 Run 的短期状态，如目标、计划、最近 Observation 和未完成动作，生命周期短且应随 Checkpoint 恢复。Episodic Memory 记录过去事件或会话摘要，例如“上次排查做过什么”，强调时间、主体和来源。Semantic Memory 是从多次事件或权威资料中提炼的稳定事实与偏好，需版本、置信度和可撤销依据，不能把一次模型输出直接晋升为事实。

划分依据是用途与更新语义，不是存储介质。工作记忆可压缩但不能丢游标；情节记忆按任务相关性检索；语义记忆需冲突合并、过期和人工修正。敏感信息受 tenant/user ACL 与 TTL 控制。指标包括记忆命中后净提升、错误记忆率、过期命中率、压缩恢复正确率、删除传播和 Token 成本。

### 25. 长上下文、检索和摘要分别适合什么场景？

长上下文适合一次性、强顺序依赖且整体可放入窗口的材料，能减少检索漏召回，但成本高、注意力会稀释。检索适合跨大量文档或历史按需选证据，成本可控、可做 ACL，却可能漏召回并引入排序复杂度。摘要适合长会话和任务历史压缩，但它是有损变换，容易丢约束、数字和未决事项，且错误会累积。

生产上通常组合：固定不变量和近期状态直接保留，历史证据按需检索，旧轨迹生成结构化摘要，并保留原事件引用供回查。摘要不能替代持久状态或副作用台账。失败时可从原始 Event 重建，并比较压缩前后完成条件。指标看 Token 节省、检索命中率、摘要事实保真率、长上下文位置敏感性、任务成功率和 p95。

### 26. 如何给 System Instruction、用户输入、检索文档和 Tool Result 划分信任等级？

信任不是简单按文本位置决定。平台签名且版本固定的 System Instruction 具有最高指令权，但仍不能越过代码级 Policy；用户输入是任务数据，只能在授权范围内表达意图；检索文档是低信任证据，可能含 Prompt Injection；Tool Result 的事实可信度取决于工具身份、Schema、Receipt 和新鲜度，也应视为数据而非新指令。

Context Builder 应给每项标注 source、trust、tenant、classification 和 allowed purpose，用清晰分隔与结构化 Schema 防止指令/数据混淆。真正的权限必须在 Tool Gateway 服务端校验，不能靠“忽略恶意文本”的 Prompt。故障后隔离污染来源、撤销派生记忆并回放。指标看注入命中/绕过率、策略拒绝率、来源缺失率、越权工具调用数和误拦截率。

### 27. Context Item 应记录哪些元数据，如何决定纳入或丢弃？

Context Item 至少记录 `id/type/content reference/source/tenant/principal/ACL/trust level/created-at/valid-time/version/token estimate/relevance/priority/sensitivity/content hash`，摘要项还要有父事件引用和压缩版本。Tool Result 应附 call ID、状态、receipt 与时效；检索片段附文档、chunk、offset 和 citation ID。敏感原文尽量引用而非复制。

纳入决策先做权限和安全硬过滤，再按任务相关性、权威性、新鲜度、去重和完成条件分配 Token；系统不变量、当前目标、未决副作用不能被普通相关性排序挤掉。丢弃要记录原因，如过期、越权、重复或预算不足，便于回放。指标看各类 Token 占比、截断/丢弃原因、context precision、过期项比例、权限违规数及纳入后的边际成功提升。

### 28. **[故障]** Coding Agent 工作两小时后上下文溢出，如何压缩且不丢失任务状态？

先暂停新工具调用并做一致性 Checkpoint，把不可压缩的状态独立持久化：目标与验收条件、repo revision/worktree、已修改文件和 Patch、测试结果、未决错误、工具副作用、计划游标及安全约束。然后把旧对话按阶段压成结构化摘要，保留关键结论对应的文件行、命令输出 Artifact 和 Event ID；近期失败与当前工作集原样保留。

恢复前由 Verifier 检查摘要是否覆盖未决事项、Patch Hash 和测试证据，可从原事件按需检索，而不是把全轨迹重新塞回窗口。若压缩质量不足，宁可开新 Run 并显式 handoff，也不能让模型凭模糊摘要继续写。指标看压缩比、事实保真率、恢复后首轮成功率、重复工具调用率、丢失约束数、Token 与延迟。当前项目 Coding 仍为 Fake，此处是目标设计。

### 29. **[故障]** 旧记忆污染当前任务并触发错误工具调用，如何防止和修复？

先通过 Trace 找到该 Tool Call 使用的 Context Snapshot，定位污染记忆的来源、写入者、版本、召回分数、ACL 和有效期，并立即取消/暂停执行；若是写工具，查询 Effect/Receipt 判断是否已产生副作用，UNKNOWN 时转确认流程。不能只删除当前 Prompt，因为污染可能已进入摘要、向量索引和缓存。

预防上，Memory 写入需来源与主体绑定，模型推断不能自动晋升为 Semantic Fact；检索按 tenant/user/task、时效和负向约束过滤，工具执行前 Policy 重新验证当前事实。修复时 tombstone 原记忆、清理派生项和缓存，从干净 Checkpoint 重建 Context，并把样本加入回归集。指标包括污染命中率、错误工具调用率、记忆撤销传播时间、来源缺失率和拦截召回率。

### 30. **[系统设计]** 设计一个支持跨会话恢复、权限和 Token Budget 的 Context Pipeline。

组件包括 Context API、Identity/ACL Resolver、Item Store、Event/Conversation Store、Memory Index、Retriever/Reranker、Trust & Safety Filter、Budget Allocator、Compactor/Summarizer、Snapshot Store 和 Trace/Eval。Harness 每轮传入 tenant、user、run、goal、profile 与模型窗口；Pipeline 返回版本化 Context Snapshot，而不是直接拼字符串。System 资产按精确版本固定，外部文本统一视为数据。

主链路是：读取当前 Run 状态和最近事件→按身份检索 episodic/semantic memory 与知识证据→硬性 ACL/PII/有效期过滤→去重和冲突检测→按分区预算选择→必要时压缩→生成带 citation 的 Snapshot。预算先预留系统规则、目标、未决动作和输出空间，再在近期轨迹、工具结果、检索证据和记忆间动态分配；任何相关性模型都不能淘汰安全不变量。

数据模型包含 ContextItem、ContextSnapshot、SnapshotItem、MemoryRecord、Summary、SourceRef 和 AccessBinding。Item 带来源、信任、版本、有效期、敏感级、Token、Hash 与丢弃原因；Snapshot 绑定 run/turn/model/profile 和各资产版本。跨会话恢复读取持久 Run/Checkpoint，而非仅靠聊天摘要，并重新鉴权、检查记忆时效；旧摘要错误时可沿 SourceRef 从原 Event 重建。

故障上，检索服务不可用时降级到最小安全上下文并标记 degraded；摘要失败则保留原始近期项或暂停，绝不静默丢状态；ACL 变更和删除通过 tombstone/outbox 传播到索引与缓存；并发更新用版本检查，防止旧 Snapshot 覆盖新状态。容量按活跃 Run、每轮 Item 数、快照保留期、向量规模和摘要 QPS估算，冷热分层并设租户配额。

指标包括 Context 构建 p50/p95、Token 使用/浪费、截断率、检索 Recall、context precision、摘要保真率、过期/越权纳入数、跨会话恢复成功率、缓存命中和每 Run 成本。映射当前项目时应明确：Legacy Chat 会话仍在 JVM，Shared Harness 只有 Context Budget 基线；这是面向 H3 的设计，不是已有完整生产能力。
