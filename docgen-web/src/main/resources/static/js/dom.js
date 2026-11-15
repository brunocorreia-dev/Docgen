// Minimal DOM builder. All text is inserted as Text nodes and attributes are
// set individually, so untrusted strings (AI output, PDF text, user input) are
// never parsed as HTML. innerHTML and friends are deliberately not used
// anywhere in the workspace (a test enforces it).

import { safeUrl } from './url.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const URL_ATTRIBUTES = new Set(['href', 'src', 'action', 'formaction', 'xlink:href']);
const PROPERTIES = new Set(['value', 'checked', 'disabled', 'selected', 'indeterminate', 'multiple']);

export function h(tag, props, ...children) {
  return build(document.createElement(tag), props, children);
}

export function s(tag, props, ...children) {
  return build(document.createElementNS(SVG_NS, tag), props, children);
}

function build(element, props, children) {
  if (props) {
    for (const [key, value] of Object.entries(props)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'on') {
        for (const [event, handler] of Object.entries(value)) element.addEventListener(event, handler);
      } else if (/^on./i.test(key)) {
        throw new Error('Inline event handler attributes are not allowed; use the "on" map.');
      } else if (key === 'class') {
        const names = Array.isArray(value) ? value.filter(Boolean).join(' ') : String(value);
        if (names) element.setAttribute('class', names);
      } else if (key === 'style') {
        // CSSOM property writes are permitted by the style-src 'self' policy.
        for (const [property, styleValue] of Object.entries(value)) {
          element.style.setProperty(property, String(styleValue));
        }
      } else if (key === 'dataset') {
        for (const [name, dataValue] of Object.entries(value)) element.setAttribute(`data-${name}`, String(dataValue));
      } else if (key === 'ref') {
        value(element);
      } else if (key === 'text') {
        element.textContent = String(value);
      } else if (PROPERTIES.has(key)) {
        element[key] = value;
      } else if (URL_ATTRIBUTES.has(key)) {
        element.setAttribute(key, safeUrl(String(value), { allowBlob: key === 'href' }));
      } else {
        element.setAttribute(key, value === true ? '' : String(value));
      }
    }
  }
  append(element, children);
  return element;
}

/** Appends one child or a (nested) array of children, skipping empty values. */
export function append(parent, children) {
  for (const child of flatten([children])) {
    if (child === undefined || child === null || child === false || child === true) continue;
    parent.append(typeof child === 'object' && 'nodeType' in child ? child : document.createTextNode(String(child)));
  }
  return parent;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
  return node;
}

export function replace(node, ...children) {
  clear(node);
  return append(node, children);
}

function flatten(items) {
  const result = [];
  for (const item of items) {
    if (Array.isArray(item)) result.push(...flatten(item));
    else result.push(item);
  }
  return result;
}
