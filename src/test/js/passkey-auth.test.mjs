import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/passkey.js', import.meta.url), 'utf8');
const loginHtml = await readFile(new URL('../../main/resources/static/index.html', import.meta.url), 'utf8');
const registerHtml = await readFile(new URL('../../main/resources/static/register.html', import.meta.url), 'utf8');
const authCss = await readFile(new URL('../../main/resources/static/auth.css', import.meta.url), 'utf8');
const welcome = await readFile(new URL('../../main/jte/welcome.jte', import.meta.url), 'utf8');

const response = (status, body = '') => ({status, ok: status >= 200 && status < 300,
  text: async () => typeof body === 'string' ? body : JSON.stringify(body), json: async () => body});
const optionsBody = {requestId: 42, publicKey: {challenge: 'AQID', user: {id: 'BAU'},
  allowCredentials: [{id: 'Bgc', type: 'public-key'}], excludeCredentials: [{id: 'CAk', type: 'public-key'}]}};
const buffer = (...bytes) => new Uint8Array(bytes).buffer;
const credential = () => ({id: 'id', type: 'public-key', rawId: buffer(1, 2), authenticatorAttachment: 'platform',
  response: {clientDataJSON: buffer(3), authenticatorData: buffer(4), signature: buffer(5),
    userHandle: buffer(6), attestationObject: buffer(7)}, getClientExtensionResults: () => ({})});

class Element {
  value = ' alice ';
  disabled = false;
  hidden = false;
  textContent = '';
  dataset = {};
  attributes = new Map();
  listeners = new Map();
  focused = 0;
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  focus() { this.focused++; }
}

function fixture({registration = false, options = async () => response(200, optionsBody),
  verify = async () => response(204), createCredential = async () => credential(),
  supportsPasskeys = true, secure = true, withForm = true} = {}) {
  const form = new Element(), input = new Element(), button = new Element(), status = new Element();
  status.hidden = true;
  button.textContent = registration ? 'Create account with passkey' : 'Sign in with passkey';
  const elements = withForm ? {[registration ? 'registerForm' : 'loginForm']: form,
    [registration ? 'newname' : 'username']: input, [registration ? 'registerButton' : 'loginButton']: button,
    log: status} : {};
  const requests = [], prompts = [], timers = new Map(), windowListeners = new Map();
  let ready, prevented = 0, nextTimer = 0;
  const body = {};
  const context = {
    document: {readyState: 'loading', body, activeElement: body,
      getElementById: id => elements[id], addEventListener(name, callback) { assert.equal(name, 'DOMContentLoaded'); ready = callback; }},
    window: {PublicKeyCredential: supportsPasskeys ? function () {} : undefined, isSecureContext: secure,
      location: {href: registration ? '/register.html' : '/'},
      addEventListener: (name, callback) => windowListeners.set(name, callback),
      setTimeout(callback, delay) { const id = ++nextTimer; timers.set(id, {callback, delay}); return id; },
      clearTimeout: id => timers.delete(id)},
    navigator: {credentials: Object.fromEntries(['get', 'create'].map(method => [method, async options => {
      prompts.push({method, options}); return createCredential(options);
    }]))},
    async fetch(url, init) {
      requests.push({url, init});
      return url.includes('/options?') ? options(url, init) : verify(url, init);
    }, atob, btoa, structuredClone, Uint8Array, ArrayBuffer
  };
  vm.runInNewContext(source, context);
  ready();
  return {form, input, button, status, requests, prompts, timers, context,
    get prevented() { return prevented; },
    submit() { return form.listeners.get('submit')({preventDefault() { prevented++; }}); },
    pageshow(persisted = false) { windowListeners.get('pageshow')?.({persisted}); }};
}

test('both pages expose semantic submit forms, shared theme, branded assets and empty hidden status', () => {
  for (const [html, mode, name] of [[loginHtml, 'login', 'username'], [registerHtml, 'register', 'newname']]) {
    assert.match(html, new RegExp(`<form id="${mode}Form"[^>]*data-auth-flow="${mode}"`));
    assert.match(html, new RegExp(`<label for="${name}">Username</label>`));
    assert.match(html, new RegExp(`<button id="${mode}Button"[^>]*type="submit"`));
    assert.match(html, /id="log"[^>]*role="status"[^>]*aria-live="polite"[^>]*hidden/);
    assert.match(html, /data-theme-select/);
    for (const preference of ['system', 'light', 'dark']) assert.ok(html.includes(`value="${preference}"`));
    assert.ok(html.indexOf('/js/theme.js') < html.indexOf('/styles.css'));
    assert.match(html, /src="\/img\/family-logo.png"/);
    assert.match(html, /Koppel, Judios, Zoopie &amp; Sellfusion Family/);
    assert.match(html, /<noscript>/);
    assert.doesNotMatch(html, /onkeydown|onclick/);
  }
  assert.match(authCss, /body\.auth-page \[hidden\]/);
  assert.match(authCss, /max-width: 440px/);
  assert.match(authCss, /font-size: 16px/);
  assert.match(authCss, /html\[data-theme="dark"\]/);
  assert.match(welcome, /aria-labelledby="activationTitle"/);
  assert.match(welcome, /administrator to activate/);
});

test('button and Enter share the submit flow, preserve endpoints and redirect after 204', async () => {
  const state = fixture();
  assert.equal(state.status.hidden, true);
  await state.submit();
  assert.equal(state.prevented, 1);
  assert.equal(state.requests.length, 2);
  assert.equal(state.requests[0].url, '/webauthn/authenticate/options?username=alice');
  assert.equal(state.requests[1].url, '/webauthn/authenticate/verify?requestId=42');
  assert.equal(state.requests[0].init.method, 'POST');
  assert.equal(state.requests[1].init.redirect, 'error');
  assert.equal(state.prompts[0].method, 'get');
  assert.deepEqual(Array.from(state.prompts[0].options.publicKey.challenge), [1, 2, 3]);
  assert.equal(JSON.parse(state.requests[1].init.body).response.signature, 'BQ');
  assert.equal(state.context.window.location.href, '/private/overview');
  assert.equal(state.button.textContent, 'Signed in');
  assert.equal(state.button.disabled, true);
  assert.equal(state.form.attributes.get('aria-busy'), 'false');
  await state.submit();
  assert.equal(state.requests.length, 2, 'completed sign-in must not submit twice');
});

test('blank and invalid usernames are explained without network requests', async () => {
  for (const value of ['   ', 'name@example.com', '<script>', 'a'.repeat(256)]) {
    const state = fixture(); state.input.value = value;
    await state.submit();
    assert.equal(state.requests.length, 0);
    assert.equal(state.input.attributes.get('aria-invalid'), 'true');
    assert.equal(state.input.focused, 1);
    assert.equal(state.status.dataset.state, 'error');
    state.input.listeners.get('input')();
    assert.equal(state.status.hidden, true);
    assert.equal(state.input.attributes.has('aria-invalid'), false);
  }
});

test('unsupported browsers and insecure addresses fail before creating an account or challenge', async () => {
  for (const configuration of [{supportsPasskeys: false}, {secure: false}]) {
    const state = fixture({...configuration, registration: true});
    await state.submit();
    assert.equal(state.requests.length, 0);
    assert.equal(state.button.disabled, false);
    assert.equal(state.status.hidden, false);
    assert.equal(state.status.dataset.state, 'error');
  }
});

test('an in-flight submission disables controls and prevents duplicate prompts', async () => {
  let resolveOptions;
  const state = fixture({options: () => new Promise(resolve => { resolveOptions = resolve; })});
  const first = state.submit();
  assert.equal(state.button.disabled, true);
  assert.equal(state.input.disabled, true);
  assert.equal(state.form.attributes.get('aria-busy'), 'true');
  await state.submit();
  assert.equal(state.requests.length, 1);
  resolveOptions(response(200, optionsBody));
  await first;
  assert.equal(state.prompts.length, 1);
});

test('cancelled sign-in restores controls and allows a deliberate retry', async () => {
  let attempts = 0;
  const state = fixture({createCredential: async () => {
    if (++attempts === 1) throw Object.assign(new Error('private browser detail'), {name: 'NotAllowedError'});
    return credential();
  }});
  await state.submit();
  assert.equal(state.button.disabled, false);
  assert.equal(state.input.disabled, false);
  assert.equal(state.form.attributes.get('aria-busy'), 'false');
  assert.match(state.status.textContent, /cancelled or timed out/);
  assert.doesNotMatch(state.status.textContent, /private browser detail/);
  assert.equal(state.requests.length, 1);
  await state.submit();
  assert.equal(state.context.window.location.href, '/private/overview');
});

test('cancelled registration does not automatically retry or promise the name is available', async () => {
  const state = fixture({registration: true, createCredential: async () => null});
  await state.submit();
  assert.equal(state.requests.length, 1);
  assert.equal(state.prompts[0].method, 'create');
  assert.match(state.status.textContent, /username is now unavailable/);
  assert.equal(state.timers.size, 0);
  assert.equal(state.button.disabled, false);
});

test('registration retains binary conversion, success instructions and three-second redirect', async () => {
  const state = fixture({registration: true});
  await state.submit();
  assert.equal(state.requests[0].url, '/webauthn/register/options?username=alice');
  assert.equal(state.requests[1].url, '/webauthn/register/verify?requestId=42');
  assert.deepEqual(Array.from(state.prompts[0].options.publicKey.user.id), [4, 5]);
  assert.equal(JSON.parse(state.requests[1].init.body).response.attestationObject, 'Bw');
  assert.match(state.status.textContent, /administrator to activate/);
  assert.equal(state.status.dataset.state, 'success');
  assert.equal(state.button.textContent, 'Account created');
  const timer = [...state.timers.values()][0];
  assert.equal(timer.delay, 3000);
  assert.equal(state.context.window.location.href, '/register.html');
  timer.callback();
  assert.equal(state.context.window.location.href, '/');
});

test('known account errors are helpful and untrusted server markup is not displayed', async () => {
  for (const [registration, body, expected] of [
    [false, 'User alice not found.', /could not find that username/],
    [true, 'User with this name alice already exists.', /already registered/],
    [false, '<img src=x onerror=alert(1)>', /could not start this request/]
  ]) {
    const state = fixture({registration, options: async () => response(400, body)});
    await state.submit();
    assert.match(state.status.textContent, expected);
    assert.doesNotMatch(state.status.textContent, /<img/);
    assert.equal(state.prompts.length, 0);
    assert.equal(state.button.disabled, false);
  }
});

test('network errors and unsuccessful verification do not redirect and release busy state', async () => {
  for (const configuration of [
    {options: async () => { throw new TypeError('network internals'); }},
    {verify: async () => response(401)},
    {verify: async () => response(200, '<html>Login</html>')},
    {verify: async () => response(500)}
  ]) {
    const state = fixture(configuration);
    await state.submit();
    assert.equal(state.context.window.location.href, '/');
    assert.equal(state.button.disabled, false);
    assert.equal(state.status.dataset.state, 'error');
    assert.doesNotMatch(state.status.textContent, /network internals|<html>/);
  }
});

test('back-forward restoration clears stale completion and preserves deliberate focus', async () => {
  const state = fixture({registration: true});
  await state.submit();
  assert.equal(state.timers.size, 1);
  state.pageshow(true);
  assert.equal(state.timers.size, 0);
  assert.equal(state.button.disabled, false);
  assert.equal(state.status.hidden, true);
  const login = fixture();
  login.context.document.activeElement = login.button;
  login.pageshow();
  assert.equal(login.input.focused, 0);
  login.context.document.activeElement = login.context.document.body;
  login.pageshow();
  assert.equal(login.input.focused, 1);
});

test('shared global helpers still work without auth forms for the server-stop page', () => {
  const state = fixture({withForm: false});
  const options = state.context.prepareRequestOptionsFromServer({challenge: 'AQI', allowCredentials: [{id: 'AwQ'}], nullField: null});
  assert.deepEqual(Array.from(options.challenge), [1, 2]);
  assert.deepEqual(Array.from(options.allowCredentials[0].id), [3, 4]);
  assert.equal('nullField' in options, false);
  assert.equal(state.context.assertionCredentialToJSON(credential()).response.signature, 'BQ');
  assert.doesNotThrow(() => state.context.log('unused status'));
  assert.equal(state.requests.length, 0);
});
