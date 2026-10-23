// Application shell: session bootstrap, sidebar navigation and hash routing.
import { createApi, sessionTokenStore } from './api.js';
import { h, replace } from './dom.js';
import { icon, logo } from './icons.js';
import { href, parseRoute } from './router.js';
import { emptyState, setView, toast, button } from './ui.js';
import { renderLogin } from './views/login.js';
import { renderDesktopPairing } from './views/desktop-pairing.js';
import { isDesktopMode } from './desktop.js';
import { renderDashboard } from './views/dashboard.js';
import { renderProjects } from './views/projects.js';
import { renderNewProject } from './views/project-new.js';
import { renderWorkspace } from './views/workspace.js';
import { renderDocuments } from './views/documents.js';
import { renderSettings, applyMotionPreference } from './views/settings.js';

const root = document.getElementById('app');
const tokenStore = sessionTokenStore();
const api = createApi({
  tokenStore,
  onUnauthorized: () => {
    toast('Sua sessão expirou. Entre novamente.', { tone: 'info' });
    shell = null;
    location.hash = '#/login';
  },
});

let shell = null;
let viewController = null;
let providersPromise = null;
let desktopModePromise = null;

/** True when the DocGen launcher started this server: sessions come from pairing, not passwords. */
function desktopMode() {
  if (!desktopModePromise) desktopModePromise = isDesktopMode(api);
  return desktopModePromise;
}

const NAV = [
  ['dashboard', 'Dashboard', 'dashboard'],
  ['projects', 'Projetos', 'folder'],
  ['project-new', 'Novo projeto', 'plus'],
  ['documents', 'Documentações', 'file'],
  ['settings', 'Configurações', 'settings'],
];

function providers() {
  if (!providersPromise) {
    providersPromise = api.providers().then((data) => data.providers).catch((failure) => {
      providersPromise = null;
      throw failure;
    });
  }
  return providersPromise;
}

async function logout() {
  await api.logout().catch(() => {});
  shell = null;
  providersPromise = null;
  location.hash = '#/login';
}

function buildShell() {
  const outlet = h('main', { class: 'outlet', id: 'main', tabindex: '-1' });
  const title = h('p', { class: 'topbar-title' });
  const navLinks = NAV.map(([name, label, iconName]) => h('a', { class: 'nav-link', href: href(name), dataset: { route: name } },
    icon(iconName), h('span', null, label)));
  const sidebar = h('aside', { class: 'sidebar', id: 'sidebar' },
    h('a', { class: 'brand', href: href('dashboard') }, logo(34), h('span', null, h('strong', null, 'DocGen'), h('small', null, 'Web workspace'))),
    h('nav', { class: 'nav', 'aria-label': 'Navegação principal' }, navLinks),
    h('div', { class: 'sidebar-footer' },
      h('div', { class: 'user-chip' }, h('span', { class: 'user-avatar' }, (tokenStore.username() || '?').slice(0, 1).toUpperCase()),
        h('span', { class: 'user-name' }, tokenStore.username() || 'Usuário')),
      h('button', { class: 'icon-button', type: 'button', 'aria-label': 'Sair', title: 'Sair', on: { click: logout } }, icon('logout'))));
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
};

async function route() {
  viewController?.abort();
  viewController = new AbortController();
  document.body.classList.remove('nav-open');
  const current = parseRoute(location.hash);
  const authenticated = Boolean(tokenStore.get());

  if (!authenticated || current.name === 'login' || current.name === 'desktop') {
    if (authenticated) {
      location.replace(href('dashboard'));
      return;
    }
    shell = null;
    const onAuthenticated = () => {
      const target = href('dashboard');
      if (location.hash === target) route();
      else location.hash = target;
    };
    const signal = viewController.signal;
    if (await desktopMode()) {
      if (signal.aborted) return;
      replace(root, renderDesktopPairing({ api, onAuthenticated, signal }));
      document.title = 'Conectar · DocGen';
    } else {
      if (signal.aborted) return;
      replace(root, renderLogin({ api, onAuthenticated }));
      document.title = 'Entrar · DocGen';
    }
    return;
  }

  if (!shell) {
    shell = buildShell();
    replace(root, shell.element);
  }
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
    providers,
    username: () => tokenStore.username(),
    logout,
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
