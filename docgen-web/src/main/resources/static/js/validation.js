// Client-side validation mirroring the server limits (the server re-validates).

export const LIMITS = Object.freeze({
  name: 120,
  description: 8000,
  requirements: 40,
  requirement: 300,
  technologies: 30,
  technology: 60,
  constraints: 20,
  constraint: 300,
  attachmentBytes: 10 * 1024 * 1024,
  attachments: 5,
  artifact: 200000,
});

export const MODEL_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$/;
export const PROVIDERS = ['groq', 'ollama'];
export const LANGUAGES = ['pt-BR', 'en'];

/** Splits a multi-line field into trimmed, unique, non-empty items. */
export function toItems(text) {
  const seen = new Set();
  const items = [];
  for (const raw of String(text ?? '').split(/\r?\n/)) {
    const item = raw.replace(/^\s*[-*•]\s*/, '').trim();
    if (item && !seen.has(item.toLowerCase())) {
      seen.add(item.toLowerCase());
      items.push(item);
    }
  }
  return items;
}

function checkList(errors, field, items, maxItems, maxLength, label) {
  if (items.length > maxItems) errors[field] = `Informe no máximo ${maxItems} ${label}.`;
  else if (items.some((item) => item.length > maxLength)) errors[field] = `Cada item deve ter até ${maxLength} caracteres.`;
}

export function validateProject(input) {
  const errors = {};
  const name = String(input.name ?? '').trim();
  const description = String(input.description ?? '').trim();
  if (!name) errors.name = 'Dê um nome ao projeto.';
  else if (name.length > LIMITS.name) errors.name = `Use até ${LIMITS.name} caracteres.`;
  if (!description) errors.description = 'Descreva a ideia do projeto.';
  else if (description.length > LIMITS.description) errors.description = `Use até ${LIMITS.description} caracteres.`;
  checkList(errors, 'functionalRequirements', input.functionalRequirements ?? [], LIMITS.requirements, LIMITS.requirement, 'requisitos');
  checkList(errors, 'nonFunctionalRequirements', input.nonFunctionalRequirements ?? [], LIMITS.requirements, LIMITS.requirement, 'requisitos');
  checkList(errors, 'preferredTechnologies', input.preferredTechnologies ?? [], LIMITS.technologies, LIMITS.technology, 'tecnologias');
  checkList(errors, 'constraints', input.constraints ?? [], LIMITS.constraints, LIMITS.constraint, 'restrições');
  if (!PROVIDERS.includes(input.provider)) errors.provider = 'Selecione um provedor.';
  if (input.model && !MODEL_PATTERN.test(input.model)) errors.model = 'Use letras, números e . _ : / - (até 100 caracteres).';
  if (input.outputLanguage && !LANGUAGES.includes(input.outputLanguage)) errors.outputLanguage = 'Idioma inválido.';
  return errors;
}

export function toProjectPayload(form) {
  return {
    name: String(form.name ?? '').trim(),
    description: String(form.description ?? '').trim(),
    functionalRequirements: toItems(form.functionalRequirements),
    nonFunctionalRequirements: toItems(form.nonFunctionalRequirements),
    preferredTechnologies: toItems(String(form.preferredTechnologies ?? '').replace(/,/g, '\n')),
    constraints: toItems(form.constraints),
    provider: form.provider,
    model: String(form.model ?? '').trim() || null,
    outputLanguage: form.outputLanguage || 'pt-BR',
  };
}

export function validatePdf(file) {
  if (!file) return 'Selecione um arquivo PDF.';
  const looksPdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name || '');
  if (!looksPdf) return 'Apenas arquivos PDF são aceitos.';
  if (file.size === 0) return 'O arquivo está vazio.';
  if (file.size > LIMITS.attachmentBytes) return 'O PDF excede o limite de 10 MB.';
  return null;
}
