export const CHAT_INTERNAL_NAV_KEY = 'qos_chat_internal_nav';

export function markInternalChatNav(): void {
  if (typeof window === 'undefined') return;
  sessionStorage.setItem(CHAT_INTERNAL_NAV_KEY, '1');
}

export function consumeInternalChatNavMark(): boolean {
  if (typeof window === 'undefined') return false;
  const flag = sessionStorage.getItem(CHAT_INTERNAL_NAV_KEY) === '1';
  if (flag) {
    sessionStorage.removeItem(CHAT_INTERNAL_NAV_KEY);
  }
  return flag;
}
