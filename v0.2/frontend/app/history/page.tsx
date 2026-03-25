'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { sandboxClient } from '../../lib/sandbox-client';

interface Session {
  id: string;
  title: string;
  created_at: string;
  status?: string;
  messages?: Array<{
    role: string;
    content: string;
  }>;
}

export default function HistoryPage() {
  const router = useRouter();
  const [sessions, setSessions] = useState<Session[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  useEffect(() => {
    fetchSessions();
  }, []);

  const fetchSessions = async () => {
    try {
      const sessions = await sandboxClient.listSessions();
      const sorted = sessions.map((s: any) => ({
        id: s.sessionId,
        title: `${s.mode || 'UNKNOWN'} / ${s.status || 'UNKNOWN'}`,
        created_at: s.createdAt,
        status: s.status,
      })).sort((a: Session, b: Session) =>
        new Date(b.created_at).getTime() - new Date(a.created_at).getTime()
      );
      setSessions(sorted);
    } catch (error) {
      console.error('Failed to fetch sessions:', error);
    } finally {
      setLoading(false);
    }
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

  const handleContinue = (sessionId: string) => {
    router.push(`/chat?session=${sessionId}`);
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
          <button
            onClick={() => router.push('/')}
            className="px-4 py-2 bg-slate-800 text-white text-sm rounded-lg hover:bg-slate-700 transition-colors"
          >
            新对话
          </button>
        </div>
      </header>

      {/* Content */}
      <main className="max-w-3xl mx-auto px-6 py-8">
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <div className="w-6 h-6 border-2 border-slate-300 border-t-slate-600 rounded-full animate-spin"></div>
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
              className="mt-4 text-blue-600 hover:text-blue-700 text-sm"
            >
              开始第一次对话 →
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {sessions.map((session) => (
              <div
                key={session.id}
                className="bg-white rounded-xl border border-slate-200 overflow-hidden hover:border-slate-300 transition-colors"
              >
                <div
                  className="px-4 py-3 cursor-pointer"
                  onClick={() => toggleExpand(session.id)}
                >
                  <div className="flex items-start justify-between gap-4">
                    <div className="flex-1 min-w-0">
                      <p className="text-slate-800 font-medium truncate">
                        {session.title || '未命名对话'}
                      </p>
                      <p className="text-slate-400 text-sm mt-1">
                        {formatDate(session.created_at)}
                      </p>
                    </div>
                    <div className="flex items-center gap-2">
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          handleContinue(session.id);
                        }}
                        className="px-3 py-1.5 text-sm text-blue-600 hover:bg-blue-50 rounded-lg transition-colors"
                      >
                        继续
                      </button>
                      <svg
                        className={`w-5 h-5 text-slate-400 transition-transform ${expandedId === session.id ? 'rotate-180' : ''}`}
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
                      </svg>
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
                              {msg.role === 'user' ? '👤 ' : '🤖 '}
                            </span>
                            <span className="line-clamp-2">{msg.content}</span>
                          </div>
                        ))}
                        {session.messages.length > 6 && (
                          <p className="text-slate-400 text-xs">
                            ... 还有 {session.messages.length - 6} 条消息
                          </p>
                        )}
                      </div>
                    ) : (
                      <p className="text-slate-400 text-sm">加载中...</p>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </main>
    </div>
  );
}