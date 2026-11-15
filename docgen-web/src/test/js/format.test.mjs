import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerLabel } from '../../main/resources/static/js/format.js';

test('labels the local provider and shows older records as stored', () => {
  assert.equal(providerLabel('ollama'), 'Ollama');
  assert.equal(providerLabel('legacy-provider'), 'legacy-provider');
  assert.equal(providerLabel(''), '—');
  assert.equal(providerLabel(undefined), '—');
});
