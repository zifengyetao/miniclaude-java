# 资深 Agent 面试题库 D～F 章参考答案

> 本文为题 31～62 的简体中文标准参考答案，面向资深 Agent 平台 / Runtime 面试。答案强调工程不变量、失败恢复、架构取舍与可观测指标；“当前项目”只描述仓库截至 2026-07-20 已实现的能力，不把规划项或 Fake 适配器包装成生产能力。

## 目录

- [D. Durable Execution 与工具事务（31～44）](#d-durable-execution-与工具事务31-44)
- [E. Tool Calling、MCP 与安全（45～54）](#e-tool-callingmcp-与安全45-54)
- [F. Multi-Agent（55～62）](#f-multi-agent55-62)

## D. Durable Execution 与工具事务（31～44）

### 31. 状态持久化为什么不等于 Durable Execution？

状态持久化只保证“数据还在”，Durable Execution 还必须保证进程、机器或网络故障后，任务能由合法 Worker 从一致位置继续，且不会跳步、重复不可逆副作用或被旧 Worker 覆盖。核心不变量是 Run/Step/Attempt 状态单调、单一有效执行权、恢复游标与输入/版本绑定、外部 Effect 可确认。仅把 `RUNNING` 写进数据库，若没有 Lease/Heartbeat/Fencing、重试与定时唤醒、幂等和版本语义，宕机后仍可能永久悬挂或双执行。恢复指标应含接管成功率、p95 接管时延、重复 Effect 数和僵尸 Run 数。当前项目 local JDBC 已有 checkpoint、审批和终态原子提交，但仍是同步请求内步进，缺少完整 Worker lease/heartbeat 与自动接管，因此不能称为完整 Durable Runtime。

### 32. Event History、Checkpoint、Snapshot 和 Effect Ledger 有什么区别？

Event History 是追加式事实序列，用于审计、重放和解释“为何到此状态”；Checkpoint 保存可继续执行的最小游标与校验信息；Snapshot 是历史在某时刻的物化状态，用于加速恢复，但可由历史重建；Effect Ledger 专门记录外部副作用的意图、幂等键、参数哈希、审批绑定、状态、receipt 与确认结果，防止重放再次产生 Effect。四者不能混用：Snapshot 被覆盖不能替代审计史，Checkpoint 也不能证明退款是否落账。恢复时先校验版本和哈希，再载入 Snapshot/Checkpoint，补放后续事件，并查询 Ledger 中 `UNKNOWN` Effect。指标包括重放时延、快照命中率、历史长度、Ledger 未决时长与重复 Effect。当前项目有事件和 checkpoint，Analyst 还保存游标、attempt、版本及 I/O 哈希，但尚无 Tool Effect Ledger。

### 33. Workflow 与 Activity 的边界如何划分？

Workflow 负责确定性协调：状态机、分支、等待、超时、重试策略、审批与补偿编排；Activity 封装模型调用、数据库、HTTP、文件和随机数等非确定性 I/O。关键不变量是 Workflow 重放相同历史必须得到相同命令，Activity 结果则作为事件写入历史后再被消费。边界过粗会使长 Activity 难以心跳、取消和局部重试；过细会放大调度、序列化和历史成本。副作用 Activity 必须带 deadline、effect class、幂等键和审批绑定，超时进入 `UNKNOWN` 而非直接失败。应观察 Activity 重试率、排队/执行 p95、心跳超时、历史膨胀和补偿率。当前项目的 Graph 节点近似 Workflow 步骤，Fake 端口近似 Activity，但默认 local 实现没有完整 Worker/Activity 调度；Temporal 仅有边界，不是生产实现。

### 34. 什么是 Deterministic Replay，为什么 LLM 调用不能直接放在可重放逻辑里？

Deterministic Replay 是用同一事件历史重新执行 Workflow 代码，并产生与历史一致的调度决策；不变量是已发生事件不能因代码、时间、随机数或外部响应变化而改写。LLM 输出受模型版本、采样、供应商和上下文变化影响，即使温度为零也不保证字节级稳定，因此直接在重放逻辑中调用会导致命令分叉。正确做法是把 LLM 调用作为 Activity：固定模型/Prompt/Tool Schema 版本，将结果和 token/cost 摘要持久化，重放时读取既有结果；需要重新推理时创建新 Attempt 或新 Run，而非伪装成 replay。版本升级用版本标记或让旧 Run 固定旧定义。指标包括 nondeterminism 次数、重放成功率和版本不兼容 Run 数。当前 local Graph 依靠 checkpoint 恢复而非完整历史重放，生产 Temporal Worker 也尚未交付。

### 35. Checkpoint 应按消息、节点、工具还是副作用保存？如何取舍？

Checkpoint 边界由“可接受的重算成本”和“不可重复风险”决定，而非机械地每条消息保存。纯计算或只读、低成本步骤可在节点后保存；昂贵模型调用宜在结果落盘后保存；写工具必须先持久化 Effect 意图/幂等键，执行后写 receipt，再推进游标；审批前后都要形成稳定边界。保存过密会增加事务、存储和延迟，过疏则扩大重放窗口，并可能重复付费或副作用。Checkpoint 必须绑定 run、step、attempt、定义版本、输入/输出哈希和序号，恢复时拒绝图漂移或篡改。指标看写入 p95、恢复重算量、checkpoint 大小和重复调用率。当前 Analyst 按节点保存游标、attempt、Graph 版本与哈希，审批和终态原子化，但工具副作用前后尚无 Effect Ledger 边界。

### 36. At-least-once 和 At-most-once 分别带来什么问题？

At-least-once 通过未确认即重投提高“不丢执行”概率，代价是重复调用，要求消费者幂等、去重和 Fencing；At-most-once 在发送或领取后不自动重试，避免平台主动重复，却可能在请求或响应丢失时漏执行。二者都不等于业务 Exactly-once：前者可能重复扣款，后者可能漏扣款。读操作通常适合 at-least-once；不可查询且不可补偿的外部写应偏保守，超时进入 `UNKNOWN` 并人工确认。选择要结合 Effect 可幂等、可查询、可补偿性及业务损失，而非统一策略。指标包括投递次数分布、去重命中、漏执行、重复 Effect 和 `UNKNOWN` 占比。当前项目已有部分业务幂等和原子终态，但尚未实现通用工具投递语义与 Ledger，不能声称 exactly-once。

### 37. 为什么通用分布式系统很难保证真正的 Exactly-once 外部副作用？

平台数据库与外部系统通常不共享同一事务域。调用成功后本地提交前崩溃，与调用失败前网络断开，在调用方看来都可能是超时；两将军问题决定了仅靠消息无法同时知道对端是否执行。即使消息队列宣称 exactly-once，也通常只覆盖其内部日志，不覆盖退款、邮件或 SaaS API。工程目标应改为“业务 Effect 恰好一次”：调用方稳定幂等键、外部方原子去重、Effect Ledger、查询确认、必要时补偿和人工介入；若对端均不支持，只能在重复与遗漏风险间取舍。衡量重复 Effect、未决 `UNKNOWN`、对账差异和补偿成功率。当前 local JDBC 的终态/Artifact 原子提交只覆盖本库，外部适配器又多为 Fake，不能外推为跨系统 exactly-once。

### 38. 幂等键由谁生成，作用域、TTL 和参数绑定如何设计？

幂等键应由最早知道业务意图且能稳定重试的一方生成，通常是 Runtime/调用方，而不是每次由 Tool 随机生成。键的唯一域至少包含 tenant、tool、operation 和业务对象，常用 `run/step/effect-slot` 或客户端 request id；所有 Attempt 复用同一键。Ledger 必须绑定规范化参数哈希、调用者、审批版本和 Effect Class：同键异参应拒绝而非返回旧结果。TTL 要覆盖最长重试、人工审批和对账周期；不可逆金融 Effect 往往长期归档，不能因 TTL 到期自动重放。还需并发唯一约束和原子“首次占位”。指标看冲突率、同键异参拒绝、去重命中和过期后重放。当前项目有 Artifact 等局部稳定幂等键，但尚无 caller-provided 通用 Tool 幂等键与 Effect Ledger。

### 39. 工具超时为什么不能直接视为失败？`UNKNOWN` 状态如何处理？

超时只表示调用方在 deadline 内没拿到确定响应，不代表外部系统没执行：请求可能已落账，响应却丢失。把它记为 `FAILED` 并盲重试会产生双重退款、重复发信等副作用。正确状态是 `UNKNOWN`：冻结依赖该结果的后续步骤，持久化幂等键、参数哈希和调用时间；优先用 receipt、幂等查询或业务对象对账确认，确认成功则转 `SUCCEEDED`，确认未发生且重试安全才重试，无法确认则补偿或人工介入。取消也不能覆盖已发出的 Effect。指标包括 `UNKNOWN` 比例、p95 未决时长、确认来源、人工率和重复 Effect。当前项目尚未实现通用 `UNKNOWN` 与 Tool Effect Ledger，Fake Tool 的确定性返回不能证明生产外部调用安全。

### 40. 查询确认、重试、补偿和人工介入如何组成决策树？

先按 READ、WRITE、IRREVERSIBLE 分类并检查审批和 kill-switch。失败若能证明请求未发送，可按退避与预算重试；若可能已发送，立即进入 `UNKNOWN`，先以原幂等键/receipt 查询。查询确认成功则提交 Ledger 并继续；确认未发生且接口支持幂等，才复用原键重试；已成功但业务需撤销，则发起独立、可审计的补偿 Effect，不能“删除历史”；无查询接口、结果冲突或补偿高风险时暂停人工处理。查询本身也可能失败，应有上限和升级时限。关键不变量是未确认写不盲重试、补偿不伪装回滚、人工决定绑定原参数与证据。指标看各分支比例、MTTR、补偿成功率、人工积压和重复 Effect。当前项目审批/取消边界可作控制面基础，但该决策树与 Ledger 尚待实现。

### 41. Worker Lease、Heartbeat 和 Fencing Token 分别解决什么问题？

Lease 是带过期时间的临时执行权，使 Worker 崩溃后任务可被接管；Heartbeat 证明持有者存活并续租，同时可上报进度、接受取消；Fencing Token 是单调递增的所有权代次，存储和下游只接受最新 token，阻止网络暂停后“复活”的旧 Worker 写入。仅有 Lease 不够：时钟漂移或长 GC 可能让两个 Worker 都以为自己有权；仅有 Heartbeat 也不能阻止旧进程晚到写。领取、token 增长和状态转换应原子化，续租需留安全余量。指标包括 lease 争抢、过期接管 p95、心跳延迟、stale-token 拒绝和双执行检测。当前项目明确缺少完整 Worker lease/heartbeat/fencing 与自动接管；已有的 Coding `WorkspaceLease` 概念也不能冒充 Durable Worker Lease。

### 42. **[故障]** 退款请求超时，但实际已经成功，系统怎样安全恢复？

先停止自动重试并将退款 Effect 标为 `UNKNOWN`，保留原 refund id/幂等键、订单、金额、参数哈希、审批和调用 trace。用支付方按幂等键或退款单号查询：若已成功，校验金额与对象后把 receipt 写入 Ledger，原 Run 从后继步骤恢复；若明确不存在，复用同一键重试；若处理中则定时查询且不重复创建；若结果冲突、无查询能力或超过 SLA，暂停并交人工对账。任何“冲正”都作为新的审批 Effect，而非修改成功历史。修复后回放响应丢失、重复回调和并发恢复用例。关注重复退款数、`UNKNOWN` MTTR、对账差异与人工率。当前项目没有真实支付/退款适配器，也没有 Tool Ledger；面试中只能给出设计，不能声称仓库已验证生产退款恢复。

### 43. **[故障]** Worker 在外部调用成功后、写 Checkpoint 前崩溃，会发生什么？

恢复 Worker 只看到旧 checkpoint，会再次进入该 Step；若直接生成新请求，就可能重复外部副作用。这是经典“外部成功、本地未知”崩溃窗口，不能靠把 checkpoint 写得更快消除。安全方案是调用前在本地原子占位 Effect Ledger，固定幂等键和参数哈希；外部系统以该键去重；接管者发现 `PENDING/UNKNOWN` 后先查询 receipt，再决定提交、重试或人工介入。Fencing Token 防旧 Worker 晚写，但不能替代外部幂等。若外部系统既不幂等又不可查询，只能选择 at-most-once 与漏执行风险，或引入人工对账。故障注入应覆盖每个写前后窗口，指标为恢复成功率和重复 Effect=0。当前项目终态原子提交缩小了本库窗口，但尚无 Tool Ledger 和完整 Worker 接管。

### 44. **[系统设计]** 设计支持暂停、审批、恢复、取消和版本升级的长任务 Agent。

组件包括 API/事件网关、Run Service、确定性 Workflow/Graph 引擎、Worker 调度器、Model/Tool Gateway、Policy/Approval Service、Event Store/Checkpoint Store、Effect Ledger、Artifact Store、定时器和审计/指标系统。主链路是创建 Run 并固定 workflow/model/tool-schema 版本；Worker 以 Lease+Heartbeat+Fencing 领取 Step；模型和工具作为 Activity 执行；写 Tool 先占位 Ledger，审批则把参数哈希、风险、审批人和过期时间原子写入并转 `WAITING_APPROVAL`；批准后从游标恢复，终态与最终 Artifact 原子发布。

数据模型至少有 `Run(id, tenant, status, definitionVersion, cursor, version)`、`Step/Attempt`、`Event(seq)`、`Checkpoint(hash)`、`Lease(fence, expiresAt)`、`Approval(subjectHash, decision)`、`Effect(idempotencyKey, paramsHash, status, receipt)` 和 `Artifact`。取消是持久化请求：阻止新 Step、传播到可取消 Activity，但已发出的写 Effect仍须确认，不能直接标为未发生。Worker 崩溃由租约过期接管；工具超时进 `UNKNOWN`；旧 token 写入被拒；历史过长用 Snapshot 加速。

版本升级默认“在途 Run 固定旧版本，新 Run 用新版本”；必须迁移时提供显式、可回滚的状态转换与兼容校验，LLM 结果作为事件而非重放时重调。容量按活跃 Run、事件写 QPS、计时器数、Tool 并发和长等待审批量分片；Worker 无状态水平扩展，租户配额和全局预算限流。核心 SLO 是恢复正确率、p95 接管/恢复时延、stale fence 拒绝、重复 Effect、审批超时、`UNKNOWN` MTTR、取消传播与单位 Run 成本。当前项目 local JDBC 已实现 checkpoint、审批/终态原子边界；完整 Worker lease/heartbeat、Tool Ledger、生产 Temporal 和自动版本迁移仍是缺口。

## E. Tool Calling、MCP 与安全（45～54）

### 45. Function Calling、REST/RPC、MCP 的差异是什么？

Function Calling 是模型输出结构化“调用意图”的能力，不负责传输、执行或授权；REST/RPC 是服务间 API 风格，约定端点、数据与错误语义；MCP 是 Host 与外部能力提供方之间的上下文协议，统一能力发现、Tools/Resources/Prompts 和会话传输。三者可叠加：模型 Function Call 经平台 Tool Gateway 转成 MCP 或 REST 请求。共同不变量是模型建议不等于已授权执行，平台必须做身份、租户、Schema、策略、审批、幂等和审计。简单固定集成用 REST/RPC 更低成本；动态工具生态才体现 MCP 价值。指标看 schema 校验失败、授权拒绝、调用成功率、p95、协议错误和 Effect 去重。当前项目有受控 Tool Gateway 与旧 stdio MCP Client，但 MCP 尚未纳入统一租户/事务治理，外部适配器多为 Fake。

### 46. MCP Host、Client、Server 分别承担什么职责？

Host 是承载 Agent/用户体验和安全决策的应用，负责用户意图、上下文选择、Consent、策略与结果呈现；Client 是 Host 内与某一 Server 建立隔离会话的协议端，负责初始化、能力协商、请求关联、取消和传输；Server 暴露 Tools、Resources、Prompts 并执行其后端能力。安全不变量是 Server 声明能力不等于获得用户权限，Client 连接成功也不等于调用获批；Host/Gateway 必须最小授权并隔离租户和凭据。Server 失陷应被视为不可信输出源，不能让其返回内容升级为系统指令。指标包括连接/协商失败、每 Server 调用与拒绝率、断线重连和输出截断。当前仓库旧引擎有 stdio Client，但没有生产级 MCP Host/Gateway 治理，也没有真实外部 MCP 能力可宣称。

### 47. Tools、Resources 和 Prompts 的边界是什么？

Tool 表示有输入、执行和结果的动作，可能产生副作用；Resource 是可寻址、可读取的上下文数据，应默认只读；Prompt 是可发现的模板/工作流起点，由用户或 Host 选择后参与上下文构造。不能为绕过审批把写操作伪装成 Resource，也不能把 Server Prompt 当高信任系统指令。三类能力都要有来源、租户、版本、敏感级别和大小限制；Tool 额外需要 Effect Class、deadline、幂等、审批与 receipt，Resource 需要 ACL、时效和引用，Prompt 需要版本审查与注入防护。失败时 Resource 可降级/缓存，写 Tool 超时应进 `UNKNOWN`。指标看各类型使用率、越权、输出大小、Prompt 版本漂移和 Tool Effect 状态。当前项目的 ScenarioPorts 是受控 Fake Tools，并未形成完整 MCP 三类能力目录。

### 48. stdio 与 Streamable HTTP 如何选择？

stdio 适合 Host 在同机启动、单用户或桌面插件式 Server：部署简单、进程级隔离直观，凭据可不经网络；缺点是生命周期、并发、资源限制和跨主机运维较弱。Streamable HTTP 适合远程共享服务、水平扩展和统一网关，但必须处理 TLS、认证、会话、代理超时、重连、限流与多租户隔离。选择依据是信任域和部署拓扑，而非吞吐单指标。两者都不自动保证幂等：断线后的写调用仍需 request id、Effect Ledger 和查询确认；stdio 子进程也需 sandbox、环境变量最小化和输出上限。指标看启动/连接 p95、断线率、并发、资源占用和重复写。当前仓库只有旧 stdio MCP Client 边界，未交付生产 Streamable HTTP Gateway。

### 49. Tool Schema 如何设计，怎样处理枚举、分页、大结果和 Schema 演进？

Schema 应表达最小、无歧义的业务命令：必填字段、类型、范围、枚举、互斥条件、默认值和敏感字段都由服务端验证，不能只靠 Prompt。枚举应封闭且未知值 fail-closed；查询使用游标分页、服务端硬上限和稳定排序；大结果存入受控 Artifact/Resource，只把摘要、引用和句柄送入模型，避免上下文与数据泄露。写操作加入业务对象、Effect Class、幂等键和审批绑定，但调用者不能自报权限。演进优先向后兼容加字段，破坏性变更使用新 tool/version；Run 固定版本，旧版本有退役窗口。监控 schema 拒绝、截断、分页深度、版本使用和兼容失败。当前 Harness 已有参数 Guard、服务端 SQL 行数上限和固定 workspace，但尚无统一 MCP Schema 注册与演进平台。

### 50. 为什么 MCP 不是认证、授权或安全边界？

MCP 规范解决“如何发现和调用上下文能力”，不替企业决定谁能以哪个租户、哪种目的访问何种数据，也不能证明 Server、返回内容或下游系统可信。能连上 Server 仅代表协议可达；若把 Server 自报的 Tool 直接暴露给模型，会遭遇越权、能力替换、Prompt Injection 和凭据滥用。真正边界应在 Host/企业 Gateway：验证用户与服务身份、传播 tenant/subject、能力 allowlist、参数级 ABAC、凭据代理、网络隔离、审批、Effect Ledger、输出净化与审计；Server 侧仍做第二次授权。失败必须 fail-closed，写超时进入 `UNKNOWN`。指标包括跨租户绕过、未授权调用、能力漂移和凭据暴露。当前项目 Policy/Tool Gateway 可作基础，但旧 MCP 尚未统一接入，因此不能宣称 MCP 已生产安全化。

### 51. 如何贯通 User、Tenant、Run、Trace、Approval 和 Idempotency Key？

入口认证后生成不可伪造的 `subject/user` 与 tenant 上下文，创建 Run/Trace；每个 Step/Tool Attempt 派生 span，但这些标识由控制面注入，模型只能引用不能改写。写调用的幂等键稳定绑定 `tenant + run + step/effect-slot`，Ledger 再绑定规范化参数哈希；Approval 记录 approver、职责、策略版本、过期时间及同一 subject hash，参数变化即审批失效。跨 MCP/HTTP 只传最小签名上下文，外部凭据由 Gateway 代理，日志使用相关 ID 而非密钥或完整敏感参数。重试复用幂等键，新业务意图创建新键。指标看链路字段缺失、孤儿 Effect、审批错绑、trace 完整率和跨租户拒绝。当前项目已有 ExecutionContext、审批绑定和 Trace/Run 概念，但通用 Tool 幂等键/Ledger 贯通尚未完成。

### 52. **[故障]** 检索文档中的 Prompt Injection 诱导模型读取敏感表，在哪些层拦截？

先从 Trace 定位恶意 Resource、模型产生的 Tool Call、Policy 决策和实际执行结果，立即撤销相关能力并隔离文档。防线应分层：摄取时扫描、标记来源与信任级；Context 组装把检索文本明确当数据并与系统指令隔离；模型侧提示不可执行文档指令；Tool Gateway 按用户/租户做 allowlist 与参数级策略；SQL Tool 使用只读账号、表/列 allowlist、解析器、行数/成本上限和网络隔离；敏感读取需审批，输出再做 DLP。不能只靠“忽略注入”的 Prompt，也不能让模型自报表权限。指标看注入集拦截率、越权尝试、敏感字段泄露和策略漏判。当前项目有服务端只读 SQL Guard、参数顺序 Guard 和 Fake 查询，不连接真实数仓；MCP 外部边界仍未生产化。

### 53. **[故障]** MCP Server 断线重连后出现重复写，如何定位和修复？

按 `tenant/run/trace/tool-call/idempotency-key` 串联 Client 重发、Gateway 日志、Server 请求与下游业务记录，判断是响应丢失后自动重放、Client 生成了新键，还是 Server 未原子去重。先停用该写 Tool 或切为人工审批，标记相关 Effect 为 `UNKNOWN` 并对账，不能继续盲重试。修复为：调用方首次生成稳定键，重连沿用；Gateway 在 Effect Ledger 原子占位并校验同键同参；Server/下游以唯一约束原子去重并返回原 receipt；重连只恢复会话，不隐式重放非幂等写。无查询能力时升级人工。回归覆盖响应丢失、双连接、乱序和并发重试；指标看重复 Effect、去重命中和 `UNKNOWN` MTTR。当前仓库尚无 MCP Tool Ledger，此能力只能作为待实现设计。

### 54. **[系统设计]** 设计企业 MCP Gateway：注册、发现、授权、凭证代理、审计和限流。

组件包括：Server/Capability Registry、连接与健康管理器、面向 Host 的发现 API、身份认证与租户策略引擎、Schema/参数 Guard、凭证代理/Vault、协议适配器（stdio/Streamable HTTP）、Tool Executor、Approval Service、Effect Ledger、输出过滤/Artifact Store、审计与指标管道。主链路是管理员验证并注册 Server 签名与能力版本；用户发现时先按 tenant/subject/环境过滤；调用时 Gateway 固定 schema 版本，校验参数和数据范围，执行策略与审批，注入短期下游凭据；写 Tool 先占位 Ledger，再调用并记录 receipt；大结果落 Artifact，仅返回引用。

数据模型至少有 `ServerRegistration`、`CapabilityVersion(type,schema,effectClass)`、`TenantGrant(subject,conditions)`、`ConnectionSession`、`ToolInvocation(run,trace,approval,idempotencyKey,paramsHash,status)`、`CredentialLease` 和不可变 `AuditEvent`。断线或超时的写调用进入 `UNKNOWN`，通过原键查询确认；Server 熔断后只读能力可降级，写能力 fail-closed；能力/schema 漂移需重新审核，旧 Run 固定旧版本。凭据不进入模型上下文、日志或 MCP 参数，由 Gateway 代签并限制目标域。

容量按连接数、调用 QPS、长流和结果字节分池；无状态 Gateway 水平扩展，Registry/Ledger 使用强一致唯一约束，按 tenant+server 限流并设全局并发、deadline 和熔断。指标包括发现/调用 p95、授权拒绝、跨租户绕过、连接健康、限流、`UNKNOWN`、重复 Effect、凭据泄露和审计完整率。当前项目仅有旧 stdio Client、受控 Tool/Policy 边界与 Fake Tools，尚未交付该企业 Gateway，也不应虚构真实 MCP/外部系统生产能力。

## F. Multi-Agent（55～62）

### 55. 单 Agent、确定性 Workflow 和 Multi-Agent 的选择标准是什么？

默认从最简单可验证方案开始。规则明确、顺序稳定、合规要求强的任务用普通代码或 Workflow/DAG；路径动态但上下文和权限可由一个主体管理时用单 Agent；只有任务可并行分解、专业上下文/权限确需隔离，且对照 Eval 证明质量或风险收益超过通信、延迟和成本时才用 Multi-Agent。不变量是无论几 Agent，预算、权限、状态 Owner、终止与审计都由确定性 Harness 管。反例是把线性客服流程拆成多个角色聊天，只增加 token 和错误传播。指标应同集比较 Task Success、p95、成本、Tool 正确率、人工率和错误相关性。当前项目选择共享单 Harness+不同 Profile，Graph 用于审批/监管；尚无证据支持继续堆 Multi-Agent。

### 56. Supervisor、Handoff、Fan-out、Blackboard 分别适合什么问题？

Supervisor 适合集中分解、路由和验收，但易成瓶颈与单点误判；Handoff 适合客服/诊断中责任和上下文明确转移，必须显式交接所有权，避免双处理；Fan-out 适合独立检索、候选生成或多数据源并行，需要 deadline、去重和聚合规则；Blackboard 适合多专家围绕共享事实迭代，但要求单写者/版本控制、来源和冲突解析，否则状态污染。选择取决于依赖结构而非角色故事。失败恢复要保存子任务状态与消息版本，只重跑失败分支；全局取消和预算必须下传。指标含分解正确率、队列等待、fan-out 放大倍数、冲突率和聚合收益。当前仓库没有生产 Multi-Agent 编排；现有 Profile 共享 Loop，不应描述成 Agent 间协作。

### 57. Multi-Agent 如何定义消息契约、共享状态 Owner 和权限？

消息应是版本化结构体，而非自由文本聊天：包含 task/subtask id、sender/recipient、目标、输入引用、证据、预期输出 schema、deadline、budget、权限范围、attempt 和 trace；不传隐式思维链。共享状态按字段或 Artifact 指定唯一 Owner，其他 Agent 以版本号/CAS 提议变更，事件追加保留来源；聚合器负责冲突裁决。权限采用最小能力令牌，子 Agent 不能继承 Supervisor 的全部权限，也不能自行扩权或生成无限子 Agent；写 Effect 仍经统一 Policy、审批与 Ledger。失败时按 subtask 幂等恢复，过期消息和旧 fence 拒绝。指标看 schema 失败、状态冲突、越权、孤儿任务和重复 Effect。当前项目只有 ExecutionContext/Profile/Policy 等基础控制面，尚无这套 Multi-Agent 消息与共享状态协议。

### 58. 为什么多个角色 Prompt 相互聊天通常不等于有效协作？

角色 Prompt 只制造语言上的分工，并未提供信息互补、独立权限、状态所有权、可验证接口或故障隔离；若多个角色共享同一模型、上下文和错误证据，其错误高度相关，互相赞同还会放大幻觉。聊天轮数增加会带来 token、延迟、上下文污染和不可终止问题。有效协作应有可分解任务、结构化交付物、独立数据或工具、确定性聚合器、全局预算和对照 Eval；若没有这些，单 Agent 加 verifier 或普通 DAG 更可靠。恢复要能定位并重跑单个子任务，而非重演整场对话。指标看相对单 Agent 的增量成功率、错误相关系数、通信开销与无效轮次。当前项目明确停止增加无 Eval 的角色，采用共享 Harness Profile，而非把“角色聊天”包装成平台能力。

### 59. Reviewer Agent 如何避免与执行 Agent 产生相关错误？

Reviewer 要尽量独立：使用不同模型/Prompt 或确定性规则，隐藏执行者的结论性措辞，优先读取原始需求、Diff、测试和证据，而非其自我总结；权限上只读，不能直接批准自己的修改。检查标准应版本化并输出结构化 issue、证据位置和置信度，关键风险仍由确定性测试、安全扫描或人工审批裁决。完全独立会增加成本，也可能因缺上下文误杀，因此可分层：便宜规则先行，高风险才调用独立 Judge，人审抽检校准。失败时保留 Reviewer 版本并允许复核，不让“review 通过”覆盖硬门禁。指标包括漏检/误杀、与执行者错误相关性、发现有效缺陷数和成本。当前 Coding 场景只有 Fake review/工件，不能声称真实仓库 Reviewer 效果。

### 60. 如何公平比较单 Agent 和 Multi-Agent 的效果、成本与延迟？

使用同一版本化任务集、隐藏集、工具权限、知识快照和成功判定，固定或分层随机模型版本与采样；按任务难度配对运行，多次重复并报告置信区间，而不是挑成功案例。Multi-Agent 的全部 Planner、子 Agent、Reviewer、通信和失败重试 token 都计入端到端成本，延迟同时报告 p50/p95、关键路径与并行资源峰值。质量除最终成功率，还看 Tool 正确性、事实性、风险事件、人工介入和错误相关性。设置预先发布阈值，例如成功率显著提升或高风险率显著下降，否则不上线；还要做消融，区分收益来自更多计算还是协作结构。当前路线建议至少 50 条同集对照，但仓库尚无该实验结果，不能把建议阈值当成已取得指标。

### 61. **[故障]** Planner 不断生成子 Agent 导致成本失控，如何治理？

先用 Trace 检查递归深度、重复目标、子任务去重、未回收任务和预算传播，暂停新 spawn 并取消可取消子任务；已发出的 Tool Effect 仍按 Ledger 确认。根因常是 Planner 没有完成判定、子任务粒度过细、失败反馈又触发同类分解。治理必须在 Harness 而非 Prompt：全局 token/金额/墙钟/并发上限、最大深度和子任务数；每个 spawn 需结构化目标、收益估计、唯一键和父预算划拨；相同目标去重，连续无进展触发熔断或人工审批。恢复时保留已完成 Artifact，只重规划未完成部分。指标包括 fan-out 倍数、深度、单位成功成本、无进展轮次和取消传播时延。当前 Harness 已有 turn/tool/context 预算，但没有生产 Multi-Agent spawn 控制。

### 62. **[反例]** 给出一个应取消 Multi-Agent 并退回单 Agent/DAG 的案例和证据。

例如企业客服“PII 脱敏→知识检索→合规校验→回复草稿→低置信转人工”被拆成五个角色 Agent 互聊。任务拓扑固定、每步有确定性安全不变量，角色没有独立数据或权限；实验若显示相对 DAG 成功率无显著提升，p95 从 3 秒增至 12 秒、token 成本翻数倍，且交接漏掉 PII/引用，就应取消 Multi-Agent。退回 DAG 管强顺序、脱敏、合规和审批，只在“生成草稿”节点使用单 Agent，并保留确定性 verifier。迁移时固定输入输出 schema、回放历史样本、验证工件等价和安全门禁，失败可回滚旧版本。持续观察成功率、泄漏率、人工率、p95 与成本。当前项目客服本就是服务内固定流程/Fake 外部能力，更不应虚构 Multi-Agent 收益。
