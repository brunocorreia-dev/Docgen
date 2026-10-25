import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../main/resources/static/js/api.js';
import { MAX_POLL_FAILURES, watchPull } from '../../main/resources/static/js/pulls.js';

const instantTimers = { setTimeout: (fn) => { fn(); return 1; }, clearTimeout: () => {} };

test('follows a download until it is no longer running', async () => {
  const states = ['RUNNING', 'RUNNING', 'SUCCEEDED'];
  const seen = [];
  const api = { pullStatus: async (id) => ({ id, state: states.shift(), completedBytes: 1 }) };

  const final = await watchPull({ api, id: 'p1', onUpdate: (view) => seen.push(view.state), timers: instantTimers });

  assert.equal(final.state, 'SUCCEEDED');
  assert.deepEqual(seen, ['RUNNING', 'RUNNING', 'SUCCEEDED']);
});

test('a few network hiccups are tolerated, more are reported', async () => {
  let calls = 0;
  const flaky = { pullStatus: async () => {
    calls += 1;
    if (calls < MAX_POLL_FAILURES) throw new ApiError(0, 'network_error', '');
    return { state: 'CANCELLED' };
  } };
  assert.equal((await watchPull({ api: flaky, id: 'p', timers: instantTimers })).state, 'CANCELLED');

  const gone = { pullStatus: async () => { throw new ApiError(0, 'network_error', ''); } };
  await assert.rejects(watchPull({ api: gone, id: 'p', timers: instantTimers }), (error) => error.code === 'network_error');
});

test('server errors stop following at once', async () => {
  const api = { pullStatus: async () => { throw new ApiError(404, 'not_found', ''); } };
  await assert.rejects(watchPull({ api, id: 'p', timers: instantTimers }), (error) => error.code === 'not_found');
});

test('leaving the screen stops the polling', async () => {
  const controller = new AbortController();
  let calls = 0;
  const api = { pullStatus: async () => { calls += 1; controller.abort(); return { state: 'RUNNING' }; } };

  await assert.rejects(watchPull({ api, id: 'p', signal: controller.signal, intervalMs: 10_000 }), (error) => error.name === 'AbortError');
  assert.equal(calls, 1);
});
