/**
 * Web Console 与后端 JSON API 之间共享的 TypeScript 数据契约。
 * 类型只提供编译期提示，不会校验运行时响应，也不能替代服务端输入验证与权限控制。
 */
export type ExecutionMode = 'CHAT' | 'PLAN_EXECUTE' | 'GOAL' | 'GRAPH'
export type RunStatus = 'PENDING' | 'PLANNING' | 'WAITING_APPROVAL' | 'PAUSED' | 'RUNNING' | 'VERIFYING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'TIMED_OUT' | 'BUDGET_EXCEEDED' | 'STEP_LIMIT_EXCEEDED'
export type RiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'REGULATED'
export type EvolutionLevel = 'L0' | 'L1' | 'L2' | 'L3'

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

export type CreateAgentRequest = Pick<AgentDefinition, 'name' | 'description' | 'roleName' | 'riskLevel' | 'evolutionLevel' | 'executionModes'>

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

export interface CreateRunRequest {
  agentId: string
  executionMode: ExecutionMode
  goal: string
  maxSteps?: number
  maxCostUsd?: number
  timeoutSeconds?: number
}

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

export interface Session {
  id: string
  createdAt: string
  lastActiveAt: string
  model: string
  title: string
}

export interface ChatResponse {
  sessionId: string
  reply: string
  model: string
  tokens: Record<string, number>
}

export interface GraphNode { id: string; type: string; reference: string }
export interface GraphEdge { from: string; to: string; condition: string }
export interface GraphRequest {
  name: string
  version: string
  entryNode: string
  nodes: GraphNode[]
  edges: GraphEdge[]
  limits: { maxSteps: number; maxIterations: number; maxCostUsd: number }
}
export interface GraphValidationResult { valid: boolean; errors: string[]; warnings: string[] }

export interface VersionRef { key: string; version: string }
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

export interface PolicyDecision {
  outcome: 'ALLOW' | 'DENY' | 'REQUIRE_APPROVAL'
  reason: string
  allowed: boolean
  approvalRequired: boolean
}

export interface EvaluationRecord {
  id?: string
  status?: string
  [key: string]: unknown
}

export interface EvolutionObservation {
  id: string
  sourceType?: string
  sourceId?: string
  attributionCategory?: string
  summary?: string
  createdAt?: string
  [key: string]: unknown
}
export interface EvolutionCandidate {
  id: string
  status?: string
  assetKey?: string
  proposedVersion?: string
  riskClass?: string
  [key: string]: unknown
}
export interface AntiRotFinding {
  id?: string
  type?: string
  severity?: string
  summary?: string
  [key: string]: unknown
}
export interface AuditEntry {
  id?: string
  action?: string
  resourceType?: string
  resourceId?: string
  actorId?: string
  createdAt?: string
  [key: string]: unknown
}
