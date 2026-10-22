import { test } from 'node:test';
import assert from 'node:assert/strict';
import { LIMITS, toItems, toProjectPayload, validatePdf, validateProject } from '../../main/resources/static/js/validation.js';

test('splits list fields into trimmed unique items', () => {
  assert.deepEqual(toItems('- Cadastrar\n\n* cadastrar\n• Listar  \n'), ['Cadastrar', 'Listar']);
});

test('builds the API payload from form values', () => {
  const payload = toProjectPayload({ name: ' X ', description: ' d ', preferredTechnologies: 'Java, Spring Boot,  ', provider: 'ollama', model: ' ' });
  assert.deepEqual(payload.preferredTechnologies, ['Java', 'Spring Boot']);
  assert.equal(payload.model, null);
  assert.equal(payload.outputLanguage, 'pt-BR');
  assert.equal(payload.name, 'X');
});

test('reports the same limits the server enforces', () => {
  const valid = { name: 'a', description: 'b', provider: 'ollama', functionalRequirements: [], nonFunctionalRequirements: [], preferredTechnologies: [], constraints: [] };
  assert.deepEqual(validateProject(valid), {});
  const errors = validateProject({
    ...valid,
    name: 'x'.repeat(LIMITS.name + 1),
    description: '',
    provider: 'openai',
    model: '../x',
    functionalRequirements: Array.from({ length: LIMITS.requirements + 1 }, (_, i) => `r${i}`),
    constraints: ['x'.repeat(LIMITS.constraint + 1)],
  });
  assert.deepEqual(Object.keys(errors).sort(), ['constraints', 'description', 'functionalRequirements', 'model', 'name', 'provider']);
});

test('checks PDF type and size before uploading', () => {
  assert.equal(validatePdf({ name: 'a.pdf', type: 'application/pdf', size: 10 }), null);
  assert.match(validatePdf({ name: 'a.exe', type: 'application/x-msdownload', size: 10 }), /PDF/);
  assert.match(validatePdf({ name: 'a.pdf', type: '', size: LIMITS.attachmentBytes + 1 }), /10 MB/);
  assert.match(validatePdf({ name: 'a.pdf', type: 'application/pdf', size: 0 }), /vazio/);
});
