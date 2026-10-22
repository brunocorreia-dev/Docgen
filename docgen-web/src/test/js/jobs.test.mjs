import { test } from 'node:test';
import assert from 'node:assert/strict';
import { elapsedSeconds, pollJob } from '../../main/resources/static/js/jobs.js';

test('polls until a terminal state, reporting every update and backing off', async () => {
  const states = ['QUEUED', 'RUNNING', 'RUNNING', 'SUCCEEDED'];
  const waits = [];
  const updates = [];
  const api = { job: async () => ({ id: 'j', status: states.shift() }) };

  const final = await pollJob(api, 'j', {
    onUpdate: (job) => updates.push(job.status),
    wait: async (ms) => { waits.push(ms); },
    intervals: [10, 20, 30],
  });

  assert.equal(final.status, 'SUCCEEDED');
  assert.deepEqual(updates, ['QUEUED', 'RUNNING', 'RUNNING', 'SUCCEEDED']);
  assert.deepEqual(waits, [10, 20, 30]);
});

test('stops when aborted', async () => {
  const controller = new AbortController();
  const api = { job: async () => ({ status: 'RUNNING' }) };
  const promise = pollJob(api, 'j', { signal: controller.signal, wait: async () => controller.abort() });
  await assert.rejects(promise, (error) => error.name === 'AbortError');
});

test('failed and cancelled jobs are terminal too', async () => {
  for (const status of ['FAILED', 'CANCELLED']) {
    const job = await pollJob({ job: async () => ({ status }) }, 'j', { wait: async () => {} });
    assert.equal(job.status, status);
  }
});

test('computes elapsed time from start to finish', () => {
  assert.equal(elapsedSeconds({ startedAt: '2026-01-01T00:00:00Z', finishedAt: '2026-01-01T00:01:05Z' }), 65);
  assert.equal(elapsedSeconds({}), 0);
});
