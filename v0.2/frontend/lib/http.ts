import { apiPath, API_VERSION, SANDBOX_FALLBACK_TOKEN } from './runtime-config';

export type JsonValue = string | number | boolean | null | JsonValue[] | { [k: string]: JsonValue };

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

export const fetchJson = async <T = any>(
  path: string,
  init?: RequestInit
): Promise<T> => {
  const response = await fetch(apiPath(path), init);
  const data = await response.json().catch(() => null);
  if (!response.ok) {
    const message = data?.detail || data?.error?.message || `HTTP ${response.status}`;
    throw new Error(message);
  }
  return data as T;
};
