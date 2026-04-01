import { apiPath } from './runtime-config';
import { buildSandboxHeaders, fetchJson } from './http';
import { streamSse } from './sse-client';

export type SessionMode = 'SANDBOX' | 'CALIBRATION';

export interface SandboxSessionSummary {
  sessionId: string;
  mode: string;
  status: string;
  messageCount: number;
  createdAt: string;
  lastActivityAt: string;
  /** 后端 LLM 摘要；缺失时用 mode/status 兜底 */
  title?: string | null;
}

export interface SandboxSessionMessage {
  messageId: string;
  role: string;
  content: string;
  turnId: number;
  createdAt: string;
  agentSpeakerId?: string | null;
}

export interface AgentInstance {
  agentId: string;
  provider: string;
  endpoint: string;
  scope: string;
  model: string;
  registeredAt: string;
}

export interface AgentInvokeResult {
  agentId: string;
  status: string;
  input: string;
  output: string;
}

export interface AgentOnboardingPacket {
  version: string;
  goal: string;
  questionos: {
    baseUrl: string;
    capabilitiesUrl: string;
    registerUrl: string;
    instancesUrl: string;
    probeTemplate: {
      invokeUrlTemplate: string;
      input: string;
    };
  };
  registerPayloadSchema: Record<string, string>;
  successCriteria: string[];
  securityNote: string;
}

export const sandboxClient = {
  createSession: async (mode: SessionMode, question: string): Promise<string> => {
    const data = await fetchJson<{ sessionId: string }>('/api/v1/sandbox/sessions', {
      method: 'POST',
      headers: {
        ...buildSandboxHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ mode, question }),
      timeoutMs: 45_000,
      retries: 0,
    });
    return data.sessionId;
  },

  sendMessage: async (sessionId: string, content: string, idempotencyKey: string): Promise<void> => {
    await fetchJson(apiPath(`/api/v1/sandbox/sessions/${sessionId}/messages`), {
      method: 'POST',
      headers: {
        ...buildSandboxHeaders(),
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ content }),
      timeoutMs: 45_000,
      retries: 2,
    });
  },

  streamTurn: async (
    sessionId: string,
    lastEventId: number | undefined,
    onEvent: (event: { eventType: string; eventId: string; dataRaw: string }) => boolean | void,
    onDebug?: (line: string) => void
  ): Promise<void> => {
    await streamSse({
      path: `/api/v1/sandbox/sessions/${sessionId}/stream`,
      lastEventId,
      onEvent,
      onDebug,
    });
  },

  listSessions: async (): Promise<SandboxSessionSummary[]> => {
    const data = await fetchJson<{ sessions: SandboxSessionSummary[] }>('/api/v1/sandbox/sessions', {
      method: 'GET',
      headers: buildSandboxHeaders(),
      retries: 2,
    });
    return data.sessions || [];
  },

  listMessages: async (sessionId: string): Promise<SandboxSessionMessage[]> => {
    const data = await fetchJson<{ messages: SandboxSessionMessage[] }>(
      `/api/v1/sandbox/sessions/${sessionId}/messages`,
      {
        method: 'GET',
        headers: buildSandboxHeaders(),
        retries: 2,
      }
    );
    return data.messages || [];
  },

  registerAgent: async (payload: {
    agentId: string;
    provider: string;
    endpoint: string;
    scope: string;
    apiKey?: string;
    model?: string;
  }): Promise<void> => {
    await fetchJson('/api/v1/agents/register', {
      method: 'POST',
      headers: {
        ...buildSandboxHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
      timeoutMs: 45_000,
      retries: 0,
    });
  },

  capabilitiesUrl: (): string => apiPath('/api/v1/agents/capabilities'),

  getCapabilities: async (): Promise<any> =>
    fetchJson('/api/v1/agents/capabilities', {
      method: 'GET',
      headers: buildSandboxHeaders(),
      retries: 2,
    }),

  getOnboardingPacket: async (): Promise<AgentOnboardingPacket> =>
    fetchJson<AgentOnboardingPacket>('/api/v1/agents/onboarding-packet', {
      method: 'GET',
      headers: buildSandboxHeaders(),
      retries: 2,
    }),

  listInstances: async (): Promise<AgentInstance[]> => {
    const data = await fetchJson<{ count: number; instances: AgentInstance[] }>('/api/v1/agents/instances', {
      method: 'GET',
      headers: buildSandboxHeaders(),
      retries: 2,
    });
    return data.instances || [];
  },

  invokeAgent: async (agentId: string, input: string): Promise<AgentInvokeResult> =>
    fetchJson<AgentInvokeResult>(`/api/v1/agents/${agentId}/invoke`, {
      method: 'POST',
      headers: {
        ...buildSandboxHeaders(),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        sessionId: `sess_probe_${Date.now()}`,
        turnId: 1,
        input,
      }),
      timeoutMs: 120_000,
      retries: 0,
    }),
};
