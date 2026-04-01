export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
export const API_VERSION = process.env.NEXT_PUBLIC_API_VERSION || '1.1';
export const SANDBOX_FALLBACK_TOKEN = process.env.NEXT_PUBLIC_SANDBOX_TOKEN || 'sk-sandbox-dev';

function apiBaseNormalized(): string {
  return API_BASE_URL.replace(/\/$/, '');
}

/** 生产环境浏览器直连 Java（公网 https），避免 Next 服务端代理在 Railway 上挂死/超时导致平台 502 */
function browserUseDirectBackend(): boolean {
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
    if (browserUseDirectBackend()) {
      return `${apiBaseNormalized()}${path}`;
    }
    return path;
  }
  if (path.startsWith('/')) {
    return `${apiBaseNormalized()}${path}`;
  }
  return `${apiBaseNormalized()}/${path}`;
};
