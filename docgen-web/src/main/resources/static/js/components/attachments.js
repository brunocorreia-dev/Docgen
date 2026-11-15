// PDF attachments: drag-and-drop or keyboard file picker, client-side checks,
// upload states and the extracted-text excerpt for verification.
import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { formatBytes, formatDate, formatNumber } from '../format.js';
import { errorMessage } from '../messages.js';
import { LIMITS, validatePdf } from '../validation.js';
import { confirmDialog, emptyState, toast } from '../ui.js';

export function attachmentsPanel({ api, projectId, attachments, onChange }) {
  const list = h('ul', { class: 'attachment-list' });
  const status = h('div', { class: 'upload-status', 'aria-live': 'polite' });
  const input = h('input', { type: 'file', accept: 'application/pdf,.pdf', class: 'visually-hidden', id: 'pdf-input', multiple: true });
  let items = [...attachments];

  const zone = h('div', {
    class: 'dropzone',
    role: 'button',
    tabindex: '0',
    'aria-label': 'Anexar PDF: arraste arquivos ou pressione Enter para escolher',
    on: {
      click: () => input.click(),
      keydown: (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          input.click();
        }
      },
      dragenter: (event) => { event.preventDefault(); zone.classList.add('is-dragging'); },
      dragover: (event) => { event.preventDefault(); zone.classList.add('is-dragging'); },
      dragleave: () => zone.classList.remove('is-dragging'),
      drop: (event) => {
        event.preventDefault();
        zone.classList.remove('is-dragging');
        uploadAll([...event.dataTransfer.files]);
      },
    },
  },
  h('div', { class: 'dropzone-icon' }, icon('upload', { size: 22 })),
  h('p', null, h('strong', null, 'Arraste PDFs aqui'), ' ou clique para escolher'),
  h('small', { class: 'muted' }, `Até ${LIMITS.attachments} arquivos de 10 MB. PDFs digitalizados (sem texto) e protegidos por senha não são suportados.`));
  input.addEventListener('change', () => {
    uploadAll([...input.files]);
    input.value = '';
  });

  async function uploadAll(files) {
    for (const file of files) {
      // Sequential uploads keep server load and error reporting predictable.
      // eslint-disable-next-line no-await-in-loop
      await upload(file);
    }
  }

  async function upload(file) {
    const row = h('div', { class: 'upload-row' },
      icon('file'), h('span', { class: 'upload-name' }, file.name), h('span', { class: 'muted' }, formatBytes(file.size)),
      h('span', { class: 'upload-phase' }, 'Validando…'), h('div', { class: 'progress progress-indeterminate' }));
    status.append(row);
    const phase = row.querySelector('.upload-phase');
    const problem = validatePdf(file);
    if (problem) {
      fail(row, problem);
      return;
    }
    if (items.length >= LIMITS.attachments) {
      fail(row, 'Este projeto já atingiu o limite de anexos.');
      return;
    }
    phase.textContent = 'Enviando e extraindo texto…';
    try {
      const attachment = await api.uploadAttachment(projectId, file);
      items = [...items, attachment];
      renderList();
      row.classList.add('is-done');
      phase.textContent = `${formatNumber(attachment.characterCount)} caracteres extraídos`;
      row.querySelector('.progress')?.remove();
      setTimeout(() => row.remove(), 2500);
      toast(`"${attachment.displayName}" anexado.`, { tone: 'success' });
      onChange?.(items);
    } catch (failure) {
      fail(row, errorMessage(failure));
    }
  }

  function fail(row, message) {
    row.classList.add('is-error');
    row.querySelector('.upload-phase').textContent = message;
    row.querySelector('.progress')?.remove();
    row.append(h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Dispensar', on: { click: () => row.remove() } }, icon('x', { size: 15 })));
  }

  function renderList() {
    if (!items.length) {
      replace(list, h('li', { class: 'attachment-empty muted' }, 'Nenhum PDF anexado.'));
      return;
    }
    replace(list, ...items.map((attachment) => h('li', { class: 'attachment' },
      h('div', { class: 'attachment-head' },
        h('span', { class: 'attachment-icon' }, icon('file')),
        h('div', { class: 'attachment-text' },
          h('strong', null, attachment.displayName),
          h('small', { class: 'muted' }, [formatBytes(attachment.sizeBytes), `${attachment.pageCount} pág.`,
            `${formatNumber(attachment.characterCount)} caracteres`, `expira em ${formatDate(attachment.expiresAt)}`].join(' · '))),
        attachment.truncated ? h('span', { class: 'badge badge-warning' }, 'Texto truncado') : null,
        h('button', { class: 'icon-button', type: 'button', 'aria-label': `Remover ${attachment.displayName}`, on: { click: async () => {
          if (!await confirmDialog({ title: 'Remover anexo', message: `Remover "${attachment.displayName}" e o texto extraído dele?`, confirmLabel: 'Remover', danger: true })) return;
          try {
            await api.deleteAttachment(projectId, attachment.id);
            items = items.filter((item) => item.id !== attachment.id);
            renderList();
            onChange?.(items);
          } catch (failure) {
            toast(errorMessage(failure), { tone: 'error' });
          }
        } } }, icon('trash', { size: 16 }))),
      h('details', { class: 'attachment-excerpt' },
        h('summary', null, 'Ver trecho extraído'),
        h('p', null, attachment.excerpt || '—')))));
  }

  renderList();
  return h('div', { class: 'attachments' }, zone, input, status, list);
}

export function noAttachments() {
  return emptyState({ iconName: 'upload', title: 'Sem anexos', message: 'Anexe PDFs de especificação para enriquecer o plano.' });
}
