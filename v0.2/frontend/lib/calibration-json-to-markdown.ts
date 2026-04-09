/**
 * 与 java-backend MainCalibrateAgent#formatCalibrationJson 对齐：
 * 模型偶发直接返回 JSON 时，前端转成与流式 Markdown 一致的版式，避免「代码糊脸」。
 */

function stripMarkdownFence(s: string): string {
  const t = s.trim();
  if (!t.startsWith('```')) return t;
  const firstNl = t.indexOf('\n');
  if (firstNl < 0) return t;
  let inner = t.slice(firstNl + 1);
  const fence = inner.lastIndexOf('```');
  if (fence >= 0) inner = inner.slice(0, fence);
  return inner.trim();
}

function textOrEmpty(v: unknown): string {
  if (v == null) return '';
  if (typeof v === 'string') return v.trim();
  if (typeof v === 'object' && v !== null && 'question' in v) {
    const q = (v as { question?: unknown }).question;
    return typeof q === 'string' ? q.trim() : '';
  }
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return '';
}

function isDecisionShape(root: Record<string, unknown>): boolean {
  if (root.phase != null && typeof root.phase === 'string' && root.phase.trim() !== '') {
    return true;
  }
  return textOrEmpty(root.calibration_mode).toLowerCase() === 'decision';
}

function phaseLabel(phase: string): string {
  if (!phase.trim()) return '深度追问';
  switch (phase.trim().toLowerCase()) {
    case 'scenario_confirm':
      return '场景确认';
    case 'language_clarify':
      return '语言澄清';
    case 'socratic':
      return '深度追问';
    case 'polanyi':
      return '默会兜底';
    case 'synthesis':
      return '结论回放';
    case 'action_anchor':
      return '下一步';
    default:
      return phase;
  }
}

function polanyiLabel(strategy: string): string {
  switch (strategy.trim().toLowerCase()) {
    case 'negative':
      return '反面/否定偏好';
    case 'exemplar':
      return '他人故事镜像';
    case 'past_behavior':
      return '过去选择模式';
    default:
      return strategy;
  }
}

function appendBlockquotedParagraph(sb: string[], text: string): void {
  const normalized = text.replace(/\r\n/g, '\n').trim();
  for (const line of normalized.split('\n', -1)) {
    if (line === '') sb.push('>\n');
    else sb.push(`> **${line}**\n`);
  }
}

function firstQuestionText(questions: unknown[]): string {
  for (const item of questions) {
    const q = textOrEmpty(item);
    if (q) return q;
  }
  return '';
}

function appendDecisionCalibrationMarkdown(
  sb: string[],
  root: Record<string, unknown>,
  questions: unknown[]
): void {
  const phaseRaw = textOrEmpty(root.phase);
  const echo = textOrEmpty(root.scenario_echo);
  const fuzzy = textOrEmpty(root.fuzzy_focus);
  const mirror = textOrEmpty(root.user_conclusion_mirror);
  const reasoning =
    root.reasoning != null && typeof root.reasoning === 'string' ? root.reasoning.trim() : '';
  const suggested =
    root.suggested_direction != null && typeof root.suggested_direction === 'string'
      ? root.suggested_direction.trim()
      : '';

  const firstQuestion = firstQuestionText(questions);

  if (firstQuestion) {
    sb.push('---\n\n', '## 本轮追问\n\n');
    appendBlockquotedParagraph(sb, firstQuestion);
    sb.push('\n---\n\n');
  }

  sb.push('### 阶段\n\n');
  sb.push('*', phaseLabel(phaseRaw), '*');
  if (phaseRaw.toLowerCase() === 'polanyi') {
    const ps = textOrEmpty(root.polanyi_strategy);
    if (ps) {
      sb.push('  \n*（默会策略：', polanyiLabel(ps), '）*');
    }
  }
  sb.push('\n\n');

  const hasUnderstand = echo !== '' || fuzzy !== '' || mirror !== '';
  if (hasUnderstand) {
    sb.push('### 理解确认\n\n');
    if (echo) sb.push(echo, '\n\n');
    if (fuzzy) sb.push('*正在澄清的词：* ', fuzzy, '\n\n');
    if (mirror) sb.push('*你的结论（回放）：*\n\n', mirror, '\n\n');
  }

  if (reasoning) {
    sb.push('### 追问理由\n\n', reasoning, '\n\n');
  }

  if (suggested) {
    sb.push('### 建议探索方向\n\n', suggested, '\n');
    const actionItems = splitActionItems(suggested);
    if (actionItems.length > 0) {
      sb.push('\n### 行动建议清单\n\n');
      for (const it of actionItems) {
        sb.push('- ', it, '\n');
      }
    }
  }
}

function splitActionItems(suggested: string): string[] {
  const out: string[] = [];
  const s = suggested.trim();
  if (!s) return out;
  const byNl = s.split(/[\n\r]+/);
  if (byNl.length > 1) {
    for (const line of byNl) {
      const t = line.trim().replace(/^\d+[.、．\s]+/, '');
      if (t) out.push(t);
    }
    if (out.length > 0) return out;
  }
  const bySemi = s.split(/[；;]/);
  if (bySemi.length > 1) {
    for (const p of bySemi) {
      const t = p.trim();
      if (t) out.push(t);
    }
  }
  if (out.length === 0) out.push(s);
  return out;
}

function appendLegacyQuestionsMarkdown(sb: string[], questions: unknown[]): void {
  sb.push('---\n\n', '## 本轮追问\n\n');
  let n = 0;
  for (const item of questions) {
    const q = textOrEmpty(item);
    if (!q) continue;
    n++;
    if (n > 1) sb.push('\n');
    appendBlockquotedParagraph(sb, q);
  }
  sb.push('\n---\n');
}

function looksLikeCalibrationPayload(root: Record<string, unknown>): boolean {
  if (Array.isArray(root.questions)) return true;
  if (root.calibration_mode != null) return true;
  if (typeof root.phase === 'string' && root.phase.trim() !== '') return true;
  return false;
}

/** 若内容为校准 JSON（可含 ```json 围栏），返回 Markdown；否则原样返回。 */
export function formatCalibrationJsonToMarkdown(raw: string): string {
  if (raw == null || !String(raw).trim()) return raw || '';
  const trimmed = stripMarkdownFence(String(raw).trim());
  const start = trimmed.indexOf('{');
  const end = trimmed.lastIndexOf('}');
  if (start < 0 || end <= start) return raw;
  const jsonStr = trimmed.slice(start, end + 1);
  let root: Record<string, unknown>;
  try {
    root = JSON.parse(jsonStr) as Record<string, unknown>;
  } catch {
    return raw;
  }
  if (!looksLikeCalibrationPayload(root)) return raw;

  const questions = Array.isArray(root.questions) ? root.questions : [];

  const sb: string[] = [];
  if (isDecisionShape(root)) {
    appendDecisionCalibrationMarkdown(sb, root, questions);
  } else if (questions.length > 0) {
    appendLegacyQuestionsMarkdown(sb, questions);
  } else {
    return raw;
  }

  const out = sb.join('').trim();
  return out === '' ? raw : out;
}
