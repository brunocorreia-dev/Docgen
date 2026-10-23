import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi, memoryTokenStore } from '../../main/resources/static/js/api.js';
import { isDesktopMode, pairWithLauncher, sleep } from '../../main/resources/static/js/desktop.js';
import { href, parseRoute } from '../../main/resources/static/js/router.js';

function response(status, body) {
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name) => (name.toLowerCase() === 'content-type' ? 'application/json' : null) },
    json: async () => body,
    text: async () => JSON.stringify(body),
  };
}

function recorder(...responses) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options });
    const next = responses.shift();
    if (next instanceof Error) throw next;
    return next;
  };
  return { calls, fetchImpl };
}

const PAIRING = { pairingId: '0f8c2a52-3b5d-4c1e-9a7f-1234567890ab', pollSecret: 'p'.repeat(43), code: 'ABCD-EFGH', expiresAt: '2999-01-01T00:00:00Z' };
const noWait = async () => {};

test('the desktop route carries no credential', () => {
  assert.equal(parseRoute('#/desktop').name, 'desktop');
  assert.equal(href('desktop'), '#/desktop');
  assert.equal(parseRoute('#/desktop?token=abc').name, 'desktop');
  assert.deepEqual(parseRoute('#/desktop?token=abc').params, {});
});

test('desktop mode is detected only from the server status endpoint', async () => {
  const yes = createApi(recorder(response(200, { mode: 'desktop' })));
  assert.equal(await isDesktopMode(yes), true);
  const unauthorized = createApi(recorder(response(401, { code: 'authentication_required' })));
  assert.equal(await isDesktopMode(unauthorized), false);
  const offline = createApi(recorder(new TypeError('offline')));
  assert.equal(await isDesktopMode(offline), false);
});

test('pairing shows the code, polls with the secret in the body and stores the session', async () => {
  const { calls, fetchImpl } = recorder(
    response(201, PAIRING),
    response(202, { status: 'pending', expiresAt: PAIRING.expiresAt }),
    response(200, { accessToken: 'tok-1', tokenType: 'Bearer', expiresAt: '2999-01-01T00:00:00Z', username: 'ana' }));
  const tokens = memoryTokenStore();
  const api = createApi({ fetchImpl, tokenStore: tokens });
  const shown = [];

  const result = await pairWithLauncher({ api, onCode: (code) => shown.push(code), wait: noWait });

  assert.deepEqual(shown, ['ABCD-EFGH']);
  assert.deepEqual(result, { pending: false, username: 'ana' });
  assert.equal(tokens.get(), 'tok-1');
  assert.equal(calls[0].url, '/api/v1/desktop/pairings');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
  assert.equal(calls[0].options.headers['X-DocGen-Client'], 'web');
  for (const call of calls.slice(1)) {
    assert.equal(call.url, `/api/v1/desktop/pairings/${PAIRING.pairingId}/token`);
    assert.ok(!call.url.includes(PAIRING.pollSecret), 'the poll secret never goes in the URL');
    assert.deepEqual(JSON.parse(call.options.body), { pollSecret: PAIRING.pollSecret });
    assert.equal(call.options.credentials, 'omit');
  }
});

test('server refusals surface as ApiError codes', async () => {
  for (const [status, code] of [[409, 'pairing_unavailable'], [409, 'pairing_conflict'], [403, 'origin_rejected']]) {
    const api = createApi(recorder(response(status, { status, code, message: '' })));
    await assert.rejects(() => pairWithLauncher({ api, wait: noWait }), (error) => error instanceof ApiError && error.code === code);
  }
  const denied = createApi(recorder(response(201, PAIRING), response(403, { status: 403, code: 'pairing_denied' })));
  await assert.rejects(() => pairWithLauncher({ api: denied, wait: noWait }), (error) => error.code === 'pairing_denied');
  const expired = createApi(recorder(response(201, PAIRING), response(410, { status: 410, code: 'pairing_expired' })));
  await assert.rejects(() => pairWithLauncher({ api: expired, wait: noWait }), (error) => error.code === 'pairing_expired');
});

test('a pairing that disappeared is reported as replaced', async () => {
  const api = createApi(recorder(response(201, PAIRING), response(404, { status: 404, code: 'not_found' })));
  await assert.rejects(() => pairWithLauncher({ api, wait: noWait }), (error) => error.code === 'pairing_replaced');
});

test('malformed pairing or token responses are rejected', async () => {
  const bad = createApi(recorder(response(201, { pairingId: 1 })));
  await assert.rejects(() => pairWithLauncher({ api: bad, wait: noWait }), (error) => error.code === 'invalid_response');
  const tokens = memoryTokenStore();
  const noToken = createApi({ ...recorder(response(201, PAIRING), response(200, { username: 'ana' })), tokenStore: tokens });
  await assert.rejects(() => pairWithLauncher({ api: noToken, wait: noWait }), (error) => error.code === 'invalid_response');
  assert.equal(tokens.get(), null);
});

test('leaving the screen stops polling', async () => {
  const controller = new AbortController();
  const { calls, fetchImpl } = recorder(response(201, PAIRING), response(202, { status: 'pending' }), response(202, { status: 'pending' }));
  const api = createApi({ fetchImpl });
  const pairing = pairWithLauncher({ api, signal: controller.signal, intervalMs: 5 });
  setTimeout(() => controller.abort(), 1);
  await assert.rejects(pairing, (error) => error.name === 'AbortError');
  assert.ok(calls.length <= 3);
  await assert.rejects(() => sleep(10, controller.signal), (error) => error.name === 'AbortError');
});
