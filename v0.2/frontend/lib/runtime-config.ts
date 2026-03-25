export const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080';
export const API_VERSION = process.env.NEXT_PUBLIC_API_VERSION || '1.1';
export const SANDBOX_FALLBACK_TOKEN = process.env.NEXT_PUBLIC_SANDBOX_TOKEN || 'sk-sandbox-dev';

export const apiPath = (path: string): string => {
  if (path.startsWith('http://') || path.startsWith('https://')) {
    return path;
  }
  // In browser, prefer same-origin Next.js proxy to avoid CORS issues.
  if (typeof window !== 'undefined' && path.startsWith('/api/')) {
    return path;
  }
  if (path.startsWith('/')) {
    return `${API_BASE_URL}${path}`;
  }
  return `${API_BASE_URL}/${path}`;
};
