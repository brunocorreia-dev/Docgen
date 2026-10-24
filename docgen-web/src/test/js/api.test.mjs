import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi, fileNameFrom, memoryTokenStore, sessionTokenStore } from '../../main/resources/static/js/api.js';

function response(status, body, headers = {}) {
  const map = new Map(Object.entries({ 'Content-Type': 'application/json', ...headers }).map(([k, v]) => [k.toLowerCase(), v]));
  return {
    status,
    ok: status >= 200 && status < 300,
    headers: { get: (name) => map.get(name.toLowerCase()) ?? null },
    json: async () => (typeof body === 'string' ? JSON.parse(body) : body),
    text: async () => JSON.stringify(body),
    blob: async () => ({ size: 3 }),
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

test('a launch token becomes the session, sent afterwards only in the Authorization header', async () => {
  const { calls, fetchImpl } = recorder(
    response(201, { accessToken: 'tok-123', tokenType: 'Bearer', idleTimeoutSeconds: 43200 }),
    response(200, { projects: [] }));
  const tokens = memoryTokenStore();
  const api = createApi({ fetchImpl, tokenStore: tokens });

  await api.startSession('L'.repeat(43));
  await api.listProjects();

  assert.equal(calls[0].url, '/api/v1/session');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers['Content-Type'], 'application/json');
  assert.equal(calls[0].options.headers.Authorization, undefined);
  assert.deepEqual(JSON.parse(calls[0].options.body), { launchToken: 'L'.repeat(43) });
  assert.equal(calls[1].options.headers.Authorization, 'Bearer tok-123');
  assert.equal(calls[1].options.credentials, 'omit');
  assert.equal(calls[1].options.redirect, 'error');
  assert.equal(tokens.get(), 'tok-123');
});

test('leaving the app stops the server and forgets the session', async () => {
  const { calls, fetchImpl } = recorder(response(202, null));
  const tokens = memoryTokenStore();
  tokens.set('tok');
  const api = createApi({ fetchImpl, tokenStore: tokens });

  await api.quit();
  assert.equal(calls[0].url, '/api/v1/desktop/shutdown');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(tokens.get(), null);
});

test('heartbeats are authenticated POSTs to the desktop route', async () => {
  const { calls, fetchImpl } = recorder(response(204, null));
  const tokens = memoryTokenStore();
  tokens.set('tok');
  await createApi({ fetchImpl, tokenStore: tokens }).heartbeat();

  assert.equal(calls[0].url, '/api/v1/desktop/heartbeat');
  assert.equal(calls[0].options.method, 'POST');
  assert.equal(calls[0].options.headers.Authorization, 'Bearer tok');
});

test('error bodies become ApiError with the server code', async () => {
  const { fetchImpl } = recorder(response(409, { status: 409, code: 'plan_not_approved', message: 'Approve first' }));
  const api = createApi({ fetchImpl });

  await assert.rejects(() => api.startDocumentation('p', { planId: 'x' }), (error) => {
    assert.ok(error instanceof ApiError);
    assert.equal(error.status, 409);
    assert.equal(error.code, 'plan_not_approved');
    return true;
  });
});

test('a 401 with a token clears the session and notifies the shell', async () => {
  let notified = 0;
  const tokens = memoryTokenStore();
  tokens.set('expired');
  const { fetchImpl } = recorder(response(401, { code: 'authentication_required' }));
  const api = createApi({ fetchImpl, tokenStore: tokens, onUnauthorized: () => { notified += 1; } });

  await assert.rejects(() => api.listProjects());
  assert.equal(tokens.get(), null);
  assert.equal(notified, 1);
});

test('network failures map to a stable code', async () => {
  const { fetchImpl } = recorder(new TypeError('Failed to fetch'));
  await assert.rejects(() => createApi({ fetchImpl }).health(), (error) => error.code === 'network_error');
});

test('uploads send raw PDF bytes with an encoded display name', async () => {
  const { calls, fetchImpl } = recorder(response(201, { id: 'a' }));
  const file = { name: '../especificação final.pdf', size: 3 };
  await createApi({ fetchImpl }).uploadAttachment('p1', file);

  assert.equal(calls[0].url, '/api/v1/projects/p1/attachments');
  assert.equal(calls[0].options.body, file);
  assert.equal(calls[0].options.headers['Content-Type'], 'application/pdf');
  assert.equal(calls[0].options.headers['X-Attachment-Name'], encodeURIComponent('../especificação final.pdf'));
});

test('identifiers are URL-encoded into paths', async () => {
  const { calls, fetchImpl } = recorder(response(200, {}));
  await createApi({ fetchImpl }).getProject('../../admin?x=1');
  assert.equal(calls[0].url, '/api/v1/projects/..%2F..%2Fadmin%3Fx%3D1');
});

test('download names are taken from Content-Disposition and sanitized', () => {
  const headers = (value) => ({ headers: { get: () => value } });
  assert.equal(fileNameFrom(headers("attachment; filename*=UTF-8''README.md"), 'x'), 'README.md');
  assert.equal(fileNameFrom(headers('attachment; filename="../../etc/passwd"'), 'x'), '.._.._etc_passwd');
  assert.equal(fileNameFrom(headers(null), 'fallback.zip'), 'fallback.zip');
});

test('the session token store keeps only the token and ignores malformed entries', () => {
  const backing = new Map();
  const storage = { getItem: (k) => backing.get(k) ?? null, setItem: (k, v) => backing.set(k, v), removeItem: (k) => backing.delete(k) };
  const store = sessionTokenStore(storage);
  store.set('t2');
  assert.equal(store.get(), 't2');
  assert.deepEqual(JSON.parse(backing.get('docgen.session')), { token: 't2' });
  backing.set('docgen.session', 'not json');
  assert.equal(store.get(), null);
  backing.set('docgen.session', '{"token": 42}');
  assert.equal(store.get(), null);
  store.clear();
  assert.equal(backing.has('docgen.session'), false);
});
