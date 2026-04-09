'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useRouter } from 'next/navigation';
import { apiPath, SANDBOX_FALLBACK_TOKEN } from '../lib/runtime-config';

export interface AuthUser {
  id: string;
  email: string;
  name: string;
  avatar?: string;
}

type AuthContextValue = {
  user: AuthUser | null;
  loading: boolean;
  refreshUser: () => Promise<void>;
  setUserFromLogin: (user: AuthUser) => void;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

type MeFetchFailure = Error & { readonly clearSession: boolean };

function meFail(message: string, clearSession: boolean): MeFetchFailure {
  const e = new Error(message) as MeFetchFailure;
  Object.defineProperty(e, 'clearSession', { value: clearSession, enumerable: true });
  return e;
}

/** Java：`{ user }`；旧 Python 网关：扁平 `{ id, email, name }` */
function parseAuthUserBody(data: unknown): AuthUser | null {
  if (!data || typeof data !== 'object') return null;
  const o = data as Record<string, unknown>;
  const raw = (o.user ?? o) as Record<string, unknown>;
  if (!raw || typeof raw !== 'object') return null;
  const id = typeof raw.id === 'string' ? raw.id : '';
  if (!id) return null;
  return {
    id,
    email: typeof raw.email === 'string' ? raw.email : '',
    name: typeof raw.name === 'string' ? raw.name : '',
    avatar: typeof raw.avatar === 'string' ? raw.avatar : undefined,
  };
}

async function fetchMe(token: string): Promise<AuthUser> {
  const res = await fetch(apiPath('/api/auth/me'), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (res.status === 401 || res.status === 403) {
    throw meFail('unauthorized', true);
  }
  if (!res.ok) {
    throw meFail(`upstream_${res.status}`, false);
  }
  let data: unknown;
  try {
    data = await res.json();
  } catch {
    throw meFail('bad_json', false);
  }
  const me = parseAuthUserBody(data);
  if (!me) {
    throw meFail('invalid_user_payload', true);
  }
  return me;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  const clearSession = useCallback(() => {
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
  }, []);

  const refreshUser = useCallback(async () => {
    let token = localStorage.getItem('token');
    if (
      !token &&
      typeof window !== 'undefined' &&
      process.env.NEXT_PUBLIC_DEV_LOCAL_AUTH === 'true'
    ) {
      localStorage.setItem('token', SANDBOX_FALLBACK_TOKEN);
      token = SANDBOX_FALLBACK_TOKEN;
    }
    if (!token) {
      setUser(null);
      setLoading(false);
      return;
    }

    /** 刷新瞬间先用本地缓存展示，避免闪回「未登录」；仅服务端明确拒信时再清 token */
    let hadCachedUser = false;
    try {
      const cachedRaw = localStorage.getItem('user');
      if (cachedRaw) {
        const u = JSON.parse(cachedRaw) as AuthUser;
        if (u?.id) {
          hadCachedUser = true;
          setUser(u);
        }
      }
    } catch {
      /* ignore */
    }

    try {
      const me = await fetchMe(token);
      localStorage.setItem('user', JSON.stringify(me));
      setUser(me);
    } catch (e) {
      const shouldClear =
        e instanceof Error && 'clearSession' in e && (e as MeFetchFailure).clearSession === true;
      if (shouldClear) {
        clearSession();
      } else if (!hadCachedUser) {
        setUser(null);
      }
    } finally {
      setLoading(false);
    }
  }, [clearSession]);

  const setUserFromLogin = useCallback((me: AuthUser) => {
    localStorage.setItem('user', JSON.stringify(me));
    setUser(me);
    setLoading(false);
  }, []);

  const logout = useCallback(async () => {
    const token = localStorage.getItem('token');
    if (token) {
      try {
        await fetch(apiPath('/api/auth/logout'), {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        // ignore
      }
    }
    clearSession();
    router.push('/');
  }, [clearSession, router]);

  useEffect(() => {
    void refreshUser();
  }, [refreshUser]);

  const value = useMemo(
    () => ({
      user,
      loading,
      refreshUser,
      setUserFromLogin,
      logout,
    }),
    [user, loading, refreshUser, setUserFromLogin, logout]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return ctx;
}
