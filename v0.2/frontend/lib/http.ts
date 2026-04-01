import { apiPath, API_VERSION, SANDBOX_FALLBACK_TOKEN } from './runtime-config';

export type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue };

/** 与 axios `api.ts` 对齐的默认超时（毫秒） */
export const DEFAULT_FETCH_TIMEOUT_MS = 30_000;
export const DEFAULT_RETRY_DELAY_MS = 400;
const MAX_RETRIES_CAP = 3;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function hasIdempotencyKey(init?: RequestInit): boolean {
  const h = init?.headers;
  if (!h) return false;
  if (h instanceof Headers) return h.has('Idempotency-Key');
  if (Array.isArray(h)) return h.some(([k]) => k.toLowerCase() === 'idempotency-key');
  return Object.keys(h as Record<string, string>).some((k) => k.toLowerCase() === 'idempotency-key');
}

function isIdempotentMethod(method: string, init?: RequestInit): boolean {
  const m = method.toUpperCase();
  if (m === 'GET' || m === 'HEAD' || m === 'OPTIONS') return true;
  if (m === 'POST' && hasIdempotencyKey(init)) return true;
  return false;
}

export function isRetryableHttpStatus(status: number): boolean {
  return status === 408 || status === 429 || status === 502 || status === 503 || status === 504;
}

function isRetryableFetchFailure(e: unknown, userSignalAborted: boolean): boolean {
  if (userSignalAborted) return false;
  if (e instanceof TypeError) return true;
  if (e instanceof DOMException && e.name === 'AbortError') return true;
  if (e instanceof Error && e.name === 'AbortError') return true;
  if (e instanceof Error && e.message.startsWith('请求超时')) return true;
  return false;
}

export type FetchJsonInit = RequestInit & {
  timeoutMs?: number;
  /** 仅对 GET/HEAD/OPTIONS 或带 Idempotency-Key 的 POST 生效 */
  retries?: number;
  retryDelayMs?: number;
};

export const getBearerToken = (): string => {
  if (typeof window !== 'undefined') {
    const token = localStorage.getItem('token');
    if (token) return token;
  }
  return SANDBOX_FALLBACK_TOKEN;
};

export const buildHeaders = (extra?: Record<string, string>): Record<string, string> => ({
  Authorization: `Bearer ${getBearerToken()}`,
  'X-API-Version': API_VERSION,
  ...extra,
});

// Sandbox APIs in local dev use a dedicated token.
// Do not rely on user login token here, otherwise old/invalid tokens can break session creation.
export const buildSandboxHeaders = (extra?: Record<string, string>): Record<string, string> => ({
  Authorization: `Bearer ${SANDBOX_FALLBACK_TOKEN}`,
  'X-API-Version': API_VERSION,
  ...extra,
});

export const fetchJson = async <T = any>(path: string, init?: FetchJsonInit): Promise<T> => {
  const {
    timeoutMs = DEFAULT_FETCH_TIMEOUT_MS,
    retries = 0,
    retryDelayMs = DEFAULT_RETRY_DELAY_MS,
    ...requestInit
  } = init ?? {};

  const { signal: userSignal, ...fetchInit } = requestInit;
  const method = (fetchInit.method || 'GET').toUpperCase();
  const allowRetry = isIdempotentMethod(method, fetchInit);
  const attempts = Math.min(retries, MAX_RETRIES_CAP);
  let lastError: unknown;

  for (let attempt = 0; attempt <= attempts; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    const onUserAbort = () => {
      clearTimeout(timer);
      controller.abort();
    };
    if (userSignal) {
      if (userSignal.aborted) {
        clearTimeout(timer);
        throw new DOMException('Aborted', 'AbortError');
      }
      userSignal.addEventListener('abort', onUserAbort, { once: true });
    }

    try {
      const response = await fetch(apiPath(path), {
        ...fetchInit,
        signal: controller.signal,
      });
      clearTimeout(timer);
      if (userSignal) userSignal.removeEventListener('abort', onUserAbort);
      const data = await response.json().catch(() => null);
      if (!response.ok) {
        if (allowRetry && attempt < attempts && isRetryableHttpStatus(response.status)) {
          await sleep(retryDelayMs * 2 ** attempt);
          continue;
        }
        const message = data?.detail || data?.error?.message || `HTTP ${response.status}`;
        throw new Error(message);
      }
      return data as T;
    } catch (e) {
      clearTimeout(timer);
      if (userSignal) userSignal.removeEventListener('abort', onUserAbort);
      const userAborted = !!userSignal?.aborted;
      if (userAborted) {
        throw e instanceof Error ? e : new DOMException('Aborted', 'AbortError');
      }
      lastError = e;
      const aborted =
        e instanceof DOMException
          ? e.name === 'AbortError'
          : e instanceof Error && e.name === 'AbortError';
      if (aborted) {
        lastError = new Error(`请求超时（${Math.round(timeoutMs / 1000)}s）`);
      }
      if (allowRetry && attempt < attempts && isRetryableFetchFailure(lastError, userAborted)) {
        await sleep(retryDelayMs * 2 ** attempt);
        continue;
      }
      throw lastError instanceof Error ? lastError : new Error(String(lastError));
    }
  }
  throw lastError instanceof Error ? lastError : new Error(String(lastError));
};
