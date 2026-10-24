// Entry point of /launch (see launch-flow.js).
import { createApi, sessionTokenStore } from './api.js';
import { replace } from './dom.js';
import { LAUNCH_MESSAGES, runLaunch } from './launch-flow.js';
import { accessScreen } from './views/access.js';

const root = document.getElementById('launch');
const api = createApi({ tokenStore: sessionTokenStore() });

runLaunch({
  hash: location.hash,
  clearFragment: () => history.replaceState(null, '', '/launch'),
  api,
  goHome: () => location.replace('/'),
}).then((result) => {
  if (!result.ok) {
    replace(root, accessScreen({ title: 'Não foi possível abrir o DocGen', message: LAUNCH_MESSAGES[result.reason], tone: 'error' }));
  }
});
