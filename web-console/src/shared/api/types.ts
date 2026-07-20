/**
 * Web Console 与后端 JSON API 之间共享的 TypeScript 数据契约。
 *
 * 重要限制：
 * - 类型只提供编译期提示，不会校验运行时响应形状
 * - 不能替代服务端输入验证、权限控制与租户隔离
 * - 可选字段（如 Evolution 相关 [key: string]: unknown）表示前端容忍后端扩展字段
 */

/** Agent 执行模式：对话 / 计划执行 / 目标驱动 / Graph 编排。 */
export type ExecutionMode = 'CHAT' | 'PLAN_EXECUTE' | 'GOAL' | 'GRAPH'

/** 持久化 Run 的生命周期状态；WAITING_APPROVAL 表示需人工审批后才能继续。 */
export type RunStatus = 'PENDING' | 'PLANNING' | 'WAITING_APPROVAL' | 'PAUSED' | 'RUNNING' | 'VERIFYING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'BUDGET_EXCEEDED' | 'STEP_LIMIT_EXCEEDED'

/** 风险分级：REGULATED 通常触发更严格的审批与进化约束。 */
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'REGULATED'

/** 受治理进化级别：L0 无自进化，L3 在 allowlist 内可自动晋升（服务端强制执行）。 */
export type EvolutionLevel = 'L0' | 'L1' | 'L2' | 'L3'

/** 平台注册的数字员工（Agent）定义快照。 */
export interface AgentDefinition {
  id: string
  name: string
  description: string
  roleName: string
  riskLevel: RiskLevel
  evolutionLevel: EvolutionLevel
  status: 'DRAFT' | 'ACTIVE' | 'DEPRECATED' | 'REVOKED'
  version: string
  executionModes: ExecutionMode[]
  createdAt: string
  updatedAt: string
}

/** 创建 Agent 时客户端可提交的字段子集（不含 id/status 等服务端生成字段）。 */
export type CreateAgentRequest = Pick<AgentDefinition, 'name' | 'description' | 'roleName' | 'riskLevel' | 'evolutionLevel' | 'executionModes'>

/** 持久化 Agent 运行实例；costUsd 与 maxCostUsd 用于预算终止。 */
export interface AgentRun {
  id: string
  agentId: string
  executionMode: ExecutionMode
  goal: string
  status: RunStatus
  currentStep: number
  maxSteps: number
  maxCostUsd: number | null
  costUsd: number
  tenantId: string
  version: number
  createdAt: string
  updatedAt: string
  timeoutAt: string | null
}

/** 创建 Run 的请求体；可选字段由服务端填充默认值并校验上下界。 */
export interface CreateRunRequest {
  agentId: string
  executionMode: ExecutionMode
  goal: string
  maxSteps?: number
  maxCostUsd?: number
  timeoutSeconds?: number
}

/** 运行事件：append-only 审计流中的一条记录，含幂等键与 payload 哈希。 */
export interface RunEvent {
  id: string
  tenantId: string
  runId: string
  stepId: string | null
  sequence: number
  type: string
  idempotencyKey: string
  payload: string
  payloadHash: string
  version: number
  createdAt: string
}

/** 检查点：可恢复执行的持久化状态快照。 */
export interface RunCheckpoint {
  id: string
  tenantId: string
  runId: string
  stepId: string
  sequence: number
  version: number
  state: string
  stateHash: string
  createdAt: string
}

/** 人工审批请求：actionParameters 与 actionHash 绑定，篡改参数后批准应被服务端拒绝。 */
export interface ApprovalRequest {
  id: string
  tenantId: string
  runId: string
  stepId: string
  sequence: number
  version: number
  actionType: string
  actionParameters: string
  actionHash: string
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'
  requestedAt: string
  expiresAt: string
  decidedAt: string | null
  decidedBy: string | null
  decisionReason: string | null
}

/** Chat 会话元数据；不含历史消息体（本 Console 未实现消息回放 API）。 */
export interface Session {
  id: string
  createdAt: string
  lastActiveAt: string
  model: string
  title: string
}

/** 单次 Chat 往返响应；tokens 为各维度 token 计数 map。 */
export interface ChatResponse {
  sessionId: string
  reply: string
  model: string
  tokens: Record<string, number>
}

/** Graph 节点：type 决定运行时解释器分支，reference 指向版本化资产。 */
export interface GraphNode { id: string; type: string; reference: string }
/** Graph 边：condition 为转移条件表达式（服务端解析）。 */
export interface GraphEdge { from: string; to: string; condition: string }
/** 提交 Graph 校验的完整请求体。 */
export interface GraphRequest {
  name: string
  version: string
  entryNode: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  limits: { maxSteps: number; maxIterations: number; maxCostUsd: number }
}
/** Graph 校验结果：errors 阻断执行，warnings 仅提示。 */
export interface GraphValidationResult { valid: boolean; errors: string[]; warnings: string[] }

/** 版本化资产引用：key@version 形式 pin。 */
export interface VersionRef { key: string; version: string }
/** 场景 RolePack：聚合 AgentSpec、规则、技能、验证器与评测套件引用。 */
export interface RolePack {
  id: string
  name: string
  version: string
  agentSpec: VersionRef
  rules: VersionRef[]
  skills: VersionRef[]
  verifiers: VersionRef[]
  evalSuite: VersionRef
}
/** 场景运行产物：content 可能含 PII 掩码后的 JSON 或文本。 */
export interface ScenarioArtifact {
  id: string
  tenantId: string
  runId: string
  type: string
  name: string
  content: string
  contentHash: string
  createdAt: string
}

/** 治理注册表中的版本化资产；status 流转由服务端状态机控制。 */
export interface VersionedAsset {
  id: string
  tenantId: string
  type: 'PROMPT' | 'RULE' | 'SKILL' | 'GRAPH' | 'VERIFIER' | 'EVAL_SET'
  key: string
  version: string
  parentId: string | null
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED' | 'REVOKED'
  content: string
  contentHash: string
  signature: string
  createdBy: string
  createdAt: string
}

/** Agent 发布清单：assetPins 将运行时代码解析到精确 contentHash。 */
export interface AgentReleaseManifest {
  id: string
  tenantId: string
  agentKey: string
  version: string
  status: 'DRAFT' | 'RELEASED' | 'DEPRECATED'
  assetPins: Record<string, string>
  manifestHash: string
  signature: string
  createdBy: string
  createdAt: string
}

/** 策略试算结果：REQUIRE_APPROVAL 表示需人工审批而非直接 ALLOW。 */
export interface PolicyDecision {
  outcome: 'ALLOW' | 'DENY' | 'REQUIRE_APPROVAL'
  reason: string
  allowed: boolean
  approvalRequired: boolean
}

/** 评测/门禁记录：字段较灵活，前端仅展示 status 等已知键。 */
export interface EvaluationRecord {
  id?: string
  status?: string
  [key: string]: unknown
}

/** 进化观测：来自运行反馈或质量归因的只读证据。 */
export interface EvolutionObservation {
  id: string
  sourceType?: string
  sourceId?: string
  attributionCategory?: string
  summary?: string
  createdAt?: string
  [key: string]: unknown
}
/** 进化候选：提案 patch 在 promote 前不得直接改写生产资产。 */
export interface EvolutionCandidate {
  id: string
  status?: string
  assetKey?: string
  proposedVersion?: string
  riskClass?: string
  [key: string]: unknown
}
/** Anti-rot 扫描发现：仅报告，不自动删除资产。 */
export interface AntiRotFinding {
  id?: string
  type?: string
  severity?: string
  summary?: string
  [key: string]: unknown
}
/** 不可变审计条目：链式 hash 由服务端维护。 */
export interface AuditEntry {
  id?: string
  action?: string
  resourceType?: string
  resourceId?: string
  actorId?: string
  createdAt?: string
  [key: string]: unknown
}
