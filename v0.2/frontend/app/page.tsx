'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { AuthButton, useAuth } from '../components/AuthButton';

const EXAMPLE_QUESTIONS = [
  "我想转行但担心沉没成本，该怎么决策？",
  "团队有两个技术方案，如何评估选择？",
  "最近工作效率很低，总是拖延，怎么办？",
  "有两个工作机会，一个钱多一个稳定，怎么选？",
  "想学新技术但怕学了没用，要不要投入时间？",
  "和同事关系不好，影响工作状态，怎么处理？",
  "老板总是临时加需求，怎么优雅地拒绝？",
  "想创业但资金有限，该从哪里开始？",
  "产品上线后用户反馈不好，下一步怎么办？",
  "团队士气低落，如何提升大家的积极性？",
  "想转管理岗但没经验，机会从哪来？",
  "竞品做得比我们好，怎么追赶？",
  "加班太多影响生活质量，要不要换工作？",
  "新技术栈学习曲线陡，团队抵触怎么办？",
  "项目延期了，怎么向客户解释？",
  "想提升个人影响力，但不知道从哪下手？",
  "团队沟通效率低，信息总是传不到位？",
  "做了5年同一件事，感觉没有成长怎么办？",
  "想要升职加薪，但不知道怎么开口？",
  "行业不景气，要不要提前做准备？",
  "想跳槽但怕新环境不适应，怎么评估风险？",
  "团队里有人总是拖延，怎么推进？",
  "想做副业但时间精力有限，如何平衡？",
  "客户需求变来变去，怎么管理预期？",
  "想提升演讲能力，但一上台就紧张？",
  "团队扩招后文化稀释，怎么保持凝聚力？",
  "想做内容创作但不知道定位什么方向？",
  "和上级意见不合，该坚持还是妥协？",
  "想系统学习某个领域，从哪里开始？",
  "项目成功了，但功劳被别人抢了怎么办？",
  "想建立个人品牌，但不知道从哪切入？",
  "团队缺乏创新，怎么做才能突破现状？",
  "想转行做AI，但技术基础薄弱怎么办？",
  "客户预算有限，怎么做出高质量交付？",
  "想提升团队执行力，制度怎么设计？",
  "做了很多项目但没沉淀，怎么建立体系？",
  "想学英语但总是坚持不下来，有什么方法？",
  "团队里有关系户，工作推不动怎么办？",
  "想做独立开发者，先从什么产品开始？",
  "每天都很忙但产出不高，怎么优化时间？",
  "想拓展人脉，但社交场合不知道聊什么？",
  "产品质量问题频发，根本原因在哪？",
  "想写技术博客，但不知道写什么内容？",
  "跨部门协作总是扯皮，怎么推进？",
  "想从执行者变成决策者，需要什么能力？",
  "技术选型分歧大，团队达不成共识？",
  "想提升产品思维，从哪个角度切入？",
  "团队成员能力参差不齐，怎么带？",
  "想做一个长期项目，但短期看不到收益？",
  "总是被琐事打断，如何保持深度工作？",
];

export default function Home() {
  const router = useRouter();
  const { user } = useAuth();
  const [question, setQuestion] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [shuffledQuestions, setShuffledQuestions] = useState<string[]>([]);
  const [mode, setMode] = useState<'calibrate' | 'consult'>('calibrate');

  // 随机排序示例问题
  useEffect(() => {
    const shuffled = [...EXAMPLE_QUESTIONS].sort(() => Math.random() - 0.5);
    setShuffledQuestions(shuffled);
  }, []);

  // 模式选择：点击时如果已有问题则直接跳转
  const handleModeSelect = (selectedMode: 'calibrate' | 'consult') => {
    if (question.trim()) {
      // 已有问题，直接跳转
      if (selectedMode === 'consult') {
        sessionStorage.setItem('consultQuestion', question.trim());
        router.push('/consult');
      } else {
        sessionStorage.setItem('initialQuestion', question.trim());
        router.push('/chat');
      }
    } else {
      // 没有问题，只是切换模式
      setMode(selectedMode);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!question.trim() || isSubmitting) return;

    setIsSubmitting(true);
    
    if (mode === 'consult') {
      // 咨询模式：跳转到咨询页面
      sessionStorage.setItem('consultQuestion', question.trim());
      router.push('/consult');
    } else {
      // 校准模式：跳转到对话页面
      sessionStorage.setItem('initialQuestion', question.trim());
      router.push('/chat');
    }
  };

  const useExample = (q: string) => {
    setQuestion(q);
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-slate-50 to-white text-gray-800 flex flex-col">
      {/* Header with Login and History */}
      <header className="w-full max-w-2xl mx-auto px-6 pt-6">
        <div className="flex items-center justify-between pr-2">
          {/* 历史记录：仅登录后显示 */}
          {user && (
            <button
              onClick={() => router.push('/history')}
              className="flex items-center gap-2 px-3 py-2 text-gray-500 hover:text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
            >
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span className="text-sm">历史记录</span>
            </button>
          )}
          <div className="ml-auto">
            <AuthButton />
          </div>
        </div>
      </header>

      {/* Main Content - Centered */}
      <main className="flex-1 flex flex-col items-center justify-center px-6">
        {/* Title */}
        <h1 className="text-6xl md:text-7xl font-light tracking-tight mb-2 text-gray-900">
          QuestionOS
        </h1>
        <p className="text-gray-400 text-lg mb-12 font-light">
          让问题更清晰，让决策更明智
        </p>

        {/* Input Form */}
        <form onSubmit={handleSubmit} className="w-full max-w-2xl mb-6">
          <div className="relative group">
            <textarea
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  handleSubmit(e);
                }
              }}
              placeholder="输入你的问题，按 Enter 开始..."
              className="relative w-full h-[80px] p-5 pr-28 text-base text-gray-800 bg-white border border-gray-200 rounded-2xl resize-none focus:outline-none focus:border-blue-300 focus:ring-4 focus:ring-blue-50 transition-all duration-300 placeholder:text-gray-400 shadow-sm overflow-hidden"
              disabled={isSubmitting}
            />
            
            <div className="absolute right-4 top-1/2 -translate-y-1/2">
              <button
                type="submit"
                disabled={!question.trim() || isSubmitting}
                className="px-5 py-2.5 bg-gray-900 text-white rounded-xl font-medium text-sm hover:bg-gray-800 disabled:opacity-40 disabled:cursor-not-allowed transition-all duration-200 flex items-center gap-2"
              >
                <span>{isSubmitting ? '准备中...' : '开始'}</span>
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                </svg>
              </button>
            </div>
          </div>
        </form>

        {/* Mode Selection */}
        <div className="w-full max-w-2xl mb-10">
          <div className="flex gap-4">
            <button
              type="button"
              onClick={() => handleModeSelect('calibrate')}
              className={`flex-1 py-4 px-6 rounded-xl font-medium transition-all flex items-center justify-center gap-3 ${
                mode === 'calibrate'
                  ? 'bg-blue-50 text-blue-700 border-2 border-blue-200 shadow-sm'
                  : 'bg-white text-gray-600 border-2 border-gray-200 hover:border-gray-300'
              }`}
            >
              <span className="text-xl">🔍</span>
              <div className="text-left">
                <div className="font-semibold">思维校准</div>
                <div className="text-xs opacity-70">帮你理清问题，不给答案</div>
              </div>
            </button>
            <button
              type="button"
              onClick={() => handleModeSelect('consult')}
              className={`flex-1 py-4 px-6 rounded-xl font-medium transition-all flex items-center justify-center gap-3 ${
                mode === 'consult'
                  ? 'bg-purple-50 text-purple-700 border-2 border-purple-200 shadow-sm'
                  : 'bg-white text-gray-600 border-2 border-gray-200 hover:border-gray-300'
              }`}
            >
              <span className="text-xl">⚔️</span>
              <div className="text-left">
                <div className="font-semibold">沙盘推演</div>
                <div className="text-xs opacity-70">修罗场压力测试，炼化决策</div>
              </div>
            </button>
          </div>
        </div>

        {/* Example Questions - Single Line Marquee */}
        <div className="w-full max-w-2xl overflow-hidden">
          <div className="flex items-center gap-4">
            <span className="text-gray-400 text-sm whitespace-nowrap">试试这些：</span>
            <div className="flex-1 overflow-hidden mask-gradient">
              <div 
                className="flex items-center gap-4 animate-marquee"
                style={{ width: 'fit-content' }}
              >
                {shuffledQuestions.length > 0 && [...shuffledQuestions, ...shuffledQuestions].map((q, i) => (
                  <button
                    key={i}
                    onClick={() => useExample(q)}
                    className="px-4 py-2 rounded-full text-sm bg-gray-50 text-gray-600 border border-gray-200 hover:border-gray-400 hover:bg-white transition-all duration-300 whitespace-nowrap"
                  >
                    {q}
                  </button>
                ))}
              </div>
            </div>
          </div>
        </div>
      </main>

      

      <style jsx>{`
        @keyframes marquee {
          0% {
            transform: translateX(0);
          }
          100% {
            transform: translateX(-50%);
          }
        }
        
        .animate-marquee {
          animation: marquee 360s linear infinite;
        }
        
        .mask-gradient {
          mask-image: linear-gradient(to right, transparent, black 5%, black 95%, transparent);
          -webkit-mask-image: linear-gradient(to right, transparent, black 5%, black 95%, transparent);
        }
      `}</style>
    </div>
  );
}