import type { NextRequest } from 'next/server';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

/** POST 等短请求：避免 stream body + duplex 在部分 Node 环境挂死；并防止 fetch 无限等待导致 Railway「Application failed to respond」 */
const MUTATING_FETCH_TIMEOUT_MS = 30_000;

function backendOrigin(): string {
  const u =
    process.env.INTERNAL_API_URL ||
    process.env.NEXT_PUBLIC_API_URL ||
    'http://127.0.0.1:8080';
  return u.replace(/\/$/, '');
}

async function proxy(request: NextRequest, pathSegments: string[] | undefined) {
  const sub = pathSegments?.length ? pathSegments.join('/') : '';
  const apiPath = sub ? `/api/${sub}` : '/api';
  const src = new URL(request.url);
  const origin = backendOrigin();
  const target = `${origin}${apiPath}${src.search}`;

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    const k = key.toLowerCase();
    if (k === 'host' || k === 'connection') return;
    headers.set(key, value);
  });

  const isMutating = !['GET', 'HEAD'].includes(request.method);

  const init: RequestInit = {
    method: request.method,
    headers,
    redirect: 'manual',
  };

  if (isMutating) {
    const bodyBuf = await request.arrayBuffer();
    if (bodyBuf.byteLength > 0) {
      init.body = bodyBuf;
    }
    headers.delete('content-length');
    headers.delete('transfer-encoding');
    init.signal = AbortSignal.timeout(MUTATING_FETCH_TIMEOUT_MS);
  }

  try {
    const res = await fetch(target, init);
    const outHeaders = new Headers(res.headers);
    return new Response(res.body, {
      status: res.status,
      statusText: res.statusText,
      headers: outHeaders,
    });
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    const timedOut = msg.includes('abort') || msg.includes('AbortError') || msg.includes('TimeoutError');
    console.error('[api-proxy] fetch failed', { target, originConfigured: origin, message: msg, timedOut });
    return Response.json(
      {
        error: timedOut ? 'UPSTREAM_TIMEOUT' : 'UPSTREAM_UNREACHABLE',
        message: msg,
        hint: timedOut
          ? `Backend did not respond within ${MUTATING_FETCH_TIMEOUT_MS / 1000}s. Check INTERNAL_API_URL / NEXT_PUBLIC_API_URL and that Java is up.`
          : 'Check INTERNAL_API_URL / NEXT_PUBLIC_API_URL points to a URL reachable from the Next.js container (e.g. backend public https URL).',
      },
      { status: timedOut ? 504 : 502 }
    );
  }
}

type Ctx = { params: { path?: string[] } };

export async function GET(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function HEAD(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function POST(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function PUT(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function PATCH(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function DELETE(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}

export async function OPTIONS(request: NextRequest, ctx: Ctx) {
  return proxy(request, ctx.params.path);
}
