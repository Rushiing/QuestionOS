export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
export const API_VERSION = process.env.NEXT_PUBLIC_API_VERSION || '1.1';
export const SANDBOX_FALLBACK_TOKEN = process.env.NEXT_PUBLIC_SANDBOX_TOKEN || 'sk-sandbox-dev';

function apiBaseNormalized(): string {
  return API_BASE_URL.replace(/\/$/, '');
}

/** RootLayout 注入的 window.__QOS_API_BASE__（服务端读 INTERNAL_API_URL / NEXT_PUBLIC_API_URL，与 build 内联无关） */
function browserRuntimeApiBase(): string {
  if (typeof window === 'undefined') return '';
  const s = String(window.__QOS_API_BASE__ ?? '')
    .trim()
    .replace(/\/$/, '');
  if (s.startsWith('http://') || s.startsWith('https://')) return s;
  return '';
}

/** 构建时内联的 NEXT_PUBLIC_API_URL 为 https 时，直连 Java（Docker build 已注入变量时生效） */
function browserUseDirectBackendFromBundle(): boolean {
  if (typeof window === 'undefined') return false;
  const b = apiBaseNormalized();
  return (
    b.startsWith('https://') &&
    !b.includes('localhost') &&
    !b.includes('127.0.0.1')
  );
}

export const apiPath = (path: string): string => {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path;
  }
  if (typeof window !== 'undefined' && path.startsWith('/api/')) {
    const rt = browserRuntimeApiBase();
    if (rt) return `${rt}${path}`;
    if (browserUseDirectBackendFromBundle()) return `${apiBaseNormalized()}${path}`;
    return path;
  }
  if (path.startsWith('/')) {
    return `${apiBaseNormalized()}${path}`;
  }
  return `${apiBaseNormalized()}/${path}`;
};
