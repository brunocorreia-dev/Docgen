// HTTP client for the DocGen REST API. The only credential is a session token
// sent in the Authorization header (never a cookie), born from the single-use
// launch token the desktop launcher puts in the /launch address (ADR 0002).

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const TOKEN_KEY = 'docgen.session';

/**
 * Session storage scoped to this tab (sessionStorage is per origin and never
 * sent automatically). A new tab has no session: it opens through the launcher.
 */
export function sessionTokenStore(storage = globalThis.sessionStorage) {
  return {
    get() {
      try {
        const raw = storage.getItem(TOKEN_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        return parsed && typeof parsed.token === 'string' ? parsed.token : null;
      } catch {
        return null;
      }
    },
    set(token) {
      try {
        storage.setItem(TOKEN_KEY, JSON.stringify({ token }));
      } catch {
        // Storage can be unavailable; the session then lasts for this page only.
      }
    },
    clear() {
      try {
        storage.removeItem(TOKEN_KEY);
      } catch {
        // Nothing to clear.
      }
    },
  };
}

export function memoryTokenStore() {
  let token = null;
  return {
    get: () => token,
    set: (value) => { token = value; },
    clear: () => { token = null; },
  };
}

const id = (value) => encodeURIComponent(String(value));

export function createApi({ fetchImpl = (...args) => globalThis.fetch(...args), tokenStore = memoryTokenStore(), onUnauthorized } = {}) {
  async function request(method, path, { json, body, headers = {}, raw = false, signal } = {}) {
    const requestHeaders = { Accept: 'application/json', ...headers };
    const token = tokenStore.get();
    if (token) requestHeaders.Authorization = `Bearer ${token}`;
    let payload = body;
    if (json !== undefined) {
      requestHeaders['Content-Type'] = 'application/json';
      payload = JSON.stringify(json);
    }
    let response;
    try {
      response = await fetchImpl(path, {
        method,
        headers: requestHeaders,
        body: payload,
        signal,
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        referrerPolicy: 'no-referrer',
      });
    } catch (failure) {
      if (failure?.name === 'AbortError') throw failure;
      throw new ApiError(0, 'network_error', 'Network request failed');
    }
    if (response.status === 401 && token) {
      tokenStore.clear();
      onUnauthorized?.();
    }
    if (!response.ok) {
      let data = null;
      try {
        data = await response.json();
      } catch {
        data = null;
      }
      const code = typeof data?.code === 'string' ? data.code : `http_${response.status}`;
      const message = typeof data?.message === 'string' ? data.message : '';
      throw new ApiError(response.status, code, message);
    }
    if (raw) return response;
    if (response.status === 204) return null;
    const type = response.headers.get('Content-Type') || '';
    return type.includes('application/json') ? response.json() : response.text();
  }

  const post = (path, json = {}, options = {}) => request('POST', path, { json, ...options });

  return {
    tokenStore,

    /** Exchanges the launch token from the /launch address for this tab's session. */
    async startSession(launchToken) {
      const session = await post('/api/v1/session', { launchToken });
      tokenStore.set(session.accessToken);
      return session;
    },
    async endSession() {
      try {
        if (tokenStore.get()) await request('DELETE', '/api/v1/session');
      } finally {
        tokenStore.clear();
      }
    },
    session: () => request('GET', '/api/v1/session'),
    health: () => request('GET', '/api/v1/health'),
    heartbeat: () => post('/api/v1/desktop/heartbeat'),
    /** "Sair do DocGen": the server answers, then stops. */
    async quit() {
      await post('/api/v1/desktop/shutdown');
      tokenStore.clear();
    },
    eraseAllData: () => request('DELETE', '/api/v1/data'),

    ollamaStatus: () => request('GET', '/api/v1/ollama/status'),
    hardware: () => request('GET', '/api/v1/hardware'),
    modelCatalog: () => request('GET', '/api/v1/models/catalog'),
    installedModels: () => request('GET', '/api/v1/models'),
    // Model tags may contain ":" and "/", so they travel as a query parameter or in the body.
    modelDetails: (name) => request('GET', `/api/v1/models/details?name=${id(name)}`),
    deleteModel: (name) => request('DELETE', `/api/v1/models?name=${id(name)}`),
    testModel: (model) => post('/api/v1/models/test', { model }),
    startPull: (model) => post('/api/v1/models/pulls', { model }),
    listPulls: () => request('GET', '/api/v1/models/pulls'),
    pullStatus: (pullId, { signal } = {}) => request('GET', `/api/v1/models/pulls/${id(pullId)}`, { signal }),
    cancelPull: (pullId) => request('DELETE', `/api/v1/models/pulls/${id(pullId)}`),

    settings: () => request('GET', '/api/v1/settings'),
    setDefaultModel: (model) => request('PUT', '/api/v1/settings/default-model', { json: { model } }),
    setOnboardingCompleted: (completed) => request('PUT', '/api/v1/settings/onboarding', { json: { completed } }),

    listProjects: () => request('GET', '/api/v1/projects'),
    getProject: (projectId) => request('GET', `/api/v1/projects/${id(projectId)}`),
    createProject: (project) => post('/api/v1/projects', project),
    updateProject: (projectId, project) => request('PUT', `/api/v1/projects/${id(projectId)}`, { json: project }),
    deleteProject: (projectId) => request('DELETE', `/api/v1/projects/${id(projectId)}`),

    listAttachments: (projectId) => request('GET', `/api/v1/projects/${id(projectId)}/attachments`),
    uploadAttachment: (projectId, file, { signal } = {}) => request('POST', `/api/v1/projects/${id(projectId)}/attachments`, {
      body: file,
      // The name is display metadata only; the server never uses it as a path.
      headers: { 'Content-Type': 'application/pdf', 'X-Attachment-Name': encodeURIComponent(file.name || 'document.pdf') },
      signal,
    }),
    deleteAttachment: (projectId, attachmentId) =>
      request('DELETE', `/api/v1/projects/${id(projectId)}/attachments/${id(attachmentId)}`),

    startPlanning: (projectId, options = {}) => post(`/api/v1/projects/${id(projectId)}/plans`, options),
    listPlans: (projectId) => request('GET', `/api/v1/projects/${id(projectId)}/plans`),
    getPlan: (projectId, planId) => request('GET', `/api/v1/projects/${id(projectId)}/plans/${id(planId)}`),
    approvePlan: (projectId, planId) => post(`/api/v1/projects/${id(projectId)}/plans/${id(planId)}/approval`),
    startDocumentation: (projectId, options) => post(`/api/v1/projects/${id(projectId)}/documentation`, options),

    job: (jobId, { signal } = {}) => request('GET', `/api/v1/jobs/${id(jobId)}`, { signal }),
    cancelJob: (jobId) => post(`/api/v1/jobs/${id(jobId)}/cancellation`),
    listJobs: (projectId) => request('GET', `/api/v1/projects/${id(projectId)}/jobs`),

    listAllArtifacts: () => request('GET', '/api/v1/artifacts'),
    listArtifacts: (projectId) => request('GET', `/api/v1/projects/${id(projectId)}/artifacts`),
    getArtifact: (projectId, artifactId) =>
      request('GET', `/api/v1/projects/${id(projectId)}/artifacts/${id(artifactId)}`),
    updateArtifact: (projectId, artifactId, content, expectedRevision) =>
      request('PUT', `/api/v1/projects/${id(projectId)}/artifacts/${id(artifactId)}`, {
        json: { content, expectedRevision },
      }),
    async downloadArtifact(projectId, artifactId) {
      const response = await request('GET', `/api/v1/projects/${id(projectId)}/artifacts/${id(artifactId)}/content`, { raw: true });
      return { blob: await response.blob(), fileName: fileNameFrom(response, 'document.md') };
    },
    async exportProject(projectId, planId) {
      const query = planId ? `?planId=${id(planId)}` : '';
      const response = await request('GET', `/api/v1/projects/${id(projectId)}/export${query}`, { raw: true });
      return { blob: await response.blob(), fileName: fileNameFrom(response, 'docgen-export.zip') };
    },
  };
}

export function fileNameFrom(response, fallback) {
  const disposition = response.headers.get('Content-Disposition') || '';
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="([^"]+)"/i.exec(disposition);
  let name = fallback;
  try {
    name = encoded ? decodeURIComponent(encoded[1]) : plain ? plain[1] : fallback;
  } catch {
    name = fallback;
  }
  const safe = name.replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 80);
  return safe || fallback;
}
