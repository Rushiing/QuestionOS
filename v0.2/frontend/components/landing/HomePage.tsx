'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AuthButton, useAuth } from '../AuthButton';
import { markInternalChatNav } from '../../lib/chat-nav';
import { extractBackgroundDocument } from '../../lib/background-extract';
import { setBackgroundContext, truncateBackgroundText } from '../../lib/background-context';

type LandingMode = 'calibrate' | 'consult';

const MODE_COPY: Record<
  LandingMode,
  {
    name: string;
    signal: string;
    badge: string;
    description: string;
    fit: string;
    placeholder: string;
    cta: string;
    preview: string[];
  }
> = {
  calibrate: {
    name: '思维校准',
    signal: '不确定自己真正想问什么',
    badge: '单 Agent 多轮对话',
    description: '适合理清问题。系统不会急着给建议，而是连续追问，帮你把模糊、摇摆、说不清的判断一步步想清楚。',
    fit: '适合：个人纠结、职业选择、关系判断、自我怀疑、目标不清。',
    placeholder: '写下一个你想理清、但现在还说不准的问题...',
    cta: '进入思维校准',
    preview: [
      '第 1 轮：先抓住你话里最模糊的变量。',
      '第 2 轮：继续追问一个更深的判断依据。',
      '多轮之后：问题变清楚，再收束下一步行动。',
    ],
  },
  consult: {
    name: '沙盘推演',
    signal: '已经有方案、角色或代价在互相拉扯',
    badge: '多 Agent 模拟辩论',
    description: '适合方案论证。多个角色会围绕你的议题辩论、质疑和压力测试，帮助你做多维度思考，最后对齐可执行共识。',
    fit: '适合：方案取舍、团队协作、商业/技术冲突、资源分配、共识拉齐。',
    placeholder: '描述一个需要论证、对齐或压力测试的方案情境...',
    cta: '进入沙盘推演',
    preview: [
      '分诊：先判断议题属于哪类审议室。',
      '辩论：多角色从概念、代价、价值和执行面施压。',
      '整合：收束为可对齐的共识与下一步验证动作。',
    ],
  },
};

const SCENARIO_GROUPS: { title: string; mode: LandingMode; items: string[] }[] = [
  {
    title: '校准模式：问题还没成形',
    mode: 'calibrate',
    items: [
      '我想离开现在的团队，但又觉得自己可能太敏感。',
      '我总想做点自己的东西，但每次开始又觉得不现实。',
      '我不知道自己是想升职，还是只是想证明自己没输。',
    ],
  },
  {
    title: '沙盘模式：冲突已经出现',
    mode: 'consult',
    items: [
      '两个技术方案分歧很大，短期交付和长期架构怎么取舍？',
      '留存差但少数用户很爱，下一步该改定位、重做功能，还是继续获客？',
      '销售要冲季度目标，交付说资源不够，产品要不要挡需求？',
    ],
  },
];

const MAX_BG_FILE_BYTES = 2 * 1024 * 1024;
const BG_TEXT_EXT = new Set(['txt', 'md', 'markdown']);

function backgroundFileExt(name: string): string {
  const i = name.lastIndexOf('.');
  return i >= 0 ? name.slice(i + 1).toLowerCase() : '';
}

const EXTRA_SCENARIOS = [
  { text: '最近工作效率很低，我真正该砍掉什么？', mode: 'calibrate' as const },
  { text: '想创业但资金有限，该先验证用户、现金流还是团队能力？', mode: 'consult' as const },
  { text: '客户预算有限，质量、范围和交付节奏必须重新谈。', mode: 'consult' as const },
  { text: '我越来越不想参加团队讨论，但又怕自己是在逃避协作。', mode: 'calibrate' as const },
];

export default function HomePage() {
  const router = useRouter();
  const { user } = useAuth();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const questionInputRef = useRef<HTMLTextAreaElement>(null);
  const [backgroundText, setBackgroundText] = useState('');
  const [backgroundFileName, setBackgroundFileName] = useState<string | null>(null);
  const [backgroundBusy, setBackgroundBusy] = useState(false);
  const [backgroundErr, setBackgroundErr] = useState<string | null>(null);
  const [scenariosExpanded, setScenariosExpanded] = useState(false);
  const [selectedMode, setSelectedMode] = useState<LandingMode>('calibrate');
  const [experienceQuestion, setExperienceQuestion] = useState('');

  const persistBackground = useCallback(() => {
    setBackgroundContext(backgroundText);
  }, [backgroundText]);

  const applyScenario = useCallback((mode: LandingMode, prefill: string) => {
    setSelectedMode(mode);
    setExperienceQuestion(prefill);
    requestAnimationFrame(() => {
      questionInputRef.current?.focus();
      questionInputRef.current?.setSelectionRange(prefill.length, prefill.length);
    });
  }, []);

  const handleExperienceSubmit = useCallback(() => {
    const question = experienceQuestion.trim();
    if (!question) return;

    persistBackground();
    sessionStorage.setItem('qosPendingLandingMode', selectedMode);
    sessionStorage.setItem('qosPendingQuestion', question);

    if (!user) {
      router.push('/login');
      return;
    }

    sessionStorage.removeItem('qosPendingLandingMode');
    sessionStorage.removeItem('qosPendingQuestion');
    if (selectedMode === 'calibrate') {
      sessionStorage.setItem('initialQuestion', question);
      markInternalChatNav();
      router.push('/chat');
    } else {
      sessionStorage.setItem('consultQuestion', question);
      router.push('/consult');
    }
  }, [selectedMode, experienceQuestion, user, persistBackground, router]);

  const clearBackgroundFile = useCallback(() => {
    setBackgroundText('');
    setBackgroundFileName(null);
    setBackgroundErr(null);
  }, []);

  const prevUserRef = useRef(user);
  useEffect(() => {
    if (prevUserRef.current && !user && (backgroundText || backgroundFileName)) {
      clearBackgroundFile();
    }
    prevUserRef.current = user;
  }, [user, backgroundText, backgroundFileName, clearBackgroundFile]);

  const onPickFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (!f) return;
    setBackgroundErr(null);
    if (!user) {
      setBackgroundErr('请先登录后再上传背景文件');
      return;
    }
    if (f.size > MAX_BG_FILE_BYTES) {
      setBackgroundErr('单文件请小于 2MB');
      return;
    }
    const ext = backgroundFileExt(f.name);
    if (!['txt', 'md', 'markdown', 'doc', 'docx'].includes(ext)) {
      setBackgroundErr('仅支持 .txt / .md / .doc / .docx');
      return;
    }
    setBackgroundBusy(true);
    try {
      let text: string;
      if (BG_TEXT_EXT.has(ext)) {
        text = await f.text();
      } else {
        const r = await extractBackgroundDocument(f);
        text = r.text;
      }
      setBackgroundText(truncateBackgroundText(text));
      setBackgroundFileName(f.name);
    } catch (err) {
      setBackgroundErr(err instanceof Error ? err.message : '读取失败');
      setBackgroundFileName(null);
      setBackgroundText('');
    } finally {
      setBackgroundBusy(false);
    }
  };

  const currentMode = MODE_COPY[selectedMode];
  const modeOrder: LandingMode[] = ['calibrate', 'consult'];
  return (
    <div className="min-h-screen bg-[#f7f8f8] text-[#161a19]">
      <nav className="sticky top-0 z-50 border-b border-[#e2e7e4] bg-[#f7f8f8]/90 backdrop-blur-md" aria-label="主导航">
        <div className="mx-auto flex h-16 max-w-[1180px] items-center justify-between gap-5 px-6">
          <button
            type="button"
            onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })}
            className="flex items-center gap-3 text-left"
            aria-label="QuestionOS 首页"
          >
            <span className="grid h-[34px] w-[34px] place-items-center rounded bg-[#161a19] font-serif text-[19px] font-semibold text-white">Q</span>
            <span className="font-serif text-lg font-semibold tracking-[-0.01em]">QuestionOS</span>
          </button>
          <div className="flex items-center gap-3">
            {user && (
              <button
                type="button"
                onClick={() => router.push('/history')}
                className="hidden rounded border border-[#c3cbc6] bg-white px-4 py-2 text-sm font-medium text-[#161a19] transition-colors hover:border-[#161a19] hover:bg-[#f0f2f1] sm:inline-flex"
              >
                历史
              </button>
            )}
            <AuthButton />
          </div>
        </div>
      </nav>

      <main>
        <section id="experience" className="scroll-mt-20 px-6 py-12 sm:py-16" aria-labelledby="experience-heading">
          <div className="mx-auto grid max-w-[1180px] items-stretch gap-10 lg:grid-cols-2 lg:gap-14">
            <div className="flex h-full flex-col justify-between">
              <p className="font-mono text-[11px] uppercase tracking-[0.22em] text-[#626b66]">
                <span className="text-[#2f6a4a]">●</span> QuestionOS · Decision Lab
              </p>
              <h1
                id="experience-heading"
                className="mt-5 font-serif text-[clamp(2.3rem,3.7vw,3.3rem)] font-medium leading-[1.1] tracking-[-0.02em] text-[#161a19]"
              >
                在找答案之前，
                <br />
                先想清楚
                <em className="text-[#2f6a4a]">该怎么想</em>。
              </h1>
              <p className="mt-5 max-w-[30rem] text-[17px] leading-8 text-[#626b66]">
                <span className="font-semibold text-[#161a19]">QuestionOS</span> 先分清你此刻的状态：问题还没说清，还是方案已在互相拉扯，再带你进入对应的多轮工作流。
              </p>

              <div className="mt-8 border-t border-[#e2e7e4] pt-7">
                <p className="font-serif text-[clamp(1.7rem,2.5vw,2.2rem)] font-medium leading-[1.14] tracking-[-0.015em]">
                  让问题<em className="text-[#2f6a4a]">更清晰</em>，
                  <br />
                  让决策<em className="text-[#2f6a4a]">更笃定</em>。
                </p>
                <ol className="mt-5">
                  {modeOrder.map((mode, index) => {
                    const item = MODE_COPY[mode];
                    return (
                      <li key={mode} className="grid grid-cols-[auto_1fr_auto] items-baseline gap-4 border-t border-[#e2e7e4] px-1 py-3 transition-colors hover:bg-[#eef4f0]">
                        <span className="pt-1 font-mono text-xs tracking-wide text-[#2f6a4a]">{String(index + 1).padStart(2, '0')}</span>
                        <span className="min-w-0">
                          <span className="block text-[15px] font-semibold text-[#161a19]">{item.name}</span>
                          <span className="mt-1 block text-[12.5px] leading-5 text-[#626b66]">{item.signal}：{mode === 'calibrate' ? '连续追问，把模糊想清楚。' : '多角色审议，压出共识。'}</span>
                        </span>
                        <span className="whitespace-nowrap pt-1 font-mono text-[10.5px] tracking-wide text-[#626b66]">
                          {mode === 'calibrate' ? '单 Agent · 多轮' : '多 Agent · 审议'}
                        </span>
                      </li>
                    );
                  })}
                </ol>
              </div>
            </div>

            <div className="flex flex-col overflow-hidden rounded-md border border-[#161a1938] bg-white shadow-[0_1px_0_rgba(22,26,25,0.06),0_18px_48px_rgba(22,26,25,0.08)]">
              <div className="flex items-center justify-between gap-3 border-b border-[#e2e7e4] px-5 py-3.5">
                <strong className="text-sm font-semibold">先说说你的情况</strong>
                <span className="text-xs text-[#626b66]">不用一次说全，我们边聊边理清</span>
              </div>

              <div className="grid border-b border-[#e2e7e4] md:grid-cols-2" role="group" aria-label="选择思考模式">
                {modeOrder.map((mode) => {
                  const item = MODE_COPY[mode];
                  const active = selectedMode === mode;
                  return (
                    <button
                      key={mode}
                      type="button"
                      onClick={() => setSelectedMode(mode)}
                      aria-pressed={active}
                      className={`relative border-b border-[#e2e7e4] p-5 text-left transition-colors last:border-b-0 md:border-b-0 md:border-r md:last:border-r-0 ${
                        active ? 'bg-[#edf5ef]' : 'bg-white hover:bg-[#f3f5f4]'
                      }`}
                    >
                      {active && <span className="absolute left-0 top-0 h-full w-[3px] bg-[#2f6a4a]" aria-hidden />}
                      <span className="flex items-center justify-between gap-2">
                        <span className="font-serif text-lg font-semibold">{item.name}</span>
                        <span className="rounded-full border border-[#e2e7e4] px-2 py-0.5 font-mono text-[10px] uppercase tracking-[0.06em] text-[#626b66]">
                          {mode === 'calibrate' ? 'Single' : 'Multi'}
                        </span>
                      </span>
                      <span className="mt-3 block text-[13px] leading-6 text-[#303634]">{item.description}</span>
                      <span className="mt-2 block text-[11.5px] leading-5 text-[#626b66]">{item.fit.replace('适合：', '适合 · ')}</span>
                    </button>
                  );
                })}
              </div>

              <div className="flex flex-1 flex-col p-5">
                <div className="mb-2 flex items-center justify-between gap-3">
                  <label htmlFor="landing-question" className="text-[13px] font-semibold text-[#161a19]">你要解决什么问题？</label>
                  <span className="font-mono text-xs text-[#2f6a4a]">当前：{currentMode.name}</span>
                </div>
                <textarea
                  ref={questionInputRef}
                  id="landing-question"
                  value={experienceQuestion}
                  onChange={(e) => setExperienceQuestion(e.target.value)}
                  placeholder={currentMode.placeholder}
                  rows={5}
                  className="min-h-[104px] max-h-44 w-full resize-y rounded border border-[#161a1938] bg-[#f9faf9] px-3.5 py-3 text-[15px] leading-6 text-[#161a19] outline-none transition focus:border-[#2f6a4a] focus:ring-4 focus:ring-[#2f6a4a24]"
                />
                <p className="mt-2 text-xs text-[#626b66]">{currentMode.fit}</p>

                <input
                  ref={fileInputRef}
                  type="file"
                  accept=".txt,.md,.markdown,.doc,.docx,text/plain,text/markdown,application/msword,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                  className="hidden"
                  onChange={onPickFile}
                />
                <div className="my-4 flex flex-col gap-2 text-[12.5px] text-[#626b66] sm:flex-row sm:items-center">
                  <button
                    type="button"
                    onClick={() => fileInputRef.current?.click()}
                    disabled={backgroundBusy || !user}
                    className="inline-flex items-center justify-center rounded border border-dashed border-[#161a1938] px-3 py-2 transition hover:border-[#161a19] hover:text-[#161a19] disabled:cursor-not-allowed disabled:opacity-45"
                  >
                    {backgroundFileName ? `已选：${backgroundFileName}` : '背景资料（可选）'}
                  </button>
                  <span>支持 .txt / .md / .doc / .docx，随首条问题带入</span>
                  {backgroundFileName && (
                    <button type="button" onClick={clearBackgroundFile} className="text-[#626b66] underline underline-offset-2 hover:text-[#161a19]">
                      移除
                    </button>
                  )}
                </div>
                {!user && <p className="-mt-2 mb-3 text-xs text-[#626b66]">上传文件需要先登录；文字问题会在登录后继续。</p>}
                {backgroundBusy && <p className="-mt-2 mb-3 text-xs text-[#2f6a4a]">正在读取并抽取文本...</p>}
                {backgroundErr && <p className="-mt-2 mb-3 text-xs text-rose-700">{backgroundErr}</p>}

                <button
                  type="button"
                  onClick={handleExperienceSubmit}
                  disabled={!experienceQuestion.trim()}
                  className="mt-auto w-full rounded bg-[#161a19] px-4 py-3.5 text-[15px] font-semibold tracking-wide text-white transition hover:bg-[#213026] disabled:cursor-not-allowed disabled:bg-[#d7dcd9] disabled:text-[#626b66]"
                >
                  {currentMode.cta} →
                </button>
              </div>
            </div>
          </div>
        </section>

        <section id="scenarios" className="px-6 pb-12" aria-labelledby="scenarios-heading">
          <div className="mx-auto max-w-[1180px]">
            <div className="mb-4 flex flex-wrap items-baseline justify-between gap-3">
              <h2 id="scenarios-heading" className="font-serif text-[22px] font-semibold">不知道从哪儿开始？试试这些真实场景</h2>
              <p className="max-w-[34rem] text-[13px] text-[#626b66]">挑一个最像你的，它会填到上面的框里，你可以改完再开始。</p>
            </div>
            <div className="grid gap-5 lg:grid-cols-2">
              {SCENARIO_GROUPS.map((group) => (
                <article key={group.title} className="overflow-hidden rounded border border-[#e2e7e4] bg-white shadow-[0_1px_0_rgba(22,26,25,0.04),0_8px_22px_rgba(22,26,25,0.06)]">
                  <div className="flex items-center justify-between border-b border-[#e2e7e4] bg-[#f9faf9] px-4 py-3">
                    <h3 className="text-[13.5px] font-semibold">{group.title}</h3>
                    <span className="font-mono text-[10px] uppercase tracking-[0.08em] text-[#2f6a4a]">{group.mode === 'calibrate' ? 'Calibrate' : 'Sandbox'}</span>
                  </div>
                  {group.items.map((text) => (
                    <button
                      key={text}
                      type="button"
                      onClick={() => applyScenario(group.mode, text)}
                      className="block w-full border-t border-[#e2e7e4] bg-white px-4 py-3 text-left text-[13.5px] leading-6 text-[#161a19] transition-all first:border-t-0 hover:bg-[#f3f5f4] hover:pl-5"
                    >
                      <span className="mr-2 text-[#95a09a]">→</span>{text}
                    </button>
                  ))}
                </article>
              ))}
            </div>
            {scenariosExpanded && (
              <div className="mt-4 grid gap-3 sm:grid-cols-2">
                {EXTRA_SCENARIOS.map((item) => (
                  <button
                    key={item.text}
                    type="button"
                    onClick={() => applyScenario(item.mode, item.text)}
                    className="rounded border border-[#e2e7e4] bg-white px-4 py-3 text-left text-[13.5px] leading-6 text-[#161a19] transition hover:border-[#c3cbc6] hover:bg-[#f3f5f4]"
                  >
                    <span className="mr-2 text-[#95a09a]">→</span>{item.text}
                  </button>
                ))}
              </div>
            )}
            <button
              type="button"
              onClick={() => setScenariosExpanded((v) => !v)}
              className="mt-4 rounded border border-[#e2e7e4] bg-transparent px-4 py-2.5 text-[13px] text-[#626b66] transition hover:border-[#161a19] hover:text-[#161a19]"
            >
              {scenariosExpanded ? '收起更多场景' : '查看更多场景'}
            </button>
          </div>
        </section>

        <section className="border-y border-[#e2e7e4] bg-[#f9faf9]" aria-label="使用流程">
          <div className="mx-auto grid max-w-[1180px] sm:grid-cols-2 lg:grid-cols-4">
            {[
              ['01', '先选状态', '看不清自己，就校准；冲突已经出现，就沙盘。'],
              ['02', '写下你的问题', '直接描述困惑或冲突；不确定怎么开口，再从案例里找个最像的。'],
              ['03', '开始对话', '你写的内容和背景资料都会带过去，不用重说一遍。'],
              ['04', '多轮推进', '校准连续追问，沙盘轮流审议，直到问题变清楚。'],
            ].map(([n, title, text]) => (
              <div key={n} className="border-b border-r border-[#e2e7e4] p-5 last:border-r-0 lg:border-b-0">
                <div className="font-mono text-xs text-[#2f6a4a]">{n}</div>
                <div className="mt-2 text-sm font-semibold">{title}</div>
                <p className="mt-1 text-[12.5px] leading-5 text-[#626b66]">{text}</p>
              </div>
            ))}
          </div>
        </section>

        <footer className="border-t border-[#e2e7e4] px-6 py-8">
          <div className="mx-auto flex max-w-[1180px] flex-wrap items-center justify-between gap-3 text-[12.5px] text-[#626b66]">
            <span>QuestionOS · 认知协同 Agent</span>
            <span>先判断问题，再进入对话</span>
          </div>
        </footer>
      </main>
    </div>
  );
}
