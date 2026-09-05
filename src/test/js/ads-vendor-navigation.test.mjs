// Run with: node --test src/test/js/ads-*.test.mjs
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
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  get cells() { return this.children; }
  appendChild(child) { this.children.push(...(child.tag === 'fragment' ? child.children : [child])); }
  querySelectorAll(tag) {
    return this.children.flatMap(child => [
      ...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)
    ]);
  }
  addEventListener(type, listener) { this.listeners.set(type, [...(this.listeners.get(type) || []), listener]); }
  dispatch(type) { for (const listener of this.listeners.get(type) || []) listener(); }
  setCustomValidity() {}
  remove() {}
}

const origin = 'https://example.test';
const date = '2026-09-05';
const firstVendor = {vendorId: '6a6fca21-f997-4589-ab95-4c75fe10c7b1', name: 'First vendor', account: 'sellfusion'};
const secondVendor = {vendorId: '34aad1bc-a4f7-425a-8611-30a013788b4a', name: 'Second vendor', account: 'second'};
const vendorOptions = {vendors: [firstVendor, secondVendor], defaultVendorId: firstVendor.vendorId};
const pages = [
  {name: 'campaigns', prefix: 'adsCampaigns', endpoint: '/app/adsCampaigns'},
  {name: 'adsets', prefix: 'adsAdsets', endpoint: '/app/adsAdsets'},
  {name: 'search-phrases', prefix: 'adsSearchPhrases', endpoint: '/app/adsSearchPhrases'},
  {name: 'targeted-products', prefix: 'adsTargetedProducts', endpoint: '/app/adsTargetedProducts'},
  {name: 'keywords', prefix: 'adsKeywords', endpoint: '/app/adsKeywords'}
];
let moduleId = 0;

async function openPage(t, page, search, options = vendorOptions) {
  const template = await readFile(new URL(`../../main/jte/ads-${page.name}.jte`, import.meta.url), 'utf8');
  const elements = new Map([...template.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
  elements.set('title', new Element());
  elements.get(page.prefix + 'Table').appendChild(elements.get(page.prefix + 'Head'));
  elements.get(page.prefix + 'Table').appendChild(elements.get(page.prefix + 'Body'));
  const calls = [];
  const downloads = [];
  const navigations = [];
  const windowEvents = new Map();
  const errors = [];
  const oldDocument = globalThis.document;
  const oldWindow = globalThis.window;
  t.after(() => {
    if (oldDocument === undefined) delete globalThis.document;
    else globalThis.document = oldDocument;
    if (oldWindow === undefined) delete globalThis.window;
    else globalThis.window = oldWindow;
  });
  globalThis.document = {
    body: new Element('body'),
    getElementById: id => elements.get(id) || null,
    createElement: tag => {
      const element = new Element(tag);
      if (tag === 'a') element.click = () => downloads.push(element.download);
      return element;
    },
    createDocumentFragment: () => new Element('fragment')
  };
  function location(url) {
    const value = new URL(url, origin);
    value.assign = target => navigations.push(new URL(target, origin));
    return value;
  }
  globalThis.window = {
    location: location(`/private/ads-${page.name}?${search}`),
    addEventListener: (type, listener) => windowEvents.set(type, listener),
    history: {replaceState(state, title, url) { window.location = location(url); }}
  };
  t.mock.method(console, 'error', error => errors.push(error));
  t.mock.method(globalThis, 'fetch', async url => {
    const parsed = new URL(url, origin);
    calls.push(parsed);
    if (parsed.pathname === '/app/adsVendors') return {ok: true, json: async () => options};
    if (parsed.pathname.endsWith('Dates')) return {ok: true, json: async () => [date]};
    assert.equal(parsed.pathname, page.endpoint);
    return {ok: true, json: async () => ({
      campaignName: 'Shared campaign',
      adsetName: 'Shared adset',
      columns: [{key: 'name', label: 'Name'}, {key: 'match_type', label: 'Match type'}],
      rows: [{campaignId: 12, adsetId: 34, values: {name: 'Example', match_type: 'negative'}}]
    })};
  });
  await import(new URL(`../../main/resources/static/js/ads-${page.name}.js?vendor-test=${moduleId++}`, import.meta.url));
  await setImmediate();
  return {
    elements, calls, downloads, navigations, errors,
    reports: () => calls.filter(call => call.pathname !== '/app/adsVendors'),
    body: () => elements.get(page.prefix + 'Body'),
    links: () => elements.get(page.prefix + 'Body').querySelectorAll('a').map(link => new URL(link.href, origin)),
    restoreFromBack() { windowEvents.get('pageshow')?.({persisted: true}); },
    download() { elements.get('downloadCsvBtn').dispatch('click'); }
  };
}

test('overview resolves the server default UUID and includes it in dates, report, URL, links and CSV', async t => {
  const page = await openPage(t, pages[0], `date=${date}`);
  assert.equal(page.elements.get('adsVendorSelect').value, firstVendor.vendorId);
  assert.deepEqual(page.elements.get('adsVendorSelect').children.map(option => option.textContent),
    ['First vendor (sellfusion)', 'Second vendor (second)']);
  assert.equal(window.location.searchParams.get('vendorId'), firstVendor.vendorId);
  for (const url of [...page.reports(), ...page.links()]) {
    assert.equal(url.searchParams.get('vendorId'), firstVendor.vendorId);
  }
  page.download();
  assert.deepEqual(page.downloads, [`ads-campaigns-${firstVendor.vendorId}-${date}.csv`]);
});

test('overview accepts the server fallback when sellfusion has no campaigns', async t => {
  const page = await openPage(t, pages[0], `date=${date}`, {
    vendors: [secondVendor], defaultVendorId: secondVendor.vendorId
  });
  assert.equal(page.elements.get('adsVendorSelect').value, secondVendor.vendorId);
  assert.equal(window.location.searchParams.get('vendorId'), secondVendor.vendorId);
});

for (const period of [`date=${date}`, `dateFrom=2026-09-01&dateTo=${date}`]) {
  test(`changing vendor navigates with the selected period (${period})`, async t => {
    const page = await openPage(t, pages[0], `vendorId=${firstVendor.vendorId}&campaignId=12&adsetId=34&negative=true&${period}`);
    const previousUrl = window.location.href;
    const select = page.elements.get('adsVendorSelect');
    select.value = secondVendor.vendorId;
    select.dispatch('change');
    assert.equal(page.navigations.length, 1);
    const target = page.navigations[0];
    assert.equal(target.pathname, '/private/ads-campaigns');
    assert.equal(target.searchParams.get('vendorId'), secondVendor.vendorId);
    for (const [key, value] of new URLSearchParams(period)) assert.equal(target.searchParams.get(key), value);
    for (const key of ['campaignId', 'adsetId', 'negative']) assert.equal(target.searchParams.get(key), null);
    assert.equal(window.location.href, previousUrl, 'navigation leaves the prior history entry intact for Back');
    page.restoreFromBack();
    assert.equal(select.value, firstVendor.vendorId, 'Back restores the vendor matching the cached report');
  });
}

test('an explicit second vendor stays selected when returning to a saved overview URL', async t => {
  const page = await openPage(t, pages[0], `vendorId=${secondVendor.vendorId}&dateFrom=2026-09-01&dateTo=${date}`);
  assert.equal(page.elements.get('adsVendorSelect').value, secondVendor.vendorId);
  assert.equal(window.location.searchParams.get('dateFrom'), '2026-09-01');
  for (const url of [...page.reports(), ...page.links()]) assert.equal(url.searchParams.get('vendorId'), secondVendor.vendorId);
});

test('overview keeps the existing latest-date fallback for an unavailable requested day', async t => {
  const page = await openPage(t, pages[0], `vendorId=${secondVendor.vendorId}&date=2026-01-01`);
  assert.equal(window.location.searchParams.get('date'), date);
  assert.equal(page.reports().find(url => url.pathname === pages[0].endpoint).searchParams.get('date'), date);
});

test('overview with no stored campaigns displays an empty vendor state without requesting reports', async t => {
  const page = await openPage(t, pages[0], '', {vendors: [], defaultVendorId: null});
  assert.equal(page.elements.get('adsVendorSelect').disabled, true);
  assert.match(page.body().textContent, /No vendors with campaign reports/);
  assert.equal(page.reports().length, 0);
});

for (const pageType of pages) {
  test(`${pageType.name}: an unknown vendor never falls back to sellfusion`, async t => {
    const page = await openPage(t, pageType, `vendorId=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa&campaignId=12&adsetId=34&date=${date}`);
    assert.match(page.body().textContent, /Unknown or invalid vendor/);
    assert.equal(page.reports().length, 0);
  });
}

for (const pageType of pages.slice(1)) {
  test(`${pageType.name}: fixed vendor label, report requests and CSV retain explicit vendor`, async t => {
    const page = await openPage(t, pageType, `vendorId=${secondVendor.vendorId}&campaignId=12&adsetId=34&negative=true&dateFrom=2026-09-01&dateTo=${date}`);
    assert.equal(page.elements.get('adsVendorLabel').textContent, 'Vendor: Second vendor (second)');
    assert.equal(page.elements.has('adsVendorSelect'), false);
    assert.equal(window.location.searchParams.get('vendorId'), secondVendor.vendorId);
    for (const url of [...page.reports(), ...page.links()]) {
      assert.equal(url.searchParams.get('vendorId'), secondVendor.vendorId);
      assert.equal(url.searchParams.get('campaignId'), '12');
      if (!url.pathname.endsWith('Dates')) {
        assert.equal(url.searchParams.get('dateFrom'), '2026-09-01');
        assert.equal(url.searchParams.get('dateTo'), date);
      }
    }
    if (pageType.name === 'adsets') {
      assert.equal(page.links().length, 4);
      assert.equal(page.links().filter(link => link.searchParams.get('negative') === 'true').length, 1);
    }
    page.download();
    assert.equal(page.downloads.length, 1);
    assert.ok(page.downloads[0].includes(secondVendor.vendorId));
    assert.ok(page.downloads[0].endsWith(`2026-09-01-to-${date}.csv`));
    if (pageType.name === 'keywords') assert.ok(page.downloads[0].startsWith('ads-negative-keywords-'));
  });

  for (const missingOrInvalid of ['', 'vendorId=not-a-uuid&']) {
    test(`${pageType.name}: ${missingOrInvalid ? 'invalid' : 'missing'} vendor stops before loading reports`, async t => {
      const page = await openPage(t, pageType, `${missingOrInvalid}campaignId=12&adsetId=34&date=${date}`);
      assert.match(page.body().textContent, /Missing or invalid vendor/);
      assert.equal(page.calls.length, 0);
    });
  }
}
