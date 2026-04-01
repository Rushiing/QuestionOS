import type { NextRequest } from 'next/server';

export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';

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

  const init: RequestInit & { duplex?: 'half' } = {
    method: request.method,
    headers,
    redirect: 'manual',
  };

  if (!['GET', 'HEAD'].includes(request.method) && request.body) {
    init.body = request.body;
    init.duplex = 'half';
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
    console.error('[api-proxy] fetch failed', { target, originConfigured: origin, message: msg });
    return Response.json(
      {
        error: 'UPSTREAM_UNREACHABLE',
        message: msg,
        hint: 'Check frontend INTERNAL_API_URL / NEXT_PUBLIC_API_URL points to a URL reachable from the Next.js container (e.g. backend public https URL).',
      },
      { status: 502 }
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
