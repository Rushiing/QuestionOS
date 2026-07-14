'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { GoogleLoginButton } from '../../components/GoogleLoginButton';
import { apiPath } from '../../lib/runtime-config';

export default function RegisterPage() {
  const router = useRouter();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [name, setName] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [emailSent, setEmailSent] = useState(false);

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (password !== confirmPassword) {
      setError('两次输入的密码不一致');
      return;
    }

    if (password.length < 6) {
      setError('密码至少需要 6 个字符');
      return;
    }

    setLoading(true);

    try {
      const response = await fetch(apiPath('/api/auth/register'), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password, name: name || undefined }),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.detail || '注册失败');
      }

      if (data.access_token) {
        localStorage.setItem('token', data.access_token);
        localStorage.setItem('user', JSON.stringify(data.user));
        router.push('/');
      }
    } catch (err: any) {
      setError(err.message || '注册失败');
    } finally {
      setLoading(false);
    }
  };

  // 邮件已发送状态
  if (emailSent) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-slate-50 to-white flex items-center justify-center px-4 py-8">
        <div className="w-full max-w-md text-center">
          <div className="mx-auto mb-6 flex h-20 w-20 items-center justify-center rounded-full bg-[#edf5ef]">
            <svg className="h-10 w-10 text-[#2f6a4a]" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 8l7.89 5.26a2 2 0 002.22 0L21 8M5 19h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
            </svg>
          </div>
          <h1 className="text-2xl font-semibold text-slate-800 mb-2">验证邮件已发送</h1>
          <p className="text-slate-500 mb-6">
            我们已向 <span className="font-medium text-slate-700">{email}</span> 发送验证邮件
          </p>
          <p className="text-sm text-slate-400 mb-6">
            请点击邮件中的链接完成注册。如果没有收到，请检查垃圾邮件文件夹。
          </p>
          <button
            onClick={() => router.push('/login')}
            className="rounded-xl bg-[#2f6a4a] px-6 py-3 font-medium text-white transition-colors hover:bg-[#244f39]"
          >
            返回登录
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-50 to-white flex items-center justify-center px-4 py-8">
      <div className="w-full max-w-md">
        {/* Logo */}
        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-slate-800">QuestionOS</h1>
          <p className="text-slate-500 mt-2">问题校准助手</p>
        </div>

        {/* Register Form */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200 p-8">
          <h2 className="text-xl font-semibold text-slate-800 mb-6">注册账号</h2>

          <form onSubmit={handleRegister} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">
                昵称 <span className="text-slate-400">(可选)</span>
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="你的昵称"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 transition-colors focus:border-[#2f6a4a] focus:bg-white focus:outline-none"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">
                邮箱
              </label>
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="your@email.com"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 transition-colors focus:border-[#2f6a4a] focus:bg-white focus:outline-none"
                required
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">
                密码
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="至少 6 个字符"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 transition-colors focus:border-[#2f6a4a] focus:bg-white focus:outline-none"
                required
                minLength={6}
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-slate-700 mb-1">
                确认密码
              </label>
              <input
                type="password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="再次输入密码"
                className="w-full rounded-xl border border-slate-200 bg-slate-50 px-4 py-3 transition-colors focus:border-[#2f6a4a] focus:bg-white focus:outline-none"
                required
              />
            </div>

            {error && (
              <div className="text-red-600 text-sm bg-red-50 px-4 py-2 rounded-lg">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full rounded-xl bg-[#2f6a4a] py-3 font-medium text-white transition-colors hover:bg-[#244f39] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {loading ? '注册中...' : '注册'}
            </button>
          </form>

          <div className="relative my-6">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-slate-200"></div>
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="px-4 bg-white text-slate-500">或</span>
            </div>
          </div>

          <GoogleLoginButton />

          <p className="text-center text-slate-500 text-sm mt-6">
            已有账号？{' '}
            <a href="/login" className="font-medium text-[#2f6a4a] hover:text-[#244f39]">
              立即登录
            </a>
          </p>
        </div>
      </div>
    </div>
  );
}
