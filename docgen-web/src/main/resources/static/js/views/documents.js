import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { href } from '../router.js';
import { formatNumber, relativeTime } from '../format.js';
import { ARTIFACT_LABEL, errorMessage } from '../messages.js';
import { badge, emptyState, errorState, skeleton } from '../ui.js';

export async function renderDocuments(ctx) {
  const body = h('div', { class: 'card' }, skeleton(6));
  ctx.setView(h('div', { class: 'page' },
    h('header', { class: 'page-header' },
      h('div', null, h('h1', null, 'Documentações'), h('p', { class: 'muted' }, 'Todos os documentos gerados nos seus projetos.'))),
    body));
  let artifacts;
  try {
    artifacts = (await ctx.api.listAllArtifacts()).artifacts;
  } catch (failure) {
    replace(body, errorState(errorMessage(failure), () => renderDocuments(ctx)));
    return;
  }
  if (!artifacts.length) {
    replace(body, emptyState({ iconName: 'file', title: 'Nenhuma documentação ainda', message: 'Aprove um plano arquitetural em um projeto e gere README.md e ARCHITECTURE.md.' }));
    return;
  }
  const groups = new Map();
  artifacts.forEach((artifact) => {
    if (!groups.has(artifact.projectId)) groups.set(artifact.projectId, { name: artifact.projectName, items: [] });
    groups.get(artifact.projectId).items.push(artifact);
  });
  replace(body, ...[...groups.entries()].map(([projectId, group]) => h('section', { class: 'doc-group' },
    h('header', { class: 'doc-group-header' }, icon('folder', { size: 16 }),
      h('a', { class: 'link', href: href('project', { projectId }) }, group.name)),
    h('ul', { class: 'doc-list' }, group.items.map((artifact) => h('li', null,
      h('a', { class: 'doc-item', href: href('project', { projectId, artifactId: artifact.id }) },
        icon(artifact.kind === 'DIAGRAM' ? 'diagram' : 'file'),
        h('span', { class: 'doc-name' }, ARTIFACT_LABEL[artifact.kind] || artifact.fileName),
        artifact.edited ? badge('Editado por pessoa', 'purple', 'edit') : badge('Gerado por IA', 'neutral', 'sparkles'),
        h('span', { class: 'muted' }, `rev. ${artifact.revision}`),
        h('span', { class: 'muted' }, `${formatNumber(artifact.characters)} caracteres`),
        h('span', { class: 'muted doc-date' }, relativeTime(artifact.updatedAt)))))))));
}
