// While a window is open it tells the app so. The desktop app stops by itself
// after 10 minutes without any window (ADR 0001), but never during a model
// download or a generation.

export const HEARTBEAT_INTERVAL_MS = 60_000;
/** Consecutive network failures after which the app is considered gone. */
export const LOST_AFTER_FAILURES = 2;

export function startHeartbeat({ api, onLost, intervalMs = HEARTBEAT_INTERVAL_MS, timers = globalThis, documentRef = globalThis.document }) {
  let failures = 0;
  let stopped = false;

  async function beat() {
    if (stopped) return;
    try {
      await api.heartbeat();
      failures = 0;
    } catch (failure) {
      if (failure?.code !== 'network_error') return;
      failures += 1;
      if (failures >= LOST_AFTER_FAILURES && !stopped) {
        stop();
        onLost?.();
      }
    }
  }

  const onVisible = () => {
    if (documentRef?.visibilityState === 'visible') beat();
  };
  const handle = timers.setInterval(beat, intervalMs);
  documentRef?.addEventListener?.('visibilitychange', onVisible);
  beat();

  function stop() {
    stopped = true;
    timers.clearInterval(handle);
    documentRef?.removeEventListener?.('visibilitychange', onVisible);
  }
  return stop;
}
