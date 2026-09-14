// Run with: node --test src/test/js/ads-period-loading.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

class Element {
  constructor(tag = '') {
    this.tag = tag;
    this.children = [];
    this.dataset = {};
    this.classList = {add() {}};
    this.listeners = new Map();
    this.value = '';
    this.text = '';
  }

  set innerHTML(value) {
    assert.equal(value, '');
    this.children = [];
    this.text = '';
  }

  set textContent(value) {
    this.children = [];
    this.text = String(value);
  }

  get textContent() {
    return this.text + this.children.map(child => child.textContent).join('');
  }

  appendChild(child) {
    this.children.push(...(child.tag === 'fragment' ? child.children : [child]));
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) || [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  dispatch(type) {
    for (const listener of this.listeners.get(type) || []) {
      listener();
    }
  }

  setCustomValidity() {}
}

let moduleId = 0;
const tables = [
  {name: 'campaigns', controlPrefix: 'adsCampaign', tablePrefix: 'adsCampaigns', rowName: 'campaign'},
  {name: 'adsets', controlPrefix: 'adsAdset', tablePrefix: 'adsAdsets', rowName: 'adset'}
];
const origin = 'https://example.test';
const vendorId = '2aa401ef-31ad-462a-baa4-993c9e21d006';
const reportDate = '2026-09-05';
const week = {dateFrom: '2026-08-30', dateTo: reportDate};
const custom = {dateFrom: '2026-08-01', dateTo: '2026-09-01'};

async function openTable(t, table) {
  const template = await readFile(new URL(`../../main/jte/ads-${table.name}.jte`, import.meta.url), 'utf8');
  const elements = new Map([...template.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
  elements.set('title', new Element());
  const requests = [];
  const errors = [];
  const originalDocument = globalThis.document;
  const originalWindow = globalThis.window;
  t.after(() => {
    if (originalDocument === undefined) delete globalThis.document;
    else globalThis.document = originalDocument;
    if (originalWindow === undefined) delete globalThis.window;
    else globalThis.window = originalWindow;
  });
  globalThis.document = {
    getElementById: id => elements.get(id) || null,
    createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('fragment')
  };
  globalThis.window = {
    location: new URL(`/private/ads-${table.name}?vendorId=${vendorId}&campaignId=12&date=${reportDate}`, origin),
    addEventListener() {},
    history: {replaceState(state, title, url) { window.location = new URL(url); }}
  };
  t.mock.method(console, 'error', error => errors.push(error));
  t.mock.method(globalThis, 'fetch', async url => {
    const parsed = new URL(url, origin);
    if (parsed.pathname === '/app/adsVendors') {
      return {ok: true, json: async () => ({
        vendors: [{vendorId, name: 'Example vendor', account: 'sellfusion'}],
        defaultVendorId: vendorId
      })};
    }
    assert.equal(parsed.searchParams.get('vendorId'), vendorId);
    if (parsed.pathname.endsWith('Dates')) {
      return {ok: true, json: async () => [reportDate, '2026-09-04']};
    }
    return new Promise((resolve, reject) => requests.push({
      url: parsed,
      respond: data => resolve({ok: true, json: async () => data}),
      fail: () => resolve({ok: false, status: 500}),
      reject
    }));
  });
  await import(new URL(`../../main/resources/static/js/ads-${table.name}.js?test=${moduleId++}`, import.meta.url));
  await setImmediate();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url.searchParams.get('date'), reportDate);

  function control(suffix) {
    return elements.get(table.controlPrefix + suffix);
  }

  return {
    requests,
    errors,
    selectWeek() {
      control('PeriodSelect').value = 'last7';
      control('PeriodSelect').dispatch('change');
    },
    selectCustom() {
      control('PeriodSelect').value = 'custom';
      control('PeriodSelect').dispatch('change');
      control('DateFromInput').value = custom.dateFrom;
      control('DateToInput').value = custom.dateTo;
      control('ApplyRangeBtn').dispatch('click');
    },
    async respond(index, clicks) {
      requests[index].respond({
        campaignName: `Campaign ${clicks}`,
        columns: [{key: 'name', label: 'Name'}, {key: 'summary_clicks', label: 'Clicks', numeric: true}],
        rows: [{campaignId: 12, adsetId: 34, values: {name: 'Example', summary_clicks: String(clicks)}}]
      });
      await setImmediate();
    },
    assertReport(clicks, period) {
      const cells = elements.get(table.tablePrefix + 'Body').children[0].children;
      assert.equal(cells.at(-1).textContent, String(clicks));
      assert.equal(elements.get(table.tablePrefix + 'Status').textContent,
        `1 ${table.rowName} from ${period.dateFrom} to ${period.dateTo}.`);
      const links = cells.flatMap(cell => cell.children).filter(child => child.tag === 'a');
      assert.equal(links.length, table.name === 'campaigns' ? 1 : 4);
      for (const params of [window.location.searchParams, ...links.map(link => new URL(link.href, origin).searchParams)]) {
        assert.equal(params.get('vendorId'), vendorId);
        assert.equal(params.get('date'), null);
        assert.equal(params.get('dateFrom'), period.dateFrom);
        assert.equal(params.get('dateTo'), period.dateTo);
      }
      if (table.name === 'adsets') {
        assert.equal(document.title, `Adsets for Campaign ${clicks}`);
      }
    },
    assertError(initial = false) {
      const message = initial ? `Failed to load ${table.rowName} data.` : `Failed to load ${table.name}.`;
      assert.equal(elements.get(table.tablePrefix + 'Body').textContent, message);
      assert.equal(elements.get(table.tablePrefix + 'Head').textContent, '');
      assert.equal(elements.get(table.tablePrefix + 'Status').textContent, '');
      assert.equal(errors.length, 1);
    }
  };
}

for (const table of tables) {
  test(`${table.name}: delayed initial day response cannot overwrite the selected range`, async t => {
    const page = await openTable(t, table);
    page.selectWeek();
    assert.equal(page.requests[1].url.searchParams.get('dateFrom'), week.dateFrom);
    assert.equal(page.requests[1].url.searchParams.get('dateTo'), week.dateTo);
    await page.respond(1, 700);
    await page.respond(0, 100);
    page.assertReport(700, week);
  });

  test(`${table.name}: delayed range response cannot overwrite a newer custom range`, async t => {
    const page = await openTable(t, table);
    await page.respond(0, 100);
    page.selectWeek();
    page.selectCustom();
    await page.respond(2, 3000);
    await page.respond(1, 700);
    page.assertReport(3000, custom);
  });

  test(`${table.name}: stale initial failure cannot clear the selected range`, async t => {
    const page = await openTable(t, table);
    page.selectWeek();
    await page.respond(1, 700);
    page.requests[0].fail();
    await setImmediate();
    page.assertReport(700, week);
    assert.equal(page.errors.length, 0);
  });

  test(`${table.name}: stale range failure cannot clear the newer range`, async t => {
    const page = await openTable(t, table);
    await page.respond(0, 100);
    page.selectWeek();
    page.selectCustom();
    await page.respond(2, 3000);
    page.requests[1].reject(new Error('Network failure'));
    await setImmediate();
    page.assertReport(3000, custom);
    assert.equal(page.errors.length, 0);
  });

  test(`${table.name}: stale response cannot replace the current request error`, async t => {
    const page = await openTable(t, table);
    page.selectWeek();
    page.requests[1].fail();
    await setImmediate();
    page.assertError();
    await page.respond(0, 100);
    page.assertError();
  });

  test(`${table.name}: initial request failures remain visible`, async t => {
    const page = await openTable(t, table);
    page.requests[0].fail();
    await setImmediate();
    page.assertError(true);
  });

  test(`${table.name}: selected range request failures remain visible`, async t => {
    const page = await openTable(t, table);
    await page.respond(0, 100);
    page.selectWeek();
    page.requests[1].fail();
    await setImmediate();
    page.assertError();
  });
}
