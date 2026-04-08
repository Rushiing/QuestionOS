/**
 * 首页「背景资料」：随首轮用户消息一并发给后端（chat / consult），用后即清。
 * 仅存 sessionStorage，不上传服务器文件存储。
 */
const STORAGE_KEY = 'qos_background_context';
const MAX_CHARS = 28000;

export function setBackgroundContext(text: string): void {
  const t = text.trim();
  if (!t) {
    sessionStorage.removeItem(STORAGE_KEY);
    return;
  }
  sessionStorage.setItem(STORAGE_KEY, t.slice(0, MAX_CHARS));
}

export function readBackgroundContext(): string | null {
  const v = sessionStorage.getItem(STORAGE_KEY);
  return v && v.trim() ? v.trim() : null;
}

/** 读取并删除（仅应在确定拼进首轮 payload 时调用一次） */
export function takeBackgroundContext(): string | null {
  const v = readBackgroundContext();
  if (v) sessionStorage.removeItem(STORAGE_KEY);
  return v;
}

export function wrapUserMessageWithBackground(question: string, background: string): string {
  return (
    `### 附：背景资料（用户上传/粘贴）\n\n${background.trim()}\n\n---\n\n### 本轮问题\n\n${question.trim()}`
  );
}
