import { API_BASE_URL, apiPath } from './runtime-config';
import { buildSandboxHeaders } from './http';

export interface SseEvent {
  eventType: string;
  eventId: string;
  dataRaw: string;
}

export interface StreamSseOptions {
  path: string;
  lastEventId?: number;
  onEvent: (event: SseEvent) => boolean | void;
  onDebug?: (line: string) => void;
}

export const streamSse = async (options: StreamSseOptions): Promise<void> => {
  // 浏览器走同源 /api → Next 运行时代理；避免直连 NEXT_PUBLIC_API_URL（生产 CORS / 与 fetchJson 行为一致）
  const streamUrl =
    typeof window !== 'undefined' && options.path.startsWith('/api/')
      ? options.path
      : options.path.startsWith('/api/')
        ? `${API_BASE_URL}${options.path}`
        : apiPath(options.path);
  options.onDebug?.(`[sse] connect ${streamUrl} lastEventId=${options.lastEventId ?? 0}`);
  let response: Response;
  try {
    response = await fetch(streamUrl, {
      method: 'GET',
      headers: {
        ...buildSandboxHeaders(),
        ...(options.lastEventId && options.lastEventId > 0
          ? { 'Last-Event-ID': String(options.lastEventId) }
          : {}),
      },
    });
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    options.onDebug?.(`[sse] fetch error ${message}`);
    throw e;
  }
  options.onDebug?.(`[sse] status ${response.status}`);
  if (!response.ok || !response.body) {
    options.onDebug?.('[sse] response invalid, abort');
    throw new Error(`stream failed: ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let shouldStop = false;

  try {
    while (!shouldStop) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      // Support both LF and CRLF SSE delimiters.
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';

      for (const block of blocks) {
        const lines = block.split(/\r?\n/);
        let eventType = '';
        let eventId = '';
        let dataRaw = '';

        for (const line of lines) {
          if (line.startsWith('event:')) eventType = line.slice(6).trim();
          if (line.startsWith('id:')) eventId = line.slice(3).trim();
          if (line.startsWith('data:')) dataRaw = line.slice(5).trim();
        }

        if (!eventType || !dataRaw || eventType === 'heartbeat') continue;
        options.onDebug?.(`[sse] event ${eventType} #${eventId || '-'}`);
        const stop = options.onEvent({ eventType, eventId, dataRaw });
        if (stop === true) {
          options.onDebug?.('[sse] stop requested');
          shouldStop = true;
          break;
        }
      }
    }
  } finally {
    options.onDebug?.('[sse] reader cancelled');
    reader.cancel();
  }
};
