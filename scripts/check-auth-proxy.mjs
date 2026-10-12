// Run against a local frontend connected to an isolated PostgreSQL backend.
import assert from 'node:assert/strict';

const base = new URL(process.env.QOS_FRONTEND_URL || 'http://127.0.0.1:3000');
assert(['localhost', '127.0.0.1', '[::1]'].includes(base.hostname), 'Local targets only');
for (const [path, data, expected] of [
  ['/api/auth/me', undefined, 401],
  ['/api/auth/login', { email: 'proxy-regression@example.test', password: 'invalid-test-password' }, 401],
  ['/api/auth/login', { email: 'invalid-email', password: '' }, 400],
]) {
  const response = await fetch(new URL(path, base), {
    method: data ? 'POST' : 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: data ? JSON.stringify(data) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  assert.equal(response.status, expected, `${path}: proxy must preserve backend errors`);
  if (data) assert.equal(typeof await response.json(), 'object');
}
console.log('Auth proxy preserves unauthenticated, invalid-password and validation responses.');
