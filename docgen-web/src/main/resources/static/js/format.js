// Pure formatting helpers (pt-BR).

export function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toLocaleString('pt-BR', { maximumFractionDigits: value < 10 ? 1 : 0 })} ${units[unit]}`;
}

export function formatNumber(value) {
  return Number.isFinite(value) ? value.toLocaleString('pt-BR') : '—';
}

export function formatDate(iso) {
  const date = iso ? new Date(iso) : null;
  if (!date || Number.isNaN(date.getTime())) return '—';
  return date.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' });
}

export function relativeTime(iso, now = Date.now()) {
  const date = iso ? new Date(iso) : null;
  if (!date || Number.isNaN(date.getTime())) return '—';
  const seconds = Math.round((date.getTime() - now) / 1000);
  const abs = Math.abs(seconds);
  const formatter = new Intl.RelativeTimeFormat('pt-BR', { numeric: 'auto' });
  if (abs < 45) return 'agora mesmo';
  if (abs < 3600) return formatter.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return formatter.format(Math.round(seconds / 3600), 'hour');
  if (abs < 86400 * 30) return formatter.format(Math.round(seconds / 86400), 'day');
  return formatDate(iso);
}

export function truncate(text, max) {
  if (typeof text !== 'string') return '';
  if (text.length <= max) return text;
  return `${text.slice(0, Math.max(0, max - 1)).trimEnd()}…`;
}

/** Ollama is the only provider; other names only appear in records created before DocGen 3. */
export function providerLabel(provider) {
  if (provider === 'ollama') return 'Ollama';
  return typeof provider === 'string' && provider ? provider : '—';
}

export function pluralize(count, singular, plural) {
  return `${formatNumber(count)} ${count === 1 ? singular : plural}`;
}
