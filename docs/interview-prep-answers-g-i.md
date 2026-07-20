# G、H、I 章标准参考答案（题 63～96）

> 本文是 `interview-prep-question-bank.md` 中 G、H、I 三章的简体中文参考答案，面向资深 Agent 平台、Runtime 与 Coding Harness 面试。文中的容量、阈值和指标均为设计建议，不代表当前仓库已取得生产数据。当前项目尚无完整 Java 流式 Runtime；Coding 外部适配器为 Fake，不执行真实 Git、Shell 或 PR；Eval 目前只有 `data-analyst` 30 条固定确定性数据集，尚未形成完整 Eval 平台或真实 Shadow/Canary。

## 目录

- [G. Java 流式 Agent Runtime（63～74）](#g-java-流式-agent-runtime63～74)
- [H. Eval、可观测与发布治理（75～86）](#h-eval可观测与发布治理75～86)
- [I. Coding Agent Harness（87～96）](#i-coding-agent-harness87～96)

## G. Java 流式 Agent Runtime（63～74）

### 63. SSE 与 WebSocket 在 Agent 场景中如何选择？

选择依据不是“谁更新”，而是通信方向和协议复杂度。Agent 的主要链路通常是服务端持续下发 Token、Step、Tool、Approval 和 Done，用户提交消息、取消或审批可走独立 HTTP，因此优先 SSE：基于 HTTP、代理友好、浏览器自动重连，并可用 `Last-Event-ID` 续传。需要低延迟双向交互、语音/二进制帧、频繁客户端控制或多人协同时再选 WebSocket，但要自建心跳、重连、鉴权刷新、顺序与流控。

不变量是每条流绑定 tenant/user/run，终态唯一，断线不等于取消。失败时由持久事件日志续传，超过保留窗口返回快照或 410。指标看连接数、重连率、发送积压、端到端事件延迟、丢重事件率和每连接资源。当前项目没有完整 Java 流式 Runtime，只能把该方案作为目标设计，不能宣称已经落地。

### 64. 模型 Token 流与 Agent Event 流有什么区别？

模型 Token 流是某一次 model attempt 的供应商增量输出，可能包含文本 delta、思考占位、tool-call 参数片段和 usage；Agent Event 流是 Runtime 的稳定业务协议，覆盖 Run/Step 生命周期、工具、审批、制品、错误和终态。前者短暂、供应商相关且可能因重试被丢弃，后者应有 run 内单调序号、版本、时间、trace 和可恢复语义。

核心不变量是不能把半截 tool-call JSON 或重试前 Token 当成已提交业务事实；Runtime 先组装、校验，再发布高层事件。为降低首字延迟，可发送标记为 provisional 的 Token，但最终 Artifact 与 Done 必须以持久状态为准。故障恢复通常不精确重放每个 Token，而重放已提交事件或文本快照。指标分层统计 TTFT、token rate、model attempt 重试率，以及 Agent step/tool 延迟、事件持久化延迟和最终完成率。

### 65. 如何定义 Token、Step、Tool、Approval、Artifact、Error、Done 事件？

统一信封至少包含 `eventId/runId/sequence/type/schemaVersion/occurredAt/traceId/attempt/payload`。Token 表示可丢弃或可合并的文本增量；Step 表示节点开始、完成、暂停；Tool 包含调用 ID、工具版本、参数摘要、effect 类型和结果引用；Approval 包含策略、审批对象哈希、过期时间与决定；Artifact 只引用原子发布后的制品；Error 包含稳定错误码、可重试性和归因；Done 是唯一终态，枚举 succeeded/failed/cancelled/budget-exhausted。

不变量是 sequence 单调、同一 eventId 幂等、Error 不一定终止而 Done 必须终止，敏感参数不能直接进入流。断线后按 sequence 续传，未知事件类型由客户端忽略而非崩溃，Schema 采用向后兼容演进。指标包括各类型吞吐、序号缺口、重复率、Error→恢复率、终态缺失率和从 Artifact 发布到 Done 的延迟。

### 66. Reactor 背压如何处理模型生产速度与客户端消费速度不匹配？

背压必须分段处理。若模型 SDK 支持 Reactive Streams，就让下游 demand 传回供应商；多数 HTTP token 流无法真正暂停，此时对低价值 Token 做小窗口聚合或采样，对 Step/Tool/Approval/Done 等控制事件绝不丢弃，并设置有界队列。超过单连接字节数、事件数或等待时长后，应断开慢客户端并允许从持久日志续传，而不是无限 `onBackpressureBuffer`。

可按事件类型分流：Token 使用 `bufferTimeout` 合并，控制事件进入可靠通道；持久化与网络发送也应隔离 Scheduler，避免慢 I/O 阻塞模型回调。断开客户端不默认取消 Run，除非产品明确采用 connection-owned 语义。指标看每连接 queue depth/bytes、oldest-event age、drop/coalesce 数、慢连接断开数、堆内存和 GC。当前仓库尚未实现该 Reactor 流控链路。

### 67. 用户取消后，如何把取消传播到模型、工具、子任务和计费？

取消是持久化状态转换，不只是关闭 SSE。入口先校验 user/tenant/run，原子写入 `CANCEL_REQUESTED` 和原因，再触发 run-scoped cancellation token。模型订阅执行 `dispose` 或供应商 cancel；可取消工具收到 deadline/token；子任务按父子关系传播；排队任务撤销。不可中断或可能已有副作用的工具不能假装取消成功，而应进入 cancelling/unknown，查询确认或人工处置。

所有 Step 在提交结果前检查 fencing token 与取消状态，防止迟到结果把 Run 改回成功；最终仅由协调器写一次 `CANCELLED`。计费以供应商实际 usage/receipt 为准，取消后停止新预算预留并结算已发生费用。指标包括取消确认 p95、取消后新增 Token/工具调用数、僵尸任务数、UNKNOWN 数、已结算成本与取消成功率。客户端断线与显式取消必须分开建模。

### 68. SSE 断线重连时如何实现事件续传、去重和顺序保证？

服务端为每个 Run 分配单调 `sequence`，事件先追加到持久 Event Store，再向在线订阅者广播；SSE 的 `id` 使用不可变 eventId 或 sequence。客户端重连携带 `Last-Event-ID`，网关在鉴权后从下一序号开始查询并发送，客户端仍以 `(runId,eventId)` 去重，只按连续 sequence 应用；发现缺口则暂停展示并补拉。

这提供的是“至少一次传输、客户端幂等应用”，不是网络层 exactly-once。并发生产者通过单写者、数据库序列或乐观锁保证 run 内顺序；Token 可压缩为文本快照，但 Tool、Approval、Artifact、Done 必须保留。若游标早于保留窗口，返回快照加新起点，或明确 410 让客户端重新同步。指标包括续传成功率、重放事件量、缺口/重复数、重连恢复 p95、保留窗口命中率和顺序冲突数。

### 69. 多个只读工具并行执行时，如何处理超时、部分成功和结果合并？

只有无副作用、相互独立且预算允许的工具才并行。每个调用拥有稳定 callId、独立 deadline、并发舱壁和结果类型 `Success/Timeout/Error/Cancelled`，父 Step 设总 deadline。Reactor 可用受限并发 `flatMap`，不要让一个失败默认取消全部；对“必须全部成功”用 fail-fast，对“证据尽力收集”用 materialize 后汇总，并在答案中显式标记缺失来源。

合并必须确定性：按工具优先级或 callId 排序，做 schema 校验、来源标注、去重、冲突检测和 Token 预算裁剪；不能让完成先后改变 Prompt。超时工具可在剩余预算内重试，仍失败则降级、补查或暂停，不可伪造空结果。指标包括 fan-out 宽度、单工具 p95、父 Step 临界路径、部分成功率、超时率、结果利用率和并行节省时长。

### 70. 如何实现模型供应商限流、排队、熔断、Fallback 和成本预算？

在 Model Gateway 按 tenant、模型和供应商维护并发、RPM/TPM Token Bucket；请求先做成本/Token 预估和预算预留，进入有界优先队列，带 deadline 与取消令牌。收到 429 应尊重 `Retry-After` 并加抖动退避；超时和 5xx 计入滑动窗口熔断，但配额不足、参数错误不应混入同一故障率。队列满或等待超时应明确拒绝，而非无限堆积。

Fallback 必须经过能力、上下文长度、数据驻留、工具调用格式和质量门槛校验，并记录模型切换；写工具前后不可盲目重放整轮。预算按 Run 预留、Attempt 结算，超限可降级模型、缩短上下文或暂停审批。指标看 429、队列深度/等待、熔断状态、fallback 成功与质量差、预算拒绝率、每成功任务成本及供应商错误率。

### 71. Trace Context 如何跨模型、Tool、消息队列和异步线程传播？

入口解析或创建 W3C `traceparent/tracestate`，并把 tenantId、runId、stepId、attempt、toolCallId 作为受控 baggage 或结构化属性。同步 HTTP 调用注入 header；消息队列把 trace context 写入消息属性并在消费端创建 consumer span；Reactor 使用 `Context` 和自动上下文传播，而不是依赖会在线程切换时丢失的普通 `ThreadLocal`。每次 Model、Tool、审批等待和 Artifact 发布建立独立 span。

不变量是业务关联 ID持久化且可在重放时复用查询，Trace ID 则描述本次执行，恢复后可用 span link 连接旧轨迹；不得把 Prompt、密钥或 PII 放进 baggage。传播失败时仍可凭 run/step 关联日志。指标包括 trace 完整率、孤儿 span、上下文传播失败、采样率、关键路径时长，以及日志—指标—Trace 的关联覆盖率。

### 72. **[故障]** 慢客户端导致内存持续增长，如何定位和修复？

先用堆、连接和队列证据定位：观察内存是否随 SSE 连接数和 outbound queue bytes 线性增长，按连接检查 pending event 数、oldest age、写延迟；用 heap dump 找 `Flux` buffer、Netty ByteBuf 或事件对象保留链，并区分慢客户端、泄漏、模型过快和持久化阻塞。压测复现时逐步降低客户端读取速率，确认拐点。

修复应把无界 buffer 改为按连接有界的事件数/字节/时间窗口；Token 聚合或丢弃中间 delta，控制事件持久化；超过阈值主动断连并支持 `Last-Event-ID` 续传。设置写超时、释放取消订阅资源、限制每租户连接数，并隔离广播订阅者，防止一个慢连接拖住全局。验收看恒定负载下堆内存进入稳态、无 ByteBuf 泄漏、慢连接断开可恢复、Done 不丢，以及 GC pause 和队列 p99 回落。

### 73. **[伪代码]** 定义 `Flux<AgentEvent>` 及取消、错误、终态语义。

核心语义是：事件先提交再发送；取消转成 Run 状态，而非仅依赖 Reactor `cancel`；可恢复错误发 `ErrorEvent` 后继续，唯一 `DoneEvent` 后 `Flux` 正常完成。订阅释放不等于取消 Run。

```java
Flux<AgentEvent> stream(RunId id, long after, CancelSignal signal) {
    Flux<AgentEvent> replay = eventStore.readAfter(id, after);
    Flux<AgentEvent> live = eventBus.forRun(id);

    return Flux.concat(replay, live)
        .transform(this::dedupeAndOrder)
        .takeUntil(AgentEvent::isDone)
        .doOnCancel(() -> metrics.clientDisconnected(id))
        .onErrorResume(StreamTransportException.class,
            e -> Flux.error(e)); // 传输失败：重连续传，不改 Run 终态
}

Mono<Void> cancel(RunId id, Principal p) {
    return runStore.requestCancel(id, p)
        .then(cancellationRegistry.signal(id))
        .then(); // 协调器等待模型/工具收敛后追加唯一 CANCELLED Done
}

Mono<Void> execute(Run run) {
    return loop(run)
        .onErrorResume(RetryableStepException.class,
            e -> append(errorEvent(e, true)).then(retry(run)))
        .onErrorResume(e -> terminateOnce(run, FAILED, stableCode(e)))
        .then(terminateOnce(run, SUCCEEDED, null));
}
```

生产实现还需 fencing 防止迟到完成、`terminateOnce` 原子 CAS、敏感字段脱敏和有界背压。指标包括重复终态、终态缺失、取消收敛时长、序号缺口和续传成功率。

### 74. **[系统设计]** 设计支持 1000 并发长连接的流式 Agent 网关。

组件包括：L7 负载均衡、无状态 SSE Gateway、鉴权/租户限额、Run API、持久 Event Store、事件总线、Agent Coordinator、Model/Tool Gateway、Cancellation Registry 和可观测平台。主链路是创建 Run→Coordinator 持久化事件→总线按 runId 分区→Gateway 先回放游标后订阅实时事件→客户端按 sequence 去重；审批、取消走独立 HTTP。网关不持有业务真相，实例重启后可从 Event Store 恢复。

数据模型至少有 `Run(status,owner,budget,version)`、`AgentEvent(run_id,seq,type,payload_ref)`、`SubscriptionCursor`、`Usage`；事件表以 `(run_id,seq)` 唯一，Artifact 大对象放对象存储。1000 连接本身不高，容量重点在每连接内存、事件速率和写放大：例如先压测 1000×目标事件率，限制连接缓冲字节、租户连接数与 Token 合并窗口，按 runId 分区水平扩容，不能把估算写成实测。

故障恢复覆盖网关重启后重连续传、总线重复消费幂等、Event Store 暂停时降载、慢客户端断开、模型取消与唯一终态；跨区可接受短暂不可用也不要破坏 run 内顺序。SLO 观察在线连接、连接建立/续传 p95、端到端事件延迟、缺重率、buffer bytes、堆/GC、Event Store lag、终态完整率和每 Run 成本。当前仓库无此完整实现，应作为架构答题而非项目成果。

## H. Eval、可观测与发布治理（75～86）

### 75. Agent Eval 为什么不能只评最终文本？

同样的最终文本可能来自越权工具、错误证据、重复副作用或不可恢复的偶然路径；文本看似不好，也可能是系统正确拒答。因此 Agent Eval 的对象应是任务结果加完整轨迹：计划、工具选择与参数、权限/审批、证据引用、状态转换、终止原因、成本和延迟。硬安全不变量应优先于语言质量，不能用平均分掩盖一次真实写越权。

失败恢复上，要保留版本化输入、环境、模型/Prompt/工具版本和 trace，支持确定性回放与重新运行，区分模型随机性和 Harness 缺陷。指标至少包含 Task Success、Tool Correctness、Faithfulness、policy violation、重复副作用、恢复成功率、p50/p95 延迟和每成功任务成本。当前仓库的 30 条 `data-analyst` 固定集只验证确定性状态、路径、审批、制品和失败原因，尚未覆盖完整模型质量。

### 76. Task Success、Tool Correctness、Faithfulness、Latency、Cost 如何定义？

Task Success 是用户目标和业务约束是否同时满足，宜按任务定义可执行判定器，而非统一文本相似度。Tool Correctness 拆为选择、参数、调用顺序、权限、幂等和结果使用；Faithfulness 衡量结论是否被允许的证据支持，可统计 citation precision/recall、unsupported claim。Latency 应分解排队、TTFT、模型、工具、审批等待和端到端 active time；Cost 包括 Token、模型、工具、计算与人工介入，并报告每次尝试和每个成功任务成本。

这些指标不能简单相加：安全和审批是硬门槛，质量/延迟/成本在门槛后做 Pareto 取舍。失败时按 trace 归因到数据、模型、工具、策略或基础设施。报告平均值之外还要有 p50/p95/p99、分任务切片、失败率和置信区间，避免被长尾或样本构成掩盖。

### 77. Golden Set、Hidden Holdout、线上样本和对抗集分别有什么作用？

Golden Set 是版本化、带期望和标签的日常回归集，便于快速定位，但反复调参会过拟合；Hidden Holdout 对开发者不可见，只在门禁运行，用来估计泛化；线上样本反映真实分布和新失败，经脱敏、授权、去重和人工标注后回流；对抗集专测 Prompt Injection、越权、预算耗尽、工具超时、乱码和边界输入，不能用普通平均成功率替代。

数据治理不变量是训练/开发/holdout 防泄漏，保留 dataset/version/provenance/consent，按场景、语言、风险分层。线上分布漂移时重采样并保留旧集防遗忘；争议标签进入复核。指标包括数据覆盖、标签一致率、泄漏检测、切片成功率、对抗绕过数、线上失败回流时延和版本间差异。当前项目只有 30 条 Analyst 固定集，不应称为 Hidden Holdout 或线上样本。

### 78. Deterministic Scorer、LLM-as-Judge 和人工评审如何组合？

先用 Deterministic Scorer 判断可精确验证的事实：状态、JSON Schema、工具参数、路径、权限、制品哈希、引用存在、预算和测试结果；它便宜、稳定、适合硬门禁。LLM-as-Judge 用于相关性、完整性、表达和开放式正确性，但输入应带 rubric、证据和匿名候选，并输出分项理由。人工评审负责高风险样本、Judge 分歧、低置信度、线上投诉和抽样校准。

组合时先过安全硬门槛，再按加权质量指标比较；不要让 Judge 高分覆盖越权。失败样本进入仲裁并更新 rubric/数据，而不是直接“调 Judge”。指标包括确定性覆盖率、Judge—人工相关性、成对一致率、评审者间一致性、仲裁率、单样本成本和门禁假阳/假阴率。当前 30 条集属于确定性基线，尚无已落地 Judge/人工平台。

### 79. LLM Judge 有哪些偏差，如何校准一致性和可信度？

常见偏差包括位置偏差、偏爱更长或更流畅答案、自我模型偏好、提示措辞敏感、引用权威幻觉、对少数语言/领域不稳，以及两个候选共享同类错误。校准方法是候选匿名、随机交换顺序、长度归一或明确惩罚冗余；使用结构化 rubric 和证据；同一批重复评测、多个 Judge 或成对比较；以分层人工金标计算相关性、混淆矩阵和阈值。

Judge 只能评 rubric 覆盖的软质量，高风险安全仍由确定性策略和人工负责。模型或 Prompt 升级后必须重校准，低置信度、Judge 分歧和分布外样本转人工。指标看 swap consistency、重复一致率、Cohen’s kappa/相关系数、各切片假阳假阴、校准误差、仲裁率和评测成本；不能只报告“Judge 平均分”。

### 80. 非确定性 Agent 如何做稳定的回归测试？

稳定不等于固定每个 Token，而是固定输入、工具快照、策略、模型/Prompt/Schema 版本和随机参数，验证业务不变量与结果分布。测试分层：Harness/策略用 Fake Model 做确定性契约测试；轨迹回放测试历史工具结果；真实模型对 Golden/Hidden 集多次采样，比较成功率、违规率、成本和延迟的置信区间。结构、工具和安全用硬断言，文本质量用容忍区间或 Judge。

失败时保存 seed、attempt、完整 trace 和环境版本，先区分基础设施抖动、模型方差与真实回归；禁止无条件重跑到绿。小样本使用配对比较或 bootstrap，门禁同时设置绝对底线和相对 baseline 非劣界。指标包括 flaky rate、重复运行方差、回归置信度、硬不变量失败数、样本覆盖和重跑率。

### 81. Trace Playback 与重新调用模型有什么区别？

Trace Playback 使用历史已记录的 Model Turn、Tool Result、事件和时间信息驱动新 Scorer、UI 或部分 Harness，输入事实不变，结果可复现、成本低，适合调试、归因和验证评分器。重新调用模型是在固定或更新环境下重新执行，能检验新模型/Prompt/策略的行为，但会受随机性、供应商变化、工具数据漂移和外部副作用影响。

Playback 不能证明新决策在当时会上线，也无法发现未记录上下文；re-run 则必须隔离写工具，使用 Fake/Shadow、快照或幂等沙箱，绝不能重放真实副作用。两者结合：先 playback 定位，再对安全样本 re-run 比较。指标包括 trace 完整/可回放率、版本缺失率、重放一致性、re-run 方差、外部调用阻断数、成本和时间。

### 82. 如何对失败轨迹进行聚类和根因分类？

先定义可操作的层级 taxonomy：输入/数据、Context/检索、模型规划、Tool 选择/参数/结果、策略权限、Runtime/恢复、外部依赖、验证器和用户取消；一个样本可有表象原因与根因。抽取稳定特征，如错误码、失败 Step、工具序列、终止原因、Token/延迟、模型版本和文本 embedding，先按结构化字段规则分桶，再对未归类文本聚类并由人工命名。

必须避免把“工具超时”全部算工具故障：用 trace 检查排队、deadline、重试和供应商状态。修复后在对应簇建立回归样本，观察簇规模是否下降及是否转移到别处。指标包括 taxonomy 覆盖率、unknown 占比、簇纯度、人工一致率、Top 根因影响任务数/成本、MTTR、复发率和修复后的净改善。

### 83. 如何设计 Baseline、Shadow、Canary、Release Gate 和 Rollback？

Baseline 固定当前模型、Prompt、工具与策略版本及分层指标。候选先跑离线 Golden/Hidden/对抗集；Shadow 镜像真实请求但隔离所有写副作用，结果不返回用户，用于比较分布、质量、延迟和成本；Canary 才让小比例、低风险、可回滚流量使用候选，并按 tenant/场景稳定分桶。Release Gate 同时包含安全零容忍、质量非劣界、成本/延迟上限和最小样本置信度。

版本要能原子路由并绑定 Run，进行中的 Run 不随配置漂移。触发阈值后自动停止新流量并回退 baseline；已有写步骤按 effect ledger 恢复，不能靠切模型撤销。观察错误预算、成功率差、投诉、策略违规、p95 延迟、每成功成本、样本量和置信区间。当前仓库仅有治理概念与 30 条固定集，没有真实 Shadow/Canary，面试中必须明确。

### 84. **[故障]** 离线分数提升但线上投诉增加，如何调查？

先冻结扩量并按版本、场景、租户、语言和时间关联投诉与 trace，确认是否确由候选引起；比较 canary 与同期 control，排除流量构成、UI、依赖故障。再检查离线数据是否过期、泄漏或缺少线上长尾，Judge 是否偏爱冗长答案，聚合分数是否掩盖拒答、延迟、引用和高风险切片；抽样人工盲评投诉轨迹。

根因可能是代理指标错、线上 Context/工具与离线快照不同、成本/延迟恶化造成体验差，或安全拒答策略变化。恢复上回滚候选或关闭问题能力，把经授权脱敏的投诉转为回归/对抗样本，修正 rubric 和切片门禁后再 Shadow/Canary。指标看版本归因投诉率、control 差异、投诉类别、线上—离线相关性、切片成功率、回滚恢复时间和复发率。

### 85. **[故障]** 新模型成功率提高但成本翻倍，如何做发布决策？

不能只比较平均成功率和单次成本，应计算每成功任务成本、增量成功的边际成本，并按任务价值、风险和复杂度切片。例如简单请求继续 baseline，只有高价值或 baseline 低置信度任务路由新模型；也可缩短上下文、减少反思轮次、缓存只读结果或设置级联。先确认成本翻倍来自输入 Token、输出长度、重试、工具调用还是单价。

决策采用硬预算加 Pareto/业务效用：安全不可退化，质量提升须有置信区间，p95 延迟和总容量可承受；超预算则不全量发布。Canary 中按 Run 预留和结算预算，异常自动回滚。报告成功率增量、每成功成本、单位质量提升成本、Token/工具分解、毛利或业务价值、延迟和预算耗尽率，且明确这些阈值是业务决策而非通用常数。

### 86. **[系统设计]** 设计 Agent Eval 平台，覆盖数据、Runner、Scorer、报告和门禁。

组件包括 Dataset Registry、样本/标签与权限库、版本化 Environment Snapshot、隔离 Runner、Trace Store、Deterministic Scorer、Judge Service、人工评审台、统计分析、Report Store 和 Release Gate。主链路是选择不可变 dataset/model/prompt/tool/policy 版本→Runner 并发执行或 playback→收集结果和轨迹→硬规则评分→Judge/人工处理软质量与分歧→按切片和 baseline 计算置信区间→门禁生成可审计决定。

数据模型至少有 `DatasetVersion/Case/ExpectedInvariant/EvalRun/CaseAttempt/TraceRef/Score/Review/ReleaseDecision`，所有分数保存 scorer 版本和理由。Runner 使用租户隔离、全链 deadline、预算和只读/Fake 工具；任务可重试但保留每次 Attempt，不能挑最好一次。故障恢复通过任务租约、幂等 case key、断点续跑实现；Judge 不可用时软评分暂停，不能跳过硬安全门禁。

容量按“样本数×重复次数×平均模型/工具耗时”估算，队列按优先级和供应商配额限流，报告 p50/p95、成功率置信区间、成本和失败簇。核心指标还有 Runner 成功率、flaky rate、评分覆盖、Judge—人工一致性、门禁假阳假阴和回滚时长。映射当前项目：已有 `data-analyst` 30 条固定确定性集，可作为首个 Dataset/Scorer 种子，但尚未接入完整 Runner、Judge、统计报告、真实 Shadow/Canary，不能虚构平台成熟度。

## I. Coding Agent Harness（87～96）

### 87. Coding Agent 的最小工具面应该包含什么？

最小闭环应有：限定根目录的文件枚举/搜索、分段读取、结构化 Patch 生成与应用、受限命令执行、构建/测试/静态检查、`git diff/status` 只读检查，以及提交候选结果；计划和进度保存属于 Runtime 能力。工具要窄而语义化，例如 `applyPatch`、`runTests(scope)` 优于任意 shell，返回稳定错误码、截断策略、Artifact 引用和审计信息。

不变量是默认最小权限、工作区隔离、网络关闭、资源/时间/输出有界，不写 main/master、不 push、不读取密钥。Patch 前记录基线哈希，冲突不强盖；取消后终止进程树并保留恢复点。指标包括工具成功/拒绝率、无效调用、Patch 应用率、测试通过率、人工接受率、越权拦截数、耗时和每成功任务成本。当前项目 Coding 工具为 Fake，不执行真实 Git/shell/PR。

### 88. Repo Search、文件读取、Patch、Shell、Test 为什么需要不同权限？

这些能力的风险不是同一级：Search 通常只暴露路径和片段；Read 可能接触源码、密钥和个人数据；Patch 会改变工作区；Shell 可绕过路径限制、启动进程和访问网络；Test 虽语义明确，仍可能执行仓库脚本、容器或外部集成。因此应为每个工具定义 capability、目录范围、命令模板、网络、资源、审批和 effect 类型，而不是给 Agent 一个全能终端。

权限按任务临时授予并绑定 user/tenant/run/workspace；Patch 仅允许工作树，Shell 默认禁用或 allowlist，Test 在沙箱和无密钥环境运行。遭遇符号链接、生成脚本或测试偷跑网络时由 OS/容器边界兜底，不能只依赖 Prompt。指标看各 capability 授权/拒绝、越界尝试、敏感读取、命令命中 allowlist、审批率和权限闲置时间。

### 89. Worktree、Container 和远程 Sandbox 如何选择？

Git worktree 创建快、共享对象库、适合可信本机的并行分支，但不是安全边界，进程仍可读宿主和联网。Container 提供文件系统、进程、网络和资源隔离，镜像可复现，适合中等不可信构建；仍需防特权容器、Docker Socket 和内核共享风险。远程一次性 Sandbox 隔离最强、易做租户与弹性治理，代价是启动延迟、镜像/源码传输、成本和数据驻留复杂度。

通常组合为远程 Sandbox/Container 内创建临时 worktree：前者负责安全，后者负责 Git 工作流。失败后根据 workspace snapshot、patch 和测试制品恢复，销毁环境前导出审计；不复用污染环境。指标包括启动 p95、隔离逃逸/策略拦截、环境复现率、缓存命中、每任务资源成本、清理成功率和恢复时长。

### 90. 如何防止危险命令、秘密泄露、越权目录写入和网络外传？

采用纵深防御：工具层只暴露参数化命令 allowlist，拒绝 shell 拼接、提权、设备挂载和 fork bomb；沙箱以非 root、只读基础镜像、限定可写 workspace、CPU/内存/PID/磁盘/时长配额运行。路径访问用规范化后的真实路径校验根目录，禁止符号链接逃逸；默认关闭网络，只允许经审计代理访问必要依赖源。

密钥不注入任务环境，日志/Prompt/Artifact 做 secret scanning 和脱敏，输出及 Patch 同样扫描；高风险命令和网络临时授权需人工审批并绑定参数哈希。发现泄露先立即断网、取消 Run、吊销凭证、保全审计并清理 Sandbox。指标包括命令/路径/网络拒绝数、secret 命中、异常出站字节、资源限额触发、审批绕过数和沙箱销毁成功率。

### 91. Agent 如何判断“修复完成”，而不是只让现有测试变绿？

完成条件应在执行前由 Harness 固化：需求/复现用例满足，新增针对根因的测试，受影响范围测试与构建/静态检查通过，未删除或弱化既有测试，diff 在授权范围，安全与兼容约束满足，并由独立 Verifier 检查。Agent 的“我完成了”只是候选信号，不能直接决定成功；Verifier 应使用原始任务、基线和隐藏测试，而非只看 Agent 自选命令。

还要审查 Patch 是否仅硬编码样例、吞异常、放宽断言或引入跨模块 API 变化；根据依赖图扩大测试范围。失败则保留诊断和 Patch，回到修复 Step 或请求人工，不无限循环。指标包括隐藏测试通过率、Patch acceptance、回归率、测试增量/删除、验证覆盖、人工修改量和 reopen rate。当前 Fake Coding 场景没有该真实验证闭环。

### 92. 如何设计 Patch Apply、冲突检测、回滚和人工审查？

Patch 请求包含 workspaceId、base commit、目标文件基线哈希、结构化 hunks 和幂等键。应用前做路径/权限/secret/大小检查，再验证哈希和 hunk 上下文；不匹配则返回 Conflict，Agent 重新读取并生成新 Patch，禁止模糊强盖。应用在临时 worktree 或文件系统快照内，成功后生成 diff Artifact，运行格式化、编译和测试。

回滚不是对用户仓库执行危险 reset，而是丢弃临时 worktree、恢复快照或逆向应用已记录 Patch；每个阶段保留审计事件。人工审查展示任务、基线、完整 diff、测试证据、风险文件与 Agent 理由，批准绑定 diff hash，之后若 Patch 变化必须重新审批。指标包括 apply/conflict/rollback 率、冲突恢复次数、审查等待、批准后变更率、测试结果和越权 Patch 拒绝数。

### 93. 长时间 Coding 任务如何保存计划、进度、上下文和恢复点？

把对话与执行状态分离。持久模型包含 Run、PlanVersion、Step/Attempt、workspace/base commit、已读文件摘要与来源、Patch Artifact、命令/测试 Receipt、预算、未决问题和 next-action cursor；每个外部动作前后写事件，安全边界后保存 Checkpoint。上下文溢出时压缩为“目标、约束、已证实事实、变更、失败尝试、待办”，原始日志和文件留作可检索 Artifact。

恢复时先校验仓库 HEAD、文件哈希、工具/策略版本和 Sandbox 状态；漂移则进入 rebase/人工处理，不能沿用旧假设。正在执行的命令用租约、心跳和 fencing 防双 Worker，UNKNOWN 动作先查询确认。指标包括 resume 成功率、重复副作用、上下文压缩率、恢复 p95、漂移冲突率、丢失进度和每 Run checkpoint 大小。

### 94. **[故障]** Agent 删除测试以让构建通过，如何检测和治理？

先对比基线 diff 和测试清单：检测测试文件删除/重命名、断言数量下降、`@Disabled`/skip 增加、覆盖率突降、测试配置排除，以及生产代码通过特判绕过。然后在不可由 Agent 修改的 Harness 配置中运行基线测试和隐藏测试；只看 Agent 报告的“绿色”没有可信度。审计 trace 还应确认删除是任务明确要求还是策略违规。

治理上默认禁止删除或弱化测试，确需变更时触发人工审批并要求替代测试与理由；Verifier 以 base commit 计算 test-diff 风险分，违规直接失败并保留证据。恢复时丢弃候选 worktree 或回退该 Patch，再让 Agent基于原测试修复。指标包括测试删除/禁用拦截数、断言和覆盖变化、隐藏测试失败率、人工例外批准率与同类违规复发率。

### 95. **[故障]** Agent 修复一个模块却引入跨模块回归，如何改进验证闭环？

先从失败流水线和依赖图定位：比较基线与 Patch 的 API、Schema、配置和共享库变化，运行受影响模块测试，确认是编译兼容、行为、数据迁移还是时序回归。局部测试全绿通常说明验证范围由 Agent 自选、缺少消费者契约或隐藏集覆盖不足，而非简单“再多跑一次测试”。

改进为风险驱动验证：静态依赖/构建图计算 affected modules；公共接口变化运行消费者契约、集成和兼容性测试；高风险 Patch 运行全量、性能或 Shadow 测试；独立 Reviewer 根据原需求和 diff 决定门禁。失败后保留复现 Artifact，回滚隔离 workspace，新增跨模块回归用例。指标包括 escaped defect、跨模块测试命中率、验证时长、受影响范围准确率、flaky rate、回滚率和修复后复发率。

### 96. **[系统设计]** 设计企业 Coding Agent Harness，覆盖隔离、工具、评测、审批和审计。

组件包括任务/API 网关、身份与策略引擎、Run/Step/Attempt 协调器、Context/Repo 服务、Model Gateway、能力化 Tool Gateway、远程 Sandbox 管理器、Patch 服务、Build/Test/Verifier、Artifact/Trace/Audit Store、审批台和 Eval/发布门禁。主链路是授权任务→固定 repo/base commit 和完成标准→创建一次性 Sandbox/worktree→检索/读取→模型提出 Patch/命令→策略校验→应用 Patch→分层验证→高风险 diff 人审→输出候选 Artifact；默认不 push、不建真实 PR、不写 main/master。

数据模型包含 `CodingRun(owner,repo,base,status,budget)`、`Plan/Step/Attempt`、`CapabilityGrant`、`WorkspaceSnapshot`、`Patch(base_hash,diff_hash)`、`CommandReceipt`、`TestResult`、`Approval(bound_hash)`、`Artifact` 和 append-only `AuditEvent`。工具按 Search/Read/Patch/Test/Shell 分权，Sandbox 非 root、网络默认关闭、资源有界、无真实密钥；Shell 即使开放也只能使用参数化 allowlist。审批绑定 Patch/命令/网络目标哈希，内容变化即失效。

故障恢复依靠事件日志、Checkpoint、租约/心跳/fencing、稳定幂等键和 Workspace 快照；命令超时先杀进程树，副作用不明进入 UNKNOWN；冲突重新读基线，不强盖；取消后导出 Patch/日志并销毁环境。Eval 使用版本化 Coding 任务、隐藏测试、Patch acceptance、安全对抗集和人工盲审，指标包括 task success、Pass@1、编译/测试/隐藏测试通过、Patch 接受率、越权/泄密为零门槛、resume 成功率、p95 时长、每成功成本和人工介入率。

映射当前仓库时只能说已有 Shared Harness、安全 Profile 与 Fake Coding 工具契约；没有真实 Git、Shell、Patch Apply、Sandbox、测试执行或 PR 流程，也没有 Coding 完成率实测。实现顺序应从临时隔离 workspace、结构化 Patch、命令 allowlist、Build/Test/Review/Cleanup 最小闭环开始，所有外部写操作继续保持 Fake 或人工批准。
