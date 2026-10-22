// Safe Markdown preview. The parser produces a small AST that is rendered with
// DOM APIs only: raw HTML is shown as literal text, images are not loaded,
// and link targets pass the shared URL allowlist. This mirrors the passive
// Markdown policy enforced server-side by DocumentationValidator.

import { h } from './dom.js';
import { safeUrl, isExternal } from './url.js';

const FENCE = /^(\s{0,3})(`{3,}|~{3,})\s*([\w+#.-]*)\s*$/;
const HEADING = /^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$/;
const HR = /^\s{0,3}([-*_])(\s*\1){2,}\s*$/;
const BULLET = /^(\s*)([-*+])\s+(.*)$/;
const ORDERED = /^(\s*)(\d{1,9})[.)]\s+(.*)$/;
const QUOTE = /^\s{0,3}>\s?(.*)$/;
const TABLE_SEPARATOR = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;

export function parseMarkdown(source) {
  const lines = String(source ?? '').replace(/\r\n?/g, '\n').split('\n');
  return parseBlocks(lines);
}

function parseBlocks(lines) {
  const blocks = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (line.trim() === '') {
      index += 1;
      continue;
    }
    const fence = FENCE.exec(line);
    if (fence) {
      const marker = fence[2];
      const body = [];
      index += 1;
      while (index < lines.length && !new RegExp(`^\\s{0,3}${marker[0]}{${marker.length},}\\s*$`).test(lines[index])) {
        body.push(lines[index]);
        index += 1;
      }
      index += 1;
      blocks.push({ type: 'code', language: fence[3] || '', text: body.join('\n') });
      continue;
    }
    const heading = HEADING.exec(line);
    if (heading) {
      blocks.push({ type: 'heading', level: heading[1].length, children: parseInline(heading[2]) });
      index += 1;
      continue;
    }
    if (HR.test(line)) {
      blocks.push({ type: 'hr' });
      index += 1;
      continue;
    }
    if (QUOTE.test(line)) {
      const body = [];
      while (index < lines.length && QUOTE.test(lines[index])) {
        body.push(QUOTE.exec(lines[index])[1]);
        index += 1;
      }
      blocks.push({ type: 'blockquote', children: parseBlocks(body) });
      continue;
    }
    if (line.includes('|') && index + 1 < lines.length && TABLE_SEPARATOR.test(lines[index + 1])) {
      const header = splitRow(line);
      const alignments = splitRow(lines[index + 1]).map((cell) => {
        const left = cell.startsWith(':');
        const right = cell.endsWith(':');
        return left && right ? 'center' : right ? 'right' : left ? 'left' : null;
      });
      index += 2;
      const rows = [];
      while (index < lines.length && lines[index].includes('|') && lines[index].trim() !== '') {
        rows.push(splitRow(lines[index]).map(parseInline));
        index += 1;
      }
      blocks.push({ type: 'table', header: header.map(parseInline), alignments, rows });
      continue;
    }
    if (BULLET.test(line) || ORDERED.test(line)) {
      const result = parseList(lines, index);
      blocks.push(result.block);
      index = result.next;
      continue;
    }
    const paragraph = [];
    while (index < lines.length && lines[index].trim() !== '' && !startsBlock(lines, index)) {
      paragraph.push(lines[index].trim());
      index += 1;
    }
    blocks.push({ type: 'paragraph', children: parseInline(paragraph.join('\n')) });
  }
  return blocks;
}

function startsBlock(lines, index) {
  const line = lines[index];
  return FENCE.test(line) || HEADING.test(line) || HR.test(line) || QUOTE.test(line)
    || BULLET.test(line) || ORDERED.test(line)
    || (line.includes('|') && index + 1 < lines.length && TABLE_SEPARATOR.test(lines[index + 1]));
}

function parseList(lines, start) {
  const first = BULLET.exec(lines[start]) || ORDERED.exec(lines[start]);
  const ordered = !BULLET.test(lines[start]);
  const indent = first[1].length;
  const items = [];
  let index = start;
  while (index < lines.length) {
    const line = lines[index];
    const match = ordered ? ORDERED.exec(line) : BULLET.exec(line);
    if (!match || match[1].length !== indent) break;
    const content = [match[3]];
    index += 1;
    const nested = [];
    while (index < lines.length && lines[index].trim() !== '') {
      const next = lines[index];
      const leading = next.length - next.trimStart().length;
      if (leading > indent && (BULLET.test(next) || ORDERED.test(next))) {
        nested.push(next);
      } else if (leading > indent && nested.length === 0) {
        content.push(next.trim());
      } else if (leading > indent) {
        nested.push(next);
      } else {
        break;
      }
      index += 1;
    }
    const item = { children: parseInline(content.join('\n')), sublist: null };
    if (nested.length) {
      const sub = parseList(nested, 0);
      item.sublist = sub.block;
    }
    items.push(item);
    while (index < lines.length && lines[index].trim() === '' && index + 1 < lines.length) {
      const peek = lines[index + 1];
      const peekMatch = ordered ? ORDERED.exec(peek) : BULLET.exec(peek);
      if (peekMatch && peekMatch[1].length === indent) index += 1;
      else break;
    }
  }
  return { block: { type: 'list', ordered, start: ordered ? Number(first[2]) : 1, items }, next: index };
}

function splitRow(line) {
  let row = line.trim();
  if (row.startsWith('|')) row = row.slice(1);
  if (row.endsWith('|') && !row.endsWith('\\|')) row = row.slice(0, -1);
  const cells = [];
  let current = '';
  for (let i = 0; i < row.length; i += 1) {
    if (row[i] === '\\' && row[i + 1] === '|') {
      current += '|';
      i += 1;
    } else if (row[i] === '|') {
      cells.push(current.trim());
      current = '';
    } else {
      current += row[i];
    }
  }
  cells.push(current.trim());
  return cells;
}

/** Inline grammar: code spans, links, strong, emphasis, strikethrough, escapes and line breaks. */
export function parseInline(text) {
  const nodes = [];
  let buffer = '';
  const flush = () => {
    if (buffer) {
      nodes.push({ type: 'text', value: buffer });
      buffer = '';
    }
  };
  let i = 0;
  while (i < text.length) {
    const char = text[i];
    if (char === '\\' && i + 1 < text.length && /[\\`*_{}[\]()#+\-.!|~<>]/.test(text[i + 1])) {
      buffer += text[i + 1];
      i += 2;
      continue;
    }
    if (char === '\n') {
      flush();
      nodes.push({ type: 'break' });
      i += 1;
      continue;
    }
    if (char === '`') {
      let run = 1;
      while (text[i + run] === '`') run += 1;
      const marker = '`'.repeat(run);
      const end = text.indexOf(marker, i + run);
      if (end > 0) {
        flush();
        nodes.push({ type: 'code', value: text.slice(i + run, end).replace(/^ (.*) $/, '$1') });
        i = end + run;
        continue;
      }
    }
    if (char === '!' && text[i + 1] === '[') {
      // Images are never loaded: render the alt text only.
      const link = matchLink(text, i + 1);
      if (link) {
        flush();
        nodes.push({ type: 'text', value: `[imagem: ${link.label}]` });
        i = link.end;
        continue;
      }
    }
    if (char === '[') {
      const link = matchLink(text, i);
      if (link) {
        flush();
        nodes.push({ type: 'link', href: link.href, children: parseInline(link.label) });
        i = link.end;
        continue;
      }
    }
    const delimiter = ['**', '__', '~~'].find((d) => text.startsWith(d, i));
    if (delimiter) {
      const end = text.indexOf(delimiter, i + 2);
      if (end > i + 2) {
        flush();
        nodes.push({ type: delimiter === '~~' ? 'del' : 'strong', children: parseInline(text.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }
    if ((char === '*' || char === '_') && text[i + 1] && text[i + 1] !== ' ') {
      const end = text.indexOf(char, i + 1);
      const wordInner = char === '_' && /\w/.test(text[i - 1] || '');
      if (end > i + 1 && text[end - 1] !== ' ' && !wordInner) {
        flush();
        nodes.push({ type: 'em', children: parseInline(text.slice(i + 1, end)) });
        i = end + 1;
        continue;
      }
    }
    buffer += char;
    i += 1;
  }
  flush();
  return nodes;
}

function matchLink(text, start) {
  let depth = 0;
  let close = -1;
  for (let i = start; i < text.length; i += 1) {
    if (text[i] === '\\') {
      i += 1;
      continue;
    }
    if (text[i] === '[') depth += 1;
    if (text[i] === ']') {
      depth -= 1;
      if (depth === 0) {
        close = i;
        break;
      }
    }
  }
  if (close < 0 || text[close + 1] !== '(') return null;
  const end = text.indexOf(')', close + 2);
  if (end < 0) return null;
  const target = text.slice(close + 2, end).trim().split(/\s+/)[0] || '';
  return { label: text.slice(start + 1, close), href: target.replace(/^<|>$/g, ''), end: end + 1 };
}

/** Renders the AST into DOM nodes; headings get stable anchor ids for the outline. */
export function renderMarkdown(source, { idPrefix = 'md' } = {}) {
  const blocks = parseMarkdown(source);
  const usedIds = new Map();
  const outline = [];
  const nodes = blocks.map((block) => renderBlock(block, { idPrefix, usedIds, outline }));
  return { nodes, outline };
}

function renderBlock(block, context) {
  switch (block.type) {
    case 'heading': {
      const title = plainText(block.children);
      const base = `${context.idPrefix}-${slug(title)}`;
      const count = context.usedIds.get(base) || 0;
      context.usedIds.set(base, count + 1);
      const anchor = count ? `${base}-${count}` : base;
      context.outline.push({ level: block.level, title, id: anchor });
      return h(`h${block.level}`, { id: anchor }, renderInline(block.children));
    }
    case 'paragraph':
      return h('p', null, renderInline(block.children));
    case 'code':
      return h('pre', { class: 'md-code', dataset: { language: block.language || 'text' } },
        h('code', null, block.text));
    case 'blockquote':
      return h('blockquote', null, block.children.map((child) => renderBlock(child, context)));
    case 'hr':
      return h('hr');
    case 'list': {
      const items = block.items.map((item) => h('li', null, renderInline(item.children),
        item.sublist ? renderBlock(item.sublist, context) : null));
      return block.ordered ? h('ol', { start: block.start !== 1 ? block.start : null }, items) : h('ul', null, items);
    }
    case 'table':
      return h('div', { class: 'md-table' }, h('table', null,
        h('thead', null, h('tr', null, block.header.map((cell, index) =>
          h('th', { class: block.alignments[index] ? `align-${block.alignments[index]}` : null }, renderInline(cell))))),
        h('tbody', null, block.rows.map((row) => h('tr', null, row.map((cell, index) =>
          h('td', { class: block.alignments[index] ? `align-${block.alignments[index]}` : null }, renderInline(cell))))))));
    default:
      return h('p', null, '');
  }
}

function renderInline(nodes) {
  return nodes.map((node) => {
    switch (node.type) {
      case 'text': return node.value;
      case 'break': return h('br');
      case 'code': return h('code', null, node.value);
      case 'strong': return h('strong', null, renderInline(node.children));
      case 'em': return h('em', null, renderInline(node.children));
      case 'del': return h('del', null, renderInline(node.children));
      case 'link': {
        const target = safeUrl(node.href);
        const external = isExternal(target);
        return h('a', {
          href: target,
          rel: external ? 'noopener noreferrer nofollow' : null,
          target: external ? '_blank' : null,
        }, renderInline(node.children));
      }
      default: return '';
    }
  });
}

export function plainText(nodes) {
  return nodes.map((node) => (node.type === 'text' || node.type === 'code' ? node.value
    : node.type === 'break' ? ' ' : node.children ? plainText(node.children) : '')).join('');
}

function slug(text) {
  return text.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'secao';
}
