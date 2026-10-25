// Application shell: session check, sidebar navigation, hash routing and the
// heartbeat that keeps the desktop app alive while a window is open.
import { createApi, sessionTokenStore } from './api.js';
import { h, replace } from './dom.js';
import { startHeartbeat } from './heartbeat.js';
import { icon, logo } from './icons.js';
import { errorMessage } from './messages.js';
import { href, parseRoute } from './router.js';
import { button, confirmDialog, emptyState, setView, toast } from './ui.js';
import { closedScreen, noSessionScreen } from './views/access.js';
import { renderDashboard } from './views/dashboard.js';
import { renderProjects } from './views/projects.js';
import { renderNewProject } from './views/project-new.js';
import { renderWorkspace } from './views/workspace.js';
import { renderDocuments } from './views/documents.js';
import { renderSettings, applyMotionPreference } from './views/settings.js';
import { renderModels } from './views/models.js';
import { renderOnboarding } from './views/onboarding.js';

const root = document.getElementById('app');
const tokenStore = sessionTokenStore();
let shell = null;
let viewController = null;
let stopHeartbeat = null;
/** The first-run guide opens by itself once per tab; "Configurar depois" is respected until the next start. */
let onboardingChecked = false;

const api = createApi({
  tokenStore,
  onUnauthorized: () => leaveShell(noSessionScreen('ended')),
});

const NAV = [
  ['dashboard', 'Dashboard', 'dashboard'],
  ['projects', 'Projetos', 'folder'],
  ['project-new', 'Novo projeto', 'plus'],
  ['documents', 'Documentações', 'file'],
  ['models', 'Modelos', 'cpu'],
  ['settings', 'Configurações', 'settings'],
];

/** Replaces the app with a full-page screen; the tab keeps no session behind. */
function leaveShell(screen) {
  stopHeartbeat?.();
  stopHeartbeat = null;
  viewController?.abort();
  shell = null;
  replace(root, screen);
  document.title = 'DocGen';
}

/** "Sair do DocGen": stops the whole app, not only this window. */
async function quit() {
  const ok = await confirmDialog({
    title: 'Sair do DocGen',
    message: 'O DocGen será encerrado neste computador. Gerações em andamento serão interrompidas.',
    confirmLabel: 'Sair do DocGen',
    danger: true,
  });
  if (!ok) return;
  try {
    await api.quit();
    leaveShell(closedScreen());
  } catch (failure) {
    toast(errorMessage(failure), { tone: 'error' });
  }
}

function buildShell() {
  const outlet = h('main', { class: 'outlet', id: 'main', tabindex: '-1' });
  const title = h('p', { class: 'topbar-title' });
  const navLinks = NAV.map(([name, label, iconName]) => h('a', { class: 'nav-link', href: href(name), dataset: { route: name } },
    icon(iconName), h('span', null, label)));
  const sidebar = h('aside', { class: 'sidebar', id: 'sidebar' },
    h('a', { class: 'brand', href: href('dashboard') }, logo(34), h('span', null, h('strong', null, 'DocGen'), h('small', null, 'IA local'))),
    h('nav', { class: 'nav', 'aria-label': 'Navegação principal' }, navLinks),
    h('div', { class: 'sidebar-footer' },
      h('div', { class: 'local-chip' }, h('span', { class: 'user-avatar', 'aria-hidden': 'true' }, icon('server', { size: 16 })),
        h('span', { class: 'local-label', title: 'O DocGen roda só neste computador' }, 'Local')),
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Sair do DocGen', title: 'Sair do DocGen', on: { click: quit } }, icon('logout'))));
  const toggle = h('button', { class: 'icon-button menu-toggle', type: 'button', 'aria-label': 'Abrir menu', 'aria-controls': 'sidebar', 'aria-expanded': 'false',
    on: { click: () => {
      const open = !document.body.classList.contains('nav-open');
      document.body.classList.toggle('nav-open', open);
      toggle.setAttribute('aria-expanded', String(open));
    } } }, icon('menu'));
  const status = h('span', { class: 'server-status', title: 'Estado da API' }, h('span', { class: 'status-dot' }), 'API local');
  const element = h('div', { class: 'app' },
    h('a', { class: 'skip-link', href: '#main', on: { click: (event) => { event.preventDefault(); outlet.focus(); } } }, 'Ir para o conteúdo'),
    sidebar,
    h('div', { class: 'scrim', on: { click: () => document.body.classList.remove('nav-open') } }),
    h('div', { class: 'content' },
      h('header', { class: 'topbar' }, toggle, title, h('span', { class: 'toolbar-spacer' }), status,
        button('Novo projeto', { variant: 'primary', iconName: 'plus', small: true, onClick: () => { location.hash = href('project-new'); } })),
      outlet));
  api.health().then(() => status.classList.add('is-online')).catch(() => status.classList.add('is-offline'));
  return { element, outlet, title, navLinks };
}

const VIEWS = {
  dashboard: [renderDashboard, 'Dashboard'],
  projects: [renderProjects, 'Projetos'],
  'project-new': [renderNewProject, 'Novo projeto'],
  project: [renderWorkspace, 'Projeto'],
  documents: [renderDocuments, 'Documentações'],
  settings: [renderSettings, 'Configurações'],
  models: [renderModels, 'Modelos'],
  onboarding: [renderOnboarding, 'Configuração inicial'],
};

/** Opens the first-run guide when it was never completed. */
async function firstRunGuide(current) {
  if (onboardingChecked) return false;
  onboardingChecked = true;
  if (current.name === 'onboarding') return false;
  try {
    const settings = await api.settings();
    if (!settings.onboardingCompleted) {
      location.hash = href('onboarding');
      return true;
    }
  } catch {
    // Without the settings the app still works; the guide stays reachable from Models.
  }
  return false;
}

async function route() {
  viewController?.abort();
  viewController = new AbortController();
  document.body.classList.remove('nav-open');
  const current = parseRoute(location.hash);

  if (!tokenStore.get()) {
    leaveShell(noSessionScreen('missing'));
    return;
  }

  if (!shell) {
    shell = buildShell();
    replace(root, shell.element);
    stopHeartbeat = startHeartbeat({ api, onLost: () => leaveShell(closedScreen()) });
  }
  if (await firstRunGuide(current)) return;
  const navRoute = current.name === 'project' ? 'projects' : current.name;
  shell.navLinks.forEach((link) => {
    const active = link.dataset.route === navRoute;
    link.classList.toggle('is-active', active);
    if (active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });

  const entry = VIEWS[current.name];
  const setTitle = (text) => {
    shell.title.textContent = text;
    document.title = `${text} · DocGen`;
  };
  if (!entry) {
    setTitle('Página não encontrada');
    setView(shell.outlet, emptyState({ iconName: 'search', title: 'Página não encontrada', message: 'O endereço não corresponde a nenhuma tela.',
      action: button('Ir para o dashboard', { onClick: () => { location.hash = href('dashboard'); } }) }));
    return;
  }
  const [render, label] = entry;
  setTitle(label);
  const signal = viewController.signal;
  const ctx = {
    api,
    params: current.params,
    signal,
    quit,
    setTitle,
    navigate: (target) => { location.hash = target; },
    setView: (...children) => {
      if (!signal.aborted) setView(shell.outlet, ...children);
    },
  };
  try {
    await render(ctx);
  } catch (failure) {
    if (failure?.name !== 'AbortError') {
      console.error('View failed', failure?.name);
      toast('Não foi possível carregar esta tela.', { tone: 'error' });
    }
  }
  if (!signal.aborted) shell.outlet.focus({ preventScroll: true });
}

applyMotionPreference();
window.addEventListener('hashchange', route);
route();
