'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { apiPath } from '../lib/runtime-config';

interface User {
  id: string;
  email: string;
  name: string;
  avatar?: string;
}

export function useAuth() {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const router = useRouter();

  useEffect(() => {
    let mounted = true;
    const bootstrap = async () => {
      const token = localStorage.getItem('token');
      if (!token) {
        if (mounted) setLoading(false);
        return;
      }
      try {
        const res = await fetch(apiPath('/api/auth/me'), {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });
        if (!res.ok) {
          throw new Error('unauthorized');
        }
        const data = await res.json();
        const me = data?.user as User | undefined;
        if (!me) throw new Error('invalid user');
        localStorage.setItem('user', JSON.stringify(me));
        if (mounted) setUser(me);
      } catch {
        localStorage.removeItem('token');
        localStorage.removeItem('user');
        if (mounted) setUser(null);
      } finally {
        if (mounted) setLoading(false);
      }
    };
    bootstrap();
    return () => {
      mounted = false;
    };
  }, []);

  const logout = async () => {
    const token = localStorage.getItem('token');
    if (token) {
      try {
        await fetch(apiPath('/api/auth/logout'), {
          method: 'POST',
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        // ignore network failure on best-effort logout
      }
    }
    localStorage.removeItem('token');
    localStorage.removeItem('user');
    setUser(null);
    router.push('/');
  };

  return { user, loading, logout, setUser };
}

export function AuthButton() {
  const { user, loading, logout } = useAuth();
  const router = useRouter();
  const [showMenu, setShowMenu] = useState(false);

  if (loading) {
    return (
      <div className="w-8 h-8 bg-slate-200 rounded-full animate-pulse"></div>
    );
  }

  if (!user) {
    return (
      <button
        onClick={() => router.push('/login')}
        className="px-4 py-2 bg-slate-800 text-white text-sm rounded-lg hover:bg-slate-700 transition-colors"
      >
        登录
      </button>
    );
  }

  return (
    <div className="relative">
      <button
        onClick={() => setShowMenu(!showMenu)}
        className="flex items-center gap-2 px-3 py-2 bg-slate-100 rounded-lg hover:bg-slate-200 transition-colors"
      >
        {user.avatar ? (
          <img src={user.avatar} alt={user.name} className="w-6 h-6 rounded-full object-cover" />
        ) : (
          <div className="w-6 h-6 bg-blue-500 rounded-full flex items-center justify-center text-white text-xs font-medium">
            {user.name.charAt(0).toUpperCase()}
          </div>
        )}
        <span className="text-sm text-slate-700">{user.name}</span>
        <svg className="w-4 h-4 text-slate-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {showMenu && (
        <>
          <div 
            className="fixed inset-0 z-10" 
            onClick={() => setShowMenu(false)}
          ></div>
          <div className="absolute right-0 top-full mt-2 w-48 bg-white rounded-lg shadow-lg border border-slate-200 py-2 z-20">
            <div className="px-4 py-2 border-b border-slate-100">
              <p className="text-sm font-medium text-slate-800">{user.name}</p>
              <p className="text-xs text-slate-500">{user.email}</p>
            </div>
            <button
              onClick={() => {
                setShowMenu(false);
                router.push('/history');
              }}
              className="w-full px-4 py-2 text-left text-sm text-slate-700 hover:bg-slate-50 flex items-center gap-2"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              历史记录
            </button>
            <button
              onClick={() => {
                setShowMenu(false);
                logout();
              }}
              className="w-full px-4 py-2 text-left text-sm text-red-600 hover:bg-red-50 flex items-center gap-2"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
              </svg>
              退出登录
            </button>
          </div>
        </>
      )}
    </div>
  );
}