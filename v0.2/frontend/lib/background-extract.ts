import { apiPath } from './runtime-config';

export type BackgroundExtractResult = { text: string; truncated: boolean };

/** Word 需登录后由 Java 抽取纯文本（不用 sandbox 兜底 token，避免未登录误过鉴权） */
export async function extractBackgroundDocument(file: File): Promise<BackgroundExtractResult> {
  const token = typeof window !== 'undefined' ? localStorage.getItem('token') : null;
  if (!token) {
    throw new Error('请先登录后再上传背景文件');
  }
  const form = new FormData();
  form.append('file', file);
  const res = await fetch(apiPath('/api/v1/background/extract'), {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  });
  const data = (await res.json().catch(() => null)) as
    | { text?: string; truncated?: boolean; message?: string }
    | null;
  if (!res.ok) {
    const msg =
      (data && typeof data.message === 'string' && data.message) ||
      (res.status === 401 ? '请先登录后再上传 Word 文档' : `解析失败（${res.status}）`);
    throw new Error(msg);
  }
  const text = typeof data?.text === 'string' ? data.text : '';
  return { text, truncated: !!data?.truncated };
}
