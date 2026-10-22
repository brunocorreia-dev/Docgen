// Shared interface pieces: toasts, accessible dialogs, buttons and states.
import { h, clear, append } from './dom.js';
import { icon } from './icons.js';

let toastRegion = null;

export function toast(message, { tone = 'info', timeout = 4500 } = {}) {
  if (!toastRegion) {
    toastRegion = h('div', { class: 'toast-region', role: 'status', 'aria-live': 'polite' });
    document.body.append(toastRegion);
  }
  const iconName = tone === 'success' ? 'check' : tone === 'error' ? 'alert' : 'info';
  const element = h('div', { class: ['toast', `toast-${tone}`] }, icon(iconName), h('span', null, message),
    h('button', { class: 'icon-button toast-close', type: 'button', 'aria-label': 'Fechar notificação', on: { click: () => dismiss() } }, icon('x', { size: 16 })));
  toastRegion.append(element);
  const timer = setTimeout(dismiss, timeout);
  function dismiss() {
    clearTimeout(timer);
    element.classList.add('is-leaving');
    setTimeout(() => element.remove(), 220);
  }
}

/** Modal built on <dialog>: focus is trapped by the browser and Escape closes it. */
export function openDialog({ title, description, body, actions = [], wide = false, onClose }) {
  const dialog = h('dialog', { class: ['dialog', wide && 'dialog-wide'], 'aria-labelledby': 'dialog-title' });
  const close = (value) => {
    dialog.close(value ?? '');
  };
  dialog.addEventListener('close', () => {
    onClose?.(dialog.returnValue);
    dialog.remove();
  });
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) close('dismiss');
  });
  append(dialog, h('div', { class: 'dialog-card' },
    h('header', { class: 'dialog-header' },
      h('h2', { id: 'dialog-title' }, title),
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Fechar', on: { click: () => close('dismiss') } }, icon('x'))),
    description ? h('p', { class: 'dialog-description' }, description) : null,
    h('div', { class: 'dialog-body' }, body),
    actions.length ? h('footer', { class: 'dialog-actions' }, actions.map((action) => h('button', {
      class: ['button', action.variant ? `button-${action.variant}` : 'button-ghost'],
      type: 'button',
      on: { click: async () => {
        if (action.onClick) {
          const keepOpen = await action.onClick();
          if (keepOpen === true) return;
        }
        close(action.value);
      } },
    }, action.icon ? icon(action.icon) : null, action.label))) : null));
  document.body.append(dialog);
  dialog.showModal();
  return { dialog, close };
}

export function confirmDialog({ title, message, confirmLabel = 'Confirmar', danger = false }) {
  return new Promise((resolve) => {
    openDialog({
      title,
      body: h('p', null, message),
      actions: [
        { label: 'Cancelar', value: 'cancel' },
        { label: confirmLabel, value: 'confirm', variant: danger ? 'danger' : 'primary' },
      ],
      onClose: (value) => resolve(value === 'confirm'),
    });
  });
}

export function button(label, { variant = 'ghost', iconName, onClick, type = 'button', disabled = false, title, small = false } = {}) {
  return h('button', {
    class: ['button', `button-${variant}`, small && 'button-small'],
    type,
    disabled,
    title,
    on: onClick ? { click: onClick } : null,
  }, iconName ? icon(iconName, { size: small ? 15 : 17 }) : null, label ? h('span', null, label) : null);
}

/** Puts a button into a busy state while an async action runs. */
export async function withBusy(buttonElement, action, busyLabel) {
  const label = buttonElement.querySelector('span');
  const original = label ? label.textContent : '';
  buttonElement.disabled = true;
  buttonElement.classList.add('is-busy');
  if (label && busyLabel) label.textContent = busyLabel;
  try {
    return await action();
  } finally {
    buttonElement.disabled = false;
    buttonElement.classList.remove('is-busy');
    if (label) label.textContent = original;
  }
}

export function spinner(label = 'Carregando') {
  return h('span', { class: 'spinner', role: 'status', 'aria-label': label });
}

export function loadingState(label = 'Carregando…') {
  return h('div', { class: 'state state-loading' }, spinner(label), h('p', null, label));
}

export function skeleton(lines = 3) {
  return h('div', { class: 'skeleton-group', 'aria-hidden': 'true' },
    Array.from({ length: lines }, (_, index) => h('div', { class: 'skeleton', style: { width: `${92 - index * 14}%` } })));
}

export function emptyState({ iconName = 'sparkles', title, message, action }) {
  return h('div', { class: 'state state-empty' },
    h('div', { class: 'state-icon' }, icon(iconName, { size: 26 })),
    h('h3', null, title),
    message ? h('p', null, message) : null,
    action || null);
}

export function errorState(message, retry) {
  return h('div', { class: 'state state-error', role: 'alert' },
    h('div', { class: 'state-icon' }, icon('alert', { size: 26 })),
    h('h3', null, 'Algo deu errado'),
    h('p', null, message),
    retry ? button('Tentar novamente', { iconName: 'refresh', onClick: retry }) : null);
}

export function badge(text, tone = 'neutral', iconName) {
  return h('span', { class: ['badge', `badge-${tone}`] }, iconName ? icon(iconName, { size: 13 }) : null, text);
}

export function aiNotice(text = 'Conteúdo gerado por IA. Revise antes de usar: nada aqui foi implementado ou validado.') {
  return h('div', { class: 'ai-notice', role: 'note' }, icon('sparkles', { size: 16 }), h('span', null, text));
}

export function downloadBlob(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const anchor = h('a', { href: url, download: fileName, class: 'visually-hidden' });
  document.body.append(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    toast('Copiado para a área de transferência.', { tone: 'success' });
  } catch {
    toast('Não foi possível copiar automaticamente.', { tone: 'error' });
  }
}

export function setView(outlet, ...children) {
  clear(outlet);
  append(outlet, children);
  outlet.classList.remove('view-enter');
  void outlet.offsetWidth;
  outlet.classList.add('view-enter');
}
