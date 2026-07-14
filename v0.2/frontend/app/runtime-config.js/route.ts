export const dynamic = 'force-dynamic';

export function GET() {
  // 先取环境对象再读取，避免 Next 在 build 时把 NEXT_PUBLIC_* 内联成定值。
  const runtimeEnv = process.env;
  const apiBase = (
    runtimeEnv.QUESTIONOS_BROWSER_API_URL ||
    runtimeEnv.NEXT_PUBLIC_API_URL ||
    ''
  ).trim().replace(/\/$/, '');
  const googleClientId = (
    runtimeEnv.INTERNAL_GOOGLE_CLIENT_ID ||
    runtimeEnv.NEXT_PUBLIC_GOOGLE_CLIENT_ID ||
    ''
  ).trim();
  const script = [
    `window.__QOS_API_BASE__=${JSON.stringify(apiBase)};`,
    `window.__QOS_GOOGLE_CLIENT_ID__=${JSON.stringify(googleClientId)};`,
  ].join('\n');

  return new Response(script, {
    headers: {
      'Content-Type': 'application/javascript; charset=utf-8',
      'Cache-Control': 'no-store, max-age=0',
    },
  });
}
