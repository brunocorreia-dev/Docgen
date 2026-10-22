import { h, replace, clear } from '../dom.js';
import { icon } from '../icons.js';
import { href } from '../router.js';
import { relativeTime, truncate } from '../format.js';
import { errorMessage } from '../messages.js';
import { button, emptyState, errorState, skeleton } from '../ui.js';

const STEPS = [
  ['edit', 'Descreva', 'Conte a ideia, requisitos, tecnologias e restrições.'],
  ['upload', 'Anexe', 'Complemente com PDFs de especificação (texto extraído e redigido).'],
  ['sparkles', 'Planeje', 'A IA propõe arquitetura, componentes, riscos e roadmap.'],
  ['eye', 'Revise', 'Analise o plano e o diagrama; aprove só o que fizer sentido.'],
  ['file', 'Documente', 'Gere, edite e exporte README.md e ARCHITECTURE.md.'],
];

function stat(label, value, iconName, tone) {
  return h('div', { class: ['stat-card', `tone-${tone}`] },
    h('div', { class: 'stat-icon' }, icon(iconName)),
    h('div', null, h('p', { class: 'stat-value' }, String(value)), h('p', { class: 'stat-label' }, label)));
}

export async function renderDashboard(ctx) {
  const statsSlot = h('div', { class: 'stats-grid' }, skeleton(2), skeleton(2), skeleton(2), skeleton(2));
  const recentSlot = h('div', { class: 'card-body' }, skeleton(4));
  const providersSlot = h('div', { class: 'provider-status' }, skeleton(2));

  const view = h('div', { class: 'page dashboard' },
    h('section', { class: 'hero' },
      h('div', { class: 'hero-copy' },
        h('span', { class: 'eyebrow' }, icon('sparkles', { size: 14 }), 'Workspace de engenharia'),
        h('h1', null, 'Da ideia à ', h('span', { class: 'gradient-text' }, 'arquitetura documentada'), '.'),
        h('p', null, 'Descreva o projeto, anexe especificações e deixe a IA propor uma arquitetura estruturada. Você revisa, aprova e publica a documentação.'),
        h('div', { class: 'hero-actions' },
          button('Novo projeto', { variant: 'primary', iconName: 'plus', onClick: () => ctx.navigate(href('project-new')) }),
          button('Ver projetos', { iconName: 'folder', onClick: () => ctx.navigate(href('projects')) }))),
      h('div', { class: 'hero-visual', 'aria-hidden': 'true' },
        h('div', { class: 'orbit orbit-1' }), h('div', { class: 'orbit orbit-2' }), h('div', { class: 'orbit-core' }, icon('layers', { size: 30 })))),
    statsSlot,
    h('div', { class: 'dashboard-grid' },
      h('section', { class: 'card' },
        h('header', { class: 'card-header' }, h('h2', null, 'Projetos recentes'),
          h('a', { class: 'link', href: href('projects') }, 'Ver todos', icon('chevron', { size: 14 }))),
        recentSlot),
      h('section', { class: 'card' },
        h('header', { class: 'card-header' }, h('h2', null, 'Como funciona')),
        h('ol', { class: 'steps' }, STEPS.map(([iconName, title, text], index) =>
          h('li', { class: 'step', style: { '--delay': `${index * 60}ms` } },
            h('span', { class: 'step-icon' }, icon(iconName, { size: 16 })),
            h('div', null, h('strong', null, title), h('p', null, text))))),
        h('header', { class: 'card-header card-header-inner' }, h('h2', null, 'Provedores de IA')),
        providersSlot)));

  ctx.setView(view);

  const [projects, providers] = await Promise.allSettled([ctx.api.listProjects(), ctx.providers()]);
  if (projects.status === 'fulfilled') {
    const list = projects.value.projects;
    const totals = list.reduce((sum, item) => ({
      plans: sum.plans + item.stats.approvedPlans,
      artifacts: sum.artifacts + item.stats.artifacts,
      jobs: sum.jobs + item.stats.activeJobs,
    }), { plans: 0, artifacts: 0, jobs: 0 });
    replace(statsSlot, 
      stat('Projetos', list.length, 'folder', 'blue'),
      stat('Planos aprovados', totals.plans, 'check', 'purple'),
      stat('Documentos gerados', totals.artifacts, 'file', 'cyan'),
      stat('Tarefas em andamento', totals.jobs, 'clock', 'amber'));
    replace(recentSlot, list.length ? h('ul', { class: 'recent-list' }, list.slice(0, 5).map((project) =>
      h('li', null, h('a', { class: 'recent-item', href: href('project', { projectId: project.id }) },
        h('span', { class: 'recent-avatar' }, project.name.slice(0, 1).toUpperCase()),
        h('span', { class: 'recent-text' }, h('strong', null, project.name), h('small', null, truncate(project.descriptionPreview, 90))),
        h('span', { class: 'recent-meta' }, project.stats.activeJobs ? h('span', { class: 'pulse-dot', title: 'Processando' }) : null,
          relativeTime(project.updatedAt)))))) : emptyState({
      title: 'Nenhum projeto ainda',
      message: 'Crie o primeiro projeto para começar a planejar.',
      action: button('Criar projeto', { variant: 'primary', iconName: 'plus', onClick: () => ctx.navigate(href('project-new')) }),
    }));
  } else {
    clear(statsSlot);
    replace(recentSlot, errorState(errorMessage(projects.reason), () => renderDashboard(ctx)));
  }
  if (providers.status === 'fulfilled') {
    replace(providersSlot, ...providers.value.map((provider) => h('div', { class: 'provider-row' },
      icon(provider.remote ? 'cloud' : 'server'),
      h('div', null, h('strong', null, provider.id === 'groq' ? 'Groq' : 'Ollama'),
        h('small', null, provider.remote ? 'Remoto · requer consentimento por operação' : 'Local · os dados ficam na máquina')),
      h('span', { class: ['badge', provider.enabled && provider.configured ? 'badge-success' : 'badge-warning'] },
        !provider.enabled ? 'Desativado' : provider.configured ? 'Disponível' : 'Sem chave'))));
  } else {
    replace(providersSlot, h('p', { class: 'muted' }, errorMessage(providers.reason)));
  }
}
