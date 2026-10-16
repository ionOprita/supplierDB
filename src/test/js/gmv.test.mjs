// Run with: node --test src/test/js/gmv.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/gmv.js', import.meta.url), 'utf8');
const {initMonthlyGMV} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const FIRST_VENDOR = '6a6fca21-f997-4589-ab95-4c75fe10c7b1';
const SECOND_VENDOR = '34aad1bc-a4f7-425a-8611-30a013788b4a';
const START = '2026-09-17';
const END = '2026-10-16';

class Element {
  constructor() {
    this.listeners = new Map();
    this.dataset = {};
    this.classes = new Set();
    this.classList = {toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)};
    this.textContent = '';
    this.hidden = false;
    this.disabled = false;
    this.value = '';
  }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  fire(type) { return this.listeners.get(type)?.({preventDefault() {}}); }
  cloneNode() {
    const copy = new Element();
    Object.assign(copy, {value: this.value, textContent: this.textContent, dataset: {...this.dataset}});
    return copy;
  }
}

class Select extends Element {
  options = [];
  get value() { return this.options?.some(option => option.value === this.selectedValue) ? this.selectedValue : ''; }
  set value(value) { this.selectedValue = value; }
  replaceChildren(...options) { this.options = options; }
}

class DateInput extends Element {
  customValidity = '';
  nativeValid = true;
  get valueAsNumber() { return Date.parse(this.value); }
  setCustomValidity(message) { this.customValidity = message; }
  checkValidity() { return this.nativeValid && Boolean(this.value) && Number.isFinite(this.valueAsNumber) && !this.customValidity; }
}

function fixture(vendorIds = [FIRST_VENDOR, SECOND_VENDOR, ''], selectedVendor = '', codes = []) {
  const elements = Object.fromEntries([
    'gmvVendorFilterSelect', 'gmvPeriodForm', 'gmvCalculateButton', 'gmvPeriodResult',
    'gmvPeriodContext', 'gmvPeriodStatus', 'gmvNoRows'
  ].map(id => [id, new Element()]));
  const rows = vendorIds.map((vendorId, index) => ({
    dataset: {vendorId, productCode: codes[index] ?? `CODE-${index}`},
    hidden: true,
    cells: [`Product ${index}`, `PNK-${index}`, codes[index] ?? `CODE-${index}`, '123.40', '—']
  }));
  const headers = ['Product', 'PNK', 'Product code', '2026-01', '2026-02'];
  elements.monthlyGmvTable = {
    querySelectorAll(selector) {
      assert.equal(selector, 'tbody tr[data-vendor-id]');
      return rows;
    }
  };
  const product = elements.gmvProductSelect = new Select();
  const allProducts = new Element();
  allProducts.textContent = 'All products';
  product.options = [allProducts, ...rows.map((row, index) => {
    const option = new Element();
    option.value = row.dataset.productCode;
    option.dataset.vendorId = row.dataset.vendorId;
    option.textContent = `Product ${index} (${option.value})`;
    return option;
  })];
  const start = elements.gmvStartDate = new DateInput();
  const end = elements.gmvEndDate = new DateInput();
  start.value = START;
  end.value = END;
  elements.gmvVendorFilterSelect.value = selectedVendor;
  elements.gmvPeriodForm.reportValidity = () => { elements.gmvPeriodForm.validityReported = true; };
  initMonthlyGMV({getElementById: id => elements[id] ?? null});
  return {
    rows, headers, elements, product, start, end,
    noRows: elements.gmvNoRows,
    button: elements.gmvCalculateButton,
    result: elements.gmvPeriodResult,
    context: elements.gmvPeriodContext,
    status: elements.gmvPeriodStatus,
    visibleRows: () => rows.filter(row => !row.hidden),
    productCodes: () => product.options.map(option => option.value),
    selectVendor(vendorId) {
      elements.gmvVendorFilterSelect.value = vendorId;
      elements.gmvVendorFilterSelect.fire('change');
    },
    selectProduct(code) {
      product.value = code;
      product.fire('change');
    },
    setDate(input, value, event = 'input') {
      input.value = value;
      input.fire(event);
    },
    submit: () => elements.gmvPeriodForm.fire('submit')
  };
}

function payload(productCode = 'CODE-0', gmv = '1234.56', startDate = START, endDate = END) {
  return {productCode, startDate, endDate, gmv};
}
function response(body = payload(), overrides = {}) {
  return {ok: true, status: 200, redirected: false,
    headers: {get: () => 'application/json'}, json: async () => body, ...overrides};
}
function deferred() {
  let resolve, reject;
  const promise = new Promise((accept, refuse) => { resolve = accept; reject = refuse; });
  return {promise, resolve, reject};
}

test('initial All vendors selection shows all products including those without a vendor', () => {
  const page = fixture();

  assert.equal(page.visibleRows().length, 3);
  assert.deepEqual(page.productCodes(), ['', 'CODE-0', 'CODE-1', 'CODE-2']);
  assert.equal(page.product.value, '');
  assert.equal(page.noRows.hidden, true);
  assert.equal(page.button.disabled, true);
  assert.match(page.status.textContent, /Select a product/);
  assert.equal(page.start.value, START);
  assert.equal(page.end.value, END);
});

test('vendor filtering uses the complete UUID and excludes products without a vendor', () => {
  const page = fixture([FIRST_VENDOR, SECOND_VENDOR, '', `${FIRST_VENDOR}-suffix`]);

  page.selectVendor(FIRST_VENDOR);

  assert.deepEqual(page.visibleRows().map(row => row.dataset.vendorId), [FIRST_VENDOR]);
  assert.deepEqual(page.productCodes(), ['', 'CODE-0']);
  assert.equal(page.noRows.hidden, true);
});

test('a vendor with no matching products retains All products and shows the empty row', () => {
  const page = fixture([FIRST_VENDOR, '']);

  page.selectVendor(SECOND_VENDOR);

  assert.deepEqual(page.visibleRows(), []);
  assert.deepEqual(page.productCodes(), ['']);
  assert.equal(page.noRows.hidden, false);
  assert.equal(page.button.disabled, true);
});

test('restoring All vendors preserves monthly columns, amounts and all original product options', t => {
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Filtering must not request data'); });
  const page = fixture();
  const originalHeaders = [...page.headers];
  const originalAmounts = page.rows.map(row => [...row.cells]);

  page.selectVendor(FIRST_VENDOR);
  page.selectVendor(SECOND_VENDOR);
  page.selectVendor('unknown-vendor');
  page.selectVendor('');

  assert.equal(page.visibleRows().length, 3);
  assert.equal(page.noRows.hidden, true);
  assert.deepEqual(page.headers, originalHeaders);
  assert.deepEqual(page.rows.map(row => row.cells), originalAmounts);
  assert.deepEqual(page.productCodes(), ['', 'CODE-0', 'CODE-1', 'CODE-2']);
});

test('an empty product list keeps the empty row visible for every filter', () => {
  const page = fixture([]);

  assert.equal(page.noRows.hidden, false);
  page.selectVendor(FIRST_VENDOR);
  assert.equal(page.noRows.hidden, false);
  page.selectVendor('');
  assert.equal(page.noRows.hidden, false);
  assert.deepEqual(page.productCodes(), ['']);
  assert.equal(page.button.disabled, true);
});

test('initialization applies a vendor selection restored by the browser', () => {
  const page = fixture([FIRST_VENDOR, SECOND_VENDOR, ''], SECOND_VENDOR);

  assert.deepEqual(page.visibleRows().map(row => row.dataset.vendorId), [SECOND_VENDOR]);
  assert.deepEqual(page.productCodes(), ['', 'CODE-1']);
});

test('initialization tolerates a page without the GMV table', () => {
  assert.doesNotThrow(() => initMonthlyGMV({getElementById: () => null}));
});

test('choosing a product shows its exact monthly row and All products restores the vendor overview', t => {
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Choosing a product must not calculate GMV'); });
  const page = fixture([FIRST_VENDOR, FIRST_VENDOR, SECOND_VENDOR], '', ['CODE', 'CODE-more', 'OTHER']);
  page.selectVendor(FIRST_VENDOR);

  page.selectProduct('CODE');
  assert.deepEqual(page.visibleRows().map(row => row.dataset.productCode), ['CODE']);
  assert.equal(page.button.disabled, false);
  page.selectProduct('');

  assert.deepEqual(page.visibleRows().map(row => row.dataset.productCode), ['CODE', 'CODE-more']);
  assert.equal(page.button.disabled, true);
  assert.match(page.status.textContent, /Select a product/);
});

test('vendor changes preserve a matching product and reset an excluded selection to All products', () => {
  const page = fixture([FIRST_VENDOR, FIRST_VENDOR, SECOND_VENDOR]);
  page.selectProduct('CODE-0');
  page.selectVendor(FIRST_VENDOR);
  assert.equal(page.product.value, 'CODE-0');
  assert.equal(page.visibleRows().length, 1);
  page.selectVendor('');
  assert.equal(page.product.value, 'CODE-0');

  page.selectVendor(SECOND_VENDOR);

  assert.equal(page.product.value, '');
  assert.deepEqual(page.productCodes(), ['', 'CODE-2']);
  assert.deepEqual(page.visibleRows().map(row => row.dataset.productCode), ['CODE-2']);
  assert.equal(page.button.disabled, true);
});

test('a product without a vendor is available under All vendors and resets under a named vendor', () => {
  const page = fixture();
  page.selectProduct('CODE-2');
  assert.deepEqual(page.visibleRows().map(row => row.dataset.vendorId), ['']);

  page.selectVendor(FIRST_VENDOR);

  assert.equal(page.product.value, '');
  assert.deepEqual(page.productCodes(), ['', 'CODE-0']);
});

test('calculate requires one product and native-valid dates in inclusive order', async t => {
  t.mock.method(globalThis, 'fetch', () => { throw new Error('Invalid inputs must not submit'); });
  const page = fixture();
  await page.submit();
  assert.equal(page.button.disabled, true);
  page.selectProduct('CODE-0');
  assert.equal(page.button.disabled, false);

  page.setDate(page.start, '');
  assert.equal(page.button.disabled, true);
  assert.match(page.status.textContent, /Choose both start and end dates/);
  await page.submit();
  assert.equal(page.elements.gmvPeriodForm.validityReported, true);
  page.setDate(page.start, END);
  assert.equal(page.button.disabled, false, 'A single-day period is valid');

  page.setDate(page.end, START);
  assert.equal(page.button.disabled, true);
  assert.match(page.end.customValidity, /on or after/);
  assert.match(page.status.textContent, /End date must be on or after start date/);
  page.setDate(page.end, END);
  assert.equal(page.end.customValidity, '');
  assert.equal(page.button.disabled, false);
  page.start.nativeValid = false;
  page.start.fire('input');
  assert.equal(page.button.disabled, true);
});

test('only form submission sends an encoded GET request and preserves a precise amount string', async t => {
  const code = 'A +/0?&=#%';
  const amount = '12345678901234567890.12';
  const calls = [];
  t.mock.method(globalThis, 'fetch', async (url, options) => {
    calls.push({url, options});
    return response(payload(code, amount));
  });
  const page = fixture([FIRST_VENDOR], '', [code]);
  page.selectProduct(code);
  page.setDate(page.start, START);
  assert.equal(calls.length, 0);

  await page.submit();

  assert.equal(calls.length, 1);
  const request = new URL(calls[0].url, 'https://example.test');
  assert.equal(request.pathname, '/app/gmvForPeriod');
  assert.deepEqual(Object.fromEntries(request.searchParams), {productCode: code, startDate: START, endDate: END});
  assert.equal(calls[0].options.method, 'GET');
  assert.equal(calls[0].options.headers.Accept, 'application/json');
  assert.equal(page.result.textContent, amount);
  assert.equal(page.result.hidden, false);
  assert.equal(page.context.textContent, `Product 0 (${code}) · ${START} to ${END}`);
  assert.equal(page.status.textContent, '');
  assert.equal(page.button.disabled, false);
});

for (const amount of ['0.00', '-234.50', '1234.50']) {
  test(`displays the exact ${amount} response without reformatting`, async t => {
    t.mock.method(globalThis, 'fetch', async () => response(payload('CODE-0', amount)));
    const page = fixture();
    page.selectProduct('CODE-0');

    await page.submit();

    assert.equal(page.result.textContent, amount);
  });
}

test('a pending request shows loading status and blocks duplicate submission', async t => {
  const request = deferred();
  let calls = 0;
  t.mock.method(globalThis, 'fetch', () => { calls++; return request.promise; });
  const page = fixture();
  page.selectProduct('CODE-0');
  const calculation = page.submit();

  assert.equal(page.button.disabled, true);
  assert.match(page.status.textContent, /Calculating/);
  assert.equal(page.result.hidden, true);
  await page.submit();
  assert.equal(calls, 1);

  request.resolve(response());
  await calculation;
  assert.equal(page.button.disabled, false);
});

for (const change of ['product', 'vendor', 'start', 'end']) {
  test(`${change} changes clear a previous result without automatically calculating`, async t => {
    let calls = 0;
    t.mock.method(globalThis, 'fetch', async () => { calls++; return response(); });
    const page = fixture();
    page.selectProduct('CODE-0');
    await page.submit();
    assert.equal(page.result.hidden, false);

    if (change === 'product') page.selectProduct('CODE-1');
    if (change === 'vendor') page.selectVendor(FIRST_VENDOR);
    if (change === 'start') page.setDate(page.start, '2026-09-18');
    if (change === 'end') page.setDate(page.end, '2026-10-15', 'change');

    assert.equal(page.result.hidden, true);
    assert.equal(page.result.textContent, '');
    assert.equal(page.context.textContent, '');
    assert.equal(page.status.textContent, '');
    assert.equal(calls, 1);
  });
}

test('a stale success cannot show a result after changing the product', async t => {
  const request = deferred();
  t.mock.method(globalThis, 'fetch', () => request.promise);
  const page = fixture();
  page.selectProduct('CODE-0');
  const calculation = page.submit();

  page.selectProduct('CODE-1');
  request.resolve(response());
  await calculation;

  assert.equal(page.result.hidden, true);
  assert.equal(page.status.textContent, '');
  assert.equal(page.button.disabled, false);
});

test('a stale failure cannot replace loading status or enable a newer request button', async t => {
  const first = deferred();
  const second = deferred();
  let calls = 0;
  t.mock.method(globalThis, 'fetch', () => calls++ === 0 ? first.promise : second.promise);
  const page = fixture();
  page.selectProduct('CODE-0');
  const oldCalculation = page.submit();
  page.selectProduct('CODE-1');
  const newCalculation = page.submit();

  first.reject(new Error('Old network failure'));
  await oldCalculation;
  assert.match(page.status.textContent, /Calculating/);
  assert.equal(page.button.disabled, true);

  second.resolve(response(payload('CODE-1', '42.00')));
  await newCalculation;
  assert.equal(page.result.textContent, '42.00');
  assert.equal(page.button.disabled, false);
});

test('a stale response cannot overwrite a newer successful calculation for changed dates', async t => {
  const first = deferred();
  let calls = 0;
  t.mock.method(globalThis, 'fetch', () => calls++ === 0 ? first.promise
    : Promise.resolve(response(payload('CODE-0', '42.00', '2026-09-18'))));
  const page = fixture();
  page.selectProduct('CODE-0');
  const oldCalculation = page.submit();
  page.setDate(page.start, '2026-09-18');
  await page.submit();

  first.resolve(response());
  await oldCalculation;

  assert.equal(page.result.textContent, '42.00');
  assert.match(page.context.textContent, /2026-09-18/);
  assert.equal(page.button.disabled, false);
});

for (const [label, reply, message] of [
  ['invalid request', response(null, {ok: false, status: 400}), /valid product and date range.*400/],
  ['unknown product', response(null, {ok: false, status: 404}), /no longer available.*404/],
  ['server failure', response(null, {ok: false, status: 500}), /Try again.*500/],
  ['expired session', response(null, {ok: false, status: 401}), /session expired.*Sign in/],
  ['forbidden request', response(null, {ok: false, status: 403}), /not allowed.*403/],
  ['redirect to login', response(null, {redirected: true}), /session expired.*Sign in/],
  ['login HTML', response(null, {headers: {get: () => 'text/html'}}), /session may have expired.*Sign in/],
  ['number instead of amount string', response(payload('CODE-0', 1234.56)), /invalid GMV result/],
  ['different product in result', response(payload('CODE-1')), /invalid GMV result/],
  ['different period in result', response(payload('CODE-0', '1.00', '2026-09-18')), /invalid GMV result/]
]) {
  test(`${label} shows an inline error and leaves Calculate available for retry`, async t => {
    t.mock.method(globalThis, 'fetch', async () => reply);
    const page = fixture();
    page.selectProduct('CODE-0');

    await page.submit();

    assert.match(page.status.textContent, message);
    assert.equal(page.status.classes.has('is-error'), true);
    assert.equal(page.result.hidden, true);
    assert.equal(page.button.disabled, false);
  });
}

test('network failure is shown inline and the same button retries successfully', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    if (calls++ === 0) throw new Error('Network unavailable');
    return response();
  });
  const page = fixture();
  page.selectProduct('CODE-0');
  await page.submit();
  assert.match(page.status.textContent, /Network unavailable/);
  assert.equal(page.button.disabled, false);

  await page.submit();

  assert.equal(calls, 2);
  assert.equal(page.status.textContent, '');
  assert.equal(page.status.classes.has('is-error'), false);
  assert.equal(page.result.hidden, false);
});

test('changing a control clears a prior error', async t => {
  t.mock.method(globalThis, 'fetch', async () => response(null, {ok: false, status: 500}));
  const page = fixture();
  page.selectProduct('CODE-0');
  await page.submit();
  assert.equal(page.status.classes.has('is-error'), true);

  page.setDate(page.end, '2026-10-15');

  assert.equal(page.status.textContent, '');
  assert.equal(page.status.classes.has('is-error'), false);
});
