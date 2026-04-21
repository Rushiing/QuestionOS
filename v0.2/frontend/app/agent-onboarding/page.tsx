'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '../../components/AuthButton';
import { apiPath } from '../../lib/runtime-config';

interface OnboardingPacket {
  version: string;
  goal: string;
  questionos: {
    baseUrl: string;
    capabilitiesUrl: string;
    registerUrl: string;
    instancesUrl: string;
    probeTemplate: {
      invokeUrlTemplate: string;
      input: string;
    };
  };
  registerPayloadSchema: Record<string, string>;
  successCriteria: string[];
  securityNote: string;
}

export default function AgentOnboardingPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const [packet, setPacket] = useState<OnboardingPacket | null>(null);
  const [loading, setLoading] = useState(true);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace('/login');
      return;
    }
    fetchOnboardingPacket();
  }, [authLoading, user, router]);

  const fetchOnboardingPacket = async () => {
    try {
      const response = await fetch(apiPath('/api/v1/agents/onboarding-packet'), {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      });
      if (response.ok) {
        const data = await response.json();
        setPacket(data);
      }
    } catch (error) {
      console.error('Failed to fetch onboarding packet:', error);
    } finally {
      setLoading(false);
    }
  };

  const copyToClipboard = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (error) {
      console.error('Failed to copy:', error);
    }
  };

  const generateOnboardingCommand = () => {
    if (!packet) return '';
    return `# QuestionOS 三方 Agent 接入流程\n# 将以下信息提供给你的 OpenClaw Agent\n\nBASE_URL="${packet.questionos.baseUrl}"\nCAPABILITIES_URL="${packet.questionos.capabilitiesUrl}"\nREGISTER_URL="${packet.questionos.registerUrl}"\nINSTANCES_URL="${packet.questionos.instancesUrl}"\nPROBE_URL="${packet.questionos.probeTemplate.invokeUrlTemplate}"\nPROBE_INPUT="${packet.questionos.probeTemplate.input}"\n\n# Agent 应该：\n# 1. 调用 CAPABILITIES_URL 获取系统能力\n# 2. 根据能力注册自己：POST REGISTER_URL\n# 3. 调用 INSTANCES_URL 验证注册\n# 4. 探活测试：POST PROBE_URL`;
  };

  if (authLoading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-teal-50 to-white">
        <div className="animate-pulse text-gray-400">加载中...</div>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gradient-to-br from-teal-50 to-white">
        <div className="animate-pulse text-gray-400">获取接入信息中...</div>
      </div>
    );
  }

  if (!packet) {
    return (
      <div className="min-h-screen bg-gradient-to-br from-teal-50 to-white">
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
            <h1 className="text-2xl font-bold text-gray-900 mt-4">三方接入</h1>
          </div>
        </header>
        <div className="max-w-4xl mx-auto px-6 py-12 text-center">
          <p className="text-gray-600">无法加载接入信息，请稍后重试</p>
        </div>
      </div>
    );
  }

  const command = generateOnboardingCommand();

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
          <h1 className="text-2xl font-bold text-gray-900 mt-4">接入第三方 Agent</h1>
          <p className="text-gray-600 text-sm mt-1">让 OpenClaw Agent 自主完成系统接入</p>
        </div>
      </header>

      {/* Main Content */}
      <main className="max-w-4xl mx-auto px-6 py-12">
        {/* Goal Section */}
        <div className="bg-white rounded-2xl border border-gray-200 p-6 sm:p-8 mb-8">
          <div className="flex items-start gap-4">
            <div className="flex-shrink-0">
              <div className="flex items-center justify-center h-10 w-10 rounded-lg bg-teal-100">
                <svg className="h-6 w-6 text-teal-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              </div>
            </div>
            <div className="flex-1">
              <h2 className="text-lg font-semibold text-gray-900 mb-2">目标</h2>
              <p className="text-gray-700">{packet.goal}</p>
            </div>
          </div>
        </div>

        {/* Code Block */}
        <div className="bg-white rounded-2xl border border-gray-200 overflow-hidden mb-8">
          <div className="bg-gray-50 border-b border-gray-200 px-6 py-3 flex items-center justify-between">
            <h2 className="font-semibold text-gray-900">接入代码（复制给你的 Agent）</h2>
            <button
              onClick={() => copyToClipboard(command)}
              className={`px-3 py-1.5 text-sm rounded-lg transition-colors ${
                copied
                  ? 'bg-green-100 text-green-700'
                  : 'bg-teal-100 text-teal-700 hover:bg-teal-200'
              }`}
            >
              {copied ? '✓ 已复制' : '复制'}
            </button>
          </div>
          <pre className="p-6 overflow-x-auto bg-gray-900 text-gray-100 text-sm font-mono">
            {command}
          </pre>
        </div>

        {/* Configuration Schema */}
        <div className="grid sm:grid-cols-2 gap-8 mb-8">
          <div className="bg-white rounded-2xl border border-gray-200 p-6">
            <h3 className="font-semibold text-gray-900 mb-4">注册参数</h3>
            <dl className="space-y-3">
              {Object.entries(packet.registerPayloadSchema).map(([key, desc]) => (
                <div key={key}>
                  <dt className="font-mono text-sm text-teal-600">{key}</dt>
                  <dd className="text-sm text-gray-600 mt-1">{desc}</dd>
                </div>
              ))}
            </dl>
          </div>

          <div className="bg-white rounded-2xl border border-gray-200 p-6">
            <h3 className="font-semibold text-gray-900 mb-4">成功标准</h3>
            <ul className="space-y-2">
              {packet.successCriteria.map((criterion, i) => (
                <li key={i} className="flex gap-2 text-sm text-gray-700">
                  <span className="text-teal-600 font-bold">✓</span>
                  <span>{criterion}</span>
                </li>
              ))}
            </ul>
          </div>
        </div>

        {/* Security Note */}
        <div className="bg-amber-50 border border-amber-200 rounded-2xl p-6">
          <div className="flex gap-3">
            <div className="flex-shrink-0">
              <svg className="h-5 w-5 text-amber-600 mt-0.5" fill="currentColor" viewBox="0 0 20 20">
                <path fillRule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clipRule="evenodd" />
              </svg>
            </div>
            <div>
              <h3 className="font-semibold text-amber-900 mb-1">安全提示</h3>
              <p className="text-amber-800 text-sm">{packet.securityNote}</p>
            </div>
          </div>
        </div>

        {/* Endpoints Reference */}
        <div className="mt-12 bg-white rounded-2xl border border-gray-200 p-6">
          <h3 className="font-semibold text-gray-900 mb-4">关键端点</h3>
          <div className="space-y-3 text-sm">
            <div className="flex flex-col sm:flex-row sm:items-start gap-2">
              <dt className="font-mono text-teal-600 min-w-fit">Capabilities</dt>
              <dd className="text-gray-600 break-all">{packet.questionos.capabilitiesUrl}</dd>
            </div>
            <div className="flex flex-col sm:flex-row sm:items-start gap-2">
              <dt className="font-mono text-teal-600 min-w-fit">Register</dt>
              <dd className="text-gray-600 break-all">{packet.questionos.registerUrl}</dd>
            </div>
            <div className="flex flex-col sm:flex-row sm:items-start gap-2">
              <dt className="font-mono text-teal-600 min-w-fit">Instances</dt>
              <dd className="text-gray-600 break-all">{packet.questionos.instancesUrl}</dd>
            </div>
            <div className="flex flex-col sm:flex-row sm:items-start gap-2">
              <dt className="font-mono text-teal-600 min-w-fit">Probe</dt>
              <dd className="text-gray-600 break-all">{packet.questionos.probeTemplate.invokeUrlTemplate}</dd>
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
