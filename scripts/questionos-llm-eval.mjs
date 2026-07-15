#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

const suitePath = process.env.QOS_EVAL_SUITE || 'evals/questionos-core.json';
const suite = JSON.parse(readFileSync(suitePath, 'utf8'));
const baseUrl = normalizeBase(process.env.QOS_EVAL_URL || 'http://127.0.0.1:3000');
const token = (process.env.QOS_EVAL_TOKEN || '').trim();
const model = (process.env.QOS_EVAL_MODEL || '').trim();
const maxCases = positiveInt(process.env.QOS_EVAL_MAX_CASES, 2);
const timeoutMs = positiveInt(process.env.QOS_EVAL_TIMEOUT_MS, 420_000);
const commit = (process.env.QOS_EVAL_COMMIT || gitCommit()).trim();

if (!token) fail('QOS_EVAL_TOKEN is required');
if (!model) fail('QOS_EVAL_MODEL is required so evidence records the evaluated model');
if (maxCases > 4) fail('QOS_EVAL_MAX_CASES may not exceed 4');
guardRemoteTarget();

const selected = suite.cases.slice(0, maxCases);
if (selected.length === 0) fail('Eval suite contains no selected cases');

const results = [];
for (const testCase of selected) {
  const startedAt = Date.now();
  let sessionId = '';
  try {
    const created = await requestJson('/api/v1/sandbox/sessions', {
      method: 'POST',
      body: { mode: testCase.mode, question: `[QOS_EVAL:${testCase.id}] ${testCase.input}` },
    });
    sessionId = created.sessionId;
    if (!sessionId) throw new Error('create session did not return sessionId');

    const eventPromise = waitForAgentResult(sessionId);
    await requestJson(`/api/v1/sandbox/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: { 'Idempotency-Key': `eval-${testCase.id}-${Date.now()}` },
      body: { content: testCase.input },
      timeoutMs,
    });
    const event = await eventPromise;
    const output = event.content;
    for (const fragment of testCase.requiredFragments || []) {
      if (!output.includes(fragment)) throw new Error(`missing required fragment: ${fragment}`);
    }
    for (const fragment of testCase.forbiddenFragments || []) {
      if (output.includes(fragment)) throw new Error(`contains forbidden fragment: ${fragment}`);
    }
    const questionSections = (output.match(/## 本轮追问/g) || []).length;
    if (questionSections !== 1) throw new Error(`expected one question section, got ${questionSections}`);
    results.push({ id: testCase.id, ok: true, elapsedMs: Date.now() - startedAt, eventId: event.eventId });
  } catch (error) {
    results.push({
      id: testCase.id,
      ok: false,
      elapsedMs: Date.now() - startedAt,
      error: error instanceof Error ? error.message : String(error),
    });
  } finally {
    if (sessionId) {
      await deleteSession(sessionId).catch((error) => {
        const result = results.at(-1);
        result.ok = false;
        result.cleanupError = error instanceof Error ? error.message : String(error);
      });
    }
  }
}

const evidence = {
  schemaVersion: 1,
  suite: suite.suite,
  suitePath,
  promptVersion: suite.promptVersion,
  model,
  commit,
  target: baseUrl,
  checkedAt: new Date().toISOString(),
  budget: { maxCases, selectedCases: selected.length, maxOutputControlledByApplication: true },
  ok: results.every((result) => result.ok),
  results,
};
process.stdout.write(`${JSON.stringify(evidence, null, 2)}\n`);
process.exitCode = evidence.ok ? 0 : 1;

async function waitForAgentResult(sessionId) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(`${baseUrl}/api/v1/sandbox/sessions/${sessionId}/stream`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: controller.signal,
    });
    if (!response.ok || !response.body) throw new Error(`SSE returned HTTP ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let finalContent = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) throw new Error('SSE ended before turn_done');
      buffer += decoder.decode(value, { stream: true });
      const blocks = buffer.split(/\r?\n\r?\n/);
      buffer = blocks.pop() || '';
      for (const block of blocks) {
        const event = parseSseBlock(block);
        if (event.eventType === 'agent_error') throw new Error(`agent_error: ${event.dataRaw.slice(0, 240)}`);
        if (event.eventType === 'agent_chunk') finalContent = extractEventContent(event.dataRaw) || finalContent;
        if (event.eventType === 'turn_done') {
          await reader.cancel().catch(() => {});
          if (!finalContent) throw new Error('turn_done arrived without agent_chunk content');
          return { content: finalContent, eventId: event.eventId };
        }
      }
    }
  } finally {
    clearTimeout(timer);
  }
}

function extractEventContent(dataRaw) {
  try {
    const parsed = JSON.parse(dataRaw);
    if (typeof parsed.content === 'string') return parsed.content;
    return typeof parsed.payload?.content === 'string' ? parsed.payload.content : '';
  } catch {
    return dataRaw;
  }
}

function parseSseBlock(block) {
  const event = { eventType: '', eventId: '', dataRaw: '' };
  const data = [];
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event.eventType = line.slice(6).trim();
    else if (line.startsWith('id:')) event.eventId = line.slice(3).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  event.dataRaw = data.join('\n').trim();
  return event;
}

async function deleteSession(sessionId) {
  const response = await requestRaw(`/api/v1/sandbox/sessions/${sessionId}`, { method: 'DELETE' });
  if (![200, 404].includes(response.status)) throw new Error(`cleanup returned HTTP ${response.status}`);
}

async function requestJson(path, options = {}) {
  const response = await requestRaw(path, options);
  const text = await response.text();
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${path} returned HTTP ${response.status}: ${text.slice(0, 240)}`);
  return JSON.parse(text);
}

async function requestRaw(path, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), options.timeoutMs || 30_000);
  try {
    return await fetch(`${baseUrl}${path}`, {
      method: options.method || 'GET',
      headers: {
        Authorization: `Bearer ${token}`,
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
        ...(options.headers || {}),
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
    process.env.QOS_EVAL_ALLOW_REMOTE !== '1' ||
    process.env.QOS_EVAL_CONFIRM !== 'RUN_REAL_LLM_EVAL_AND_DELETE_TEST_SESSIONS'
  ) {
    fail(
      'Remote real-LLM eval is blocked. Set QOS_EVAL_ALLOW_REMOTE=1 and ' +
        'QOS_EVAL_CONFIRM=RUN_REAL_LLM_EVAL_AND_DELETE_TEST_SESSIONS only after explicit user approval.'
    );
  }
}

function gitCommit() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim();
  } catch {
    return 'unknown';
  }
}

function positiveInt(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}

function normalizeBase(value) {
  return String(value).trim().replace(/\/+$/, '');
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
