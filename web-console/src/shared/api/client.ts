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
  get apiKey() { return sessionStorage.getItem(API_KEY_STORAGE) ?? '' },
  get tenantId() { return sessionStorage.getItem(TENANT_STORAGE) ?? 'default' },
  get actorId() { return sessionStorage.getItem(ACTOR_STORAGE) ?? 'web-console' },
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
  constructor(message: string, status: number, details?: unknown) {
    super(message)
    this.status = status
    this.details = details
  }
}

class ApiClient {
  private readonly base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'

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
    control: (id: string, action: 'pause' | 'resume' | 'cancel') =>
      api.post<AgentRun>(`/platform/runs/${id}/${action}`, undefined, { 'Idempotency-Key': crypto.randomUUID() }),
    decide: (runId: string, approvalId: string, actionParameters: string, decision: 'APPROVED' | 'REJECTED', reason: string) =>
      api.post<ApprovalRequest>(`/platform/runs/${runId}/approvals/${approvalId}/decision`, {
        decision, actor: credentials.actorId, reason, actionParameters,
      }),
  },
  graphs: { validate: (body: GraphRequest) => api.post<GraphValidationResult>('/platform/graphs/validate', body) },
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
  action: (id: string, action: 'review' | 'shadow' | 'canary' | 'promote' | 'rollback', body: unknown) =>
    api.post<EvolutionCandidate>(`/governance/evolution/candidates/${id}/${action}`, body),
}
