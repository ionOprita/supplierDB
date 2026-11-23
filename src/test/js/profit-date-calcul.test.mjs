import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

const endpoint = '/app/profitCalculatorSettings';
const sharedEndpoint = '/app/profitCalculatorSharedSettings';
const moduleSource = await readFile(new URL('../../main/resources/static/js/profit-date-calcul.js', import.meta.url), 'utf8');
const template = await readFile(new URL('../../main/jte/profit-date-calcul.jte', import.meta.url), 'utf8');
const dataUrl = text => `data:text/javascript;base64,${Buffer.from(text).toString('base64')}`;
const common = dataUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
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
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); }
  replaceChildren(...children) { this.children = children; this.text = ''; }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  addEventListener(name, callback) { this.listeners.set(name, callback); }
  dispatch(name) { return this.listeners.get(name)?.({target: this}); }
  focus() { this.focused = true; }
}

function settings(group = 'S') {
  return {
    vendorGroup: group,
    general: {revision: 7, values: {geniusEasyboxRate: '0.35', monthlyCapitalRate: '0',
      monthlyInflationRate: '0.015', averageDaysPerMonth: '30.416666666666668', vatRate: '0.21',
      incomeTaxRate: '0.03', profitTaxRate: '0.16', dividendTaxRate: '0.16', unaccountedStornoRate: '0'}},
    generalCosts: {revision: 5, values: {packingCostRon: '1', courierCostRon: group === 'K' ? '11' : '13',
      geniusCostRon: '8', monthlyAccountingCostRon: '1600', warehousePreparationCostRon: '2'}},
    employees: {revision: 1, items: group === 'J' ? [] : [
      {label: 'Employee A', amountRon: null}, {label: 'Employee B', amountRon: '2500'}]},
    miscellaneous: {revision: 3, items: []},
    calculated: {previousMonth: '2026-10', previousMonthUnits: group === 'J' ? 0 : 1276,
      employeeTotalRon: group === 'J' ? '0' : '2500', miscellaneousTotalRon: '0',
      accountingCostPerUnitRon: group === 'J' ? null : '1.2539184952978056426',
      employeeCostPerUnitRon: group === 'J' ? null : '1.9592476489028213',
      miscellaneousCostPerUnitRon: group === 'J' ? null : '0', interestCostPerUnitRon: null}
  };
}

async function openPage(t, {options = {vendorGroups: ['J', 'K', 'S'].map(vendorGroup => ({vendorGroup})), defaultVendorGroup: 'S'},
  get = async group => settings(group), post = async () => ({saved: true}),
  getShared = async () => ({revision: 1, turnoverTaxVatRate: '0.21'}),
  postShared = async () => ({saved: true})} = {}) {
  const previousDocument = globalThis.document;
  const previousWindow = globalThis.window;
  t.after(() => {
    if (previousDocument === undefined) delete globalThis.document;
    else globalThis.document = previousDocument;
    if (previousWindow === undefined) delete globalThis.window;
    else globalThis.window = previousWindow;
  });
  const roots = new Map([...template.matchAll(/<\w+\b[^>]*\bid="([^"]+)"[^>]*>/g)].map(([tag, id]) => {
    const node = new Element();
    node.hidden = /\s+hidden\b/.test(tag);
    node.disabled = /\s+disabled\b/.test(tag);
    return [id, node];
  }));
  const descend = node => [node, ...node.children.flatMap(descend)];
  const all = () => [...roots.values()].flatMap(descend);
  globalThis.document = {createElement: tag => new Element(tag),
    getElementById: id => roots.get(id) ?? all().find(node => node.id === id)};
  const events = new Map();
  globalThis.window = {addEventListener: (name, callback) => events.set(name, callback)};
  const posts = [];
  const loads = [];
  const sharedPosts = [];
  let sharedLoads = 0;
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    let result;
    if (url === '/app/profitCalculatorOptions') {
      result = options;
    } else if (url === sharedEndpoint) {
      if (init?.method === 'POST') {
        const body = JSON.parse(init.body);
        sharedPosts.push(body);
        result = await postShared(body);
      } else {
        sharedLoads++;
        result = await getShared();
      }
    } else if (init?.method === 'POST') {
      assert.equal(url, endpoint);
      const body = JSON.parse(init.body);
      posts.push(body);
      result = await post(body);
    } else {
      const parsed = new URL(url, 'https://example.test');
      assert.equal(parsed.pathname, endpoint);
      const group = parsed.searchParams.get('vendorGroup');
      loads.push(group);
      result = await get(group);
    }
    return {ok: !result.httpStatus, status: result.httpStatus || 200, json: async () => result};
  });
  await import(dataUrl(moduleSource.replace("'./common.js'", JSON.stringify(common)) + `\n// fixture ${moduleId++}`));
  await setImmediate();
  const selector = roots.get('dateCalculGroup');
  return {
    selector, status: roots.get('dateCalculStatus'), retry: roots.get('dateCalculRetry'),
    editors: roots.get('dateCalculEditors'), loads, posts, events,
    sharedStatus: roots.get('dateCalculSharedStatus'), sharedRetry: roots.get('dateCalculSharedRetry'), sharedPosts,
    get sharedLoads() { return sharedLoads; },
    switchGroup(group) {
      selector.value = group;
      selector.dispatch('change');
    },
    input(section, key) { return document.getElementById(`dateCalcul-${section}-${key}`); },
    output(key) { return all().find(node => node.dataset.computed === key); },
    action(section, actionName) {
      return all().find(node => node.dataset.section === section && node.dataset.action === actionName);
    }
  };
}

function edit(page, section, field, value) {
  const input = page.input(section, field);
  input.value = value;
  input.dispatch('input');
  return input;
}

test('uses the default group and displays editable percentages and read-only costs and units', async t => {
  const page = await openPage(t);
  assert.deepEqual(page.loads, ['S']);
  assert.equal(page.selector.value, 'S');
  assert.equal(page.editors.hidden, false);
  assert.equal(page.input('general', 'vatRate').value, '21');
  assert.equal(page.input('general', 'monthlyInflationRate').value, '1.5');
  assert.equal(page.input('general', 'averageDaysPerMonth').value, '30.416666666666668');
  assert.equal(page.input('employees', 'amountRon-0').value, '');
  assert.equal(page.output('previousMonthUnits').textContent, '1,276');
  assert.match(page.output('previousMonthUnits').attributes.get('aria-label'), /2026-10/);
  assert.equal(page.output('accountingCostPerUnitRon').textContent, '1.25');
  assert.equal(page.output('employeeTotalRon').textContent, '2,500.00');
  assert.equal(page.output('miscellaneousTotalRon').textContent, '0.00');
  assert.equal(page.output('interestCostPerUnitRon').textContent, '—');
  assert.ok(page.action('general', 'save').disabled);
  page.switchGroup('J');
  await setImmediate();
  assert.equal(page.output('previousMonthUnits').textContent, '0');
  assert.equal(page.output('employeeCostPerUnitRon').textContent, '—');
  assert.equal(page.output('employeeTotalRon').textContent, '0.00');
});

test('preserves original group drafts and ignores stale group loads', async t => {
  let finishJ;
  const pendingJ = new Promise(resolve => { finishJ = resolve; });
  const page = await openPage(t, {get: async group => group === 'J' ? pendingJ : settings(group)});
  edit(page, 'general', 'vatRate', '19');
  page.switchGroup('J');
  page.switchGroup('K');
  await setImmediate();
  finishJ(settings('J'));
  await setImmediate();
  assert.equal(page.selector.value, 'K');
  assert.equal(page.input('generalCosts', 'courierCostRon').value, '11');
  assert.equal(page.input('general', 'vatRate').value, '21');
  page.switchGroup('S');
  await setImmediate();
  assert.equal(page.input('general', 'vatRate').value, '19');
  assert.equal(page.action('general', 'save').disabled, false);
  assert.equal(page.action('generalCosts', 'save').disabled, true);
  assert.equal(page.posts.length, 0);
});

test('validates exact rates and saves only the chosen section while retaining another draft', async t => {
  const current = settings();
  const page = await openPage(t, {get: async () => current, post: async request => {
    current[request.section] = {revision: request.expectedRevision + 1, values: request.values};
    current.calculated.accountingCostPerUnitRon = '2.50';
    return {saved: true};
  }});
  const vat = edit(page, 'general', 'vatRate', '100.0000000000000001');
  assert.equal(vat.attributes.get('aria-invalid'), 'true');
  assert.equal(page.action('general', 'save').disabled, true);
  edit(page, 'general', 'vatRate', ' 19.123456789123456789 ');
  const inflation = edit(page, 'general', 'monthlyInflationRate', '-100');
  assert.equal(inflation.attributes.get('aria-invalid'), 'true');
  edit(page, 'general', 'monthlyInflationRate', '-99.999999999999999999');
  edit(page, 'generalCosts', 'packingCostRon', '9007199254740993.123456789');
  page.action('general', 'save').dispatch('click');
  await setImmediate();
  assert.equal(page.posts.length, 1);
  assert.deepEqual(page.posts[0], {vendorGroup: 'S', section: 'general', expectedRevision: 7,
    values: {...settings().general.values, vatRate: '0.19123456789123456789', monthlyInflationRate: '-0.99999999999999999999'}});
  assert.equal(page.input('generalCosts', 'packingCostRon').value, '9007199254740993.123456789');
  assert.equal(page.output('accountingCostPerUnitRon').textContent, '2.50');
  assert.equal(page.action('general', 'save').disabled, true);
  assert.equal(page.action('generalCosts', 'save').disabled, false);
});

test('replaces cost rows with explicit blank and zero amounts and rejects missing names', async t => {
  const page = await openPage(t);
  page.action('employees', 'remove').dispatch('click');
  page.action('employees', 'add').dispatch('click');
  assert.equal(page.action('employees', 'save').disabled, true);
  assert.equal(page.input('employees', 'label-1').attributes.get('aria-invalid'), 'true');
  edit(page, 'employees', 'label-1', '  Employee C   ');
  edit(page, 'employees', 'amountRon-0', '0');
  page.action('employees', 'save').dispatch('click');
  await setImmediate();
  assert.deepEqual(page.posts, [{vendorGroup: 'S', section: 'employees', expectedRevision: 1,
    items: [{label: 'Employee B', amountRon: '0'}, {label: 'Employee C', amountRon: null}]}]);
});

test('keeps an edit after conflict and reloads the latest group with Revert', async t => {
  const current = settings();
  const page = await openPage(t, {get: async () => current, post: async () => {
    current.generalCosts = {revision: 6, values: {...current.generalCosts.values, packingCostRon: '3'}};
    return {httpStatus: 409};
  }});
  edit(page, 'generalCosts', 'packingCostRon', '2');
  page.action('generalCosts', 'save').dispatch('click');
  await setImmediate();
  assert.match(page.status.textContent, /Your edits are still here/);
  assert.equal(page.input('generalCosts', 'packingCostRon').value, '2');
  assert.equal(page.posts[0].expectedRevision, 5);
  page.action('generalCosts', 'revert').dispatch('click');
  await setImmediate();
  assert.equal(page.input('generalCosts', 'packingCostRon').value, '3');
  assert.equal(page.action('generalCosts', 'save').disabled, true);
  assert.equal(page.posts.length, 1);
});

test('only retries the read if a save committed and the settings refresh failed', async t => {
  let attempts = 0;
  const current = settings();
  const page = await openPage(t, {get: async () => {
    if (++attempts === 2) throw new Error('Read unavailable');
    return current;
  }, post: async request => {
    current[request.section] = {revision: request.expectedRevision + 1, values: request.values};
    return {saved: true};
  }});
  edit(page, 'generalCosts', 'packingCostRon', '4');
  edit(page, 'general', 'vatRate', '19');
  page.action('generalCosts', 'save').dispatch('click');
  await setImmediate();
  assert.match(page.status.textContent, /were saved/);
  assert.equal(page.input('generalCosts', 'packingCostRon').value, '4');
  assert.equal(page.action('generalCosts', 'save').disabled, true);
  assert.equal(page.output('accountingCostPerUnitRon').textContent, '—');
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.posts.length, 1);
  assert.deepEqual(page.loads, ['S', 'S', 'S']);
  assert.equal(page.input('general', 'vatRate').value, '19');
  assert.equal(page.action('general', 'save').disabled, false);
  assert.equal(page.output('accountingCostPerUnitRon').textContent, '1.25');
});

test('keeps editors hidden when there are no vendor groups', async t => {
  const empty = await openPage(t, {options: {vendorGroups: [], defaultVendorGroup: null}});
  assert.equal(empty.selector.disabled, true);
  assert.equal(empty.editors.hidden, true);
  assert.deepEqual(empty.loads, []);
  assert.equal(empty.input('shared', 'turnoverTaxVatRate').value, '21');
});

test('retries settings that could not be loaded initially', async t => {
  let attempts = 0;
  const page = await openPage(t, {get: async group => {
    if (++attempts === 1) throw new Error('Unavailable');
    return settings(group);
  }});
  assert.equal(page.editors.hidden, true);
  assert.equal(page.retry.hidden, false);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.editors.hidden, false);
  assert.equal(page.retry.hidden, true);
});

test('shared tax VAT keeps its own draft, validates percentages and saves without group scope', async t => {
  let shared = {revision: 3, turnoverTaxVatRate: '0.21'};
  const page = await openPage(t, {getShared: async () => shared, postShared: async body => {
    shared = {revision: body.expectedRevision + 1, turnoverTaxVatRate: body.turnoverTaxVatRate};
    return {saved: true};
  }});
  const invalid = edit(page, 'shared', 'turnoverTaxVatRate', '100.000000001');
  assert.equal(invalid.attributes.get('aria-invalid'), 'true');
  assert.equal(page.action('shared', 'save').disabled, true);
  edit(page, 'shared', 'turnoverTaxVatRate', '19.123456789123456789');
  page.switchGroup('K');
  await setImmediate();
  assert.equal(page.input('shared', 'turnoverTaxVatRate').value, '19.123456789123456789');
  assert.equal(page.input('general', 'vatRate').value, '21');
  assert.equal(page.sharedLoads, 1);
  edit(page, 'general', 'vatRate', '20');
  page.action('shared', 'save').dispatch('click');
  await setImmediate();
  assert.deepEqual(page.sharedPosts, [{expectedRevision: 3, turnoverTaxVatRate: '0.19123456789123456789'}]);
  assert.equal(page.input('general', 'vatRate').value, '20');
  assert.equal(page.action('general', 'save').disabled, false);
  assert.equal(page.action('shared', 'save').disabled, true);
  assert.deepEqual(page.loads, ['S', 'K']);
  assert.equal(page.posts.length, 0);
});

test('shared tax VAT retains a conflicted draft and Revert reloads the saved zero', async t => {
  let shared = {revision: 3, turnoverTaxVatRate: '0.21'};
  const page = await openPage(t, {getShared: async () => shared, postShared: async () => {
    shared = {revision: 4, turnoverTaxVatRate: '0'};
    return {httpStatus: 409};
  }});
  edit(page, 'shared', 'turnoverTaxVatRate', '');
  page.action('shared', 'save').dispatch('click');
  await setImmediate();
  assert.match(page.sharedStatus.textContent, /Your edits are still here/);
  assert.equal(page.input('shared', 'turnoverTaxVatRate').value, '');
  assert.deepEqual(page.sharedPosts, [{expectedRevision: 3, turnoverTaxVatRate: null}]);
  page.action('shared', 'revert').dispatch('click');
  await setImmediate();
  assert.equal(page.input('shared', 'turnoverTaxVatRate').value, '0');
  assert.equal(page.action('shared', 'save').disabled, true);
  assert.equal(page.sharedPosts.length, 1);
});

test('shared tax VAT retries only its readback after a successful save', async t => {
  let shared = {revision: 3, turnoverTaxVatRate: '0.21'};
  let attempts = 0;
  const page = await openPage(t, {getShared: async () => {
    if (++attempts === 2) throw new Error('Read unavailable');
    return shared;
  }, postShared: async body => {
    shared = {revision: body.expectedRevision + 1, turnoverTaxVatRate: body.turnoverTaxVatRate};
    return {saved: true};
  }});
  edit(page, 'shared', 'turnoverTaxVatRate', '');
  page.action('shared', 'save').dispatch('click');
  await setImmediate();
  assert.match(page.sharedStatus.textContent, /were saved/);
  assert.equal(page.input('shared', 'turnoverTaxVatRate').disabled, true);
  assert.equal(page.sharedRetry.hidden, false);
  page.sharedRetry.dispatch('click');
  await setImmediate();
  assert.equal(page.sharedLoads, 3);
  assert.equal(page.sharedPosts.length, 1);
  assert.equal(page.sharedRetry.hidden, true);
  assert.equal(page.input('shared', 'turnoverTaxVatRate').value, '');
  assert.equal(page.input('shared', 'turnoverTaxVatRate').disabled, false);
});
