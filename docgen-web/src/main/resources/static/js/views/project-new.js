import { h } from '../dom.js';
import { href } from '../router.js';
import { errorMessage } from '../messages.js';
import { toast } from '../ui.js';
import { projectForm } from './project-form.js';

export async function renderNewProject(ctx) {
  const { form, showErrors } = projectForm({
    showDemo: true,
    onSubmit: async (payload) => {
      try {
        const project = await ctx.api.createProject(payload);
        toast('Projeto criado. Anexe PDFs ou gere o plano arquitetural.', { tone: 'success' });
        ctx.navigate(href('project', { projectId: project.id }));
      } catch (failure) {
        if (failure.code === 'invalid_request') showErrors({ name: ' ' });
        toast(errorMessage(failure), { tone: 'error' });
      }
    },
  });
  ctx.setView(h('div', { class: 'page page-narrow' },
    h('header', { class: 'page-header' },
      h('div', null, h('h1', null, 'Novo projeto'),
        h('p', { class: 'muted' }, 'Tudo o que você escrever é tratado como dado não confiável e validado no servidor.'))),
    h('div', { class: 'card card-padded' }, form)));
}
