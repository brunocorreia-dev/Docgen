// Architecture diagram drawn from the structured plan (components and
// relationships). Mermaid source is generated and validated server-side and
// offered as an export; the browser never executes Mermaid, so no diagram
// text can become markup or script here. Layout is a small layered (Sugiyama
// style) algorithm: longest-path ranks after breaking cycles, then barycenter
// ordering sweeps.

import { s, h } from './dom.js';
import { COMPONENT_TYPE } from './messages.js';

export const NODE_WIDTH = 210;
export const NODE_HEIGHT = 68;
const GAP_X = 84;
const GAP_Y = 34;
const PADDING = 40;

export const PALETTE = {
  CLIENT: { fill: '#13254d', stroke: '#4f7cff', accent: '#7aa0ff' },
  API_GATEWAY: { fill: '#1d1b4b', stroke: '#8b5cf6', accent: '#b49bff' },
  SERVICE: { fill: '#14213f', stroke: '#5b8cff', accent: '#93b4ff' },
  WORKER: { fill: '#1a1f3f', stroke: '#6d7cff', accent: '#a5b0ff' },
  DATABASE: { fill: '#0d2a33', stroke: '#22d3ee', accent: '#67e8f9' },
  CACHE: { fill: '#0f2a2a', stroke: '#2dd4bf', accent: '#5eead4' },
  QUEUE: { fill: '#2a1f3d', stroke: '#c084fc', accent: '#d8b4fe' },
  STORAGE: { fill: '#0d2733', stroke: '#38bdf8', accent: '#7dd3fc' },
  EXTERNAL: { fill: '#2b2233', stroke: '#f472b6', accent: '#f9a8d4' },
  OTHER: { fill: '#1b2133', stroke: '#94a3b8', accent: '#cbd5e1' },
};

/** Pure layout: returns positioned nodes and routed edges. */
export function layoutDiagram(components = [], relationships = []) {
  const ids = components.map((component) => component.id);
  const known = new Set(ids);
  const edges = relationships.filter((edge) => known.has(edge.from) && known.has(edge.to) && edge.from !== edge.to);
  const outgoing = new Map(ids.map((id) => [id, []]));
  edges.forEach((edge, index) => outgoing.get(edge.from).push({ ...edge, index }));

  // Break cycles with a DFS: edges that point back to an ancestor are reversed for ranking.
  const state = new Map();
  const reversed = new Set();
  const visit = (id) => {
    state.set(id, 1);
    for (const edge of outgoing.get(id)) {
      const target = state.get(edge.to);
      if (target === 1) reversed.add(edge.index);
      else if (!target) visit(edge.to);
    }
    state.set(id, 2);
  };
  ids.forEach((id) => { if (!state.get(id)) visit(id); });

  const forward = edges.map((edge, index) => (reversed.has(index)
    ? { from: edge.to, to: edge.from } : { from: edge.from, to: edge.to }));
  const rank = new Map(ids.map((id) => [id, 0]));
  for (let pass = 0; pass < ids.length; pass += 1) {
    let changed = false;
    for (const edge of forward) {
      if (rank.get(edge.to) < rank.get(edge.from) + 1) {
        rank.set(edge.to, rank.get(edge.from) + 1);
        changed = true;
      }
    }
    if (!changed) break;
  }

  const layers = [];
  ids.forEach((id) => {
    const r = rank.get(id);
    (layers[r] ||= []).push(id);
  });
  const order = new Map();
  layers.forEach((layer) => layer.forEach((id, index) => order.set(id, index)));
  const predecessors = new Map(ids.map((id) => [id, []]));
  const successors = new Map(ids.map((id) => [id, []]));
  forward.forEach((edge) => {
    predecessors.get(edge.to).push(edge.from);
    successors.get(edge.from).push(edge.to);
  });
  const barycenter = (id, neighbours) => {
    const list = neighbours.get(id);
    if (!list.length) return order.get(id);
    return list.reduce((sum, other) => sum + order.get(other), 0) / list.length;
  };
  for (let sweep = 0; sweep < 4; sweep += 1) {
    const downward = sweep % 2 === 0;
    const sequence = downward ? layers.slice(1) : layers.slice(0, -1).reverse();
    for (const layer of sequence) {
      const neighbours = downward ? predecessors : successors;
      layer.sort((a, b) => barycenter(a, neighbours) - barycenter(b, neighbours) || order.get(a) - order.get(b));
      layer.forEach((id, index) => order.set(id, index));
    }
  }

  const tallest = Math.max(1, ...layers.map((layer) => layer.length));
  const height = PADDING * 2 + tallest * NODE_HEIGHT + (tallest - 1) * GAP_Y;
  const width = PADDING * 2 + Math.max(1, layers.length) * NODE_WIDTH + Math.max(0, layers.length - 1) * GAP_X;
  const byId = new Map(components.map((component) => [component.id, component]));
  const nodes = [];
  layers.forEach((layer, column) => {
    const columnHeight = layer.length * NODE_HEIGHT + (layer.length - 1) * GAP_Y;
    const top = (height - columnHeight) / 2;
    layer.forEach((id, row) => {
      const component = byId.get(id);
      nodes.push({
        id,
        name: component.name,
        type: PALETTE[component.type] ? component.type : 'OTHER',
        x: PADDING + column * (NODE_WIDTH + GAP_X),
        y: top + row * (NODE_HEIGHT + GAP_Y),
        rank: column,
      });
    });
  });
  const position = new Map(nodes.map((node) => [node.id, node]));
  const routed = edges.map((edge) => {
    const source = position.get(edge.from);
    const target = position.get(edge.to);
    const forwardEdge = target.rank > source.rank;
    const sameRank = target.rank === source.rank;
    let start;
    let end;
    let c1;
    let c2;
    if (forwardEdge) {
      start = { x: source.x + NODE_WIDTH, y: source.y + NODE_HEIGHT / 2 };
      end = { x: target.x, y: target.y + NODE_HEIGHT / 2 };
      const dx = (end.x - start.x) / 2;
      c1 = { x: start.x + dx, y: start.y };
      c2 = { x: end.x - dx, y: end.y };
    } else if (sameRank) {
      const down = target.y > source.y;
      start = { x: source.x + NODE_WIDTH, y: source.y + NODE_HEIGHT / 2 };
      end = { x: target.x + NODE_WIDTH, y: target.y + NODE_HEIGHT / 2 };
      const bulge = 60 + Math.abs(end.y - start.y) / 4;
      c1 = { x: start.x + bulge, y: start.y + (down ? 10 : -10) };
      c2 = { x: end.x + bulge, y: end.y + (down ? -10 : 10) };
    } else {
      start = { x: source.x, y: source.y + NODE_HEIGHT / 2 };
      end = { x: target.x + NODE_WIDTH, y: target.y + NODE_HEIGHT / 2 };
      const lift = 50;
      c1 = { x: start.x - lift, y: start.y - lift * 1.5 };
      c2 = { x: end.x + lift, y: end.y - lift * 1.5 };
    }
    const mid = cubicPoint(start, c1, c2, end, 0.5);
    return {
      from: edge.from,
      to: edge.to,
      label: edge.description || '',
      path: `M ${r(start.x)} ${r(start.y)} C ${r(c1.x)} ${r(c1.y)}, ${r(c2.x)} ${r(c2.y)}, ${r(end.x)} ${r(end.y)}`,
      labelX: r(mid.x),
      labelY: r(mid.y),
    };
  });
  return { nodes, edges: routed, width, height };
}

function cubicPoint(p0, p1, p2, p3, t) {
  const u = 1 - t;
  return {
    x: u ** 3 * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t ** 3 * p3.x,
    y: u ** 3 * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t ** 3 * p3.y,
  };
}

const r = (value) => Math.round(value * 10) / 10;

export function clip(text, max) {
  const value = String(text ?? '');
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}

/**
 * Builds the interactive SVG. Colors are SVG presentation attributes (not
 * inline styles) so the exported file is self-contained and CSP-compatible.
 */
export function renderDiagram(plan, { onSelect } = {}) {
  const layout = layoutDiagram(plan.components, plan.relationships);
  let scale = 1;
  let offset = { x: 0, y: 0 };
  let selected = null;

  const viewport = s('g', { class: 'diagram-viewport' });
  const svg = s('svg', {
    class: 'diagram-svg',
    viewBox: `0 0 ${layout.width} ${layout.height}`,
    role: 'group',
    'aria-label': 'Diagrama de arquitetura sugerido pela IA',
    xmlns: 'http://www.w3.org/2000/svg',
    'font-family': 'Inter, system-ui, sans-serif',
  },
  s('defs', null,
    s('marker', { id: 'diagram-arrow', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '7', markerHeight: '7', orient: 'auto-start-reverse' },
      s('path', { d: 'M 0 0 L 10 5 L 0 10 z', fill: '#8ea2d8' }))),
  viewport);

  const edgeElements = layout.edges.map((edge) => {
    const group = s('g', { class: 'diagram-edge', dataset: { from: edge.from, to: edge.to } },
      s('path', { d: edge.path, fill: 'none', stroke: '#5a6b99', 'stroke-width': '1.6', 'marker-end': 'url(#diagram-arrow)' }));
    if (edge.label) {
      const label = clip(edge.label, 22);
      const width = Math.max(36, label.length * 6.6 + 14);
      group.append(s('g', { class: 'diagram-edge-label', transform: `translate(${edge.labelX} ${edge.labelY})` },
        s('rect', { x: -width / 2, y: -10, width, height: 20, rx: 10, fill: '#0b1020', stroke: '#2a3558' }),
        s('text', { 'text-anchor': 'middle', y: 4, 'font-size': '10.5', fill: '#aab6dd' }, label)));
    }
    viewport.append(group);
    return group;
  });

  const nodeElements = layout.nodes.map((node) => {
    const colors = PALETTE[node.type];
    const component = plan.components.find((item) => item.id === node.id);
    const group = s('g', {
      class: 'diagram-node',
      transform: `translate(${node.x} ${node.y})`,
      tabindex: '0',
      role: 'button',
      'aria-label': `${node.name} (${COMPONENT_TYPE[node.type] || node.type})`,
      dataset: { id: node.id },
      on: {
        click: () => select(node.id),
        keydown: (event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            select(node.id);
          }
        },
        mouseenter: () => highlight(node.id),
        mouseleave: () => highlight(selected),
        focus: () => highlight(node.id),
        blur: () => highlight(selected),
      },
    },
    s('rect', { class: 'diagram-node-box', width: NODE_WIDTH, height: NODE_HEIGHT, rx: 14, fill: colors.fill, stroke: colors.stroke, 'stroke-width': '1.4' }),
    s('rect', { x: 0, y: 0, width: 5, height: NODE_HEIGHT, rx: 2.5, fill: colors.stroke }),
    s('text', { x: 18, y: 28, 'font-size': '13.5', 'font-weight': '600', fill: '#eef1ff' }, clip(node.name, 24)),
    s('text', { x: 18, y: 48, 'font-size': '10.5', fill: colors.accent, 'letter-spacing': '0.06em' },
      (COMPONENT_TYPE[node.type] || node.type).toUpperCase()),
    s('title', null, `${node.name}${component?.responsibility ? ` — ${component.responsibility}` : ''}`));
    viewport.append(group);
    return group;
  });

  function highlight(id) {
    const connected = (otherId) => layout.edges.some((edge) => (edge.from === id && edge.to === otherId)
      || (edge.to === id && edge.from === otherId));
    nodeElements.forEach((element) => {
      const nodeId = element.dataset.id;
      element.classList.toggle('is-dimmed', Boolean(id) && nodeId !== id && !connected(nodeId));
    });
    edgeElements.forEach((element) => {
      const related = Boolean(id) && (element.dataset.from === id || element.dataset.to === id);
      element.classList.toggle('is-active', related);
      element.classList.toggle('is-dimmed', Boolean(id) && !related);
    });
  }

  function select(id) {
    selected = selected === id ? null : id;
    nodeElements.forEach((element) => element.classList.toggle('is-selected', element.dataset.id === selected));
    highlight(selected);
    onSelect?.(selected ? plan.components.find((component) => component.id === selected) : null);
  }

  function apply() {
    viewport.setAttribute('transform', `translate(${r(offset.x)} ${r(offset.y)}) scale(${r(scale * 100) / 100})`);
  }

  function zoom(factor, center = { x: layout.width / 2, y: layout.height / 2 }) {
    const next = Math.min(2.5, Math.max(0.4, scale * factor));
    offset = {
      x: center.x - (center.x - offset.x) * (next / scale),
      y: center.y - (center.y - offset.y) * (next / scale),
    };
    scale = next;
    apply();
  }

  function reset() {
    scale = 1;
    offset = { x: 0, y: 0 };
    apply();
  }

  let drag = null;
  svg.addEventListener('pointerdown', (event) => {
    if (event.target.closest && event.target.closest('.diagram-node')) return;
    drag = { x: event.clientX, y: event.clientY, origin: { ...offset } };
    svg.setPointerCapture?.(event.pointerId);
    svg.classList.add('is-panning');
  });
  svg.addEventListener('pointermove', (event) => {
    if (!drag) return;
    const bounds = svg.getBoundingClientRect();
    const ratio = layout.width / Math.max(1, bounds.width);
    offset = { x: drag.origin.x + (event.clientX - drag.x) * ratio, y: drag.origin.y + (event.clientY - drag.y) * ratio };
    apply();
  });
  const endDrag = () => {
    drag = null;
    svg.classList.remove('is-panning');
  };
  svg.addEventListener('pointerup', endDrag);
  svg.addEventListener('pointerleave', endDrag);
  svg.addEventListener('wheel', (event) => {
    if (!event.ctrlKey && !event.metaKey) return;
    event.preventDefault();
    const bounds = svg.getBoundingClientRect();
    const ratio = layout.width / Math.max(1, bounds.width);
    zoom(event.deltaY < 0 ? 1.1 : 0.9, { x: (event.clientX - bounds.left) * ratio, y: (event.clientY - bounds.top) * ratio });
  }, { passive: false });

  apply();
  return {
    element: svg,
    layout,
    zoomIn: () => zoom(1.2),
    zoomOut: () => zoom(1 / 1.2),
    reset,
    select,
  };
}

/** Self-contained SVG export (no external CSS, scripts or links). */
export function exportSvg(plan) {
  const { element } = renderDiagram(plan);
  element.setAttribute('width', element.getAttribute('viewBox').split(' ')[2]);
  element.setAttribute('height', element.getAttribute('viewBox').split(' ')[3]);
  element.querySelectorAll('[tabindex],[role]').forEach((node) => {
    node.removeAttribute('tabindex');
    node.removeAttribute('role');
  });
  const background = s('rect', { width: '100%', height: '100%', fill: '#070a14' });
  element.insertBefore(background, element.firstChild.nextSibling);
  return new XMLSerializer().serializeToString(element);
}

export function legend(plan) {
  const types = [...new Set(plan.components.map((component) => (PALETTE[component.type] ? component.type : 'OTHER')))];
  return h('ul', { class: 'diagram-legend', 'aria-label': 'Legenda' }, types.map((type) =>
    h('li', null, h('span', { class: 'legend-swatch', style: { '--swatch': PALETTE[type].stroke } }),
      COMPONENT_TYPE[type] || type)));
}
