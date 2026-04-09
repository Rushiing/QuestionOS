'use client';

import { useState, useEffect, useRef, useMemo } from 'react';
import { flushSync } from 'react-dom';
import { useRouter } from 'next/navigation';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AuthButton, useAuth } from '../../components/AuthButton';
import { AgentInstance, OnboardingJobStatus, sandboxClient } from '../../lib/sandbox-client';
import { normalizeIntegratorExpertBullets } from '../../lib/integrator-markdown';
import { takeBackgroundContext, wrapUserMessageWithBackground } from '../../lib/background-context';
import { CONSULT_RECOMMENDED_SCENARIOS } from '../../lib/recommended-scenarios';
import { handleEnterToSubmit } from '../../lib/keyboard-ime';
import { formatCalibrationJsonToMarkdown } from '../../lib/calibration-json-to-markdown';
import { SANDBOX_TURN_MAX_WAIT_MS } from '../../lib/runtime-config';

interface Agent {
  id: string;
  name: string;
  avatar: string;
  description: string;
  role: string;
}

interface Message {
  id: string;
  role: 'user' | 'agent' | 'system';
  agent_id?: string;
  agent_name?: string;
  agent_avatar?: string;
  content: string;
  is_streaming?: boolean;
}

/** 沙盘 Agent 回复：统一标题/分隔线/列表/表格版式（与整合报告等 Markdown 结构配合） */
const consultAgentMarkdownComponents: Components = {
  h2: ({ node, children, ...props }) => (
    <h2 className="mt-6 mb-2 pb-2 text-lg font-bold tracking-tight text-slate-900 border-b border-slate-200 first:mt-0" {...props}>
      {children}
    </h2>
  ),
  h3: ({ node, children, ...props }) => (
    <h3 className="mt-5 mb-2 text-base font-semibold text-slate-800" {...props}>
      {children}
    </h3>
  ),
  hr: ({ node, ...props }) => <hr className="my-5 border-0 border-t border-slate-200" {...props} />,
  p: ({ node, children, ...props }) => (
    <p className="my-2.5 leading-relaxed text-slate-700" {...props}>
      {children}
    </p>
  ),
  ul: ({ node, children, ...props }) => (
    <ul className="my-3 space-y-2 pl-5 list-disc marker:text-slate-400 text-slate-700" {...props}>
      {children}
    </ul>
  ),
  ol: ({ node, children, ...props }) => (
    <ol className="my-3 list-decimal space-y-2 pl-5 text-slate-700 marker:text-slate-400" {...props}>
      {children}
    </ol>
  ),
  li: ({ node, children, ...props }) => (
    <li className="pl-1 leading-relaxed" {...props}>
      {children}
    </li>
  ),
  strong: ({ node, children, ...props }) => (
    <strong className="font-semibold text-slate-900" {...props}>
      {children}
    </strong>
  ),
  table: ({ node, children, ...props }) => (
    <div className="my-5 w-full overflow-x-auto rounded-xl border border-slate-200 bg-white shadow-sm">
      <table className="w-full min-w-[300px] border-collapse text-left text-[0.9375rem] text-slate-700" {...props}>
        {children}
      </table>
    </div>
  ),
  thead: ({ node, children, ...props }) => (
    <thead className="bg-slate-50" {...props}>
      {children}
    </thead>
  ),
  tbody: ({ node, children, ...props }) => (
    <tbody className="divide-y divide-slate-100 bg-white" {...props}>
      {children}
    </tbody>
  ),
  tr: ({ node, children, ...props }) => (
    <tr className="transition-colors hover:bg-slate-50/90" {...props}>
      {children}
    </tr>
  ),
  th: ({ node, children, ...props }) => (
    <th className="border border-slate-200 px-3 py-2.5 align-middle text-xs font-semibold text-slate-600" {...props}>
      {children}
    </th>
  ),
  td: ({ node, children, ...props }) => (
    <td className="border border-slate-200 px-3 py-2.5 align-middle leading-relaxed" {...props}>
      {children}
    </td>
  ),
};

function agentMarkdownSource(msg: Message): string {
  const raw = msg.content || (msg.is_streaming ? '...' : '');
  if (raw === '...') return raw;
  const isIntegrator =
    msg.role === 'agent' &&
    (msg.agent_id === 'integrator' || msg.agent_name === '首席整合官');
  if (isIntegrator) {
    return normalizeIntegratorExpertBullets(raw);
  }
  return formatCalibrationJsonToMarkdown(raw);
}

/** 外聘区「🧩 已接入 Agents」预览卡片：先隐藏；loadInstances / 沙盘三方 slot 仍工作 */
const SHOW_EMBEDDED_CONNECTED_AGENTS_CARD = false;

export default function ConsultPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loadingAgents, setLoadingAgents] = useState(true);
  const [sessionStarted, setSessionStarted] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputMessage, setInputMessage] = useState('');
  const [isAgentResponding, setIsAgentResponding] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [integrationEntryOpen, setIntegrationEntryOpen] = useState(false);
  const [onboardingStep, setOnboardingStep] = useState<1 | 2 | 3>(1);
  const [openClawAgentId, setOpenClawAgentId] = useState('');
  const [openClawEndpoint, setOpenClawEndpoint] = useState('http://127.0.0.1:18789');
  const [openClawApiKey, setOpenClawApiKey] = useState('');
  const [openClawModel, setOpenClawModel] = useState('custom-dogfooding/pitaya-03-20');
  const [isCreatingIntegration, setIsCreatingIntegration] = useState(false);
  const [isTestingIntegration, setIsTestingIntegration] = useState(false);
  const [integrationHint, setIntegrationHint] = useState('');
  const [onboardingJobId, setOnboardingJobId] = useState<string | null>(null);
  const [onboardingJobToken, setOnboardingJobToken] = useState<string | null>(null);
  const [onboardingInstructionUrl, setOnboardingInstructionUrl] = useState<string | null>(null);
  const [onboardingJobStatus, setOnboardingJobStatus] = useState<OnboardingJobStatus | null>(null);
  const [isGeneratingOnboardingPacket, setIsGeneratingOnboardingPacket] = useState(false);
  const [integrationResult, setIntegrationResult] = useState<{
    ok: boolean;
    firstChunkMs?: number;
    hasDone?: boolean;
    message?: string;
  } | null>(null);
  const [instances, setInstances] = useState<AgentInstance[]>([]);
  const [loadingInstances, setLoadingInstances] = useState(false);
  const [puzzleHover, setPuzzleHover] = useState(false);
  const [debugPanelOpen, setDebugPanelOpen] = useState(false);
  const [debugLogs, setDebugLogs] = useState<string[]>([]);
  /** 未开局时先展示推荐场景，点选或「自己写」后再露出议题输入区 */
  const [starterComposerVisible, setStarterComposerVisible] = useState(false);
  const lastSeqRef = useRef<number>(0);
  const hasStreamActivityRef = useRef<boolean>(false);
  /** 超时插入的「本轮较慢」气泡 id；若随后收到 turn_done 则移除，避免与完整回复并存 */
  const slowRoundWarningIdRef = useRef<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const instanceHoverScrollRef = useRef<HTMLDivElement>(null);
  const isLoggedIn = !!user;

  const defaultAgentList: Agent[] = [
    { id: 'auditor', name: '利益审计师', avatar: '💰', description: '量化一切，只算ROI', role: 'sandbox' },
    { id: 'risk_officer', name: '风险预测官', avatar: '⚠️', description: '模拟最坏情况，找崩盘点', role: 'sandbox' },
    { id: 'value_judge', name: '价值裁判', avatar: '⚖️', description: '拷问动机，防止异化', role: 'sandbox' },
    { id: 'integrator', name: '首席整合官', avatar: '🏛️', description: '收束冲突，输出决策沙盘', role: 'sandbox' },
    { id: 'third-party-adapter', name: '外聘 Agent', avatar: '🧩', description: 'OpenClaw 等外部 Agent', role: 'third-party' },
  ];

  const thirdPartyIdSet = useMemo(
    () => new Set(instances.map((i) => i.agentId)),
    [instances]
  );

  const sessionThirdPartyDisplay = useMemo(() => {
    const hit = [...messages].reverse().find(
      (m) =>
        m.role === 'agent' &&
        m.agent_id &&
        (thirdPartyIdSet.has(m.agent_id) || m.agent_id === 'third-party-adapter')
    );
    if (!hit) return null;
    const n = (hit.agent_name || '').trim();
    return n || hit.agent_id || null;
  }, [messages, thirdPartyIdSet]);

  /** 外聘区主文案：无实例 → 兜底；本会话已有外聘发言 → 显示该 agent；否则显示当前注册列表首选（最新） */
  const puzzlePrimaryLabel = useMemo(() => {
    if (instances.length === 0) return '已接入 Agents';
    if (sessionStarted && sessionThirdPartyDisplay) return sessionThirdPartyDisplay;
    return instances[0].agentId;
  }, [instances, sessionStarted, sessionThirdPartyDisplay]);

  const agentMeta = (agentId: string) => {
    if (agentId === 'auditor') {
      return { name: '利益审计师', avatar: '💰' };
    }
    if (agentId === 'risk_officer') {
      return { name: '风险预测官', avatar: '⚠️' };
    }
    if (agentId === 'value_judge') {
      return { name: '价值裁判', avatar: '⚖️' };
    }
    if (agentId === 'integrator') {
      return { name: '首席整合官', avatar: '🏛️' };
    }
    if (agentId === 'third-party-adapter') {
      const inst = instances[0];
      return { name: inst?.agentId ?? '已接入 Agents', avatar: '🧩' };
    }
    const inst = instances.find((i) => i.agentId === agentId);
    if (inst) {
      return { name: inst.agentId, avatar: '🧩' };
    }
    if (agentId && !['main-calibrate', 'auditor', 'risk_officer', 'value_judge', 'integrator'].includes(agentId)) {
      return { name: agentId, avatar: '🧩' };
    }
    return { name: '主校准 Agent', avatar: '🧠' };
  };

  // 获取 Agent 能力 & 读取传入的问题
  useEffect(() => {
    setAgents(defaultAgentList);
    sandboxClient.getCapabilities()
      .then(() => setAgents(defaultAgentList))
      .catch(err => {
        console.error('Failed to fetch agent capabilities:', err);
        setAgents(defaultAgentList);
      })
      .finally(() => {
        setLoadingAgents(false);
      });
    
    // 读取从首页传入的问题
    const storedQuestion = sessionStorage.getItem('consultQuestion');
    if (storedQuestion) {
      setInputMessage(storedQuestion);
      setStarterComposerVisible(true);
      sessionStorage.removeItem('consultQuestion');
    }
  }, []);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const loadInstances = async () => {
    setLoadingInstances(true);
    try {
      const list = await sandboxClient.listInstances();
      setInstances(list);
    } catch {
      setInstances([]);
    } finally {
      setLoadingInstances(false);
    }
  };

  useEffect(() => {
    loadInstances();
  }, []);

  useEffect(() => {
    if (integrationEntryOpen) {
      loadInstances();
    }
  }, [integrationEntryOpen]);

  useEffect(() => {
    if (!onboardingJobId) return;
    let cancelled = false;
    const doneStatus = new Set(['VERIFIED', 'FAILED']);
    const poll = async () => {
      try {
        const status = await sandboxClient.getOnboardingJobStatus(onboardingJobId);
        if (cancelled) return;
        setOnboardingJobStatus(status);
        if (status.agentId && status.status !== 'DRAFT' && status.status !== 'SUBMITTED') {
          setOpenClawAgentId(status.agentId);
        }
        if (doneStatus.has(status.status)) {
          setOnboardingStep(status.status === 'VERIFIED' ? 3 : 2);
          await loadInstances();
          return;
        }
      } catch {
        // ignore transient polling errors
      }
      if (!cancelled) {
        setTimeout(poll, 2500);
      }
    };
    poll();
    return () => {
      cancelled = true;
    };
  }, [onboardingJobId]);

  useEffect(() => {
    if (!puzzleHover || !instanceHoverScrollRef.current || instances.length < 2) return;
    const el = instanceHoverScrollRef.current;
    if (el.scrollHeight <= el.clientHeight + 2) return;
    let raf = 0;
    const tick = () => {
      el.scrollTop += 0.45;
      if (el.scrollTop >= el.scrollHeight - el.clientHeight - 1) {
        el.scrollTop = 0;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [puzzleHover, instances]);

  const openClawConfigText = [
    '# OpenClaw 原生接入配置（无需改 OpenClaw）',
    `AGENT_ID=${openClawAgentId || '<created-agent-id>'}`,
    'PROVIDER=OpenClaw',
    `ENDPOINT=${openClawEndpoint}`,
    'SCOPE=sandbox:invoke',
    `MODEL=${openClawModel}`,
    `API_KEY=${openClawApiKey ? '***已设置***' : '<required-for-openclaw>'}`,
    '',
    '# OpenClaw 原生 API（QuestionOS 会自动适配）',
    'HEALTH=GET /health',
    'INVOKE=POST /v1/chat/completions',
    '',
    '# 说明',
    `QuestionOS Capabilities URL: ${sandboxClient.capabilitiesUrl()}`,
    'QuestionOS 会在后端把请求转发为 OpenAI 兼容格式。',
  ].join('\n');

  const buildOneClickMessage = (jobId: string, token: string, instructionUrl: string): string => [
    '你是 OpenClaw 接入代理。请执行本次 QuestionOS 接入委托：',
    `- jobId: ${jobId}`,
    '- 要求：自动决定并填写 agentId/provider/endpoint/apiKey/model，完成注册与联通测试。',
    '- 规则：写入密钥前先向我确认一次，不要在公开消息里泄露密钥。',
    '',
    '请先读取机器说明（包含 submit endpoint 与 payload schema）：',
    instructionUrl,
    '',
    '提交时使用 submitToken：',
    token,
  ].join('\n');

  const normalizeOpenClawEndpoint = (value: string): string => {
    const trimmed = value.trim().replace(/\/+$/, '');
    if (trimmed.endsWith('/v1/chat/completions')) {
      return trimmed.replace(/\/v1\/chat\/completions$/, '');
    }
    return trimmed;
  };

  const createOpenClawIntegration = async () => {
    if (!isLoggedIn) {
      setIntegrationHint('请先登录后继续三方接入。');
      return;
    }
    const nextId = openClawAgentId || `openclaw-${Date.now().toString().slice(-6)}`;
    const endpoint = normalizeOpenClawEndpoint(openClawEndpoint);
    setIsCreatingIntegration(true);
    setIntegrationHint('');
    setIntegrationResult(null);
    try {
      if (!endpoint) throw new Error('OpenClaw endpoint 不能为空。');
      if (!openClawApiKey.trim()) throw new Error('OpenClaw API Key 不能为空。');
      await sandboxClient.registerAgent({
        agentId: nextId,
        provider: 'OpenClaw',
        endpoint,
        scope: 'sandbox:invoke',
        apiKey: openClawApiKey.trim(),
        model: openClawModel,
      });
      setOpenClawEndpoint(endpoint);
      setOpenClawAgentId(nextId);
      setIntegrationHint('OpenClaw 原生接入实例创建成功。');
      await loadInstances();
      setOnboardingStep(2);
    } catch (e: unknown) {
      setIntegrationHint(`创建失败：${e instanceof Error ? e.message : '请检查后端服务与参数。'}`);
    } finally {
      setIsCreatingIntegration(false);
    }
  };

  const runOpenClawConnectivityTest = async () => {
    if (!isLoggedIn) {
      setIntegrationHint('请先登录后继续三方接入。');
      return;
    }
    setIsTestingIntegration(true);
    setIntegrationResult(null);
    const startedAt = Date.now();
    try {
      let latestInstances = await sandboxClient.listInstances();
      if (!latestInstances.length) {
        const autoAgentId = openClawAgentId || `openclaw-auto-${Date.now().toString().slice(-6)}`;
        const endpoint = normalizeOpenClawEndpoint(openClawEndpoint);
        if (!endpoint) throw new Error('OpenClaw endpoint 不能为空。');
        if (!openClawApiKey.trim()) throw new Error('OpenClaw API Key 不能为空。');
        await sandboxClient.registerAgent({
          agentId: autoAgentId,
          provider: 'OpenClaw',
          endpoint,
          scope: 'sandbox:invoke',
          apiKey: openClawApiKey.trim(),
          model: openClawModel,
        });
        setOpenClawEndpoint(endpoint);
        setOpenClawAgentId(autoAgentId);
        latestInstances = await sandboxClient.listInstances();
      }
      setInstances(latestInstances);
      const preferred = latestInstances.find((it) => it.agentId === openClawAgentId);
      const targetAgentId = preferred?.agentId || latestInstances[0]?.agentId;
      if (!targetAgentId) {
        throw new Error('未找到可测试实例，请先创建或重新创建 OpenClaw 实例。');
      }
      const timeoutTask = new Promise<never>((_, reject) =>
        setTimeout(() => reject(new Error('connectivity test timeout')), 12000),
      );
      const invokeTask = sandboxClient.invokeAgent(targetAgentId, '请回复：联通成功');
      const result = await Promise.race([invokeTask, timeoutTask]);
      const firstChunkMs = Date.now() - startedAt;
      const output = result?.output || '';
      const outputPreview = output.length > 120 ? `${output.slice(0, 120)}...` : output;
      setIntegrationResult({
        ok: true,
        firstChunkMs,
        hasDone: true,
        message: outputPreview
          ? `联通成功：${outputPreview}`
          : '联通成功：已收到 OpenClaw 返回。',
      });
      setOnboardingStep(3);
    } catch (e) {
      const detail = e instanceof Error ? e.message : '未知错误';
      setIntegrationResult({
        ok: false,
        message: detail.includes('HTTP 404')
          ? '联通失败：实例不存在（HTTP 404）。请先点击“创建接入实例”后再测试。'
          : `联通失败：${detail}`,
      });
    } finally {
      setIsTestingIntegration(false);
    }
  };

  const generateOneClickOnboarding = async () => {
    if (!isLoggedIn) {
      setIntegrationHint('请先登录后继续三方接入。');
      return;
    }
    setIsGeneratingOnboardingPacket(true);
    setIntegrationHint('');
    try {
      const job = await sandboxClient.createOnboardingJob();
      setOnboardingJobId(job.jobId);
      setOnboardingJobToken(job.submitToken);
      setOnboardingInstructionUrl(job.instructionUrl);
      setOnboardingJobStatus(null);
      const text = buildOneClickMessage(job.jobId, job.submitToken, job.instructionUrl);
      await navigator.clipboard.writeText(text);
      setIntegrationHint('已复制「接入委托单」到剪贴板，发给 OpenClaw Agent 后将自动回填并联通。');
      setOnboardingStep(2);
    } catch (e: unknown) {
      setIntegrationHint(`生成失败：${e instanceof Error ? e.message : '请稍后重试。'}`);
    } finally {
      setIsGeneratingOnboardingPacket(false);
    }
  };

  const createSession = async (question: string): Promise<string> => {
    return sandboxClient.createSession('SANDBOX', question);
  };

  const streamTurnEvents = async (sid: string) => {
    let isDone = false;
    let activeAgentMsgId: string | null = null;
    const appendDebugLog = (line: string) => {
      setDebugLogs((prev) => {
        const next = [...prev, line];
        return next.length > 80 ? next.slice(next.length - 80) : next;
      });
    };
    const ensureAgentMessage = (agentId: string, sseDisplayName?: string) => {
      const base = agentMeta(agentId);
      const name = sseDisplayName?.trim() ? sseDisplayName.trim() : base.name;
      const newId = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`;
      activeAgentMsgId = newId;
      // agent_chunk 会在同一轮事件循环里紧跟 agent_start；若此处仅异步 setState，下一条 map 的 prev 可能还没有这条气泡，导致正文永远写不进去、一直停在「输出中」。
      flushSync(() => {
        setMessages((prev) => [
          ...prev,
          {
            id: newId,
            role: 'agent',
            agent_id: agentId,
            agent_name: name,
            agent_avatar: base.avatar,
            content: '',
            is_streaming: true,
          },
        ]);
      });
    };

    await sandboxClient.streamTurn(sid, lastSeqRef.current, ({ eventType, eventId, dataRaw }) => {
      appendDebugLog(`[${new Date().toLocaleTimeString('zh-CN')}] ${eventType} #${eventId || '-'} ${dataRaw.slice(0, 140)}`);
      if (eventId && !eventId.startsWith('hb-')) {
        const seq = Number(eventId);
        if (!Number.isNaN(seq)) lastSeqRef.current = Math.max(lastSeqRef.current, seq);
      }
      try {
        const parsed = JSON.parse(dataRaw);
        const content = parsed?.payload?.content || '';

        if (eventType === 'agent_start') {
          hasStreamActivityRef.current = true;
          const raw = String(content);
          const bar = raw.indexOf('|');
          const agentId = (bar >= 0 ? raw.slice(0, bar) : raw).trim() || 'main-calibrate';
          const sseName = bar >= 0 ? raw.slice(bar + 1).trim() : '';
          ensureAgentMessage(agentId, sseName || undefined);
        } else if (eventType === 'agent_chunk') {
          hasStreamActivityRef.current = true;
          if (!activeAgentMsgId) {
            ensureAgentMessage('main-calibrate');
          }
          setMessages(prev => prev.map(m =>
            m.id === activeAgentMsgId ? { ...m, content: (m.content ?? '') + content } : m
          ));
        } else if (eventType === 'agent_error') {
          hasStreamActivityRef.current = true;
          if (!activeAgentMsgId) {
            ensureAgentMessage(instances[0]?.agentId ?? 'third-party-adapter');
          }
          setMessages(prev => prev.map(m =>
            m.id === activeAgentMsgId
              ? { ...m, content: (m.content ?? '') + ((m.content ?? '') ? '\n\n' : '') + content }
              : m
          ));
        } else if (eventType === 'agent_done') {
          if (activeAgentMsgId) {
            setMessages(prev => prev.map(m =>
              m.id === activeAgentMsgId ? { ...m, is_streaming: false } : m
            ));
            activeAgentMsgId = null;
          }
        } else if (eventType === 'handoff') {
          setMessages(prev => [...prev, {
            id: `${Date.now()}-handoff`,
            role: 'system',
            content: `Agent 切换：${content}`,
          }]);
        } else if (eventType === 'done') {
          // 后端在 done 之后还会发 turn_done；若此处就停止，会漏掉 turn_done，下一轮 Last-Event-ID 重放会先收到旧的 turn_done 并误结束。
          return false;
        } else if (eventType === 'turn_done') {
          const warnId = slowRoundWarningIdRef.current;
          if (warnId) {
            slowRoundWarningIdRef.current = null;
            setMessages((prev) => prev.filter((m) => m.id !== warnId));
          }
          isDone = true;
          return true;
        }
      } catch (e) {
        console.error('Parse SSE event failed:', e);
      }
      return false;
    }, (line) => appendDebugLog(`[${new Date().toLocaleTimeString('zh-CN')}] ${line}`));

    if (!isDone) {
      return;
    }
  };

  const runTurn = async (sid: string, userText: string) => {
    setIsAgentResponding(true);
    hasStreamActivityRef.current = false;
    slowRoundWarningIdRef.current = null;
    setDebugLogs((prev) => [...prev, `--- turn start ${new Date().toLocaleTimeString('zh-CN')} ---`]);
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      setIsAgentResponding(false);
      if (hasStreamActivityRef.current) {
        setMessages(prev => prev.map(m =>
          m.is_streaming ? { ...m, is_streaming: false } : m
        ));
        const warnId = `${Date.now()}-slow`;
        slowRoundWarningIdRef.current = warnId;
        setMessages(prev => [...prev, {
          id: warnId,
          role: 'system',
          content: '本轮较慢，已停止等待；可继续追问。',
        }]);
        return;
      }
      // Fallback: recover latest persisted AGENT reply when stream is delayed/lost.
      (async () => {
        try {
          const msgList = await sandboxClient.listMessages(sid);
          const latestAgent = [...msgList].reverse().find(m => m.role === 'AGENT' && m.content?.trim());
          if (latestAgent) {
            const { name, avatar } = agentMeta('main-calibrate');
            setMessages(prev => [...prev, {
              id: `${Date.now()}-fallback`,
              role: 'agent',
              agent_id: 'main-calibrate',
              agent_name: name,
              agent_avatar: avatar,
              content: latestAgent.content,
              is_streaming: false,
            }]);
            return;
          }
        } catch {
          // ignore recovery errors
        }
        setMessages(prev => [...prev, {
          id: `${Date.now()}-timeout`,
          role: 'system',
          content: '响应超时，请点击发送重试。',
        }]);
      })();
    }, SANDBOX_TURN_MAX_WAIT_MS);

    try {
      const idemKey = `idem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      await sandboxClient.sendMessage(sid, userText, idemKey);
      if (timedOut) return;
      await streamTurnEvents(sid);
      if (timedOut) return;
    } catch (error) {
      console.error('runTurn failed:', error);
      setMessages(prev => [...prev, {
        id: `${Date.now()}-err`,
        role: 'system',
        content: '抱歉，本轮推演失败，请重试。',
      }]);
    } finally {
      clearTimeout(timeout);
      if (!timedOut) {
        setIsAgentResponding(false);
      }
    }
  };

  const handleStartSession = async () => {
    if (!inputMessage.trim() || isAgentResponding) return;
    lastSeqRef.current = 0;
    setDebugLogs([]);
    setSessionStarted(true);
    const question = inputMessage.trim();
    const userMsg: Message = {
      id: `${Date.now()}-user`,
      role: 'user',
      content: question,
    };
    setMessages([userMsg]);
    setInputMessage('');

    try {
      const sid = await createSession(question);
      setSessionId(sid);
      const bg = takeBackgroundContext();
      const payload = bg ? wrapUserMessageWithBackground(question, bg) : question;
      await runTurn(sid, payload);
    } catch (error) {
      console.error('start session failed:', error);
      setMessages(prev => [...prev, {
        id: `${Date.now()}-err`,
        role: 'system',
        content: '会话创建失败，请检查网络/鉴权，或稍后重试。',
      }]);
      setIsAgentResponding(false);
    }
  };

  const handleSendMessage = async () => {
    if (!inputMessage.trim() || isAgentResponding || !sessionId) return;
    const userText = inputMessage.trim();
    const userMsg: Message = {
      id: `${Date.now()}-user`,
      role: 'user',
      content: userText,
    };
    setMessages(prev => [...prev, userMsg]);
    setInputMessage('');
    await runTurn(sessionId, userText);
  };

  const handleReset = () => {
    setSessionStarted(false);
    setMessages([]);
    setInputMessage('');
    setStarterComposerVisible(false);
    setIsAgentResponding(false);
    setSessionId(null);
    lastSeqRef.current = 0;
    loadInstances();
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-gray-50 to-white flex flex-col">
      {/* Header */}
      <header className="bg-white/95 backdrop-blur-sm border-b border-gray-200 px-6 py-4 sticky top-0 z-10">
        <div className="max-w-4xl mx-auto flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <button
              type="button"
              onClick={() => router.push('/')}
              className="p-2 hover:bg-gray-100 rounded-lg transition-colors shrink-0"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
            </button>
            <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-teal-500 to-teal-400 flex items-center justify-center text-white text-sm font-bold shadow-md shadow-teal-500/20 shrink-0">
              Q
            </div>
            <div className="min-w-0">
              <h1 className="text-base font-bold text-gray-900 leading-tight">QuestionOS</h1>
              <p className="text-xs text-gray-500">
                <span className="font-medium text-teal-800">⚔️ 沙盘推演</span>
                {sessionStarted && sessionId ? (
                  <span className="ml-2 text-gray-400 font-mono break-all" title={sessionId}>
                    #{sessionId}
                  </span>
                ) : (
                  <span className="ml-2">多角色辩论 · 压力测试</span>
                )}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {sessionStarted && (
              <button
                onClick={handleReset}
                className="text-sm text-slate-500 hover:text-slate-700"
              >
                重新开始
              </button>
            )}
            <button
              onClick={() => setDebugPanelOpen(v => !v)}
              className="text-sm text-slate-500 hover:text-slate-700"
            >
              {debugPanelOpen ? '隐藏调试' : '调试开关'}
            </button>
            <AuthButton />
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-4xl mx-auto w-full px-6 py-4 flex flex-col">
        {/* 开始前：Agent团队展示 */}
        {!sessionStarted && (
          <div className="flex-1 flex flex-col justify-center">
            <div className="mb-8">
              {loadingAgents ? (
                <div className="flex justify-center py-8">
                  <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin"></div>
                </div>
              ) : (
                <div className="space-y-8 pt-1">
                  <div>
                    <p className="text-center text-xs text-slate-500 mb-3 tracking-wide">内置推演团队</p>
                    <div className="flex justify-center gap-6 sm:gap-8 flex-wrap">
                      {agents
                        .filter((a) => a.role === 'sandbox')
                        .map((agent) => (
                          <div key={agent.id} className="flex flex-col items-center min-w-[4.5rem]">
                            <div className="w-16 h-16 rounded-full bg-slate-100 flex items-center justify-center text-3xl mb-2">
                              {agent.avatar}
                            </div>
                            <span className="text-sm font-medium text-slate-700 text-center leading-tight">{agent.name}</span>
                          </div>
                        ))}
                    </div>
                  </div>
                  <div>
                    <p className="text-center text-xs text-slate-500 mb-3 tracking-wide">外聘推演专家</p>
                    <div className="flex justify-center gap-8 sm:gap-10 flex-wrap items-start">
                      {SHOW_EMBEDDED_CONNECTED_AGENTS_CARD && (
                        <div
                          className="relative flex flex-col items-center min-w-[4.5rem]"
                          onMouseEnter={() => setPuzzleHover(true)}
                          onMouseLeave={() => setPuzzleHover(false)}
                        >
                          <div className="w-16 h-16 rounded-full bg-emerald-50 border border-emerald-100 flex items-center justify-center text-3xl mb-2">
                            🧩
                          </div>
                          <span
                            className="text-sm font-medium text-slate-700 text-center leading-tight max-w-[10rem] truncate"
                            title={puzzlePrimaryLabel}
                          >
                            {puzzlePrimaryLabel}
                          </span>
                          {instances.length > 0 && puzzleHover && (
                            <div
                              className="absolute left-1/2 bottom-full z-30 mb-2 w-56 -translate-x-1/2 rounded-lg border border-slate-200 bg-white px-2 py-2 shadow-lg"
                              role="tooltip"
                            >
                              <p className="mb-1 px-1 text-[10px] font-medium uppercase tracking-wide text-slate-500">
                                已接入实例
                              </p>
                              <div
                                ref={instanceHoverScrollRef}
                                className="max-h-28 overflow-y-auto text-left text-xs text-slate-700"
                              >
                                {instances.map((it) => (
                                  <div
                                    key={it.agentId}
                                    className="truncate rounded px-1.5 py-1 hover:bg-slate-50"
                                    title={`${it.agentId} · ${it.provider}`}
                                  >
                                    <span className="font-medium">{it.agentId}</span>
                                    <span className="ml-1 text-slate-400">{it.provider}</span>
                                  </div>
                                ))}
                              </div>
                            </div>
                          )}
                        </div>
                      )}
                      <button
                        type="button"
                        onClick={() => {
                          if (authLoading) return;
                          if (!isLoggedIn) {
                            setIntegrationHint('请先登录后继续三方接入。');
                            router.push('/login');
                            return;
                          }
                          setIntegrationEntryOpen((v) => !v);
                        }}
                        className="flex flex-col items-center min-w-[4.5rem] rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-teal-400"
                      >
                        <div
                          className={`w-16 h-16 rounded-full border-2 flex items-center justify-center text-2xl mb-2 transition-colors ${
                            integrationEntryOpen
                              ? 'bg-teal-100 border-teal-400'
                              : 'bg-teal-50 border-teal-200 hover:bg-teal-100'
                          }`}
                        >
                          🔌
                        </div>
                        <span className="text-sm font-medium text-teal-700 text-center leading-tight">三方接入入口</span>
                      </button>
                    </div>
                  </div>
                </div>
              )}
              {integrationEntryOpen && (
                <div className="mt-5 mx-auto max-w-xl bg-teal-50 border border-teal-200 rounded-xl p-4 text-sm text-teal-900">
                  <div className="font-semibold mb-2">OpenClaw Agent 接入台</div>
                  <p className="mb-3">推荐一键接入：创建委托单 → 发给 Agent 自动执行 → 本页自动更新结果。</p>
                  {!isLoggedIn && (
                    <div className="mb-3 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
                      当前未登录：可查看说明，但三方接入操作需要登录后继续。
                    </div>
                  )}
                  <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                    线上环境请填写「后端服务器可访问」的真实 endpoint / API Key / model（第三方模型提供方参数）。
                    这里的 API Key 与 QuestionOS 的 sandbox token 不同，不要混用。
                  </div>

                  <div className="mb-3 flex gap-2 text-xs">
                    <span className={`px-2 py-1 rounded ${onboardingStep >= 1 ? 'bg-teal-600 text-white' : 'bg-teal-100 text-teal-700'}`}>1. 创建实例</span>
                    <span className={`px-2 py-1 rounded ${onboardingStep >= 2 ? 'bg-teal-600 text-white' : 'bg-teal-100 text-teal-700'}`}>2. 获取配置</span>
                    <span className={`px-2 py-1 rounded ${onboardingStep >= 3 ? 'bg-teal-600 text-white' : 'bg-teal-100 text-teal-700'}`}>3. 联通测试</span>
                  </div>

                  <div className="grid grid-cols-1 gap-2 mb-3">
                    <input
                      value={openClawAgentId}
                      onChange={(e) => setOpenClawAgentId(e.target.value)}
                      placeholder="Agent ID（留空可自动生成）"
                      disabled={!isLoggedIn}
                      className="px-3 py-2 rounded-lg border border-teal-200 bg-white text-teal-900"
                    />
                    <input
                      value={openClawEndpoint}
                      onChange={(e) => setOpenClawEndpoint(e.target.value)}
                      placeholder="OpenClaw endpoint"
                      disabled={!isLoggedIn}
                      className="px-3 py-2 rounded-lg border border-teal-200 bg-white text-teal-900"
                    />
                    <input
                      value={openClawApiKey}
                      onChange={(e) => setOpenClawApiKey(e.target.value)}
                      placeholder="OpenClaw API Key（例如 7182...）"
                      disabled={!isLoggedIn}
                      className="px-3 py-2 rounded-lg border border-teal-200 bg-white text-teal-900"
                    />
                    <input
                      value={openClawModel}
                      onChange={(e) => setOpenClawModel(e.target.value)}
                      placeholder="OpenClaw model"
                      disabled={!isLoggedIn}
                      className="px-3 py-2 rounded-lg border border-teal-200 bg-white text-teal-900"
                    />
                  </div>

                  <div className="flex flex-wrap gap-2 mb-3">
                    <button
                      onClick={generateOneClickOnboarding}
                      disabled={!isLoggedIn || isGeneratingOnboardingPacket}
                      className="px-3 py-1.5 rounded-lg bg-teal-700 text-white hover:bg-teal-800 disabled:opacity-50"
                    >
                      {isGeneratingOnboardingPacket ? '生成中...' : '创建接入委托（复制给 Agent）'}
                    </button>
                    <button
                      onClick={createOpenClawIntegration}
                      disabled={!isLoggedIn || isCreatingIntegration}
                      className="px-3 py-1.5 rounded-lg bg-teal-600 text-white hover:bg-teal-700 disabled:opacity-50"
                    >
                      {isCreatingIntegration ? '创建中...' : '创建 OpenClaw 接入'}
                    </button>
                    <button
                      onClick={() => navigator.clipboard.writeText(openClawConfigText)}
                      className="px-3 py-1.5 rounded-lg border border-teal-300 text-teal-800 hover:bg-teal-100"
                    >
                      复制接入配置
                    </button>
                    <button
                      onClick={loadInstances}
                      disabled={!isLoggedIn || loadingInstances}
                      className="px-3 py-1.5 rounded-lg border border-teal-300 text-teal-800 hover:bg-teal-100 disabled:opacity-50"
                    >
                      {loadingInstances ? '刷新中...' : '刷新实例面板'}
                    </button>
                    <button
                      onClick={runOpenClawConnectivityTest}
                      disabled={!isLoggedIn || isTestingIntegration}
                      className="px-3 py-1.5 rounded-lg border border-teal-300 text-teal-800 hover:bg-teal-100 disabled:opacity-50"
                    >
                      {isTestingIntegration ? '测试中...' : '运行联通测试'}
                    </button>
                  </div>

                  <pre className="text-xs p-3 rounded bg-white border border-teal-200 text-teal-900 overflow-x-auto whitespace-pre-wrap mb-2">{openClawConfigText}</pre>
                  {onboardingJobId && onboardingJobToken && onboardingInstructionUrl && (
                    <pre className="text-xs p-3 rounded bg-white border border-teal-200 text-teal-900 overflow-x-auto whitespace-pre-wrap mb-2">
{buildOneClickMessage(onboardingJobId, onboardingJobToken, onboardingInstructionUrl)}
                    </pre>
                  )}
                  {onboardingJobStatus && (
                    <div className="mt-2 text-xs rounded-lg p-2 border bg-teal-50 text-teal-800 border-teal-200">
                      <div>委托状态：{onboardingJobStatus.status}</div>
                      <div className="mt-1">说明：{onboardingJobStatus.message}</div>
                      {onboardingJobStatus.agentId && (
                        <div className="mt-1">Agent：{onboardingJobStatus.agentId} ({onboardingJobStatus.provider || '-'})</div>
                      )}
                    </div>
                  )}

                  {integrationHint && <p className="mt-1 text-xs text-teal-700">{integrationHint}</p>}
                  {integrationResult && (
                    <div className={`mt-2 text-xs rounded-lg p-2 border ${integrationResult.ok ? 'bg-emerald-50 text-emerald-700 border-emerald-200' : 'bg-rose-50 text-rose-700 border-rose-200'}`}>
                      <div>{integrationResult.message}</div>
                      {integrationResult.firstChunkMs !== undefined && (
                        <div className="mt-1">首包耗时: {integrationResult.firstChunkMs}ms / done: {String(!!integrationResult.hasDone)}</div>
                      )}
                    </div>
                  )}

                  <div className="mt-3 bg-white border border-teal-200 rounded-lg p-3">
                    <div className="flex items-center justify-between mb-2">
                      <div className="text-xs font-semibold text-teal-800">OpenClaw 实例面板</div>
                      <div className="text-xs text-teal-500">共 {instances.length} 个</div>
                    </div>
                    {instances.length === 0 ? (
                      <div className="text-xs text-teal-500">暂无实例，先点击“创建 OpenClaw 接入”。</div>
                    ) : (
                      <div className="space-y-2 max-h-40 overflow-y-auto">
                        {instances.map((item) => (
                          <div key={item.agentId} className="text-xs border border-teal-100 rounded p-2">
                            <div className="font-medium text-teal-900">{item.agentId}</div>
                            <div className="text-teal-700">provider: {item.provider} | scope: {item.scope}</div>
                            <div className="text-teal-600 truncate">endpoint: {item.endpoint}</div>
                            <div className="text-teal-500">model: {item.model || '-'}</div>
                            <div className="text-teal-500">registered: {new Date(item.registeredAt).toLocaleString('zh-CN')}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>

            <div className="rounded-2xl border border-teal-100 bg-gradient-to-br from-teal-50/60 via-white to-white p-6 sm:p-8 mb-6 shadow-sm shadow-teal-500/5">
              <h2 className="text-2xl sm:text-3xl font-bold text-gray-900 mb-2">想推演什么情境？</h2>
              <p className="text-gray-600 text-sm sm:text-base mb-6 leading-relaxed">
                内置多角色将围绕你的议题辩论与整合。可先选推荐场景填入议题，再开始推演。
              </p>
              <p className="text-xs font-semibold uppercase tracking-wider text-teal-800 mb-3">推荐场景</p>
              <div className="grid sm:grid-cols-2 gap-3">
                {CONSULT_RECOMMENDED_SCENARIOS.map((q, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => {
                      if (!isLoggedIn) {
                        setIntegrationHint('请先登录后开始沙盘推演。');
                        router.push('/login');
                        return;
                      }
                      setInputMessage(q);
                      setStarterComposerVisible(true);
                    }}
                    className="text-left px-4 py-3.5 rounded-xl bg-white text-gray-800 text-sm font-medium border border-gray-200 hover:border-teal-400 hover:bg-teal-50/80 hover:shadow-sm transition-all leading-snug"
                  >
                    {q}
                  </button>
                ))}
              </div>
              <button
                type="button"
                onClick={() => {
                  if (!isLoggedIn) {
                    router.push('/login');
                    return;
                  }
                  setStarterComposerVisible(true);
                }}
                className="mt-6 text-sm font-semibold text-teal-700 hover:text-teal-800 underline-offset-2 hover:underline"
              >
                或自己写推演议题 ↓
              </button>
            </div>

            {starterComposerVisible && (
              <div className="bg-white rounded-2xl border border-gray-200 p-4 shadow-sm">
                <label className="block text-xs font-medium text-gray-500 mb-2">推演议题</label>
                <textarea
                  ref={inputRef}
                  value={inputMessage}
                  onChange={(e) => setInputMessage(e.target.value)}
                  onKeyDown={(e) => handleEnterToSubmit(e, () => void handleStartSession())}
                  placeholder="描述要推演的情境、方案或争议点…"
                  className="w-full h-28 p-3 bg-gray-50 border border-gray-200 rounded-xl resize-none focus:outline-none focus:border-teal-400 focus:ring-2 focus:ring-teal-100 focus:bg-white transition-colors overflow-hidden text-[15px]"
                />
                <div className="flex justify-end mt-3">
                  <button
                    type="button"
                    onClick={handleStartSession}
                    disabled={!inputMessage.trim() || isAgentResponding}
                    className="inline-flex items-center gap-2 px-6 py-2.5 rounded-xl font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-md shadow-teal-500/20 hover:shadow-lg disabled:opacity-40 disabled:cursor-not-allowed disabled:shadow-none transition-all"
                  >
                    ⚔️ 开始推演
                  </button>
                </div>
              </div>
            )}

            {!starterComposerVisible && (
              <p className="text-center text-sm text-gray-400 py-3">点选上方推荐场景，或点击「自己写推演议题」以显示输入框</p>
            )}
          </div>
        )}

        {/* 推演进行中 */}
        {sessionStarted && (
          <div className="flex-1 flex flex-col">
            {debugPanelOpen && (
              <div className="mb-3 rounded-xl border border-slate-300 bg-slate-50 p-3">
                <div className="flex items-center justify-between mb-2">
                  <div className="text-xs font-semibold text-slate-700">SSE 调试面板（前端收到的原始事件）</div>
                  <button
                    onClick={() => setDebugLogs([])}
                    className="text-xs text-slate-500 hover:text-slate-700"
                  >
                    清空
                  </button>
                </div>
                <div className="max-h-44 overflow-y-auto text-xs font-mono text-slate-700 whitespace-pre-wrap">
                  {debugLogs.length === 0 ? '暂无事件...' : debugLogs.join('\n')}
                </div>
              </div>
            )}
            {/* 消息列表 */}
            <div className="flex-1 overflow-y-auto space-y-4 pb-4">
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div className={`max-w-[80%] ${msg.role === 'user' ? 'order-2' : 'order-1'}`}>
                    {msg.role === 'user' ? (
                      <div className="rounded-2xl px-4 py-3 bg-slate-100 text-slate-800 rounded-br-md">
                        <p className="whitespace-pre-wrap text-[15px] leading-relaxed">{msg.content}</p>
                      </div>
                    ) : msg.role === 'system' ? (
                      <div className="bg-amber-50 border border-amber-200 text-amber-800 px-4 py-2 rounded-xl text-sm">
                        {msg.content}
                      </div>
                    ) : (
                      <div className="bg-white border border-slate-200 text-slate-800 px-4 py-3 rounded-2xl rounded-bl-md shadow-sm overflow-hidden">
                        <div className="flex items-center gap-2 mb-2">
                          <span className="text-xl">{msg.agent_avatar}</span>
                          <span className="font-semibold text-slate-800">{msg.agent_name}</span>
                          {msg.is_streaming && (
                            <span className="text-xs text-teal-500 animate-pulse">输出中...</span>
                          )}
                        </div>
                        <div className="max-w-none text-[0.9375rem] text-slate-700 leading-relaxed">
                          <div className="markdown-content consult-agent-md">
                            <ReactMarkdown remarkPlugins={[remarkGfm]} components={consultAgentMarkdownComponents}>
                              {agentMarkdownSource(msg)}
                            </ReactMarkdown>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>

            {/* 输入区域 */}
            <div className="border-t border-slate-200 pt-4 mt-4">
              <div className="flex gap-3">
                <div className="flex-1 relative">
                  <textarea
                    ref={inputRef}
                    value={inputMessage}
                    onChange={(e) => setInputMessage(e.target.value)}
                    onKeyDown={(e) => handleEnterToSubmit(e, () => void handleSendMessage())}
                    placeholder={isAgentResponding ? "等待专家回复..." : "继续追问或补充..."}
                    disabled={isAgentResponding}
                    className="w-full h-12 px-4 py-3 bg-gray-50 border border-gray-200 rounded-xl resize-none focus:outline-none focus:border-teal-400 focus:ring-2 focus:ring-teal-100 focus:bg-white transition-colors disabled:opacity-50 overflow-hidden"
                    rows={1}
                  />
                </div>
                <button
                  type="button"
                  onClick={handleSendMessage}
                  disabled={!inputMessage.trim() || isAgentResponding || !sessionId}
                  className="px-5 py-2 rounded-xl font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-md shadow-teal-500/15 hover:shadow-lg disabled:opacity-40 disabled:cursor-not-allowed disabled:shadow-none transition-all"
                >
                  发送
                </button>
              </div>
            </div>
          </div>
        )}
      </main>

      <style jsx global>{`
        .consult-agent-md pre {
          overflow-x: auto;
          white-space: pre-wrap;
          word-wrap: break-word;
          margin: 0.75rem 0;
          padding: 0.75rem 1rem;
          border-radius: 0.5rem;
          background: #f8fafc;
          border: 1px solid #e2e8f0;
          font-size: 0.8125rem;
        }
        .consult-agent-md blockquote {
          margin: 0.75rem 0;
          padding-left: 0.875rem;
          border-left: 3px solid #cbd5e1;
          color: #475569;
        }
        .consult-agent-md a {
          color: #2563eb;
          text-decoration: underline;
          text-underline-offset: 2px;
        }
      `}</style>
    </div>
  );
}