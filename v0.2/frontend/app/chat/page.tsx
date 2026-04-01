'use client';

import { useState, useEffect, useLayoutEffect, useRef, Suspense } from 'react';
import { useRouter, useSearchParams, usePathname } from 'next/navigation';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AuthButton } from '../../components/AuthButton';
import { sandboxClient } from '../../lib/sandbox-client';
interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

const EXAMPLE_QUESTIONS = [
  "我想转行但担心沉没成本，该怎么决策？",
  "团队有两个技术方案，如何评估选择？",
  "最近工作效率很低，总是拖延，怎么办？",
];

/** 浏览器刷新（F5 / 地址栏回车刷新等），非从站内路由进入 */
function isPageReload(): boolean {
  if (typeof window === 'undefined' || typeof performance === 'undefined') return false;
  const nav = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
  if (nav?.type === 'reload') return true;
  const legacy = performance as Performance & { navigation?: { type: number } };
  return legacy.navigation?.type === 1;
}

// 提取 **问题** 格式的内容
function extractQuestions(content: string): string[] {
  const regex = /\*\*([^*]+)\*\*/g;
  const questions: string[] = [];
  let match;
  while ((match = regex.exec(content)) !== null) {
    questions.push(match[1]);
  }
  return questions;
}

// 检测是否是"炼金输出"
function isAlchemyOutput(content: string): boolean {
  return content.includes('💎 提问炼金成功') || 
         (content.includes('原问题') && content.includes('重构后的天才提问'));
}

// 从炼金输出中提取结构化内容
function extractAlchemyContent(content: string): { original: string; essence: string; refined: string } | null {
  // 匹配原问题（无方括号格式）
  const originalMatch = content.match(/原问题[（(]垃圾堆[)）][：:]\s*(.+?)(?=\n|$)/);
  // 匹配本质拆解（用 [\s\S] 替代 . 来匹配任意字符包括换行）
  const essenceMatch = content.match(/本质拆解[（(]显微镜[)）][：:]\s*([\s\S]+?)(?=\n|重构)/);
  // 匹配重构后的天才提问
  const refinedMatch = content.match(/重构后的天才提问[（(]手术刀[)）][：:]\s*([\s\S]+?)(?=\n\n|$)/);
  
  if (originalMatch && essenceMatch && refinedMatch) {
    return {
      original: originalMatch[1].trim(),
      essence: essenceMatch[1].trim(),
      refined: refinedMatch[1].trim()
    };
  }
  return null;
}

// 从本质拆解中提取多个点
function extractEssencePoints(essence: string): string[] {
  // 尝试按数字序号分割（如 1. 2. 3. 或 一、二、三、）
  const numberPattern = /[1-9][.、．]\s*/g;
  const chinesePattern = /[一二三四五六七八九十][、.．]\s*/g;
  
  // 检测使用哪种分割方式
  if (essence.match(numberPattern)) {
    return essence.split(numberPattern).filter(s => s.trim()).map(s => s.trim());
  }
  if (essence.match(chinesePattern)) {
    return essence.split(chinesePattern).filter(s => s.trim()).map(s => s.trim());
  }
  
  // 如果没有序号，按句号或分号分割
  const sentences = essence.split(/[。；;]/).filter(s => s.trim());
  if (sentences.length > 1) {
    return sentences.map(s => s.trim());
  }
  
  // 如果只有一个点，返回整个内容
  return [essence];
}

// 解析矛盾图标记
function parseContradictionImage(content: string): { center: string; main_conflict: string; secondary_conflicts: string[] } | null {
  const match = content.match(/\[生成矛盾图\]([\s\S]*?)\[\/生成矛盾图\]/);
  if (!match) return null;
  
  const block = match[1];
  const centerMatch = block.match(/中心[：:]\s*(.+)/);
  const mainMatch = block.match(/主要矛盾[：:]\s*(.+)/);
  const secondaryMatch = block.match(/次要矛盾[：:]\s*(.+)/);
  
  if (!centerMatch || !mainMatch) return null;
  
  return {
    center: centerMatch[1].trim(),
    main_conflict: mainMatch[1].trim(),
    secondary_conflicts: secondaryMatch ? secondaryMatch[1].split(/[、，,]/).map(s => s.trim()).filter(Boolean) : []
  };
}

// 生成炼金输出的思维导图
function generateAlchemyMindMap(content: { original: string; essence: string; refined: string }): string {
  const essencePoints = extractEssencePoints(content.essence);
  
  let markdown = `# ${content.original}\n\n`;
  
  essencePoints.forEach((point, i) => {
    markdown += `## 本质拆解${i + 1}\n- ${point}\n\n`;
  });
  
  markdown += `## 重构后的天才提问\n- ${content.refined}`;
  
  return markdown;
}

// 生成 Markdown 格式的思维导图
function generateMindMapMarkdown(questions: string[], title: string = '问题全局'): string {
  if (questions.length === 0) return '';
  
  let markdown = `# ${title}\n`;
  questions.forEach((q, i) => {
    markdown += `\n## ${i + 1}. ${q}\n`;
    markdown += `- 待回答\n`;
  });
  return markdown;
}

// 解析思维脑图内容
function parseMindMapContent(content: string): { title: string; markdown: string; fullMatch: string } | null {
  // 匹配 **🧠 思维脑图：** 或 **🧠 终极思维脑图：** 后面的代码块
  const match = content.match(/\*\*🧠 (思维脑图|终极思维脑图)[：:]\*\*\s*```[\s\S]*?```/);
  if (!match) return null;
  
  const fullMatch = match[0];
  const titleMatch = fullMatch.match(/🧠 (思维脑图|终极思维脑图)/);
  const title = titleMatch ? titleMatch[1] : '思维脑图';
  
  // 提取代码块内容
  const codeBlockMatch = fullMatch.match(/```\s*([\s\S]*?)```/);
  if (!codeBlockMatch) return null;
  
  return {
    title,
    markdown: codeBlockMatch[1].trim(),
    fullMatch
  };
}

// 思维导图组件（带浮层放大）
function MindMapView({ markdown, title = '思维脑图' }: { markdown: string; title?: string }) {
  const svgRef = useRef<SVGSVGElement>(null);
  const modalSvgRef = useRef<SVGSVGElement>(null);
  const markmapRef = useRef<any>(null);
  const modalMarkmapRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [showModal, setShowModal] = useState(false);

  // 初始化小图
  useEffect(() => {
    if (!svgRef.current || !markdown) return;
    const timer = setTimeout(() => {
      if (!svgRef.current) return;
      (async () => {
        try {
          // 按需加载重依赖，避免进入 /chat 首屏包
          const [{ Transformer }, { Markmap }] = await Promise.all([
            import('markmap-lib'),
            import('markmap-view'),
          ]);
          const transformer = new Transformer();
        const { root } = transformer.transform(markdown);
        if (!markmapRef.current) {
          markmapRef.current = Markmap.create(svgRef.current, undefined, root);
        } else {
          markmapRef.current.setData(root);
          markmapRef.current.fit();
        }
        } catch (e) {
          console.error('Mindmap render error:', e);
        }
      })();
    }, 100);
    return () => clearTimeout(timer);
  }, [markdown]);

  // 初始化浮层大图
  useEffect(() => {
    if (!showModal || !modalSvgRef.current || !markdown) return;
    const timer = setTimeout(() => {
      if (!modalSvgRef.current) return;
      (async () => {
        try {
          const [{ Transformer }, { Markmap }] = await Promise.all([
            import('markmap-lib'),
            import('markmap-view'),
          ]);
          const transformer = new Transformer();
        const { root } = transformer.transform(markdown);
        if (!modalMarkmapRef.current) {
          modalMarkmapRef.current = Markmap.create(modalSvgRef.current, undefined, root);
        } else {
          modalMarkmapRef.current.setData(root);
          modalMarkmapRef.current.fit();
        }
        } catch (e) {
          console.error('Modal mindmap render error:', e);
        }
      })();
    }, 100);
    return () => clearTimeout(timer);
  }, [showModal, markdown]);

  return (
    <>
      <div ref={containerRef} className="w-full bg-gradient-to-r from-purple-50 to-pink-50 rounded-xl p-4 my-4 overflow-auto relative">
        <div className="flex items-center justify-between mb-2">
          <div className="text-xs text-purple-600 font-medium">🧠 {title}</div>
          <button 
            onClick={() => setShowModal(true)}
            className="text-xs text-purple-500 hover:text-purple-700 flex items-center gap-1 px-2 py-1 rounded hover:bg-purple-100 transition-colors"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 8V4m0 0h4M4 4l5 5m11-1V4m0 0h-4m4 0l-5 5M4 16v4m0 0h4m-4 0l5-5m11 5l-5-5m5 5v-4m0 4h-4" />
            </svg>
            放大
          </button>
        </div>
        <svg 
          ref={svgRef} 
          width="100%"
          height={250}
          style={{ minWidth: '100%', minHeight: 250, display: 'block' }} 
        />
      </div>

      {/* 浮层 */}
      {showModal && (
        <div 
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm"
          onClick={() => setShowModal(false)}
        >
          <div 
            className="bg-white rounded-2xl shadow-2xl w-[90vw] max-w-5xl max-h-[85vh] overflow-hidden"
            onClick={e => e.stopPropagation()}
          >
            <div className="flex items-center justify-between p-4 border-b border-gray-100">
              <div className="text-lg font-semibold text-purple-600">🧠 {title}</div>
              <button 
                onClick={() => setShowModal(false)}
                className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
              >
                <svg className="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>
            <div className="p-6 overflow-auto" style={{ maxHeight: 'calc(85vh - 70px)' }}>
              <svg 
                ref={modalSvgRef} 
                width="100%"
                height={600}
                style={{ minWidth: '100%', minHeight: 600, display: 'block' }} 
              />
            </div>
          </div>
        </div>
      )}
    </>
  );
}

// 矛盾图组件 - 已禁用生图
function ContradictionImageView({ center, mainConflict, secondaryConflicts }: { center: string; mainConflict: string; secondaryConflicts: string[] }) {
  // 生图功能已禁用，不渲染任何内容
  return null;
}

// 炼金输出组件（带继续追问按钮）
function AlchemyMessage({ content, onContinueWithQuestion }: { content: string; onContinueWithQuestion?: (question: string) => void }) {
  // 提取重构后的问题
  const refinedMatch = content.match(/重构后的天才提问[（(]手术刀[)）][：:]\s*```[\s\S]*?```/);
  const hasRefinedQuestion = !!refinedMatch;
  
  const extractRefinedQuestion = (text: string): string | null => {
    const match = text.match(/重构后的天才提问[（(]手术刀[)）][：:]\s*```\s*([\s\S]+?)```/);
    return match ? match[1].trim() : null;
  };
  
  const refinedQuestion = hasRefinedQuestion ? extractRefinedQuestion(content) : null;
  
  return (
    <div className="text-[15px] leading-relaxed">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ className, children, ...props }) {
            const codeContent = String(children);
            const isInline = !codeContent.includes('\n');
            if (isInline) {
              return <code className="bg-blue-100 text-blue-800 px-1.5 py-0.5 rounded text-sm font-medium" {...props}>{children}</code>;
            }
            return (
              <div className="relative">
                <pre className="bg-gradient-to-r from-blue-50 to-indigo-50 text-gray-800 p-4 rounded-xl my-3 overflow-x-auto text-sm leading-relaxed whitespace-pre-wrap border border-blue-200 shadow-sm">
                  <code {...props}>{children}</code>
                </pre>
              </div>
            );
          },
          blockquote({ children }) {
            return <blockquote className="border-l-4 border-blue-400 pl-4 py-2 my-2 bg-blue-50 rounded-r-lg text-gray-700">{children}</blockquote>;
          },
          h2({ children }) {
            return <h2 className="text-xl font-bold text-gray-800 mt-4 mb-3">{children}</h2>;
          },
          h3({ children }) {
            const titleText = String(children);
            const isRefinedTitle = titleText.includes('重构后的天才提问');
            return (
              <div className="flex items-center gap-3 mt-4 mb-2">
                <h3 className="text-lg font-semibold text-blue-700 flex items-center gap-2">{children}</h3>
                {isRefinedTitle && hasRefinedQuestion && refinedQuestion && onContinueWithQuestion && (
                  <button
                    onClick={() => onContinueWithQuestion(refinedQuestion)}
                    className="px-3 py-1 bg-blue-600 hover:bg-blue-700 text-white text-xs font-medium rounded-lg transition-all duration-200 flex items-center gap-1.5 shadow-sm hover:shadow-md whitespace-nowrap"
                  >
                    <svg className="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                    </svg>
                    继续追问
                  </button>
                )}
              </div>
            );
          },
          strong({ children }) {
            return <strong className="font-bold text-gray-900">{children}</strong>;
          },
          ol({ children }) {
            return <ol className="list-decimal list-inside space-y-2 my-3 ml-2">{children}</ol>;
          },
          ul({ children }) {
            return <ul className="list-disc list-inside space-y-2 my-3 ml-2">{children}</ul>;
          },
          hr() {
            return <hr className="my-5 border-gray-200" />;
          },
          p({ children }) {
            return <p className="my-2">{children}</p>;
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

// AI 消息组件（支持 Markdown + 脑图）
function AIMessage({ content, onContinueWithQuestion }: { content: string; onContinueWithQuestion?: (question: string) => void }) {
  // 解析思维脑图
  const mindMapData = parseMindMapContent(content);
  
  // 如果有思维脑图，渲染脑图（保留文字内容）
  if (mindMapData) {
    return (
      <div className="whitespace-pre-wrap text-[15px] leading-relaxed">
        <ReactMarkdown
          remarkPlugins={[remarkGfm]}
          components={{
            code({ className, children, ...props }) {
              const codeContent = String(children);
              const isInline = !codeContent.includes('\n');
              if (isInline) {
                return <code className="bg-blue-100 text-blue-800 px-1.5 py-0.5 rounded text-sm font-medium" {...props}>{children}</code>;
              }
              return <pre className="bg-gray-100 text-gray-800 p-4 rounded-xl my-3 overflow-x-auto text-sm whitespace-pre-wrap border border-gray-200"><code {...props}>{children}</code></pre>;
            },
            blockquote({ children }) {
              return <blockquote className="border-l-4 border-gray-300 pl-4 py-2 my-2 bg-gray-50 rounded-r-lg text-gray-700 italic">{children}</blockquote>;
            },
            h2({ children }) {
              return <h2 className="text-lg font-bold text-gray-800 mt-4 mb-2">{children}</h2>;
            },
            strong({ children }) {
              return <strong className="font-bold text-gray-900">{children}</strong>;
            },
            ol({ children }) {
              return <ol className="list-decimal list-inside space-y-1 my-2">{children}</ol>;
            },
            ul({ children }) {
              return <ul className="list-disc list-inside space-y-1 my-2">{children}</ul>;
            },
            hr() {
              return <hr className="my-4 border-gray-200" />;
            },
          }}
        >
          {content}
        </ReactMarkdown>
        
        {/* 思维脑图 */}
        <MindMapView markdown={mindMapData.markdown} title={mindMapData.title} />
      </div>
    );
  }
  
  // 检测是否是炼金输出（包含💎）
  const isAlchemyOutput = content.includes('💎');
  
  // 如果是炼金输出，用专门组件渲染
  if (isAlchemyOutput) {
    return <AlchemyMessage content={content} onContinueWithQuestion={onContinueWithQuestion} />;
  }
  
  // 普通追问输出
  return (
    <div className="whitespace-pre-wrap text-[15px] leading-relaxed">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ className, children, ...props }) {
            const codeContent = String(children);
            const isInline = !codeContent.includes('\n');
            if (isInline) {
              return <code className="bg-blue-100 text-blue-800 px-1.5 py-0.5 rounded text-sm font-medium" {...props}>{children}</code>;
            }
            return <pre className="bg-gray-100 text-gray-800 p-4 rounded-xl my-3 overflow-x-auto text-sm whitespace-pre-wrap border border-gray-200"><code {...props}>{children}</code></pre>;
          },
          blockquote({ children }) {
            return <blockquote className="border-l-4 border-gray-300 pl-4 py-2 my-2 bg-gray-50 rounded-r-lg text-gray-700 italic">{children}</blockquote>;
          },
          h2({ children }) {
            return <h2 className="text-lg font-bold text-gray-800 mt-4 mb-2">{children}</h2>;
          },
          strong({ children }) {
            return <strong className="font-bold text-gray-900">{children}</strong>;
          },
          ol({ children }) {
            return <ol className="list-decimal list-inside space-y-1 my-2">{children}</ol>;
          },
          ul({ children }) {
            return <ul className="list-disc list-inside space-y-1 my-2">{children}</ul>;
          },
          hr() {
            return <hr className="my-4 border-gray-200" />;
          },
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

function ChatPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [showWelcome, setShowWelcome] = useState(true);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [streamingContent, setStreamingContent] = useState('');
  const lastSeqRef = useRef<number>(0);

  // 刷新 /chat 时回到首页（避免无历史消息时只剩「临时欢迎态」）
  useLayoutEffect(() => {
    if (isPageReload()) {
      router.replace('/');
    }
  }, [router]);

  // 从 URL 读取 sessionId（v1.1 状态接口不返回历史消息）
  useEffect(() => {
    const urlSessionId = searchParams.get('session');
    if (urlSessionId) {
      setSessionId(urlSessionId);
    }
  }, [searchParams]);

  // 更新 URL 中的 sessionId
  const updateUrlSession = (newSessionId: string) => {
    const params = new URLSearchParams(searchParams.toString());
    params.set('session', newSessionId);
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  // 处理用重构后的问题继续追问
  const handleContinueWithQuestion = async (question: string) => {
    if (!question.trim() || isLoading) return;
    
    setShowWelcome(false);
    setValidationError(null);
    
    // 直接用重构后的问题作为用户输入
    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: question.trim(),
      timestamp: new Date(),
    };
    
    setMessages((prev) => [...prev, userMessage]);
    setIsLoading(true);
    setStreamingContent('');
    
    try {
      let currentSessionId = sessionId;
      if (!currentSessionId) {
        currentSessionId = await createSession(question.trim());
        setSessionId(currentSessionId);
        updateUrlSession(currentSessionId);
      }

      const fullContent = await sendMessageAndStream(currentSessionId, userMessage.content);
      
      // 添加AI回复
      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date(),
      };
      
      setMessages((prev) => [...prev, assistantMessage]);
      setStreamingContent('');
      
    } catch (error) {
      console.error('Error:', error);
      setStreamingContent('');
    } finally {
      setIsLoading(false);
    }
  };

  // 滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent]);

  // 加载初始问题
  useEffect(() => {
    const initialQuestion = sessionStorage.getItem('initialQuestion');
    if (initialQuestion) {
      setShowWelcome(false);
      sessionStorage.removeItem('initialQuestion');
      handleSendMessage(initialQuestion);
    }
  }, []);

  const validateInput = (text: string): { valid: boolean; suggestion?: string } => {
    if (text.trim().length === 0) {
      return { valid: false, suggestion: '请输入内容' };
    }
    const invalidPatterns = ['...', '。。。', '？？？', '!!!', '111', 'asdf', '测试', 'asdfgh'];
    if (invalidPatterns.includes(text.trim())) {
      return { valid: false, suggestion: '请认真回复😊' };
    }
    return { valid: true };
  };

  const createSession = async (question: string): Promise<string> => {
    return sandboxClient.createSession('CALIBRATION', question);
  };

  const sendMessageAndStream = async (
    sid: string,
    content: string
  ): Promise<string> => {
    const idemKey = `idem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    await sandboxClient.sendMessage(sid, content, idemKey);

    let streamDone = false;
    let fullContent = '';
    await sandboxClient.streamTurn(sid, lastSeqRef.current, ({ eventType, eventId, dataRaw }) => {
      if (eventId && !eventId.startsWith('hb-')) {
        const seq = Number(eventId);
        if (!Number.isNaN(seq)) lastSeqRef.current = Math.max(lastSeqRef.current, seq);
      }
      try {
        const parsed = JSON.parse(dataRaw);
        const piece = parsed?.payload?.content || '';
        if (eventType === 'agent_chunk') {
          fullContent += piece;
          setStreamingContent(fullContent);
        }
        if (eventType === 'done') {
          return false;
        }
        if (eventType === 'turn_done') {
          streamDone = true;
          return true;
        }
      } catch {
        // ignore parse errors
      }
      return false;
    });

    if (!streamDone) {
      // keep current behavior if stream closes unexpectedly
      return fullContent;
    }
    return fullContent;
  };

  const handleSendMessage = async (text?: string) => {
    const messageText = text || input;
    if (!messageText.trim() || isLoading) return;

    const validation = validateInput(messageText);
    if (!validation.valid) {
      setValidationError(validation.suggestion || '输入无效');
      setTimeout(() => setValidationError(null), 5000);
      return;
    }

    setShowWelcome(false);
    setValidationError(null);

    const userMessage: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: messageText.trim(),
      timestamp: new Date(),
    };

    setMessages((prev) => [...prev, userMessage]);
    setInput('');
    setIsLoading(true);
    setStreamingContent('');
    let activeSessionId = sessionId;
    if (!activeSessionId) {
      try {
        activeSessionId = await createSession(messageText.trim());
        setSessionId(activeSessionId);
        updateUrlSession(activeSessionId);
      } catch (error) {
        setIsLoading(false);
        const msg = error instanceof Error ? error.message : '未知错误';
        const errorMessage: Message = {
          id: Date.now().toString(),
          role: 'assistant',
          content: `会话创建失败：${msg}`,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, errorMessage]);
        return;
      }
    }
    let timedOut = false;
    const timeout = setTimeout(() => {
      timedOut = true;
      (async () => {
        try {
          if (!activeSessionId) {
            setIsLoading(false);
            setStreamingContent('');
            return;
          }
          const msgList = await sandboxClient.listMessages(activeSessionId);
          const latestAgent = [...msgList].reverse().find(m => m.role === 'AGENT' && m.content?.trim());
          if (latestAgent) {
            const recovered: Message = {
              id: Date.now().toString(),
              role: 'assistant',
              content: latestAgent.content,
              timestamp: new Date(),
            };
            setMessages((prev) => [...prev, recovered]);
          } else {
            const timeoutMessage: Message = {
              id: Date.now().toString(),
              role: 'assistant',
              content: '响应超时，请重试（网络波动时可再次发送）。',
              timestamp: new Date(),
            };
            setMessages((prev) => [...prev, timeoutMessage]);
          }
        } catch {
          const timeoutMessage: Message = {
            id: Date.now().toString(),
            role: 'assistant',
            content: '响应超时，请重试（网络波动时可再次发送）。',
            timestamp: new Date(),
          };
          setMessages((prev) => [...prev, timeoutMessage]);
        } finally {
          setIsLoading(false);
          setStreamingContent('');
        }
      })();
    }, 15000);

    try {
      let fullContent = await sendMessageAndStream(activeSessionId, messageText.trim());
      if (timedOut) return;
      if (!fullContent.trim()) {
        const msgList = await sandboxClient.listMessages(activeSessionId);
        const latestAgent = [...msgList].reverse().find(m => m.role === 'AGENT' && m.content?.trim());
        if (latestAgent) {
          fullContent = latestAgent.content;
        }
      }

      const assistantMessage: Message = {
        id: Date.now().toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date(),
      };

      setMessages((prev) => [...prev, assistantMessage]);
      setStreamingContent('');
    } catch (error) {
      if (timedOut) return;
      console.error('Error:', error);
      const msg = error instanceof Error ? error.message : '未知错误';
      const hint = msg === 'Failed to fetch'
        ? '（请确认 Java WebFlux 后端已启动：cd java-backend && mvn spring-boot:run）'
        : '';
      const errorMessage: Message = {
        id: Date.now().toString(),
        role: 'assistant',
        content: `抱歉，出了点问题：${msg}${hint}`,
        timestamp: new Date(),
      };
      setMessages((prev) => [...prev, errorMessage]);
    } finally {
      clearTimeout(timeout);
      if (!timedOut) {
        setIsLoading(false);
      }
    }
  };

  const handleKeyPress = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const handleNewChat = () => {
    router.push('/');
  };

  return (
    <div className="h-screen flex flex-col bg-gray-50">
      {/* Header */}
      <header className="bg-white border-b border-gray-100 px-6 py-4">
        <div className="max-w-3xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-4">
            <button
              onClick={handleNewChat}
              className="p-2 hover:bg-gray-100 rounded-lg transition-colors"
              title="新对话"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 19l-7-7m0 0l7-7m-7 7h18" />
              </svg>
            </button>
            <div>
              <h1 className="text-lg font-semibold text-gray-800">QuestionOS</h1>
              <p className="text-xs text-gray-500">
                问题校准助手
                {sessionId && (
                  <span className="ml-2 text-gray-400 font-mono" title="Session ID">
                    #{sessionId.slice(0, 8)}
                  </span>
                )}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 text-sm text-green-600">
            <span className="w-2 h-2 bg-green-500 rounded-full"></span>
            在线
          </div>
          <button
            onClick={() => router.push('/history')}
            className="flex items-center gap-2 px-3 py-1.5 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors text-sm"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            历史
          </button>
          <div className="ml-2">
            <AuthButton />
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto px-6 py-8">
          {/* Welcome */}
          {showWelcome && messages.length === 0 && (
            <div className="mb-8">
              <h2 className="text-2xl font-light text-gray-700 mb-4">你在想什么问题？</h2>
              <p className="text-gray-500 mb-6">我会通过追问帮你理清问题的本质</p>
              
              <div className="flex flex-wrap gap-2 mb-6">
                {EXAMPLE_QUESTIONS.map((q, i) => (
                  <button
                    key={i}
                    onClick={() => setInput(q)}
                    className="px-4 py-2 bg-white text-gray-600 text-sm rounded-full border border-gray-200 hover:border-blue-300 hover:bg-blue-50 transition-all"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Validation Error */}
          {validationError && (
            <div className="mb-4 p-4 bg-amber-50 border border-amber-200 rounded-xl text-amber-800 text-sm">
              💭 {validationError}
            </div>
          )}

          {/* Messages */}
          <div className="space-y-4">
            {messages.map((message, index) => {
              // 判断是否是首轮AI回复：第一条assistant消息
              const isFirstAssistantMessage = 
                message.role === 'assistant' && 
                messages.slice(0, index).filter(m => m.role === 'assistant').length === 0;
              
              return (
                <div
                  key={message.id}
                  className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}
                >
                  <div
                    className={`max-w-[80%] rounded-2xl px-4 py-3 ${
                      message.role === 'user'
                        ? 'bg-slate-100 text-slate-800 rounded-br-md'
                        : 'bg-white border border-slate-200 text-slate-800 rounded-bl-md shadow-sm'
                    }`}
                  >
                    {message.role === 'user' ? (
                      <div className="whitespace-pre-wrap text-[15px] leading-relaxed">
                        {message.content}
                      </div>
                    ) : (
                      <AIMessage 
                        content={message.content} 
                        onContinueWithQuestion={handleContinueWithQuestion}
                      />
                    )}
                  </div>
                </div>
              );
            })}
            
            {/* Streaming Content */}
            {streamingContent && (
              <div className="flex justify-start">
                <div className="max-w-[80%] bg-white border border-gray-100 text-gray-800 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm">
                  <AIMessage 
                    content={streamingContent} 
                  />
                  <span className="animate-pulse">▌</span>
                </div>
              </div>
            )}
            
            {/* Loading */}
            {isLoading && !streamingContent && (
              <div className="flex justify-start">
                <div className="bg-white border border-gray-100 rounded-2xl rounded-bl-md px-4 py-3 shadow-sm">
                  <div className="flex gap-1">
                    <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce"></span>
                    <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0.1s' }}></span>
                    <span className="w-2 h-2 bg-blue-400 rounded-full animate-bounce" style={{ animationDelay: '0.2s' }}></span>
                  </div>
                </div>
              </div>
            )}
            
            <div ref={messagesEndRef} />
          </div>
        </div>
      </div>

      {/* Input */}
      <div className="border-t border-gray-100 bg-white px-6 py-4">
        <div className="max-w-3xl mx-auto">
          <div className="flex gap-3 items-end">
            <div className="flex-1 relative">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                onKeyPress={handleKeyPress}
                placeholder="输入你的回答..."
                className="w-full px-4 py-3 bg-gray-50 border border-gray-200 rounded-2xl focus:outline-none focus:border-blue-300 focus:bg-white resize-none text-[15px]"
                rows={1}
                style={{ minHeight: '48px', maxHeight: '120px' }}
                disabled={isLoading}
              />
            </div>
            <button
              onClick={() => handleSendMessage()}
              disabled={isLoading || !input.trim()}
              className={`px-6 py-3 rounded-2xl text-white font-medium transition-all ${
                isLoading || !input.trim()
                  ? 'bg-blue-300 cursor-not-allowed'
                  : 'bg-blue-600 hover:bg-blue-700 active:scale-95'
              }`}
            >
              发送
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// 用 Suspense 包装以支持 useSearchParams
export default function ChatPageWrapper() {
  return (
    <Suspense fallback={
      <div className="h-screen flex items-center justify-center bg-gray-50">
        <div className="w-6 h-6 border-2 border-gray-300 border-t-gray-600 rounded-full animate-spin"></div>
      </div>
    }>
      <ChatPageContent />
    </Suspense>
  );
}