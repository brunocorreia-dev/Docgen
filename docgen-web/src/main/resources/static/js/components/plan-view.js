// Structured plan panels. Every value is rendered as text; requirement origins
// are labelled so user-provided facts stay distinct from AI suggestions.
import { h } from '../dom.js';
import { icon } from '../icons.js';
import { formatDate, providerLabel } from '../format.js';
import { COMPONENT_TYPE, IMPACT, PLAN_STATUS, SOURCE_LABEL } from '../messages.js';
import { aiNotice, badge } from '../ui.js';

const SOURCE_TONE = { USER: 'success', DOCUMENT_INFERENCE: 'info', AI_SUGGESTION: 'purple' };
const IMPACT_TONE = { LOW: 'neutral', MEDIUM: 'warning', HIGH: 'danger' };

function section(title, iconName, ...content) {
  const filled = content.flat().filter(Boolean);
  if (!filled.length) return null;
  return h('section', { class: 'result-section' }, h('h3', null, icon(iconName, { size: 16 }), title), filled);
}

function bullets(items, className = 'bullet-list') {
  return items?.length ? h('ul', { class: className }, items.map((item) => h('li', null, item))) : null;
}

function requirementList(requirements) {
  if (!requirements?.length) return null;
  return h('ul', { class: 'requirement-list' }, requirements.map((requirement) => h('li', { class: 'requirement' },
    h('span', { class: 'requirement-id' }, requirement.id),
    h('span', { class: 'requirement-text' }, requirement.description),
    badge(SOURCE_LABEL[requirement.source] || requirement.source, SOURCE_TONE[requirement.source] || 'neutral'))));
}

export function planHeader(planView, { onApprove }) {
  const approved = planView.status === 'APPROVED';
  return h('div', { class: ['plan-status', approved ? 'is-approved' : 'is-draft'] },
    h('div', { class: 'plan-status-text' },
      badge(PLAN_STATUS[planView.status] || planView.status, approved ? 'success' : 'warning', approved ? 'check' : 'eye'),
      h('small', { class: 'muted' }, `Gerado em ${formatDate(planView.createdAt)} por ${providerLabel(planView.provider)} (${planView.model || 'modelo padrão'})`
        + (approved ? ` · aprovado em ${formatDate(planView.approvedAt)}` : ''))),
    !approved && onApprove ? h('button', { class: 'button button-primary button-small', type: 'button', on: { click: onApprove } },
      icon('check', { size: 15 }), h('span', null, 'Revisei e aprovo')) : null);
}

export function summaryPanel(planView) {
  const plan = planView.plan;
  return h('div', { class: 'result-panel' },
    aiNotice(),
    planView.warnings?.length ? h('div', { class: 'callout callout-warning' }, icon('alert', { size: 16 }),
      h('div', null, h('strong', null, 'Ajustes de validação aplicados'), bullets(planView.warnings))) : null,
    section('Resumo', 'info', h('p', { class: 'lead' }, plan.summary)),
    section('Objetivos', 'check', bullets(plan.objectives)),
    section('Requisitos funcionais', 'list', requirementList(plan.functionalRequirements)),
    section('Requisitos não funcionais', 'shield', requirementList(plan.nonFunctionalRequirements)),
    section('Premissas assumidas pela IA', 'eye', bullets(plan.assumptions)),
    section('Perguntas em aberto', 'alert', bullets(plan.openQuestions)));
}

export function architecturePanel(planView) {
  const plan = planView.plan;
  return h('div', { class: 'result-panel' },
    aiNotice('Arquitetura sugerida pela IA. São decisões propostas, não uma implementação existente.'),
    section('Estilo arquitetural', 'layers',
      h('p', { class: 'lead' }, plan.architecture.style),
      plan.architecture.rationale ? h('p', null, plan.architecture.rationale) : null),
    section('Componentes e responsabilidades', 'diagram', h('div', { class: 'component-grid' }, plan.components.map((component) =>
      h('article', { class: ['component-card', `type-${component.type.toLowerCase()}`] },
        h('header', null, h('strong', null, component.name), badge(COMPONENT_TYPE[component.type] || component.type, 'neutral')),
        component.responsibility ? h('p', null, component.responsibility) : null,
        component.technologies.length ? h('div', { class: 'chip-row' }, component.technologies.map((tech) => h('span', { class: 'chip' }, tech))) : null)))),
    section('Fluxo de dados', 'refresh', plan.dataFlow.length ? h('ol', { class: 'flow-list' }, plan.dataFlow.map((step) => h('li', null, step))) : null),
    section('Tecnologias recomendadas', 'sparkles', plan.technologies.length ? h('div', { class: 'table-wrap' }, h('table', { class: 'data-table' },
      h('thead', null, h('tr', null, h('th', null, 'Tecnologia'), h('th', null, 'Categoria'), h('th', null, 'Justificativa'))),
      h('tbody', null, plan.technologies.map((tech) => h('tr', null, h('td', null, h('strong', null, tech.name)), h('td', null, tech.category), h('td', null, tech.justification))))))
      : null),
    section('Integrações externas', 'external', plan.integrations.length ? h('ul', { class: 'bullet-list' }, plan.integrations.map((item) =>
      h('li', null, h('strong', null, item.name), item.purpose ? ` — ${item.purpose}` : ''))) : null),
    section('Persistência', 'server', h('p', null, plan.persistence.strategy), bullets(plan.persistence.details)),
    section('Segurança', 'shield', bullets(plan.securityConsiderations)),
    section('Decisões arquiteturais propostas', 'edit', plan.decisions.length ? h('div', { class: 'decision-list' }, plan.decisions.map((decision, index) =>
      h('article', { class: 'decision' },
        h('header', null, h('span', { class: 'decision-id' }, `ADR-${String(index + 1).padStart(2, '0')}`), h('strong', null, decision.title), badge('Proposta', 'purple')),
        h('p', null, decision.decision),
        decision.rationale ? h('p', { class: 'muted' }, decision.rationale) : null,
        decision.alternatives.length ? h('p', { class: 'muted' }, `Alternativas: ${decision.alternatives.join(', ')}`) : null))) : null),
    section('Riscos', 'alert', plan.risks.length ? h('ul', { class: 'risk-list' }, plan.risks.map((risk) => h('li', { class: 'risk' },
      badge(`Impacto ${IMPACT[risk.impact] || risk.impact}`, IMPACT_TONE[risk.impact] || 'neutral'),
      h('div', null, h('p', null, risk.description), risk.mitigation ? h('p', { class: 'muted' }, `Mitigação: ${risk.mitigation}`) : null)))) : null));
}

export function roadmapPanel(planView) {
  const phases = planView.plan.implementationPlan;
  return h('div', { class: 'result-panel' },
    aiNotice('Plano de implementação sugerido. Ajuste prazos e prioridades com a equipe.'),
    phases.length ? h('ol', { class: 'roadmap' }, phases.map((phase) => h('li', { class: 'roadmap-phase', style: { '--delay': `${phase.order * 50}ms` } },
      h('span', { class: 'roadmap-marker' }, String(phase.order)),
      h('div', { class: 'roadmap-body' },
        h('h3', null, phase.title),
        phase.goal ? h('p', { class: 'muted' }, phase.goal) : null,
        phase.tasks.length ? h('div', null, h('p', { class: 'roadmap-label' }, 'Tarefas'), bullets(phase.tasks, 'check-list')) : null,
        phase.deliverables.length ? h('div', null, h('p', { class: 'roadmap-label' }, 'Entregáveis'),
          h('div', { class: 'chip-row' }, phase.deliverables.map((item) => h('span', { class: 'chip' }, item)))) : null))))
      : h('p', { class: 'muted' }, 'O modelo não propôs fases de implementação.'));
}
