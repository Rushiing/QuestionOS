'use client';

import { useState, useEffect, useLayoutEffect, useRef } from 'react';
import { flushSync } from 'react-dom';
import { useRouter, useSearchParams } from 'next/navigation';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AuthButton, useAuth } from '../../components/AuthButton';
import { sandboxClient, type SandboxSessionMessage } from '../../lib/sandbox-client';
import { normalizeIntegratorExpertBullets } from '../../lib/integrator-markdown';
import { takeBackgroundContext, wrapUserMessageWithBackground } from '../../lib/background-context';
import { handleEnterToSubmit, resizeComposer } from '../../lib/keyboard-ime';
import { formatCalibrationJsonToMarkdown } from '../../lib/calibration-json-to-markdown';
import { SANDBOX_TURN_MAX_WAIT_MS } from '../../lib/runtime-config';

interface Agent {
  id: string;
  name: string;
  avatar: string;
  description: string;
  role: string;
}

/** 沙盘六大审议室（不含兜底类，避免与「六室」展示混淆） */
const SANDBOX_DELIBERATION_ROOMS: Agent[] = [
  { id: 'BUSINESS', name: '集市', avatar: '📈', description: '商业与战略', role: 'sandbox' },
  { id: 'ENGINEERING', name: '锻造坊', avatar: '⚙️', description: '工程与架构', role: 'sandbox' },
  { id: 'LIFE_CROSSROADS', name: '神谕所', avatar: '🔮', description: '人生十字路口与存在抉择', role: 'sandbox' },
  { id: 'RELATIONSHIP', name: '火炉边', avatar: '🔥', description: '关系与家庭', role: 'sandbox' },
  { id: 'PSYCHOLOGY', name: '诊疗室', avatar: '🩺', description: '心理韧性与行为', role: 'sandbox' },
  { id: 'CREATIVE', name: '工作坊', avatar: '✍️', description: '创作与表达', role: 'sandbox' },
];

interface Message {
  id: string;
  role: 'user' | 'agent' | 'system';
  /** 首轮沙盘：步骤① 议题确认与入室 / 步骤② 审议路由卡片 */
  variant?: 'sandbox_classify' | 'sandbox_route';
  agent_id?: string;
  agent_name?: string;
  agent_avatar?: string;
  content: string;
  is_streaming?: boolean;
}

const isSandboxUiMessage = (m: SandboxSessionMessage) =>
  m.agentSpeakerId === 'sandbox-route' || m.agentSpeakerId === 'sandbox-classify';

const isPersistedAgentReply = (m: SandboxSessionMessage) =>
  String(m.role || '').toUpperCase() === 'AGENT' && !!m.content?.trim() && !isSandboxUiMessage(m);

const resolveTurnIdFromMessages = (
  msgList: SandboxSessionMessage[],
  sentMessageId?: string,
  userText?: string
): number | undefined => {
  if (sentMessageId) {
    const byId = msgList.find((m) => m.messageId === sentMessageId);
    if (typeof byId?.turnId === 'number') return byId.turnId;
  }
  const normalized = userText?.trim();
  if (normalized) {
    const byContent = [...msgList]
      .reverse()
      .find((m) => String(m.role || '').toUpperCase() === 'USER' && m.content?.trim() === normalized);
    if (typeof byContent?.turnId === 'number') return byContent.turnId;
  }
  const latestUser = [...msgList].reverse().find((m) => String(m.role || '').toUpperCase() === 'USER');
  return typeof latestUser?.turnId === 'number' ? latestUser.turnId : undefined;
};

const latestAgentForTurn = (msgList: SandboxSessionMessage[], turnId?: number) => {
  if (typeof turnId !== 'number') return undefined;
  return [...msgList].reverse().find((m) => isPersistedAgentReply(m) && m.turnId === turnId);
};

/** 沙盘 Agent 回复：统一标题/分隔线/列表/表格版式（与整合报告等 Markdown 结构配合） */
const consultAgentMarkdownComponents: Components = {
  h2: ({ node, children, ...props }) => (
    <h2 className="mb-2 mt-6 border-b border-[#e2e7e4] pb-2 font-serif text-lg font-semibold tracking-[-0.01em] text-[#161a19] first:mt-0" {...props}>
      {children}
    </h2>
  ),
  h3: ({ node, children, ...props }) => (
    <h3 className="mb-2 mt-5 flex items-center gap-3 text-base font-semibold text-[#161a19]" {...props}>
      <span className="h-4 w-1 shrink-0 rounded-full bg-[#2f6a4a]" aria-hidden />
      <span>{children}</span>
    </h3>
  ),
  hr: ({ node, ...props }) => <hr className="my-5 border-0 border-t border-[#e2e7e4]" {...props} />,
  p: ({ node, children, ...props }) => (
    <p className="my-2 text-[15px] leading-7 text-[#303634]" {...props}>
      {children}
    </p>
  ),
  ul: ({ node, children, ...props }) => (
    <ul className="my-3 list-disc space-y-1.5 pl-5 text-[15px] leading-7 text-[#303634] marker:text-[#95a09a]" {...props}>
      {children}
    </ul>
  ),
  ol: ({ node, children, ...props }) => (
    <ol className="my-3 list-decimal space-y-1.5 pl-5 text-[15px] leading-7 text-[#303634] marker:text-[#95a09a]" {...props}>
      {children}
    </ol>
  ),
  li: ({ node, children, ...props }) => (
    <li className="pl-1 leading-relaxed" {...props}>
      {children}
    </li>
  ),
  strong: ({ node, children, ...props }) => (
    <strong className="font-semibold text-[#161a19]" {...props}>
      {children}
    </strong>
  ),
  table: ({ node, children, ...props }) => (
    <div className="my-5 w-full overflow-x-auto rounded border border-[#d5ded9] bg-white">
      <table className="w-full min-w-[300px] border-collapse text-left text-[0.9375rem] text-[#303634]" {...props}>
        {children}
      </table>
    </div>
  ),
  thead: ({ node, children, ...props }) => (
    <thead className="bg-[#f7f8f8]" {...props}>
      {children}
    </thead>
  ),
  tbody: ({ node, children, ...props }) => (
    <tbody className="divide-y divide-[#e2e7e4] bg-white" {...props}>
      {children}
    </tbody>
  ),
  tr: ({ node, children, ...props }) => (
    <tr className="transition-colors hover:bg-[#f9faf9]" {...props}>
      {children}
    </tr>
  ),
  th: ({ node, children, ...props }) => (
    <th className="border border-[#e2e7e4] px-3 py-2.5 align-middle text-xs font-semibold text-[#626b66]" {...props}>
      {children}
    </th>
  ),
  td: ({ node, children, ...props }) => (
    <td className="border border-[#e2e7e4] px-3 py-2.5 align-middle leading-relaxed" {...props}>
      {children}
    </td>
  ),
};

/** 审议路由卡片：强调首行「### …审议路由」对应的 h3，避免与正文同字号 */
const sandboxRouteMarkdownComponents: Components = {
  ...consultAgentMarkdownComponents,
  h3: ({ node, children, ...props }) => (
    <h3
      className="mb-3 mt-0 border-b border-[#e2e7e4] pb-2.5 font-serif text-lg font-semibold tracking-tight text-[#161a19]"
      {...props}
    >
      {children}
    </h3>
  ),
};

/** 步骤① 分诊卡片：与步骤②审议路由区分 */
const sandboxClassifyMarkdownComponents: Components = {
  ...consultAgentMarkdownComponents,
  h3: ({ node, children, ...props }) => (
    <h3
      className="mb-3 mt-0 border-b border-[#e2e7e4] pb-2.5 font-serif text-lg font-semibold tracking-tight text-[#161a19]"
      {...props}
    >
      {children}
    </h3>
  ),
};

function agentMarkdownSource(msg: Message): string {
  const raw = msg.content || (msg.is_streaming ? '...' : '');
  if (raw === '...') return raw;
  const isIntegrator = msg.role === 'agent' && msg.agent_id === 'integrator';
  if (isIntegrator) {
    return normalizeIntegratorExpertBullets(raw);
  }
  return formatCalibrationJsonToMarkdown(raw);
}

export default function ConsultPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { user, loading: authLoading } = useAuth();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loadingAgents, setLoadingAgents] = useState(true);
  const [sessionStarted, setSessionStarted] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [inputMessage, setInputMessage] = useState('');
  const [pendingAutoStartQuestion, setPendingAutoStartQuestion] = useState<string | null>(null);
  const [isAgentResponding, setIsAgentResponding] = useState(false);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [debugPanelOpen, setDebugPanelOpen] = useState(false);
  const [debugLogs, setDebugLogs] = useState<string[]>([]);
  const lastSeqRef = useRef<number>(0);
  const hasStreamActivityRef = useRef<boolean>(false);
  /** 超时插入的「本轮较慢」气泡 id；若随后收到 turn_done 则移除，避免与完整回复并存 */
  const slowRoundWarningIdRef = useRef<string | null>(null);
  const loadedHistorySessionRef = useRef<string | null>(null);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const isLoggedIn = !!user;
  const urlSessionId = searchParams.get('session');

  useLayoutEffect(() => {
    resizeComposer(inputRef.current);
  }, [inputMessage]);

  const defaultAgentList: Agent[] = [
    ...SANDBOX_DELIBERATION_ROOMS,
  ];

  const agentMeta = (agentId: string) => {
    if (agentId === 'auditor') {
      return { name: '概念席', avatar: '📐' };
    }
    if (agentId === 'risk_officer') {
      return { name: '代价席', avatar: '⚡' };
    }
    if (agentId === 'value_judge') {
      return { name: '校准席', avatar: '🧭' };
    }
    if (agentId === 'integrator') {
      return { name: '综合席', avatar: '🛡️' };
    }
    if (agentId && !['main-calibrate', 'auditor', 'risk_officer', 'value_judge', 'integrator'].includes(agentId)) {
      return { name: agentId, avatar: '🧠' };
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
    const storedQuestion = urlSessionId ? null : sessionStorage.getItem('consultQuestion');
    const initialQuestion = storedQuestion?.trim();
    if (initialQuestion) {
      setInputMessage(initialQuestion);
      setPendingAutoStartQuestion(initialQuestion);
      sessionStorage.removeItem('consultQuestion');
    }
  }, [urlSessionId]);

  useEffect(() => {
    if (!urlSessionId || authLoading || !user) return;
    if (loadedHistorySessionRef.current === urlSessionId) return;
    loadedHistorySessionRef.current = urlSessionId;
    let cancelled = false;
    setSessionStarted(true);
    setSessionId(urlSessionId);
    setIsAgentResponding(true);
    sandboxClient.listMessages(urlSessionId)
      .then((history) => {
        if (cancelled) return;
        setMessages(history
          .filter((message) => message.content?.trim())
          .map((message) => {
            const role = String(message.role).toUpperCase();
            const agentId = message.agentSpeakerId || undefined;
            const meta = agentMeta(agentId || '');
            return {
              id: message.messageId,
              role: role === 'USER' ? 'user' as const : role === 'SYSTEM' ? 'system' as const : 'agent' as const,
              variant: agentId === 'sandbox-classify'
                ? 'sandbox_classify' as const
                : agentId === 'sandbox-route'
                  ? 'sandbox_route' as const
                  : undefined,
              agent_id: agentId,
              agent_name: meta.name,
              agent_avatar: meta.avatar,
              content: message.content,
            };
          }));
      })
      .catch((error) => {
        if (cancelled) return;
        console.error('Failed to load sandbox session history:', error);
        setMessages([{
          id: `${Date.now()}-history-error`,
          role: 'system',
          content: '历史会话加载失败，请稍后重试。',
        }]);
      })
      .finally(() => {
        if (!cancelled) setIsAgentResponding(false);
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, urlSessionId, user]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const createSession = async (question: string): Promise<string> => {
    return sandboxClient.createSession('SANDBOX', question);
  };

  const streamTurnEvents = async (sid: string, currentTurnId?: number) => {
    let isDone = false;
    let activeAgentMsgId: string | null = null;
    // 本轮渲染审计：turn_done 到了但正文一个字都没渲染上，说明解析/渲染层出了意外，需要回填保险
    let renderedChunkChars = 0;
    let lastBubbleId: string | null = null;
    const clearActiveStreaming = () => {
      if (!activeAgentMsgId) return;
      const targetId = activeAgentMsgId;
      setMessages((prev) =>
        prev.map((m) => (m.id === targetId ? { ...m, is_streaming: false } : m))
      );
      activeAgentMsgId = null;
    };
    const appendDebugLog = (line: string) => {
      // 同步落 Console（debug 级别，需在 DevTools 打开 Verbose 才显示）：空气泡类问题的现场取证通道
      console.debug('[qos-sse]', line);
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
      lastBubbleId = newId;
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

        if (eventType === 'sandbox_classify') {
          hasStreamActivityRef.current = true;
          const p = parsed?.payload;
          const md =
            typeof p?.content === 'string' && p.content.trim()
              ? p.content
              : String(content || '');
          flushSync(() => {
            setMessages((prev) => [
              ...prev,
              {
                id: `${Date.now()}-classify`,
                role: 'system',
                variant: 'sandbox_classify',
                content: md,
              },
            ]);
          });
        } else if (eventType === 'sandbox_route') {
          hasStreamActivityRef.current = true;
          flushSync(() => {
            setMessages((prev) => [
              ...prev,
              {
                id: `${Date.now()}-route`,
                role: 'system',
                variant: 'sandbox_route',
                content: String(content || ''),
              },
            ]);
          });
        } else if (eventType === 'agent_start') {
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
          renderedChunkChars += String(content).length;
          const targetId = activeAgentMsgId;
          setMessages(prev => {
            if (targetId && !prev.some(m => m.id === targetId)) {
              // 渲染自愈：气泡丢失（flushSync 失败等）时就地重建，并留下现场证据
              console.warn('[consult] agent_chunk arrived but bubble missing; recreating', targetId);
              const base = agentMeta('main-calibrate');
              return [...prev, {
                id: targetId,
                role: 'agent' as const,
                agent_id: 'main-calibrate',
                agent_name: base.name,
                agent_avatar: base.avatar,
                content: String(content),
                is_streaming: true,
              }];
            }
            return prev.map(m =>
              m.id === targetId ? { ...m, content: (m.content ?? '') + content } : m
            );
          });
        } else if (eventType === 'agent_error') {
          hasStreamActivityRef.current = true;
          if (!activeAgentMsgId) {
            ensureAgentMessage('main-calibrate');
          }
          setMessages(prev => prev.map(m =>
            m.id === activeAgentMsgId
              ? { ...m, content: (m.content ?? '') + ((m.content ?? '') ? '\n\n' : '') + content }
              : m
          ));
        } else if (eventType === 'agent_done') {
          clearActiveStreaming();
        } else if (eventType === 'handoff') {
          setMessages(prev => [...prev, {
            id: `${Date.now()}-handoff`,
            role: 'system',
            content: `Agent 切换：${content}`,
          }]);
        } else if (eventType === 'done') {
          // 后端在 done 之后还会发 turn_done；若此处就停止，会漏掉 turn_done，下一轮 Last-Event-ID 重放会先收到旧的 turn_done 并误结束。
          clearActiveStreaming();
          return false;
        } else if (eventType === 'turn_done') {
          clearActiveStreaming();
          const warnId = slowRoundWarningIdRef.current;
          if (warnId) {
            slowRoundWarningIdRef.current = null;
            setMessages((prev) => prev.filter((m) => m.id !== warnId));
          }
          isDone = true;
          return true;
        }
      } catch (e) {
        // 带上事件类型与原文片段：空气泡排障的关键证据
        console.error('Parse SSE event failed:', eventType, dataRaw.slice(0, 200), e);
      }
      return false;
    }, (line) => appendDebugLog(`[${new Date().toLocaleTimeString('zh-CN')}] ${line}`));

    clearActiveStreaming();
    console.debug('[qos-sse] turn stream ended', { isDone, renderedChunkChars, lastBubbleId });
    // 渲染保险：turn_done 已收到但本轮没有任何正文渲染成功（解析/渲染层意外）。
    // 后端此刻必已落库，拉最新回复回填空气泡——chat 页同款保险，杜绝"空白发言"（2026-06-12 沙盘实测）。
    if (isDone && renderedChunkChars === 0) {
      console.warn('[consult] turn completed but no content rendered; self-healing from listMessages');
      try {
        const msgList = await sandboxClient.listMessages(sid);
        const latestAgent = latestAgentForTurn(msgList || [], currentTurnId);
        if (latestAgent) {
          const fillId = lastBubbleId;
          if (fillId) {
            setMessages(prev => prev.map(m =>
              m.id === fillId && !(m.content ?? '').trim()
                ? { ...m, content: latestAgent.content, is_streaming: false }
                : m
            ));
          } else {
            const aid = (latestAgent.agentSpeakerId || 'auditor').trim() || 'auditor';
            const { name, avatar } = agentMeta(aid);
            setMessages(prev => [...prev, {
              id: `${Date.now()}-selfheal`,
              role: 'agent',
              agent_id: aid,
              agent_name: name,
              agent_avatar: avatar,
              content: latestAgent.content,
            }]);
          }
        }
      } catch (e) {
        console.warn('[consult] self-heal fetch failed:', e);
      }
    }
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
    let sentMessageId: string | undefined;
    let currentTurnId: number | undefined;
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
          const scopedTurnId = currentTurnId ?? resolveTurnIdFromMessages(msgList, sentMessageId, userText);
          const latestAgent = latestAgentForTurn(msgList, scopedTurnId);
          if (latestAgent) {
            const aid = (latestAgent.agentSpeakerId || 'auditor').trim() || 'auditor';
            const { name, avatar } = agentMeta(aid);
            setMessages(prev => [...prev, {
              id: `${Date.now()}-fallback`,
              role: 'agent',
              agent_id: aid,
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
      const sendResult = await sandboxClient.sendMessage(sid, userText, idemKey);
      sentMessageId = sendResult.messageId;
      try {
        const msgList = await sandboxClient.listMessages(sid);
        currentTurnId = resolveTurnIdFromMessages(msgList, sentMessageId, userText);
      } catch (e) {
        console.warn('[consult] resolve current turnId failed:', e);
      }
      if (timedOut) return;
      await streamTurnEvents(sid, currentTurnId);
      if (timedOut) return;
    } catch (error) {
      console.error('runTurn failed:', error);
      // SSE 中途断开时后端往往已生成完回复：先拉最新消息兜底恢复，真失败才报错（与 chat 页一致）
      let recovered = false;
      try {
        const msgList = await sandboxClient.listMessages(sid);
        const scopedTurnId = currentTurnId ?? resolveTurnIdFromMessages(msgList || [], sentMessageId, userText);
        const latestAgent = latestAgentForTurn(msgList || [], scopedTurnId);
        if (latestAgent) {
          recovered = true;
          setMessages(prev => {
            if (prev.some(p => p.role === 'agent' && p.content === latestAgent.content)) {
              return prev; // 该回复已在界面上，无需重复恢复
            }
            return [...prev, {
              id: `${Date.now()}-recovered`,
              role: 'agent' as const,
              agent_id: latestAgent.agentSpeakerId ?? undefined,
              content: latestAgent.content,
            }];
          });
        }
      } catch (recoverErr) {
        console.warn('recover latest agent message failed:', recoverErr);
      }
      if (!recovered) {
        setMessages(prev => [...prev, {
          id: `${Date.now()}-err`,
          role: 'system',
          content: '网络连接中断，本轮推演可能未完成；请重试，或刷新页面查看已生成的内容。',
        }]);
      }
    } finally {
      clearTimeout(timeout);
      if (!timedOut) {
        setIsAgentResponding(false);
      }
    }
  };

  const startSession = async (rawQuestion: string) => {
    const question = rawQuestion.trim();
    if (!question || isAgentResponding) return;
    if (!isLoggedIn) {
      if (typeof window !== 'undefined') {
        sessionStorage.setItem('consultQuestion', question);
      }
      router.push('/login');
      return;
    }
    lastSeqRef.current = 0;
    setDebugLogs([]);
    setSessionStarted(true);
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
      loadedHistorySessionRef.current = sid;
      router.replace(`/consult?session=${encodeURIComponent(sid)}`, { scroll: false });
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

  useEffect(() => {
    if (!pendingAutoStartQuestion || authLoading || sessionStarted || isAgentResponding) return;
    const question = pendingAutoStartQuestion;
    setPendingAutoStartQuestion(null);
    void startSession(question);
  }, [pendingAutoStartQuestion, authLoading, sessionStarted, isAgentResponding]);

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
    setPendingAutoStartQuestion(null);
    setIsAgentResponding(false);
    setSessionId(null);
    loadedHistorySessionRef.current = null;
    lastSeqRef.current = 0;
    router.replace('/consult', { scroll: false });
  };

  return (
    <div className="flex min-h-screen flex-col bg-[#f7f8f8] text-[#161a19]">
      {/* Header */}
      <header className="sticky top-0 z-10 border-b border-[#e2e7e4] bg-[#f7f8f8]/90 px-5 py-3 backdrop-blur-md">
        <div className="mx-auto flex max-w-[780px] flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <button
              type="button"
              onClick={() => router.push('/')}
              className="grid h-[34px] w-[34px] shrink-0 place-items-center rounded border border-[#e2e7e4] bg-white transition-colors hover:border-[#161a19] hover:bg-[#f3f5f4]"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M15 18l-6-6 6-6" />
              </svg>
            </button>
            <button
              type="button"
              onClick={() => router.push('/')}
              className="grid h-[30px] w-[30px] shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white transition-opacity hover:opacity-85"
              aria-label="返回首页"
              title="返回首页"
            >
              Q
            </button>
            <div className="min-w-0">
              <h1 className="flex items-center gap-2 font-serif text-base font-semibold leading-tight tracking-[-0.01em]">
                沙盘推演
                <span className="rounded border border-[#2f6a4a66] px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] text-[#2f6a4a]">
                  Sandbox
                </span>
              </h1>
              <p className="text-[11.5px] text-[#626b66]">
                多 Agent · 分诊 → 审议 → 整合{sessionStarted && sessionId ? ' · 会话已建立' : ''}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {sessionStarted && (
              <button
                onClick={handleReset}
                className="rounded border border-[#e2e7e4] bg-white px-3 py-1.5 text-sm text-[#626b66] transition hover:border-[#161a19] hover:text-[#161a19]"
              >
                重新开始
              </button>
            )}
            <AuthButton />
          </div>
        </div>
      </header>

      <main className="mx-auto flex w-full max-w-[780px] flex-1 flex-col px-5 py-5">
        {/* 开始前：只保留自动开局/空状态，不再重复首页的沙盘选择 */}
        {!sessionStarted && (
          <div className="flex flex-1 items-center justify-center py-16">
            <div className="w-full max-w-[560px] rounded border border-[#e2e7e4] bg-white p-8 text-center shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.06)]">
              {pendingAutoStartQuestion || authLoading ? (
                <>
                  <div className="mx-auto mb-5 h-7 w-7 rounded-full border-2 border-[#d5ded9] border-t-[#2f6a4a] animate-spin" />
                  <p className="font-serif text-2xl font-medium text-[#161a19]">正在进入沙盘推演</p>
                  <p className="mt-3 text-sm leading-6 text-[#626b66]">正在带入首页的议题，并启动多角色审议。</p>
                </>
              ) : (
                <>
                  <p className="font-serif text-2xl font-medium text-[#161a19]">还没有推演议题</p>
                  <p className="mt-3 text-sm leading-6 text-[#626b66]">先回首页写下议题，再选择沙盘模式开始。</p>
                  <button
                    type="button"
                    onClick={() => router.push('/')}
                    className="mt-6 rounded bg-[#161a19] px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-[#213026]"
                  >
                    回首页选择议题
                  </button>
                </>
              )}
            </div>
          </div>
        )}

        {/* 推演进行中 */}
        {sessionStarted && (
          <div className="flex flex-1 flex-col">
            {debugPanelOpen && (
              <div className="mb-3 rounded border border-[#c3cbc6] bg-[#f9faf9] p-3">
                <div className="flex items-center justify-between mb-2">
                  <div className="text-xs font-semibold text-[#303634]">SSE 调试面板（前端收到的原始事件）</div>
                  <button
                    onClick={() => setDebugLogs([])}
                    className="text-xs text-[#626b66] hover:text-[#161a19]"
                  >
                    清空
                  </button>
                </div>
                <div className="max-h-44 overflow-y-auto whitespace-pre-wrap font-mono text-xs text-[#303634]">
                  {debugLogs.length === 0 ? '暂无事件...' : debugLogs.join('\n')}
                </div>
              </div>
            )}
            {/* 消息列表 */}
            <div className="flex-1 space-y-5 overflow-y-auto pb-5">
              {messages.map((msg) => (
                <div
                  key={msg.id}
                  className={`flex ${msg.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  {msg.role === 'user' ? (
                    <div className="max-w-[min(680px,92%)] rounded border border-[#d5ded9] bg-white px-5 py-3.5 text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04)]">
                      <div className="mb-2 flex items-center justify-between gap-3">
                        <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#95a09a]">Issue</span>
                        <span className="rounded border border-[#e2e7e4] px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.08em] text-[#2f6a4a]">Sandbox</span>
                      </div>
                      <p className="whitespace-pre-wrap text-[15px] leading-relaxed">{msg.content}</p>
                    </div>
                  ) : msg.variant === 'sandbox_classify' ? (
                    <div className="w-full max-w-[720px] overflow-hidden rounded border border-[#d5ded9] bg-white text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.04)]">
                      <div className="flex items-center justify-between gap-3 border-b border-[#e2e7e4] bg-[#f9faf9] px-4 py-3">
                        <div>
                          <p className="font-serif text-lg font-semibold leading-tight text-[#161a19]">议题分诊</p>
                          <p className="mt-1 text-xs text-[#626b66]">判断问题是否已经足够进入多角色审议。</p>
                        </div>
                        <span className="rounded border border-[#2f6a4a66] px-2 py-1 font-mono text-[10px] uppercase tracking-[0.08em] text-[#2f6a4a]">Step 01</span>
                      </div>
                      <div className="px-4 py-3">
                        <div className="markdown-content consult-agent-md max-w-none text-[0.9375rem] leading-relaxed">
                          <ReactMarkdown remarkPlugins={[remarkGfm]} components={sandboxClassifyMarkdownComponents}>
                            {msg.content}
                          </ReactMarkdown>
                        </div>
                      </div>
                    </div>
                  ) : msg.variant === 'sandbox_route' ? (
                    <div className="w-full max-w-[720px] overflow-hidden rounded border border-[#cbd8d0] bg-[#f9faf9] text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.04)]">
                      <div className="flex items-center justify-between gap-3 border-b border-[#d6e4dc] bg-[#edf5ef] px-4 py-3">
                        <div>
                          <p className="font-serif text-lg font-semibold leading-tight text-[#161a19]">审议路由</p>
                          <p className="mt-1 text-xs text-[#626b66]">确定审议室、角度和接下来要承压的问题。</p>
                        </div>
                        <span className="rounded bg-[#161a19] px-2 py-1 font-mono text-[10px] uppercase tracking-[0.08em] text-white">Step 02</span>
                      </div>
                      <div className="px-4 py-3">
                        <div className="markdown-content consult-agent-md max-w-none text-[0.9375rem] leading-relaxed">
                          <ReactMarkdown remarkPlugins={[remarkGfm]} components={sandboxRouteMarkdownComponents}>
                            {msg.content}
                          </ReactMarkdown>
                        </div>
                      </div>
                    </div>
                  ) : msg.role === 'system' ? (
                    <div className="mx-auto max-w-[560px] rounded border border-dashed border-[#c3cbc6] bg-[#f9faf9] px-4 py-2 text-center text-sm text-[#626b66]">
                      {msg.content}
                    </div>
                  ) : (
                    <div className="flex w-full max-w-[720px] items-start gap-3">
                      <span className="mt-1 grid h-9 w-9 shrink-0 place-items-center rounded border border-[#d5ded9] bg-white text-base shadow-[0_1px_0_rgba(22,26,25,0.04)]">
                        {msg.agent_avatar || agentMeta(msg.agent_id || '').avatar}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="mb-2 flex flex-wrap items-center gap-2">
                          <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#2f6a4a]">
                            {msg.agent_name || agentMeta(msg.agent_id || '').name}
                          </span>
                          {msg.is_streaming && (
                            <span className="animate-pulse text-xs text-[#2f6a4a]">输出中...</span>
                          )}
                          <span className="h-px min-w-5 flex-1 bg-[#e2e7e4]" aria-hidden />
                        </div>
                        <div className="border-l-[3px] border-l-[#2f6a4a] bg-white px-5 py-4 text-[#303634] shadow-[inset_0_0_0_1px_#e2e7e4,0_1px_0_rgba(22,26,25,0.04)]">
                          <div className="max-w-none text-[0.9375rem] leading-relaxed text-[#303634]">
                            <div className="markdown-content consult-agent-md">
                              <ReactMarkdown remarkPlugins={[remarkGfm]} components={consultAgentMarkdownComponents}>
                                {agentMarkdownSource(msg)}
                              </ReactMarkdown>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              ))}
              <div ref={messagesEndRef} />
            </div>

            {/* 输入区域 */}
            <div className="mt-4 border-t border-[#e2e7e4] pt-4">
              <div className="flex items-end gap-2 rounded border border-[#d5ded9] bg-white p-2 shadow-[0_-1px_0_rgba(22,26,25,0.02),0_8px_22px_rgba(22,26,25,0.05)]">
                <div className="relative flex-1">
                  <textarea
                    ref={inputRef}
                    value={inputMessage}
                    onChange={(e) => setInputMessage(e.target.value)}
                    onKeyDown={(e) => handleEnterToSubmit(e, () => void handleSendMessage())}
                    placeholder={isAgentResponding ? "等待专家回复..." : "继续追问或补充..."}
                    disabled={isAgentResponding}
                    className="min-h-12 max-h-[120px] w-full resize-none overflow-hidden rounded border-0 bg-transparent px-3 py-3 text-[15px] leading-6 text-[#161a19] placeholder:text-[#95a09a] transition-colors focus:outline-none disabled:opacity-50"
                    rows={1}
                  />
                </div>
                <button
                  type="button"
                  onClick={handleSendMessage}
                  disabled={!inputMessage.trim() || isAgentResponding || !sessionId}
                  className="grid h-11 w-16 shrink-0 place-items-center rounded bg-[#161a19] text-sm font-semibold text-white transition hover:bg-[#213026] disabled:cursor-not-allowed disabled:bg-[#d7dcd9] disabled:text-[#626b66]"
                  aria-label="发送"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.9} d="M5 12h14m-5-5 5 5-5 5" />
                  </svg>
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
          border-radius: 4px;
          background: #f7f8f8;
          border: 1px solid #e2e7e4;
          font-size: 0.8125rem;
        }
        .consult-agent-md blockquote {
          margin: 0.75rem 0;
          padding: 0.75rem 1rem;
          border: 1px solid #cbd8d0;
          border-left: 3px solid #2f6a4a;
          background: #f9faf9;
          color: #303634;
        }
        .consult-agent-md a {
          color: #2f6a4a;
          text-decoration: underline;
          text-underline-offset: 2px;
        }
      `}</style>
    </div>
  );
}
