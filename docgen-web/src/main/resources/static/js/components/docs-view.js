// Documentation review: Markdown preview with outline, in-place editing with
// server-side passive-Markdown validation, downloads and ZIP export.
import { h, replace } from '../dom.js';
import { icon } from '../icons.js';
import { formatDate, formatNumber } from '../format.js';
import { ARTIFACT_LABEL, errorMessage } from '../messages.js';
import { renderMarkdown } from '../markdown.js';
import { LIMITS } from '../validation.js';
import { aiNotice, badge, downloadBlob, emptyState, loadingState, toast } from '../ui.js';

const ORDER = ['README', 'ARCHITECTURE', 'DIAGRAM'];

export function docsPanel({ api, projectId, planView, artifacts, selectedId, onGenerate, onSelect, generating }) {
  const forPlan = artifacts.filter((artifact) => artifact.planId === planView.id);
  const latest = ORDER.map((kind) => forPlan.find((artifact) => artifact.kind === kind)).filter(Boolean);
  const approved = planView.status === 'APPROVED';

  const generateButton = h('button', {
    class: 'button button-primary', type: 'button', disabled: !approved || generating,
    title: approved ? null : 'Aprove o plano antes de gerar a documentação',
    on: { click: onGenerate },
  }, icon('sparkles'), h('span', null, latest.length ? 'Gerar novamente' : 'Gerar README e ARCHITECTURE'));

  if (!latest.length) {
    return h('div', { class: 'result-panel' },
      emptyState({
        iconName: 'file',
        title: approved ? 'Pronto para documentar' : 'Revise o plano primeiro',
        message: approved
          ? 'Gere README.md e ARCHITECTURE.md a partir do plano aprovado. Você poderá revisar e editar tudo.'
          : 'A documentação definitiva só é gerada a partir de um plano que você revisou e aprovou na aba Resumo.',
        action: generateButton,
      }));
  }

  const current = latest.find((artifact) => artifact.id === selectedId) || latest[0];
  const viewer = h('div', { class: 'doc-viewer' }, loadingState('Carregando documento…'));
  const tabs = h('div', { class: 'doc-tabs', role: 'tablist', 'aria-label': 'Documentos' }, latest.map((artifact) => h('button', {
    class: ['doc-tab', artifact.id === current.id && 'is-active'],
    role: 'tab',
    type: 'button',
    'aria-selected': artifact.id === current.id ? 'true' : 'false',
    on: { click: () => onSelect(artifact.id) },
  }, icon(artifact.kind === 'DIAGRAM' ? 'diagram' : 'file', { size: 15 }), ARTIFACT_LABEL[artifact.kind] || artifact.fileName)));

  const exportButton = h('button', { class: 'button button-ghost', type: 'button', on: { click: async () => {
    try {
      const { blob, fileName } = await api.exportProject(projectId, planView.id);
      downloadBlob(blob, fileName);
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  } } }, icon('download'), h('span', null, 'Exportar ZIP'));

  loadDocument(current);

  async function loadDocument(summary) {
    let artifact;
    try {
      artifact = await api.getArtifact(projectId, summary.id);
    } catch (failure) {
      replace(viewer, h('p', { class: 'form-alert' }, errorMessage(failure)));
      return;
    }
    showPreview(artifact);
  }

  function meta(artifact) {
    return h('div', { class: 'doc-meta' },
      artifact.edited ? badge('Editado por pessoa', 'purple', 'edit') : badge('Gerado por IA — revisar', 'warning', 'sparkles'),
      h('small', { class: 'muted' }, `Revisão ${artifact.revision} · atualizado em ${formatDate(artifact.updatedAt)} · ${formatNumber(artifact.content.length)} caracteres`),
      h('span', { class: 'toolbar-spacer' }),
      artifact.editable ? h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => showEditor(artifact) } },
        icon('edit', { size: 15 }), h('span', null, 'Editar')) : null,
      h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: async () => {
        try {
          const { blob, fileName } = await api.downloadArtifact(projectId, artifact.id);
          downloadBlob(blob, fileName);
        } catch (failure) {
          toast(errorMessage(failure), { tone: 'error' });
        }
      } } }, icon('download', { size: 15 }), h('span', null, 'Baixar')));
  }

  function showPreview(artifact) {
    if (artifact.kind === 'DIAGRAM') {
      replace(viewer, meta(artifact), h('pre', { class: 'md-code' }, h('code', null, artifact.content)));
      return;
    }
    const { nodes, outline } = renderMarkdown(artifact.content, { idPrefix: `doc-${artifact.kind.toLowerCase()}` });
    replace(viewer, meta(artifact), h('div', { class: 'doc-layout' },
      outline.length > 2 ? h('nav', { class: 'doc-outline', 'aria-label': 'Sumário' },
        h('p', { class: 'roadmap-label' }, 'Sumário'),
        h('ul', null, outline.filter((item) => item.level <= 3).map((item) => h('li', { class: `outline-level-${item.level}` },
          h('a', { href: `#${item.id}`, on: { click: (event) => {
            event.preventDefault();
            document.getElementById(item.id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          } } }, item.title))))) : null,
      h('article', { class: 'markdown-body' }, nodes)));
  }

  function showEditor(artifact) {
    const area = h('textarea', { class: 'doc-editor', value: artifact.content, spellcheck: true, 'aria-label': `Editar ${artifact.fileName}`, maxlength: LIMITS.artifact });
    const preview = h('article', { class: 'markdown-body doc-live-preview' });
    const error = h('p', { class: 'form-alert', role: 'alert', hidden: true });
    const refresh = () => replace(preview, ...renderMarkdown(area.value, { idPrefix: 'live' }).nodes);
    let timer = null;
    area.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(refresh, 180);
    });
    refresh();
    const save = h('button', { class: 'button button-primary button-small', type: 'button', on: { click: async () => {
      save.disabled = true;
      error.hidden = true;
      try {
        const updated = await api.updateArtifact(projectId, artifact.id, area.value, artifact.revision);
        toast('Documento salvo como revisão humana.', { tone: 'success' });
        showPreview(updated);
      } catch (failure) {
        error.textContent = errorMessage(failure);
        error.hidden = false;
      } finally {
        save.disabled = false;
      }
    } } }, icon('check', { size: 15 }), h('span', null, 'Salvar'));
    replace(viewer, 
      h('div', { class: 'doc-meta' }, badge('Editando', 'info', 'edit'),
        h('small', { class: 'muted' }, 'Markdown passivo: sem HTML, imagens ou scripts. O servidor valida ao salvar.'),
        h('span', { class: 'toolbar-spacer' }),
        h('button', { class: 'button button-small button-ghost', type: 'button', on: { click: () => showPreview(artifact) } }, h('span', null, 'Cancelar')),
        save),
      error,
      h('div', { class: 'editor-split' }, area, preview));
    area.focus();
  }

  return h('div', { class: 'result-panel' },
    aiNotice('Documentação gerada por IA a partir do plano aprovado. Revise e edite antes de publicar.'),
    h('div', { class: 'doc-actions' }, tabs, h('span', { class: 'toolbar-spacer' }), exportButton, generateButton),
    viewer);
}
