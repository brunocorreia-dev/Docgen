import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { errorMessage } from '../messages.js';
import { button } from '../ui.js';

const MOTION_KEY = 'docgen.reduceMotion';

export function applyMotionPreference() {
  let reduce = false;
  try {
    reduce = localStorage.getItem(MOTION_KEY) === '1';
  } catch {
    reduce = false;
  }
  document.documentElement.classList.toggle('reduce-motion', reduce);
  return reduce;
}

export async function renderSettings(ctx) {
  const reduce = applyMotionPreference();
  const motion = h('input', { type: 'checkbox', id: 'pref-motion', checked: reduce, on: { change: () => {
    try {
      localStorage.setItem(MOTION_KEY, motion.checked ? '1' : '0');
    } catch {
      // Preference storage is optional.
    }
    applyMotionPreference();
  } } });
  const server = h('dl', { class: 'kv' }, h('dt', null, 'API'), h('dd', null, 'verificando…'));

  ctx.setView(h('div', { class: 'page page-narrow' },
    h('header', { class: 'page-header' }, h('div', null, h('h1', null, 'Configurações'), h('p', { class: 'muted' }, 'Sessão, preferências e segurança.'))),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('user'), 'Conta'),
      h('dl', { class: 'kv' }, h('dt', null, 'Usuário'), h('dd', null, ctx.username() || '—'),
        h('dt', null, 'Sessão'), h('dd', null, 'Token em memória do servidor, guardado apenas nesta aba (sessionStorage). Expira após 30 min de inatividade.')),
      button('Sair', { iconName: 'logout', variant: 'danger', onClick: () => ctx.logout() })),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('eye'), 'Preferências'),
      h('label', { class: 'toggle', for: 'pref-motion' }, motion, h('span', { class: 'toggle-track', 'aria-hidden': 'true' }),
        h('span', null, 'Reduzir animações (também respeitamos a preferência do sistema).'))),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('server'), 'Servidor'),
      server),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('shield'), 'Privacidade e segurança'),
      h('ul', { class: 'bullet-list' },
        h('li', null, 'O servidor escuta apenas em 127.0.0.1 e exige autenticação em todas as rotas de dados.'),
        h('li', null, 'A chave da Groq existe só no servidor (GROQ_API_KEY); o navegador nunca a recebe.'),
        h('li', null, 'Envios para provedores remotos exigem sua confirmação explícita para cada operação, com o conteúdo exato que será transmitido.'),
        h('li', null, 'PDFs são processados em área privada e removidos logo após a extração; apenas o texto redigido é guardado, por até 30 dias.'),
        h('li', null, 'Resultados da IA são propostas: revise antes de usar. Nada é apresentado como implementado.')))));

  try {
    const [health, providers] = await Promise.all([ctx.api.health(), ctx.providers()]);
    replace(server, 
      h('dt', null, 'API'), h('dd', null, health.status === 'available' ? 'Disponível' : health.status),
      ...providers.flatMap((provider) => [
        h('dt', null, provider.id === 'groq' ? 'Groq' : 'Ollama'),
        h('dd', null, [provider.remote ? 'Remoto' : 'Local', `modelo padrão ${provider.defaultModel}`,
          provider.enabled ? 'habilitado' : 'desativado pelo operador',
          provider.remote ? (provider.configured ? 'chave configurada no servidor' : 'GROQ_API_KEY ausente') : null]
          .filter(Boolean).join(' · ')),
      ]));
  } catch (failure) {
    replace(server, h('dt', null, 'API'), h('dd', null, errorMessage(failure)));
  }
}
