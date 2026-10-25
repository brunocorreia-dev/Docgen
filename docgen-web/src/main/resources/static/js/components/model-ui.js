// Pieces shared by the first-run guide and the Models screen: the hardware
// diagnosis, Ollama's status, catalog cards and the download progress.
import { h, replace } from '../dom.js';
import { formatBytes } from '../format.js';
import { icon } from '../icons.js';
import { externalLink, OLLAMA_DOWNLOAD_URL, OLLAMA_LOCAL_ONLY_URL } from '../links.js';
import { errorMessage, pullErrorMessage } from '../messages.js';
import {
  detected, diskCheck, FIT, formatParameters, formatRemaining, formatSpeed, formatTokens, GPU_SOURCE_NOTE,
  OLLAMA_INSTALL_STEPS, osFamily, PULL_STATE, PURPOSE, pullPercent, STAGE,
} from '../models.js';
import { watchPull } from '../pulls.js';
import { badge, button, openDialog } from '../ui.js';

export function fitBadge(fit) {
  const meta = FIT[fit] || FIT.NOT_RECOMMENDED;
  return badge(meta.label, meta.tone, meta.icon);
}

/** The diagnosis as a definition list; everything unknown reads "não detectado". */
export function hardwareSummary(hardware) {
  const gpus = hardware.gpus || [];
  const storage = hardware.modelStorage || {};
  const rows = [
    ['Sistema', `${detected(hardware.osName)} · ${detected(hardware.osVersion)} · ${detected(hardware.architecture)}`],
    ['Processador', `${detected(hardware.cpuModel)} (${detected(hardware.cpuCores)} núcleos, ${detected(hardware.cpuThreads)} threads)`],
    ['Memória RAM', `${detected(hardware.ramTotalBytes, formatBytes)} no total · ${detected(hardware.ramAvailableBytes, formatBytes)} disponíveis agora`],
  ];
  if (!gpus.length) {
    rows.push(['Placa de vídeo', 'não detectada']);
  }
  gpus.forEach((gpu, index) => {
    const memory = hardware.unifiedMemory && gpu.source === 'memória unificada'
      ? 'usa a memória RAM do sistema (memória unificada)'
      : `${detected(gpu.vramTotalBytes, formatBytes)} de memória de vídeo · ${detected(gpu.vramFreeBytes, formatBytes)} livres`;
    const source = gpu.source ? ` (${GPU_SOURCE_NOTE[gpu.source] || gpu.source})` : '';
    rows.push([gpus.length > 1 ? `Placa de vídeo ${index + 1}` : 'Placa de vídeo', `${detected(gpu.name)} · ${memory}${source}`]);
  });
  rows.push(['Disco para modelos', storage.directory
    ? `${detected(storage.freeBytes, formatBytes)} livres em ${storage.directory}`
    : `${detected(storage.freeBytes, formatBytes)} livres na sua pasta pessoal (pasta de modelos do Ollama não encontrada)`]);
  return h('dl', { class: 'kv hardware-kv' }, rows.flatMap(([label, value]) => [h('dt', null, label), h('dd', null, value)]));
}

/** Ollama is not answering: friendly, per-system instructions and the official download page. */
export function ollamaHelp({ osName, onRetry }) {
  const steps = OLLAMA_INSTALL_STEPS[osFamily(osName)];
  return h('div', { class: 'ollama-help', role: 'alert' },
    h('div', { class: 'callout callout-warning' }, icon('alert'),
      h('div', null,
        h('strong', null, 'O Ollama não está rodando neste computador.'),
        h('p', null, 'O DocGen usa o Ollama, um programa gratuito, para rodar os modelos de IA aqui mesmo. Se ele já está instalado, basta abri-lo. Se não, instale assim:'))),
    h('ol', { class: 'help-steps' }, steps.map((step) => h('li', null, step))),
    h('p', { class: 'help-actions' }, externalLink('Abrir a página oficial de download do Ollama', OLLAMA_DOWNLOAD_URL)),
    h('p', { class: 'muted small' }, 'O DocGen não instala nem baixa o Ollama sozinho. Depois de instalar e abrir o Ollama, clique em "Tentar novamente".'),
    button('Tentar novamente', { variant: 'primary', iconName: 'refresh', onClick: onRetry }));
}

/** Ollama answers; recommends its local-only mode, citing the official page. */
export function ollamaReady(status) {
  return h('div', { class: 'ollama-ready' },
    h('p', { class: 'ready-line' }, icon('check'), h('span', null, `Ollama ${status.version} rodando em ${status.address}.`)),
    status.version !== status.testedVersion
      ? h('p', { class: 'muted small' }, `O DocGen foi testado com o Ollama ${status.testedVersion}.`)
      : null,
    h('div', { class: 'callout callout-info' }, icon('shield'),
      h('div', null,
        h('strong', null, 'Recomendado: deixe o Ollama só no modo local.'),
        h('p', null, 'O Ollama tem modelos que rodam na nuvem dele. O DocGen já recusa esses modelos, mas você pode desligar esse recurso no próprio Ollama, para todos os programas. ',
          externalLink('Veja como na documentação oficial', OLLAMA_LOCAL_ONLY_URL)))));
}

/** A catalog model with its class for this computer, the reason and what it needs. */
export function catalogCard(model, { suggestedFor = [], selected = false, onSelect, actions = [] } = {}) {
  const purposes = model.purposes.map((purpose) => PURPOSE[purpose] || purpose).join(' · ');
  const card = h('article', {
    class: ['model-card', `fit-${String(model.fit).toLowerCase()}`, suggestedFor.length && 'is-suggested', selected && 'is-selected'],
    'aria-label': `Modelo ${model.tag}`,
  },
  h('header', { class: 'model-card-head' },
    h('div', null,
      h('h3', { class: 'model-tag' }, model.tag),
      h('p', { class: 'muted small' }, `${purposes} · ${formatParameters(model.parameterCount)} de parâmetros`
        + (model.activeParameterCount < model.parameterCount ? ` (${formatParameters(model.activeParameterCount)} ativos)` : ''))),
    h('div', { class: 'model-badges' },
      suggestedFor.map((label) => badge(label, 'purple', 'star')),
      fitBadge(model.fit),
      model.installed ? badge('Instalado', 'neutral', 'check') : null)),
  h('p', { class: 'model-description' }, model.description),
  h('p', { class: 'model-reason' }, model.reason),
  h('dl', { class: 'model-meta' },
    h('dt', null, 'Download'), h('dd', null, model.downloadSize),
    h('dt', null, 'Memória estimada'), h('dd', null, `${formatBytes(model.estimate.totalBytes)} com ${formatTokens(model.estimate.contextTokens)} de contexto`),
    h('dt', null, 'Contexto máximo'), h('dd', null, formatTokens(model.contextTokens)),
    h('dt', null, 'Quantização'), h('dd', null, model.quantization)),
  model.verified ? null : h('p', { class: 'muted small' }, 'Dados deste modelo ainda não verificados.'),
  onSelect || actions.length ? h('footer', { class: 'model-card-actions' },
    onSelect ? button(selected ? 'Escolhido' : 'Escolher', { variant: selected ? 'primary' : 'ghost', iconName: selected ? 'check' : null, small: true, onClick: () => onSelect(model) }) : null,
    actions) : null);
  return card;
}

/**
 * The explicit click before a download (ADR 0003): what is downloaded, from
 * where, how big, the free disk, and a clear warning for a model this
 * computer should not run. Resolves to true only on confirmation.
 */
export function confirmDownload(model, storage) {
  const disk = diskCheck(model, storage);
  const risky = model.fit === 'NOT_RECOMMENDED';
  const understood = h('input', { type: 'checkbox', id: 'download-risk' });
  const hint = h('p', { class: 'form-alert', role: 'alert', hidden: true }, 'Confirme que entendeu o aviso para continuar.');
  return new Promise((resolve) => {
    openDialog({
      title: `Baixar ${model.tag}?`,
      description: 'Esta é a única etapa do DocGen que usa a internet.',
      body: h('div', { class: 'download-confirm' },
        h('ul', { class: 'bullet-list' },
          h('li', null, 'Quem baixa é o próprio Ollama, da biblioteca oficial dele (registry.ollama.ai), que entrega os arquivos por uma rede de distribuição de conteúdo.'),
          h('li', null, `Tamanho aproximado: ${model.downloadSize}.`),
          h('li', null, `Espaço livre: ${detected(storage?.freeBytes, formatBytes)}.`),
          h('li', null, 'Depois de baixado, o modelo funciona sem internet. Nenhuma conversa é enviada.')),
        disk.warning ? h('p', { class: disk.blocked ? 'form-alert' : 'callout callout-warning' }, disk.warning) : null,
        risky ? h('div', { class: 'callout callout-warning' }, icon('alert'),
          h('div', null, h('strong', null, 'Este modelo não é recomendado para este computador.'), h('p', null, model.reason))) : null,
        risky ? h('label', { class: 'check-row', for: 'download-risk' }, understood,
          h('span', null, 'Entendo que ele pode deixar o computador lento ou nem abrir, e quero baixar mesmo assim.')) : null,
        risky ? hint : null),
      actions: [
        { label: 'Cancelar', value: 'cancel' },
        disk.blocked ? null : { label: 'Baixar', value: 'confirm', variant: risky ? 'danger' : 'primary', icon: 'download', onClick: () => {
          if (risky && !understood.checked) {
            hint.hidden = false;
            return true;
          }
          return false;
        } },
      ].filter(Boolean),
      onClose: (value) => resolve(value === 'confirm'),
    });
  });
}

/**
 * Live progress of a download: Ollama's stage, bytes, speed and time left,
 * with "Cancelar". Resolves `done` with the final snapshot.
 */
export function pullProgress({ api, pull, signal, onFinished }) {
  const fill = h('span', { class: 'progress-fill' });
  const bar = h('div', { class: 'progress progress-lg', role: 'progressbar', 'aria-valuemin': '0', 'aria-valuemax': '100', 'aria-label': `Download de ${pull.model}` }, fill);
  const stage = h('p', { class: 'pull-stage' });
  const numbers = h('p', { class: 'pull-numbers muted small', 'aria-live': 'polite' });
  const cancel = button('Cancelar download', { iconName: 'x', small: true, onClick: async () => {
    cancel.disabled = true;
    try {
      await api.cancelPull(pull.id);
    } catch (failure) {
      cancel.disabled = false;
      numbers.textContent = errorMessage(failure);
    }
  } });
  const element = h('div', { class: 'pull-panel' }, h('div', { class: 'pull-head' }, icon('download'), h('strong', null, pull.model), cancel), stage, bar, numbers);

  function render(view) {
    const percent = pullPercent(view);
    bar.classList.toggle('progress-indeterminate', percent === null);
    fill.style.setProperty('width', `${percent ?? 0}%`);
    if (percent === null) bar.removeAttribute('aria-valuenow');
    else bar.setAttribute('aria-valuenow', String(percent));
    stage.textContent = view.state === 'RUNNING' ? `${STAGE[view.stage] || STAGE.OTHER}…` : PULL_STATE[view.state] || view.state;
    const total = view.totalBytes > 0 ? view.totalBytes : view.expectedBytes;
    const remaining = formatRemaining(view);
    replace(numbers, view.state === 'FAILED'
      ? pullErrorMessage(view.error)
      : view.state === 'CANCELLED'
        ? 'Download cancelado. O Ollama guarda o que já baixou e continua de onde parou na próxima vez.'
        : `${formatBytes(view.completedBytes)} de ${formatBytes(total)}${percent === null ? '' : ` (${percent}%)`} · ${formatSpeed(view.bytesPerSecond)}${remaining ? ` · ${remaining} restantes` : ''}`);
    element.classList.toggle('is-error', view.state === 'FAILED');
    element.classList.toggle('is-done', view.state === 'SUCCEEDED');
    cancel.hidden = view.state !== 'RUNNING';
  }

  render(pull);
  const done = watchPull({ api, id: pull.id, signal, onUpdate: render })
    .then((view) => {
      onFinished?.(view);
      return view;
    });
  return { element, done };
}
