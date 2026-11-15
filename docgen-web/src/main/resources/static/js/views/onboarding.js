// First-run guide (section 5.1 of the plan): welcome and privacy, Ollama,
// this computer, the recommended model, its download, a quick test, done.
import { h, replace } from '../dom.js';
import { icon, logo } from '../icons.js';
import { errorMessage } from '../messages.js';
import { orderCatalog } from '../models.js';
import { href } from '../router.js';
import { button, loadingState, toast } from '../ui.js';
import { budgetNotes, catalogCard, confirmDownload, hardwareSummary, ollamaHelp, ollamaReady, pullProgress } from '../components/model-ui.js';

export const ONBOARDING_STEPS = [
  ['welcome', 'Boas-vindas'],
  ['ollama', 'Ollama'],
  ['hardware', 'Seu computador'],
  ['model', 'Modelo'],
  ['download', 'Download'],
  ['test', 'Teste'],
  ['done', 'Pronto'],
];

/** A model that is already installed needs no download. */
export function stepAfterChoice(model) {
  return model?.installed ? 'test' : 'download';
}

/** The models to show first: the suggestions; the rest stay behind "Ver todos". */
export function featuredModels(catalog) {
  const tags = [catalog.suggestedGeneral, catalog.suggestedCode].filter(Boolean);
  return catalog.models.filter((model) => tags.includes(model.tag));
}

export async function renderOnboarding(ctx) {
  const state = { step: 'welcome', hardware: null, ollama: null, catalog: null, choice: null, showAll: false };
  const body = h('div', { class: 'wizard-body' });
  const items = ONBOARDING_STEPS.map(([name, label], index) => h('li', { class: 'wizard-step', dataset: { step: name } },
    h('span', { class: 'wizard-dot', 'aria-hidden': 'true' }, String(index + 1)), h('span', null, label)));
  const later = h('a', { class: 'link', href: href('dashboard') }, 'Configurar depois');

  ctx.setView(h('div', { class: 'page page-narrow onboarding' },
    h('header', { class: 'page-header' },
      h('div', { class: 'onboarding-brand' }, logo(40), h('div', null, h('h1', null, 'Configuração inicial'),
        h('p', { class: 'muted' }, 'Sua IA. Seu computador. Suas regras.'))),
      later),
    h('section', { class: 'card card-padded wizard' },
      h('ol', { class: 'wizard-steps', 'aria-label': 'Etapas da configuração' }, items),
      body)));

  function go(step) {
    if (ctx.signal.aborted) return;
    state.step = step;
    const current = ONBOARDING_STEPS.findIndex(([name]) => name === step);
    items.forEach((item, index) => {
      item.classList.toggle('is-current', index === current);
      item.classList.toggle('is-done', index < current);
      if (index === current) item.setAttribute('aria-current', 'step');
      else item.removeAttribute('aria-current');
    });
    RENDER[step]();
  }

  function show(title, ...content) {
    const heading = h('h2', { class: 'wizard-title', tabindex: '-1' }, title);
    replace(body, heading, ...content);
    heading.focus({ preventScroll: true });
  }

  const nav = (...actions) => h('div', { class: 'wizard-actions' }, actions);
  const back = (step) => button('Voltar', { onClick: () => go(step) });

  async function loadHardware() {
    const view = await ctx.api.hardware();
    state.hardware = view.hardware;
    state.ollama = view.ollama;
  }

  const RENDER = {
    welcome() {
      show('Boas-vindas ao DocGen',
        h('p', { class: 'lead' }, 'O DocGen roda modelos de IA Qwen aqui no seu computador, pelo Ollama. Nada de conta, nada de assinatura.'),
        h('ul', { class: 'privacy-list' },
          h('li', null, icon('shield'), h('span', null, h('strong', null, 'Nada sai do seu computador. '), 'Conversas, anexos e histórico ficam aqui.')),
          h('li', null, icon('download'), h('span', null, h('strong', null, 'Internet só para baixar o modelo, '), 'quando você clicar. Depois, funciona offline.')),
          h('li', null, icon('lock'), h('span', null, h('strong', null, 'Sem telemetria. '), 'O DocGen não envia dados de uso a ninguém.'))),
        h('p', { class: 'muted' }, 'Em poucos passos: conferimos o Ollama, olhamos o seu hardware, sugerimos um modelo, baixamos e testamos.'),
        nav(button('Começar', { variant: 'primary', iconName: 'chevron', onClick: () => go('ollama') })));
    },

    async ollama() {
      show('Ollama', loadingState('Procurando o Ollama neste computador…'));
      try {
        await loadHardware();
      } catch (failure) {
        show('Ollama', h('p', { class: 'form-alert' }, errorMessage(failure)), nav(button('Tentar novamente', { iconName: 'refresh', onClick: () => go('ollama') })));
        return;
      }
      if (!state.ollama.reachable) {
        show('Ollama', ollamaHelp({ osName: state.hardware.osName, onRetry: () => go('ollama') }), nav(back('welcome')));
        return;
      }
      show('Ollama', ollamaReady(state.ollama),
        nav(back('welcome'), button('Continuar', { variant: 'primary', iconName: 'chevron', onClick: () => go('hardware') })));
    },

    async hardware() {
      show('Seu computador', loadingState('Avaliando o hardware…'));
      try {
        await loadHardware();
        state.catalog = await ctx.api.modelCatalog();
      } catch (failure) {
        show('Seu computador', h('p', { class: 'form-alert' }, errorMessage(failure)),
          nav(back('ollama'), button('Tentar novamente', { iconName: 'refresh', onClick: () => go('hardware') })));
        return;
      }
      show('Seu computador',
        h('p', { class: 'muted' }, 'Isto é o que o DocGen conseguiu medir. O que não pôde ser medido aparece como "não detectado".'),
        hardwareSummary(state.hardware),
        budgetNotes(state.catalog.budget),
        nav(back('ollama'), button('Ver modelos recomendados', { variant: 'primary', iconName: 'chevron', onClick: () => go('model') })));
    },

    model() {
      const catalog = state.catalog;
      const featured = featuredModels(catalog);
      const list = state.showAll || !featured.length ? orderCatalog(catalog) : featured;
      const suggestedFor = (tag) => [
        tag === catalog.suggestedGeneral ? 'Sugerido para uso geral' : null,
        tag === catalog.suggestedCode ? 'Sugerido para código' : null,
      ].filter(Boolean);
      const choose = (model) => {
        state.choice = model;
        RENDER.model();
      };
      show('Escolha um modelo',
        budgetNotes(catalog.budget),
        featured.length
          ? h('p', { class: 'muted' }, 'Estas são as sugestões para o seu computador. Você pode escolher qualquer outro modelo do catálogo.')
          : h('p', { class: 'callout callout-warning' }, icon('alert'), h('span', null, 'Nenhum modelo do catálogo cabe com folga na memória livre agora. Fechar outros programas pode ajudar; você ainda pode escolher um modelo, por sua conta e risco.')),
        h('div', { class: 'model-grid' }, list.map((model) => catalogCard(model, {
          suggestedFor: suggestedFor(model.tag),
          selected: state.choice?.tag === model.tag,
          onSelect: choose,
        }))),
        featured.length && !state.showAll
          ? button(`Ver todos os ${catalog.models.length} modelos`, { iconName: 'list', onClick: () => { state.showAll = true; RENDER.model(); } })
          : null,
        nav(back('hardware'), button('Continuar', {
          variant: 'primary', iconName: 'chevron', disabled: !state.choice,
          onClick: () => go(stepAfterChoice(state.choice)),
        })));
    },

    async download() {
      const model = state.choice;
      const start = async () => {
        if (!await confirmDownload(model, state.hardware?.modelStorage)) return;
        try {
          follow(await ctx.api.startPull(model.tag));
        } catch (failure) {
          if (failure?.code === 'already_installed') {
            go('test');
            return;
          }
          if (failure?.code === 'pull_in_progress') {
            const running = (await ctx.api.listPulls()).pulls.find((pull) => pull.state === 'RUNNING');
            if (running) {
              follow(running);
              return;
            }
          }
          toast(errorMessage(failure), { tone: 'error' });
        }
      };
      const follow = (pull) => {
        const { element, done } = pullProgress({ api: ctx.api, pull, signal: ctx.signal });
        show(`Baixando ${pull.model}`, h('p', { class: 'muted' }, 'Pode continuar usando o computador. Se fechar esta janela, o download continua no DocGen.'), element);
        done.then((view) => {
          if (view.state === 'SUCCEEDED') {
            state.choice = { ...model, installed: true };
            body.append(nav(button('Testar o modelo', { variant: 'primary', iconName: 'chevron', onClick: () => go('test') })));
          } else {
            body.append(nav(button('Escolher outro modelo', { onClick: () => go('model') }),
              button('Tentar de novo', { variant: 'primary', iconName: 'refresh', onClick: () => start() })));
          }
        }).catch((failure) => {
          if (failure?.name !== 'AbortError') toast(errorMessage(failure), { tone: 'error' });
        });
      };
      show(`Baixar ${model.tag}`,
        h('p', null, model.description),
        h('p', { class: 'muted' }, `Download de ${model.downloadSize}, feito pelo Ollama a partir da biblioteca oficial dele.`),
        nav(back('model'), button('Baixar agora', { variant: 'primary', iconName: 'download', onClick: start })));
    },

    async test() {
      const model = state.choice;
      show(`Testando ${model.tag}`, loadingState('Carregando o modelo na memória. Na primeira vez, isso pode levar alguns minutos…'));
      try {
        const result = await ctx.api.testModel(model.tag);
        await ctx.api.setDefaultModel(model.tag);
        show('Funcionou!',
          h('p', { class: 'muted' }, `O ${model.tag} respondeu e passou a ser o seu modelo padrão:`),
          h('blockquote', { class: 'model-answer' }, result.answer || '(resposta vazia)'),
          result.totalMillis != null ? h('p', { class: 'muted small' }, `Primeira resposta em ${(result.totalMillis / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 })} s, contando o carregamento do modelo. As próximas costumam ser mais rápidas.`) : null,
          nav(button('Concluir', { variant: 'primary', iconName: 'check', onClick: () => go('done') })));
      } catch (failure) {
        show(`Testando ${model.tag}`, h('p', { class: 'form-alert' }, errorMessage(failure)),
          nav(button('Escolher outro modelo', { onClick: () => go('model') }),
            button('Tentar novamente', { variant: 'primary', iconName: 'refresh', onClick: () => go('test') })));
      }
    },

    async done() {
      try {
        await ctx.api.setOnboardingCompleted(true);
      } catch (failure) {
        toast(errorMessage(failure), { tone: 'error' });
      }
      later.hidden = true;
      show('Tudo pronto',
        h('p', { class: 'lead' }, `O DocGen vai usar o ${state.choice?.tag ?? 'modelo escolhido'}, que roda só neste computador.`),
        h('p', { class: 'muted' }, 'O chat local está em desenvolvimento e chega numa próxima versão. Enquanto isso, o modelo já funciona nas ferramentas de documentação e arquitetura. Você troca de modelo quando quiser, em Modelos.'),
        nav(button('Abrir Modelos', { onClick: () => ctx.navigate(href('models')) }),
          button('Começar a usar', { variant: 'primary', iconName: 'chevron', onClick: () => ctx.navigate(href('dashboard')) })));
    },
  };

  go('welcome');
}
