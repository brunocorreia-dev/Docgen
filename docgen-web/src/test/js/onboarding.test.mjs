import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDom } from './fake-dom.mjs';

installFakeDom();
const { ONBOARDING_STEPS, featuredModels, stepAfterChoice } = await import('../../main/resources/static/js/views/onboarding.js');

test('the guide has the seven steps of the plan, in order', () => {
  assert.deepEqual(ONBOARDING_STEPS.map(([name]) => name), ['welcome', 'ollama', 'hardware', 'model', 'download', 'test', 'done']);
});

test('an installed model skips the download', () => {
  assert.equal(stepAfterChoice({ tag: 'qwen3:8b', installed: true }), 'test');
  assert.equal(stepAfterChoice({ tag: 'qwen3:8b', installed: false }), 'download');
});

test('the suggestions are shown first', () => {
  const catalog = { suggestedGeneral: 'qwen3.5:9b', suggestedCode: 'qwen2.5-coder:7b',
    models: [{ tag: 'qwen3:8b' }, { tag: 'qwen3.5:9b' }, { tag: 'qwen2.5-coder:7b' }] };
  assert.deepEqual(featuredModels(catalog).map((model) => model.tag), ['qwen3.5:9b', 'qwen2.5-coder:7b']);
  assert.deepEqual(featuredModels({ suggestedGeneral: null, suggestedCode: null, models: [{ tag: 'x' }] }), []);
});
