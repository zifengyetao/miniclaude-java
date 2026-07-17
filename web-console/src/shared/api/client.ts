/**
 * Web Console 的统一 HTTP 客户端与领域 API 门面。
 *
 * 浏览器只负责携带租户、操作者和开发 API Key 发起请求；这些前端字段都可被篡改，
 * 不能作为权限或租户隔离的安全边界，服务端仍须完成认证、授权、审计与参数校验。
 * 当前封装仅覆盖普通请求/响应 API，不提供 SSE 订阅、断线重连或流式事件保证；
 * 页面看到的状态取决于显式加载/刷新，不能等同于服务端实时状态。
 */
import type {
  AgentDefinition, AgentReleaseManifest, AgentRun, AntiRotFinding, ApprovalRequest, AuditEntry, ChatResponse,
  CreateAgentRequest, CreateRunRequest, EvolutionCandidate, EvolutionObservation,
  EvaluationRecord, GraphRequest, GraphValidationResult, PolicyDecision, RolePack, RunCheckpoint,
  RunEvent, ScenarioArtifact, Session, VersionedAsset,
} from './types'

const API_KEY_STORAGE = 'miniclaude.platformApiKey'
const TENANT_STORAGE = 'miniclaude.tenantId'
const ACTOR_STORAGE = 'miniclaude.actorId'

export const credentials = {
  /** 从当前标签页会话读取 API Key；sessionStorage 仅降低持久化范围，不等于安全存储。 */
  get apiKey() { return sessionStorage.getItem(API_KEY_STORAGE) ?? '' },
  get tenantId() { return sessionStorage.getItem(TENANT_STORAGE) ?? 'default' },
  get actorId() { return sessionStorage.getItem(ACTOR_STORAGE) ?? 'web-console' },
  /**
   * 保存开发调用凭据到 sessionStorage，供后续请求组装请求头。
   * 租户和操作者只是客户端声明，服务端不得据此直接授予权限。
   */
  save(apiKey: string, tenantId: string, actorId: string) {
    if (apiKey.trim()) sessionStorage.setItem(API_KEY_STORAGE, apiKey.trim())
    else sessionStorage.removeItem(API_KEY_STORAGE)
    sessionStorage.setItem(TENANT_STORAGE, tenantId.trim() || 'default')
    sessionStorage.setItem(ACTOR_STORAGE, actorId.trim() || 'web-console')
  },
}

export class ApiError extends Error {
  readonly status: number
  readonly details?: unknown
  /** 保留 HTTP 状态和后端错误体，便于页面展示可读错误并按需诊断。 */
  constructor(message: string, status: number, details?: unknown) {
    super(message)
    this.status = status
    this.details = details
  }
}

class ApiClient {
  private readonly base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

  /**
   * 发送 JSON API 请求并统一注入调用上下文、解析响应和转换非 2xx 错误。
   * 此处没有超时、重试、取消或 SSE 语义；调用方必须自行管理异步状态和重复提交。
   */
  async request<T>(path: string, init: RequestInit = {}): Promise<T> {
    const headers = new Headers(init.headers)
    headers.set('Content-Type', 'application/json')
    headers.set('X-Tenant-Id', credentials.tenantId)
    headers.set('X-Actor-Id', credentials.actorId)
    if (credentials.apiKey) headers.set('X-Platform-Api-Key', credentials.apiKey)
    const response = await fetch(`${this.base}${path}`, { ...init, headers })
    if (!response.ok) {
      const details = await response.json().catch(() => null) as { message?: string } | null
      throw new ApiError(details?.message ?? `请求失败 (${response.status})`, response.status, details)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }

  get<T>(path: string) { return this.request<T>(path) }
  post<T>(path: string, body?: unknown, headers?: HeadersInit) {
    return this.request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), headers })
  }
  put<T>(path: string, body?: unknown) {
    return this.request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) })
  }
  delete(path: string) { return this.request<void>(path, { method: 'DELETE' }) }
}

export const api = new ApiClient()

export const platformApi = {
  agents: {
    list: () => api.get<AgentDefinition[]>('/platform/agents'),
    create: (body: CreateAgentRequest) => api.post<AgentDefinition>('/platform/agents', body),
  },
  runs: {
    list: () => api.get<AgentRun[]>('/platform/runs'),
    get: (id: string) => api.get<AgentRun>(`/platform/runs/${id}`),
    create: (body: CreateRunRequest) => api.post<AgentRun>('/platform/runs', body),
    events: (id: string) => api.get<RunEvent[]>(`/platform/runs/${id}/events`),
    checkpoints: (id: string) => api.get<RunCheckpoint[]>(`/platform/runs/${id}/checkpoints`),
    approvals: (id: string) => api.get<ApprovalRequest[]>(`/platform/runs/${id}/approvals`),
    /** 提交运行控制命令；随机幂等键只辅助服务端去重，最终状态仍以后端返回为准。 */
    control: (id: string, action: 'pause' | 'resume' | 'cancel') =>
      api.post<AgentRun>(`/platform/runs/${id}/${action}`, undefined, { 'Idempotency-Key': crypto.randomUUID() }),
    /** 提交人工审批决定；前端按钮与操作者字段不构成授权，服务端必须复核权限和审批状态。 */
    decide: (runId: string, approvalId: string, actionParameters: string, decision: 'APPROVED' | 'REJECTED', reason: string) =>
      api.post<ApprovalRequest>(`/platform/runs/${runId}/approvals/${approvalId}/decision`, {
        decision, actor: credentials.actorId, reason, actionParameters,
      }),
  },
  graphs: {
    /** 请求服务端执行权威 Graph 校验；编辑器内 JSON 解析仅是输入预检查。 */
    validate: (body: GraphRequest) => api.post<GraphValidationResult>('/platform/graphs/validate', body),
  },
}

export const chatApi = {
  sessions: () => api.get<Session[]>('/sessions'),
  createSession: (model?: string) => api.post<Session>('/sessions', model ? { model } : {}),
  send: (message: string, sessionId?: string, model?: string) =>
    api.post<ChatResponse>('/chat', { message, sessionId: sessionId || undefined, model: model || undefined }),
}

export const scenarioApi = {
  templates: () => api.get<RolePack[]>('/scenarios/templates'),
  start: (scenario: string, input: Record<string, unknown>) => api.post<AgentRun>(`/scenarios/${scenario}/start`, input),
  status: (scenario: string, runId: string) => api.get<AgentRun>(`/scenarios/${scenario}/runs/${runId}`),
  artifacts: (scenario: string, runId: string) => api.get<ScenarioArtifact[]>(`/scenarios/${scenario}/runs/${runId}/artifacts`),
  continue: (scenario: string, runId: string) => api.post<AgentRun>(`/scenarios/${scenario}/runs/${runId}/continue`),
}

export const governanceApi = {
  assets: () => api.get<VersionedAsset[]>('/governance/assets'),
  createAsset: (body: Pick<VersionedAsset, 'type' | 'key' | 'version' | 'content'> & { parentId?: string; signature?: string }) =>
    api.post<VersionedAsset>('/governance/assets', body),
  publishAsset: (id: string, hash: string) => api.put<VersionedAsset>(`/governance/assets/${id}/publish`, { hash }),
  deprecateAsset: (id: string) => api.put<VersionedAsset>(`/governance/assets/${id}/deprecate`),
  resolveAsset: (type: VersionedAsset['type'], key: string, version: string, forRun = false) =>
    api.get<VersionedAsset>(`/governance/assets/resolve?type=${type}&key=${encodeURIComponent(key)}&version=${encodeURIComponent(version)}&forRun=${forRun}`),
  createManifest: (body: { agentKey: string; version: string; assetPins: Record<string, string>; signature?: string }) =>
    api.post<AgentReleaseManifest>('/governance/manifests', body),
  verifyManifest: (id: string) => api.get<AgentReleaseManifest>(`/governance/manifests/${id}/verify`),
  releaseManifest: (id: string, hash: string) => api.put<AgentReleaseManifest>(`/governance/manifests/${id}/release`, { hash }),
  createPolicyRule: (body: { key: string; version: string; scope: string; actionPattern: string; resourcePattern: string; priority: number; effect: string }) =>
    api.post<Record<string, unknown>>('/governance/policies/rules', body),
  audits: () => api.get<AuditEntry[]>('/governance/audits'),
  evaluatePolicy: (action: string, resource: string) =>
    api.post<PolicyDecision>('/governance/policies/evaluate', { action, resource }),
  createEvaluationSuite: (body: { key: string; version: string; thresholds: Record<string, number> }) =>
    api.post<EvaluationRecord>('/governance/evaluations/suites', body),
  runEvaluation: (body: { suiteId: string; manifestId: string; metrics: Record<string, number>; safetyPassed: boolean }) =>
    api.post<EvaluationRecord>('/governance/evaluations/runs', body),
  releaseGate: (id: string) => api.get<EvaluationRecord>(`/governance/release-gates/${id}`),
}

export const evolutionApi = {
  observations: () => api.get<EvolutionObservation[]>('/governance/evolution/observations'),
  candidates: () => api.get<EvolutionCandidate[]>('/governance/evolution/candidates'),
  findings: () => api.get<AntiRotFinding[]>('/governance/evolution/anti-rot/findings'),
  scan: (currentModel?: string) => api.post<AntiRotFinding[]>(`/governance/evolution/anti-rot/scan${currentModel ? `?currentModel=${encodeURIComponent(currentModel)}` : ''}`),
  observe: (body: Record<string, unknown>) => api.post<EvolutionObservation>('/governance/evolution/observations', body),
  propose: (body: Record<string, unknown>) => api.post<EvolutionCandidate>('/governance/evolution/candidates', body),
  evaluate: (id: string, body: Record<string, unknown>) => api.post<EvolutionCandidate>(`/governance/evolution/candidates/${id}/evaluate`, body),
  /**
   * 推进候选的受治理动作。前端动作集合只是交互约束，服务端必须校验状态迁移、
   * 角色权限、发布门禁与回滚条件。
   */
  action: (id: string, action: 'review' | 'shadow' | 'canary' | 'promote' | 'rollback', body: unknown) =>
    api.post<EvolutionCandidate>(`/governance/evolution/candidates/${id}/${action}`, body),
}
