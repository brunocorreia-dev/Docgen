import { h } from '../dom.js';
import { icon, logo } from '../icons.js';
import { errorMessage } from '../messages.js';
import { pairWithLauncher } from '../desktop.js';

/**
 * Sign-in screen of the desktop app. It shows the confirmation code the
 * launcher window shows; the session starts only after the person confirms
 * there that both codes are the same.
 */
export function renderDesktopPairing({ api, onAuthenticated, signal }) {
  const status = h('p', { class: 'pairing-status', role: 'status', 'aria-live': 'polite' }, 'Solicitando acesso ao DocGen…');
  const code = h('p', { class: 'pairing-code', id: 'pairing-code' });
  const codeBlock = h('div', { class: 'pairing-code-block', hidden: true },
    h('span', { class: 'pairing-code-label' }, 'Código de confirmação'), code);
  const error = h('p', { class: 'form-alert', role: 'alert', hidden: true });
  const retry = h('button', { class: 'button button-primary button-block', type: 'button', hidden: true, on: { click: () => start() } },
    icon('refresh'), h('span', null, 'Tentar novamente'));

  async function start() {
    error.hidden = true;
    retry.hidden = true;
    codeBlock.hidden = true;
    status.textContent = 'Solicitando acesso ao DocGen…';
    try {
      await pairWithLauncher({
        api,
        signal,
        onCode: (value) => {
          code.textContent = value;
          codeBlock.hidden = false;
          status.textContent = 'Confira se a janela do DocGen mostra o mesmo código e clique em “Autorizar”.';
        },
      });
      status.textContent = 'Acesso autorizado. Abrindo o DocGen…';
      onAuthenticated();
    } catch (failure) {
      if (failure?.name === 'AbortError') return;
      codeBlock.hidden = true;
      status.textContent = 'Este navegador ainda não está conectado.';
      error.textContent = errorMessage(failure);
      error.hidden = false;
      retry.hidden = false;
    }
  }

  setTimeout(start, 0);

  return h('main', { class: 'login-screen' },
    h('div', { class: 'login-aurora', 'aria-hidden': 'true' }),
    h('section', { class: 'login-card', 'aria-labelledby': 'pairing-title' },
      h('div', { class: 'login-brand' }, logo(44),
        h('div', null, h('h1', { id: 'pairing-title' }, 'DocGen'), h('p', null, 'Seu assistente local e gratuito.'))),
      status,
      codeBlock,
      error,
      retry,
      h('ul', { class: 'login-facts' },
        h('li', null, icon('shield', { size: 16 }), 'Só autorize se o código for igual ao da janela do DocGen que você abriu.'),
        h('li', null, icon('lock', { size: 16 }), 'Sem senha: a sessão fica só nesta aba e expira por inatividade.'),
        h('li', null, icon('server', { size: 16 }), 'Tudo roda no seu computador (127.0.0.1).'))));
}
