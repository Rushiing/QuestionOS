'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { GoogleLoginButton } from '../../components/GoogleLoginButton';
import { apiPath } from '../../lib/runtime-config';

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const response = await fetch(apiPath('/api/auth/login'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail || '登录失败');
      localStorage.setItem('token', data.access_token);
      localStorage.setItem('user', JSON.stringify(data.user));
      router.push('/');
    } catch (err: any) {
      setError(err.message || '登录失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-gray-50 to-white flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-slate-800">QuestionOS</h1>
          <p className="text-slate-500 mt-2">问题校准助手</p>
        </div>

        {/* Login Form */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-6">登录</h2>

          <form onSubmit={handleLogin} className="space-y-4 mb-6">
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="邮箱" required className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 focus:border-[#2f6a4a] focus:bg-white focus:outline-none" />
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="密码" required className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 focus:border-[#2f6a4a] focus:bg-white focus:outline-none" />

          {error && (
            <div className="text-red-600 text-sm bg-red-50 px-4 py-2 rounded-lg mb-4">
              {error}
            </div>
          )}

            <button type="submit" disabled={loading} className="w-full rounded-xl bg-[#2f6a4a] py-3 font-medium text-white hover:bg-[#244f39] disabled:opacity-50">
              {loading ? '登录中...' : '登录'}
            </button>
          </form>

          <div className="relative my-6"><div className="absolute inset-0 flex items-center"><div className="w-full border-t border-slate-200" /></div><div className="relative flex justify-center text-sm"><span className="bg-white px-4 text-slate-500">或</span></div></div>

          <GoogleLoginButton />

          <p className="text-center text-slate-500 text-sm mt-6">
            还没有账号？{' '}
            <a href="/register" className="font-medium text-[#2f6a4a] hover:text-[#244f39]">
              立即注册
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
