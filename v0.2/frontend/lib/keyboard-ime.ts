import type { KeyboardEvent as ReactKeyboardEvent } from 'react';

/** 是否处于 IME 组字或浏览器「正在处理输入法」状态（避免 Enter 误触发送） */
export function isImeComposingOrProcessing(e: ReactKeyboardEvent): boolean {
  const ne = e.nativeEvent;
  if (ne.isComposing) return true;
  const keyCode = (ne as KeyboardEvent & { keyCode?: number }).keyCode;
  if (keyCode === 229) return true;
  return false;
}

/**
 * 多行输入：Enter 发送、Shift+Enter 换行；
 * 中文等 IME 用 Enter 确认候选字时不触发 submit。
 */
export function handleEnterToSubmit(e: ReactKeyboardEvent, submit: () => void): void {
  if (e.key !== 'Enter' || e.shiftKey) return;
  if (isImeComposingOrProcessing(e)) return;
  e.preventDefault();
  submit();
}

/** 对话输入框从单行起步自动增高，避免显示原生滚动条。 */
export function resizeComposer(el: HTMLTextAreaElement | null, maxHeight = 120): void {
  if (!el) return;
  el.style.height = '48px';
  el.style.height = `${Math.min(Math.max(el.scrollHeight, 48), maxHeight)}px`;
}
