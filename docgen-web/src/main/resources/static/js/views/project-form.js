// Project brief form, shared by the "new project" page and the edit dialog.
import { h } from '../dom.js';
import { icon } from '../icons.js';
import { LIMITS, toProjectPayload, validateProject } from '../validation.js';

// Clearly labelled demonstration input; it is user-side example text, never AI output.
export const DEMO_PROJECT = Object.freeze({
  name: 'API de Gestão de Estoque',
  description: 'Quero construir uma API REST de gerenciamento de estoque usando Java, Spring Boot e PostgreSQL, com autenticação JWT e arquitetura em camadas. A API deve atender lojas físicas e um painel web administrativo.',
  functionalRequirements: 'Cadastrar e editar produtos\nRegistrar entradas e saídas de estoque\nEmitir alerta de estoque mínimo\nConsultar histórico de movimentações',
  nonFunctionalRequirements: 'Autenticação JWT com expiração curta\nTempo de resposta abaixo de 300 ms no p95\nAuditoria das movimentações',
  preferredTechnologies: 'Java 21, Spring Boot, PostgreSQL, Flyway',
  constraints: 'Arquitetura em camadas\nImplantação em um único servidor Linux',
  provider: 'ollama',
  model: '',
  outputLanguage: 'pt-BR',
});

function field({ name, label, hint, control, max, counter }) {
  const counterElement = counter ? h('span', { class: 'field-counter', 'aria-live': 'off' }) : null;
  const update = () => {
    if (counterElement) counterElement.textContent = `${control.value.length.toLocaleString('pt-BR')}/${max.toLocaleString('pt-BR')}`;
  };
  if (counter) {
    control.addEventListener('input', update);
    update();
  }
  const error = h('p', { class: 'field-error', id: `${name}-error`, role: 'alert' });
  control.setAttribute('aria-describedby', `${name}-hint ${name}-error`);
  return h('div', { class: 'field', dataset: { field: name } },
    h('div', { class: 'field-label-row' }, h('label', { for: `field-${name}` }, label), counterElement),
    control,
    hint ? h('p', { class: 'field-hint', id: `${name}-hint` }, hint) : null,
    error);
}

export function projectForm({ initial = {}, submitLabel = 'Criar projeto', onSubmit, showDemo = false }) {
  const values = {
    name: initial.name ?? '',
    description: initial.description ?? '',
    functionalRequirements: (initial.functionalRequirements ?? []).join?.('\n') ?? initial.functionalRequirements ?? '',
    nonFunctionalRequirements: (initial.nonFunctionalRequirements ?? []).join?.('\n') ?? initial.nonFunctionalRequirements ?? '',
    preferredTechnologies: (initial.preferredTechnologies ?? []).join?.(', ') ?? initial.preferredTechnologies ?? '',
    constraints: (initial.constraints ?? []).join?.('\n') ?? initial.constraints ?? '',
    model: initial.model ?? '',
    outputLanguage: initial.outputLanguage ?? 'pt-BR',
  };

  const controls = {
    name: h('input', { id: 'field-name', name: 'name', type: 'text', maxlength: LIMITS.name, required: true, autocomplete: 'off', value: values.name, placeholder: 'Ex.: Plataforma de agendamentos' }),
    description: h('textarea', { id: 'field-description', name: 'description', rows: 6, maxlength: LIMITS.description, required: true, value: values.description, placeholder: 'Descreva a ideia em linguagem natural: problema, usuários, escopo, integrações…' }),
    functionalRequirements: h('textarea', { id: 'field-functionalRequirements', rows: 4, value: values.functionalRequirements, placeholder: 'Um requisito por linha' }),
    nonFunctionalRequirements: h('textarea', { id: 'field-nonFunctionalRequirements', rows: 3, value: values.nonFunctionalRequirements, placeholder: 'Desempenho, segurança, disponibilidade… (um por linha)' }),
    preferredTechnologies: h('input', { id: 'field-preferredTechnologies', type: 'text', value: values.preferredTechnologies, placeholder: 'Separadas por vírgula: Java, Spring Boot, PostgreSQL' }),
    constraints: h('textarea', { id: 'field-constraints', rows: 3, value: values.constraints, placeholder: 'Orçamento, prazos, infraestrutura obrigatória… (uma por linha)' }),
    model: h('input', { id: 'field-model', type: 'text', value: values.model, maxlength: 100, list: 'model-suggestions', placeholder: 'Modelo padrão do DocGen', autocomplete: 'off' }),
    outputLanguage: h('select', { id: 'field-outputLanguage' },
      h('option', { value: 'pt-BR', selected: values.outputLanguage === 'pt-BR' }, 'Português (Brasil)'),
      h('option', { value: 'en', selected: values.outputLanguage === 'en' }, 'English')),
  };

  const suggestions = h('datalist', { id: 'model-suggestions' },
    ['qwen2.5-coder:7b', 'qwen2.5-coder:3b', 'qwen2.5-coder:14b']
      .map((model) => h('option', { value: model })));

  const form = h('form', { class: 'project-form', novalidate: true },
    showDemo ? h('div', { class: 'demo-banner' },
      icon('info', { size: 16 }),
      h('span', null, 'Quer testar rapidamente? Preencha com um exemplo. São dados de demonstração digitados por você, não respostas da IA.'),
      h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => fill(DEMO_PROJECT) } }, 'Usar exemplo')) : null,
    h('section', { class: 'form-section' },
      h('h3', { class: 'form-section-title' }, h('span', { class: 'step-dot' }, '1'), 'A ideia'),
      field({ name: 'name', label: 'Nome do projeto', control: controls.name, max: LIMITS.name, counter: true }),
      field({ name: 'description', label: 'Descrição', hint: 'Texto livre. Ele é tratado como dado, nunca como instrução para a IA.', control: controls.description, max: LIMITS.description, counter: true })),
    h('section', { class: 'form-section' },
      h('h3', { class: 'form-section-title' }, h('span', { class: 'step-dot' }, '2'), 'Requisitos e restrições'),
      h('div', { class: 'form-grid' },
        field({ name: 'functionalRequirements', label: 'Requisitos funcionais', hint: `Até ${LIMITS.requirements} itens, um por linha.`, control: controls.functionalRequirements }),
        field({ name: 'nonFunctionalRequirements', label: 'Requisitos não funcionais', hint: `Até ${LIMITS.requirements} itens, um por linha.`, control: controls.nonFunctionalRequirements })),
      h('div', { class: 'form-grid' },
        field({ name: 'preferredTechnologies', label: 'Tecnologias preferidas', control: controls.preferredTechnologies }),
        field({ name: 'constraints', label: 'Restrições técnicas', control: controls.constraints }))),
    h('section', { class: 'form-section' },
      h('h3', { class: 'form-section-title' }, h('span', { class: 'step-dot' }, '3'), 'Inteligência artificial'),
      h('p', { class: 'field-hint local-model-note' }, icon('server', { size: 15 }),
        'Os modelos rodam no Ollama deste computador (127.0.0.1). Nada é enviado para serviços externos.'),
      h('div', { class: 'form-grid' },
        field({ name: 'model', label: 'Modelo', hint: 'Opcional. Deixe em branco para usar o modelo padrão.', control: controls.model }),
        field({ name: 'outputLanguage', label: 'Idioma da documentação', control: controls.outputLanguage })),
      suggestions),
    h('div', { class: 'form-actions' },
      h('button', { class: 'button button-primary', type: 'submit' }, icon('check'), h('span', null, submitLabel))));

  function fill(data) {
    for (const [key, control] of Object.entries(controls)) {
      if (key in data) {
        control.value = data[key];
        control.dispatchEvent(new Event('input'));
      }
    }
  }

  function read() {
    return toProjectPayload({
      name: controls.name.value,
      description: controls.description.value,
      functionalRequirements: controls.functionalRequirements.value,
      nonFunctionalRequirements: controls.nonFunctionalRequirements.value,
      preferredTechnologies: controls.preferredTechnologies.value,
      constraints: controls.constraints.value,
      provider: 'ollama',
      model: controls.model.value,
      outputLanguage: controls.outputLanguage.value,
    });
  }

  function showErrors(errors) {
    form.querySelectorAll('.field').forEach((element) => {
      const message = errors[element.dataset.field];
      element.classList.toggle('has-error', Boolean(message));
      const slot = element.querySelector('.field-error');
      if (slot) slot.textContent = message || '';
    });
    const first = Object.keys(errors)[0];
    if (first) form.querySelector(`[data-field="${first}"] input, [data-field="${first}"] textarea`)?.focus();
  }

  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const payload = read();
    const errors = validateProject(payload);
    showErrors(errors);
    if (Object.keys(errors).length) return;
    const submit = form.querySelector('button[type="submit"]');
    submit.disabled = true;
    submit.classList.add('is-busy');
    try {
      await onSubmit(payload);
    } finally {
      submit.disabled = false;
      submit.classList.remove('is-busy');
    }
  });

  return { form, showErrors };
}
