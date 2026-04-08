'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useRef, useState } from 'react';
import { AuthButton, useAuth } from '../AuthButton';
import { markInternalChatNav } from '../../lib/chat-nav';
import { setBackgroundContext } from '../../lib/background-context';

const SCENARIO_GROUPS: { icon: string; title: string; items: string[] }[] = [
  {
    icon: '💼',
    title: '职场决策',
    items: [
      '老板总是临时加需求，怎么优雅地拒绝？',
      '项目延期了，怎么向客户解释？',
      '想要升职加薪，但不知道怎么开口？',
      '团队里有人总是拖延，怎么推进？',
    ],
  },
  {
    icon: '🎯',
    title: '个人成长',
    items: [
      '我想转行但担心沉没成本，该怎么决策？',
      '想建立个人品牌，但不知道从哪切入？',
      '每天都很忙但产出不高，怎么优化时间？',
      '想提升演讲能力，但一上台就紧张？',
    ],
  },
  {
    icon: '🚀',
    title: '团队管理',
    items: [
      '团队扩招后文化稀释，怎么保持凝聚力？',
      '跨部门协作总是扯皮，怎么推进？',
      '技术选型分歧大，团队达不成共识？',
      '想提升团队执行力，制度怎么设计？',
    ],
  },
];

const EXTRA_SCENARIOS = [
  '团队有两个技术方案，如何评估选择？',
  '最近工作效率很低，总是拖延，怎么办？',
  '有两个工作机会，一个钱多一个稳定，怎么选？',
  '想创业但资金有限，该从哪里开始？',
  '竞品做得比我们好，怎么追赶？',
];

export default function HomePage() {
  const router = useRouter();
  const { user } = useAuth();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [backgroundText, setBackgroundText] = useState('');
  const [scenariosExpanded, setScenariosExpanded] = useState(false);
  const [selectedMode, setSelectedMode] = useState<'calibrate' | 'consult' | null>(null);
  const [experienceQuestion, setExperienceQuestion] = useState('');

  const persistBackground = useCallback(() => {
    setBackgroundContext(backgroundText);
  }, [backgroundText]);

  const scrollToExperience = useCallback(() => {
    document.getElementById('experience')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }, []);

  const requireUser = useCallback(
    (fn: () => void) => {
      if (!user) {
        router.push('/login');
        return;
      }
      fn();
    },
    [user, router]
  );

  const selectCalibrate = useCallback(
    (prefill?: string) => {
      setSelectedMode('calibrate');
      if (prefill !== undefined) setExperienceQuestion(prefill);
      scrollToExperience();
    },
    [scrollToExperience]
  );

  const selectConsult = useCallback(
    (prefill?: string) => {
      setSelectedMode('consult');
      if (prefill !== undefined) setExperienceQuestion(prefill);
      scrollToExperience();
    },
    [scrollToExperience]
  );

  const handleExperienceSubmit = useCallback(() => {
    if (!selectedMode || !experienceQuestion.trim()) return;
    requireUser(() => {
      persistBackground();
      if (selectedMode === 'calibrate') {
        sessionStorage.setItem('initialQuestion', experienceQuestion.trim());
        markInternalChatNav();
        router.push('/chat');
      } else {
        sessionStorage.setItem('consultQuestion', experienceQuestion.trim());
        router.push('/consult');
      }
    });
  }, [selectedMode, experienceQuestion, requireUser, persistBackground, router]);

  const onPickFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (!f) return;
    if (f.size > 128 * 1024) {
      alert('文件请小于 128KB；大文档请粘贴摘要。');
      return;
    }
    const text = await f.text();
    setBackgroundText((prev) => (prev ? `${prev}\n\n---\n\n${text}` : text));
  };

  return (
    <div className="min-h-screen bg-white text-gray-900">
      <nav
        className="fixed top-0 left-0 right-0 z-[1000] border-b border-gray-200 bg-white/95 backdrop-blur-md"
        role="navigation"
        aria-label="主导航"
      >
        <div className="max-w-7xl mx-auto px-6 py-4 flex items-center justify-between">
          <button
            type="button"
            onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
            className="flex items-center gap-2 text-left"
            aria-label="QuestionOS 首页"
          >
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-teal-500 to-teal-400 flex items-center justify-center text-white text-xl font-bold shadow-md shadow-teal-500/25">
              Q
            </div>
            <span className="text-xl font-bold text-gray-900">QuestionOS</span>
          </button>
          <div className="hidden md:flex items-center gap-8">
            <a href="#features" className="text-gray-600 hover:text-gray-900 font-medium text-sm transition-colors">
              功能
            </a>
            <a href="#scenarios" className="text-gray-600 hover:text-gray-900 font-medium text-sm transition-colors">
              场景
            </a>
            <a href="#workflow" className="text-gray-600 hover:text-gray-900 font-medium text-sm transition-colors">
              使用方法
            </a>
            {user && (
              <button
                type="button"
                onClick={() => router.push('/history')}
                className="text-gray-600 hover:text-gray-900 font-medium text-sm transition-colors"
              >
                历史
              </button>
            )}
          </div>
          <div className="flex items-center gap-3">
            <AuthButton />
          </div>
        </div>
      </nav>

      <section className="relative min-h-screen flex flex-col justify-center pt-24 pb-16 px-6 overflow-hidden" aria-label="首屏介绍">
        <div
          className="pointer-events-none absolute w-[450px] h-[450px] rounded-full bg-teal-500/[0.06] blur-[60px] -top-24 -right-24"
          aria-hidden
        />
        <div
          className="pointer-events-none absolute w-[450px] h-[450px] rounded-full bg-teal-500/[0.06] blur-[60px] -bottom-32 -left-24"
          aria-hidden
        />

        <div className="relative z-10 max-w-6xl mx-auto w-full">
          <header className="text-center mb-10">
            <h1 className="text-5xl sm:text-6xl md:text-7xl lg:text-8xl font-black text-gray-900 tracking-tight leading-tight mb-4">
              QuestionOS
            </h1>
            <p className="text-xl sm:text-2xl md:text-3xl font-semibold text-gray-800 mb-3">让问题更清晰，让决策更明智</p>
            <p className="text-base sm:text-lg text-gray-500 max-w-2xl mx-auto leading-relaxed">
              将模糊的问题转化为清晰可执行的指令
              <br className="hidden sm:block" />
              通过 AI 对话发现思维盲点
            </p>
          </header>

          <div className="flex flex-col sm:flex-row gap-4 justify-center mb-8">
            <button
              type="button"
              onClick={scrollToExperience}
              className="inline-flex items-center justify-center gap-2 rounded-xl px-10 py-4 text-lg font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-lg shadow-teal-500/25 hover:shadow-xl hover:shadow-teal-500/30 hover:-translate-y-0.5 transition-all"
            >
              立即体验
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
              </svg>
            </button>
            <a
              href="#features"
              className="inline-flex items-center justify-center rounded-xl px-10 py-4 text-lg font-semibold border-2 border-teal-500 text-teal-600 hover:bg-teal-50 transition-all"
            >
              了解更多
            </a>
          </div>

          <p className="text-center text-sm text-gray-500 max-w-xl mx-auto">
            向下选择「思维校准」或「沙盘推演」，选中后再填写问题与可选背景资料。
          </p>
        </div>
      </section>

      {/* 先选模式，再出现输入区 */}
      <section
        id="experience"
        className="relative scroll-mt-28 py-16 px-6 bg-gradient-to-b from-gray-50/80 to-white border-t border-gray-100"
        aria-labelledby="experience-heading"
      >
        <div className="max-w-4xl mx-auto">
          <header className="text-center mb-10">
            <h2 id="experience-heading" className="text-2xl sm:text-3xl font-bold text-gray-900 mb-2">
              选择模式
            </h2>
            <p className="text-gray-500 text-sm sm:text-base">
              点选一种能力后，在下方输入你的问题；登录后即可开始。
            </p>
          </header>

          <div className="grid md:grid-cols-2 gap-5 max-w-3xl mx-auto">
            <button
              type="button"
              onClick={() => selectCalibrate()}
              className={`text-left rounded-2xl border-2 bg-white p-7 shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5 ${
                selectedMode === 'calibrate'
                  ? 'border-teal-500 ring-2 ring-teal-200'
                  : 'border-gray-200 hover:border-teal-200'
              }`}
            >
              <div className="w-14 h-14 rounded-xl flex items-center justify-center text-2xl mb-4 bg-teal-500/[0.08] border border-teal-500/15">
                🔍
              </div>
              <h3 className="text-xl font-bold text-gray-900 mb-1">思维校准</h3>
              <span className="inline-block px-2.5 py-0.5 rounded-full text-xs font-medium bg-teal-500/10 text-teal-700 border border-teal-500/20 mb-2">
                单 Agent 多轮对话
              </span>
              <p className="text-gray-600 text-sm leading-relaxed">
                理清问题、不给现成答案，用追问帮你自己想清楚。
              </p>
            </button>

            <button
              type="button"
              onClick={() => selectConsult()}
              className={`text-left rounded-2xl border-2 bg-white p-7 shadow-sm transition-all hover:shadow-md hover:-translate-y-0.5 ${
                selectedMode === 'consult'
                  ? 'border-teal-500 ring-2 ring-teal-200'
                  : 'border-gray-200 hover:border-teal-200'
              }`}
            >
              <div className="w-14 h-14 rounded-xl flex items-center justify-center text-2xl mb-4 bg-teal-500/[0.08] border border-teal-500/15">
                ⚔️
              </div>
              <h3 className="text-xl font-bold text-gray-900 mb-1">沙盘推演</h3>
              <span className="inline-block px-2.5 py-0.5 rounded-full text-xs font-medium bg-teal-500/10 text-teal-700 border border-teal-500/20 mb-2">
                多 Agent 模拟辩论
              </span>
              <p className="text-gray-600 text-sm leading-relaxed">
                多角色碰撞与压力测试，适合方案论证与共识拉扯。
              </p>
            </button>
          </div>

          {selectedMode && (
            <div className="max-w-2xl mx-auto mt-10 space-y-5">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">你要解决什么问题？</label>
                <textarea
                  value={experienceQuestion}
                  onChange={(e) => setExperienceQuestion(e.target.value)}
                  placeholder={
                    selectedMode === 'calibrate'
                      ? '用一两句话描述你的纠结、决策或想理清的事…'
                      : '描述要推演的情境、方案或争议点…'
                  }
                  rows={4}
                  className="w-full rounded-xl border border-gray-200 px-4 py-3 text-sm text-gray-800 focus:border-teal-400 focus:ring-2 focus:ring-teal-100 outline-none resize-y min-h-[120px]"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-2">背景资料（可选）（开发中）</label>
                <p className="text-xs text-gray-500 mb-2">
                  粘贴摘要，或上传 .txt / .md（&lt;128KB）；将附在首轮消息中。
                </p>
                <textarea
                  value={backgroundText}
                  onChange={(e) => setBackgroundText(e.target.value)}
                  placeholder="会议记录、需求片段、约束条件等…"
                  rows={3}
                  className="w-full rounded-xl border border-gray-200 px-4 py-3 text-sm text-gray-800 focus:border-teal-400 focus:ring-2 focus:ring-teal-100 outline-none resize-y"
                />
                <div className="flex flex-wrap items-center gap-3 mt-2">
                  <input ref={fileInputRef} type="file" accept=".txt,.md,text/plain,text/markdown" className="hidden" onChange={onPickFile} />
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    className="text-sm font-medium text-teal-600 hover:text-teal-700"
                  >
                    上传文本文件
                  </button>
                </div>
              </div>
              <div className="flex flex-col sm:flex-row gap-3 sm:items-center">
                <button
                  type="button"
                  onClick={handleExperienceSubmit}
                  disabled={!experienceQuestion.trim()}
                  className="inline-flex items-center justify-center gap-2 rounded-xl px-8 py-3.5 text-base font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-md shadow-teal-500/20 hover:shadow-lg disabled:opacity-40 disabled:cursor-not-allowed transition-all"
                >
                  {selectedMode === 'calibrate' ? '进入思维校准' : '进入沙盘推演'}
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
                  </svg>
                </button>
                {!user && <p className="text-xs text-gray-500">提交时将跳转登录，随后进入对话。</p>}
              </div>
            </div>
          )}

          {!selectedMode && (
            <p className="text-center text-sm text-gray-400 mt-8">↑ 请先点选「思维校准」或「沙盘推演」</p>
          )}
        </div>
      </section>

      <div className="h-px max-w-6xl mx-auto bg-gradient-to-r from-transparent via-gray-200 to-transparent my-16" aria-hidden />

      <section id="features" className="py-20 px-6 scroll-mt-24" aria-labelledby="features-heading">
        <div className="max-w-7xl mx-auto">
          <header className="text-center mb-14">
            <h2 id="features-heading" className="text-3xl md:text-5xl font-bold text-gray-900 mb-4">
              两大核心能力
            </h2>
            <p className="text-lg md:text-xl text-gray-500 max-w-2xl mx-auto">针对不同决策场景，提供两种智能对话模式</p>
          </header>
          <div className="grid lg:grid-cols-2 gap-8">
            <article className="rounded-2xl border border-gray-200 bg-white p-8 md:p-10 shadow-sm">
              <div className="flex flex-col sm:flex-row gap-6">
                <div className="w-16 h-16 shrink-0 rounded-2xl flex items-center justify-center text-3xl bg-teal-500/[0.08] border border-teal-500/15">
                  🔍
                </div>
                <div>
                  <h3 className="text-2xl md:text-3xl font-bold text-gray-900 mb-2">思维校准</h3>
                  <p className="text-lg font-semibold text-teal-600 mb-6">单 Agent 多轮对话 · 引导式思考</p>
                  <ul className="space-y-4 mb-8 text-left">
                    {[
                      ['深度追问与引导', 'AI 不会直接给答案，而是通过苏格拉底式提问帮你理清思路'],
                      ['结构化问题拆解', '将复杂问题拆解为可执行的小步骤'],
                      ['假设检验', '识别并验证你的隐含假设'],
                    ].map(([t, d]) => (
                      <li key={t} className="flex gap-3">
                        <span className="text-teal-600 font-bold shrink-0">✓</span>
                        <div>
                          <strong className="text-gray-900">{t}</strong>
                          <p className="text-sm text-gray-600 mt-1">{d}</p>
                        </div>
                      </li>
                    ))}
                  </ul>
                  <div className="rounded-xl border border-teal-500/15 bg-teal-500/[0.04] p-4 text-sm">
                    <p className="font-semibold text-gray-900">适用场景</p>
                    <p className="text-gray-600 mt-1">个人决策 · 职业规划 · 问题分析 · 创意发散</p>
                  </div>
                </div>
              </div>
            </article>
            <article className="rounded-2xl border border-gray-200 bg-white p-8 md:p-10 shadow-sm">
              <div className="flex flex-col sm:flex-row gap-6">
                <div className="w-16 h-16 shrink-0 rounded-2xl flex items-center justify-center text-3xl bg-teal-500/[0.08] border border-teal-500/15">
                  ⚔️
                </div>
                <div>
                  <h3 className="text-2xl md:text-3xl font-bold text-gray-900 mb-2">沙盘推演</h3>
                  <p className="text-lg font-semibold text-teal-600 mb-6">多 Agent 模拟辩论 · 全视角碰撞</p>
                  <ul className="space-y-4 mb-8 text-left">
                    {[
                      ['多角色模拟辩论', '不同立场的 AI 角色围绕你的议题展开讨论'],
                      ['压力测试', '模拟极端情况和反对意见，验证方案鲁棒性'],
                      ['全景视角', '从多个维度审视同一问题，避免认知偏差'],
                    ].map(([t, d]) => (
                      <li key={t} className="flex gap-3">
                        <span className="text-teal-600 font-bold shrink-0">✓</span>
                        <div>
                          <strong className="text-gray-900">{t}</strong>
                          <p className="text-sm text-gray-600 mt-1">{d}</p>
                        </div>
                      </li>
                    ))}
                  </ul>
                  <div className="rounded-xl border border-teal-500/15 bg-teal-500/[0.04] p-4 text-sm">
                    <p className="font-semibold text-gray-900">适用场景</p>
                    <p className="text-gray-600 mt-1">战略决策 · 风险评估 · 方案论证 · 团队共识</p>
                  </div>
                </div>
              </div>
            </article>
          </div>
        </div>
      </section>

      <div className="h-px max-w-6xl mx-auto bg-gradient-to-r from-transparent via-gray-200 to-transparent my-16" aria-hidden />

      <section id="scenarios" className="py-20 px-6 bg-gray-50 scroll-mt-24" aria-labelledby="scenarios-heading">
        <div className="max-w-7xl mx-auto">
          <header className="text-center mb-14">
            <h2 id="scenarios-heading" className="text-3xl md:text-5xl font-bold text-gray-900 mb-4">
              试试这些真实场景
            </h2>
            <p className="text-lg text-gray-500 max-w-2xl mx-auto">
              点击后带入「选择模式」区并预选思维校准，填好问题后登录开始
            </p>
          </header>
          <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-8">
            {SCENARIO_GROUPS.map((g) => (
              <div key={g.title} className="space-y-3">
                <h3 className="text-lg font-bold text-gray-900 flex items-center gap-2">
                  <span aria-hidden>{g.icon}</span> {g.title}
                </h3>
                {g.items.map((q) => (
                  <button
                    key={q}
                    type="button"
                    onClick={() => selectCalibrate(q)}
                    className="w-full text-left rounded-xl border border-gray-200 bg-white px-4 py-3 shadow-sm hover:border-teal-300 hover:shadow-md hover:translate-x-1 transition-all"
                  >
                    <p className="text-gray-800 font-medium text-sm leading-snug">{q}</p>
                  </button>
                ))}
              </div>
            ))}
          </div>
          {scenariosExpanded && (
            <div className="mt-10 grid sm:grid-cols-2 lg:grid-cols-3 gap-3 max-w-5xl mx-auto">
              {EXTRA_SCENARIOS.map((q) => (
                <button
                  key={q}
                  type="button"
                  onClick={() => selectCalibrate(q)}
                  className="text-left rounded-xl border border-gray-200 bg-white px-4 py-3 text-sm text-gray-700 hover:border-teal-300 transition-all"
                >
                  {q}
                </button>
              ))}
            </div>
          )}
          <div className="text-center mt-10">
            <button
              type="button"
              onClick={() => setScenariosExpanded((v) => !v)}
              className="inline-flex items-center rounded-xl border-2 border-teal-500 px-6 py-3 font-semibold text-teal-600 hover:bg-teal-50 transition-colors"
            >
              {scenariosExpanded ? '收起更多场景' : '查看更多场景 →'}
            </button>
          </div>
        </div>
      </section>

      <div className="h-px max-w-6xl mx-auto bg-gradient-to-r from-transparent via-gray-200 to-transparent my-16" aria-hidden />

      <section id="workflow" className="py-20 px-6 scroll-mt-24" aria-labelledby="workflow-heading">
        <div className="max-w-6xl mx-auto">
          <header className="text-center mb-14">
            <h2 id="workflow-heading" className="text-3xl md:text-5xl font-bold text-gray-900 mb-4">
              三步开启智能决策
            </h2>
            <p className="text-lg text-gray-500">简单三步，快速上手</p>
          </header>
          <ol className="grid md:grid-cols-3 gap-12 list-none">
            {[
              {
                n: '1',
                t: '选择模式',
                d: '根据你的需求，选择「思维校准」或「沙盘推演」',
                hint: ['🔍 单人深度思考', '⚔️ 多方观点碰撞'],
              },
              {
                n: '2',
                t: '描述问题',
                d: '用自然语言描述困境；可上传或粘贴背景资料（开发中）',
                hint: ['✍️ 支持中英文', '📎 首轮附带背景资料（开发中）'],
              },
              {
                n: '3',
                t: '获得洞察',
                d: 'AI 通过对话帮你理清思路；校准模式含可勾选行动建议清单',
                hint: ['💡 结构化输出', '📋 行动建议清单'],
              },
            ].map((step) => (
              <li key={step.n} className="text-center">
                <div className="flex justify-center mb-6">
                  <div className="w-[72px] h-[72px] rounded-full bg-gradient-to-br from-teal-500 to-teal-400 text-white text-2xl font-extrabold flex items-center justify-center shadow-lg shadow-teal-500/25">
                    {step.n}
                  </div>
                </div>
                <h3 className="text-2xl font-bold text-gray-900 mb-3">{step.t}</h3>
                <p className="text-gray-600 leading-relaxed mb-4">{step.d}</p>
                <div className="inline-block rounded-xl border border-gray-200 bg-white px-4 py-3 shadow-sm text-sm text-gray-700 text-left">
                  {step.hint.map((line) => (
                    <p key={line} className="font-medium">
                      {line}
                    </p>
                  ))}
                </div>
              </li>
            ))}
          </ol>
          <div className="text-center mt-14">
            <button
              type="button"
              onClick={scrollToExperience}
              className="inline-flex items-center justify-center gap-2 rounded-xl px-12 py-4 text-lg font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-lg shadow-teal-500/25 hover:shadow-xl transition-all"
            >
              立即开始体验
              <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
              </svg>
            </button>
            <p className="text-sm text-gray-500 mt-4">登录后使用 · Web 端</p>
          </div>
        </div>
      </section>

      <section className="py-20 px-6 bg-gray-50" aria-labelledby="cta-heading">
        <div className="max-w-4xl mx-auto text-center">
          <h2 id="cta-heading" className="text-3xl md:text-5xl font-bold text-gray-900 mb-6">
            准备好做出更明智的决策了吗？
          </h2>
          <p className="text-lg text-gray-600 mb-10">登录后开始校准或沙盘推演</p>
          <button
            type="button"
            onClick={() => {
              if (user) {
                selectCalibrate();
              } else {
                router.push('/register');
              }
            }}
            className="inline-flex items-center justify-center gap-2 rounded-xl px-12 py-5 text-lg font-semibold text-white bg-gradient-to-br from-teal-500 to-teal-400 shadow-lg shadow-teal-500/25 hover:shadow-xl transition-all"
          >
            {user ? '进入思维校准' : '免费注册账号'}
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden>
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 7l5 5m0 0l-5 5m5-5H6" />
            </svg>
          </button>
        </div>
      </section>
    </div>
  );
}
