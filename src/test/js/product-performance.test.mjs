// Run with: node --test src/test/js/product-performance.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

// Load the browser module without introducing a frontend package/build dependency.
const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/product-performance.js', import.meta.url), 'utf8');
const {formatPerformanceValue: format, formatPerformancePeriod: formatPeriod,
  visiblePerformanceRows, initProductPerformance} = await import(moduleUrl(source.replace("'./common.js'", JSON.stringify(common))));

test('formats numeric values like the spreadsheet, including fractional percentages', () => {
  assert.equal(format(43488, 'integer'), '43,488');
  assert.equal(format(149.9, 'decimal'), '149.90');
  assert.equal(format(0.0267, 'percent'), '2.67%');
  assert.equal(format(1.2255, 'percent'), '122.55%');
  assert.equal(format('SUPER HOT', 'text'), 'SUPER HOT');
});

test('distinguishes unavailable metrics from real zero values', () => {
  for (const type of ['integer', 'decimal', 'percent', 'date', 'text']) {
    for (const value of [null, undefined, '']) assert.equal(format(value, type), '—');
  }
  for (const value of [NaN, Infinity, '123']) assert.equal(format(value, 'integer'), '—');
  assert.equal(format(0, 'integer'), '0');
  assert.equal(format(0, 'decimal'), '0.00');
  assert.equal(format(0, 'percent'), '0.00%');
});

test('renders ISO calendar dates without time-zone shifts or invalid date rollover', () => {
  assert.equal(format('2026-05-01', 'date'), '01 May 26');
  assert.equal(format('2026-09-04', 'date'), '04 Sep 26');
  for (const value of ['2026-02-30', '2026-13-01', '2026-05-01T00:00:00Z', 'bad']) {
    assert.equal(format(value, 'date'), '—');
  }
});

test('formats weekly ranges and monthly period labels', () => {
  assert.equal(formatPeriod('2026-05-08'), '08 May 26 - 14 May 26');
  assert.equal(formatPeriod('2026-05-08', 'month'), 'May 2026');
  assert.equal(formatPeriod('2026-02-30'), '—');
});

test('limits visible rows to the latest 20 unless everything is requested', () => {
  const rows = Array.from({length: 25}, (_, index) => index);
  assert.deepEqual(visiblePerformanceRows(rows, false), rows.slice(5));
  assert.deepEqual(visiblePerformanceRows(rows, true), rows);
  assert.deepEqual(visiblePerformanceRows(rows.slice(0, 3), false), [0, 1, 2]);
});

// Exercise the browser module with just the DOM operations used by this page.
class Element {
  constructor(tag = '') {
    this.tag = tag;
    this.children = [];
    this.dataset = {};
    this.attributes = new Map();
    this.listeners = new Map();
    this.classList = {add() {}};
    this.style = {setProperty() {}};
    this.hidden = false;
    this.value = '';
    this.text = '';
    this.scrollLeft = 0;
  }

  set textContent(value) {
    this.replaceChildren();
    this.text = String(value);
  }

  get textContent() {
    return this.text + this.children.map(child => child.textContent).join('');
  }

  append(...children) {
    children.forEach(child => this.appendChild(child));
  }

  appendChild(child) {
    if (child.tag === 'fragment') {
      [...child.children].forEach(item => this.appendChild(item));
    } else {
      child.remove();
      child.parent = this;
      this.children.push(child);
    }
    return child;
  }

  replaceChildren(...children) {
    this.children.forEach(child => { child.parent = null; });
    this.children = [];
    this.text = '';
    this.append(...children);
  }

  insertBefore(child, reference) {
    child.remove();
    child.parent = this;
    this.children.splice(this.children.indexOf(reference), 0, child);
  }

  remove() {
    if (this.parent) this.parent.children.splice(this.parent.children.indexOf(this), 1);
    this.parent = null;
  }

  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }
  removeAttribute(name) { this.attributes.delete(name); }
  getBoundingClientRect() { return {width: this.tag === 'table' ? 1000 : 100}; }

  descendants() {
    return this.children.flatMap(child => [child, ...child.descendants()]);
  }

  querySelectorAll(selector) {
    return this.descendants().filter(child => selector === '[data-frozen-index]'
      ? child.dataset.frozenIndex !== undefined : child.tag === selector);
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  dispatch(type) {
    for (const listener of this.listeners.get(type) || []) listener();
  }
}

const vendorId = '2aa401ef-31ad-462a-baa4-993c9e21d006';
const productCode = 'PRODUCT-CODE';
const origin = 'https://example.test';
const classificationError = {
  code: 'INVALID_KEYWORD_MATCH_TYPES', vendorId, campaignId: 12, adsetId: 34,
  reportDate: '2026-09-07', matchTypes: ['broad', 'exact'],
  message: 'Expected one broad or exact match type.'
};

function report(clicks, date = '2026-09-07', errors = []) {
  return {
    mock: false,
    product: {name: 'Example product', pnk: 'EXAMPLE-PNK', url: 'https://emag.ro/product'},
    groups: [
      {key: 'overview', label: 'Product metrics', columns: [{key: 'week', label: 'Week', type: 'date'}]},
      {key: 'auto', label: 'Auto', columns: [{key: 'auto_clicks', label: 'Clicks', type: 'integer'}]},
      {key: 'total', label: 'Total', columns: [{key: 'total_clicks', label: 'Clicks', type: 'integer'}]}
    ],
    rows: [{values: {week: date, auto_clicks: clicks, total_clicks: errors.length ? null : clicks}}],
    errors
  };
}

async function openPage(t, period) {
  const template = await readFile(new URL('../../main/jte/product-performance.jte', import.meta.url), 'utf8');
  const elements = new Map([...template.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)]
    .map(([markup, tag, id]) => {
      const element = new Element(tag);
      element.hidden = /\bhidden\b/.test(markup);
      return [id, element];
    }));
  const byId = suffix => elements.get(`productPerformance${suffix}`);
  byId('Table').append(byId('Head'), byId('Body'));
  byId('Errors').append(byId('ErrorSummary'), byId('ErrorList'));
  const originals = new Map(['document', 'window', 'ResizeObserver'].map(key => [key, globalThis[key]]));
  t.after(() => originals.forEach((value, key) => {
    if (value === undefined) delete globalThis[key];
    else globalThis[key] = value;
  }));
  globalThis.document = {
    getElementById: id => elements.get(id) || null,
    querySelector: () => null,
    createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('fragment'),
    createTextNode: text => Object.assign(new Element('#text'), {text})
  };
  globalThis.window = {
    location: new URL(`/private/product-performance?vendorId=${vendorId}&productCode=${productCode}${period ? `&period=${period}` : ''}`, origin),
    history: {replaceState(state, title, url) { window.location = new URL(url, origin); }}
  };
  globalThis.ResizeObserver = class {observe() {} disconnect() {}};
  const requests = [];
  const loggedErrors = [];
  t.mock.method(console, 'error', (...args) => loggedErrors.push(args));
  t.mock.method(globalThis, 'fetch', async url => {
    const parsed = new URL(url, origin);
    if (parsed.pathname === '/app/productPerformanceOptions') {
      return {ok: true, json: async () => ({defaultVendorId: vendorId, vendors: [
        {vendorId, name: 'Example vendor', products: [
          {productCode, name: 'Example product'}, {productCode: 'OTHER', name: 'Other product'}
        ]}
      ]})};
    }
    return new Promise(resolve => requests.push({
      url: parsed,
      respond: data => resolve({ok: true, json: async () => data}),
      fail: () => resolve({ok: false, status: 500})
    }));
  });
  initProductPerformance();
  await setImmediate();
  assert.equal(requests.length, 1);
  return {
    byId, requests, loggedErrors, template,
    periodSelect() {
      return byId('Head').descendants().find(element => element.getAttribute('aria-label') === 'Performance period');
    },
    async respond(index, data) {
      requests[index].respond(data);
      await setImmediate();
    }
  };
}

test('period selection requests server aggregates and preserves vendor/product selection', async t => {
  const page = await openPage(t);
  assert.equal(page.requests[0].url.searchParams.get('period'), 'week');
  await page.respond(0, report(7));
  assert.equal(page.byId('Body').children[0].children[0].textContent, '07 Sep 26 - 13 Sep 26');
  const select = page.periodSelect();
  select.value = 'month';
  select.dispatch('change');
  assert.equal(page.byId('Body').textContent, '');
  assert.equal(page.requests.length, 2);
  for (const params of [page.requests[1].url.searchParams, window.location.searchParams]) {
    assert.equal(params.get('period'), 'month');
    assert.equal(params.get('vendorId'), vendorId);
    assert.equal(params.get('productCode'), productCode);
  }
  await page.respond(1, report(300, '2026-09-01'));
  assert.equal(page.byId('Body').children[0].children[0].textContent, 'September 2026');
  assert.equal(page.byId('Body').children[0].children[1].textContent, '300');
  assert.equal(page.periodSelect().value, 'month');
  assert.match(page.byId('Table').getAttribute('aria-label'), /^Monthly/);
  assert.match(page.byId('Wrap').getAttribute('aria-label'), /^Monthly/);
  assert.match(page.byId('Status').textContent, /^1 month/);
  assert.doesNotMatch(page.template, /mock|productPerformancePreview/);
});

test('monthly links load monthly data immediately and invalid periods default to week', async t => {
  for (const [requested, expected] of [['month', 'month'], ['invalid', 'week']]) {
    await t.test(requested, async child => {
      const page = await openPage(child, requested);
      assert.equal(page.requests[0].url.searchParams.get('period'), expected);
      assert.equal(window.location.searchParams.get('period'), expected);
      await page.respond(0, report(1));
      assert.equal(page.periodSelect().value, expected);
    });
  }
});

test('partial reports show expandable classification details safely and retain independent metrics', async t => {
  const page = await openPage(t);
  await page.respond(0, report(25, '2026-09-07', [classificationError,
    {...classificationError, adsetId: 35, matchTypes: [], message: '<img src=x onerror=alert(1)>'}]));
  assert.equal(page.byId('Errors').tag, 'details');
  assert.equal(page.byId('Errors').hidden, false);
  assert.equal(page.byId('Errors').open, false);
  assert.match(page.byId('ErrorSummary').textContent, /^Partial report: 2 data issues/);
  assert.match(page.byId('ErrorList').textContent, /Campaign 12 · Adset 34 · Report date 2026-09-07 · Match types: broad, exact/);
  assert.match(page.byId('ErrorList').textContent, /Adset 35.*Match types: none/);
  assert.match(page.byId('ErrorList').textContent, /<img src=x onerror=alert\(1\)>/);
  assert.deepEqual(page.byId('ErrorList').children.map(item => item.children), [[], []]);
  assert.equal(page.byId('Table').getAttribute('aria-describedby'), 'productPerformanceErrorSummary');
  const cells = page.byId('Body').children[0].children;
  assert.equal(cells[1].textContent, '25');
  assert.equal(cells[2].textContent, '—');
});

test('a stale period response cannot overwrite newer rows or restore old classification errors', async t => {
  const page = await openPage(t);
  await page.respond(0, report(7));
  const select = page.periodSelect();
  select.value = 'month';
  select.dispatch('change');
  select.value = 'week';
  select.dispatch('change');
  await page.respond(2, report(70));
  await page.respond(1, report(300, '2026-09-01', [classificationError]));
  assert.equal(page.byId('Body').children[0].children[1].textContent, '70');
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('ErrorList').textContent, '');
  assert.match(page.byId('Status').textContent, /^1 week/);
  assert.equal(page.periodSelect().value, 'week');
});

test('a stale period failure cannot clear newer data', async t => {
  const page = await openPage(t);
  await page.respond(0, report(7));
  const select = page.periodSelect();
  select.value = 'month';
  select.dispatch('change');
  select.value = 'week';
  select.dispatch('change');
  await page.respond(2, report(70));
  page.requests[1].fail();
  await setImmediate();
  assert.equal(page.byId('Body').children[0].children[1].textContent, '70');
  assert.equal(page.byId('Wrap').hidden, false);
  assert.equal(page.loggedErrors.length, 0);
});

test('classification errors clear when changing selection and remain clear after failure and retry', async t => {
  const page = await openPage(t);
  await page.respond(0, report(7, '2026-09-07', [classificationError]));
  page.byId('ProductSelect').value = 'OTHER';
  page.byId('ProductSelect').dispatch('change');
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('ErrorList').textContent, '');
  assert.equal(page.byId('Table').getAttribute('aria-describedby'), null);
  page.requests[1].fail();
  await setImmediate();
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('Body').textContent, '');
  assert.match(page.byId('Status').textContent, /Unable to load product performance/);
  assert.equal(page.byId('Retry').hidden, false);
  page.byId('Retry').dispatch('click');
  assert.equal(page.requests[2].url.searchParams.get('productCode'), 'OTHER');
  assert.equal(page.requests[2].url.searchParams.get('period'), 'week');
  await page.respond(2, report(3));
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('ErrorList').textContent, '');
  assert.equal(page.byId('Body').children[0].children[1].textContent, '3');
});
