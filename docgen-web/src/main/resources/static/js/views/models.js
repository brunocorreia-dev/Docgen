// Models: Ollama's status, the installed models (default, test, details,
// removal), a running download and the catalog classified for this computer.
import { h, replace } from '../dom.js';
import { formatBytes } from '../format.js';
import { icon } from '../icons.js';
import { errorMessage } from '../messages.js';
import { detected, formatTokens, memorySplit, orderCatalog } from '../models.js';
import { href } from '../router.js';
import { badge, button, confirmDialog, errorState, openDialog, skeleton, toast, withBusy } from '../ui.js';
import { budgetNotes, catalogCard, confirmDownload, hardwareSummary, ollamaHelp, ollamaReady, pullProgress } from '../components/model-ui.js';

export async function renderModels(ctx) {
  const statusSlot = h('div', { class: 'card-body' }, skeleton(2));
  const pullSlot = h('div', { class: 'pull-slot' });
  const installedSlot = h('div', { class: 'card-body' }, skeleton(3));
  const catalogSlot = h('div', { class: 'card-body' }, skeleton(4));
  const hardwareSlot = h('div', { class: 'card-body' }, skeleton(3));
  let storage = null;
  let following = null;

  ctx.setView(h('div', { class: 'page models-page' },
    h('header', { class: 'page-header' },
      h('div', null, h('h1', null, 'Modelos'),
        h('p', { class: 'muted' }, 'Os modelos de IA rodam no Ollama deste computador. Baixe, teste e escolha o modelo padrão.')),
      h('div', { class: 'page-actions' },
        button('Configuração inicial', { iconName: 'sparkles', onClick: () => ctx.navigate(href('onboarding')) }),
        button('Atualizar', { iconName: 'refresh', onClick: () => load() }))),
    h('section', { class: 'card' }, h('header', { class: 'card-header' }, h('h2', null, icon('server'), 'Ollama')), statusSlot),
    pullSlot,
    h('section', { class: 'card' }, h('header', { class: 'card-header' }, h('h2', null, icon('layers'), 'Instalados')), installedSlot),
    h('section', { class: 'card' }, h('header', { class: 'card-header' }, h('h2', null, icon('sparkles'), 'Catálogo para este computador')), catalogSlot),
    h('section', { class: 'card' }, h('header', { class: 'card-header' }, h('h2', null, icon('cpu'), 'Este computador')), hardwareSlot)));

  function follow(pull) {
    if (following === pull.id) return;
    following = pull.id;
    const { element, done } = pullProgress({ api: ctx.api, pull, signal: ctx.signal });
    replace(pullSlot, h('section', { class: 'card card-padded' }, element));
    done.then((view) => {
      following = null;
      if (view.state === 'SUCCEEDED') toast(`${view.model} foi baixado e está pronto para uso.`, { tone: 'success' });
      load();
    }).catch((failure) => {
      following = null;
      if (failure?.name !== 'AbortError') toast(errorMessage(failure), { tone: 'error' });
    });
  }

  async function download(model) {
    if (!await confirmDownload(model, storage)) return;
    try {
      follow(await ctx.api.startPull(model.tag));
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  async function testModel(name, trigger) {
    try {
      const result = await withBusy(trigger, () => ctx.api.testModel(name), 'Testando…');
      openDialog({
        title: `Teste de ${name}`,
        body: h('div', { class: 'test-result' },
          h('blockquote', { class: 'model-answer' }, result.answer || '(resposta vazia)'),
          h('p', { class: 'muted small' }, [
            result.loadMillis != null ? `Carregou em ${(result.loadMillis / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} s` : null,
            result.tokensPerSecond != null ? `${Math.round(result.tokensPerSecond)} tokens por segundo nesta resposta` : null,
          ].filter(Boolean).join(' · '))),
        actions: [{ label: 'Fechar', value: 'close', variant: 'primary' }],
      });
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  async function showDetails(name) {
    try {
      const details = await ctx.api.modelDetails(name);
      openDialog({
        title: name,
        body: h('dl', { class: 'kv' },
          h('dt', null, 'Arquitetura'), h('dd', null, detected(details.architecture)),
          h('dt', null, 'Família'), h('dd', null, detected(details.family)),
          h('dt', null, 'Parâmetros'), h('dd', null, detected(details.parameterSize)),
          h('dt', null, 'Quantização'), h('dd', null, detected(details.quantization)),
          h('dt', null, 'Contexto máximo'), h('dd', null, detected(details.contextLength, formatTokens)),
          h('dt', null, 'Capacidades'), h('dd', null, details.capabilities.length ? details.capabilities.join(', ') : 'não informadas'),
          h('dt', null, 'Onde roda'), h('dd', null, details.remote ? 'fora deste computador (bloqueado pelo DocGen)' : 'neste computador'),
          h('dt', null, 'Catálogo do DocGen'), h('dd', null, details.inCatalog ? 'sim' : 'não')),
        actions: [{ label: 'Fechar', value: 'close' }],
      });
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  async function remove(name) {
    const ok = await confirmDialog({
      title: `Remover ${name}?`,
      message: 'O modelo será apagado do Ollama e do disco. Para usá-lo de novo, será preciso baixá-lo outra vez.',
      confirmLabel: 'Remover', danger: true,
    });
    if (!ok) return;
    try {
      await ctx.api.deleteModel(name);
      toast(`${name} foi removido.`, { tone: 'success' });
      load();
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  async function makeDefault(name) {
    try {
      await ctx.api.setDefaultModel(name);
      toast(`${name} é agora o modelo padrão.`, { tone: 'success' });
      load();
    } catch (failure) {
      toast(errorMessage(failure), { tone: 'error' });
    }
  }

  function installedTable(list) {
    if (!list.models.length) {
      return h('p', { class: 'muted' }, 'Nenhum modelo instalado ainda. Escolha um no catálogo abaixo.');
    }
    return h('div', { class: 'table-wrap' }, h('table', { class: 'data-table installed-table' },
      h('thead', null, h('tr', null, ['Modelo', 'Tamanho', 'Contexto', 'Situação', 'Ações'].map((label) => h('th', { scope: 'col' }, label)))),
      h('tbody', null, list.models.map((model) => {
        const split = memorySplit(model.loaded);
        const testButton = button('Testar', { iconName: 'play', small: true, onClick: () => testModel(model.name, testButton) });
        return h('tr', null,
          h('td', null, h('strong', null, model.name), h('div', { class: 'muted small' }, [model.parameterSize, model.quantization].filter(Boolean).join(' · ') || '—')),
          h('td', null, formatBytes(model.sizeBytes)),
          h('td', null, detected(model.contextLength, formatTokens)),
          h('td', null, h('div', { class: 'chip-row' },
            model.isDefault ? badge('Padrão', 'purple', 'star') : null,
            split ? badge(`Na memória · ${split}`, 'info', 'zap') : null,
            model.remote ? badge('Roda fora do computador: bloqueado', 'danger', 'lock')
              : model.inCatalog ? null : badge('Fora do catálogo do DocGen', 'neutral'))),
          h('td', null, h('div', { class: 'row-actions' },
            model.usable && !model.isDefault ? button('Tornar padrão', { iconName: 'star', small: true, onClick: () => makeDefault(model.name) }) : null,
            model.usable ? testButton : null,
            button('Detalhes', { iconName: 'info', small: true, onClick: () => showDetails(model.name) }),
            button('Remover', { iconName: 'trash', variant: 'danger-ghost', small: true, onClick: () => remove(model.name) }))));
      }))));
  }

  function catalogView(catalog) {
    const suggestions = { [catalog.suggestedGeneral]: [], [catalog.suggestedCode]: [] };
    if (catalog.suggestedGeneral) suggestions[catalog.suggestedGeneral].push('Sugerido para uso geral');
    if (catalog.suggestedCode) suggestions[catalog.suggestedCode].push('Sugerido para código');
    return h('div', null,
      budgetNotes(catalog.budget),
      h('p', { class: 'muted small' }, `Estimativas para ${formatTokens(catalog.contextTokens)} de contexto. Catálogo verificado em ${catalog.verifiedOn}.`),
      h('div', { class: 'model-grid' }, orderCatalog(catalog).map((model) => catalogCard(model, {
        suggestedFor: suggestions[model.tag] || [],
        actions: model.installed ? [] : [button('Baixar', { iconName: 'download', small: true, variant: model.fit === 'NOT_RECOMMENDED' ? 'ghost' : 'primary', onClick: () => download(model) })],
      }))));
  }

  async function load() {
    const [hardware, catalog, installed, pulls] = await Promise.allSettled([
      ctx.api.hardware(), ctx.api.modelCatalog(), ctx.api.installedModels(), ctx.api.listPulls()]);
    if (ctx.signal.aborted) return;
    if (hardware.status === 'fulfilled') {
      storage = hardware.value.hardware.modelStorage;
      const status = hardware.value.ollama;
      replace(statusSlot, status.reachable ? ollamaReady(status) : ollamaHelp({ osName: hardware.value.hardware.osName, onRetry: () => load() }));
      replace(hardwareSlot, hardwareSummary(hardware.value.hardware));
    } else {
      replace(statusSlot, errorState(errorMessage(hardware.reason), () => load()));
      replace(hardwareSlot, errorState(errorMessage(hardware.reason), () => load()));
    }
    replace(installedSlot, installed.status === 'fulfilled'
      ? installedTable(installed.value)
      : h('p', { class: 'muted' }, errorMessage(installed.reason)));
    replace(catalogSlot, catalog.status === 'fulfilled'
      ? catalogView(catalog.value)
      : errorState(errorMessage(catalog.reason), () => load()));
    const running = pulls.status === 'fulfilled' ? pulls.value.pulls.find((pull) => pull.state === 'RUNNING') : null;
    if (running) follow(running);
    else if (!following) replace(pullSlot);
  }

  await load();
}

