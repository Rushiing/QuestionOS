import { apiPath } from './runtime-config';
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
  /** 仅限制「建立连接 / 收到响应头」耗时，不限制后续 SSE 流式读取 */
  connectTimeoutMs?: number;
}

const DEFAULT_SSE_CONNECT_TIMEOUT_MS = 60_000;

export const streamSse = async (options: StreamSseOptions): Promise<void> => {
  const streamUrl = apiPath(options.path);
  const connectTimeoutMs = options.connectTimeoutMs ?? DEFAULT_SSE_CONNECT_TIMEOUT_MS;
  options.onDebug?.(`[sse] connect ${streamUrl} lastEventId=${options.lastEventId ?? 0}`);
  let response: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), connectTimeoutMs);
  try {
    response = await fetch(streamUrl, {
      method: 'GET',
      headers: {
        ...buildSandboxHeaders(),
        ...(options.lastEventId && options.lastEventId > 0
          ? { 'Last-Event-ID': String(options.lastEventId) }
          : {}),
      },
      signal: controller.signal,
    });
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    options.onDebug?.(`[sse] fetch error ${message}`);
    if (e instanceof DOMException && e.name === 'AbortError') {
      throw new Error(`SSE 连接超时（${Math.round(connectTimeoutMs / 1000)}s）`);
    }
    if (e instanceof Error && e.name === 'AbortError') {
      throw new Error(`SSE 连接超时（${Math.round(connectTimeoutMs / 1000)}s）`);
    }
    throw e;
  } finally {
    clearTimeout(timer);
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
