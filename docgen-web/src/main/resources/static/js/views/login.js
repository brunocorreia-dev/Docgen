import { h } from '../dom.js';
import { icon, logo } from '../icons.js';
import { errorMessage } from '../messages.js';

export function renderLogin({ api, onAuthenticated }) {
  const error = h('p', { class: 'form-alert', role: 'alert', hidden: true });
  const username = h('input', { id: 'login-username', type: 'text', autocomplete: 'username', required: true, maxlength: 64, autofocus: true });
  const password = h('input', { id: 'login-password', type: 'password', autocomplete: 'current-password', required: true, maxlength: 256 });
  const submit = h('button', { class: 'button button-primary button-block', type: 'submit' }, icon('lock'), h('span', null, 'Entrar'));

  const form = h('form', { class: 'login-form', on: { submit: async (event) => {
    event.preventDefault();
    error.hidden = true;
    submit.disabled = true;
    submit.classList.add('is-busy');
    try {
      await api.login(username.value.trim(), password.value);
      password.value = '';
      onAuthenticated();
    } catch (failure) {
      error.textContent = errorMessage(failure);
      error.hidden = false;
      password.select();
    } finally {
      submit.disabled = false;
      submit.classList.remove('is-busy');
    }
  } } },
  h('div', { class: 'field' }, h('label', { for: 'login-username' }, 'Usuário'), username),
  h('div', { class: 'field' }, h('label', { for: 'login-password' }, 'Senha'), password),
  error,
  submit);

  return h('main', { class: 'login-screen' },
    h('div', { class: 'login-aurora', 'aria-hidden': 'true' }),
    h('section', { class: 'login-card' },
      h('div', { class: 'login-brand' }, logo(44), h('div', null, h('h1', null, 'DocGen'), h('p', null, 'Arquitetura e documentação assistidas por IA'))),
      form,
      h('ul', { class: 'login-facts' },
        h('li', null, icon('shield', { size: 16 }), 'Servidor local (127.0.0.1); credenciais definidas pelo operador.'),
        h('li', null, icon('lock', { size: 16 }), 'A sessão fica só nesta aba e expira por inatividade.'),
        h('li', null, icon('cloud', { size: 16 }), 'Nada é enviado a provedores remotos sem a sua confirmação.'))));
}
