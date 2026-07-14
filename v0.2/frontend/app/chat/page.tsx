'use client';

import React, { useState, useEffect, useLayoutEffect, useRef, Suspense, type ReactNode } from 'react';
import { useRouter, useSearchParams, usePathname } from 'next/navigation';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { AuthButton, useAuth } from '../../components/AuthButton';
import { sandboxClient } from '../../lib/sandbox-client';
import { CHAT_INTERNAL_NAV_KEY } from '../../lib/chat-nav';
import { takeBackgroundContext, wrapUserMessageWithBackground } from '../../lib/background-context';
import { CHAT_RECOMMENDED_SCENARIOS } from '../../lib/recommended-scenarios';
import { handleEnterToSubmit, resizeComposer } from '../../lib/keyboard-ime';
import { formatCalibrationJsonToMarkdown } from '../../lib/calibration-json-to-markdown';
import { beginNavigation } from '../../lib/navigation-feedback';

/**
 * React 18 Strict Mode 下 /chat 会挂载两次：第一次 useLayoutEffect 消费掉站内导航标记后，
 * 第二次挂载若再读 sessionStorage 会误判为「非法进入」并 replace('/')，表现为首页点击「开始」无反应。
 */
let chatInternalNavStrictModeGuard = false;
interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  timestamp: Date;
}

function markdownChildrenToPlainText(node: ReactNode): string {
  if (node == null || typeof node === 'boolean') return '';
  if (typeof node === 'string' || typeof node === 'number') return String(node);
  if (Array.isArray(node)) return node.map(markdownChildrenToPlainText).join('');
  if (typeof node === 'object' && 'props' in node) {
    const el = node as React.ReactElement<{ children?: ReactNode }>;
    return markdownChildrenToPlainText(el.props?.children);
  }
  return '';
}

/** 思维校准气泡：突出「本轮追问」，其余小节卡片化，避免灰底斜体「说明书」感 */
const calibrationMarkdownComponents: Components = {
  code({ className, children, ...props }) {
    const codeContent = String(children);
    const isInline = !codeContent.includes('\n');
    if (isInline) {
      return (
        <code
          className="rounded border border-[#e2e7e4] bg-[#f7f8f8] px-1.5 py-0.5 font-mono text-[0.88em] text-[#303634]"
          {...props}
        >
          {children}
        </code>
      );
    }
    return (
      <pre className="my-3 overflow-x-auto whitespace-pre-wrap rounded border border-[#d5ded9] bg-[#f7f8f8] p-4 font-mono text-sm text-[#303634]">
        <code {...props}>{children}</code>
      </pre>
    );
  },
  h2({ children }) {
    const label = markdownChildrenToPlainText(children).replace(/\s+/g, '');
    if (label.includes('本轮追问')) {
      return (
        <div className="mb-3 mt-0">
          <span className="inline-flex items-center rounded bg-[#2f6a4a] px-3.5 py-1.5 text-[13px] font-semibold tracking-wide text-white shadow-[0_6px_16px_rgba(47,106,74,0.18)]">
            本轮追问
          </span>
        </div>
      );
    }
    return (
      <h2 className="mb-2 mt-6 border-b border-[#e2e7e4] pb-2 font-serif text-lg font-semibold tracking-[-0.01em] text-[#161a19] first:mt-0">
        {children}
      </h2>
    );
  },
  h3({ children }) {
    return (
      <h3 className="mb-2 mt-5 flex items-center gap-3 text-base font-semibold leading-snug text-[#161a19]">
        <span className="h-4 w-1 shrink-0 rounded-full bg-[#2f6a4a]" aria-hidden />
        <span>{children}</span>
      </h3>
    );
  },
  blockquote({ children }) {
    return (
      <blockquote className="my-3 rounded border border-[#cbd8d0] bg-[#f9faf9] px-4 py-3.5 not-italic text-[#303634]">
        <div className="text-[1.02rem] font-normal leading-relaxed tracking-tight text-[#303634] [&_strong]:font-normal">
          {children}
        </div>
      </blockquote>
    );
  },
  p({ children }) {
    return <p className="my-1.5 text-[15px] font-normal leading-7 text-[#303634]">{children}</p>;
  },
  em({ children }) {
    return <em className="font-normal not-italic text-[#303634]">{children}</em>;
  },
  strong({ children }) {
    return <strong className="font-semibold text-[#161a19]">{children}</strong>;
  },
  hr() {
    return <hr className="my-5 h-px border-0 bg-[#e2e7e4]" />;
  },
  ol({ children }) {
    return (
      <ol className="my-2 ml-4 list-decimal space-y-1 text-[15px] font-normal leading-7 text-[#303634] marker:text-[#626b66]">
        {children}
      </ol>
    );
  },
  ul({ children }) {
    return (
      <ul className="my-2 ml-4 list-disc space-y-1 text-[15px] font-normal leading-7 text-[#303634] marker:text-[#626b66]">
        {children}
      </ul>
    );
  },
};

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
              return <code className="rounded bg-[#edf5ef] px-1.5 py-0.5 text-sm font-medium text-[#2f6a4a]" {...props}>{children}</code>;
            }
            return (
              <div className="relative">
                <pre className="my-3 overflow-x-auto whitespace-pre-wrap rounded-xl border border-[#cbd8d0] bg-[#edf5ef] p-4 text-sm leading-relaxed text-gray-800 shadow-sm">
                  <code {...props}>{children}</code>
                </pre>
              </div>
            );
          },
          blockquote({ children }) {
            return <blockquote className="my-2 rounded-r-lg border-l-4 border-[#2f6a4a] bg-[#edf5ef] py-2 pl-4 text-gray-700">{children}</blockquote>;
          },
          h2({ children }) {
            return <h2 className="text-xl font-bold text-gray-800 mt-4 mb-3">{children}</h2>;
          },
          h3({ children }) {
            const titleText = String(children);
            const isRefinedTitle = titleText.includes('重构后的天才提问');
            return (
              <div className="flex items-center gap-3 mt-4 mb-2">
                <h3 className="flex items-center gap-2 text-lg font-semibold text-[#2f6a4a]">{children}</h3>
                {isRefinedTitle && hasRefinedQuestion && refinedQuestion && onContinueWithQuestion && (
                  <button
                    onClick={() => onContinueWithQuestion(refinedQuestion)}
                    className="flex items-center gap-1.5 whitespace-nowrap rounded-lg bg-[#2f6a4a] px-3 py-1 text-xs font-medium text-white shadow-sm transition-all duration-200 hover:bg-[#244f39] hover:shadow-md"
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
  const displayContent = formatCalibrationJsonToMarkdown(content);
  // 解析思维脑图
  const mindMapData = parseMindMapContent(displayContent);
  
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
                return <code className="rounded bg-[#edf5ef] px-1.5 py-0.5 text-sm font-medium text-[#2f6a4a]" {...props}>{children}</code>;
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
        {displayContent}
      </ReactMarkdown>
        
        {/* 思维脑图 */}
        <MindMapView markdown={mindMapData.markdown} title={mindMapData.title} />
      </div>
    );
  }
  
  // 检测是否是炼金输出（包含💎）
  const isAlchemyOutput = displayContent.includes('💎');
  
  // 如果是炼金输出，用专门组件渲染
  if (isAlchemyOutput) {
    return <AlchemyMessage content={displayContent} onContinueWithQuestion={onContinueWithQuestion} />;
  }

  /**
   * 本页仅创建 CALIBRATION 会话，助手内容均应按校准样式渲染。
   * 勿再用「## 本轮追问」做检测：首轮若模型未返回 questions，后端不会带该标题，会误走旧版 Markdown（无 h3 样式）。
   */
  return (
    <div className="calibration-md text-sm leading-relaxed">
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={calibrationMarkdownComponents}>
        {displayContent}
      </ReactMarkdown>
    </div>
  );
}

/** 流式阶段占位：版式与成稿一致，不展示原始 JSON，避免「代码糊脸」 */
function CalibrationStreamingSkeleton() {
  return (
    <div className="calibration-md text-sm leading-relaxed">
      <div className="mb-3 mt-0">
        <span className="inline-flex items-center rounded bg-[#2f6a4a] px-3.5 py-1.5 text-[13px] font-semibold tracking-wide text-white shadow-[0_6px_16px_rgba(47,106,74,0.18)]">
          本轮追问
        </span>
      </div>
      <div className="my-3 rounded border border-[#cbd8d0] bg-[#f9faf9] px-4 py-3.5">
        <div className="space-y-2.5">
          <div className="h-4 max-w-[88%] animate-pulse rounded bg-[#dce6e1]" />
          <div className="h-4 w-full animate-pulse rounded bg-[#e7eeea]" />
          <div className="h-4 max-w-[72%] animate-pulse rounded bg-[#eef3f0]" />
        </div>
      </div>
      <div className="mb-2 mt-5 flex items-center gap-3 text-base font-semibold text-[#95a09a]">
        <span className="h-4 w-1 shrink-0 animate-pulse rounded-full bg-[#dce6e1]" aria-hidden />
        <span className="inline-block h-4 w-28 animate-pulse rounded bg-[#dce6e1]" />
      </div>
      <div className="h-3 max-w-md animate-pulse rounded bg-[#eef3f0]" />
      <p className="mt-4 text-xs text-[#95a09a]">正在组织追问与版面…</p>
      <span className="mt-2 inline-block animate-pulse text-[#95a09a]">▌</span>
    </div>
  );
}

function ChatPageContent() {
  const router = useRouter();
  const { user, loading: authLoading } = useAuth();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [showWelcome, setShowWelcome] = useState(true);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [streamingContent, setStreamingContent] = useState('');
  /** 模型仍在吐 token：只显示骨架，不展示原始 JSON */
  const [streamingSkeletonActive, setStreamingSkeletonActive] = useState(false);
  /** 非 null 时对 streamingContent 做打字机式逐字显现（与骨架衔接） */
  const [streamingTypeTarget, setStreamingTypeTarget] = useState<string | null>(null);
  /** 欢迎态下先展示推荐场景；点此或发首条后才露出底部输入框 */
  const [composerUnlocked, setComposerUnlocked] = useState(false);
  const lastSeqRef = useRef<number>(0);
  const loadedHistorySessionRef = useRef<string | null>(null);

  const showInputBar = composerUnlocked || messages.length > 0;

  useLayoutEffect(() => {
    resizeComposer(inputRef.current);
  }, [input]);

  const prefillQuestion = (question: string) => {
    setInput(question);
    setComposerUnlocked(true);
    requestAnimationFrame(() => {
      inputRef.current?.focus();
      inputRef.current?.setSelectionRange(question.length, question.length);
    });
  };

  useEffect(() => {
    if (streamingTypeTarget === null) {
      return;
    }
    const full = streamingTypeTarget;
    if (full.length === 0) {
      setStreamingTypeTarget(null);
      return;
    }
    let i = 0;
    let raf = 0;
    const charsPerFrame = 3;
    const tick = () => {
      i = Math.min(full.length, i + charsPerFrame);
      setStreamingContent(full.slice(0, i));
      if (i < full.length) {
        raf = requestAnimationFrame(tick);
      } else {
        setStreamingTypeTarget(null);
      }
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
    };
  }, [streamingTypeTarget]);

  const urlSessionId = searchParams.get('session');

  // 新对话仅允许站内跳转；带 session 的历史会话允许直达和刷新。
  useLayoutEffect(() => {
    if (urlSessionId) {
      return;
    }
    const hasMark = sessionStorage.getItem(CHAT_INTERNAL_NAV_KEY) === '1';
    if (hasMark) {
      sessionStorage.removeItem(CHAT_INTERNAL_NAV_KEY);
      chatInternalNavStrictModeGuard = true;
      return;
    }
    if (chatInternalNavStrictModeGuard) {
      return;
    }
    router.replace('/');
  }, [router, urlSessionId]);

  // 从 URL 加载历史会话全文。
  useEffect(() => {
    if (!urlSessionId || authLoading || !user) return;
    if (loadedHistorySessionRef.current === urlSessionId) return;
    loadedHistorySessionRef.current = urlSessionId;
    let cancelled = false;
    setSessionId(urlSessionId);
    setShowWelcome(false);
    setIsLoading(true);
    setValidationError(null);
    sandboxClient.listMessages(urlSessionId)
      .then((history) => {
        if (cancelled) return;
        setMessages(history
          .filter((message) => message.content?.trim())
          .map((message) => ({
            id: message.messageId,
            role: String(message.role).toUpperCase() === 'USER' ? 'user' as const : 'assistant' as const,
            content: message.content,
            timestamp: new Date(message.createdAt),
          })));
      })
      .catch((error) => {
        if (cancelled) return;
        console.error('Failed to load session history:', error);
        setValidationError('历史会话加载失败，请稍后重试');
      })
      .finally(() => {
        if (!cancelled) setIsLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [authLoading, urlSessionId, user]);

  // 更新 URL 中的 sessionId
  const updateUrlSession = (newSessionId: string) => {
    const params = new URLSearchParams(searchParams.toString());
    params.set('session', newSessionId);
    router.replace(`${pathname}?${params.toString()}`, { scroll: false });
  };

  // 处理用重构后的问题继续追问
  const handleContinueWithQuestion = async (question: string) => {
    if (!user || !question.trim() || isLoading) return;
    
    setShowWelcome(false);
    setValidationError(null);

    const attachBackgroundOnce = messages.filter((m) => m.role === 'user').length === 0;
    
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
    setStreamingSkeletonActive(false);
    setStreamingTypeTarget(null);
    
    try {
      let currentSessionId = sessionId;
      if (!currentSessionId) {
        currentSessionId = await createSession(question.trim());
        setSessionId(currentSessionId);
        loadedHistorySessionRef.current = currentSessionId;
        updateUrlSession(currentSessionId);
      }

      const fullContent = await sendMessageAndStream(currentSessionId, userMessage.content, attachBackgroundOnce);
      
      // 添加AI回复
      const assistantMessage: Message = {
        id: (Date.now() + 1).toString(),
        role: 'assistant',
        content: fullContent,
        timestamp: new Date(),
      };
      
      setMessages((prev) => [...prev, assistantMessage]);
      setStreamingContent('');
      setStreamingSkeletonActive(false);
      setStreamingTypeTarget(null);
      
    } catch (error) {
      console.error('Error:', error);
      setStreamingContent('');
      setStreamingSkeletonActive(false);
      setStreamingTypeTarget(null);
    } finally {
      setIsLoading(false);
    }
  };

  // 滚动到底部
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, streamingContent, streamingSkeletonActive]);

  // 加载初始问题
  useEffect(() => {
    if (urlSessionId) return;
    const initialQuestion = sessionStorage.getItem('initialQuestion');
    if (initialQuestion) {
      setShowWelcome(false);
      sessionStorage.removeItem('initialQuestion');
      handleSendMessage(initialQuestion);
    }
  }, [urlSessionId]);

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
    content: string,
    attachBackgroundOnce?: boolean
  ): Promise<string> => {
    const idemKey = `idem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    let payload = content;
    if (attachBackgroundOnce) {
      const bg = takeBackgroundContext();
      if (bg) payload = wrapUserMessageWithBackground(content, bg);
    }
    await sandboxClient.sendMessage(sid, payload, idemKey);

    let streamDone = false;
    let chunkBuf = '';
    let receivedModelDelta = false;
    await sandboxClient.streamTurn(sid, lastSeqRef.current, ({ eventType, eventId, dataRaw }) => {
      if (eventId && !eventId.startsWith('hb-')) {
        const seq = Number(eventId);
        if (!Number.isNaN(seq)) lastSeqRef.current = Math.max(lastSeqRef.current, seq);
      }
      try {
        const parsed = JSON.parse(dataRaw);
        const piece = parsed?.payload?.content || '';
        if (eventType === 'agent_delta' && piece) {
          receivedModelDelta = true;
          setStreamingSkeletonActive(true);
        }
        if (eventType === 'agent_error' && piece) {
          setStreamingSkeletonActive(false);
          setStreamingTypeTarget(null);
          chunkBuf += (chunkBuf ? '\n\n' : '') + '⚠️ ' + piece + '\n\n';
          setStreamingContent(chunkBuf);
        }
        if (eventType === 'agent_chunk') {
          if (receivedModelDelta) {
            chunkBuf = piece;
            setStreamingSkeletonActive(false);
            setStreamingTypeTarget(piece);
          } else {
            chunkBuf += piece;
            setStreamingTypeTarget(null);
            setStreamingContent(chunkBuf);
          }
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
      return chunkBuf;
    }
    return chunkBuf;
  };

  const handleSendMessage = async (text?: string) => {
    if (!user) {
      setValidationError('请先登录后再开始对话');
      setTimeout(() => setValidationError(null), 4000);
      return;
    }
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

    const attachBackgroundOnce = messages.filter((m) => m.role === 'user').length === 0;

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
    setStreamingSkeletonActive(false);
    setStreamingTypeTarget(null);
    let activeSessionId = sessionId;
    if (!activeSessionId) {
      try {
        activeSessionId = await createSession(messageText.trim());
        setSessionId(activeSessionId);
        loadedHistorySessionRef.current = activeSessionId;
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
            setStreamingSkeletonActive(false);
            setStreamingTypeTarget(null);
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
          setStreamingSkeletonActive(false);
          setStreamingTypeTarget(null);
        }
      })();
    }, 280000);

    try {
      let fullContent = await sendMessageAndStream(activeSessionId, messageText.trim(), attachBackgroundOnce);
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
      setStreamingSkeletonActive(false);
      setStreamingTypeTarget(null);
    } catch (error) {
      if (timedOut) return;
      console.error('Error:', error);

      // 先尝试从后端获取最新消息——SSE 中途断开（network error / Failed to fetch / Load failed）时，
      // 后端往往已经成功处理。把已生成的内容拉回来，比直接报错给用户友好得多。
      let recoveredContent = '';
      if (activeSessionId) {
        try {
          const msgList = await sandboxClient.listMessages(activeSessionId);
          const latestAgent = [...msgList].reverse().find(
            m => m.role === 'AGENT' && m.content?.trim()
          );
          if (latestAgent) {
            recoveredContent = latestAgent.content;
          }
        } catch (fetchErr) {
          console.warn('Failed to recover latest message after stream error:', fetchErr);
        }
      }

      if (recoveredContent) {
        // 后端已生成内容，直接显示恢复的回复，不需要给用户看错误
        const recoveredMessage: Message = {
          id: Date.now().toString(),
          role: 'assistant',
          content: recoveredContent,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, recoveredMessage]);
      } else {
        // 真的失败了，给一个更友好的错误提示
        const msg = error instanceof Error ? error.message : '未知错误';
        const isNetworkLike =
          msg === 'Failed to fetch' ||
          msg.toLowerCase().includes('network') ||
          msg.toLowerCase().includes('load failed');
        const friendlyMsg = isNetworkLike
          ? '网络连接中断了。请检查网络后再次发送（可以直接重发原内容）。'
          : `出了点问题：${msg}`;
        const errorMessage: Message = {
          id: Date.now().toString(),
          role: 'assistant',
          content: friendlyMsg,
          timestamp: new Date(),
        };
        setMessages((prev) => [...prev, errorMessage]);
      }
      setStreamingContent('');
      setStreamingSkeletonActive(false);
      setStreamingTypeTarget(null);
    } finally {
      clearTimeout(timeout);
      if (!timedOut) {
        setIsLoading(false);
      }
    }
  };

  const handleNewChat = () => {
    beginNavigation();
    router.push('/');
  };

  return (
    <div className="flex h-screen flex-col bg-[#f7f8f8] text-[#161a19]">
      {/* Header */}
      <header className="sticky top-0 z-20 border-b border-[#e2e7e4] bg-[#f7f8f8]/90 px-5 py-3 backdrop-blur-md">
        <div className="mx-auto flex max-w-[760px] flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            <button
              onClick={handleNewChat}
              className="grid h-[34px] w-[34px] shrink-0 place-items-center rounded border border-[#e2e7e4] bg-white transition-colors hover:border-[#161a19] hover:bg-[#f3f5f4]"
              title="回首页"
            >
              <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d="M15 18l-6-6 6-6" />
              </svg>
            </button>
            <button
              type="button"
              onClick={() => {
                beginNavigation();
                router.push('/');
              }}
              className="grid h-[30px] w-[30px] shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white transition-opacity hover:opacity-85"
              aria-label="返回首页"
              title="返回首页"
            >
              Q
            </button>
            <div className="min-w-0">
              <h1 className="flex items-center gap-2 font-serif text-base font-semibold leading-tight tracking-[-0.01em]">
                思维校准
                <span className="rounded border border-[#2f6a4a66] px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] text-[#2f6a4a]">
                  Calibrate
                </span>
              </h1>
              <p className="text-[11.5px] text-[#626b66]">
                单 Agent · 多轮追问{sessionId ? ' · 会话已建立' : ''}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 sm:gap-3">
            <div className="hidden items-center gap-1.5 text-xs font-medium text-[#626b66] sm:flex">
              <span className="h-1.5 w-1.5 rounded-full bg-[#2f6a4a] shadow-[0_0_0_3px_rgba(47,106,74,0.16)]" />
              在线
            </div>
            {user && (
              <button
                type="button"
                onClick={() => {
                  beginNavigation();
                  router.push('/history');
                }}
                className="inline-flex items-center gap-1.5 rounded border border-[#e2e7e4] bg-white px-3 py-1.5 text-sm text-[#161a19] transition-colors hover:border-[#161a19] hover:bg-[#f3f5f4]"
              >
                <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.7} d="M12 8v4l3 2m6-2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                历史
              </button>
            )}
            <AuthButton />
          </div>
        </div>
      </header>

      {/* Main Content */}
      <div className="flex-1 overflow-y-auto scroll-smooth">
        <div className="mx-auto max-w-[760px] px-5 py-12 sm:py-14">
          {/* Welcome */}
          {showWelcome && messages.length === 0 && (
            <div className="mb-10 px-0 py-8 sm:py-12">
              <p className="font-mono text-[11px] uppercase tracking-[0.2em] text-[#626b66]">
                <span className="text-[#2f6a4a]">●</span> Calibrate
              </p>
              <h2 className="mt-3 font-serif text-[clamp(1.9rem,3.4vw,2.6rem)] font-medium leading-[1.1] tracking-[-0.02em] text-[#161a19]">
                把还说不清的问题，
                <br />
                先问清楚。
              </h2>
              <p className="mt-3 max-w-[34rem] text-[15.5px] leading-7 text-[#626b66]">
                我会用连续追问帮你理清问题，而不是立刻给建议清单。可先选一条推荐场景预填，再改成你的真实情况。
              </p>
              <p className="mb-3 mt-8 flex items-center gap-2 text-[12.5px] text-[#626b66] before:h-px before:w-5 before:bg-[#c3cbc6]">推荐场景</p>
              <div className="grid gap-3 sm:grid-cols-2">
                {CHAT_RECOMMENDED_SCENARIOS.map((q, i) => (
                  <button
                    key={i}
                    type="button"
                    onClick={() => {
                      prefillQuestion(q);
                    }}
                    className="rounded border border-[#e2e7e4] bg-white px-4 py-3.5 text-left text-[13.5px] leading-6 text-[#161a19] transition hover:border-[#c3cbc6] hover:bg-[#f3f5f4]"
                  >
                    <span className="mr-1 text-[#95a09a]">→</span>{q}
                  </button>
                ))}
              </div>
              {!composerUnlocked && (
                <button
                  type="button"
                  onClick={() => {
                    setComposerUnlocked(true);
                    requestAnimationFrame(() => inputRef.current?.focus());
                  }}
                  className="mt-6 rounded border border-[#e2e7e4] px-4 py-2.5 text-sm text-[#626b66] transition hover:border-[#161a19] hover:text-[#161a19]"
                >
                  或自己输入问题
                </button>
              )}
            </div>
          )}

          {/* Validation Error */}
          {validationError && (
            <div className="mb-4 rounded border border-[#d6c79a] bg-[#fff9e8] p-4 text-sm text-[#67531a]">
              {validationError}
            </div>
          )}
          {!authLoading && !user && (
            <div className="mb-4 rounded border border-[#cbd8d0] bg-[#edf5ef] p-4 text-sm text-[#2f6a4a]">
              当前为访客模式：可浏览页面，登录后可发起对话并查看历史记录。
            </div>
          )}

          {/* Messages */}
          <div className="space-y-5">
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
                  {message.role === 'user' ? (
                    <div className="max-w-[min(680px,92%)] rounded border border-[#d5ded9] bg-white px-5 py-3.5 text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04)]">
                      <div className="mb-2 flex items-center justify-between gap-3">
                        <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#95a09a]">You</span>
                        <span className="h-1.5 w-1.5 rounded-full bg-[#2f6a4a]" aria-hidden />
                      </div>
                      <div className="whitespace-pre-wrap text-[15px] leading-relaxed">
                        {message.content}
                      </div>
                    </div>
                  ) : (
                    <div className="flex w-full max-w-[min(700px,100%)] items-start gap-3">
                      <div className="mt-1 grid h-8 w-8 shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white">
                        Q
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="mb-2 flex flex-wrap items-center gap-2">
                          <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#2f6a4a]">
                            {isFirstAssistantMessage ? 'Calibrate · First Round' : 'Calibrate'}
                          </span>
                          <span className="h-px min-w-5 flex-1 bg-[#e2e7e4]" aria-hidden />
                        </div>
                        <div className="rounded border border-[#e2e7e4] bg-white px-5 py-4 text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.04)]">
                          <AIMessage
                            content={message.content}
                            onContinueWithQuestion={handleContinueWithQuestion}
                          />
                        </div>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
            
            {/* 流式阶段：先展示与成稿一致的版式骨架，不展示原始 JSON */}
            {streamingSkeletonActive && (
              <div className="flex justify-start">
                <div className="flex w-full max-w-[min(700px,100%)] items-start gap-3">
                  <div className="mt-1 grid h-8 w-8 shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white">
                    Q
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="mb-2 flex items-center gap-2">
                      <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#2f6a4a]">Calibrate</span>
                      <span className="h-px min-w-5 flex-1 bg-[#e2e7e4]" aria-hidden />
                    </div>
                    <div className="rounded border border-[#e2e7e4] bg-white px-5 py-4 text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.04)]">
                      <CalibrationStreamingSkeleton />
                    </div>
                  </div>
                </div>
              </div>
            )}

            {/* 成稿 Markdown：delta 结束后由打字机效果逐段显现 */}
            {streamingContent && (
              <div className="flex justify-start">
                <div className="flex w-full max-w-[min(700px,100%)] items-start gap-3">
                  <div className="mt-1 grid h-8 w-8 shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white">
                    Q
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="mb-2 flex items-center gap-2">
                      <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-[#2f6a4a]">Calibrate</span>
                      <span className="h-px min-w-5 flex-1 bg-[#e2e7e4]" aria-hidden />
                    </div>
                    <div className="rounded border border-[#e2e7e4] bg-white px-5 py-4 text-[#303634] shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.04)]">
                      <AIMessage
                        content={streamingContent}
                      />
                      {isLoading && <span className="animate-pulse text-[#2f6a4a]">▌</span>}
                    </div>
                  </div>
                </div>
              </div>
            )}
            
            {/* Loading */}
            {isLoading &&
              !streamingContent &&
              !streamingSkeletonActive &&
              streamingTypeTarget === null && (
              <div className="flex justify-start">
                <div className="flex w-full max-w-[min(700px,100%)] items-start gap-3">
                  <div className="grid h-8 w-8 shrink-0 place-items-center rounded bg-[#161a19] font-serif text-base font-semibold text-white">
                    Q
                  </div>
                  <div className="rounded border border-[#e2e7e4] bg-white px-4 py-3 shadow-[0_1px_0_rgba(22,26,25,0.04)]">
                    <div className="flex gap-1">
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#95a09a]"></span>
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#95a09a]" style={{ animationDelay: '0.1s' }}></span>
                      <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#95a09a]" style={{ animationDelay: '0.2s' }}></span>
                    </div>
                  </div>
                </div>
              </div>
            )}
            
            <div ref={messagesEndRef} />
          </div>
        </div>
      </div>

      {/* Input：欢迎态下先选场景或点「自己输入」后再显示 */}
      {showInputBar && (
        <div className="shrink-0 border-t border-[#e2e7e4] bg-[#f7f8f8]/95 px-5 py-4 backdrop-blur-md">
          <div className="mx-auto max-w-[760px]">
            <div className="flex items-end gap-2 rounded border border-[#d5ded9] bg-white p-2 shadow-[0_-1px_0_rgba(22,26,25,0.02),0_8px_22px_rgba(22,26,25,0.05)]">
              <div className="relative flex-1">
                <textarea
                  ref={inputRef}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={(e) => handleEnterToSubmit(e, () => void handleSendMessage())}
                  placeholder={user ? '输入你的回答…' : '登录后开始对话'}
                  className="min-h-12 max-h-[120px] w-full resize-none overflow-hidden rounded border-0 bg-transparent px-3 py-3 text-[15px] leading-6 text-[#161a19] placeholder:text-[#95a09a] focus:outline-none"
                  rows={1}
                  disabled={isLoading || !user}
                />
              </div>
              <button
                type="button"
                onClick={() => handleSendMessage()}
                disabled={isLoading || !user || !input.trim()}
                className={`grid h-11 w-16 shrink-0 place-items-center rounded text-sm font-semibold transition ${
                  isLoading || !user || !input.trim()
                    ? 'cursor-not-allowed bg-[#d7dcd9] text-[#626b66]'
                    : 'bg-[#161a19] text-white hover:bg-[#213026]'
                }`}
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
