import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../main/resources/static/js/api.js';
import { LAUNCH_MESSAGES, readLaunchToken, runLaunch } from '../../main/resources/static/js/launch-flow.js';

const TOKEN = 'abcDEF0123456789_-abcDEF0123456789_-abcDEF0';

function harness(startSession) {
  const events = [];
  return {
    events,
    args: (hash) => ({
      hash,
      clearFragment: () => events.push('clear'),
      goHome: () => events.push('home'),
      api: { startSession: async (token) => { events.push(`exchange:${token}`); return startSession(token); } },
    }),
  };
}

test('only a 43-character base64url fragment is a launch token', () => {
  assert.equal(TOKEN.length, 43);
  assert.equal(readLaunchToken(`#${TOKEN}`), TOKEN);
  for (const hash of ['', '#', '#short', `#${TOKEN}x`, `#${TOKEN.slice(1)}=`, `#/dashboard`, `#${TOKEN.slice(0, 42)}/`, null]) {
    assert.equal(readLaunchToken(hash), null, String(hash));
  }
});

test('the fragment leaves the address bar before the exchange, and success goes to the app', async () => {
  const { events, args } = harness(async () => ({ accessToken: 't' }));
  const result = await runLaunch(args(`#${TOKEN}`));

  assert.deepEqual(result, { ok: true });
  assert.deepEqual(events, ['clear', `exchange:${TOKEN}`, 'home']);
});

test('a malformed fragment is cleared and never sent', async () => {
  const { events, args } = harness(async () => ({}));
  const result = await runLaunch(args('#<script>alert(1)</script>'));

  assert.deepEqual(result, { ok: false, reason: 'missing' });
  assert.deepEqual(events, ['clear']);
});

test('expired, reused and unreachable cases explain what to do in Portuguese', async () => {
  const cases = [
    [new ApiError(401, 'launch_token_invalid', ''), 'expired'],
    [new ApiError(409, 'launch_token_reused', ''), 'reused'],
    [new ApiError(0, 'network_error', ''), 'unavailable'],
    [new ApiError(403, 'origin_rejected', ''), 'unavailable'],
  ];
  for (const [failure, reason] of cases) {
    const { events, args } = harness(async () => { throw failure; });
    const result = await runLaunch(args(`#${TOKEN}`));
    assert.deepEqual(result, { ok: false, reason });
    assert.ok(!events.includes('home'));
    assert.match(LAUNCH_MESSAGES[reason], /DocGen/);
  }
  assert.match(LAUNCH_MESSAGES.expired, /expirou ou já foi usado/);
});
