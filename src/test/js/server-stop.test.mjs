// Run with: node --test src/test/js/server-stop.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/server-stop.js', import.meta.url), 'utf8');
const template = await readFile(new URL('../../main/jte/layout.jte', import.meta.url), 'utf8');
const response = (status, body) => ({ok: status >= 200 && status < 300, status, text: async () => body});

class Clock {
  now = 0;
  nextId = 0;
  timers = new Map();
  setTimeout = (callback, delay) => {
    const id = ++this.nextId;
    this.timers.set(id, {callback, due: this.now + delay});
    return id;
  };
  clearTimeout = id => this.timers.delete(id);

  async advance(milliseconds) {
    const until = this.now + milliseconds;
    while (true) {
      const next = [...this.timers].sort((a, b) => a[1].due - b[1].due)[0];
      if (!next || next[1].due > until) break;
      this.now = next[1].due;
      this.timers.delete(next[0]);
      next[1].callback();
      await setImmediate();
    }
    this.now = until;
    await setImmediate();
  }
}

class Element {
  disabled = false;
  open = false;
  textContent = '';
  listeners = new Map();
  classes = new Set();
  classList = {toggle: (name, on) => on ? this.classes.add(name) : this.classes.delete(name)};
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  fire(name) { return this.listeners.get(name)?.(); }
  click() { if (!this.disabled) return this.fire('click'); }
  showModal() {
    assert.equal(this.open, false, 'an open modal must not be opened again');
    this.open = true;
  }
  close() { this.open = false; }
}

function fixture({
  health = async () => response(503, 'stopping'),
  options = async () => ({ok: true, json: async () => ({requestId: 42, publicKey: {challenge: 'challenge'}})}),
  verify = async () => response(202, ''),
  credential = async () => ({id: 'test-assertion'}),
  supportsPasskeys = true,
  supportsDialog = true
} = {}) {
  const clock = new Clock();
  const elements = Object.fromEntries([...template.matchAll(/id="(serverStop[^"]+)"/g)]
    .map(([, id]) => [id, new Element()]));
  const {serverStopButton: stop, serverStopDialog: dialog, serverStopConfirmButton: confirm,
    serverStopCancelButton: cancel, serverStopStatus: status} = elements;
  if (!supportsDialog) dialog.showModal = undefined;
  cancel.addEventListener('click', () => dialog.close()); // Native form method="dialog" behavior.
  const requests = [];
  const prompts = [];
  let reloads = 0;
  let assertions = 0;
  let domReady;
  const location = {href: 'https://example.test/private/tasks?view=active#tasks', reload() { reloads++; }};
  const context = {
    document: {
      getElementById: id => elements[id],
      addEventListener(name, callback) { assert.equal(name, 'DOMContentLoaded'); domReady = callback; }
    },
    window: {
      PublicKeyCredential: supportsPasskeys ? function () {} : undefined,
      location,
      confirm(message) { prompts.push(message); return true; }
    },
    navigator: {credentials: {get: (...args) => { assertions++; return credential(...args); }}},
    prepareRequestOptionsFromServer: publicKey => publicKey,
    assertionCredentialToJSON: assertion => assertion,
    async fetch(url, init) {
      requests.push({url, init});
      if (url === '/admin/server-stop/options') return options(init);
      if (url === '/admin/server-stop/verify?requestId=42') return verify(init);
      assert.equal(url, '/health');
      return health(init);
    },
    Date: {now: () => clock.now},
    AbortController,
    setTimeout: clock.setTimeout,
    clearTimeout: clock.clearTimeout
  };
  vm.runInNewContext(source, context);
  domReady();
  return {
    clock, stop, dialog, confirm, cancel, status, requests, prompts, location,
    get reloads() { return reloads; },
    get assertions() { return assertions; },
    healthRequests: () => requests.filter(({url}) => url === '/health'),
    stopRequests: () => requests.filter(({url}) => url.startsWith('/admin/server-stop/')),
    async proceed() { stop.click(); confirm.click(); await setImmediate(); }
  };
}

test('waits through stopping, network errors and invalid health responses before reloading once', async () => {
  const healthResponses = [response(503, 'stopping'), response(502, ''), new Error('connection refused'),
    response(200, '<html>Sign in</html>'), response(204, 'ok'), response(200, 'ok')];
  const state = fixture({health: async () => {
    const next = healthResponses.shift();
    if (next instanceof Error) throw next;
    return next;
  }});
  await state.proceed();
  assert.equal(state.confirm.disabled, true);
  assert.equal(state.cancel.disabled, false);
  assert.equal(state.cancel.textContent, 'Close');
  assert.match(state.status.textContent, /Waiting for server/);
  assert.equal(state.reloads, 0);
  await state.clock.advance(4999);
  assert.equal(state.reloads, 0);
  await state.clock.advance(1);
  assert.equal(state.reloads, 1);
  assert.equal(state.location.href, 'https://example.test/private/tasks?view=active#tasks');
  assert.equal(state.clock.timers.size, 0);
  assert.equal(state.assertions, 1);
  assert.equal(state.stopRequests().length, 2);
  for (const {init} of state.stopRequests()) {
    assert.equal(init.method, 'POST');
    assert.equal(init.redirect, 'error');
  }
  for (const {init} of state.healthRequests()) {
    assert.equal(init.cache, 'no-store');
    assert.equal(init.redirect, 'error');
    assert.ok(init.signal instanceof AbortSignal);
  }
  await state.clock.advance(300_000);
  assert.equal(state.reloads, 1);
});

test('recovers even if the first probe reaches the restarted server', async () => {
  const state = fixture({health: async () => response(200, 'ok')});
  await state.proceed();
  assert.equal(state.reloads, 1);
  assert.equal(state.healthRequests().length, 1);
  assert.equal(state.clock.timers.size, 0);
});

test('closing and reopening while waiting preserves progress and cannot send another shutdown', async () => {
  let ready = false;
  const state = fixture({health: async () => response(ready ? 200 : 503, ready ? 'ok' : 'stopping')});
  await state.proceed();
  state.cancel.click();
  assert.equal(state.dialog.open, false);
  state.stop.click();
  state.stop.click();
  assert.equal(state.dialog.open, true);
  assert.equal(state.confirm.disabled, true);
  assert.match(state.status.textContent, /Waiting for server/);
  state.confirm.fire('click'); // Also guard duplicate events independently of disabled buttons.
  assert.equal(state.stopRequests().length, 2);
  assert.equal(state.healthRequests().length, 1);
  state.cancel.click();
  ready = true;
  await state.clock.advance(1000);
  assert.equal(state.reloads, 1, 'recovery continues with the dialog closed');
});

test('times out after five minutes and retries availability without repeating passkey or shutdown', async () => {
  let ready = false;
  const state = fixture({health: async () => response(ready ? 200 : 503, ready ? 'ok' : 'stopping')});
  await state.proceed();
  await state.clock.advance(300_000);
  assert.equal(state.reloads, 0);
  assert.equal(state.confirm.disabled, false);
  assert.equal(state.confirm.textContent, 'Retry connection');
  assert.equal(state.cancel.disabled, false);
  assert.ok(state.status.classes.has('is-error'));
  assert.equal(state.clock.timers.size, 0);
  const probes = state.healthRequests().length;
  await state.clock.advance(60_000);
  assert.equal(state.healthRequests().length, probes);
  state.cancel.click();
  state.stop.click();
  assert.equal(state.confirm.textContent, 'Retry connection');
  ready = true;
  state.confirm.click();
  state.confirm.fire('click');
  await setImmediate();
  assert.equal(state.reloads, 1);
  assert.equal(state.assertions, 1);
  assert.equal(state.stopRequests().length, 2);
  assert.equal(state.healthRequests().length, probes + 1);
  assert.equal(state.clock.timers.size, 0);
});

test('aborts hanging probes after five seconds without overlapping requests', async () => {
  let inFlight = 0;
  let aborted = 0;
  const state = fixture({health: ({signal}) => new Promise((resolve, reject) => {
    inFlight++;
    assert.equal(inFlight, 1);
    signal.addEventListener('abort', () => {
      inFlight--;
      aborted++;
      reject(signal.reason);
    }, {once: true});
  })});
  await state.proceed();
  await state.clock.advance(4999);
  assert.equal(aborted, 0);
  assert.equal(state.healthRequests().length, 1);
  await state.clock.advance(1);
  assert.equal(aborted, 1);
  assert.equal(inFlight, 0);
  await state.clock.advance(1000);
  assert.equal(state.healthRequests().length, 2);
  await state.clock.advance(294_000);
  assert.equal(inFlight, 0);
  assert.equal(state.reloads, 0);
  assert.equal(state.confirm.textContent, 'Retry connection');
  assert.equal(state.clock.timers.size, 0);
});

test('the deadline also aborts a hanging response body', async () => {
  const state = fixture({health: async ({signal}) => ({
    status: 200,
    text: () => new Promise((resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), {once: true});
    })
  })});
  await state.proceed();
  await state.clock.advance(300_000);
  assert.equal(state.reloads, 0);
  assert.equal(state.confirm.textContent, 'Retry connection');
  assert.equal(state.clock.timers.size, 0);
});

test('duplicate clicks during passkey verification do not reset controls or submit twice', async () => {
  let accept;
  const state = fixture({credential: () => new Promise(resolve => { accept = resolve; })});
  await state.proceed();
  state.dialog.close();
  state.stop.click();
  assert.equal(state.confirm.disabled, true);
  assert.equal(state.cancel.disabled, true);
  state.confirm.fire('click');
  assert.equal(state.assertions, 1);
  assert.equal(state.stopRequests().length, 1);
  accept({id: 'test-assertion'});
  await setImmediate();
  assert.equal(state.stopRequests().length, 2);
  assert.equal(state.healthRequests().length, 1);
});

test('passkey cancellation restores controls and never starts recovery', async () => {
  const state = fixture({credential: async () => {
    throw Object.assign(new Error('cancelled'), {name: 'NotAllowedError'});
  }});
  await state.proceed();
  assert.equal(state.confirm.disabled, false);
  assert.equal(state.confirm.textContent, 'Proceed');
  assert.equal(state.cancel.disabled, false);
  assert.equal(state.status.textContent, 'Passkey confirmation was cancelled.');
  assert.equal(state.healthRequests().length, 0);
  assert.equal(state.stopRequests().length, 1);
  assert.equal(state.clock.timers.size, 0);
});

for (const stage of ['options', 'verify']) {
  test(`${stage} authorization failure restores controls without polling`, async () => {
    const state = fixture({[stage]: async () => response(403, 'Forbidden')});
    await state.proceed();
    assert.equal(state.confirm.disabled, false);
    assert.equal(state.cancel.disabled, false);
    assert.match(state.status.textContent, /403.*Forbidden/);
    assert.ok(state.status.classes.has('is-error'));
    assert.equal(state.healthRequests().length, 0);
    assert.equal(state.reloads, 0);
    assert.equal(state.clock.timers.size, 0);
  });

  test(`${stage} redirect or network failure restores controls without polling`, async () => {
    const state = fixture({[stage]: async () => { throw new TypeError('Failed to fetch'); }});
    await state.proceed();
    assert.equal(state.confirm.disabled, false);
    assert.equal(state.cancel.disabled, false);
    assert.equal(state.status.textContent, 'Failed to fetch');
    assert.equal(state.healthRequests().length, 0);
    assert.equal(state.reloads, 0);
    assert.equal(state.clock.timers.size, 0);
  });
}

test('an unexpected verification success status cannot start recovery without shutdown acceptance', async () => {
  const state = fixture({verify: async () => response(200, '<html>Sign in</html>')});
  await state.proceed();
  assert.equal(state.confirm.disabled, false);
  assert.equal(state.cancel.disabled, false);
  assert.match(state.status.textContent, /Server refused the stop request \(200\)/);
  assert.equal(state.healthRequests().length, 0);
  assert.equal(state.reloads, 0);
  assert.equal(state.clock.timers.size, 0);
});

test('unsupported passkeys show an error without sending a request', async () => {
  const state = fixture({supportsPasskeys: false});
  await state.proceed();
  assert.equal(state.status.textContent, 'This browser does not support passkeys.');
  assert.equal(state.confirm.disabled, false);
  assert.equal(state.cancel.disabled, false);
  assert.equal(state.requests.length, 0);
});

test('native confirmation fallback also waits for readiness and offers connection retry', async () => {
  let ready = false;
  const state = fixture({supportsDialog: false,
    health: async () => response(ready ? 200 : 503, ready ? 'ok' : 'stopping')});
  state.stop.click();
  await setImmediate();
  assert.deepEqual(state.prompts, ['Stop the server?']);
  assert.equal(state.healthRequests().length, 1);
  await state.clock.advance(300_000);
  ready = true;
  state.stop.click();
  await setImmediate();
  assert.deepEqual(state.prompts, ['Stop the server?', 'Check whether the server has restarted?']);
  assert.equal(state.reloads, 1);
  assert.equal(state.stopRequests().length, 2);
});
