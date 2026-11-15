// Pure helpers for the model screens: labels, progress math and the checks
// the interface makes before a download. No DOM and no network here.
import { formatBytes, formatNumber } from './format.js';

export const NOT_DETECTED = 'não detectado';

export const FIT = {
  RECOMMENDED: { label: 'Recomendado', tone: 'success', icon: 'check' },
  GPU_OFFLOAD: { label: 'Funciona com offload', tone: 'info', icon: 'layers' },
  CPU_ONLY: { label: 'Só CPU', tone: 'warning', icon: 'cpu' },
  NOT_RECOMMENDED: { label: 'Não recomendado', tone: 'danger', icon: 'alert' },
};

export const PURPOSE = { general: 'Uso geral', code: 'Código', reasoning: 'Raciocínio' };

export const STAGE = {
  MANIFEST: 'Preparando o download',
  DOWNLOADING: 'Baixando',
  VERIFYING: 'Conferindo a integridade dos arquivos',
  WRITING: 'Finalizando',
  CLEANUP: 'Removendo arquivos antigos',
  SUCCESS: 'Concluído',
  OTHER: 'Em andamento',
};

export const PULL_STATE = { RUNNING: 'Baixando', SUCCEEDED: 'Concluído', FAILED: 'Falhou', CANCELLED: 'Cancelado' };

export const GPU_SOURCE_NOTE = {
  'driver da placa': 'medido pelo driver da placa',
  'nvidia-smi': 'medido pelo nvidia-smi',
  'memória unificada': 'memória unificada com o sistema (Apple Silicon)',
  'sistema, não confirmado': 'informado pelo sistema, sem confirmação do driver',
};

/** A value from the diagnosis, or "não detectado"; never a guess. */
export function detected(value, format = String) {
  return value === null || value === undefined || value === '' ? NOT_DETECTED : format(value);
}

/** "8,2 bilhões" / "752 milhões" of parameters. */
export function formatParameters(count) {
  if (!Number.isFinite(count) || count <= 0) return '—';
  if (count >= 1e9) return `${(count / 1e9).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} bilhões`;
  return `${formatNumber(Math.round(count / 1e6))} milhões`;
}

export function formatTokens(tokens) {
  if (!Number.isFinite(tokens) || tokens <= 0) return '—';
  return tokens >= 1024 ? `${formatNumber(Math.round(tokens / 1024))} mil tokens` : `${formatNumber(tokens)} tokens`;
}

/** Percent of a download, from Ollama's layer counts (or the catalog size while they are unknown). */
export function pullPercent(pull) {
  if (!pull) return null;
  if (pull.state === 'SUCCEEDED') return 100;
  const total = pull.totalBytes > 0 ? pull.totalBytes : pull.expectedBytes;
  if (!(total > 0) || !(pull.completedBytes >= 0)) return null;
  return Math.min(100, Math.floor((pull.completedBytes * 100) / total));
}

export function formatSpeed(bytesPerSecond) {
  return bytesPerSecond > 0 ? `${formatBytes(bytesPerSecond)}/s` : '—';
}

/** Rough time left at the current speed; null while it cannot be told. */
export function formatRemaining(pull) {
  if (!pull || pull.state !== 'RUNNING' || !(pull.bytesPerSecond > 0)) return null;
  const total = pull.totalBytes > 0 ? pull.totalBytes : pull.expectedBytes;
  const seconds = Math.max(0, (total - pull.completedBytes) / pull.bytesPerSecond);
  if (seconds < 60) return 'menos de 1 minuto';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `cerca de ${minutes} ${minutes === 1 ? 'minuto' : 'minutos'}`;
  const hours = Math.floor(minutes / 60);
  return `cerca de ${hours} h ${minutes % 60} min`;
}

/**
 * What the screen says about disk space before a download: blocked when it
 * clearly does not fit, a warning when it is tight or unknown.
 */
export function diskCheck(model, storage) {
  const free = storage?.freeBytes;
  if (!Number.isFinite(free)) {
    return { blocked: false, warning: 'Não foi possível medir o espaço livre em disco. Confira se há pelo menos ' + formatBytes(model.downloadBytes) + ' livres.' };
  }
  if (free < model.downloadBytes) {
    return { blocked: true, warning: `Espaço insuficiente: o modelo precisa de cerca de ${formatBytes(model.downloadBytes)} e há ${formatBytes(free)} livres.` };
  }
  if (free < model.downloadBytes * 1.2) {
    return { blocked: false, warning: `O disco vai ficar quase cheio: há ${formatBytes(free)} livres para um modelo de cerca de ${formatBytes(model.downloadBytes)}.` };
  }
  return { blocked: false, warning: storage.exact ? null : 'A pasta de modelos do Ollama não foi encontrada; o espaço livre mostrado é o da sua pasta pessoal.' };
}

/** The catalog with the suggested models first, then by class (best first), keeping catalog order inside. */
export function orderCatalog(catalog) {
  const rank = { RECOMMENDED: 0, GPU_OFFLOAD: 1, CPU_ONLY: 2, NOT_RECOMMENDED: 3 };
  const suggested = new Set([catalog.suggestedGeneral, catalog.suggestedCode].filter(Boolean));
  return catalog.models
    .map((model, index) => ({ model, index }))
    .sort((a, b) => Number(suggested.has(b.model.tag)) - Number(suggested.has(a.model.tag))
      || (rank[a.model.fit] ?? 9) - (rank[b.model.fit] ?? 9) || a.index - b.index)
    .map(({ model }) => model);
}

/** How the loaded model sits in memory, as Ollama's "ollama ps" puts it. */
export function memorySplit(loaded) {
  if (!loaded || !(loaded.sizeBytes > 0)) return null;
  const gpu = Math.round((loaded.vramBytes * 100) / loaded.sizeBytes);
  if (gpu >= 100) return '100% na GPU';
  if (gpu <= 0) return '100% na CPU';
  return `${gpu}% na GPU, ${100 - gpu}% na CPU`;
}

/** The operating system family, from the diagnosis, for install instructions. */
export function osFamily(osName) {
  const name = String(osName || '').toLowerCase();
  if (name.includes('windows')) return 'windows';
  if (name.includes('mac') || name.includes('darwin')) return 'macos';
  return 'linux';
}

export const OLLAMA_INSTALL_STEPS = {
  windows: ['Baixe o instalador do Ollama para Windows no site oficial.', 'Execute o instalador e siga as etapas.', 'Abra o Ollama pelo menu Iniciar. Ele fica rodando perto do relógio.'],
  macos: ['Baixe o Ollama para macOS no site oficial.', 'Abra o arquivo baixado e arraste o Ollama para Aplicativos.', 'Abra o Ollama. O ícone dele aparece na barra de menus.'],
  linux: ['Abra a página oficial de download do Ollama para Linux.', 'Instale pelo método indicado lá ou pelo gerenciador de pacotes da sua distribuição.', 'Garanta que o serviço do Ollama esteja rodando e volte aqui.'],
};
