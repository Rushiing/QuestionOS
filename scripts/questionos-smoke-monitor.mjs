#!/usr/bin/env node

import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const smokeScript = path.join(__dirname, 'questionos-smoke-check.mjs');

const cfg = {
  port: numberEnv('PORT', numberEnv('QOS_MONITOR_PORT', 3000)),
  host: process.env.QOS_MONITOR_HOST || '0.0.0.0',
  intervalMs: numberEnv('QOS_MONITOR_INTERVAL_MS', 60_000),
  staleAfterMs: numberEnv('QOS_MONITOR_STALE_AFTER_MS', 180_000),
  failureThreshold: numberEnv('QOS_MONITOR_FAILURE_THRESHOLD', 1),
  historySize: numberEnv('QOS_MONITOR_HISTORY_SIZE', 20),
  webhookUrl: (process.env.QOS_MONITOR_WEBHOOK_URL || '').trim(),
};

let last = null;
let history = [];
let running = false;
let consecutiveFailures = 0;
let lastState = 'unknown';

runOnce('startup').catch((error) => {
  console.error('[qos-monitor] startup run failed', error);
});

setInterval(() => {
  runOnce('interval').catch((error) => {
    console.error('[qos-monitor] interval run failed', error);
  });
}, cfg.intervalMs);

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  if (url.pathname === '/health') {
    const healthy = isHealthy();
    return json(res, healthy ? 200 : 503, {
      ok: healthy,
      running,
      consecutiveFailures,
      stale: isStale(),
      last: publicLast(),
    });
  }
  if (url.pathname === '/ready') {
    return json(res, last ? 200 : 503, { ready: Boolean(last), running });
  }
  if (url.pathname === '/last') {
    return json(res, 200, { last: publicLast(), history: publicHistory() });
  }
  if (url.pathname === '/metrics') {
    const ok = isHealthy() ? 1 : 0;
    const lastOk = last?.ok ? 1 : 0;
    const lastElapsed = Number(last?.elapsedMs || 0);
    const body = [
      '# HELP questionos_monitor_healthy 1 when monitor is healthy.',
      '# TYPE questionos_monitor_healthy gauge',
      `questionos_monitor_healthy ${ok}`,
      '# HELP questionos_smoke_last_ok 1 when last smoke run passed.',
      '# TYPE questionos_smoke_last_ok gauge',
      `questionos_smoke_last_ok ${lastOk}`,
      '# HELP questionos_smoke_consecutive_failures Consecutive failed smoke runs.',
      '# TYPE questionos_smoke_consecutive_failures gauge',
      `questionos_smoke_consecutive_failures ${consecutiveFailures}`,
      '# HELP questionos_smoke_last_elapsed_ms Last smoke run duration.',
      '# TYPE questionos_smoke_last_elapsed_ms gauge',
      `questionos_smoke_last_elapsed_ms ${lastElapsed}`,
      '',
    ].join('\n');
    res.writeHead(200, { 'Content-Type': 'text/plain; version=0.0.4' });
    return res.end(body);
  }
  return json(res, 404, { error: 'not_found' });
});

server.listen(cfg.port, cfg.host, () => {
  console.log(`[qos-monitor] listening on ${cfg.host}:${cfg.port}; intervalMs=${cfg.intervalMs}`);
});

async function runOnce(reason) {
  if (running) {
    console.log(`[qos-monitor] skip ${reason}: previous run still active`);
    return;
  }
  running = true;
  const startedAt = new Date();
  try {
    const result = await runSmokeScript();
    const record = {
      ...result,
      checkedAt: startedAt.toISOString(),
      completedAt: new Date().toISOString(),
      reason,
    };
    last = record;
    history.unshift(record);
    history = history.slice(0, cfg.historySize);
    consecutiveFailures = record.ok ? 0 : consecutiveFailures + 1;
    const failedChecks = (record.results || []).filter((result) => !result.ok);
    console.log(`[qos-monitor] smoke ${record.ok ? 'ok' : 'failed'} elapsedMs=${record.elapsedMs}`);
    for (const failed of failedChecks) {
      console.log(`[qos-monitor] failed ${failed.name}: ${String(failed.detail || '').slice(0, 240)}`);
    }
    await maybeNotify(record);
  } finally {
    running = false;
  }
}

function runSmokeScript() {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [smokeScript], {
      env: process.env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      stdout += chunk;
    });
    child.stderr.on('data', (chunk) => {
      stderr += chunk;
    });
    child.on('close', (code) => {
      const parsed = parseLastJson(stdout);
      if (parsed) {
        resolve(parsed);
        return;
      }
      resolve({
        ok: false,
        elapsedMs: 0,
        results: [
          {
            name: 'smoke-script',
            ok: false,
            detail: `exit=${code}; ${stderr || stdout || 'no output'}`.slice(0, 500),
          },
        ],
      });
    });
  });
}

async function maybeNotify(record) {
  const state = record.ok ? 'ok' : 'failed';
  const crossedThreshold =
    !record.ok && consecutiveFailures === cfg.failureThreshold;
  const recovered = record.ok && lastState === 'failed';
  lastState = state;
  if (!cfg.webhookUrl || (!crossedThreshold && !recovered)) {
    return;
  }
  const payload = {
    service: 'questionos-smoke-monitor',
    state,
    consecutiveFailures,
    checkedAt: record.checkedAt,
    elapsedMs: record.elapsedMs,
    failedChecks: (record.results || [])
      .filter((r) => !r.ok)
      .map((r) => ({ name: r.name, detail: r.detail })),
  };
  try {
    await fetch(cfg.webhookUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
  } catch (error) {
    console.error('[qos-monitor] webhook failed', error);
  }
}

function isHealthy() {
  if (!last) return false;
  if (isStale()) return false;
  if (consecutiveFailures >= cfg.failureThreshold) return false;
  return true;
}

function isStale() {
  if (!last?.completedAt) return true;
  return Date.now() - Date.parse(last.completedAt) > cfg.staleAfterMs;
}

function publicLast() {
  if (!last) return null;
  return sanitizeRecord(last);
}

function publicHistory() {
  return history.map(sanitizeRecord);
}

function sanitizeRecord(record) {
  return {
    ok: record.ok,
    elapsedMs: record.elapsedMs,
    checkedAt: record.checkedAt,
    completedAt: record.completedAt,
    reason: record.reason,
    results: record.results,
  };
}

function parseLastJson(output) {
  const text = String(output || '').trim();
  if (!text) return null;
  const start = text.lastIndexOf('\n{');
  const jsonText = start >= 0 ? text.slice(start + 1) : text;
  try {
    return JSON.parse(jsonText);
  } catch {
    return null;
  }
}

function json(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(value, null, 2));
}

function numberEnv(name, fallback) {
  const parsed = Number(process.env[name]);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}
