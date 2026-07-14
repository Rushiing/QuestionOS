'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { sandboxClient, type SandboxSessionSummary } from '../../lib/sandbox-client';
import { useAuth } from '../../components/AuthButton';
import { markInternalChatNav } from '../../lib/chat-nav';

interface Session {
  id: string;
  mode: string;
  title: string;
  created_at: string;
  status?: string;
  messages?: Array<{
    role: string;
    content: string;
    agentSpeakerId?: string | null;
  }>;
}

export default function HistoryPage() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [deleting, setDeleting] = useState(false);

  useEffect(() => {
    if (authLoading) return;
    if (!user) {
      router.replace('/login');
      return;
    }
    fetchSessions();
  }, [authLoading, user]);

  const fetchSessions = async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const sessions = await sandboxClient.listSessions();
      const sorted = sessions.map((s: SandboxSessionSummary) => ({
        id: s.sessionId,
        mode: s.mode,
        title:
          (s.title && String(s.title).trim()) ||
          `${s.mode || 'UNKNOWN'} · ${s.status || 'UNKNOWN'}`,
        created_at: s.createdAt,
        status: s.status,
      })).sort((a: Session, b: Session) =>
        new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
      );
      setSessions(sorted);
    } catch (error) {
      console.error('Failed to fetch sessions:', error);
      setLoadError(true);
    } finally {
      setLoading(false);
    }
  };

  const hideSessions = async (sessionIds: string[]) => {
    if (!user || sessionIds.length === 0) return;
    const label = sessionIds.length === 1 ? '这条历史记录' : `选中的 ${sessionIds.length} 条历史记录`;
    if (!window.confirm(`确认删除${label}？删除后将不再展示。`)) return;

    setDeleting(true);
    try {
      if (sessionIds.length === 1) await sandboxClient.deleteSession(sessionIds[0]);
      else await sandboxClient.deleteSessions(sessionIds);
      const deletedIds = new Set(sessionIds);
      setSessions(current => current.filter(session => !deletedIds.has(session.id)));
      setExpandedId(current => current && deletedIds.has(current) ? null : current);
      setSelectedIds(new Set());
      if (selectionMode) setSelectionMode(false);
    } catch (error) {
      console.error('Failed to delete sessions:', error);
      window.alert('删除失败，请稍后重试');
    } finally {
      setDeleting(false);
    }
  };

  const toggleSelection = (sessionId: string) => {
    setSelectedIds(current => {
      const next = new Set(current);
      if (next.has(sessionId)) next.delete(sessionId);
      else next.add(sessionId);
      return next;
    });
  };

  const exitSelectionMode = () => {
    setSelectionMode(false);
    setSelectedIds(new Set());
  };

  const formatDate = (dateString: string) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffMs = now.getTime() - date.getTime();
    const diffMins = Math.floor(diffMs / 60000);
    const diffHours = Math.floor(diffMs / 3600000);
    const diffDays = Math.floor(diffMs / 86400000);

    if (diffMins < 1) return '刚刚';
    if (diffMins < 60) return `${diffMins} 分钟前`;
    if (diffHours < 24) return `${diffHours} 小时前`;
    if (diffDays < 7) return `${diffDays} 天前`;
    return date.toLocaleDateString('zh-CN');
  };

  const groupSessionsByDate = (sessions: Session[]): Array<[string, Session[]]> => {
    // 用本地日期 yyyy-mm-dd 作为分组与排序的 key——中文标签（"2026年4月9日"）丢给 new Date()
    // 解析结果是 NaN，排序是未定义行为，跨天历史会乱序
    const toDayKey = (d: Date) =>
      `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    const today = new Date();
    const yesterday = new Date(today);
    yesterday.setDate(yesterday.getDate() - 1);
    const todayKey = toDayKey(today);
    const yesterdayKey = toDayKey(yesterday);

    const groups: { [key: string]: { label: string; items: Session[] } } = {};
    sessions.forEach(session => {
      const date = new Date(session.created_at);
      const key = toDayKey(date);
      if (!groups[key]) {
        const label =
          key === todayKey ? '今天'
            : key === yesterdayKey ? '昨天'
              : date.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' });
        groups[key] = { label, items: [] };
      }
      groups[key].items.push(session);
    });

    return Object.entries(groups)
      .sort(([a], [b]) => (a > b ? -1 : a < b ? 1 : 0))
      .map(([, g]) => [g.label, g.items]);
  };

  const handleViewFull = (session: Session) => {
    if (session.mode.toUpperCase() === 'SANDBOX') {
      router.push(`/consult?session=${encodeURIComponent(session.id)}`);
      return;
    }
    markInternalChatNav();
    router.push(`/chat?session=${encodeURIComponent(session.id)}`);
  };

  const toggleExpand = async (sessionId: string) => {
    if (expandedId === sessionId) {
      setExpandedId(null);
      return;
    }
    
    setExpandedId(sessionId);
    
    // 如果没有 messages 详情，从后端加载
    const session = sessions.find(s => s.id === sessionId);
    if (session && !session.messages) {
      try {
        const messages = await sandboxClient.listMessages(sessionId);
        setSessions(prev => prev.map(s => 
          s.id === sessionId ? {
            ...s,
            messages: (messages || []).map((m: any) => ({
              role: String(m.role || '').toLowerCase(),
              content: m.content,
              agentSpeakerId: m.agentSpeakerId ?? null,
            })),
          } : s
        ));
      } catch (error) {
        console.error('Failed to fetch session details:', error);
      }
    }
  };

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Header */}
      <header className="bg-white border-b border-slate-200 px-6 py-4 sticky top-0 z-10">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-4">
            <button
              onClick={() => router.push('/')}
              className="p-2 hover:bg-slate-100 rounded-lg transition-colors"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
            </button>
            <h1 className="text-lg font-semibold text-slate-800">历史记录</h1>
          </div>
          <div className="flex items-center gap-2">
            {selectionMode ? (
              <>
                <button
                  onClick={() => setSelectedIds(selectedIds.size === sessions.length ? new Set() : new Set(sessions.map(s => s.id)))}
                  className="rounded-lg px-3 py-2 text-sm text-slate-600 transition-colors hover:bg-slate-100"
                >
                  {selectedIds.size === sessions.length ? '取消全选' : '全选'}
                </button>
                <button
                  onClick={() => void hideSessions(Array.from(selectedIds))}
                  disabled={selectedIds.size === 0 || deleting}
                  className="rounded-lg bg-red-600 px-3 py-2 text-sm text-white transition-colors hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {deleting ? '删除中...' : `删除${selectedIds.size > 0 ? ` (${selectedIds.size})` : ''}`}
                </button>
                <button onClick={exitSelectionMode} className="rounded-lg px-3 py-2 text-sm text-slate-600 hover:bg-slate-100">取消</button>
              </>
            ) : (
              <>
                {sessions.length > 0 && (
                  <button onClick={() => setSelectionMode(true)} className="rounded-lg px-3 py-2 text-sm text-slate-600 transition-colors hover:bg-slate-100">
                    多选
                  </button>
                )}
                <button
                  onClick={() => router.push('/')}
                  className="rounded-lg bg-[#2f6a4a] px-4 py-2 text-sm text-white transition-colors hover:bg-[#244f39]"
                >
                  新对话
                </button>
              </>
            )}
          </div>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-3xl mx-auto px-6 py-8">
        {authLoading || loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin"></div>
          </div>
        ) : loadError ? (
          <div className="text-center py-12">
            <p className="text-slate-600 mb-4">历史记录加载失败，可能是网络问题</p>
            <button
              onClick={fetchSessions}
              className="rounded-lg bg-[#2f6a4a] px-4 py-2 text-sm text-white transition-colors hover:bg-[#244f39]"
            >
              重试
            </button>
          </div>
        ) : sessions.length === 0 ? (
          <div className="text-center py-12">
            <div className="text-slate-400 mb-4">
              <svg className="w-16 h-16 mx-auto" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" />
              </svg>
            </div>
            <p className="text-slate-500">暂无对话记录</p>
            <button
              onClick={() => router.push('/')}
              className="mt-4 text-sm text-[#2f6a4a] hover:text-[#244f39]"
            >
              开始第一次对话 →
            </button>
          </div>
        ) : (
          <div className="space-y-6">
            {groupSessionsByDate(sessions).map(([dateGroup, groupSessions]) => (
              <div key={dateGroup}>
                <h2 className="text-sm font-semibold text-slate-700 mb-2 px-2">
                  {dateGroup}
                </h2>
                <div className="space-y-2">
                  {groupSessions.map((session) => (
              <div
                key={session.id}
                className={`bg-white rounded-xl border overflow-hidden transition-colors ${selectedIds.has(session.id) ? 'border-[#2f6a4a] ring-1 ring-[#2f6a4a]' : 'border-slate-200 hover:border-slate-300'}`}
              >
                <div
                  className="px-4 py-3 cursor-pointer"
                  onClick={() => selectionMode ? toggleSelection(session.id) : toggleExpand(session.id)}
                >
                  <div className="flex items-start justify-between gap-4">
                    {selectionMode && (
                      <input
                        type="checkbox"
                        checked={selectedIds.has(session.id)}
                        onChange={() => toggleSelection(session.id)}
                        onClick={(event) => event.stopPropagation()}
                        className="mt-1 h-4 w-4 accent-[#2f6a4a]"
                        aria-label={`选择 ${session.title || '未命名对话'}`}
                      />
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="text-slate-800 font-medium truncate">
                        {session.title || '未命名对话'}
                      </p>
                      <div className="mt-1.5 flex items-center gap-2">
                        <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${session.mode.toUpperCase() === 'SANDBOX' ? 'bg-amber-50 text-amber-700' : 'bg-emerald-50 text-emerald-700'}`}>
                          {session.mode.toUpperCase() === 'SANDBOX' ? '沙盘推演' : '思维校准'}
                        </span>
                        <span className="text-sm text-slate-400">{formatDate(session.created_at)}</span>
                      </div>
                    </div>
                    <div className="flex items-center gap-2">
                      {!selectionMode && (
                        <>
                          <button
                            type="button"
                            onClick={(event) => {
                              event.stopPropagation();
                              void hideSessions([session.id]);
                            }}
                            disabled={deleting}
                            className="flex h-8 w-8 items-center justify-center rounded-lg text-slate-400 transition-colors hover:bg-red-50 hover:text-red-600 disabled:opacity-40"
                            aria-label={`删除 ${session.title || '未命名对话'}`}
                          >
                            <svg className="h-[18px] w-[18px]" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.7} d="m14.74 9-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673A2.25 2.25 0 0 1 15.916 21H8.084a2.25 2.25 0 0 1-2.244-1.327L4.772 5.79m14.456 0A48.108 48.108 0 0 0 15.75 5.4m-10.978.39c.34-.059.68-.114 1.022-.165m0 0A48.11 48.11 0 0 1 8.25 5.4m7.5 0V4.477c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 0 0-3.32 0c-1.18.037-2.09 1.022-2.09 2.201V5.4m7.5 0a48.667 48.667 0 0 0-7.5 0" />
                            </svg>
                          </button>
                          <svg
                            className={`w-5 h-5 text-slate-400 transition-transform ${expandedId === session.id ? 'rotate-180' : ''}`}
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                          </svg>
                        </>
                      )}
                    </div>
                  </div>
                </div>
                
                {expandedId === session.id && (
                  <div className="border-t border-slate-100 px-4 py-3 bg-slate-50">
                    {session.messages && session.messages.length > 0 ? (
                      <div className="space-y-2 text-sm">
                        {session.messages.slice(0, 6).map((msg, i) => (
                          <div
                            key={i}
                            className={`${msg.role === 'user' ? 'text-slate-700' : 'text-slate-500'}`}
                          >
                            <span className="font-medium">
                              {msg.role === 'user'
                                ? '👤 '
                                : msg.agentSpeakerId === 'sandbox-route'
                                  ? '🧭 '
                                  : msg.agentSpeakerId === 'sandbox-classify'
                                    ? '🔎 '
                                    : '🤖 '}
                            </span>
                            <span className="line-clamp-2">{msg.content}</span>
                          </div>
                        ))}
                        {session.messages.length > 6 && (
                          <p className="text-slate-400 text-xs">
                            ... 还有 {session.messages.length - 6} 条消息
                          </p>
                        )}
                        <div className="border-t border-slate-200 pt-3">
                          <button
                            type="button"
                            onClick={() => handleViewFull(session)}
                            className="inline-flex items-center gap-1.5 text-sm font-medium text-[#2f6a4a] transition-colors hover:text-[#244f39]"
                          >
                            查看完整记录
                            <svg
                              className="h-4 w-4"
                              fill="none"
                              stroke="currentColor"
                              viewBox="0 0 24 24"
                              aria-hidden
                            >
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="m9 5 7 7-7 7" />
                            </svg>
                          </button>
                        </div>
                      </div>
                    ) : (
                      <p className="text-slate-400 text-sm">加载中...</p>
                    )}
                  </div>
                )}
                  </div>
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}
