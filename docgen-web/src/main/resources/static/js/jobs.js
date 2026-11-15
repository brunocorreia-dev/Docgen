// Polling for asynchronous jobs. HTTP polling is enough for minute-long AI
// calls; it backs off gently and stops on a terminal state or on abort.

export const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
export const DEFAULT_INTERVALS = [700, 1000, 1500, 2000, 3000];

function abortError() {
  const error = new Error('Polling aborted');
  error.name = 'AbortError';
  return error;
}

export function sleep(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError());
      return;
    }
    const timer = setTimeout(() => {
      signal?.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort() {
      clearTimeout(timer);
      reject(abortError());
    }
    signal?.addEventListener('abort', onAbort, { once: true });
  });
}

export async function pollJob(api, jobId, { onUpdate, signal, wait = sleep, intervals = DEFAULT_INTERVALS, maxPolls = 2000 } = {}) {
  for (let attempt = 0; attempt < maxPolls; attempt += 1) {
    if (signal?.aborted) throw abortError();
    const job = await api.job(jobId, { signal });
    onUpdate?.(job);
    if (TERMINAL.has(job.status)) return job;
    await wait(intervals[Math.min(attempt, intervals.length - 1)], signal);
  }
  throw new Error('Job polling limit reached');
}

export function elapsedSeconds(job, now = Date.now()) {
  const start = Date.parse(job?.startedAt || job?.createdAt || '');
  if (Number.isNaN(start)) return 0;
  const end = job?.finishedAt ? Date.parse(job.finishedAt) : now;
  return Math.max(0, Math.round((end - start) / 1000));
}
