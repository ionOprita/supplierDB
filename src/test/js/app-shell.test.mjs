import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/app-shell.js', import.meta.url), 'utf8');
const serverStopSource = await readFile(new URL('../../main/resources/static/js/server-stop.js', import.meta.url), 'utf8');

class Element {
  constructor(document, tag, id = '') {
    this.document = document;
    this.tag = tag;
    this.id = id;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.classes = new Set();
    this.style = {overflow: ''};
    this.disabled = false;
    this.hidden = false;
    this.open = false;
    this.mobileOnly = false;
    this.tabIndex = ['button', 'a', 'input', 'select', 'textarea'].includes(tag) ? 0 : -1;
    this.classList = {
      add: (...names) => names.forEach(name => this.classes.add(name)),
      remove: (...names) => names.forEach(name => this.classes.delete(name)),
      contains: name => this.classes.has(name),
      toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
    };
  }
  get inert() { return this.attributes.has('inert'); }
  set inert(value) { if (value) this.attributes.set('inert', ''); else this.attributes.delete('inert'); }
  append(...children) {
    for (const child of children) { child.parentElement = this; this.children.push(child); }
  }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, callback) {
    this.listeners.set(name, [...(this.listeners.get(name) || []), callback]);
  }
  dispatch(name, event = {}) {
    event.target ??= this;
    event.defaultPrevented ??= false;
    event.preventDefault ??= () => { event.defaultPrevented = true; };
    for (const callback of this.listeners.get(name) || []) callback(event);
    this.parentElement?.dispatch(name, event);
    return event;
  }
  click(event = {}) { this.focus(); return this.dispatch('click', event); }
  focus() { this.document.activeElement = this; }
  contains(element) { return this === element || this.children.some(child => child.contains(element)); }
  matches(selector) {
    return selector.split(',').some(part => {
      const value = part.trim();
      if (value === '[hidden]') return this.hidden;
      if (value === '[inert]') return this.inert;
      if (value === '[tabindex]') return this.attributes.has('tabindex');
      if (value === 'a.app-nav-link[href]') return this.tag === 'a' && this.classes.has('app-nav-link') && this.attributes.has('href');
      if (value === 'a[href]') return this.tag === 'a' && this.attributes.has('href');
      if (value === 'a[aria-current="page"]') return this.tag === 'a' && this.getAttribute('aria-current') === 'page';
      return this.tag === value;
    });
  }
  closest(selector) { return this.matches(selector) ? this : this.parentElement?.closest(selector) || null; }
  querySelectorAll(selector) {
    return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]);
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  getClientRects() { return this.hidden || (this.mobileOnly && !this.document.mobile.matches) ? [] : [{}]; }
  showModal() {
    this.previousFocus = this.document.activeElement;
    this.open = true;
    this.children[0]?.focus();
  }
  close() { this.open = false; this.previousFocus?.focus(); }
}

function fixture({path = '/private/orders', mobile = true, sidebar = true, serverStop = false,
  readyState = 'loading', matchMedia = true} = {}) {
  const events = new Map();
  const mediaListeners = [];
  const media = {matches: mobile, addEventListener(name, callback) {
    assert.equal(name, 'change'); mediaListeners.push(callback);
  }};
  const elements = new Map();
  const document = {
    readyState, mobile: media,
    getElementById: id => elements.get(id) || null,
    addEventListener(name, callback) { events.set(name, [...(events.get(name) || []), callback]); },
    querySelector(selector) {
      assert.equal(selector, 'dialog[open]');
      return [...elements.values()].find(element => element.tag === 'dialog' && element.open) || null;
    }
  };
  const element = (tag, id) => {
    const result = new Element(document, tag, id);
    if (id) elements.set(id, result);
    return result;
  };
  const body = element('body');
  document.body = body;
  document.activeElement = body;
  body.classList.add('app-shell');
  const header = element('header', 'appHeader');
  const main = element('main', 'appMain');
  const footer = element('footer', 'appFooter');
  body.append(header, main, footer);
  const internal = element('a', 'internalDbExplorerLink');
  internal.setAttribute('href', '/admin/db-explorer/brands?sort=name');
  internal.setAttribute('aria-current', 'page');
  main.append(internal);
  const links = new Map();
  if (sidebar) {
    body.classList.add('app-shell--with-nav');
    const toggle = element('button', 'appNavToggle');
    toggle.mobileOnly = true;
    header.append(toggle);
    const aside = element('aside', 'appSidebar');
    aside.setAttribute('aria-labelledby', 'appNavTitle');
    const title = element('h2', 'appNavTitle');
    const close = element('button', 'appNavClose');
    close.mobileOnly = true;
    const navigation = element('nav', 'appNavigation');
    aside.append(title, close, navigation);
    for (const route of ['orders', 'storno', 'returns', 'products', 'ads-campaigns', 'tasks']) {
      const link = element('a');
      link.classes.add('app-nav-link');
      link.setAttribute('href', `/private/${route}`);
      navigation.append(link);
      links.set(route, link);
    }
    const explorer = element('a');
    explorer.classes.add('app-nav-link');
    explorer.setAttribute('href', '/admin/db-explorer/products');
    navigation.append(explorer);
    links.set('db-explorer', explorer);
    const backdrop = element('button', 'appNavBackdrop');
    backdrop.hidden = true;
    backdrop.tabIndex = -1;
    body.append(aside, backdrop);
    if (serverStop) {
      const stop = element('button', 'serverStopButton');
      aside.append(stop);
      const dialog = element('dialog', 'serverStopDialog');
      const cancel = element('button', 'serverStopCancelButton');
      const confirm = element('button', 'serverStopConfirmButton');
      const status = element('p', 'serverStopStatus');
      dialog.append(cancel, confirm, status);
      body.append(dialog);
      cancel.addEventListener('click', () => dialog.close());
    }
  }
  const window = {location: new URL(path, 'https://example.test')};
  if (matchMedia) window.matchMedia = query => {
    assert.equal(query, '(max-width: 1199px)'); return media;
  };
  const requests = [];
  const context = {document, window, URL, fetch: (...args) => { requests.push(args); throw new Error('Unexpected request'); }};
  vm.runInNewContext(source, context);
  if (serverStop) vm.runInNewContext(serverStopSource, context);
  const fire = (name, event = {}) => {
    event.defaultPrevented ??= false;
    event.preventDefault ??= () => { event.defaultPrevented = true; };
    for (const listener of events.get(name) || []) listener(event);
    return event;
  };
  if (readyState === 'loading') fire('DOMContentLoaded');
  return {body, document, elements, links, internal, window, requests,
    key: (key, shiftKey = false) => fire('keydown', {key, shiftKey}),
    resize(isMobile) { media.matches = isMobile; for (const listener of mediaListeners) listener({matches: isMobile}); },
    get: id => elements.get(id),
    open() { elements.get('appNavToggle').click(); }
  };
}

for (const [path, active] of [
  ['/private/orders?sort=date#latest', 'orders'],
  ['/private/orders/123', 'orders'],
  ['/private/products/edit?productCode=A%2FB', 'products'],
  ['/private/products/new', 'products'],
  ['/private/ads-keywords?vendorId=vendor&campaignId=12&adsetId=34', 'ads-campaigns'],
  ['/admin/db-explorer/brands?sort=name', 'db-explorer']
]) {
  test(`marks only the global section for ${path}`, () => {
    const state = fixture({path});
    assert.equal(state.links.get(active).getAttribute('aria-current'), 'page');
    assert.deepEqual([...state.links].filter(([, link]) => link.classes.has('is-active')).map(([name]) => name), [active]);
    assert.equal(state.internal.getAttribute('aria-current'), 'page', 'DB Explorer keeps its own subnavigation');
    assert.equal(state.window.location.href, new URL(path, 'https://example.test').href);
    assert.equal(state.requests.length, 0);
  });
}

test('does not select a similar route prefix', () => {
  const state = fixture({path: '/private/orders-archive'});
  assert.equal([...state.links.values()].some(link => link.getAttribute('aria-current') === 'page'), false);
});

test('mobile drawer sets modal semantics, focus, background inertness and restores prior state', () => {
  const state = fixture();
  state.body.style.overflow = 'clip';
  state.get('appFooter').inert = true;
  assert.equal(state.get('appSidebar').inert, true);
  assert.equal(state.get('appNavBackdrop').hidden, true);
  state.open();
  assert.equal(state.get('appSidebar').inert, false);
  assert.equal(state.get('appSidebar').getAttribute('role'), 'dialog');
  assert.equal(state.get('appSidebar').getAttribute('aria-modal'), 'true');
  assert.equal(state.get('appSidebar').getAttribute('aria-labelledby'), 'appNavTitle');
  assert.equal(state.get('appNavToggle').getAttribute('aria-expanded'), 'true');
  assert.equal(state.document.activeElement, state.get('appNavClose'));
  assert.equal(state.body.style.overflow, 'hidden');
  for (const id of ['appHeader', 'appMain', 'appFooter']) assert.equal(state.get(id).inert, true);
  state.get('appNavClose').click();
  assert.equal(state.get('appNavBackdrop').hidden, true);
  assert.equal(state.get('appSidebar').inert, true);
  assert.equal(state.get('appSidebar').getAttribute('role'), null);
  assert.equal(state.get('appSidebar').getAttribute('aria-labelledby'), 'appNavTitle');
  assert.equal(state.get('appNavToggle').getAttribute('aria-expanded'), 'false');
  assert.equal(state.get('appHeader').inert, false);
  assert.equal(state.get('appMain').inert, false);
  assert.equal(state.get('appFooter').inert, true);
  assert.equal(state.body.style.overflow, 'clip');
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
});

test('Tab and Shift+Tab wrap inside the drawer while Escape and backdrop restore menu focus', () => {
  const state = fixture();
  state.open();
  assert.equal(state.key('Tab', true).defaultPrevented, true);
  assert.equal(state.document.activeElement, state.links.get('db-explorer'));
  assert.equal(state.key('Tab').defaultPrevented, true);
  assert.equal(state.document.activeElement, state.get('appNavClose'));
  state.links.get('products').focus();
  assert.equal(state.key('Tab').defaultPrevented, false, 'ordinary Tab retains native focus order');
  assert.equal(state.key('Escape').defaultPrevented, true);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
  assert.equal(state.body.classes.has('app-nav-open'), false);
  state.open();
  state.get('appNavBackdrop').click();
  assert.equal(state.body.classes.has('app-nav-open'), false);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
});

test('following a drawer link keeps its native URL, query, fragment and modified-click behavior', () => {
  const state = fixture({path: '/private/products/edit?productCode=old'});
  const link = state.links.get('ads-campaigns');
  const href = '/private/ads-campaigns?vendorId=vendor&dateFrom=2026-10-01&dateTo=2026-10-29#reports';
  link.setAttribute('href', href);
  state.open();
  const click = link.click({metaKey: true, button: 0});
  assert.equal(click.defaultPrevented, false);
  assert.equal(link.getAttribute('href'), href);
  assert.equal(state.body.classes.has('app-nav-open'), false);
  assert.equal(state.requests.length, 0);
});

test('desktop breakpoint restores persistent navigation and removes modal and scroll state', () => {
  const state = fixture();
  state.open();
  state.resize(false);
  assert.equal(state.get('appSidebar').inert, false);
  assert.equal(state.get('appSidebar').getAttribute('aria-hidden'), null);
  assert.equal(state.get('appSidebar').getAttribute('role'), null);
  assert.equal(state.get('appSidebar').getAttribute('aria-modal'), null);
  assert.equal(state.get('appNavBackdrop').hidden, true);
  assert.equal(state.get('appMain').inert, false);
  assert.equal(state.body.style.overflow, '');
  assert.equal(state.document.activeElement, state.links.get('orders'));
  state.get('appNavToggle').click();
  assert.equal(state.body.classes.has('app-nav-open'), false, 'desktop navigation cannot become a drawer');
  state.links.get('products').focus();
  state.resize(true);
  assert.equal(state.get('appSidebar').inert, true);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
});

test('initial desktop navigation stays visible and pages without a sidebar initialize safely', () => {
  const desktop = fixture({mobile: false, readyState: 'complete'});
  assert.equal(desktop.get('appSidebar').inert, false);
  assert.equal(desktop.get('appNavToggle').getAttribute('aria-expanded'), 'false');
  assert.equal(desktop.get('appMain').inert, false);
  assert.doesNotThrow(() => fixture({sidebar: false}));
  const unsupported = fixture({matchMedia: false});
  assert.equal(unsupported.body.classes.has('app-shell--enhanced'), false, 'unsupported browsers keep visible fallback navigation');
  assert.equal(unsupported.get('appSidebar').inert, false);
});

test('the existing Stop Server modal owns keyboard focus without closing the drawer', () => {
  const state = fixture({serverStop: true});
  state.open();
  state.get('serverStopButton').click();
  assert.equal(state.get('serverStopDialog').open, true);
  assert.equal(state.body.classes.has('app-nav-open'), true);
  assert.equal(state.document.activeElement, state.get('serverStopCancelButton'));
  assert.equal(state.key('Tab', true).defaultPrevented, false);
  assert.equal(state.key('Escape').defaultPrevented, false, 'the native dialog must handle Escape first');
  state.get('serverStopCancelButton').click();
  assert.equal(state.get('serverStopDialog').open, false);
  assert.equal(state.document.activeElement, state.get('serverStopButton'));
  assert.equal(state.body.classes.has('app-nav-open'), true);
  assert.equal(state.requests.length, 0);
  state.key('Escape');
  assert.equal(state.body.classes.has('app-nav-open'), false);
});
