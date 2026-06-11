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
 * 业务事件看门狗（正确性以 replay 拉取为准，实时推送只当加速器）：
 * 生产观察（2026-06-11）：Railway 边缘上"已建立连接的实时推送"会间歇性失效，
 * 且心跳可能照常流动——所以心跳不能作为链路健康的依据，只有业务事件才算数。
 * 本流是"每轮一开"的短生命周期流，轮次进行中事件间隔通常只有几秒；
 * 12s 没有任何业务事件就主动断开、带 Last-Event-ID 重连，由后端 eventStore replay 补齐。
 * 误触发的代价只是一次多余请求（replay 幂等 + 水位线去重），零数据损失。
 */
const SSE_BUSINESS_IDLE_MS = 12_000;
const SSE_MAX_RECONNECTS = 10;

class SseIdleTimeoutError extends Error {
  constructor() {
    super(`SSE 业务事件空闲超时（${Math.round(SSE_BUSINESS_IDLE_MS / 1000)}s 无业务事件，主动重连拉取）`);
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
    // 业务空闲基准：连接建立视作起点；之后只有真实业务事件（非心跳）才会刷新
    let lastBusinessAt = Date.now();

    // 看门狗：每次 read 与「距离业务空闲截止的剩余时间」赛跑。
    // 心跳字节会让 read 返回（循环继续），但不刷新 lastBusinessAt——心跳不能证明推送层健康。
    const readWithIdleGuard = async (): Promise<ReadableStreamReadResult<Uint8Array>> => {
      const remaining = SSE_BUSINESS_IDLE_MS - (Date.now() - lastBusinessAt);
      if (remaining <= 0) {
        throw new SseIdleTimeoutError();
      }
      let idleTimer: ReturnType<typeof setTimeout> | undefined;
      const idle = new Promise<never>((_, reject) => {
        idleTimer = setTimeout(() => reject(new SseIdleTimeoutError()), remaining);
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
          // 收到真实业务事件：刷新业务空闲基准 + 重置重连预算（长会话多次抖动也能撑过去）
          lastBusinessAt = Date.now();
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
