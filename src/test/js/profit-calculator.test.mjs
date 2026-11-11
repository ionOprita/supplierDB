import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

const origin = 'https://example.test';
const options = {
  vendorGroups: [
    {vendorGroup: 'J', categories: ['Baby products', 'Toys']},
    {vendorGroup: 'S', categories: ['Hair trimmers', 'Personal care']},
    {vendorGroup: 'Z', categories: []}
  ],
  defaultVendorGroup: 'S'
};
const dataUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const commonUrl = dataUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
let moduleId = 0;

class Element {
  constructor() {
    this.children = [];
    this.dataset = {};
    this.listeners = new Map();
    this.attributes = new Map();
    this.value = '';
    this.text = '';
    this.disabled = false;
    this.hidden = false;
  }
  set innerHTML(value) { assert.equal(value, ''); this.replaceChildren(); }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.text = ''; this.value = ''; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, callback) {
    this.listeners.set(name, [...(this.listeners.get(name) || []), callback]);
  }
  dispatch(name) { for (const callback of this.listeners.get(name) || []) callback({target: this}); }
}

async function openPage(t, {categoryPage = true, search = '', response = options, failFirst = false} = {}) {
  const template = categoryPage ? 'profit-by-category' : 'profit-exposure';
  const html = await readFile(new URL(`../../main/jte/${template}.jte`, import.meta.url), 'utf8');
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
  const oldDocument = globalThis.document;
  const oldWindow = globalThis.window;
  t.after(() => {
    if (oldDocument === undefined) delete globalThis.document;
    else globalThis.document = oldDocument;
    if (oldWindow === undefined) delete globalThis.window;
    else globalThis.window = oldWindow;
  });
  const calls = [];
  const events = new Map();
  globalThis.document = {
    getElementById: id => elements.get(id) || null,
    createElement: () => new Element()
  };
  globalThis.window = {
    location: new URL(`/private/${template}?${search}`, origin),
    addEventListener: (name, callback) => events.set(name, callback),
    history: {replaceState(state, title, url) { window.location = new URL(url, window.location); }}
  };
  t.mock.method(console, 'error', () => {});
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    const requested = new URL(url, origin);
    calls.push(requested);
    assert.equal(requested.pathname, '/app/profitCalculatorOptions');
    assert.ok(!init?.method || init.method === 'GET');
    return failFirst && calls.length === 1
      ? {ok: false, status: 503}
      : {ok: true, json: async () => response};
  });
  const source = await readFile(new URL('../../main/resources/static/js/profit-calculator.js', import.meta.url), 'utf8');
  await import(dataUrl(source.replace("'./common.js'", JSON.stringify(commonUrl)) + `\n// fixture ${moduleId++}`));
  await setImmediate();
  return {
    calls,
    group: elements.get('profitVendorGroupSelect'),
    category: elements.get('profitCategorySelect'),
    status: elements.get('profitCalculatorStatus'),
    retry: elements.get('profitCalculatorRetry'),
    categoryNames: () => elements.get('profitCategorySelect').children.map(option => option.textContent)
  };
}

test('exposure page shows the database group letters and selects the server default', async t => {
  const page = await openPage(t, {categoryPage: false});
  assert.deepEqual(page.group.children.map(option => option.textContent), ['J', 'S', 'Z']);
  assert.equal(page.group.value, 'S');
  assert.equal(page.group.disabled, false);
  assert.equal(page.category, undefined);
  assert.equal(window.location.searchParams.get('vendorGroup'), 'S');
});

test('changing vendor group replaces category options and resets the selected category', async t => {
  const page = await openPage(t);
  assert.equal(page.category.value, 'Hair trimmers');
  assert.deepEqual(page.categoryNames(), ['Hair trimmers', 'Personal care']);
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  page.group.value = 'J';
  page.group.dispatch('change');
  await setImmediate();
  assert.deepEqual(page.categoryNames(), ['Baby products', 'Toys']);
  assert.equal(page.category.value, 'Baby products');
  assert.equal(window.location.searchParams.get('vendorGroup'), 'J');
  assert.equal(window.location.searchParams.get('category'), 'Baby products');
  assert.equal(page.calls.length, 1, 'Changing filters uses the already loaded options');
});

test('saved URLs restore a category belonging to the requested vendor group', async t => {
  const page = await openPage(t, {search: 'vendorGroup=J&category=Toys'});
  assert.equal(page.group.value, 'J');
  assert.equal(page.category.value, 'Toys');
  assert.deepEqual(page.categoryNames(), ['Baby products', 'Toys']);
});

test('a category from another vendor group cannot remain selected', async t => {
  const page = await openPage(t, {search: 'vendorGroup=J&category=Personal%20care'});
  assert.equal(page.category.value, 'Baby products');
  assert.equal(window.location.searchParams.get('category'), 'Baby products');
});

test('a group without categories clears and disables the category selector', async t => {
  const page = await openPage(t, {search: 'vendorGroup=Z&category=Toys'});
  assert.equal(page.group.value, 'Z');
  assert.equal(page.group.disabled, false);
  assert.equal(page.category.value, '');
  assert.equal(page.category.disabled, true);
  assert.equal(window.location.searchParams.has('category'), false);
  assert.match(page.status.textContent, /no categor/i);
});

test('no available vendor groups leaves both selectors disabled', async t => {
  const page = await openPage(t, {response: {vendorGroups: [], defaultVendorGroup: null}});
  assert.equal(page.group.disabled, true);
  assert.equal(page.category.disabled, true);
  assert.match(page.status.textContent, /no vendor groups/i);
});

test('failed options loading can be retried', async t => {
  const page = await openPage(t, {failFirst: true});
  assert.equal(page.group.disabled, true);
  assert.equal(page.category.disabled, true);
  assert.equal(page.retry.hidden, false);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.calls.length, 2);
  assert.equal(page.group.value, 'S');
  assert.equal(page.category.value, 'Hair trimmers');
  assert.equal(page.retry.hidden, true);
});
