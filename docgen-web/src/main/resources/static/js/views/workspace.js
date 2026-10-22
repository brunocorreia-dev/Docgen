// Project workspace: brief and attachments on the left, AI actions and the
// activity timeline below them, structured results on the right.
import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { href } from '../router.js';
import { errorMessage, jobErrorMessage, PLAN_STATUS } from '../messages.js';
import { formatDate } from '../format.js';
import { pollJob } from '../jobs.js';
import { button, confirmDialog, emptyState, errorState, loadingState, openDialog, toast } from '../ui.js';
import { attachmentsPanel } from '../components/attachments.js';
import { activityList } from '../components/activity.js';
import { requestConsent } from '../components/consent.js';
import { architecturePanel, planHeader, roadmapPanel, summaryPanel } from '../components/plan-view.js';
import { diagramPanel } from '../components/diagram-view.js';
import { docsPanel } from '../components/docs-view.js';
import { projectForm } from './project-form.js';

const TABS = [
  ['summary', 'Resumo', 'info'],
  ['architecture', 'Arquitetura', 'layers'],
  ['diagram', 'Diagrama', 'diagram'],
  ['plan', 'Plano', 'list'],
  ['docs', 'Documentação', 'file'],
];

export async function renderWorkspace(ctx) {
  const { projectId } = ctx.params;
  ctx.setView(loadingState('Abrindo projeto…'));

  const state = {
    project: null,
    attachments: [],
    plans: [],
    jobs: [],
    artifacts: [],
    providers: [],
    plan: null,
    tab: ctx.params.tab || 'summary',
    artifactId: ctx.params.artifactId || null,
    polling: new Set(),
  };

  try {
    const [project, attachments, plans, jobs, artifacts, providers] = await Promise.all([
      ctx.api.getProject(projectId),
      ctx.api.listAttachments(projectId),
      ctx.api.listPlans(projectId),
      ctx.api.listJobs(projectId),
      ctx.api.listArtifacts(projectId),
      ctx.providers().catch(() => []),
    ]);
    Object.assign(state, {
      project, attachments: attachments.attachments, plans: plans.plans, jobs: jobs.jobs,
      artifacts: artifacts.artifacts, providers,
    });
  } catch (failure) {
    ctx.setView(failure.code === 'not_found'
      ? emptyState({ iconName: 'folder', title: 'Projeto não encontrado', message: 'Ele pode ter sido removido.', action: button('Ver projetos', { onClick: () => ctx.navigate(href('projects')) }) })
      : errorState(errorMessage(failure), () => renderWorkspace(ctx)));
    return;
  }
  ctx.setTitle(state.project.name);

  const header = h('header', { class: 'workspace-header' });
  const briefCard = h('section', { class: 'card' });
  const attachmentsCard = h('section', { class: 'card' });
  const aiCard = h('section', { class: 'card ai-card' });
  const activityCard = h('section', { class: 'card' });
  const results = h('section', { class: 'card results-card', 'aria-label': 'Resultados' });

  ctx.setView(h('div', { class: 'page workspace' }, header,
    h('div', { class: 'workspace-grid' },
      h('div', { class: 'workspace-main' }, briefCard, attachmentsCard, aiCard, activityCard),
      h('div', { class: 'workspace-results' }, results))));

  const activeProvider = () => state.providers.find((provider) => provider.id === state.project.provider)
    || { id: state.project.provider, remote: state.project.provider === 'groq', enabled: true, configured: true, defaultModel: '' };
  const hasActive = (type) => state.jobs.some((job) => job.type === type && (job.status === 'QUEUED' || job.status === 'RUNNING'));

  function renderHeader() {
    const provider = activeProvider();
    replace(header, 
      h('div', { class: 'workspace-title' },
        h('a', { class: 'link back-link', href: href('projects') }, icon('chevron', { size: 14, className: 'flip' }), 'Projetos'),
        h('h1', null, state.project.name),
        h('div', { class: 'chip-row' },
          h('span', { class: 'chip' }, icon(provider.remote ? 'cloud' : 'server', { size: 14 }),
            `${provider.id === 'groq' ? 'Groq' : 'Ollama'} · ${state.project.model || provider.defaultModel || 'modelo padrão'}`),
          h('span', { class: 'chip' }, state.project.outputLanguage === 'en' ? 'Documentação em inglês' : 'Documentação em português'),
          h('span', { class: 'chip' }, `Criado em ${formatDate(state.project.createdAt)}`))),
      h('div', { class: 'page-actions' },
        button('Editar', { iconName: 'edit', onClick: editProject }),
        button('Excluir', { iconName: 'trash', variant: 'danger-ghost', onClick: deleteProject })));
  }

  function list(items) {
    return items.length ? h('ul', { class: 'bullet-list' }, items.map((item) => h('li', null, item))) : h('p', { class: 'muted' }, 'Não informado.');
  }

  function renderBrief() {
    const project = state.project;
    replace(briefCard, 
      h('header', { class: 'card-header' }, h('h2', null, icon('edit', { size: 17 }), 'Descrição e requisitos'),
        h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Editar descrição', on: { click: editProject } }, icon('edit', { size: 16 }))),
      h('div', { class: 'card-body brief' },
        h('p', { class: 'brief-description' }, project.description),
        h('div', { class: 'brief-grid' },
          h('div', null, h('h4', null, 'Funcionais'), list(project.functionalRequirements)),
          h('div', null, h('h4', null, 'Não funcionais'), list(project.nonFunctionalRequirements)),
          h('div', null, h('h4', null, 'Tecnologias preferidas'), project.preferredTechnologies.length
            ? h('div', { class: 'chip-row' }, project.preferredTechnologies.map((tech) => h('span', { class: 'chip' }, tech))) : h('p', { class: 'muted' }, 'Não informado.')),
          h('div', null, h('h4', null, 'Restrições'), list(project.constraints)))));
  }

  function renderAttachments() {
    replace(attachmentsCard, 
      h('header', { class: 'card-header' }, h('h2', null, icon('upload', { size: 17 }), 'Anexos PDF'),
        h('span', { class: 'muted' }, `${state.attachments.length}/5`)),
      h('div', { class: 'card-body' }, attachmentsPanel({
        api: ctx.api,
        projectId,
        attachments: state.attachments,
        onChange: (items) => {
          state.attachments = items;
          attachmentsCard.querySelector('.card-header .muted').textContent = `${items.length}/5`;
        },
      })));
  }

  function renderAiCard() {
    const provider = activeProvider();
    const planning = hasActive('ARCHITECTURE_PLAN');
    replace(aiCard, 
      h('header', { class: 'card-header' }, h('h2', null, icon('sparkles', { size: 17 }), 'Análise com IA')),
      h('div', { class: 'card-body' },
        h('p', { class: 'muted' }, provider.remote
          ? 'Provedor remoto: antes do envio você verá exatamente o que será transmitido e precisará autorizar.'
          : 'Provedor local: o conteúdo é enviado apenas para o Ollama em 127.0.0.1.'),
        !provider.enabled ? h('div', { class: 'callout callout-warning' }, icon('alert', { size: 16 }), h('span', null, 'Este provedor está desativado no servidor. Edite o projeto e escolha outro.')) : null,
        provider.remote && provider.enabled && !provider.configured ? h('div', { class: 'callout callout-warning' }, icon('alert', { size: 16 }),
          h('span', null, 'GROQ_API_KEY não está configurada no servidor; a geração vai falhar.')) : null,
        h('div', { class: 'ai-actions' },
          h('button', { class: ['button', 'button-primary', 'button-glow', planning && 'is-busy'], type: 'button', disabled: planning || !provider.enabled, on: { click: startPlanning } },
            icon('sparkles'), h('span', null, planning ? 'Analisando requisitos…' : state.plans.length ? 'Gerar novo plano' : 'Analisar e gerar plano')),
          h('a', { class: 'button button-ghost', href: href('project', { projectId, tab: 'docs' }) }, icon('file'), h('span', null, 'Documentação')))));
  }

  function renderActivity() {
    replace(activityCard, 
      h('header', { class: 'card-header' }, h('h2', null, icon('clock', { size: 17 }), 'Histórico de interações')),
      h('div', { class: 'card-body' }, activityList(state.jobs.slice(0, 12), { onCancel: cancelJob })));
  }

  async function selectPlan(planId) {
    if (!planId) {
      state.plan = null;
      renderResults();
      return;
    }
    renderResults(true);
    try {
      state.plan = await ctx.api.getPlan(projectId, planId);
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
      state.plan = null;
    }
    renderResults();
  }

  function renderResults(loading = false) {
    const tabs = h('div', { class: 'tabs', role: 'tablist', 'aria-label': 'Resultados do planejamento' }, TABS.map(([id, label, iconName]) => h('button', {
      class: ['tab', state.tab === id && 'is-active'],
      role: 'tab',
      type: 'button',
      id: `tab-${id}`,
      'aria-selected': state.tab === id ? 'true' : 'false',
      'aria-controls': 'results-panel',
      tabindex: state.tab === id ? '0' : '-1',
      on: {
        click: () => switchTab(id),
        keydown: (event) => {
          const index = TABS.findIndex(([tab]) => tab === state.tab);
          if (event.key === 'ArrowRight' || event.key === 'ArrowLeft') {
            event.preventDefault();
            const next = TABS[(index + (event.key === 'ArrowRight' ? 1 : TABS.length - 1)) % TABS.length][0];
            switchTab(next);
            results.querySelector(`#tab-${next}`)?.focus();
          }
        },
      },
    }, icon(iconName, { size: 15 }), h('span', null, label))));

    const planPicker = state.plans.length ? h('label', { class: 'plan-picker' }, h('span', { class: 'visually-hidden' }, 'Plano'),
      h('select', { on: { change: (event) => selectPlan(event.target.value) } }, state.plans.map((plan, index) => h('option', {
        value: plan.id, selected: state.plan?.id === plan.id,
      }, `Plano ${state.plans.length - index} · ${PLAN_STATUS[plan.status].split(' —')[0]} · ${formatDate(plan.createdAt)}`)))) : null;

    let panel;
    if (loading) {
      panel = loadingState('Carregando plano…');
    } else if (!state.plan) {
      const planning = hasActive('ARCHITECTURE_PLAN');
      panel = planning
        ? h('div', { class: 'state state-processing' }, h('div', { class: 'processing-orb', 'aria-hidden': 'true' }),
          h('h3', null, 'A IA está analisando o projeto'), h('p', null, 'Isso pode levar alguns minutos. Você pode continuar navegando; acompanhe no histórico.'))
        : emptyState({ iconName: 'sparkles', title: 'Nenhum plano ainda', message: 'Revise a descrição, anexe PDFs se quiser e clique em "Analisar e gerar plano".' });
    } else {
      const approve = async () => {
        const ok = await confirmDialog({
          title: 'Aprovar plano arquitetural',
          message: 'Confirme que você revisou o plano. Aprovar não torna a proposta correta: apenas libera a geração da documentação a partir dela.',
          confirmLabel: 'Revisei e aprovo',
        });
        if (!ok) return;
        try {
          state.plan = await ctx.api.approvePlan(projectId, state.plan.id);
          state.plans = (await ctx.api.listPlans(projectId)).plans;
          toast('Plano aprovado para documentação.', { tone: 'success' });
          renderResults();
        } catch (failure) {
          toast(errorMessage(failure), { tone: 'error' });
        }
      };
      const body = {
        summary: () => summaryPanel(state.plan),
        architecture: () => architecturePanel(state.plan),
        diagram: () => diagramPanel(state.plan, state.project.name),
        plan: () => roadmapPanel(state.plan),
        docs: () => docsPanel({
          api: ctx.api,
          projectId,
          planView: state.plan,
          artifacts: state.artifacts,
          selectedId: state.artifactId,
          generating: hasActive('PROJECT_DOCUMENTATION'),
          onGenerate: startDocumentation,
          onSelect: (artifactId) => {
            state.artifactId = artifactId;
            renderResults();
          },
        }),
      }[state.tab]();
      panel = h('div', null, planHeader(state.plan, { onApprove: approve }), body);
    }
    replace(results, 
      h('header', { class: 'results-header' }, tabs, planPicker),
      h('div', { class: 'results-body', id: 'results-panel', role: 'tabpanel', 'aria-labelledby': `tab-${state.tab}` }, panel));
  }

  function switchTab(tab) {
    state.tab = tab;
    history.replaceState(null, '', href('project', { projectId, tab }));
    renderResults();
  }

  async function transmissionConsent(operation, planId) {
    const provider = activeProvider();
    if (!provider.remote) return { consentToken: null };
    const preview = await ctx.api.previewTransmission(projectId, { operation, planId });
    if (!preview.remoteEnabled) throw Object.assign(new Error('remote'), { code: 'remote_disabled' });
    const approved = await requestConsent(preview, operation === 'PLAN' ? 'O planejamento arquitetural' : 'A geração da documentação');
    return approved ? { consentToken: preview.consentToken } : null;
  }

  async function startPlanning() {
    try {
      const consent = await transmissionConsent('PLAN');
      if (!consent) {
        toast('Envio cancelado. Nada foi transmitido.', { tone: 'info' });
        return;
      }
      const job = await ctx.api.startPlanning(projectId, consent.consentToken ? { consentToken: consent.consentToken } : {});
      toast('Análise iniciada. O plano aparece aqui quando estiver pronto.', { tone: 'info' });
      track(job);
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  async function startDocumentation() {
    if (!state.plan) return;
    try {
      const consent = await transmissionConsent('DOCUMENTATION', state.plan.id);
      if (!consent) {
        toast('Envio cancelado. Nada foi transmitido.', { tone: 'info' });
        return;
      }
      const job = await ctx.api.startDocumentation(projectId, { planId: state.plan.id, ...(consent.consentToken ? { consentToken: consent.consentToken } : {}) });
      toast('Gerando README.md e ARCHITECTURE.md…', { tone: 'info' });
      track(job);
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  function upsertJob(job) {
    state.jobs = [job, ...state.jobs.filter((item) => item.id !== job.id)]
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
  }

  async function track(job) {
    upsertJob(job);
    renderAiCard();
    renderActivity();
    renderResults();
    if (state.polling.has(job.id)) return;
    state.polling.add(job.id);
    try {
      const final = await pollJob(ctx.api, job.id, {
        signal: ctx.signal,
        onUpdate: (update) => {
          upsertJob(update);
          renderActivity();
        },
      });
      upsertJob(final);
      if (final.status === 'SUCCEEDED') {
        if (final.type === 'ARCHITECTURE_PLAN') {
          state.plans = (await ctx.api.listPlans(projectId)).plans;
          state.tab = 'summary';
          await selectPlan(final.resultId);
          toast('Plano arquitetural pronto para revisão.', { tone: 'success' });
        } else {
          state.artifacts = (await ctx.api.listArtifacts(projectId)).artifacts;
          state.artifactId = null;
          state.tab = 'docs';
          renderResults();
          toast('Documentação gerada. Revise antes de exportar.', { tone: 'success' });
        }
      } else if (final.status === 'FAILED') {
        toast(jobErrorMessage(final.error), { tone: 'error', timeout: 8000 });
      }
    } catch (failure) {
      if (failure.name !== 'AbortError') toast(errorMessage(failure), { tone: 'error' });
    } finally {
      state.polling.delete(job.id);
      if (!ctx.signal.aborted) {
        renderAiCard();
        renderActivity();
        renderResults();
      }
    }
  }

  async function cancelJob(job) {
    try {
      upsertJob(await ctx.api.cancelJob(job.id));
      toast('Tarefa cancelada.', { tone: 'info' });
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
    renderActivity();
    renderAiCard();
  }

  function editProject() {
    let closeDialog = () => {};
    const { form } = projectForm({
      initial: state.project,
      providers: state.providers,
      submitLabel: 'Salvar alterações',
      onSubmit: async (payload) => {
        try {
          state.project = await ctx.api.updateProject(projectId, payload);
          renderHeader();
          renderBrief();
          renderAiCard();
          toast('Projeto atualizado.', { tone: 'success' });
          closeDialog();
        } catch (failure) {
          toast(errorMessage(failure), { tone: 'error' });
        }
      },
    });
    closeDialog = openDialog({ title: 'Editar projeto', body: form, wide: true }).close;
  }

  async function deleteProject() {
    const ok = await confirmDialog({
      title: 'Excluir projeto',
      message: `Excluir "${state.project.name}" com anexos, planos, documentos e histórico? Tarefas em andamento serão canceladas. Esta ação não pode ser desfeita.`,
      confirmLabel: 'Excluir definitivamente',
      danger: true,
    });
    if (!ok) return;
    try {
      await ctx.api.deleteProject(projectId);
      toast('Projeto excluído.', { tone: 'success' });
      ctx.navigate(href('projects'));
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  renderHeader();
  renderBrief();
  renderAttachments();
  renderAiCard();
  renderActivity();
  if (state.artifactId) {
    const artifact = state.artifacts.find((item) => item.id === state.artifactId);
    await selectPlan(artifact?.planId || state.plans[0]?.id);
  } else {
    await selectPlan(state.plans[0]?.id);
  }
  state.jobs.filter((job) => job.status === 'QUEUED' || job.status === 'RUNNING').forEach((job) => track(job));
}
