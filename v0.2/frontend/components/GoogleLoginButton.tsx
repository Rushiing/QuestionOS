'use client';

import { useState, useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { apiPath } from '../lib/runtime-config';
import { useAuth } from './AuthProvider';

function resolveGoogleClientId(): string {
  if (typeof window !== 'undefined' && window.__QOS_GOOGLE_CLIENT_ID__) {
    return window.__QOS_GOOGLE_CLIENT_ID__.trim();
  }
  return (process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID || '').trim();
}

const GIS_SCRIPT_SRC = 'https://accounts.google.com/gsi/client';

function loadGoogleScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined') {
      reject(new Error('window 不可用（SSR 环境）'));
      return;
    }
    if (window.google?.accounts?.id) {
      resolve();
      return;
    }
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GIS_SCRIPT_SRC}"]`);
    if (existing) {
      existing.addEventListener('load', () => resolve(), { once: true });
      existing.addEventListener('error', () => reject(new Error('Google SDK 加载失败')), { once: true });
      return;
    }
    const script = document.createElement('script');
    script.src = GIS_SCRIPT_SRC;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('Google SDK 加载失败（网络受限？）'));
    document.head.appendChild(script);
  });
}

export function GoogleLoginButton() {
  const router = useRouter();
  const { setUserFromLogin } = useAuth();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string>('');
  const [ready, setReady] = useState(false);
  const buttonRef = useRef<HTMLDivElement>(null);
  const initializedRef = useRef(false);

  const handleGoogleCallback = async (response: { credential?: string }) => {
    if (!response?.credential) {
      setError('Google 未返回凭证，请重试');
      return;
    }
    setLoading(true);
    setError('');
    try {
      const res = await fetch(apiPath('/api/auth/google'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ id_token: response.credential }),
      });
      const data = await res.json();
      if (!res.ok) {
        throw new Error(data.detail || data.message || 'Google 登录失败');
      }
      localStorage.setItem('token', data.access_token);
      setUserFromLogin(data.user);
      router.push('/');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Google 登录失败';
      console.error('Google login error:', err);
      setError(message);
    } finally {
      setLoading(false);
    }
  };

  // 加载 Google SDK 并初始化（仅一次）
  useEffect(() => {
    let cancelled = false;
    const googleClientId = resolveGoogleClientId();
    if (!googleClientId) {
      setError('Google OAuth 未配置（NEXT_PUBLIC_GOOGLE_CLIENT_ID 为空）');
      return;
    }

    loadGoogleScript()
      .then(() => {
        if (cancelled) return;
        if (initializedRef.current) {
          setReady(true);
          return;
        }
        try {
          window.google.accounts.id.initialize({
            client_id: googleClientId,
            callback: handleGoogleCallback,
            auto_select: false,
            ux_mode: 'popup',
            use_fedcm_for_prompt: true,
          });
          initializedRef.current = true;
          setReady(true);
        } catch (err) {
          console.error('Google Sign-In init error:', err);
          setError('Google 登录初始化失败，请刷新页面重试');
        }
      })
      .catch((err) => {
        if (!cancelled) {
          console.error('Google SDK 加载失败:', err);
          setError('无法加载 Google 登录组件，请检查网络后刷新重试');
        }
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // SDK 准备好后渲染 Google 官方按钮（点击后弹出标准 OAuth 弹窗，兼容 Safari / 第三方 cookie 受限场景）
  useEffect(() => {
    if (!ready || !buttonRef.current) return;
    try {
      // 清空容器，避免重复渲染
      buttonRef.current.innerHTML = '';
      window.google.accounts.id.renderButton(buttonRef.current, {
        theme: 'outline',
        size: 'large',
        type: 'standard',
        text: 'signin_with',
        shape: 'rectangular',
        logo_alignment: 'left',
        width: 320,
      });
    } catch (err) {
      console.error('Google renderButton error:', err);
      setError('Google 登录按钮渲染失败，请刷新页面重试');
    }
  }, [ready]);

  return (
    <div className="w-full">
      {/* Google 官方渲染的登录按钮容器 */}
      <div
        ref={buttonRef}
        className="w-full flex justify-center min-h-[44px] items-center"
        aria-busy={loading || !ready}
      />

      {/* 加载占位/状态 */}
      {!ready && !error && (
        <p className="mt-2 text-center text-xs text-slate-400">正在加载 Google 登录…</p>
      )}
      {loading && (
        <p className="mt-2 text-center text-xs text-slate-500">正在登录…</p>
      )}
      {error && (
        <div className="mt-2 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
          {error}
        </div>
      )}
    </div>
  );
}

declare global {
  interface Window {
    google: any;
  }
}
