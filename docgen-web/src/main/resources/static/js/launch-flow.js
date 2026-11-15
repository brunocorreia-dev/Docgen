// The /launch page: the desktop launcher opens /launch#<token> with a
// single-use launch token in the fragment (never sent to the server, never in
// the Referer). The page removes it from the address bar before doing
// anything else, exchanges it for this tab's session and goes to the app.

export const LAUNCH_TOKEN = /^[A-Za-z0-9_-]{43}$/;

export const LAUNCH_MESSAGES = {
  missing: 'Este endereço não traz um acesso válido. Abra o DocGen pelo menu ou pelo atalho.',
  expired: 'Este link de acesso expirou ou já foi usado. Abra o DocGen de novo pelo menu ou pelo atalho.',
  reused: 'Este link de acesso já tinha sido usado por outro programa deste computador. Por segurança, '
    + 'essa sessão foi encerrada. Abra o DocGen de novo pelo menu ou pelo atalho.',
  unavailable: 'Não foi possível falar com o DocGen. Abra-o de novo pelo menu ou pelo atalho.',
};

export function readLaunchToken(hash) {
  const value = String(hash || '').replace(/^#/, '');
  return LAUNCH_TOKEN.test(value) ? value : null;
}

/**
 * @returns {{ok: true} | {ok: false, reason: keyof LAUNCH_MESSAGES}}
 */
export async function runLaunch({ hash, clearFragment, api, goHome }) {
  const token = readLaunchToken(hash);
  // Out of the address bar and the history before any request, valid or not.
  clearFragment();
  if (!token) return { ok: false, reason: 'missing' };
  try {
    await api.startSession(token);
  } catch (failure) {
    if (failure?.code === 'launch_token_reused') return { ok: false, reason: 'reused' };
    if (failure?.code === 'launch_token_invalid' || failure?.code === 'invalid_request') return { ok: false, reason: 'expired' };
    return { ok: false, reason: 'unavailable' };
  }
  goHome();
  return { ok: true };
}
