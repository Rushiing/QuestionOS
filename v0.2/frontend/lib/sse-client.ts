import { apiPath } from './runtime-config';
import { buildSandboxHeaders, handleUnauthorized } from './http';

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

const DEFAULT_SSE_CONNECT_TIMEOUT_MS = 270_000; // 270s = 后端 240s LLM timeout + 30s 缓冲
/**
 * 空闲看门狗：后端每 20s 发一次 heartbeat，正常连接不可能 45s 毫无字节。
 * 超过即判定为 TCP 半开（边缘代理静默断链，服务端仍向死管道写入、浏览器侧 read() 永久挂起，
 * 2026-06-11 生产卡死的根因），主动断开并带 Last-Event-ID 重连，由后端 replay 补发漏掉的事件。
 */
const SSE_IDLE_TIMEOUT_MS = 30_000;
const SSE_MAX_RECONNECTS = 3;

class SseIdleTimeoutError extends Error {
  constructor() {
    super(`SSE 空闲超时（${Math.round(SSE_IDLE_TIMEOUT_MS / 1000)}s 未收到任何数据，含心跳）`);
    this.name = 'SseIdleTimeoutError';
  }
}

export const streamSse = async (options: StreamSseOptions): Promise<void> => {
  let cursor = options.lastEventId ?? 0;
  let reconnects = 0;

  // 单次连接：返回 true 表示 onEvent 主动要求停止（turn_done），整个 streamSse 正常结束
  const streamOnce = async (): Promise<boolean> => {
    const streamUrl = apiPath(options.path);
    const connectTimeoutMs = options.connectTimeoutMs ?? DEFAULT_SSE_CONNECT_TIMEOUT_MS;
    options.onDebug?.(`[sse] connect ${streamUrl} lastEventId=${cursor}`);

    const controller = new AbortController();
    const connectTimer = setTimeout(() => controller.abort(), connectTimeoutMs);
    let response: Response;
    try {
      response = await fetch(streamUrl, {
        method: 'GET',
        headers: {
          ...buildSandboxHeaders(),
          ...(cursor > 0 ? { 'Last-Event-ID': String(cursor) } : {}),
        },
        signal: controller.signal,
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      options.onDebug?.(`[sse] fetch error ${message}`);
      if ((e instanceof DOMException || e instanceof Error) && e.name === 'AbortError') {
        throw new Error(`SSE 连接超时（${Math.round(connectTimeoutMs / 1000)}s）`);
      }
      throw e;
    } finally {
      clearTimeout(connectTimer);
    }
    options.onDebug?.(`[sse] status ${response.status}`);
    if (response.status === 401) {
      handleUnauthorized();
      throw new Error('登录已过期，请重新登录');
    }
    if (!response.ok || !response.body) {
      options.onDebug?.('[sse] response invalid, abort');
      throw new Error(`stream failed: ${response.status}`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    // 空闲看门狗：每次 read 与定时器赛跑；任何字节（含心跳）都会重置
    const readWithIdleGuard = async (): Promise<ReadableStreamReadResult<Uint8Array>> => {
      let idleTimer: ReturnType<typeof setTimeout> | undefined;
      const idle = new Promise<never>((_, reject) => {
        idleTimer = setTimeout(() => reject(new SseIdleTimeoutError()), SSE_IDLE_TIMEOUT_MS);
      });
      try {
        return await Promise.race([reader.read(), idle]);
      } finally {
        clearTimeout(idleTimer);
      }
    };

    try {
      while (true) {
        const { value, done } = await readWithIdleGuard();
        if (done) {
          // 服务端结束了流但没收到停止信号：交给外层按断流重连处理
          return false;
        }
        buffer += decoder.decode(value, { stream: true });
        // Support both LF and CRLF SSE delimiters.
        const blocks = buffer.split(/\r?\n\r?\n/);
        buffer = blocks.pop() || '';

        for (const block of blocks) {
          const lines = block.split(/\r?\n/);
          let eventType = '';
          let eventId = '';
          const dataParts: string[] = [];

          for (const line of lines) {
            if (line.startsWith('event:')) eventType = line.slice(6).trim();
            else if (line.startsWith('id:')) eventId = line.slice(3).trim();
            else if (line.startsWith('data:')) {
              // WHATWG HTML: multiple `data:` lines are joined with U+000A (may be split by proxies).
              const rest = line.slice(5);
              dataParts.push(rest.startsWith(' ') ? rest.slice(1) : rest);
            }
          }

          const dataRaw = dataParts.join('\n').trim();

          if (!eventType || !dataRaw || eventType === 'heartbeat') continue;
          if (eventId && !eventId.startsWith('hb-')) {
            const seq = Number(eventId);
            if (!Number.isNaN(seq)) {
              cursor = Math.max(cursor, seq);
            }
          }
          // 收到真实业务事件说明链路健康，重置重连预算（长会话多次抖动也能撑过去）
          reconnects = 0;
          options.onDebug?.(`[sse] event ${eventType} #${eventId || '-'}`);
          const stop = options.onEvent({ eventType, eventId, dataRaw });
          if (stop === true) {
            options.onDebug?.('[sse] stop requested');
            return true;
          }
        }
      }
    } finally {
      options.onDebug?.('[sse] reader cancelled');
      try {
        await reader.cancel();
      } catch {
        // ignore cancel errors on dead connections
      }
    }
  };

  while (true) {
    let lastError: unknown;
    try {
      const stopped = await streamOnce();
      if (stopped) return;
      lastError = new Error('SSE 流被服务端提前结束');
    } catch (e) {
      lastError = e;
    }
    if (reconnects >= SSE_MAX_RECONNECTS) {
      throw lastError instanceof Error ? lastError : new Error(String(lastError));
    }
    reconnects++;
    const delayMs = 600 * reconnects;
    options.onDebug?.(`[sse] reconnect #${reconnects} in ${delayMs}ms from id=${cursor} (${lastError instanceof Error ? lastError.message : lastError})`);
    await new Promise((resolve) => setTimeout(resolve, delayMs));
  }
};
