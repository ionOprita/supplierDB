import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/app-shell.js', import.meta.url), 'utf8');
const serverStopSource = await readFile(new URL('../../main/resources/static/js/server-stop.js', import.meta.url), 'utf8');
const storageKey = 'sellfusion.sidebar.groups.v1';

function browserStorage(value) {
  const values = new Map(value === undefined ? [] : [[storageKey, value]]);
  return {values, writes: [], failRead: false, failWrite: false,
    getItem(key) { if (this.failRead) throw new Error('Storage blocked'); return values.get(key) ?? null; },
    setItem(key, data) {
      if (this.failWrite) throw new Error('Storage full');
      this.writes.push([key, data]);
      values.set(key, data);
    }};
}

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
    this._open = false;
    this.mobileOnly = false;
    this.tabIndex = ['button', 'a', 'summary', 'input', 'select', 'textarea'].includes(tag) ? 0 : -1;
    this.classList = {
      add: (...names) => names.forEach(name => this.classes.add(name)),
      remove: (...names) => names.forEach(name => this.classes.delete(name)),
      contains: name => this.classes.has(name),
      toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
    };
  }
  get tagName() { return this.tag.toUpperCase(); }
  get open() { return this._open; }
  set open(value) {
    const changed = this._open !== Boolean(value);
    this._open = Boolean(value);
    if (changed && this.tag === 'details') this.document.pendingToggles.add(this);
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
  click(event = {}) {
    const summary = this.closest('summary');
    (summary || this).focus();
    const result = this.dispatch('click', event);
    if (!result.defaultPrevented && summary?.parentElement.tag === 'details') {
      summary.parentElement.open = !summary.parentElement.open;
    }
    return result;
  }
  focus() { this.document.activeElement = this; }
  contains(element) { return this === element || this.children.some(child => child.contains(element)); }
  matches(selector) {
    return selector.split(',').some(part => {
      const value = part.trim();
      if (value === '[hidden]') return this.hidden;
      if (value === '[inert]') return this.inert;
      if (value === '[tabindex]') return this.attributes.has('tabindex');
      if (value === 'a.app-nav-link[href]') return this.tag === 'a' && this.classes.has('app-nav-link') && this.attributes.has('href');
      if (value === 'details.app-nav-group[data-nav-group]') return this.tag === 'details' && this.classes.has('app-nav-group') && this.attributes.has('data-nav-group');
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
  getClientRects() {
    if (this.hidden || (this.mobileOnly && !this.document.mobile.matches)) return [];
    if (!this.document.closedRectsVisible) {
      for (let parent = this.parentElement; parent; parent = parent.parentElement) {
        if (parent.tag === 'details' && !parent.open
            && !parent.children.find(child => child.tag === 'summary')?.contains(this)) return [];
      }
    }
    return [{}];
  }
  showModal() {
    this.previousFocus = this.document.activeElement;
    this.open = true;
    this.children[0]?.focus();
  }
  close() { this.open = false; this.previousFocus?.focus(); }
}

function fixture({path = '/private/orders', mobile = true, sidebar = true, serverStop = false,
  readyState = 'loading', matchMedia = true, storage = browserStorage(), storageGetterError = false,
  closedRectsVisible = false, requestAnimationFrame = true} = {}) {
  const events = new Map();
  const mediaListeners = [];
  const frames = [];
  const media = {matches: mobile, addEventListener(name, callback) {
    assert.equal(name, 'change'); mediaListeners.push(callback);
  }};
  const elements = new Map();
  const document = {
    readyState, mobile: media, pendingToggles: new Set(), closedRectsVisible,
    getElementById: id => elements.get(id) || null,
    addEventListener(name, callback) { events.set(name, [...(events.get(name) || []), callback]); },
    querySelector(selector) {
      assert.equal(selector, 'dialog[open]');
      return [...elements.values()].find(element => element.tag === 'dialog' && element.open) || null;
    }
  };
  const flushToggles = () => {
    const pending = [...document.pendingToggles];
    document.pendingToggles.clear();
    for (const group of pending) group.dispatch('toggle');
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
  const groups = new Map();
  const summaries = new Map();
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
    function link(route, parent, href = `/private/${route}`) {
      const link = element('a');
      link.classes.add('app-nav-link');
      link.setAttribute('href', href);
      parent.append(link);
      links.set(route, link);
    }
    const sales = element('section', 'salesPerformance');
    sales.classes.add('app-nav-group');
    navigation.append(sales);
    for (const route of ['overview', 'gmv', 'product-performance']) link(route, sales);
    for (const [id, routes] of [
      ['advertising', ['ads-campaigns']],
      ['business-data', ['products', 'categories', 'employees']],
      ['orders-returns', ['orders', 'storno', 'returns']],
      ['return-analysis', ['rrr', 'returnCharts', 'storno-rate', 'return-rate', 'current-rates', 'current-rates-categories']],
      ['system', ['tasks']]
    ]) {
      const details = element('details', `group-${id}`);
      details.classes.add('app-nav-group');
      details.setAttribute('data-nav-group', id);
      const summary = element('summary', `summary-${id}`);
      summary.classes.add('app-nav-heading');
      const icon = element('svg', `icon-${id}`);
      summary.append(icon);
      const list = element('ul');
      details.append(summary, list);
      for (const route of routes) link(route, list);
      if (id === 'system') link('db-explorer', list, '/admin/db-explorer/products');
      navigation.append(details);
      groups.set(id, details);
      summaries.set(id, summary);
    }
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
  if (requestAnimationFrame) window.requestAnimationFrame = callback => { frames.push(callback); return frames.length; };
  if (storageGetterError) Object.defineProperty(window, 'localStorage', {get() { throw new Error('Storage access denied'); }});
  else window.localStorage = storage;
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
  flushToggles();
  return {body, document, elements, links, groups, summaries, internal, window, requests, storage, flushToggles,
    flushFrames() { const pending = frames.splice(0); pending.forEach(callback => callback()); },
    key(key, shiftKey = false) {
      const event = fire('keydown', {key, shiftKey});
      if (!event.defaultPrevented && ['Enter', ' '].includes(key) && document.activeElement.tag === 'summary') {
        document.activeElement.click();
        flushToggles();
      }
      return event;
    },
    resize(isMobile) { media.matches = isMobile; for (const listener of mediaListeners) listener({matches: isMobile}); },
    get: id => elements.get(id),
    open() { elements.get('appNavToggle').click(); },
    toggleGroup(id) { summaries.get(id).click(); flushToggles(); }
  };
}

for (const [path, active] of [
  ['/private/overview', 'overview'],
  ['/private/overview/?month=2026-10#charts', 'overview'],
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
    const activeGroup = state.links.get(active).closest('details.app-nav-group[data-nav-group]');
    if (activeGroup) assert.equal(activeGroup.open, true);
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

test('dashboard leaves all collapsible groups closed while Sales performance remains visible', () => {
  const state = fixture({path: '/private/overview'});
  assert.deepEqual([...state.groups.keys()], ['advertising', 'business-data', 'orders-returns', 'return-analysis', 'system']);
  assert.ok([...state.groups.values()].every(group => !group.open));
  assert.equal(state.get('salesPerformance').tag, 'section');
  assert.ok(['overview', 'gmv', 'product-performance'].every(route => state.links.get(route).getClientRects().length > 0));
  assert.equal(state.storage.writes.length, 0);
});

test('saved boolean choices restore independently and forced active opening does not rewrite them', () => {
  const storage = browserStorage(JSON.stringify({'advertising': true, 'business-data': false,
    'orders-returns': false, 'return-analysis': 'true', 'system': true, unknown: true}));
  const state = fixture({storage, path: '/private/orders'});
  assert.deepEqual([...state.groups].filter(([, group]) => group.open).map(([id]) => id),
    ['advertising', 'orders-returns', 'system']);
  state.flushToggles();
  assert.equal(storage.writes.length, 0, 'queued initialization toggles must not persist the forced active state');
  assert.equal(JSON.parse(storage.values.get(storageKey))['orders-returns'], false);
  state.toggleGroup('orders-returns');
  assert.equal(state.groups.get('orders-returns').open, false, 'the current page group can still be collapsed manually');
  assert.equal(JSON.parse(storage.values.get(storageKey))['orders-returns'], false);
  const revisited = fixture({storage, path: '/private/orders/123'});
  assert.equal(revisited.groups.get('orders-returns').open, true);
  assert.equal(storage.writes.length, 1);
});

test('explicit user choices persist across visits without closing other groups', () => {
  const storage = browserStorage();
  const first = fixture({storage, path: '/private/overview'});
  first.open();
  first.toggleGroup('advertising');
  first.toggleGroup('business-data');
  assert.equal(first.groups.get('advertising').open, true);
  assert.equal(first.groups.get('business-data').open, true);
  assert.equal(first.body.classes.has('app-nav-open'), true);
  assert.deepEqual(JSON.parse(storage.values.get(storageKey)), {'advertising': true, 'business-data': true});
  const second = fixture({storage, path: '/private/rrr'});
  assert.equal(second.groups.get('advertising').open, true);
  assert.equal(second.groups.get('business-data').open, true);
  assert.equal(second.groups.get('return-analysis').open, true);
  assert.equal(storage.writes.length, 2);
  second.toggleGroup('advertising');
  const third = fixture({storage, path: '/private/overview'});
  assert.equal(third.groups.get('advertising').open, false);
  assert.equal(third.groups.get('business-data').open, true);
  assert.equal(third.groups.get('return-analysis').open, false, 'forced active opening is not saved as a user choice');
});

test('native summary keyboard and chevron clicks toggle groups without closing the mobile drawer', () => {
  const state = fixture({path: '/private/overview'});
  state.open();
  const summary = state.summaries.get('advertising');
  summary.focus();
  state.key('Enter');
  assert.equal(state.groups.get('advertising').open, true);
  assert.equal(state.document.activeElement.id, 'summary-advertising');
  assert.equal(state.body.classes.has('app-nav-open'), true);
  state.key(' ');
  assert.equal(state.groups.get('advertising').open, false);
  state.get('icon-advertising').click();
  state.flushToggles();
  assert.equal(state.groups.get('advertising').open, true);
  assert.equal(state.body.classes.has('app-nav-open'), true);
  assert.equal(state.document.activeElement.id, 'summary-advertising');
});

test('invalid or unavailable browser storage never breaks native group toggles or active-page expansion', () => {
  for (const saved of ['{bad json', 'null', '[]', 'true', '123', '"text"', '{"advertising":"yes"}']) {
    const state = fixture({storage: browserStorage(saved), path: '/private/products/edit'});
    assert.equal(state.groups.get('business-data').open, true);
    assert.equal(state.groups.get('advertising').open, false);
    state.toggleGroup('advertising');
    assert.equal(state.groups.get('advertising').open, true);
  }
  for (const failure of ['read', 'write', 'getter']) {
    const storage = browserStorage();
    storage.failRead = failure === 'read';
    storage.failWrite = failure === 'write';
    const state = fixture({storage, storageGetterError: failure === 'getter'});
    assert.equal(state.groups.get('orders-returns').open, true);
    assert.doesNotThrow(() => state.toggleGroup('advertising'));
    assert.equal(state.groups.get('advertising').open, true);
    assert.doesNotThrow(() => state.toggleGroup('advertising'));
    assert.equal(state.groups.get('advertising').open, false);
  }
});

test('group restoration and active expansion work before the matchMedia fallback returns', () => {
  const storage = browserStorage('{"advertising":true,"business-data":false}');
  const state = fixture({storage, matchMedia: false, path: '/private/products/new'});
  assert.equal(state.groups.get('advertising').open, true);
  assert.equal(state.groups.get('business-data').open, true);
  assert.equal(storage.writes.length, 0);
  state.toggleGroup('system');
  assert.equal(JSON.parse(storage.values.get(storageKey)).system, true);
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

test('opening retries focus after two animation frames when initial CSS visibility rejects it', () => {
  const state = fixture();
  const close = state.get('appNavClose');
  const focus = close.focus.bind(close);
  let attempts = 0;
  close.focus = () => { if (++attempts > 1) focus(); };
  state.open();
  assert.equal(attempts, 1);
  assert.equal(state.document.activeElement.id, 'appNavToggle');
  state.flushFrames();
  assert.equal(attempts, 1, 'the first frame lets the visibility transition begin');
  assert.equal(state.document.activeElement.id, 'appNavToggle');
  state.flushFrames();
  assert.equal(attempts, 2);
  assert.equal(state.document.activeElement.id, 'appNavClose');
});

test('deferred drawer focus never steals focus acquired before either animation frame', () => {
  for (const framesBeforeFocus of [0, 1]) {
    const state = fixture();
    state.get('appNavClose').focus = () => {};
    state.open();
    if (framesBeforeFocus) state.flushFrames();
    state.links.get('orders').focus();
    state.flushFrames();
    state.flushFrames();
    assert.equal(state.document.activeElement.getAttribute('href'), '/private/orders');
  }
});

test('closing the drawer or switching to desktop before either frame prevents deferred refocus', () => {
  for (const action of ['close', 'desktop']) {
    for (const framesBeforeAction of [0, 1]) {
      const state = fixture();
      state.get('appNavClose').focus = () => {};
      state.open();
      if (framesBeforeAction) state.flushFrames();
      if (action === 'close') state.get('appNavBackdrop').click();
      else state.resize(false);
      const focused = state.document.activeElement;
      state.flushFrames();
      state.flushFrames();
      assert.ok(state.document.activeElement === focused, `${action} after ${framesBeforeAction} frames`);
      assert.equal(state.body.classes.has('app-nav-open'), false);
    }
  }
});

test('a native dialog opened before either deferred frame retains focus', () => {
  for (const framesBeforeDialog of [0, 1]) {
    const state = fixture({serverStop: true});
    state.get('appNavClose').focus = () => {};
    state.open();
    if (framesBeforeDialog) state.flushFrames();
    state.get('serverStopButton').click();
    assert.equal(state.get('serverStopDialog').open, true);
    state.flushFrames();
    state.flushFrames();
    assert.equal(state.document.activeElement.id, 'serverStopCancelButton');
    assert.equal(state.body.classes.has('app-nav-open'), true);
  }
});

test('opening still works safely when requestAnimationFrame is unavailable', () => {
  const state = fixture({requestAnimationFrame: false});
  assert.doesNotThrow(() => state.open());
  assert.equal(state.document.activeElement.id, 'appNavClose');
});

test('Tab and Shift+Tab wrap inside the drawer while Escape and backdrop restore menu focus', () => {
  const state = fixture();
  state.open();
  assert.equal(state.key('Tab', true).defaultPrevented, true);
  assert.equal(state.document.activeElement.id, 'summary-system');
  assert.equal(state.key('Tab').defaultPrevented, true);
  assert.equal(state.document.activeElement, state.get('appNavClose'));
  state.links.get('orders').focus();
  assert.equal(state.key('Tab').defaultPrevented, false, 'ordinary Tab retains native focus order');
  assert.equal(state.key('Escape').defaultPrevented, true);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
  assert.equal(state.body.classes.has('app-nav-open'), false);
  state.open();
  state.get('appNavBackdrop').click();
  assert.equal(state.body.classes.has('app-nav-open'), false);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
});

test('the mobile focus trap excludes collapsed descendants even when browser geometry reports rectangles', () => {
  const state = fixture({path: '/private/overview', closedRectsVisible: true});
  const nested = new Element(state.document, 'details', 'nestedGroup');
  const nestedSummary = new Element(state.document, 'summary', 'nestedSummary');
  const nestedLink = new Element(state.document, 'a', 'nestedLink');
  nestedLink.setAttribute('href', '/example');
  nested.append(nestedSummary, nestedLink);
  state.groups.get('system').children[1].append(nested);
  state.open();
  state.key('Tab', true);
  assert.equal(state.document.activeElement.id, 'summary-system', 'nested summaries are hidden by their closed outer group');
  state.key('Tab');
  assert.equal(state.document.activeElement.id, 'appNavClose');
  state.toggleGroup('system');
  state.get('appNavClose').focus();
  state.key('Tab', true);
  assert.equal(state.document.activeElement.id, 'nestedSummary', 'the owning summary stays focusable while its links are closed');
  nestedSummary.click();
  state.flushToggles();
  state.get('appNavClose').focus();
  state.key('Tab', true);
  assert.equal(state.document.activeElement.id, 'nestedLink');
  state.key('Tab');
  assert.equal(state.document.activeElement.id, 'appNavClose');
});

test('following a drawer link keeps its native URL, query, fragment and modified-click behavior', () => {
  const state = fixture({path: '/private/products/edit?productCode=old'});
  const link = state.links.get('ads-campaigns');
  const href = '/private/ads-campaigns?vendorId=vendor&dateFrom=2026-10-01&dateTo=2026-10-29#reports';
  link.setAttribute('href', href);
  state.open();
  state.toggleGroup('advertising');
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
  state.toggleGroup('business-data');
  state.links.get('products').focus();
  state.resize(true);
  assert.equal(state.get('appSidebar').inert, true);
  assert.equal(state.document.activeElement, state.get('appNavToggle'));
});

test('desktop focus restoration chooses the visible summary when the active group was manually collapsed', () => {
  const state = fixture();
  state.open();
  state.toggleGroup('orders-returns');
  assert.equal(state.groups.get('orders-returns').open, false);
  state.get('appNavClose').focus();
  state.resize(false);
  assert.equal(state.document.activeElement.id, 'summary-orders-returns');
  assert.equal(state.groups.get('orders-returns').open, false, 'resizing does not undo a user choice');
  assert.equal(state.get('appSidebar').inert, false);
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
