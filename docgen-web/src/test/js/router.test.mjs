import { test } from 'node:test';
import assert from 'node:assert/strict';
import { href, parseRoute } from '../../main/resources/static/js/router.js';

const ID = '0f8c2a52-3b5d-4c1e-9a7f-1234567890ab';
const ARTIFACT = '11111111-2222-3333-4444-555555555555';

test('parses every workspace route', () => {
  assert.equal(parseRoute('').name, 'dashboard');
  assert.equal(parseRoute('#/projects').name, 'projects');
  assert.equal(parseRoute('#/projects/new').name, 'project-new');
  assert.deepEqual(parseRoute(`#/projects/${ID}`).params, { projectId: ID });
  assert.deepEqual(parseRoute(`#/projects/${ID}/diagram`).params, { projectId: ID, tab: 'diagram' });
  assert.deepEqual(parseRoute(`#/projects/${ID}/docs/${ARTIFACT}`).params, { tab: 'docs', projectId: ID, artifactId: ARTIFACT });
});

test('there is no login route any more', () => {
  assert.equal(parseRoute('#/login').name, 'not-found');
});

test('rejects unknown or malformed paths', () => {
  assert.equal(parseRoute('#/projects/../../etc').name, 'not-found');
  assert.equal(parseRoute(`#/projects/${ID}/<script>`).name, 'not-found');
});

test('href and parseRoute round-trip', () => {
  for (const [name, params] of [['projects', {}], ['project', { projectId: ID, tab: 'plan' }], ['project', { projectId: ID, artifactId: ARTIFACT }]]) {
    const parsed = parseRoute(href(name, params));
    assert.equal(parsed.name, name);
    assert.equal(parsed.params.projectId, params.projectId);
  }
});
