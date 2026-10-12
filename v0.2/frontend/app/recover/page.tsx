'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '../../components/AuthProvider';
import { apiPath } from '../../lib/runtime-config';

export default function RecoverPage() {
  const router = useRouter();
  const { setUserFromLogin } = useAuth();
  const [email, setEmail] = useState('');
  const [token, setToken] = useState('');
  const [password, setPassword] = useState('');
  const [sent, setSent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const response = await fetch(apiPath(`/api/auth/email-claim/${sent ? 'confirm' : 'request'}`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(sent ? { email, token: token.trim(), password } : { email }),
      });
      const data = await response.json();
      if (!response.ok) throw new Error(data.detail || '验证失败，请检查输入或稍后重试');
      if (sent) {
        localStorage.setItem('token', data.access_token);
        setUserFromLogin(data.user);
        router.push('/history');
      } else {
        setSent(true);
        setMessage(data.detail);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '请求失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="min-h-screen bg-slate-50 flex items-center justify-center px-4">
      <div className="w-full max-w-md rounded-2xl bg-white border border-slate-200 p-8">
        <h1 className="text-xl font-semibold text-slate-800">找回旧账号</h1>
        <p className="mt-3 mb-6 text-sm text-slate-600">曾通过 Google 登录？验证原邮箱后设置密码，保留原账号与全部历史记录。已有密码请直接登录。</p>
        <form onSubmit={submit} className="space-y-4">
          <label className="block text-sm">原账号邮箱
            <input type="email" autoComplete="email" required maxLength={254} readOnly={sent || busy} value={email} onChange={e => setEmail(e.target.value)} className="mt-1 w-full rounded-xl border px-4 py-3" />
          </label>
          {sent && <>
            <label className="block text-sm">邮件中的验证口令
              <input required autoComplete="one-time-code" spellCheck={false} maxLength={43} value={token} onChange={e => setToken(e.target.value.trim())} className="mt-1 w-full rounded-xl border px-4 py-3" />
            </label>
            <label className="block text-sm">设置密码
              <input type="password" autoComplete="new-password" required minLength={6} maxLength={72} value={password} onChange={e => setPassword(e.target.value)} className="mt-1 w-full rounded-xl border px-4 py-3" />
            </label>
          </>}
          {message && <p role="status" className="text-sm text-slate-600">{message}</p>}
          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
          <button disabled={busy} className="w-full rounded-xl bg-[#2f6a4a] py-3 text-white disabled:opacity-50">{busy ? '处理中…' : sent ? '验证并恢复账号' : '发送验证邮件'}</button>
          {sent && <button type="button" disabled={busy} onClick={() => { setSent(false); setToken(''); setPassword(''); setError(''); setMessage(''); }} className="w-full text-sm text-[#2f6a4a]">更换邮箱或重新申请口令</button>}
        </form>
        <p className="mt-6 text-sm text-slate-500">口令 10 分钟内有效。无法访问原邮箱时，暂时无法恢复登录；历史记录会继续保留。</p>
        <a href="/login" className="mt-4 inline-block text-sm text-[#2f6a4a]">返回登录</a>
      </div>
    </main>
  );
}
