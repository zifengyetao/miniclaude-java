# 资深 Agent 面试题库

共 96 题。目标不是背定义，每题都要回答：

1. 原理和不变量；
2. 适用边界与反例；
3. 失败场景和恢复方式；
4. 架构取舍；
5. 可观测指标；
6. 如何映射到当前项目或既有经历。

标记说明：

- **[系统设计]**：要求在 45～60 分钟内完成架构、数据模型、时序、故障和容量设计。
- **[故障]**：先定位再修复，不允许只给最佳实践列表。
- **[伪代码]**：要求写接口、状态转换或核心算法。
- **[反例]**：重点回答为什么不应使用某种 Agent 方案。

## A. Agent 与 Harness 基础（10）

1. Agent Loop 与普通 LLM API 调用的本质区别是什么？
2. Workflow、Agent、Harness 三者分别负责什么，边界如何确定？
3. ReAct、Plan-and-Execute、Reflection 分别解决什么问题，有什么失败模式？
4. 如何定义 Run、Step、Attempt、Observation、Artifact 和 Final Result？
5. Agent 的终止条件如何设计，怎样区分完成、失败、暂停和预算耗尽？
6. 为什么 Coding Agent 可以自主规划，但仍必须被确定性 Harness 包住？
7. **[反例]** 哪些任务不应该使用 Agent，而应该用普通代码、规则或 DAG？
8. **[故障]** Agent 重复调用同一工具并陷入循环，如何诊断和阻断？
9. **[伪代码]** 写一个支持预算、取消和 Tool Calling 的最小 Agent Loop。
10. **[系统设计]** 设计一个支持 Chat、长任务和人工审批的统一 Agent Runtime。

## B. RAG 与检索（12）

11. Chunk Size、Overlap 和文档结构分别如何影响召回与生成？
12. Fixed-size、Recursive、Semantic、Parent-child Chunk 各适合什么数据？
13. BM25、Dense Retrieval 和 Hybrid Search 的优缺点是什么？
14. Reranker 解决什么问题，为什么不能替代召回？
15. Recall@K、MRR、nDCG@K 分别衡量什么？
16. 如何把 RAG 错误分为索引、召回、排序、上下文装配和生成错误？
17. **[故障]** Top-K 没有正确文档，如何逐层排查？
18. **[故障]** 正确文档已经召回，模型仍然回答错误，如何定位？
19. 如何处理不可回答问题、冲突证据和过期知识？
20. 如何实现文档 ACL、租户隔离、增量索引和删除传播？
21. 如何证明增加 Reranker 值得额外的延迟和成本？
22. **[系统设计]** 设计一个百万级企业知识库 RAG，覆盖评测、引用和权限。

## C. Context Engineering 与 Memory（8）

23. Prompt Engineering 与 Context Engineering 的区别是什么？
24. Working、Episodic、Semantic Memory 如何划分？
25. 长上下文、检索和摘要分别适合什么场景？
26. 如何给 System Instruction、用户输入、检索文档和 Tool Result 划分信任等级？
27. Context Item 应记录哪些元数据，如何决定纳入或丢弃？
28. **[故障]** Coding Agent 工作两小时后上下文溢出，如何压缩且不丢失任务状态？
29. **[故障]** 旧记忆污染当前任务并触发错误工具调用，如何防止和修复？
30. **[系统设计]** 设计一个支持跨会话恢复、权限和 Token Budget 的 Context Pipeline。

## D. Durable Execution 与工具事务（14）

31. 状态持久化为什么不等于 Durable Execution？
32. Event History、Checkpoint、Snapshot 和 Effect Ledger 有什么区别？
33. Workflow 与 Activity 的边界如何划分？
34. 什么是 Deterministic Replay，为什么 LLM 调用不能直接放在可重放逻辑里？
35. Checkpoint 应按消息、节点、工具还是副作用保存？如何取舍？
36. At-least-once 和 At-most-once 分别带来什么问题？
37. 为什么通用分布式系统很难保证真正的 Exactly-once 外部副作用？
38. 幂等键由谁生成，作用域、TTL 和参数绑定如何设计？
39. 工具超时为什么不能直接视为失败？`UNKNOWN` 状态如何处理？
40. 查询确认、重试、补偿和人工介入如何组成决策树？
41. Worker Lease、Heartbeat 和 Fencing Token 分别解决什么问题？
42. **[故障]** 退款请求超时，但实际已经成功，系统怎样安全恢复？
43. **[故障]** Worker 在外部调用成功后、写 Checkpoint 前崩溃，会发生什么？
44. **[系统设计]** 设计支持暂停、审批、恢复、取消和版本升级的长任务 Agent。

## E. Tool Calling、MCP 与安全（10）

45. Function Calling、REST/RPC、MCP 的差异是什么？
46. MCP Host、Client、Server 分别承担什么职责？
47. Tools、Resources 和 Prompts 的边界是什么？
48. stdio 与 Streamable HTTP 如何选择？
49. Tool Schema 如何设计，怎样处理枚举、分页、大结果和 Schema 演进？
50. 为什么 MCP 不是认证、授权或安全边界？
51. 如何贯通 User、Tenant、Run、Trace、Approval 和 Idempotency Key？
52. **[故障]** 检索文档中的 Prompt Injection 诱导模型读取敏感表，在哪些层拦截？
53. **[故障]** MCP Server 断线重连后出现重复写，如何定位和修复？
54. **[系统设计]** 设计企业 MCP Gateway：注册、发现、授权、凭证代理、审计和限流。

## F. Multi-Agent（8）

55. 单 Agent、确定性 Workflow 和 Multi-Agent 的选择标准是什么？
56. Supervisor、Handoff、Fan-out、Blackboard 分别适合什么问题？
57. Multi-Agent 如何定义消息契约、共享状态 Owner 和权限？
58. 为什么多个角色 Prompt 相互聊天通常不等于有效协作？
59. Reviewer Agent 如何避免与执行 Agent 产生相关错误？
60. 如何公平比较单 Agent 和 Multi-Agent 的效果、成本与延迟？
61. **[故障]** Planner 不断生成子 Agent 导致成本失控，如何治理？
62. **[反例]** 给出一个应取消 Multi-Agent 并退回单 Agent/DAG 的案例和证据。

## G. Java 流式 Agent Runtime（12）

63. SSE 与 WebSocket 在 Agent 场景中如何选择？
64. 模型 Token 流与 Agent Event 流有什么区别？
65. 如何定义 Token、Step、Tool、Approval、Artifact、Error、Done 事件？
66. Reactor 背压如何处理模型生产速度与客户端消费速度不匹配？
67. 用户取消后，如何把取消传播到模型、工具、子任务和计费？
68. SSE 断线重连时如何实现事件续传、去重和顺序保证？
69. 多个只读工具并行执行时，如何处理超时、部分成功和结果合并？
70. 如何实现模型供应商限流、排队、熔断、Fallback 和成本预算？
71. Trace Context 如何跨模型、Tool、消息队列和异步线程传播？
72. **[故障]** 慢客户端导致内存持续增长，如何定位和修复？
73. **[伪代码]** 定义 `Flux<AgentEvent>` 及取消、错误、终态语义。
74. **[系统设计]** 设计支持 1000 并发长连接的流式 Agent 网关。

## H. Eval、可观测与发布治理（12）

75. Agent Eval 为什么不能只评最终文本？
76. Task Success、Tool Correctness、Faithfulness、Latency、Cost 如何定义？
77. Golden Set、Hidden Holdout、线上样本和对抗集分别有什么作用？
78. Deterministic Scorer、LLM-as-Judge 和人工评审如何组合？
79. LLM Judge 有哪些偏差，如何校准一致性和可信度？
80. 非确定性 Agent 如何做稳定的回归测试？
81. Trace Playback 与重新调用模型有什么区别？
82. 如何对失败轨迹进行聚类和根因分类？
83. 如何设计 Baseline、Shadow、Canary、Release Gate 和 Rollback？
84. **[故障]** 离线分数提升但线上投诉增加，如何调查？
85. **[故障]** 新模型成功率提高但成本翻倍，如何做发布决策？
86. **[系统设计]** 设计 Agent Eval 平台，覆盖数据、Runner、Scorer、报告和门禁。

## I. Coding Agent Harness（10）

87. Coding Agent 的最小工具面应该包含什么？
88. Repo Search、文件读取、Patch、Shell、Test 为什么需要不同权限？
89. Worktree、Container 和远程 Sandbox 如何选择？
90. 如何防止危险命令、秘密泄露、越权目录写入和网络外传？
91. Agent 如何判断“修复完成”，而不是只让现有测试变绿？
92. 如何设计 Patch Apply、冲突检测、回滚和人工审查？
93. 长时间 Coding 任务如何保存计划、进度、上下文和恢复点？
94. **[故障]** Agent 删除测试以让构建通过，如何检测和治理？
95. **[故障]** Agent 修复一个模块却引入跨模块回归，如何改进验证闭环？
96. **[系统设计]** 设计企业 Coding Agent Harness，覆盖隔离、工具、评测、审批和审计。

## 训练方式

每周从对应模块选择：

- 4 道基础题：每题 5 分钟；
- 2 道故障题：每题 15 分钟；
- 1 道系统设计题：45～60 分钟；
- 1 道当前项目映射题：说明代码现状、缺口和改造方案。

复盘时标记：

```text
题号：
掌握度：0/1/2/3
第一次回答：
遗漏点：
被追问后失效的位置：
项目证据：
下一次复习日期：
```

掌握度定义：

- 0：不会；
- 1：能解释概念；
- 2：能讲取舍和失败；
- 3：能结合代码、指标和事故完成资深答辩。

