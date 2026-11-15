// URL allowlist shared by the DOM helpers and the Markdown renderer.
// Only navigation-safe schemes survive; everything else becomes an inert "#".

const ALLOWED_SCHEMES = new Set(['http:', 'https:', 'mailto:']);

/**
 * Returns the URL when it is a relative reference, an in-page anchor or uses
 * an allowed scheme; otherwise returns "#". Control and whitespace characters
 * are stripped first so "java\tscript:" style tricks cannot slip through.
 */
export function safeUrl(value, { allowBlob = false } = {}) {
  if (typeof value !== 'string') return '#';
  // eslint-disable-next-line no-control-regex
  const cleaned = value.replace(/[\u0000-\u0020\u007f-\u009f\u200b-\u200f\u2028-\u202e\u2060-\u206f\ufeff]/g, '');
  if (cleaned === '') return '#';
  if (cleaned.startsWith('#')) return cleaned;
  if (cleaned.startsWith('//')) return '#';
  const scheme = /^([a-zA-Z][a-zA-Z0-9+.-]*):/.exec(cleaned);
  if (!scheme) {
    return cleaned.startsWith('/') || cleaned.startsWith('./') || cleaned.startsWith('../') || /^[\w-]/.test(cleaned)
      ? cleaned
      : '#';
  }
  const protocol = scheme[1].toLowerCase() + ':';
  if (ALLOWED_SCHEMES.has(protocol)) return cleaned;
  if (allowBlob && protocol === 'blob:') return cleaned;
  return '#';
}

export function isExternal(url) {
  return /^https?:/i.test(url);
}
