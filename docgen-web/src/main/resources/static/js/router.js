// Hash router: routes never reach the server, so no server-side fallback is needed.

const ID = '([0-9a-f-]{36})';
const ROUTES = [
  { name: 'dashboard', pattern: /^\/?$/ },
  { name: 'dashboard', pattern: /^\/dashboard$/ },
  { name: 'projects', pattern: /^\/projects$/ },
  { name: 'project-new', pattern: /^\/projects\/new$/ },
  { name: 'project', pattern: new RegExp(`^/projects/${ID}$`), keys: ['projectId'] },
  { name: 'project', pattern: new RegExp(`^/projects/${ID}/(summary|architecture|diagram|plan|docs)$`), keys: ['projectId', 'tab'] },
  { name: 'project', pattern: new RegExp(`^/projects/${ID}/docs/${ID}$`), keys: ['projectId', 'artifactId'], extra: { tab: 'docs' } },
  { name: 'documents', pattern: /^\/documents$/ },
  { name: 'settings', pattern: /^\/settings$/ },
  { name: 'models', pattern: /^\/models$/ },
  { name: 'onboarding', pattern: /^\/onboarding$/ },
];

export function parseRoute(hash) {
  const path = (hash || '').replace(/^#/, '').split('?')[0] || '/';
  for (const route of ROUTES) {
    const match = route.pattern.exec(path);
    if (match) {
      const params = { ...(route.extra || {}) };
      (route.keys || []).forEach((key, index) => {
        params[key] = match[index + 1];
      });
      return { name: route.name, params, path };
    }
  }
  return { name: 'not-found', params: {}, path };
}

export function href(name, params = {}) {
  switch (name) {
    case 'dashboard': return '#/dashboard';
    case 'projects': return '#/projects';
    case 'project-new': return '#/projects/new';
    case 'project':
      if (params.artifactId) return `#/projects/${params.projectId}/docs/${params.artifactId}`;
      return params.tab ? `#/projects/${params.projectId}/${params.tab}` : `#/projects/${params.projectId}`;
    case 'documents': return '#/documents';
    case 'settings': return '#/settings';
    case 'models': return '#/models';
    case 'onboarding': return '#/onboarding';
    default: return '#/dashboard';
  }
}
