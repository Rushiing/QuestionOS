'use client';

import { useRouter } from 'next/navigation';
import { useAuth } from '../../components/AuthButton';
import { useEffect } from 'react';

export default function DiagnosticPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace('/login');
    }
  }, [authLoading, user, router]);

  if (authLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-teal-50 to-white">
        <div className="animate-pulse text-gray-400">加载中...</div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gradient-to-br from-teal-50 to-white">
      {/* Header */}
      <header className="sticky top-0 z-40 border-b border-gray-200 bg-white/80 backdrop-blur-sm">
        <div className="max-w-4xl mx-auto px-6 py-4">
          <button
            onClick={() => router.back()}
            className="flex items-center gap-2 text-sm text-gray-600 hover:text-gray-900 transition-colors"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 19l-7-7 7-7" />
            </svg>
            返回
          </button>
          <h1 className="text-2xl font-bold text-gray-900 mt-4">诊断报告</h1>
          <p className="text-gray-600 text-sm mt-1">账户下的关键指标与洞察（开发中）</p>
        </div>
      </header>

      {/* Main Content */}
      <div className="max-w-4xl mx-auto px-6 py-12">
        <div className="grid gap-6">
          {/* Placeholder Card */}
          <div className="rounded-2xl border-2 border-dashed border-teal-200 bg-teal-50/50 p-8 sm:p-12 text-center">
            <div className="inline-flex items-center justify-center w-16 h-16 rounded-full bg-teal-100 mb-4">
              <svg className="w-8 h-8 text-teal-600 animate-pulse" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
            </div>
            <h2 className="text-lg font-semibold text-gray-900 mb-2">诊断报告生成中</h2>
            <p className="text-gray-600 text-sm max-w-xs mx-auto">
              这个功能正在开发中。将展示你的账户统计、对话质量分析、学习进展等关键洞察。
            </p>
          </div>

          {/* Coming Soon Items */}
          <div className="grid sm:grid-cols-3 gap-4 mt-8">
            <div className="rounded-xl border border-gray-200 bg-white p-6">
              <div className="text-2xl font-bold text-teal-600 mb-2">—</div>
              <h3 className="font-semibold text-gray-900 text-sm mb-1">对话总数</h3>
              <p className="text-xs text-gray-500">所有已完成的沙盘推演</p>
            </div>
            <div className="rounded-xl border border-gray-200 bg-white p-6">
              <div className="text-2xl font-bold text-teal-600 mb-2">—</div>
              <h3 className="font-semibold text-gray-900 text-sm mb-1">平均深度</h3>
              <p className="text-xs text-gray-500">Round 推进平均轮数</p>
            </div>
            <div className="rounded-xl border border-gray-200 bg-white p-6">
              <div className="text-2xl font-bold text-teal-600 mb-2">—</div>
              <h3 className="font-semibold text-gray-900 text-sm mb-1">问题质量</h3>
              <p className="text-xs text-gray-500">基于溶解检测的评分</p>
            </div>
          </div>

          {/* Info Box */}
          <div className="mt-8 rounded-xl bg-blue-50 border border-blue-200 p-4 sm:p-6">
            <div className="flex gap-3">
              <div className="flex-shrink-0">
                <svg className="w-5 h-5 text-blue-600 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M18 10a8 8 0 11-16 0 8 8 0 0116 0zm-7-4a1 1 0 11-2 0 1 1 0 012 0zM9 9a1 1 0 000 2v3a1 1 0 001 1h1a1 1 0 100-2v-3a1 1 0 00-1-1H9z" clipRule="evenodd" />
                </svg>
              </div>
              <div>
                <h3 className="font-medium text-blue-900 text-sm">关于诊断报告</h3>
                <p className="text-blue-800 text-xs mt-1">
                  诊断报告将基于你的所有对话历史，提供个性化的洞察和改进建议。帮助你更好地理解自己的思维模式和决策特点。
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
