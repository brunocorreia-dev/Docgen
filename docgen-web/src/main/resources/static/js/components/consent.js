// Operation-specific consent for remote transmission. The dialog shows the
// server's own preview of the exact payload; confirming returns the single-use
// token bound to that content. Signing in is not consent.
import { h } from '../dom.js';
import { icon } from '../icons.js';
import { formatNumber } from '../format.js';
import { INCLUDED_DATA } from '../messages.js';
import { openDialog } from '../ui.js';

export function requestConsent(preview, operationLabel) {
  return new Promise((resolve) => {
    const checkbox = h('input', { type: 'checkbox', id: 'consent-check' });
    let confirmButton = null;
    checkbox.addEventListener('change', () => {
      if (confirmButton) confirmButton.disabled = !checkbox.checked;
    });
    const { dialog } = openDialog({
      title: 'Autorizar envio para provedor remoto',
      description: `${operationLabel} usa ${preview.provider === 'groq' ? 'Groq' : preview.provider}, um serviço externo. Revise o que será transmitido.`,
      body: h('div', { class: 'consent' },
        h('dl', { class: 'kv' },
          h('dt', null, 'Destino'), h('dd', null, preview.destination),
          h('dt', null, 'Modelo'), h('dd', null, preview.model || 'padrão do provedor'),
          h('dt', null, 'Tamanho'), h('dd', null, `${formatNumber(preview.characterCount)} caracteres`),
          preview.documentCount ? h('dt', null, 'Anexos') : null,
          preview.documentCount ? h('dd', null, `${preview.documentCount} PDF(s)${preview.documentsTrimmed ? ' — texto reduzido para caber no limite' : ''}`) : null),
        h('p', { class: 'consent-heading' }, 'Dados incluídos'),
        h('ul', { class: 'bullet-list' }, preview.includedData.map((item) => h('li', null, INCLUDED_DATA[item] || item))),
        h('p', { class: 'consent-note' }, icon('shield', { size: 15 }),
          'Segredos reconhecidos são substituídos por [REDACTED_SECRET] antes do envio. A redação é heurística: não anexe credenciais.'),
        h('label', { class: 'consent-check', for: 'consent-check' }, checkbox,
          h('span', null, 'Autorizo o envio destes dados para este destino somente nesta operação.'))),
      actions: [
        { label: 'Cancelar', value: 'cancel' },
        { label: 'Autorizar e enviar', value: 'confirm', variant: 'primary', icon: 'cloud' },
      ],
      onClose: (value) => resolve(value === 'confirm' && checkbox.checked),
    });
    confirmButton = dialog.querySelector('.button-primary');
    confirmButton.disabled = true;
  });
}
