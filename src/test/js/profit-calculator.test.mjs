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
  constructor(tag = '') {
    this.tag = tag;
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

const product = (code, name = code, retracted = false) => ({productCode: code, name, pnk: `PNK-${code}`, retracted});
const products = [product('first', 'S. 2 - Trimmer'), product('second', 'S. 10 - Trimmer', true)];

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}

async function openPage(t, {categoryPage = true, search = '', response = options, failFirst = false,
  categoryRequest = async () => ({products})} = {}) {
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
    createElement: tag => new Element(tag)
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
    assert.ok(!init?.method || init.method === 'GET');
    if (requested.pathname === '/app/profitCalculatorCategory') {
      return {ok: true, json: async () => categoryRequest(requested)};
    }
    assert.equal(requested.pathname, '/app/profitCalculatorOptions');
    return failFirst && calls.length === 1
      ? {ok: false, status: 503}
      : {ok: true, json: async () => response};
  });
  const layoutUrl = dataUrl(await readFile(new URL('../../main/resources/static/js/profit-calculator-layout.js', import.meta.url), 'utf8'));
  const tableSource = await readFile(new URL('../../main/resources/static/js/profit-calculator-table.js', import.meta.url), 'utf8');
  const tableUrl = dataUrl(tableSource.replace("'./profit-calculator-layout.js'", JSON.stringify(layoutUrl)));
  const source = await readFile(new URL('../../main/resources/static/js/profit-calculator.js', import.meta.url), 'utf8');
  await import(dataUrl(source.replace("'./common.js'", JSON.stringify(commonUrl))
    .replace("'./profit-calculator-table.js'", JSON.stringify(tableUrl)) + `\n// fixture ${moduleId++}`));
  await setImmediate();
  return {
    calls,
    group: elements.get('profitVendorGroupSelect'),
    category: elements.get('profitCategorySelect'),
    status: elements.get('profitCalculatorStatus'),
    retry: elements.get('profitCalculatorRetry'),
    head: elements.get('profitCategoryHead'),
    body: elements.get('profitCategoryBody'),
    summary: elements.get('profitCategorySummaryBody'),
    wrap: elements.get('profitCategoryWrap'),
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
  assert.deepEqual(page.calls.map(call => call.pathname), ['/app/profitCalculatorOptions']);
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
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 1,
    'Changing filters uses the already loaded selector options');
  assert.equal(page.calls.at(-1).searchParams.get('vendorGroup'), 'J');
  assert.equal(page.calls.at(-1).searchParams.get('category'), 'Baby products');
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
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 2);
  assert.equal(page.group.value, 'S');
  assert.equal(page.category.value, 'Hair trimmers');
  assert.equal(page.retry.hidden, true);
});

test('the matrix fills only product identity and display-only retracted checkboxes', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    product('first', '<em>Actual name</em>'),
    {...product('second', null, true), pnk: null}
  ]})});
  assert.equal(page.head.children.length, 3);
  const [names, pnks, retracted] = page.head.children;
  assert.equal(names.children[1].textContent, '<em>Actual name</em>');
  assert.equal(names.children[1].children.length, 0, 'Names are literal text');
  assert.equal(names.children[2].textContent, '');
  assert.equal(pnks.children[1].textContent, 'PNK-first');
  assert.equal(pnks.children[2].textContent, '');
  assert.deepEqual(retracted.children.slice(1).map(cell => cell.children[0].checked), [false, true]);
  assert.ok(retracted.children.slice(1).every(cell => cell.children[0].disabled));
  assert.ok(page.body.children.every(row => row.children.length === 3));
  assert.ok(page.body.children.every(row => row.children.slice(1).every(cell => !cell.textContent && !cell.children.length)));
  assert.ok(page.summary.children.every(row => row.children[1].textContent === ''));
  assert.match(page.body.textContent, /Total Venit\/Unitate \(RON\)/);
  assert.match(page.body.textContent, /Profit net \(dupa costul banilor si inflatie\)/);
  assert.equal(page.body.children[0].dataset.sourceRow, 10);
  assert.equal(page.body.children.at(-1).dataset.sourceRow, 107);
});

test('empty product results preserve labels and remove all product columns', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: []})});
  assert.ok(page.head.children.every(row => row.children.length === 1));
  assert.ok(page.body.children.every(row => row.children.length === 1));
  assert.match(page.body.textContent, /Total Venit\/Unitate/);
  assert.match(page.status.textContent, /no products/i);
  assert.equal(page.wrap.attributes.get('aria-busy'), 'false');
});

test('a delayed category response cannot overwrite the latest selected category', async t => {
  const initial = deferred();
  const latest = deferred();
  const page = await openPage(t, {categoryRequest: url => url.searchParams.get('category') === 'Hair trimmers'
    ? initial.promise : latest.promise});
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  assert.ok(page.head.children.every(row => row.children.length === 1), 'Old product columns clear immediately');
  assert.match(page.body.textContent, /Total Venit\/Unitate/);
  latest.resolve({products: [product('latest')]});
  await setImmediate();
  initial.resolve({products: [product('old')]});
  await setImmediate();
  assert.equal(page.head.children[0].children[1].textContent, 'latest');
  assert.equal(page.status.hidden, true);
});

test('a stale product failure cannot replace the current successful table', async t => {
  const initial = deferred();
  const page = await openPage(t, {categoryRequest: url => url.searchParams.get('category') === 'Hair trimmers'
    ? initial.promise : {products: [product('current')]}});
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  await setImmediate();
  initial.reject(new Error('Old request failed'));
  await setImmediate();
  assert.equal(page.head.children[0].children[1].textContent, 'current');
  assert.equal(page.retry.hidden, true);
  assert.equal(page.status.hidden, true);
});

test('product failure keeps labels and retries the current selection without reloading options', async t => {
  let attempts = 0;
  const page = await openPage(t, {search: 'vendorGroup=J&category=Toys', categoryRequest: async () => {
    if (++attempts === 1) throw new Error('Product load failed');
    return {products: [product('toy')]};
  }});
  assert.equal(page.retry.hidden, false);
  assert.ok(page.head.children.every(row => row.children.length === 1));
  assert.match(page.body.textContent, /Total Venit\/Unitate/);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.head.children[0].children[1].textContent, 'toy');
  assert.equal(page.group.value, 'J');
  assert.equal(page.category.value, 'Toys');
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 1);
  assert.equal(page.retry.hidden, true);
});
