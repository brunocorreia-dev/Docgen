// HTTP client for the DocGen REST API. Credentials are a bearer token sent in
// the Authorization header only (never cookies), which is why cross-site
// requests cannot ride on the user's session.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const TOKEN_KEY = 'docgen.session';

/** Token storage in sessionStorage: scoped to this tab and cleared when it closes. */
export function sessionTokenStore(storage = globalThis.sessionStorage) {
  return {
    get() {
      try {
        const raw = storage.getItem(TOKEN_KEY);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (!parsed || typeof parsed.token !== 'string') return null;
        if (parsed.expiresAt && Date.parse(parsed.expiresAt) <= Date.now()) {
          storage.removeItem(TOKEN_KEY);
          return null;
        }
        return parsed.token;
      } catch {
        return null;
      }
    },
    username() {
      try {
        return JSON.parse(storage.getItem(TOKEN_KEY) || 'null')?.username ?? null;
      } catch {
        return null;
      }
    },
    set(token, expiresAt, username) {
      try {
        storage.setItem(TOKEN_KEY, JSON.stringify({ token, expiresAt, username }));
      } catch {
        // Storage can be unavailable (private mode); the session then lasts for this page only.
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
  let state = null;
  return {
    get: () => state?.token ?? null,
    username: () => state?.username ?? null,
    set: (token, expiresAt, username) => { state = { token, expiresAt, username }; },
    clear: () => { state = null; },
  };
}

const id = (value) => encodeURIComponent(String(value));

export function createApi({ fetchImpl = (...args) => globalThis.fetch(...args), tokenStore = memoryTokenStore(), onUnauthorized } = {}) {
  async function request(method, path, { json, body, headers = {}, raw = false, signal } = {}) {
    const requestHeaders = { 'X-DocGen-Client': 'web', Accept: 'application/json', ...headers };
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

    async login(username, password) {
      const session = await post('/api/v1/auth/token', { username, password });
      tokenStore.set(session.accessToken, session.expiresAt, session.username);
      return session;
    },
    async logout() {
      try {
        if (tokenStore.get()) await request('DELETE', '/api/v1/auth/token');
      } finally {
        tokenStore.clear();
      }
    },
    session: () => request('GET', '/api/v1/auth/session'),

    // Desktop pairing (only when the DocGen launcher started the server). The
    // poll secret stays in memory and travels only in a same-origin JSON body.
    desktopStatus: () => request('GET', '/api/v1/desktop/status'),
    startPairing: () => post('/api/v1/desktop/pairings', {}),
    async pollPairing(pairingId, pollSecret, { signal } = {}) {
      const response = await request('POST', `/api/v1/desktop/pairings/${id(pairingId)}/token`,
        { json: { pollSecret }, raw: true, signal });
      let body = null;
      try {
        body = await response.json();
      } catch {
        body = null;
      }
      if (response.status === 202) return { pending: true, expiresAt: body?.expiresAt ?? null };
      if (response.status !== 200 || typeof body?.accessToken !== 'string' || typeof body?.username !== 'string') {
        throw new ApiError(response.status, 'invalid_response', 'Unexpected pairing response');
      }
      tokenStore.set(body.accessToken, body.expiresAt, body.username);
      return { pending: false, username: body.username };
    },
    health: () => request('GET', '/api/v1/health'),
    providers: () => request('GET', '/api/v1/providers'),

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

    previewTransmission: (projectId, preview) =>
      post(`/api/v1/projects/${id(projectId)}/transmission-previews`, preview),
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
