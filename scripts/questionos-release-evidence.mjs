#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const PROJECT_ID = 'e045a0c4-63c9-4fad-addb-c1980a849292';
const expectedCommit = (process.env.QOS_RELEASE_COMMIT || process.argv[2] || '').trim();
const expectedServices = new Set(
  (process.env.QOS_RELEASE_SERVICES || process.argv[3] || 'frontend,backend,smoke-monitor')
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean)
);
const frontendUrl = normalizeBase(
  process.env.QOS_FRONTEND_URL || 'https://questionos-app.up.railway.app'
);
const monitorUrl = normalizeBase(
  process.env.QOS_MONITOR_URL || 'https://smoke-monitor-production.up.railway.app'
);

if (!/^[0-9a-f]{40}$/i.test(expectedCommit)) {
  fail('Usage: QOS_RELEASE_COMMIT=<40-char-sha> node scripts/questionos-release-evidence.mjs');
}

const status = railwayJson(['status', '--json']);
if (status.id !== PROJECT_ID || status.name !== 'QuestionOS') {
  fail(`Railway CLI is linked to ${status.name || 'unknown'} (${status.id || 'unknown'}), not QuestionOS`);
}

const services = railwayJson(['service', 'list', '--json']);
const byName = Object.fromEntries(services.map((service) => [service.name, service]));
const deployments = {};

for (const serviceName of ['frontend', 'backend', 'smoke-monitor']) {
  const rows = railwayJson(['deployment', 'list', '--service', serviceName, '--limit', '1', '--json']);
  const deployment = rows[0];
  if (!deployment) fail(`No Railway deployment found for ${serviceName}`);
  const commit = deployment.meta?.commitHash || null;
  deployments[serviceName] = {
    id: deployment.id,
    status: deployment.status,
    commit,
    sourceRepo: deployment.meta?.repo || byName[serviceName]?.source?.repo || null,
    rootDirectory: deployment.meta?.rootDirectory ?? null,
    configFile: deployment.meta?.configFile || null,
    dockerfilePath: deployment.meta?.serviceManifest?.build?.dockerfilePath || null,
    watchPatterns: deployment.meta?.serviceManifest?.build?.watchPatterns || [],
    healthcheckPath: deployment.meta?.serviceManifest?.deploy?.healthcheckPath || null,
  };
  if (deployment.status !== 'SUCCESS') fail(`${serviceName} deployment is ${deployment.status}`);
  if (expectedServices.has(serviceName) && commit !== expectedCommit) {
    fail(`${serviceName} runs ${commit || 'no Git commit'}, expected ${expectedCommit}`);
  }
}

const postgres = byName.Postgres;
if (!postgres || postgres.status !== 'SUCCESS' || postgres.replicas?.running !== 1) {
  fail('Postgres is not running successfully');
}
if (!postgres.volumes?.some((volume) => volume.state === 'READY')) {
  fail('Postgres volume is not READY');
}

const [home, login, runtime, unauthenticated, ready, health, last, metrics] = await Promise.all([
  fetchText(`${frontendUrl}/`),
  fetchText(`${frontendUrl}/login`),
  fetchText(`${frontendUrl}/runtime-config.js`),
  fetchText(`${frontendUrl}/api/auth/me`),
  fetchText(`${monitorUrl}/ready`),
  fetchText(`${monitorUrl}/health`),
  fetchText(`${monitorUrl}/last`),
  fetchText(`${monitorUrl}/metrics`),
]);

assert(home.status === 200 && home.body.includes('QuestionOS'), 'frontend homepage failed');
assert(login.status === 200 && login.body.includes('Google'), 'frontend login entry failed');
assert(
  runtime.status === 200 && /__QOS_GOOGLE_CLIENT_ID__\s*=\s*"[^"]+"/.test(runtime.body),
  'frontend Google runtime config failed'
);
assert(unauthenticated.status === 401, `frontend API proxy expected 401, got ${unauthenticated.status}`);
assert(ready.status === 200 && parseJson(ready.body)?.ready === true, 'smoke /ready failed');
const healthJson = parseJson(health.body);
assert(health.status === 200 && healthJson?.ok === true, 'smoke /health failed');
const lastJson = parseJson(last.body);
assert(last.status === 200 && lastJson?.last?.ok === true, 'smoke /last failed');
assert(
  lastJson.last.results.some((result) => result.name === 'backend.health' && result.ok),
  'smoke did not prove backend health'
);
assert(
  lastJson.last.results.some((result) => result.name === 'backend.prometheus' && result.ok),
  'smoke did not prove backend metrics'
);
assert(metrics.status === 200 && metrics.body.includes('questionos_smoke_last_ok 1'), 'smoke metrics failed');

const backendLogs = execRailway([
  'logs',
  '--service',
  'backend',
  '--deployment',
  deployments.backend.id,
  '--lines',
  '400',
]);
const databaseEvidence = {
  hikariConnected: backendLogs.includes('HikariPool-1 - Start completed'),
  flywayValidated: /Successfully validated \d+ migrations/.test(backendLogs),
  schemaCurrent: backendLogs.includes('Schema "public" is up to date'),
  volumeReady: true,
};
assert(databaseEvidence.hikariConnected, 'backend logs do not prove a Postgres connection');
assert(databaseEvidence.flywayValidated, 'backend logs do not prove Flyway validation');

const evidence = {
  schemaVersion: 1,
  checkedAt: new Date().toISOString(),
  project: { id: status.id, name: status.name, environment: 'production' },
  expectedCommit,
  expectedServices: [...expectedServices],
  deployments,
  postgres: {
    deploymentId: postgres.deploymentId,
    status: postgres.status,
    image: postgres.source?.image || null,
    region: postgres.regions?.[0]?.name || null,
    databaseEvidence,
  },
  online: {
    frontendHome: true,
    frontendLogin: true,
    googleRuntimeConfig: true,
    frontendProxyUnauthenticated401: true,
    backendHealthViaMonitor: true,
    backendMetricsViaMonitor: true,
    smokeReady: true,
    smokeHealthy: true,
    smokeConsecutiveFailures: healthJson.consecutiveFailures,
    smokeCheckedAt: lastJson.last.checkedAt,
  },
};

const output = `${JSON.stringify(evidence, null, 2)}\n`;
if (process.env.QOS_RELEASE_EVIDENCE_OUT) {
  writeFileSync(process.env.QOS_RELEASE_EVIDENCE_OUT, output, { encoding: 'utf8', mode: 0o600 });
}
process.stdout.write(output);

function railwayJson(args) {
  return parseJsonOrFail(execRailway(args), `railway ${args.join(' ')}`);
}

function execRailway(args) {
  return execFileSync('railway', args, { encoding: 'utf8', maxBuffer: 10 * 1024 * 1024 });
}

async function fetchText(url) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 30_000);
    try {
      const response = await fetch(url, { signal: controller.signal });
      return { status: response.status, body: await response.text() };
    } catch (error) {
      lastError = error;
      if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, attempt * 1_000));
    } finally {
      clearTimeout(timer);
    }
  }
  throw new Error(`Cannot fetch ${url} after 3 attempts: ${lastError?.message || lastError}`);
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function parseJsonOrFail(text, label) {
  const parsed = parseJson(text);
  if (parsed == null) fail(`${label} did not return JSON`);
  return parsed;
}

function normalizeBase(value) {
  return String(value).trim().replace(/\/+$/, '');
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
