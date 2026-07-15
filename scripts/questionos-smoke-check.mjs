#!/usr/bin/env node

const startedAt = Date.now();

const cfg = {
  frontendUrl: normalizeBase(process.env.QOS_FRONTEND_URL || process.env.FRONTEND_URL || ''),
  backendUrl: normalizeBase(process.env.QOS_BACKEND_URL || process.env.BACKEND_URL || ''),
  token: (process.env.QOS_SMOKE_TOKEN || '').trim(),
  allowMissingGoogle:
    boolEnv('QOS_SMOKE_ALLOW_MISSING_GOOGLE') ||
    boolEnv('ALLOW_MISSING_GOOGLE'),
  createSession: boolEnv('QOS_SMOKE_CREATE_SESSION'),
  runLlmTurn: boolEnv('QOS_SMOKE_RUN_LLM_TURN'),
  timeoutMs: numberEnv('QOS_SMOKE_TIMEOUT_MS', 15_000),
  sseTimeoutMs: numberEnv('QOS_SMOKE_SSE_TIMEOUT_MS', 12_000),
  question:
    process.env.QOS_SMOKE_QUESTION ||
    '巡检：我在考虑是否重构一个老项目，担心影响线上稳定性。',
};

if (!cfg.frontendUrl) {
  failFast('Missing QOS_FRONTEND_URL. Example: QOS_FRONTEND_URL=https://questionos-app.up.railway.app');
}

const results = [];

await check('frontend.home', async () => {
  const res = await fetchText(`${cfg.frontendUrl}/`);
  assertStatus(res, 200, 399);
  if (!res.body.includes('QuestionOS')) {
    throw new Error('home page does not contain QuestionOS');
  }
  return `HTTP ${res.status}`;
});

await check('frontend.login-runtime', async () => {
  const login = await fetchText(`${cfg.frontendUrl}/login`);
  assertStatus(login, 200, 399);
  const runtime = await fetchText(`${cfg.frontendUrl}/runtime-config.js`);
  assertStatus(runtime, 200, 399);
  const googleClientId = extractRuntimeString(runtime.body, '__QOS_GOOGLE_CLIENT_ID__');
  if (!googleClientId && !cfg.allowMissingGoogle) {
    throw new Error('Google Client ID is empty in login runtime script');
  }
  return googleClientId ? 'Google Client ID present' : 'Google Client ID missing but allowed';
});

await check('frontend.api-proxy-auth', async () => {
  const res = await fetchText(`${cfg.frontendUrl}/api/auth/me`);
  if (res.status !== 401) {
    throw new Error(`expected 401 for unauthenticated /api/auth/me, got ${res.status}: ${snippet(res.body)}`);
  }
  return 'HTTP 401 as expected';
});

if (cfg.backendUrl) {
  await check('backend.health', async () => {
    const res = await fetchText(`${cfg.backendUrl}/actuator/health`);
    assertStatus(res, 200, 399);
    const json = parseJson(res.body);
    if (json && json.status && json.status !== 'UP') {
      throw new Error(`health status is ${json.status}`);
    }
    return json?.status ? `status=${json.status}` : `HTTP ${res.status}`;
  });

  await check('backend.prometheus', async () => {
    const res = await fetchText(`${cfg.backendUrl}/actuator/prometheus`);
    assertStatus(res, 200, 399);
    if (!res.body.includes('jvm_') && !res.body.includes('process_')) {
      throw new Error('prometheus response does not look like metrics text');
    }
    return `HTTP ${res.status}`;
  });
}

if (cfg.token && cfg.createSession) {
  await check('sandbox.create-session', async () => {
    const session = await createSession();
    globalThis.__qosSmokeSessionId = session.sessionId;
    return `sessionId=${session.sessionId}`;
  });

  if (globalThis.__qosSmokeSessionId) {
    await check('sandbox.sse-replay', async () => {
      const event = await waitForSseEvent(
        `${cfg.frontendUrl}/api/v1/sandbox/sessions/${globalThis.__qosSmokeSessionId}/stream`,
        new Set(['session_created'])
      );
      return `${event.eventType}#${event.eventId || '-'}`;
    });
  } else {
    results.push({
      name: 'sandbox.sse-replay',
      ok: true,
      detail: 'skipped: session creation failed',
      skipped: true,
    });
  }

  if (cfg.runLlmTurn && globalThis.__qosSmokeSessionId) {
    await check('sandbox.llm-turn', async () => {
      const sessionId = globalThis.__qosSmokeSessionId;
      const streamPromise = waitForSseEvent(
        `${cfg.frontendUrl}/api/v1/sandbox/sessions/${sessionId}/stream`,
        new Set(['sandbox_classify', 'turn_done', 'agent_error'])
      );
      await postJson(
        `${cfg.frontendUrl}/api/v1/sandbox/sessions/${sessionId}/messages`,
        { content: cfg.question },
        {
          Authorization: `Bearer ${cfg.token}`,
          'Idempotency-Key': `smoke-${Date.now()}`,
        },
        Math.max(cfg.timeoutMs, 45_000)
      );
      const event = await streamPromise;
      if (event.eventType === 'agent_error') {
        throw new Error(`agent_error: ${snippet(event.dataRaw)}`);
      }
      return `${event.eventType}#${event.eventId || '-'}`;
    });
  } else if (cfg.runLlmTurn) {
    results.push({
      name: 'sandbox.llm-turn',
      ok: true,
      detail: 'skipped: session creation failed',
      skipped: true,
    });
  }
} else {
  results.push({
    name: 'sandbox.synthetic',
    ok: true,
    detail: cfg.token
      ? 'skipped: set QOS_SMOKE_CREATE_SESSION=1 to create a session and verify SSE'
      : 'skipped: set QOS_SMOKE_TOKEN and QOS_SMOKE_CREATE_SESSION=1 to create a session and verify SSE',
    skipped: true,
  });
}

const failed = results.filter((r) => !r.ok);
const elapsedMs = Date.now() - startedAt;
console.log(JSON.stringify({ ok: failed.length === 0, elapsedMs, results }, null, 2));
process.exit(failed.length === 0 ? 0 : 1);

async function check(name, fn) {
  const t0 = Date.now();
  try {
    const detail = await fn();
    results.push({ name, ok: true, ms: Date.now() - t0, detail });
  } catch (error) {
    results.push({
      name,
      ok: false,
      ms: Date.now() - t0,
      detail: error instanceof Error ? error.message : String(error),
    });
  }
}

async function createSession() {
  const res = await postJson(
    `${cfg.frontendUrl}/api/v1/sandbox/sessions`,
    { mode: 'SANDBOX', question: 'QuestionOS smoke check' },
    { Authorization: `Bearer ${cfg.token}` }
  );
  if (!res.sessionId) {
    throw new Error(`missing sessionId in response: ${JSON.stringify(res)}`);
  }
  return res;
}

async function postJson(url, body, headers = {}, timeoutMs = cfg.timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...headers,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const text = await res.text();
    if (res.status < 200 || res.status > 399) {
      throw new Error(`HTTP ${res.status}: ${snippet(text)}`);
    }
    return parseJson(text) || {};
  } finally {
    clearTimeout(timer);
  }
}

async function fetchText(url, timeoutMs = cfg.timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(url, { signal: controller.signal });
    const body = await res.text();
    return { status: res.status, body };
  } finally {
    clearTimeout(timer);
  }
}

async function waitForSseEvent(url, wantedTypes) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), cfg.sseTimeoutMs);
  try {
    const res = await fetch(url, {
      headers: { Authorization: `Bearer ${cfg.token}` },
      signal: controller.signal,
    });
    if (!res.ok || !res.body) {
      throw new Error(`SSE HTTP ${res.status}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        throw new Error('SSE ended before wanted event');
      }
      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        const event = parseSseBlock(block);
        if (event.eventType && wantedTypes.has(event.eventType)) {
          await reader.cancel().catch(() => {});
          return event;
        }
      }
    }
  } finally {
    clearTimeout(timer);
  }
}

function parseSseBlock(block) {
  const event = { eventType: '', eventId: '', dataRaw: '' };
  const data = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event.eventType = line.slice(6).trim();
    else if (line.startsWith('id:')) event.eventId = line.slice(3).trim();
    else if (line.startsWith('data:')) {
      const rest = line.slice(5);
      data.push(rest.startsWith(' ') ? rest.slice(1) : rest);
    }
  }
  event.dataRaw = data.join('\n').trim();
  return event;
}

function extractRuntimeString(html, name) {
  const pattern = new RegExp(`window\\.${escapeRegExp(name)}=([^;]+);`);
  const match = html.match(pattern);
  if (!match) return '';
  try {
    const parsed = JSON.parse(match[1]);
    return typeof parsed === 'string' ? parsed.trim() : '';
  } catch {
    return '';
  }
}

function assertStatus(res, min, max) {
  if (res.status < min || res.status > max) {
    throw new Error(`HTTP ${res.status}: ${snippet(res.body)}`);
  }
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function snippet(text) {
  return String(text || '').replace(/\s+/g, ' ').trim().slice(0, 240);
}

function normalizeBase(value) {
  return String(value || '').trim().replace(/\/+$/, '');
}

function boolEnv(name) {
  const value = String(process.env[name] || '').trim().toLowerCase();
  return value === '1' || value === 'true' || value === 'yes';
}

function numberEnv(name, fallback) {
  const parsed = Number(process.env[name]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function failFast(message) {
  console.error(message);
  process.exit(2);
}
