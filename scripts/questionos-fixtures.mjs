#!/usr/bin/env node

import { existsSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';

const command = process.argv[2];
const baseUrl = normalizeBase(process.env.QOS_FIXTURE_URL || 'http://127.0.0.1:3000');
const token = (process.env.QOS_FIXTURE_TOKEN || 'sk-sandbox-dev').trim();
const manifestPath = process.env.QOS_FIXTURE_MANIFEST || '.qos-fixtures.json';
const fixtures = [
  {
    key: 'calibration-basic',
    mode: 'CALIBRATION',
    question: '[QOS_FIXTURE_V1] 思维校准：在稳定与成长之间做选择',
  },
  {
    key: 'sandbox-basic',
    mode: 'SANDBOX',
    question: '[QOS_FIXTURE_V1] 沙盘：评估一个可回滚的项目重构决策',
  },
];

if (!['seed', 'clean', 'status'].includes(command)) {
  fail('Usage: node scripts/questionos-fixtures.mjs seed|status|clean');
}
if (!token) fail('QOS_FIXTURE_TOKEN is required');

guardRemoteTarget();

if (command === 'seed') await seed();
if (command === 'status') await status();
if (command === 'clean') await clean();

async function seed() {
  const existing = readManifest();
  if (existing) {
    const states = await Promise.all(existing.sessions.map((item) => getSession(item.sessionId)));
    if (states.every(Boolean)) {
      print({ ok: true, action: 'seed', reused: true, manifest: manifestPath, sessions: existing.sessions });
      return;
    }
    fail(`Manifest ${manifestPath} contains missing sessions; run clean before seeding again`);
  }

  const sessions = [];
  try {
    for (const fixture of fixtures) {
      const created = await request('/api/v1/sandbox/sessions', {
        method: 'POST',
        body: { mode: fixture.mode, question: fixture.question },
      });
      if (!created.sessionId) fail(`Fixture ${fixture.key} did not return sessionId`);
      sessions.push({ ...fixture, sessionId: created.sessionId });
    }
  } catch (error) {
    await Promise.allSettled(sessions.map((item) => deleteSession(item.sessionId)));
    throw error;
  }

  const manifest = {
    schemaVersion: 1,
    target: baseUrl,
    createdAt: new Date().toISOString(),
    sessions,
  };
  writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, {
    encoding: 'utf8',
    mode: 0o600,
    flag: 'wx',
  });
  print({ ok: true, action: 'seed', reused: false, manifest: manifestPath, sessions });
}

async function status() {
  const manifest = requireManifest();
  const sessions = [];
  for (const item of manifest.sessions) {
    sessions.push({ ...item, exists: Boolean(await getSession(item.sessionId)) });
  }
  print({ ok: sessions.every((item) => item.exists), action: 'status', manifest: manifestPath, sessions });
}

async function clean() {
  const manifest = requireManifest();
  if (normalizeBase(manifest.target) !== baseUrl) {
    fail(`Manifest target ${manifest.target} does not match QOS_FIXTURE_URL ${baseUrl}`);
  }
  const deleted = [];
  for (const item of manifest.sessions) {
    const response = await deleteSession(item.sessionId);
    deleted.push({ key: item.key, sessionId: item.sessionId, status: response.status });
  }
  unlinkSync(manifestPath);
  print({ ok: true, action: 'clean', manifest: manifestPath, deleted });
}

async function getSession(sessionId) {
  const response = await requestRaw(`/api/v1/sandbox/sessions/${encodeURIComponent(sessionId)}`);
  if (response.status === 404) return null;
  if (!response.ok) fail(`GET ${sessionId} returned HTTP ${response.status}`);
  return response.json();
}

async function deleteSession(sessionId) {
  const response = await requestRaw(`/api/v1/sandbox/sessions/${encodeURIComponent(sessionId)}`, {
    method: 'DELETE',
  });
  if (![200, 404].includes(response.status)) {
    fail(`DELETE ${sessionId} returned HTTP ${response.status}`);
  }
  return response;
}

async function request(path, options = {}) {
  const response = await requestRaw(path, options);
  const text = await response.text();
  if (!response.ok) fail(`${options.method || 'GET'} ${path} returned HTTP ${response.status}: ${text.slice(0, 240)}`);
  try {
    return JSON.parse(text);
  } catch {
    fail(`${options.method || 'GET'} ${path} returned invalid JSON`);
  }
}

async function requestRaw(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 20_000);
  try {
    return await fetch(`${baseUrl}${path}`, {
      method: options.method || 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: options.body ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

function guardRemoteTarget() {
  const host = new URL(baseUrl).hostname;
  const local = host === 'localhost' || host === '127.0.0.1' || host === '::1';
  if (local) return;
  if (
    process.env.QOS_FIXTURE_ALLOW_REMOTE !== '1' ||
    process.env.QOS_FIXTURE_CONFIRM !== 'CREATE_AND_DELETE_TEST_SESSIONS'
  ) {
    fail(
      'Remote fixture writes are blocked. Set QOS_FIXTURE_ALLOW_REMOTE=1 and ' +
        'QOS_FIXTURE_CONFIRM=CREATE_AND_DELETE_TEST_SESSIONS only after explicit user approval.'
    );
  }
}

function readManifest() {
  if (!existsSync(manifestPath)) return null;
  try {
    return JSON.parse(readFileSync(manifestPath, 'utf8'));
  } catch {
    fail(`Cannot parse ${manifestPath}`);
  }
}

function requireManifest() {
  const manifest = readManifest();
  if (!manifest) fail(`Fixture manifest not found: ${manifestPath}`);
  return manifest;
}

function normalizeBase(value) {
  return String(value).trim().replace(/\/+$/, '');
}

function print(value) {
  process.stdout.write(`${JSON.stringify(value, null, 2)}\n`);
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
