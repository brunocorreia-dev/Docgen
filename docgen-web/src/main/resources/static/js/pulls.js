// Follows a model download. The server reports Ollama's own progress events;
// the page reads the latest snapshot a few times per second.
import { ApiError } from './api.js';

export const PULL_POLL_MS = 700;
/** Consecutive network failures after which the page stops following. */
export const MAX_POLL_FAILURES = 3;

export function sleep(ms, signal, timers = globalThis) {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) {
      reject(new DOMException('Aborted', 'AbortError'));
      return;
    }
    const handle = timers.setTimeout(() => {
      signal?.removeEventListener?.('abort', onAbort);
      resolve();
    }, ms);
    const onAbort = () => {
      timers.clearTimeout(handle);
      reject(new DOMException('Aborted', 'AbortError'));
    };
    signal?.addEventListener?.('abort', onAbort, { once: true });
  });
}

/** Resolves with the final snapshot once the download is no longer running. */
export async function watchPull({ api, id, onUpdate, signal, intervalMs = PULL_POLL_MS, timers = globalThis }) {
  let failures = 0;
  for (;;) {
    let view;
    try {
      view = await api.pullStatus(id, { signal });
      failures = 0;
    } catch (failure) {
      if (failure?.name === 'AbortError' || !(failure instanceof ApiError) || failure.code !== 'network_error') throw failure;
      failures += 1;
      if (failures >= MAX_POLL_FAILURES) throw failure;
      await sleep(intervalMs, signal, timers);
      continue;
    }
    onUpdate?.(view);
    if (view.state !== 'RUNNING') return view;
    await sleep(intervalMs, signal, timers);
  }
}
