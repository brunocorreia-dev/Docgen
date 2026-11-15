// Minimal DOM double for node:test. It records exactly what the builders do so
// tests can assert that untrusted strings only ever become Text nodes.

class FakeNode {
  constructor(nodeType) {
    this.nodeType = nodeType;
    this.childNodes = [];
    this.parentNode = null;
  }

  get firstChild() { return this.childNodes[0] ?? null; }

  append(...nodes) {
    for (const node of nodes) {
      const child = typeof node === 'string' ? new FakeText(node) : node;
      child.parentNode = this;
      this.childNodes.push(child);
    }
  }

  appendChild(node) { this.append(node); return node; }

  insertBefore(node, reference) {
    const index = this.childNodes.indexOf(reference);
    node.parentNode = this;
    if (index < 0) this.childNodes.push(node);
    else this.childNodes.splice(index, 0, node);
    return node;
  }

  removeChild(node) {
    this.childNodes = this.childNodes.filter((child) => child !== node);
    node.parentNode = null;
    return node;
  }

  replaceChildren(...nodes) {
    this.childNodes = [];
    this.append(...nodes);
  }

  get textContent() { return this.childNodes.map((child) => child.textContent).join(''); }

  set textContent(value) { this.childNodes = [new FakeText(String(value))]; }
}

export class FakeText extends FakeNode {
  constructor(data) {
    super(3);
    this.data = data;
  }

  get textContent() { return this.data; }
}

export class FakeElement extends FakeNode {
  constructor(tagName, namespaceURI = 'http://www.w3.org/1999/xhtml') {
    super(1);
    this.tagName = tagName.toUpperCase();
    this.localName = tagName;
    this.namespaceURI = namespaceURI;
    this.attributes = new Map();
    this.listeners = new Map();
    this.styles = new Map();
    const element = this;
    this.style = { setProperty(name, value) { element.styles.set(name, value); } };
    this.classList = {
      add: (...names) => names.forEach((name) => element.#classes().add(name)) || element.#syncClasses(),
      remove: (...names) => { names.forEach((name) => element.#classes().delete(name)); element.#syncClasses(); },
      toggle: (name, force) => {
        const set = element.#classes();
        const on = force === undefined ? !set.has(name) : Boolean(force);
        if (on) set.add(name); else set.delete(name);
        element.#syncClasses();
        return on;
      },
      contains: (name) => element.#classes().has(name),
    };
  }

  #classSet = null;

  #classes() {
    if (!this.#classSet) this.#classSet = new Set((this.attributes.get('class') || '').split(/\s+/).filter(Boolean));
    return this.#classSet;
  }

  #syncClasses() { this.attributes.set('class', [...this.#classSet].join(' ')); }

  setAttribute(name, value) {
    if (/^on/i.test(name)) throw new Error(`event handler attribute ${name} set`);
    this.attributes.set(name, String(value));
    if (name === 'class') this.#classSet = null;
  }

  getAttribute(name) { return this.attributes.has(name) ? this.attributes.get(name) : null; }

  removeAttribute(name) { this.attributes.delete(name); }

  get dataset() {
    const data = {};
    for (const [key, value] of this.attributes) {
      if (key.startsWith('data-')) data[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    }
    return data;
  }

  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(handler);
  }

  dispatch(type, event = {}) {
    for (const handler of this.listeners.get(type) || []) handler({ preventDefault() {}, target: this, ...event });
  }

  set innerHTML(_) { throw new Error('innerHTML must never be used'); }

  get innerHTML() { throw new Error('innerHTML must never be used'); }

  insertAdjacentHTML() { throw new Error('insertAdjacentHTML must never be used'); }

  /** Depth-first list of all descendants. */
  descendants() {
    const result = [];
    const walk = (node) => {
      for (const child of node.childNodes) {
        result.push(child);
        if (child.childNodes) walk(child);
      }
    };
    walk(this);
    return result;
  }
}

export function installFakeDom() {
  const document = {
    createElement: (tag) => new FakeElement(tag),
    createElementNS: (namespace, tag) => new FakeElement(tag, namespace),
    createTextNode: (data) => new FakeText(data),
  };
  globalThis.document = document;
  return document;
}
