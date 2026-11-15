import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../main/resources/static/js/api.js';
import { HEARTBEAT_INTERVAL_MS, startHeartbeat } from '../../main/resources/static/js/heartbeat.js';

function fakeTimers() {
  const intervals = new Map();
  let next = 1;
  return {
    intervals,
    setInterval: (fn, ms) => { intervals.set(next, { fn, ms }); return next++; },
    clearInterval: (id) => intervals.delete(id),
    tick: async () => { for (const { fn } of [...intervals.values()]) await fn(); },
  };
}

const flush = () => new Promise((resolve) => setImmediate(resolve));

test('beats at once and then every minute', async () => {
  const timers = fakeTimers();
  let beats = 0;
  startHeartbeat({ api: { heartbeat: async () => { beats += 1; } }, timers, documentRef: null });
  await flush();

  assert.equal(beats, 1);
  assert.equal([...timers.intervals.values()][0].ms, HEARTBEAT_INTERVAL_MS);
  await timers.tick();
  assert.equal(beats, 2);
});

test('two network failures in a row mean the app is gone', async () => {
  const timers = fakeTimers();
  let lost = 0;
  const api = { heartbeat: async () => { throw new ApiError(0, 'network_error', ''); } };
  startHeartbeat({ api, timers, documentRef: null, onLost: () => { lost += 1; } });
  await flush();
  assert.equal(lost, 0);

  await timers.tick();
  assert.equal(lost, 1);
  assert.equal(timers.intervals.size, 0, 'stops beating');
});

test('other errors do not count as a closed app, and stop() ends the timer', async () => {
  const timers = fakeTimers();
  let lost = 0;
  const api = { heartbeat: async () => { throw new ApiError(401, 'authentication_required', ''); } };
  const stop = startHeartbeat({ api, timers, documentRef: null, onLost: () => { lost += 1; } });
  await flush();
  await timers.tick();
  await timers.tick();

  assert.equal(lost, 0);
  stop();
  assert.equal(timers.intervals.size, 0);
});
