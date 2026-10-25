import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installFakeDom } from './fake-dom.mjs';

installFakeDom();
const models = await import('../../main/resources/static/js/models.js');
const { catalogCard, hardwareSummary } = await import('../../main/resources/static/js/components/model-ui.js');
const { externalLink } = await import('../../main/resources/static/js/links.js');

const GIB = 1024 ** 3;

test('what was not detected reads "não detectado", never a guess', () => {
  assert.equal(models.detected(null), 'não detectado');
  assert.equal(models.detected(undefined), 'não detectado');
  assert.equal(models.detected(''), 'não detectado');
  assert.equal(models.detected(0), '0');
  assert.equal(models.detected(8, (value) => `${value} núcleos`), '8 núcleos');
});

test('download progress uses Ollama counts, then the catalog size, and is capped', () => {
  assert.equal(models.pullPercent({ state: 'RUNNING', completedBytes: 250, totalBytes: 1000, expectedBytes: 5 }), 25);
  assert.equal(models.pullPercent({ state: 'RUNNING', completedBytes: 0, totalBytes: 0, expectedBytes: 0 }), null);
  assert.equal(models.pullPercent({ state: 'RUNNING', completedBytes: 1, totalBytes: 0, expectedBytes: 4 }), 25);
  assert.equal(models.pullPercent({ state: 'RUNNING', completedBytes: 5000, totalBytes: 1000 }), 100);
  assert.equal(models.pullPercent({ state: 'SUCCEEDED', completedBytes: 0, totalBytes: 0 }), 100);
});

test('time left is only shown while the speed is known', () => {
  assert.equal(models.formatRemaining({ state: 'RUNNING', completedBytes: 0, totalBytes: 600e6, bytesPerSecond: 10e6 }), 'cerca de 1 minuto');
  assert.equal(models.formatRemaining({ state: 'RUNNING', completedBytes: 0, totalBytes: 100e6, bytesPerSecond: 10e6 }), 'menos de 1 minuto');
  assert.equal(models.formatRemaining({ state: 'RUNNING', completedBytes: 0, totalBytes: 9000e6, bytesPerSecond: 1e6 }), 'cerca de 2 h 30 min');
  assert.equal(models.formatRemaining({ state: 'RUNNING', completedBytes: 0, totalBytes: 100, bytesPerSecond: 0 }), null);
  assert.equal(models.formatRemaining({ state: 'CANCELLED', completedBytes: 0, totalBytes: 100, bytesPerSecond: 9 }), null);
  assert.equal(models.formatSpeed(0), '—');
});

test('the disk check blocks clearly insufficient space and warns when tight or unknown', () => {
  const model = { downloadBytes: 5 * GIB };
  assert.equal(models.diskCheck(model, { freeBytes: 4 * GIB, exact: true }).blocked, true);
  const tight = models.diskCheck(model, { freeBytes: 5.5 * GIB, exact: true });
  assert.equal(tight.blocked, false);
  assert.match(tight.warning, /quase cheio/);
  assert.equal(models.diskCheck(model, { freeBytes: 100 * GIB, exact: true }).warning, null);
  assert.match(models.diskCheck(model, { freeBytes: 100 * GIB, exact: false }).warning, /pasta pessoal/);
  assert.match(models.diskCheck(model, { freeBytes: null }).warning, /Não foi possível medir/);
});

test('suggestions come first, then the best classes, in catalog order', () => {
  const catalog = {
    suggestedGeneral: 'b', suggestedCode: null,
    models: [{ tag: 'a', fit: 'CPU_ONLY' }, { tag: 'b', fit: 'RECOMMENDED' }, { tag: 'c', fit: 'RECOMMENDED' }, { tag: 'd', fit: 'NOT_RECOMMENDED' }],
  };
  assert.deepEqual(models.orderCatalog(catalog).map((model) => model.tag), ['b', 'c', 'a', 'd']);
});

test('the memory split reads like ollama ps', () => {
  assert.equal(models.memorySplit({ sizeBytes: 100, vramBytes: 100 }), '100% na GPU');
  assert.equal(models.memorySplit({ sizeBytes: 100, vramBytes: 0 }), '100% na CPU');
  assert.equal(models.memorySplit({ sizeBytes: 100, vramBytes: 94 }), '94% na GPU, 6% na CPU');
  assert.equal(models.memorySplit(null), null);
});

test('install help follows the detected system', () => {
  assert.equal(models.osFamily('Windows 11'), 'windows');
  assert.equal(models.osFamily('macOS'), 'macos');
  assert.equal(models.osFamily('Freedesktop SDK'), 'linux');
  assert.equal(models.osFamily(null), 'linux');
  for (const steps of Object.values(models.OLLAMA_INSTALL_STEPS)) assert.equal(steps.length, 3);
});

test('external links open a new tab without opener or referrer', () => {
  const link = externalLink('Baixar o Ollama', 'https://ollama.com/download');
  assert.equal(link.getAttribute('target'), '_blank');
  assert.equal(link.getAttribute('rel'), 'noopener noreferrer');
  assert.equal(link.getAttribute('href'), 'https://ollama.com/download');
});

test('catalog cards show model text only as text nodes', () => {
  const card = catalogCard({
    tag: 'qwen3:8b<img src=x onerror=alert(1)>', family: 'qwen3', parameterCount: 8.19e9, activeParameterCount: 8.19e9,
    quantization: 'Q4_K_M', downloadSize: '5,2 GB', downloadBytes: 5.25e9, contextTokens: 40960, purposes: ['general', 'reasoning'],
    description: '<script>alert(1)</script>', reason: 'Cabe inteiro na placa de vídeo.', verified: true, fit: 'RECOMMENDED',
    estimate: { totalBytes: 7 * GIB, contextTokens: 8192 }, installed: false,
  }, { suggestedFor: ['Sugerido para uso geral'] });
  const elements = card.descendants().filter((node) => node.nodeType === 1).map((node) => node.localName);
  assert.ok(!elements.includes('script') && !elements.includes('img'));
  assert.ok(card.textContent.includes('<script>alert(1)</script>'));
  assert.ok(card.textContent.includes('Recomendado'));
  assert.ok(card.textContent.includes('Sugerido para uso geral'));
  assert.ok(card.textContent.includes('8,2 bilhões de parâmetros'));
  assert.ok(card.getAttribute('class').includes('fit-recommended'));
});

test('the hardware summary says what was not detected', () => {
  const summary = hardwareSummary({
    osName: 'Linux', osVersion: null, architecture: 'x86_64', cpuModel: null, cpuCores: null, cpuThreads: 12,
    ramTotalBytes: 32 * GIB, ramAvailableBytes: null, unifiedMemory: false,
    gpus: [{ vendor: 'NVIDIA', name: 'GeForce RTX 5070', vramTotalBytes: 12 * GIB, vramFreeBytes: null, source: 'sistema, não confirmado' }],
    modelStorage: { directory: null, freeBytes: 100 * GIB, exact: false },
  });
  const text = summary.textContent;
  assert.ok(text.includes('Linux · não detectado · x86_64'));
  assert.ok(text.includes('não detectado disponíveis agora'));
  assert.ok(text.includes('sem confirmação do driver'));
  assert.ok(text.includes('pasta de modelos do Ollama não encontrada'));
});
