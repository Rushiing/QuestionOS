'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '../../components/AuthButton';
import { apiPath } from '../../lib/runtime-config';

interface DelegationResult {
  status: 'success' | 'error';
  agentId?: string;
  message?: string;
}

export default function AgentOnboardingPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const [delegating, setDelegating] = useState(false);
  const [result, setResult] = useState<DelegationResult | null>(null);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace('/login');
      return;
    }
  }, [authLoading, user, router]);

  const handleDelegation = async () => {
    setDelegating(true);
    setResult(null);
    try {
      const response = await fetch(apiPath('/api/v1/agents/delegate'), {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
      });
      const data = await response.json();
      setResult(data);
    } catch (error) {
      console.error('Delegation failed:', error);
      setResult({
        status: 'error',
        message: '委托失败，请稍后重试',
      });
    } finally {
      setDelegating(false);
    }
  };

  if (authLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-teal-50 to-white">
        <div className="animate-pulse text-gray-400">加载中...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-teal-50 to-white">
      <header className="sticky top-0 z-40 border-b border-gray-200 bg-white/80 backdrop-blur-sm">
        <div className="max-w-2xl mx-auto px-6 py-4">
          <button
            onClick={() => router.back()}
            className="flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            返回
          </button>
          <h1 className="text-2xl font-bold text-gray-900 mt-4">接入第三方 Agent</h1>
          <p className="text-gray-600 text-sm mt-1">一键委托 OpenClaw Agent 完成系统接入</p>
        </div>
      </header>

      <main className="max-w-2xl mx-auto px-6 py-12">
        <div className="bg-white rounded-2xl border border-gray-200 p-8 text-center">
          {!result ? (
            <>
              <div className="flex justify-center mb-6">
                <div className="flex items-center justify-center h-12 w-12 rounded-lg bg-teal-100">
                  <svg className="h-8 w-8 text-teal-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                  </svg>
                </div>
              </div>
              <h2 className="text-xl font-semibold text-gray-900 mb-3">准备委托？</h2>
              <p className="text-gray-600 mb-8">
                点击下方按钮，系统将自动配置并启动一个 OpenClaw Agent 来完成接入流程。
              </p>
              <button
                onClick={handleDelegation}
                disabled={delegating}
                className="px-6 py-3 bg-teal-600 text-white rounded-lg font-medium hover:bg-teal-700 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-2 justify-center mx-auto"
              >
                {delegating ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin"></div>
                    委托中...
                  </>
                ) : (
                  <>
                    <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                      <path d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z" />
                    </svg>
                    一键委托
                  </>
                )}
              </button>
            </>
          ) : result.status === 'success' ? (
            <>
              <div className="flex justify-center mb-6">
                <div className="flex items-center justify-center h-12 w-12 rounded-lg bg-green-100">
                  <svg className="h-8 w-8 text-green-600" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clipRule="evenodd" />
                  </svg>
                </div>
              </div>
              <h2 className="text-xl font-semibold text-gray-900 mb-3">委托成功！</h2>
              {result.agentId && (
                <div className="bg-gray-50 rounded-lg p-4 mb-6 text-left">
                  <p className="text-sm text-gray-600 mb-2">Agent ID</p>
                  <p className="font-mono text-sm text-gray-900">{result.agentId}</p>
                </div>
              )}
              <p className="text-gray-600 mb-6">
                Agent 已启动，正在自动完成接入流程。您可以关闭此页面，系统将在后台继续运行。
              </p>
              <button
                onClick={() => router.back()}
                className="px-6 py-3 bg-gray-200 text-gray-900 rounded-lg font-medium hover:bg-gray-300 transition-colors"
              >
                返回
              </button>
            </>
          ) : (
            <>
              <div className="flex justify-center mb-6">
                <div className="flex items-center justify-center h-12 w-12 rounded-lg bg-red-100">
                  <svg className="h-8 w-8 text-red-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                  </svg>
                </div>
              </div>
              <h2 className="text-xl font-semibold text-gray-900 mb-3">委托失败</h2>
              <p className="text-gray-600 mb-6">{result.message || '发生未知错误'}</p>
              <button
                onClick={() => setResult(null)}
                className="px-6 py-3 bg-teal-600 text-white rounded-lg font-medium hover:bg-teal-700 transition-colors"
              >
                重试
              </button>
            </>
          )}
        </div>
      </main>
    </div>
  );
}
