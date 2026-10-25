// Full-page screens shown outside the app shell: the launch page states, a tab
// without a session and the app after "Sair do DocGen". There is no login.
import { h } from '../dom.js';
import { icon, logo } from '../icons.js';

export function accessScreen({ title, message, tone = 'info', facts = true }) {
  return h('main', { class: 'access-screen' },
    h('div', { class: 'access-aurora', 'aria-hidden': 'true' }),
    h('section', { class: 'access-card', role: tone === 'error' ? 'alert' : 'status' },
      h('div', { class: 'access-brand' }, logo(44), h('div', null, h('h1', null, 'DocGen'), h('p', null, 'Sua IA. Seu computador. Suas regras.'))),
      h('h2', { class: 'access-title' }, icon(tone === 'error' ? 'alert' : 'info'), h('span', null, title)),
      h('p', { class: 'access-message' }, message),
      facts ? h('ul', { class: 'access-facts' },
        h('li', null, icon('shield', { size: 16 }), 'O DocGen roda só neste computador (127.0.0.1) e não tem conta nem senha.'),
        h('li', null, icon('lock', { size: 16 }), 'Cada janela recebe um acesso de uso único, que vale só para ela.')) : null));
}

export function noSessionScreen(reason) {
  return accessScreen({
    title: reason === 'ended' ? 'Sua sessão terminou' : 'Abra o DocGen pelo atalho',
    message: reason === 'ended'
      ? 'Por segurança, esta janela perdeu o acesso (o DocGen foi reiniciado ou ficou muito tempo sem uso). Abra o DocGen de novo pelo menu ou pelo atalho.'
      : 'Esta janela não tem acesso ao DocGen. Abra-o pelo menu de aplicativos ou pelo atalho: o DocGen abre uma janela já autorizada.',
  });
}

export function closedScreen() {
  return accessScreen({
    title: 'O DocGen foi encerrado',
    message: 'Pode fechar esta janela. Para usar de novo, abra o DocGen pelo menu ou pelo atalho.',
    facts: false,
  });
}
