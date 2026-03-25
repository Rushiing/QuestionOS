/**
 * 模型常把「博弈复盘」三位专家挤在同一段；渲染前拆成 Markdown 列表，保证每条独占一行。
 */
const EXPERT_OPEN = /\*\*(?:利益审计师|风险预测官|价值裁判)\*\*[：:]/;

function findIntegratorExpertBlockEnd(fromFirstExpert: string): number {
  const hits = [
    fromFirstExpert.search(/\n###\s*📊/),
    fromFirstExpert.search(/\n##\s*📊/),
    fromFirstExpert.search(/\n###\s*❓/),
    fromFirstExpert.search(/\n##\s*❓/),
    fromFirstExpert.search(/\n###\s*[^\n]*决策沙盘/),
    fromFirstExpert.search(/\n---\s*\n\s*###\s*📊/),
    fromFirstExpert.search(/\n---\s*\n\s*\|/),
    fromFirstExpert.search(/\n\|[^\n]*维度[^\n]*\|/),
  ].filter((i) => i >= 0);
  return hits.length ? Math.min(...hits) : fromFirstExpert.length;
}

export function normalizeIntegratorExpertBullets(md: string): string {
  if (!md || !md.includes('利益审计师')) return md;

  const firstIdx = md.search(EXPERT_OPEN);
  if (firstIdx < 0) return md;

  const fromFirst = md.slice(firstIdx);
  const endRel = findIntegratorExpertBlockEnd(fromFirst);
  let expertRegion = fromFirst.slice(0, endRel).trimEnd();
  const suffix = fromFirst.slice(endRel);

  const pieces = expertRegion
    .split(/(?=\*\*(?:利益审计师|风险预测官|价值裁判)\*\*[：:])/g)
    .map((x) => x.trim())
    .filter(Boolean)
    .map((x) => x.replace(/^[-*]\s+/, '').trim())
    .filter((x) => EXPERT_OPEN.test(x));

  if (pieces.length < 2) return md;

  const listBody = pieces.map((p) => `- ${p}`).join('\n\n');
  return md.slice(0, firstIdx) + listBody + suffix;
}
