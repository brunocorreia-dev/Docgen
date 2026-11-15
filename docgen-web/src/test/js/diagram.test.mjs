import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDom } from './fake-dom.mjs';

let diagram;
before(async () => {
  installFakeDom();
  diagram = await import('../../main/resources/static/js/diagram.js');
});

const plan = {
  components: [
    { id: 'web', name: 'Painel Web', type: 'CLIENT', responsibility: 'UI', technologies: [] },
    { id: 'api', name: 'API', type: 'SERVICE', responsibility: '', technologies: [] },
    { id: 'db', name: 'PostgreSQL', type: 'DATABASE', responsibility: '', technologies: [] },
    { id: 'mail', name: 'E-mail', type: 'EXTERNAL', responsibility: '', technologies: [] },
  ],
  relationships: [
    { from: 'web', to: 'api', description: 'HTTPS' },
    { from: 'api', to: 'db', description: 'JDBC' },
    { from: 'api', to: 'mail', description: 'SMTP' },
    { from: 'db', to: 'api', description: 'cycle' },
    { from: 'api', to: 'ghost', description: 'unknown' },
  ],
};

test('ranks components left to right along relationships', () => {
  const layout = diagram.layoutDiagram(plan.components, plan.relationships);
  const rank = Object.fromEntries(layout.nodes.map((node) => [node.id, node.rank]));
  assert.equal(rank.web, 0);
  assert.equal(rank.api, 1);
  assert.equal(rank.db, 2);
  assert.equal(rank.mail, 2);
  assert.equal(layout.edges.length, 4, 'edges to unknown components are ignored');
});

test('nodes never overlap and fit inside the canvas', () => {
  const layout = diagram.layoutDiagram(plan.components, plan.relationships);
  for (const a of layout.nodes) {
    assert.ok(a.x >= 0 && a.y >= 0 && a.x + diagram.NODE_WIDTH <= layout.width && a.y + diagram.NODE_HEIGHT <= layout.height);
    for (const b of layout.nodes) {
      if (a === b) continue;
      const overlap = a.x < b.x + diagram.NODE_WIDTH && b.x < a.x + diagram.NODE_WIDTH
        && a.y < b.y + diagram.NODE_HEIGHT && b.y < a.y + diagram.NODE_HEIGHT;
      assert.ok(!overlap, `${a.id} overlaps ${b.id}`);
    }
  }
});

test('handles empty plans and unknown component types', () => {
  assert.equal(diagram.layoutDiagram([], []).nodes.length, 0);
  const layout = diagram.layoutDiagram([{ id: 'x', name: 'X', type: 'MALICIOUS' }], []);
  assert.equal(layout.nodes[0].type, 'OTHER');
});

test('renders hostile names as SVG text without markup', () => {
  const hostile = { ...plan, components: plan.components.map((component, index) => (index === 0
    ? { ...component, name: '<script>alert(1)</script>' } : component)) };
  const rendered = diagram.renderDiagram(hostile);
  const texts = rendered.element.descendants().filter((node) => node.localName === 'text');
  assert.ok(texts.some((node) => node.textContent.startsWith('<script>')));
  assert.ok(rendered.element.descendants().every((node) => node.nodeType === 3 || node.namespaceURI === 'http://www.w3.org/2000/svg'));
  assert.ok(rendered.element.descendants().filter((node) => node.localName === 'script').length === 0);
});

test('clip shortens long labels with an ellipsis', () => {
  assert.equal(diagram.clip('abcdef', 4), 'abc…');
  assert.equal(diagram.clip('abc', 4), 'abc');
});
