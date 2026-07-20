# 资深 Agent 岗位调研摘要

> 调研截止：2026-07-20。动态招聘页面可能更新或下线，投递前需重新核验。

## 1. 岗位匹配排序

| 排名 | 岗位方向 | 匹配度 | 判断 |
|------|----------|--------|------|
| 1 | Agent 平台 / Runtime / 企业智能体后端 | 极高 | Java 分布式、DAG、RAG 可直接迁移 |
| 2 | 企业 Agent 应用 / AI-First 后端 | 高 | 强调系统集成、可靠性和业务交付 |
| 3 | Coding Agent Harness / Eval | 中高 | 需补 Sandbox、代码工具链和轨迹评测 |
| 4 | Agent 平台架构师 / Staff | 中高 | 履历足够，但需补 Agent 平台 Ownership 证据 |
| 5 | Agent 算法 / Post-training / RL | 低 | 通常要求多年训练、PyTorch、RL 或论文经历 |
| 6 | CUDA / 推理引擎 / 模型系统 | 很低 | 不属于两个月可转换方向 |

Java 足以竞争平台、Runtime 和企业应用岗位，但 Python 需要达到能独立编写评测、数据处理、适配服务和排障脚本的程度。

## 2. 高频考察矩阵

| 能力域 | 面试重点 | 当前策略 |
|--------|----------|----------|
| 分布式后端 | 高并发、HA、异步、一致性、故障治理 | 用大厂经历建立资深基线 |
| Agent Loop/编排 | 状态、恢复、异常、审批和生命周期 | 把 DAG 经验迁移为 Durable Agent |
| Tool/MCP | Schema、鉴权、幂等、超时、重试和审计 | 实现 Tool Envelope 与 MCP Gateway |
| Context/RAG/Memory | 检索、重排、引用、ACL、压缩和预算 | 重做可评测 RAG 与 Context Pipeline |
| Eval/可观测 | Dataset、回放、Judge、回归、线上反馈 | 用推荐评测经验迁移到 Agent Eval |
| 成本与延迟 | Token、路由、缓存、限流、降级 | 给出 SLO、容量和单任务成本模型 |
| 安全治理 | Sandbox、最小权限、多租户、注入、HITL | 做威胁模型与故障/攻击演示 |
| 技术领导力 | 取舍、路线图、跨团队、事故和复盘 | 准备 Owner 级项目故事 |

## 3. 高频系统设计题

1. 设计支持多租户、工具治理、Context、Eval 和成本归因的企业 Agent 平台。
2. 设计可暂停、恢复、审批、重试、补偿和版本升级的 Durable Agent Runtime。
3. 设计带 ACL、Hybrid Search、Rerank、引用和评测的企业 RAG Agent。
4. 设计受限文件/终端、Patch/Test/Review 的 Coding Agent Harness。
5. 设计 Golden Set、轨迹回放、规则 Scorer、LLM Judge 和发布门禁的 Eval 平台。
6. 设计可取消、可续传、有背压和模型降级的高并发流式 Agent 服务。
7. 设计 MCP Registry/Gateway，处理授权、凭证、版本、审计和 Prompt Injection。
8. 如何把推荐系统的召回、排序和实验经验迁移到 Agent Context 与 Eval。

## 4. 简历关键词使用原则

当前阶段只用于准备项目表达，不开始制作正式简历。

- 平台：Agent Runtime、Agent Loop、DAG、Checkpoint、Interrupt/Resume。
- 工具：Tool Calling、MCP、Skill、Idempotency、Approval、Audit。
- Context：Context Engineering、Memory、RAG、Hybrid Search、Rerank、Citation。
- 可靠性：Retry/Backoff、Compensation、Circuit Breaker、SLO、Tracing。
- Eval：Dataset、Offline Replay、Scorer/Judge、Regression Gate。
- 安全：Sandbox、Least Privilege、Multi-tenant Isolation、Prompt Injection、Kill-switch。

关键词必须绑定真实动作、规模、指标或取舍，不能只罗列名词。

## 5. 两个月可补与不可补

### 可补

- Python 工程熟练度。
- 可验证的 Java Agent Runtime 项目。
- MCP 协议及生产治理认知。
- Agent Eval、回放和发布门禁。
- 基础 Sandbox 和安全治理。
- Coding Agent 深度使用与小型 Benchmark。

### 不可伪造

- 多年生产 Agent Ownership。
- 大规模 SFT/DPO/RLHF 和百卡训练经验。
- CUDA/vLLM 推理内核资历。
- 成熟 Coding Agent 产品的大规模用户经验。
- 顶会论文或长期高影响开源信誉。

## 6. 主要一手来源

- [字节跳动：AI Agent 研发工程师](https://jobs.bytedance.com/experienced/position/7571365864895072517/detail)
- [腾讯：微信读书 AI 后台开发](https://careers.tencent.com/jobdesc.html?postId=2014174712460103680)
- [腾讯游戏 MagicAI：AI Agent 开发](https://careers.tencent.com/jobdesc.html?postId=2043637366815617024)
- [Gate：AI 研发工程师](https://jobs.lever.co/gate/26d78072-0282-4e71-bccc-5ecb79282269)
- [Patsnap：Lead/Staff AI Agent Platform](https://jobs.lever.co/patsnap/3b8e49d4-dc8b-485e-9b46-415b155969e3/apply)
- [OKX：Senior/Staff AI Agent Development](https://job-boards.greenhouse.io/okx/jobs/7699598003)
- [OKX：Senior Staff AI Platform](https://job-boards.greenhouse.io/okx/jobs/7678331003)
- [Dun & Bradstreet：Senior Engineer-AI](https://jobs.lever.co/dnb/dc500c96-72ec-4635-9b79-c3dc710465be)
- [Binance：Java Backend AI/Big Data](https://jobs.lever.co/binance/913620d8-db8a-45d3-9969-4929e3e3b558)
- [Cursor：Agent Evaluation and Quality](https://cursor.com/careers/software-engineer-agent-evaluation-and-quality)
- [Cursor：Agent Harness](https://cursor.com/careers/product-manager-agent-harness)

证据说明：公司 JD 通常不公开逐轮面试题。本文系统设计题依据岗位职责推导，不冒充泄露题库。

