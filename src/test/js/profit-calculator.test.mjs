import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';
import {overrideFields, overrideInputValue, parseOverrideInput} from '../../main/resources/static/js/profit-calculator-overrides.js';

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
    this.classes = new Set();
    this.classList = {toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)};
  }
  set innerHTML(value) { assert.equal(value, ''); this.replaceChildren(); }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.text = ''; this.value = ''; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  setCustomValidity(message) { this.validationMessage = message; }
  showModal() { this.open = true; }
  close() { this.open = false; }
  addEventListener(name, callback) {
    this.listeners.set(name, [...(this.listeners.get(name) || []), callback]);
  }
  dispatch(name) { for (const callback of this.listeners.get(name) || []) callback({target: this}); }
}

const product = (code, name = code, retracted = false) => ({productCode: code, name, pnk: `PNK-${code}`, retracted});
const products = [product('first', 'S. 2 - Trimmer'), product('second', 'S. 10 - Trimmer', true)];
const metricCell = (page, sourceRow, productIndex = 0) => page.body.children
  .find(row => Number(row.dataset.sourceRow) === sourceRow).children[productIndex + 1];
const returnRateCells = (page, productIndex = 0) => [53, 54]
  .map(row => metricCell(page, row, productIndex).textContent);
const preparationCostCells = (page, productIndex = 0) => [56, 83, 84, 87]
  .map(row => metricCell(page, row, productIndex).textContent);
const fbeFeeCells = (page, productIndex = 0) => [57, 58, 59, 60]
  .map(row => metricCell(page, row, productIndex).textContent);
const assertCategorySummary = (page, category = '') => assert.deepEqual(
  page.summary.children.map(row => row.children[1].textContent), [category, '', '', '', '']);
const overrideInput = (page, row, index = 0) => metricCell(page, row, index).children[0];
const edit = (page, row, value, index = 0) => {
  const input = overrideInput(page, row, index);
  input.value = value;
  input.dispatch(row === 45 ? 'change' : 'input');
  return input;
};
const advancePreview = async t => { t.mock.timers.tick(200); await setImmediate(); };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}

async function openPage(t, {categoryPage = true, search = '', response = options, failFirst = false,
  categoryRequest = async () => ({products}), previewRequest = async () => ({products}),
  saveRequest = async () => ({saved: true})} = {}) {
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
  const posts = [];
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
    if (init?.method === 'POST') {
      const body = JSON.parse(init.body);
      posts.push({path: requested.pathname, body});
      const payload = await (requested.pathname === '/app/profitCalculatorPreview'
        ? previewRequest(body, requested) : saveRequest(body, requested));
      return payload?.httpStatus
        ? {ok: false, status: payload.httpStatus, json: async () => payload}
        : {ok: true, json: async () => payload};
    }
    if (requested.pathname === '/app/profitCalculatorCategory') {
      return {ok: true, json: async () => categoryRequest(requested)};
    }
    assert.equal(requested.pathname, '/app/profitCalculatorOptions');
    return failFirst && calls.length === 1
      ? {ok: false, status: 503}
      : {ok: true, json: async () => response};
  });
  const layoutUrl = dataUrl(await readFile(new URL('../../main/resources/static/js/profit-calculator-layout.js', import.meta.url), 'utf8'));
  const overridesUrl = dataUrl(await readFile(new URL('../../main/resources/static/js/profit-calculator-overrides.js', import.meta.url), 'utf8'));
  const tableSource = await readFile(new URL('../../main/resources/static/js/profit-calculator-table.js', import.meta.url), 'utf8');
  const tableUrl = dataUrl(tableSource.replace("'./profit-calculator-layout.js'", JSON.stringify(layoutUrl))
    .replace("'./profit-calculator-overrides.js'", JSON.stringify(overridesUrl)));
  const source = await readFile(new URL('../../main/resources/static/js/profit-calculator.js', import.meta.url), 'utf8');
  await import(dataUrl(source.replace("'./common.js'", JSON.stringify(commonUrl))
    .replace("'./profit-calculator-table.js'", JSON.stringify(tableUrl))
    .replace("'./profit-calculator-overrides.js'", JSON.stringify(overridesUrl)) + `\n// fixture ${moduleId++}`));
  await setImmediate();
  return {
    calls,
    posts, events, elements,
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
  assertCategorySummary(page, 'Hair trimmers');
  assert.deepEqual(page.categoryNames(), ['Hair trimmers', 'Personal care']);
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  assertCategorySummary(page, 'Personal care');
  page.group.value = 'J';
  page.group.dispatch('change');
  assertCategorySummary(page, 'Baby products');
  await setImmediate();
  assert.deepEqual(page.categoryNames(), ['Baby products', 'Toys']);
  assert.equal(page.category.value, 'Baby products');
  assert.equal(window.location.searchParams.get('vendorGroup'), 'J');
  assert.equal(window.location.searchParams.get('category'), 'Baby products');
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 1,
    'Changing filters uses the already loaded selector options');
  assert.equal(page.calls.at(-1).searchParams.get('vendorGroup'), 'J');
  assert.equal(page.calls.at(-1).searchParams.get('category'), 'Baby products');
  assertCategorySummary(page, 'Baby products');
  page.group.value = 'Z';
  page.group.dispatch('change');
  assertCategorySummary(page);
});

test('saved URLs restore a category belonging to the requested vendor group', async t => {
  const page = await openPage(t, {search: 'vendorGroup=J&category=Toys'});
  assert.equal(page.group.value, 'J');
  assert.equal(page.category.value, 'Toys');
  assert.deepEqual(page.categoryNames(), ['Baby products', 'Toys']);
  assertCategorySummary(page, 'Toys');
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
  assertCategorySummary(page);
});

test('no available vendor groups leaves both selectors disabled', async t => {
  const page = await openPage(t, {response: {vendorGroups: [], defaultVendorGroup: null}});
  assert.equal(page.group.disabled, true);
  assert.equal(page.category.disabled, true);
  assert.match(page.status.textContent, /no vendor groups/i);
  assertCategorySummary(page);
});

test('failed options loading can be retried', async t => {
  const page = await openPage(t, {failFirst: true});
  assert.equal(page.group.disabled, true);
  assert.equal(page.category.disabled, true);
  assert.equal(page.retry.hidden, false);
  assertCategorySummary(page);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 2);
  assert.equal(page.group.value, 'S');
  assert.equal(page.category.value, 'Hair trimmers');
  assert.equal(page.retry.hidden, true);
  assertCategorySummary(page, 'Hair trimmers');
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
  assert.ok(page.body.children.filter(row => ![40, 41, 42, 43, 44, 45].includes(Number(row.dataset.sourceRow)))
    .every(row => row.children.slice(1).every(cell => !cell.textContent && !cell.children.length)));
  assertCategorySummary(page, 'Hair trimmers');
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
  assertCategorySummary(page, 'Hair trimmers');
  assert.equal(page.wrap.attributes.get('aria-busy'), 'false');
});

test('the three confirmed fields display prices, literal tax regimes and disabled VAT states only', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('first'), emagSalePriceRon: 1234.5, incomeProfitTax: 'Profit', vatPayer: true},
    {...product('second'), emagSalePriceRon: 0, incomeProfitTax: 'Venit', vatPayer: false},
    {...product('literal'), emagSalePriceRon: 9.99, incomeProfitTax: '<b>Profit</b>', vatPayer: null}
  ]})});
  assert.equal(metricCell(page, 48).textContent, '1,234.50');
  assert.equal(metricCell(page, 48, 1).textContent, '0.00');
  assert.equal(metricCell(page, 71).textContent, 'Profit');
  assert.equal(metricCell(page, 71, 1).textContent, 'Venit');
  assert.equal(metricCell(page, 71, 2).textContent, '<b>Profit</b>');
  assert.equal(metricCell(page, 71, 2).children.length, 0);
  for (const [index, checked] of [true, false].entries()) {
    const input = metricCell(page, 72, index).children[0];
    assert.equal(input.type, 'checkbox');
    assert.equal(input.checked, checked);
    assert.equal(input.disabled, true);
  }
  assert.equal(metricCell(page, 72, 2).children.length, 0);
  assert.ok(page.body.children.filter(row => ![40, 41, 42, 43, 44, 45, 48, 71, 72].includes(Number(row.dataset.sourceRow)))
    .every(row => row.children.slice(1).every(cell => !cell.textContent && !cell.children.length)));
  assertCategorySummary(page, 'Hair trimmers');
});

test('missing source values and ineligible products keep all three metric cells empty', async t => {
  const populated = {emagSalePriceRon: 169.99, incomeProfitTax: 'Profit', vatPayer: true};
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('nulls'), emagSalePriceRon: null, incomeProfitTax: null, vatPayer: null},
    product('missing-fields'),
    {...product('retracted', 'Retracted product', true), ...populated},
    {...product('missing-pnk'), ...populated, pnk: null},
    {...product('blank-pnk'), ...populated, pnk: ' \t\n'}
  ]})});
  for (const sourceRow of [48, 71, 72]) {
    for (let index = 0; index < 5; index++) {
      const value = metricCell(page, sourceRow, index);
      assert.equal(value.textContent, '');
      assert.equal(value.children.length, 0);
    }
  }
});

test('category changes replace reference values, monthly units, return rates and FBE fees', async t => {
  const page = await openPage(t, {categoryRequest: url => ({products: [
    url.searchParams.get('category') === 'Hair trimmers'
      ? {...product('old'), emagSalePriceRon: 169.99, incomeProfitTax: 'Profit', vatPayer: true,
        calculated: {previousMonthGrossUnits: 1234, returnRate: 0.1, refusalRate: 0.025,
          fbeProcessingCostRon: 3.5, fbeReturnCostRon: 1.8,
          fbeWithdrawalCostRon: 2, fbeDisposalCostRon: 2}}
      : {...product('new'), emagSalePriceRon: 89, incomeProfitTax: 'Venit', vatPayer: false,
        calculated: {previousMonthGrossUnits: 7, returnRate: null, refusalRate: 0,
          fbeProcessingCostRon: 5.5, fbeReturnCostRon: null,
          fbeWithdrawalCostRon: 3.2, fbeDisposalCostRon: 2.1}}
  ]})});
  assert.equal(metricCell(page, 48).textContent, '169.99');
  assert.equal(metricCell(page, 35).textContent, '1,234');
  assert.deepEqual(returnRateCells(page), ['10%', '2.5%']);
  assert.deepEqual(fbeFeeCells(page), ['3.50', '1.80', '2.00', '2.00']);
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  assert.ok(page.body.children.every(row => row.children.length === 1));
  await setImmediate();
  assert.equal(metricCell(page, 48).textContent, '89.00');
  assert.equal(metricCell(page, 71).textContent, 'Venit');
  assert.equal(metricCell(page, 72).children[0].checked, false);
  assert.equal(metricCell(page, 35).textContent, '7');
  assert.deepEqual(returnRateCells(page), ['', '0%']);
  assert.deepEqual(fbeFeeCells(page), ['5.50', '', '3.20', '2.10']);
});

test('return and refusal rates display percentages with zero, missing values and product eligibility', async t => {
  const calculated = {returnRate: 0.12345678, refusalRate: 0.005};
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('known'), calculated},
    {...product('zero'), calculated: {returnRate: 0, refusalRate: 0}},
    {...product('only-return'), calculated: {returnRate: 0.04, refusalRate: null}},
    {...product('only-refusal'), calculated: {returnRate: null, refusalRate: 0.07}},
    product('missing'),
    {...product('retracted', 'Retracted', true), calculated},
    {...product('no-pnk'), pnk: null, calculated},
    {...product('blank-pnk'), pnk: ' \t', calculated}
  ]})});
  assert.deepEqual(returnRateCells(page), ['12.3457%', '0.5%']);
  assert.deepEqual(returnRateCells(page, 1), ['0%', '0%']);
  assert.deepEqual(returnRateCells(page, 2), ['4%', '']);
  assert.deepEqual(returnRateCells(page, 3), ['', '7%']);
  for (let index = 4; index < 8; index++) assert.deepEqual(returnRateCells(page, index), ['', '']);
  for (const row of [55, 83, 84, 85]) assert.equal(metricCell(page, row).textContent, '');
});

test('FBE reference fees display RON amounts and preserve zero, partial unknowns and eligibility', async t => {
  const known = {fbeProcessingCostRon: 3.5, fbeReturnCostRon: 1.8,
    fbeWithdrawalCostRon: 2, fbeDisposalCostRon: 2};
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('known'), calculated: known},
    {...product('zero'), calculated: Object.fromEntries(Object.keys(known).map(key => [key, 0]))},
    {...product('partial'), calculated: {fbeProcessingCostRon: null, fbeReturnCostRon: 0.3,
      fbeWithdrawalCostRon: null, fbeDisposalCostRon: 1.1}},
    product('missing'),
    {...product('retracted', 'Retracted', true), calculated: known},
    {...product('no-pnk'), pnk: null, calculated: known},
    {...product('blank-pnk'), pnk: ' \t', calculated: known}
  ]})});
  assert.deepEqual(fbeFeeCells(page), ['3.50', '1.80', '2.00', '2.00']);
  assert.deepEqual(fbeFeeCells(page, 1), ['0.00', '0.00', '0.00', '0.00']);
  assert.deepEqual(fbeFeeCells(page, 2), ['', '0.30', '', '1.10']);
  for (let index = 3; index < 7; index++) assert.deepEqual(fbeFeeCells(page, index), ['', '', '', '']);
  for (const row of [61, 83, 84, 85, 87]) assert.equal(metricCell(page, row).textContent, '');
});

test('warehouse, parcel preparation, refusal and defective costs use the existing amount format', async t => {
  const calculated = {warehousePreparationCostRon: 2.005, refusalCostRon: 0.1234,
    defectiveProductCostRon: 0, parcelPreparationCostRon: 4.84};
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('known'), calculated},
    {...product('unknown'), calculated: {warehousePreparationCostRon: 0, refusalCostRon: null,
      defectiveProductCostRon: null, parcelPreparationCostRon: null}},
    {...product('retracted', 'Retracted', true), calculated},
    {...product('no-pnk'), pnk: null, calculated}
  ]})});
  assert.deepEqual(preparationCostCells(page), ['2.01', '0.12', '0.00', '4.84']);
  assert.deepEqual(preparationCostCells(page, 1), ['0.00', '', '', '']);
  assert.deepEqual(preparationCostCells(page, 2), ['', '', '', '']);
  assert.deepEqual(preparationCostCells(page, 3), ['', '', '', '']);
});

test('monthly gross units display as integers while zero, unknown and ineligible products remain blank', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('known'), calculated: {previousMonthGrossUnits: 1234}},
    {...product('zero'), calculated: {previousMonthGrossUnits: 0}},
    {...product('unknown'), calculated: {previousMonthGrossUnits: null}},
    product('missing'),
    {...product('retracted', 'Retracted', true), calculated: {previousMonthGrossUnits: 12}},
    {...product('no-pnk'), pnk: null, calculated: {previousMonthGrossUnits: 12}}
  ]})});
  assert.equal(metricCell(page, 35).textContent, '1,234');
  for (let index = 1; index < 6; index++) assert.equal(metricCell(page, 35, index).textContent, '');
  for (const row of [36, 37]) {
    for (let index = 0; index < 6; index++) assert.equal(metricCell(page, row, index).textContent, '');
  }
  assertCategorySummary(page, 'Hair trimmers');
});

test('per-product commission results display independently without a category-wide rate', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('first'), calculated: {effectiveCommissionRate: 0.14, commissionCostRon: 14}},
    {...product('second'), calculated: {effectiveCommissionRate: 0.18, commissionCostRon: 36}},
    {...product('zero'), calculated: {effectiveCommissionRate: 0, commissionCostRon: 0}},
    {...product('unknown'), calculated: {effectiveCommissionRate: null, commissionCostRon: null}}
  ]})});
  assert.deepEqual([0, 1, 2, 3].map(index => metricCell(page, 76, index).textContent), ['14%', '18%', '0%', '']);
  assert.deepEqual([0, 1, 2, 3].map(index => metricCell(page, 86, index).textContent), ['14.00', '36.00', '0.00', '']);
  assertCategorySummary(page, 'Hair trimmers');
});

test('confirmed zero costs display as amounts while unknown and ineligible results stay blank', async t => {
  const costRows = [63, 64, 88, 100, 101, 105, 106];
  const calculated = {googleAdsCostRon: 0, socialMediaAdsCostRon: 0, storageCostRon: 0,
    turnoverTaxCostRon: 0, vatCostRon: 0, profitTaxCostRon: 0, dividendTaxCostRon: 0};
  const page = await openPage(t, {categoryRequest: async () => ({products: [
    {...product('known'), calculated},
    {...product('nulls'), calculated: Object.fromEntries(Object.keys(calculated).map(key => [key, null]))},
    product('missing'),
    {...product('retracted', 'Retracted', true), calculated},
    {...product('no-pnk'), pnk: null, calculated}
  ]})});
  for (const row of costRows) {
    assert.equal(metricCell(page, row).textContent, '0.00');
    for (let index = 1; index < 5; index++) assert.equal(metricCell(page, row, index).textContent, '');
  }
  for (const row of [28, 29, 30, 103, 104, 107]) assert.equal(metricCell(page, row).textContent, '');
  assertCategorySummary(page, 'Hair trimmers');
});

test('category changes clear zero costs before loading unknown results for the next category', async t => {
  const calculated = {googleAdsCostRon: 0, socialMediaAdsCostRon: 0, storageCostRon: 0,
    turnoverTaxCostRon: 0, vatCostRon: 0, profitTaxCostRon: 0, dividendTaxCostRon: 0};
  const page = await openPage(t, {categoryRequest: async url => ({products: [
    url.searchParams.get('category') === 'Hair trimmers'
      ? {...product('old'), calculated}
      : {...product('new'), calculated: Object.fromEntries(Object.keys(calculated).map(key => [key, null]))}
  ]})});
  assert.equal(metricCell(page, 88).textContent, '0.00');
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  assert.ok(page.body.children.every(row => row.children.length === 1));
  await setImmediate();
  for (const row of [63, 64, 88, 100, 101, 105, 106]) assert.equal(metricCell(page, row).textContent, '');
});

test('a delayed category response cannot overwrite the latest selected category', async t => {
  const initial = deferred();
  const latest = deferred();
  const page = await openPage(t, {categoryRequest: url => url.searchParams.get('category') === 'Hair trimmers'
    ? initial.promise : latest.promise});
  assertCategorySummary(page, 'Hair trimmers');
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  assertCategorySummary(page, 'Personal care');
  assert.ok(page.head.children.every(row => row.children.length === 1), 'Old product columns clear immediately');
  assert.match(page.body.textContent, /Total Venit\/Unitate/);
  latest.resolve({products: [{...product('latest'), calculated: {previousMonthGrossUnits: 23,
    fbeProcessingCostRon: 4.2, fbeReturnCostRon: 2.1, fbeWithdrawalCostRon: 2, fbeDisposalCostRon: 3}}]});
  await setImmediate();
  initial.resolve({products: [{...product('old'), calculated: {previousMonthGrossUnits: 99,
    fbeProcessingCostRon: 30, fbeReturnCostRon: 15, fbeWithdrawalCostRon: 12, fbeDisposalCostRon: 12}}]});
  await setImmediate();
  assert.equal(page.head.children[0].children[1].textContent, 'latest');
  assert.equal(metricCell(page, 35).textContent, '23');
  assert.deepEqual(fbeFeeCells(page), ['4.20', '2.10', '2.00', '3.00']);
  assert.equal(page.status.hidden, true);
  assertCategorySummary(page, 'Personal care');
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
  assertCategorySummary(page, 'Toys');
  assert.match(page.body.textContent, /Total Venit\/Unitate/);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.head.children[0].children[1].textContent, 'toy');
  assert.equal(page.group.value, 'J');
  assert.equal(page.category.value, 'Toys');
  assert.equal(page.calls.filter(call => call.pathname === '/app/profitCalculatorOptions').length, 1);
  assert.equal(page.retry.hidden, true);
  assertCategorySummary(page, 'Toys');
});

test('override parsing preserves decimal precision, percent units, zero and clearing', () => {
  const field = key => overrideFields.find(item => item.key === key);
  assert.equal(parseOverrideInput(field('commissionRate'), '14.123456789123456789').value, '0.14123456789123456789');
  assert.equal(overrideInputValue(field('commissionRate'), '0.14123456789123456789'), '14.123456789123456789');
  assert.equal(parseOverrideInput(field('commissionRate'), '100').value, '1');
  assert.ok(parseOverrideInput(field('commissionRate'), '100.0000000000000001').error);
  assert.ok(parseOverrideInput(field('usdRonRate'), '0').error);
  assert.ok(parseOverrideInput(field('salePriceRon'), '-1').error);
  assert.ok(parseOverrideInput(field('salePriceRon'), '1'.repeat(65)).error);
  assert.equal(parseOverrideInput(field('salePriceRon'), '0').value, '0');
  assert.equal(parseOverrideInput(field('salePriceRon'), '').value, null);
});

test('saved overrides remain editable for ineligible products while outputs stay empty', async t => {
  const page = await openPage(t, {categoryRequest: async () => ({products: [{
    ...product('old', 'Retracted', true), overrides: {salePriceRon: '123.45', commissionRate: '0.14', fulfillmentSource: 'eMAG'},
    calculated: {effectiveSalePriceRon: 123.45}, companyName: 'Company'
  }]})});
  for (const row of [40, 41, 42, 43, 44, 45]) assert.equal(overrideInput(page, row).disabled, false);
  assert.equal(overrideInput(page, 40).value, '123.45');
  assert.equal(overrideInput(page, 41).value, '14');
  assert.equal(overrideInput(page, 45).value, 'eMAG');
  assert.equal(metricCell(page, 75).textContent, '');
  assert.equal(metricCell(page, 70).textContent, '');
});

test('previews use canonical strings and update results without replacing the focused input', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const item = {...product('one'), overrideRevision: 3, emagSalePriceRon: 200, companyName: 'Sellfusion SRL',
    calculated: {effectiveSalePriceRon: 200, previousMonthGrossUnits: 22}, overrides: {}};
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}), previewRequest: async body => {
    assert.deepEqual(body.changes, [{productCode: 'one', expectedRevision: 3, values: {commissionRate: '0.1425'}}]);
    return {products: [{...item, calculated: {effectiveSalePriceRon: 200, effectiveCommissionRate: 0.1425,
      commissionCostRon: 28.5, previousMonthGrossUnits: 22}}]};
  }});
  const focused = edit(page, 41, '14.25');
  assert.equal(page.elements.get('profitOverrideReview').disabled, true);
  assert.equal(metricCell(page, 35).textContent, '22', 'Commission edits do not invalidate monthly units');
  assertCategorySummary(page, 'Hair trimmers');
  await advancePreview(t);
  assert.equal(overrideInput(page, 41), focused);
  assert.equal(overrideInput(page, 41).value, '14.25');
  assert.equal(metricCell(page, 76).textContent, '14.25%');
  assert.equal(metricCell(page, 86).textContent, '28.50');
  assert.equal(metricCell(page, 70).textContent, 'Sellfusion SRL');
  assert.equal(metricCell(page, 35).textContent, '22');
  assert.equal(page.elements.get('profitOverrideReview').disabled, false);
  assertCategorySummary(page, 'Hair trimmers');
});

test('override-independent costs and rates retain the latest successful preview values', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const first = deferred();
  const second = deferred();
  const third = deferred();
  const fees = {fbeProcessingCostRon: 3.5, fbeReturnCostRon: 1.8,
    fbeWithdrawalCostRon: 2, fbeDisposalCostRon: 2};
  const item = {...product('one'), overrideRevision: 1, overrides: {fulfillmentSource: 'eMAG'},
    calculated: {...fees, effectiveSalePriceRon: 200, returnRate: 0.06, refusalRate: 0.035,
      warehousePreparationCostRon: 2, refusalCostRon: 0.15, parcelPreparationCostRon: 4.24}};
  let attempts = 0;
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}),
    previewRequest: () => [first, second, third][attempts++].promise});
  edit(page, 45, 'Minulescu');
  assert.deepEqual(returnRateCells(page), ['6%', '3.5%']);
  assert.deepEqual(preparationCostCells(page), ['2.00', '0.15', '', '']);
  assert.deepEqual(fbeFeeCells(page), ['3.50', '1.80', '2.00', '2.00'],
    'Reference FBE fees do not depend on the selected fulfillment source');
  await advancePreview(t);
  edit(page, 40, '99');
  assert.deepEqual(returnRateCells(page), ['6%', '3.5%']);
  assert.deepEqual(fbeFeeCells(page), ['3.50', '1.80', '2.00', '2.00'],
    'Selling-price edits leave reference fees available while preview is pending');
  await advancePreview(t);
  second.resolve({products: [{...item, overrides: {salePriceRon: '99', fulfillmentSource: 'Minulescu'},
    calculated: {effectiveSalePriceRon: 99, returnRate: null, refusalRate: 0,
      fbeProcessingCostRon: 4.25, fbeReturnCostRon: 2.11, fbeWithdrawalCostRon: 0, fbeDisposalCostRon: null,
      warehousePreparationCostRon: 2.5, refusalCostRon: 0, parcelPreparationCostRon: 2.5}}]});
  await setImmediate();
  assert.deepEqual(returnRateCells(page), ['', '0%']);
  assert.deepEqual(preparationCostCells(page), ['2.50', '0.00', '', '2.50']);
  assert.deepEqual(fbeFeeCells(page), ['4.25', '2.11', '0.00', '']);
  first.resolve({products: [item]});
  await setImmediate();
  assert.deepEqual(returnRateCells(page), ['', '0%']);
  assert.deepEqual(preparationCostCells(page), ['2.50', '0.00', '', '2.50']);
  assert.deepEqual(fbeFeeCells(page), ['4.25', '2.11', '0.00', ''],
    'An older response cannot restore outdated fees');
  assert.equal(metricCell(page, 75).textContent, '99.00');
  edit(page, 40, '');
  assert.deepEqual(returnRateCells(page), ['', '0%']);
  assert.deepEqual(preparationCostCells(page), ['2.50', '0.00', '', '']);
  assert.deepEqual(fbeFeeCells(page), ['4.25', '2.11', '0.00', ''],
    'Later edits retain the latest known tariffs while a new preview is pending');
  assert.equal(metricCell(page, 75).textContent, '200.00',
    'Clearing a price draft restores the original GET baseline, not the previous preview price');
  await advancePreview(t);
  assert.deepEqual(page.posts.at(-1).body.changes, [{productCode: 'one', expectedRevision: 1,
    values: {fulfillmentSource: 'Minulescu'}}], 'Preview references do not replace saved override/revision baselines');
  third.reject(new Error('Preview unavailable'));
  await setImmediate();
  assert.deepEqual(returnRateCells(page), ['', '0%']);
  assert.deepEqual(preparationCostCells(page), ['2.50', '0.00', '', '']);
  assert.deepEqual(fbeFeeCells(page), ['4.25', '2.11', '0.00', ''],
    'A failed subsequent preview does not restore older GET tariff values');
});

test('clearing a saved override sends null and restores the server reference-price fallback', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const item = {...product('one'), emagSalePriceRon: 169.99, overrideRevision: 1, overrides: {salePriceRon: '199.99'},
    calculated: {effectiveSalePriceRon: 199.99}};
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}), previewRequest: async body => {
    assert.equal(body.changes[0].values.salePriceRon, null);
    return {products: [{...item, calculated: {effectiveSalePriceRon: 169.99}}]};
  }});
  edit(page, 40, '');
  await advancePreview(t);
  assert.equal(metricCell(page, 75).textContent, '169.99');
  assert.equal(metricCell(page, 26).textContent, '169.99');
});

test('clearing commission restores the server product default and retains monthly units', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const item = {...product('one'), overrideRevision: 1, overrides: {commissionRate: '0.14'},
    calculated: {effectiveCommissionRate: 0.14, commissionCostRon: 28, previousMonthGrossUnits: 7}};
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}), previewRequest: async body => {
    assert.equal(body.changes[0].values.commissionRate, null);
    return {products: [{...item, calculated: {effectiveCommissionRate: 0.18, commissionCostRon: 36,
      previousMonthGrossUnits: 7}}]};
  }});
  edit(page, 41, '');
  assert.equal(metricCell(page, 76).textContent, '');
  assert.equal(metricCell(page, 86).textContent, '');
  assert.equal(metricCell(page, 35).textContent, '7');
  await advancePreview(t);
  assert.equal(overrideInput(page, 41).value, '');
  assert.equal(metricCell(page, 76).textContent, '18%');
  assert.equal(metricCell(page, 86).textContent, '36.00');
  assert.equal(metricCell(page, 35).textContent, '7');
});

test('fulfillment edits clear saved preparation and storage costs through pending, failed and invalid previews', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const pending = deferred();
  const item = {...product('one'), overrideRevision: 2, overrides: {fulfillmentSource: 'Minulescu'},
    calculated: {parcelPreparationCostRon: 2, storageCostRon: 0, googleAdsCostRon: 0}};
  let previews = 0;
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}), previewRequest: body => {
    assert.equal(body.changes[0].values.fulfillmentSource, previews++ === 0 ? 'eMAG' : null);
    return previews === 1 ? pending.promise
      : {products: [{...item, calculated: {parcelPreparationCostRon: null, storageCostRon: null}}]};
  }});
  assert.equal(metricCell(page, 88).textContent, '0.00');
  assert.equal(metricCell(page, 87).textContent, '2.00');
  edit(page, 45, 'eMAG');
  assert.equal(metricCell(page, 88).textContent, '', 'Saved zero is stale as soon as fulfillment changes');
  assert.equal(metricCell(page, 87).textContent, '');
  assert.equal(metricCell(page, 63).textContent, '0.00', 'Unrelated results remain available');
  await advancePreview(t);
  assert.equal(metricCell(page, 88).textContent, '');
  pending.reject(new Error('Preview unavailable'));
  await setImmediate();
  assert.equal(metricCell(page, 88).textContent, '');
  assert.equal(metricCell(page, 87).textContent, '');
  assert.match(page.elements.get('profitOverrideStatus').textContent, /could not preview/i);
  edit(page, 45, '');
  await advancePreview(t);
  assert.equal(metricCell(page, 88).textContent, '');
  assert.equal(metricCell(page, 87).textContent, '');
  edit(page, 45, 'Minulescu');
  assert.equal(metricCell(page, 88).textContent, '0.00', 'Restoring the saved selection restores its known result');
  assert.equal(metricCell(page, 87).textContent, '2.00');
  const invalid = edit(page, 45, 'Unknown warehouse');
  await advancePreview(t);
  assert.ok(invalid.validationMessage);
  assert.equal(metricCell(page, 88).textContent, '');
  assert.equal(metricCell(page, 87).textContent, '');
  assert.equal(page.posts.length, 2, 'Invalid fulfillment sends no preview request');
});

test('a stale Minulescu preview cannot restore storage zero after fulfillment is cleared', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const minulescu = deferred();
  const cleared = deferred();
  const item = {...product('one'), overrides: {fulfillmentSource: 'eMAG'}, calculated: {storageCostRon: null}};
  let previews = 0;
  const page = await openPage(t, {categoryRequest: async () => ({products: [item]}),
    previewRequest: () => ++previews === 1 ? minulescu.promise : cleared.promise});
  edit(page, 45, 'Minulescu');
  await advancePreview(t);
  edit(page, 45, '');
  await advancePreview(t);
  assert.equal(page.posts.at(-1).body.changes[0].values.fulfillmentSource, null);
  cleared.resolve({products: [{...item, calculated: {storageCostRon: null}}]});
  await setImmediate();
  minulescu.resolve({products: [{...item, calculated: {storageCostRon: 0}}]});
  await setImmediate();
  assert.equal(overrideInput(page, 45).value, '');
  assert.equal(metricCell(page, 88).textContent, '');
  assert.equal(page.elements.get('profitOverrideReview').disabled, false);
});

test('invalid input blocks saving and blanks its dependent results without a preview request', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const page = await openPage(t, {categoryRequest: async () => ({products: [{...product('one'), emagSalePriceRon: 169.99,
    calculated: {effectiveSalePriceRon: 169.99, effectiveCommissionRate: 0.14, commissionCostRon: 23.8}}]})});
  const input = edit(page, 40, '-');
  await advancePreview(t);
  assert.ok(input.validationMessage);
  assert.equal(page.posts.length, 0);
  assert.equal(metricCell(page, 75).textContent, '');
  assert.equal(metricCell(page, 86).textContent, '');
  assert.equal(metricCell(page, 76).textContent, '14%');
  assert.equal(metricCell(page, 48).textContent, '169.99');
  assert.equal(page.elements.get('profitOverrideReview').disabled, true);
});

test('a stale preview cannot replace newer values or errors', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const first = deferred();
  const second = deferred();
  let attempts = 0;
  const page = await openPage(t, {categoryRequest: async () => ({products: [product('one')]}),
    previewRequest: () => ++attempts === 1 ? first.promise : second.promise});
  edit(page, 40, '100');
  await advancePreview(t);
  edit(page, 40, '200');
  await advancePreview(t);
  second.resolve({products: [{...product('one'), calculated: {effectiveSalePriceRon: 200}}]});
  await setImmediate();
  first.reject(new Error('Stale request failed'));
  await setImmediate();
  assert.equal(metricCell(page, 75).textContent, '200.00');
  assert.equal(page.elements.get('profitOverrideReview').disabled, false);
  assert.match(page.elements.get('profitOverrideStatus').textContent, /preview updated/i);
});

test('category changes retain draft inputs and their original revision despite newer saved data', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  let reads = 0;
  const page = await openPage(t, {categoryRequest: async url => ({products: [
    url.searchParams.get('category') === 'Hair trimmers'
      ? {...product('one'), overrideRevision: ++reads, overrides: {salePriceRon: reads === 1 ? '100' : '300'}}
      : product('other')
  ]}), previewRequest: async () => ({products: [{...product('one'), calculated: {effectiveSalePriceRon: 120}}]})});
  edit(page, 40, '120');
  page.category.value = 'Personal care';
  page.category.dispatch('change');
  await setImmediate();
  page.category.value = 'Hair trimmers';
  page.category.dispatch('change');
  await setImmediate();
  assert.equal(overrideInput(page, 40).value, '120');
  assert.equal(page.posts.at(-1).body.changes[0].expectedRevision, 1);
  const event = {preventDefault() { this.prevented = true; }};
  page.events.get('beforeunload')(event);
  assert.equal(event.prevented, true);
});

test('saving reviewed changes clears drafts only after acknowledgement and reloads saved data', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  let saved = false;
  const page = await openPage(t, {categoryRequest: async () => ({products: [{...product('one'), overrideRevision: saved ? 2 : 1,
    overrides: {salePriceRon: saved ? '120' : '100'}, calculated: {effectiveSalePriceRon: saved ? 120 : 100}}]}),
  previewRequest: async () => ({products: [{...product('one'), calculated: {effectiveSalePriceRon: 120}}]}),
  saveRequest: async body => {
    assert.deepEqual(body.changes, [{productCode: 'one', expectedRevision: 1, values: {salePriceRon: '120'}}]);
    saved = true;
    return {saved: true};
  }});
  edit(page, 40, '120');
  await advancePreview(t);
  page.elements.get('profitOverrideReview').dispatch('click');
  assert.equal(page.elements.get('profitOverrideDialog').open, true);
  assert.match(page.elements.get('profitOverrideReviewBody').textContent, /100.*120/);
  page.elements.get('profitOverrideSave').dispatch('click');
  await setImmediate();
  assert.equal(page.elements.get('profitOverrideDialog').open, false);
  assert.equal(page.elements.get('profitOverrideReview').disabled, true);
  assert.equal(overrideInput(page, 40).value, '120');
  assert.match(page.elements.get('profitOverrideStatus').textContent, /saved/i);
});

test('save conflict retains drafts and Revert retrieves the latest saved values', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  let latest = false;
  const page = await openPage(t, {categoryRequest: async () => ({products: [{...product('one'), overrideRevision: latest ? 2 : 1,
    overrides: {salePriceRon: latest ? '300' : '100'}}]}),
  previewRequest: async () => ({products: [{...product('one'), calculated: {effectiveSalePriceRon: 120}}]}),
  saveRequest: async () => { latest = true; return {httpStatus: 409, error: 'Conflict', productCode: 'one'}; }});
  edit(page, 40, '120');
  await advancePreview(t);
  page.elements.get('profitOverrideReview').dispatch('click');
  page.elements.get('profitOverrideSave').dispatch('click');
  await setImmediate();
  assert.equal(overrideInput(page, 40).value, '120');
  assert.equal(page.elements.get('profitOverrideDialog').open, true);
  assert.match(page.elements.get('profitOverrideReviewError').textContent, /one.*edits are still here/i);
  page.elements.get('profitOverrideReviewRevert').dispatch('click');
  await setImmediate();
  assert.equal(overrideInput(page, 40).value, '300');
  assert.equal(page.elements.get('profitOverrideReview').disabled, true);
});

test('a reload failure after save acknowledgement retries only GET, never the saved POST', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  let saved = false;
  let failReload = true;
  const page = await openPage(t, {categoryRequest: async () => {
    if (saved && failReload) throw new Error('Reload failed');
    return {products: [{...product('one'), overrides: {salePriceRon: saved ? '120' : '100'}}]};
  }, previewRequest: async () => ({products: [{...product('one'), calculated: {effectiveSalePriceRon: 120}}]}),
  saveRequest: async () => { saved = true; return {saved: true}; }});
  edit(page, 40, '120');
  await advancePreview(t);
  page.elements.get('profitOverrideReview').dispatch('click');
  page.elements.get('profitOverrideSave').dispatch('click');
  await setImmediate();
  assert.match(page.elements.get('profitOverrideStatus').textContent, /saved/i);
  assert.equal(page.retry.hidden, false);
  assert.equal(page.elements.get('profitOverrideDialog').open, false);
  assert.equal(page.elements.get('profitOverrideReview').disabled, true);
  failReload = false;
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(overrideInput(page, 40).value, '120');
  assert.equal(page.posts.filter(request => request.path === '/app/profitCalculatorOverrides').length, 1);
});

test('save failures retain editable drafts and disable edits only while the request is pending', async t => {
  t.mock.timers.enable({apis: ['setTimeout']});
  const save = deferred();
  const page = await openPage(t, {categoryRequest: async () => ({products: [product('one')]}),
    previewRequest: async () => ({products: [{...product('one'), calculated: {effectiveSalePriceRon: 120}}]}),
    saveRequest: () => save.promise});
  edit(page, 40, '120');
  await advancePreview(t);
  page.elements.get('profitOverrideReview').dispatch('click');
  page.elements.get('profitOverrideSave').dispatch('click');
  assert.equal(overrideInput(page, 40).disabled, true);
  assert.equal(page.group.disabled, true);
  save.reject(new Error('Connection lost'));
  await setImmediate();
  assert.equal(overrideInput(page, 40).disabled, false);
  assert.equal(overrideInput(page, 40).value, '120');
  assert.equal(page.group.disabled, false);
  assert.match(page.elements.get('profitOverrideReviewError').textContent, /edits are still here/i);
});
