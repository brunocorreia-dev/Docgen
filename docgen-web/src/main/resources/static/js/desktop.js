// Desktop pairing: when the DocGen launcher started the server there is no
// password. The workspace asks for a session and waits until the person
// confirms, in the launcher window, the code shown on this page. Nothing secret
// is put in the URL, and the poll secret lives only in this function's memory.
import { ApiError } from './api.js';

export const POLL_INTERVAL_MS = 1000;

export async function isDesktopMode(api) {
  try {
    const status = await api.desktopStatus();
    return status?.mode === 'desktop';
  } catch {
    return false;
  }
}

function abortError() {
  const error = new Error('Pairing aborted');
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

/**
 * Requests a pairing, reports the code to show, and polls until the launcher
 * approves (the session is then stored by the API client) or the request
 * fails. Errors are ApiError instances with the server's code; a pairing that
 * disappeared (replaced, cancelled) is reported as "pairing_replaced".
 */
export async function pairWithLauncher({ api, onCode, signal, intervalMs = POLL_INTERVAL_MS, wait = sleep }) {
  const pairing = await api.startPairing();
  if (typeof pairing?.pairingId !== 'string' || typeof pairing?.pollSecret !== 'string' || typeof pairing?.code !== 'string') {
    throw new ApiError(0, 'invalid_response', 'Unexpected pairing response');
  }
  onCode?.(pairing.code, pairing.expiresAt);
  for (;;) {
    if (signal?.aborted) throw abortError();
    let result;
    try {
      result = await api.pollPairing(pairing.pairingId, pairing.pollSecret, { signal });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) {
        throw new ApiError(404, 'pairing_replaced', failure.message);
      }
      throw failure;
    }
    if (!result.pending) return result;
    await wait(intervalMs, signal);
  }
}
