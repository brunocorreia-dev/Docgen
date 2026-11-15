import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { errorMessage } from '../messages.js';
import { button, openDialog, toast } from '../ui.js';

/** The word the user types to confirm "Apagar todos os meus dados". */
export const ERASE_CONFIRMATION = 'APAGAR';

function confirmErase() {
  return new Promise((resolve) => {
    const input = h('input', { id: 'erase-confirmation', type: 'text', autocomplete: 'off', spellcheck: 'false', maxlength: 16 });
    const hint = h('p', { class: 'form-alert', role: 'alert', hidden: true }, `Digite ${ERASE_CONFIRMATION} para confirmar.`);
    openDialog({
      title: 'Apagar todos os meus dados',
      description: 'Isto não pode ser desfeito.',
      body: h('div', null,
        h('p', null, 'Todos os projetos, planos, documentos gerados, anexos e o histórico de tarefas deste computador serão apagados. ',
          'Os logs técnicos (que nunca guardam o conteúdo das conversas, dos anexos nem dos documentos) são mantidos.'),
        h('div', { class: 'field' }, h('label', { for: 'erase-confirmation' }, `Digite ${ERASE_CONFIRMATION} para confirmar`), input),
        hint),
      actions: [
        { label: 'Cancelar', value: 'cancel' },
        { label: 'Apagar tudo', value: 'confirm', variant: 'danger', onClick: () => {
          const confirmed = input.value.trim().toUpperCase() === ERASE_CONFIRMATION;
          hint.hidden = confirmed;
          return !confirmed;
        } },
      ],
      onClose: (value) => resolve(value === 'confirm'),
    });
  });
}

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
    h('header', { class: 'page-header' }, h('div', null, h('h1', null, 'Configurações'), h('p', { class: 'muted' }, 'Sessão, preferências, privacidade e seus dados.'))),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('lock'), 'Sessão'),
      h('dl', { class: 'kv' },
        h('dt', null, 'Acesso'), h('dd', null, 'Sem conta nem senha: cada janela aberta pelo DocGen recebe um acesso de uso único.'),
        h('dt', null, 'Sessão'), h('dd', null, 'Guardada só nesta aba. Termina após 12 horas sem uso ou quando o DocGen é fechado.'),
        h('dt', null, 'Encerramento'), h('dd', null, 'Sem nenhuma janela aberta por 10 minutos, o DocGen se encerra sozinho, mas nunca durante uma geração.')),
      button('Sair do DocGen', { iconName: 'logout', variant: 'danger', onClick: () => ctx.quit() })),
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
        h('li', null, 'O DocGen escuta apenas em 127.0.0.1 e exige a sessão desta janela em todas as rotas de dados.'),
        h('li', null, 'A IA roda só no Ollama deste computador (127.0.0.1). Nenhum conteúdo é enviado para serviços externos.'),
        h('li', null, 'PDFs são processados em área privada e removidos logo após a extração; apenas o texto redigido é guardado, por até 30 dias.'),
        h('li', null, 'Resultados da IA são propostas: revise antes de usar. Nada é apresentado como implementado.'))),
    h('section', { class: 'card card-padded settings-section' },
      h('h2', null, icon('trash'), 'Seus dados'),
      h('p', { class: 'muted' }, 'Tudo fica num diretório privado deste computador. Você pode apagar tudo a qualquer momento.'),
      button('Apagar todos os meus dados', { iconName: 'trash', variant: 'danger', onClick: async () => {
        if (!await confirmErase()) return;
        try {
          await ctx.api.eraseAllData();
          toast('Seus dados foram apagados. O arquivo do banco é compactado quando o DocGen for fechado.', { tone: 'success' });
        } catch (failure) {
          toast(errorMessage(failure), { tone: 'error' });
        }
      } }))));

  try {
    const health = await ctx.api.health();
    replace(server,
      h('dt', null, 'API'), h('dd', null, health.status === 'available' ? 'Disponível' : health.status),
      h('dt', null, 'Ollama'), h('dd', null, 'Local · 127.0.0.1'));
  } catch (failure) {
    replace(server, h('dt', null, 'API'), h('dd', null, errorMessage(failure)));
  }
}
