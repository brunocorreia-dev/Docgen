import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { href } from '../router.js';
import { pluralize, providerLabel, relativeTime } from '../format.js';
import { errorMessage } from '../messages.js';
import { button, emptyState, errorState, skeleton } from '../ui.js';

export async function renderProjects(ctx) {
  const grid = h('div', { class: 'project-grid' }, Array.from({ length: 3 }, () => h('div', { class: 'card' }, skeleton(4))));
  const search = h('input', { class: 'search-input', type: 'search', placeholder: 'Filtrar projetos…', 'aria-label': 'Filtrar projetos' });
  ctx.setView(h('div', { class: 'page' },
    h('header', { class: 'page-header' },
      h('div', null, h('h1', null, 'Projetos'), h('p', { class: 'muted' }, 'Ideias, planos arquiteturais e documentações.')),
      h('div', { class: 'page-actions' },
        h('div', { class: 'search' }, icon('search', { size: 16 }), search),
        button('Novo projeto', { variant: 'primary', iconName: 'plus', onClick: () => ctx.navigate(href('project-new')) }))),
    grid));

  let projects;
  try {
    projects = (await ctx.api.listProjects()).projects;
  } catch (failure) {
    replace(grid, errorState(errorMessage(failure), () => renderProjects(ctx)));
    return;
  }
  const render = () => {
    const term = search.value.trim().toLowerCase();
    const visible = projects.filter((project) => !term || project.name.toLowerCase().includes(term)
      || project.descriptionPreview.toLowerCase().includes(term));
    if (!projects.length) {
      replace(grid, emptyState({
        iconName: 'folder',
        title: 'Nenhum projeto ainda',
        message: 'Descreva uma ideia em linguagem natural e receba uma proposta de arquitetura.',
        action: button('Criar o primeiro projeto', { variant: 'primary', iconName: 'plus', onClick: () => ctx.navigate(href('project-new')) }),
      }));
      return;
    }
    if (!visible.length) {
      replace(grid, emptyState({ iconName: 'search', title: 'Nada encontrado', message: 'Nenhum projeto corresponde ao filtro.' }));
      return;
    }
    replace(grid, ...visible.map((project, index) => h('a', {
      class: 'card project-card',
      href: href('project', { projectId: project.id }),
      style: { '--delay': `${Math.min(index, 8) * 40}ms` },
    },
    h('div', { class: 'project-card-head' },
      h('span', { class: 'recent-avatar' }, project.name.slice(0, 1).toUpperCase()),
      h('div', null, h('h3', null, project.name), h('small', { class: 'muted' }, `Atualizado ${relativeTime(project.updatedAt)}`)),
      project.stats.activeJobs ? h('span', { class: 'badge badge-info' }, h('span', { class: 'pulse-dot' }), 'Processando') : null),
    h('p', { class: 'project-card-text' }, project.descriptionPreview),
    h('div', { class: 'project-card-meta' },
      h('span', { class: 'chip' }, icon('server', { size: 14 }), providerLabel(project.provider)),
      h('span', { class: 'chip' }, icon('upload', { size: 14 }), pluralize(project.stats.attachments, 'anexo', 'anexos')),
      h('span', { class: 'chip' }, icon('layers', { size: 14 }), pluralize(project.stats.plans, 'plano', 'planos')),
      h('span', { class: 'chip' }, icon('file', { size: 14 }), pluralize(project.stats.artifacts, 'documento', 'documentos'))))));
  };
  search.addEventListener('input', render);
  render();
}
