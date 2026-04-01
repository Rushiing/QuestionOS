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
import { apiPath } from '../lib/runtime-config';

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

async function fetchMe(token: string): Promise<AuthUser> {
  const res = await fetch(apiPath('/api/auth/me'), {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!res.ok) throw new Error('unauthorized');
  const data = await res.json();
  const me = data?.user as AuthUser | undefined;
  if (!me?.id) throw new Error('invalid user');
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
    const token = localStorage.getItem('token');
    if (!token) {
      setUser(null);
      setLoading(false);
      return;
    }
    try {
      const me = await fetchMe(token);
      localStorage.setItem('user', JSON.stringify(me));
      setUser(me);
    } catch {
      clearSession();
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
