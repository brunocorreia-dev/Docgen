// The only external addresses in the app: official Ollama pages the user may
// open in a new tab (ADR 0003, item 6). The app never requests them itself.
import { h } from './dom.js';
import { icon } from './icons.js';

export const OLLAMA_DOWNLOAD_URL = 'https://ollama.com/download';
export const OLLAMA_LOCAL_ONLY_URL = 'https://docs.ollama.com/faq#how-do-i-disable-ollama-cloud-features';

/** A link the user follows on purpose: new tab, no opener and no referrer. */
export function externalLink(label, url) {
  return h('a', { class: 'link', href: url, target: '_blank', rel: 'noopener noreferrer' },
    h('span', null, label), icon('external', { size: 14 }));
}
