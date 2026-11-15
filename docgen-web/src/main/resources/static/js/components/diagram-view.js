import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { COMPONENT_TYPE } from '../messages.js';
import { exportSvg, legend, renderDiagram } from '../diagram.js';
import { aiNotice, copyText, downloadBlob } from '../ui.js';

export function diagramPanel(planView, projectName) {
  const details = h('aside', { class: 'diagram-details', 'aria-live': 'polite' },
    h('p', { class: 'muted' }, 'Selecione um componente para ver os detalhes.'));
  const diagram = renderDiagram(planView.plan, {
    onSelect: (component) => {
      if (!component) {
        replace(details, h('p', { class: 'muted' }, 'Selecione um componente para ver os detalhes.'));
        return;
      }
      const incoming = planView.plan.relationships.filter((edge) => edge.to === component.id);
      const outgoing = planView.plan.relationships.filter((edge) => edge.from === component.id);
      const name = (id) => planView.plan.components.find((item) => item.id === id)?.name || id;
      replace(details, 
        h('h4', null, component.name),
        h('span', { class: 'badge badge-neutral' }, COMPONENT_TYPE[component.type] || component.type),
        component.responsibility ? h('p', null, component.responsibility) : null,
        component.technologies.length ? h('div', { class: 'chip-row' }, component.technologies.map((tech) => h('span', { class: 'chip' }, tech))) : null,
        outgoing.length ? h('p', { class: 'muted' }, `Chama: ${outgoing.map((edge) => `${name(edge.to)}${edge.description ? ` (${edge.description})` : ''}`).join(', ')}`) : null,
        incoming.length ? h('p', { class: 'muted' }, `Chamado por: ${incoming.map((edge) => name(edge.from)).join(', ')}`) : null);
    },
  });
  const tool = (label, iconName, onClick) => h('button', { class: 'icon-button', type: 'button', 'aria-label': label, title: label, on: { click: onClick } }, icon(iconName, { size: 17 }));
  const fileBase = projectName.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'arquitetura';

  return h('div', { class: 'result-panel' },
    aiNotice('Diagrama derivado do plano estruturado sugerido pela IA.'),
    h('div', { class: 'diagram-toolbar' },
      tool('Aproximar', 'zoomIn', diagram.zoomIn),
      tool('Afastar', 'zoomOut', diagram.zoomOut),
      tool('Restaurar enquadramento', 'maximize', diagram.reset),
      h('span', { class: 'toolbar-spacer' }),
      h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => downloadBlob(new Blob([exportSvg(planView.plan)], { type: 'image/svg+xml' }), `${fileBase}.svg`) } },
        icon('download', { size: 15 }), h('span', null, 'SVG')),
      h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => copyText(planView.mermaid) } },
        icon('copy', { size: 15 }), h('span', null, 'Copiar Mermaid'))),
    h('div', { class: 'diagram-stage' }, h('div', { class: 'diagram-canvas' }, diagram.element), details),
    h('p', { class: 'muted diagram-hint' }, 'Arraste para mover · Ctrl + roda do mouse para zoom · Tab e Enter navegam pelos componentes.'),
    legend(planView.plan),
    h('details', { class: 'mermaid-source' },
      h('summary', null, 'Código Mermaid (validado no servidor)'),
      h('pre', { class: 'md-code' }, h('code', null, planView.mermaid))));
}
