// Run with: node --test src/test/js/gmv.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const tableUrl = new URL('../../main/resources/static/js/table-common.js', import.meta.url).href;
const detailsSource = await readFile(new URL('../../main/resources/static/js/gmv-details.js', import.meta.url), 'utf8');
const detailsUrl = moduleUrl(detailsSource.replace(/(['"])\.\/table-common\.js\1/, JSON.stringify(tableUrl)));
const {monthPeriod} = await import(detailsUrl);
const source = await readFile(new URL('../../main/resources/static/js/gmv.js', import.meta.url), 'utf8');
const {initMonthlyGMV} = await import(moduleUrl(source.replace("'./gmv-details.js'", JSON.stringify(detailsUrl))));

const FIRST_VENDOR = '6a6fca21-f997-4589-ab95-4c75fe10c7b1';
const SECOND_VENDOR = '34aad1bc-a4f7-425a-8611-30a013788b4a';
const START = '2026-09-17';
const END = '2026-10-16';

class Element {
  constructor(tag = '') {
    this.tag = tag;
    this.listeners = new Map();
    this.dataset = {};
    this.classes = new Set();
    this.classList = {
      add: name => this.classes.add(name),
      toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name)
    };
    this.children = [];
    this.attributes = new Map();
    this.textContent = '';
    this.hidden = false;
    this.disabled = false;
    this.value = '';
  }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  fire(type) { return this.listeners.get(type)?.({preventDefault() {}}); }
  get cells() { return this.children; }
  appendChild(child) { this.children.push(child); child.parentNode = this; }
  replaceChildren(...children) { this.children = children; }
  querySelectorAll(tag) {
    return this.children.flatMap(child => [
      ...(child.tag === tag ? [child] : []), ...child.querySelectorAll(tag)
    ]);
  }
  remove() {
    if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
  }
  setAttribute(name, value) { this.attributes.set(name, value); }
  focus(options) { this.focusCount = (this.focusCount ?? 0) + 1; this.focusOptions = options; }
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
    'gmvPeriodContext', 'gmvPeriodStatus', 'gmvNoRows', 'gmvDetailsDialog', 'gmvDetailsTitle',
    'gmvDetailsContext', 'gmvDetailsStatus', 'gmvDetailsReference', 'gmvDetailsMismatch',
    'gmvDetailsBody', 'gmvDetailsTotal', 'gmvDetailsRetry', 'gmvDetailsClose',
    'gmvDetailsDownloadCsv', 'gmvDetailsTable'
  ].map(id => [id, new Element()]));
  const rows = vendorIds.map((vendorId, index) => ({
    dataset: {vendorId, productCode: codes[index] ?? `CODE-${index}`},
    hidden: true,
    cells: [`Product ${index}`, `PNK-${index}`, codes[index] ?? `CODE-${index}`, '123.40', '—']
  }));
  const headers = ['Product', 'PNK', 'Product code', '2026-01', '2026-02'];
  const wrap = {scrollLeft: 42, scrollTop: 90};
  const view = {scrollX: 10, scrollY: 20, scrollTo(x, y) { this.scrollX = x; this.scrollY = y; }};
  const monthlyTriggers = rows.map((row, index) => {
    const trigger = new Element();
    trigger.dataset = {productCode: row.dataset.productCode, productName: `Product ${index}`,
      month: '2026-01', gmv: '123.40'};
    return trigger;
  });
  elements.monthlyGmvTable = {
    closest: () => wrap,
    querySelectorAll(selector) {
      if (selector === 'tbody tr[data-vendor-id]') return rows;
      if (selector === '.gmv-details-trigger') return monthlyTriggers;
      throw new Error(`Unexpected selector: ${selector}`);
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
  elements.gmvDetailsDialog.open = false;
  elements.gmvDetailsDialog.showModal = () => {
    elements.gmvDetailsDialog.open = true;
    elements.gmvDetailsClose.focus();
  };
  elements.gmvDetailsDialog.close = () => {
    elements.gmvDetailsDialog.open = false;
    elements.gmvDetailsDialog.fire('close');
  };
  const detailHead = new Element('thead');
  const detailHeader = new Element('tr');
  for (const label of ['Order ID', 'Date', 'Status', 'Quantity used', 'Unit price incl. VAT', 'Seller voucher adjustment', 'GMV contribution']) {
    const cell = new Element('th');
    cell.textContent = label;
    detailHeader.appendChild(cell);
  }
  detailHead.appendChild(detailHeader);
  elements.gmvDetailsBody.tag = 'tbody';
  elements.gmvDetailsTable.appendChild(detailHead);
  elements.gmvDetailsTable.appendChild(elements.gmvDetailsBody);
  elements.gmvDetailsDownloadCsv.disabled = true;
  const documentRoot = {getElementById: id => elements[id] ?? null, defaultView: view,
    createElement: tag => new Element(tag), body: new Element('body')};
  initMonthlyGMV(documentRoot);
  return {
    rows, headers, elements, product, start, end, wrap, view, monthlyTriggers, documentRoot,
    details: Object.fromEntries(['Dialog', 'Title', 'Context', 'Status', 'Reference', 'Mismatch',
      'Body', 'Total', 'Retry', 'Close', 'DownloadCsv', 'Table'].map(name => [name.toLowerCase(), elements[`gmvDetails${name}`]])),
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
    submit: () => elements.gmvPeriodForm.fire('submit'),
    openMonthly: (index = 0) => monthlyTriggers[index].fire('click'),
    openCalculated: () => elements.gmvPeriodResult.fire('click'),
    closeDetails: () => elements.gmvDetailsClose.fire('click'),
    escapeDetails() {
      elements.gmvDetailsDialog.fire('cancel');
      elements.gmvDetailsDialog.close();
    }
  };
}

function payload(productCode = 'CODE-0', gmv = '1234.56', startDate = START, endDate = END) {
  return {productCode, startDate, endDate, gmv, contributions: []};
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

function contribution(overrides = {}) {
  return {orderId: '000123', orderDate: START, status: 'Finalized', quantity: 1,
    unitPrice: '1234.56', sellerVoucherAdjustment: '0.00', gmv: '1234.56', ...overrides};
}

function monthlyPayload(url, gmv = '123.40', contributions = [contribution({unitPrice: gmv, gmv})]) {
  return {...Object.fromEntries(new URL(url, 'https://example.test').searchParams), gmv, contributions};
}

for (const [month, lastDay] of [
  ['2024-02', '29'], ['2023-02', '28'], ['2000-02', '29'], ['1900-02', '28'],
  ['2026-01', '31'], ['2026-04', '30'], ['2026-12', '31']
]) {
  test(`monthly details use the entire inclusive month ${month}`, () => {
    assert.deepEqual(monthPeriod(month), {startDate: `${month}-01`, endDate: `${month}-${lastDay}`});
  });
}

test('a stored zero opens details while a missing value cannot trigger a request', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async url => {
    requests.push(url);
    return response(monthlyPayload(url, '0.00', [contribution({quantity: 0, unitPrice: '10.00', gmv: '0.00'})]));
  });
  const page = fixture();
  page.monthlyTriggers[0].dataset.gmv = '—';
  await page.openMonthly();
  assert.equal(requests.length, 0);
  assert.equal(page.details.dialog.open, false);

  page.monthlyTriggers[0].dataset.gmv = '0.00';
  await page.openMonthly();

  assert.equal(requests.length, 1);
  assert.equal(page.details.dialog.open, true);
  assert.equal(page.details.total.textContent, '0.00');
  assert.equal(page.details.reference.textContent, 'Stored monthly GMV: 0.00');
  assert.equal(page.details.mismatch.hidden, true);
  assert.deepEqual(Object.fromEntries(new URL(requests[0], 'https://example.test').searchParams),
    {productCode: 'CODE-0', startDate: '2026-01-01', endDate: '2026-01-31'});
});

for (const liveTotal of ['123.40', '100.00']) {
  test(`monthly details compare the clicked stored amount with the current ${liveTotal} total`, async t => {
    t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url, liveTotal)));
    const page = fixture();

    await page.openMonthly();

    assert.equal(page.details.total.textContent, liveTotal);
    assert.equal(page.details.reference.textContent, 'Stored monthly GMV: 123.40');
    assert.equal(page.details.mismatch.hidden, liveTotal === '123.40');
    if (liveTotal !== '123.40') {
      assert.match(page.details.mismatch.textContent, /Stored monthly GMV is 123\.40.*current calculation is 100\.00/);
    }
    assert.equal(page.rows[0].cells[3], '123.40', 'Stored table values are preserved');
  });
}

test('calculated GMV opens the cached contributing rows without another request or precision loss', async t => {
  let calls = 0;
  const row = contribution({orderId: '<order>000123', status: 'Storno deduction', quantity: -2,
    unitPrice: '12345678901234567890.12', gmv: '-24691357802469135780.24'});
  t.mock.method(globalThis, 'fetch', async () => {
    calls++;
    return response({...payload('CODE-0', row.gmv), contributions: [row]});
  });
  const page = fixture();
  page.selectProduct('CODE-0');
  await page.submit();

  page.openCalculated();

  assert.equal(calls, 1);
  assert.equal(page.details.dialog.open, true);
  assert.equal(page.details.total.textContent, row.gmv);
  assert.equal(page.details.reference.textContent, `Calculated GMV: ${row.gmv}`);
  assert.equal(page.result.attributes.get('aria-label'),
    `GMV ${row.gmv} for Product 0 (CODE-0), ${START} to ${END}. View contributing orders.`);
  assert.equal(page.details.mismatch.hidden, true);
  assert.deepEqual(page.details.body.children[0].children.map(cell => cell.textContent),
    [row.orderId, START, 'Storno deduction', '-2', row.unitPrice, row.sellerVoucherAdjustment, row.gmv]);
  assert.ok(page.details.body.children[0].children.slice(3).every(cell => cell.classes.has('numeric')));
  assert.equal(page.details.context.textContent, `Product 0 (CODE-0) · ${START} to ${END}`);
  assert.equal(page.details.retry.hidden, true);
  page.details.retry.fire('click');
  assert.equal(calls, 1, 'Calculated details do not offer a monthly retry request');
});

test('a cached zero-quantity storno renders its neutral status and exact zero total', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    calls++;
    return response({...payload('CODE-0', '0.00'), contributions: [
      contribution({status: 'Storno', quantity: 0, unitPrice: '10.00', gmv: '0.00'})
    ]});
  });
  const page = fixture();
  page.selectProduct('CODE-0');
  await page.submit();

  page.openCalculated();

  assert.equal(calls, 1);
  assert.equal(page.details.dialog.open, true);
  assert.deepEqual(page.details.body.children[0].children.slice(2).map(cell => cell.textContent),
    ['Storno', '0', '10.00', '0.00', '0.00']);
  assert.equal(page.result.textContent, '0.00');
  assert.equal(page.details.total.textContent, '0.00');
});

for (const change of ['product', 'vendor', 'start', 'end']) {
  test(`${change} changes invalidate cached calculated details and close their dialog`, async t => {
    let calls = 0;
    t.mock.method(globalThis, 'fetch', async () => { calls++; return response(); });
    const page = fixture();
    page.selectProduct('CODE-0');
    await page.submit();
    page.openCalculated();
    assert.equal(page.details.dialog.open, true);

    if (change === 'product') page.selectProduct('CODE-1');
    if (change === 'vendor') page.selectVendor(FIRST_VENDOR);
    if (change === 'start') page.setDate(page.start, '2026-09-18');
    if (change === 'end') page.setDate(page.end, '2026-10-15');
    page.openCalculated();

    assert.equal(page.details.dialog.open, false);
    assert.equal(page.details.downloadcsv.disabled, true);
    assert.equal(page.result.hidden, true);
    assert.equal(calls, 1);
  });
}

test('empty contribution details show the zero total and an explicit empty message', async t => {
  t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url, '0.00', [])));
  const page = fixture();

  await page.openMonthly();

  assert.equal(page.details.body.children.length, 0);
  assert.equal(page.details.total.textContent, '0.00');
  assert.match(page.details.status.textContent, /No contributing orders for this period/);
});

test('opening and closing monthly details preserves page state, scroll and trigger focus', async t => {
  t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url)));
  const page = fixture();
  page.selectVendor(FIRST_VENDOR);
  page.selectProduct('CODE-0');
  const original = {headers: [...page.headers], cells: page.rows.map(row => [...row.cells]),
    product: page.product.value, vendor: page.elements.gmvVendorFilterSelect.value,
    start: page.start.value, end: page.end.value, status: page.status.textContent};
  const scroll = {...page.wrap, x: page.view.scrollX, y: page.view.scrollY};

  await page.openMonthly();
  page.wrap.scrollLeft = 999;
  page.wrap.scrollTop = 777;
  page.view.scrollX = 111;
  page.view.scrollY = 222;
  page.closeDetails();

  assert.equal(page.details.dialog.open, false);
  assert.equal(page.monthlyTriggers[0].focusCount, 1);
  assert.deepEqual(page.monthlyTriggers[0].focusOptions, {preventScroll: true});
  assert.deepEqual(page.wrap, {scrollLeft: scroll.scrollLeft, scrollTop: scroll.scrollTop});
  assert.deepEqual([page.view.scrollX, page.view.scrollY], [scroll.x, scroll.y]);
  assert.deepEqual({headers: page.headers, cells: page.rows.map(row => row.cells),
    product: page.product.value, vendor: page.elements.gmvVendorFilterSelect.value,
    start: page.start.value, end: page.end.value, status: page.status.textContent}, original);
});

for (const close of ['Close', 'Escape']) {
  test(`${close} invalidates an in-flight monthly response`, async t => {
    const request = deferred();
    let requestedUrl;
    t.mock.method(globalThis, 'fetch', url => { requestedUrl = url; return request.promise; });
    const page = fixture();
    const loading = page.openMonthly();
    assert.match(page.details.status.textContent, /Loading/);
    if (close === 'Close') page.closeDetails();
    else page.escapeDetails();

    request.resolve(response(monthlyPayload(requestedUrl)));
    await loading;

    assert.equal(page.details.dialog.open, false);
    assert.equal(page.details.body.children.length, 0);
    assert.equal(page.details.total.textContent, '—');
    assert.equal(page.monthlyTriggers[0].focusCount, 1);
  });
}

test('a newer monthly cell replaces pending details and ignores the old failure', async t => {
  const oldRequest = deferred();
  let calls = 0;
  t.mock.method(globalThis, 'fetch', url => calls++ === 0 ? oldRequest.promise
    : Promise.resolve(response(monthlyPayload(url, '42.00'))));
  const page = fixture();
  const oldLoading = page.openMonthly(0);
  await page.openMonthly(1);

  oldRequest.reject(new Error('Old failure'));
  await oldLoading;

  assert.match(page.details.context.textContent, /Product 1 \(CODE-1\)/);
  assert.equal(page.details.total.textContent, '42.00');
  assert.equal(page.details.status.classes.has('is-error'), false);
  assert.equal(page.details.retry.hidden, true);
});

test('cached calculated details replace a pending monthly request without another fetch', async t => {
  const monthly = deferred();
  let calls = 0;
  let monthlyUrl;
  t.mock.method(globalThis, 'fetch', url => {
    if (calls++ === 0) return Promise.resolve(response({...payload(), contributions: [contribution()]}));
    monthlyUrl = url;
    return monthly.promise;
  });
  const page = fixture();
  page.selectProduct('CODE-0');
  await page.submit();
  const loading = page.openMonthly(1);

  page.openCalculated();
  monthly.resolve(response(monthlyPayload(monthlyUrl, '999.00')));
  await loading;

  assert.equal(calls, 2);
  assert.equal(page.details.total.textContent, '1234.56');
  assert.equal(page.details.reference.textContent, 'Calculated GMV: 1234.56');
  assert.match(page.details.context.textContent, /Product 0 \(CODE-0\)/);
  assert.equal(page.details.mismatch.hidden, true);
  page.closeDetails();
  assert.equal(page.result.focusCount, 1);
});

test('calculator control changes do not replace an independent monthly detail request', async t => {
  const request = deferred();
  let url;
  t.mock.method(globalThis, 'fetch', requested => { url = requested; return request.promise; });
  const page = fixture();
  const loading = page.openMonthly();

  page.selectProduct('CODE-1');
  page.setDate(page.start, '2026-09-18');
  request.resolve(response(monthlyPayload(url)));
  await loading;

  assert.equal(page.details.dialog.open, true);
  assert.match(page.details.context.textContent, /Product 0 \(CODE-0\).*2026-01-01 to 2026-01-31/);
  assert.equal(page.product.value, 'CODE-1');
});

test('monthly detail errors allow retrying the exact captured product and month', async t => {
  const requests = [];
  t.mock.method(globalThis, 'fetch', async url => {
    requests.push(url);
    return requests.length === 1 ? response(null, {ok: false, status: 500})
      : response(monthlyPayload(url));
  });
  const page = fixture();
  await page.openMonthly();
  assert.match(page.details.status.textContent, /Try again.*500/);
  assert.equal(page.details.retry.hidden, false);
  assert.equal(page.details.status.classes.has('is-error'), true);

  await page.details.retry.fire('click');

  assert.equal(requests.length, 2);
  assert.equal(requests[0], requests[1]);
  assert.equal(page.details.total.textContent, '123.40');
  assert.equal(page.details.retry.hidden, true);
  assert.equal(page.details.status.classes.has('is-error'), false);
});

for (const [label, rows] of [
  ['missing contributions', undefined], ['non-array contributions', {}],
  ['numeric order ID', [contribution({orderId: 123})]],
  ['invalid date', [contribution({orderDate: 'yesterday'})]],
  ['unknown status', [contribution({status: 'Unknown'})]],
  ['fractional quantity', [contribution({quantity: 1.5})]],
  ['unsafe quantity', [contribution({quantity: Number.MAX_SAFE_INTEGER + 1})]],
  ['numeric unit price', [contribution({unitPrice: 1.23})]],
  ['numeric voucher adjustment', [contribution({sellerVoucherAdjustment: 1.23})]],
  ['missing voucher adjustment', [contribution({sellerVoucherAdjustment: undefined})]],
  ['numeric row GMV', [contribution({gmv: 1.23})]]
]) {
  test(`${label} is rejected before rendering or caching a result`, async t => {
    t.mock.method(globalThis, 'fetch', async () => response({...payload(), contributions: rows}));
    const page = fixture();
    page.selectProduct('CODE-0');

    await page.submit();
    page.openCalculated();

    assert.match(page.status.textContent, /invalid GMV result/);
    assert.equal(page.result.hidden, true);
    assert.equal(page.details.dialog.open, false);
  });
}

const CSV_HEADER = 'Order ID,Date,Status,Quantity used,Unit price incl. VAT,Seller voucher adjustment,GMV contribution';

function captureDownloads(t, page) {
  const oldDocument = globalThis.document;
  globalThis.document = page.documentRoot;
  t.after(() => {
    if (oldDocument === undefined) delete globalThis.document;
    else globalThis.document = oldDocument;
  });
  const blobs = [];
  const downloads = [];
  const revoked = [];
  const events = [];
  t.mock.method(URL, 'createObjectURL', blob => {
    const url = `blob:gmv-csv-${blobs.length}`;
    blobs.push(blob);
    events.push('create');
    return url;
  });
  t.mock.method(URL, 'revokeObjectURL', url => { revoked.push(url); events.push('revoke'); });
  t.mock.method(page.documentRoot, 'createElement', tag => {
    const element = new Element(tag);
    if (tag === 'a') {
      element.click = () => {
        events.push('click');
        downloads.push({fileName: element.download, href: element.href,
          attached: page.documentRoot.body.children.includes(element)});
      };
    }
    return element;
  });
  return {blobs, downloads, revoked, events,
    click: () => page.details.downloadcsv.fire('click'),
    text: async (index = 0) => Buffer.from(await blobs[index].arrayBuffer()).toString('utf8')};
}

test('CSV exports every rendered contribution with BOM, escaping, Unicode and exact signed money', async t => {
  const code = 'A/B:*?"<>| Δ';
  const rows = [
    contribution({orderId: '0001, "Șurub"', quantity: 2, unitPrice: '12345678901234567890.12',
      gmv: '24691357802469135780.24'}),
    contribution({orderId: 'RMA-2\nline', status: 'Storno deduction', quantity: -2, unitPrice: '12.35', gmv: '-24.70'}),
    contribution({orderId: '0003', status: 'Storno', quantity: 0, unitPrice: '0.00', gmv: '0.00'}),
    ...Array.from({length: 27}, (_, index) => contribution({orderId: String(index + 4).padStart(4, '0'),
      unitPrice: '1.00', gmv: '1.00'}))
  ];
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async () => {
    calls++;
    return response({...payload(code, '24691357802469135782.54'), contributions: rows});
  });
  const page = fixture([FIRST_VENDOR], '', [code]);
  const captured = captureDownloads(t, page);
  page.selectProduct(code);
  await page.submit();
  page.openCalculated();
  assert.equal(page.details.downloadcsv.disabled, false);

  captured.click();

  const bytes = new Uint8Array(await captured.blobs[0].arrayBuffer());
  const csv = await captured.text();
  assert.deepEqual([...bytes.slice(0, 3)], [0xef, 0xbb, 0xbf]);
  assert.equal(captured.blobs[0].type, 'text/csv;charset=utf-8;');
  assert.deepEqual(csv.split('\r\n'), [
    `\uFEFF${CSV_HEADER}`,
    `"0001, ""Șurub""",${START},Finalized,2,12345678901234567890.12,0.00,24691357802469135780.24`,
    `RMA-2 line,${START},Storno deduction,-2,12.35,0.00,-24.70`,
    `0003,${START},Storno,0,0.00,0.00,0.00`,
    ...Array.from({length: 27}, (_, index) => `${String(index + 4).padStart(4, '0')},${START},Finalized,1,1.00,0.00,1.00`)
  ]);
  assert.equal(csv.replaceAll('\r\n', '').includes('\n'), false);
  assert.equal(calls, 1, 'CSV uses cached rendered details without another request');
  assert.deepEqual(captured.downloads, [{
    fileName: `gmv-details-A_B_-Δ-${START}-to-${END}.csv`, href: 'blob:gmv-csv-0', attached: true
  }]);
  assert.deepEqual(captured.events, ['create', 'click', 'revoke']);
  assert.deepEqual(captured.revoked, ['blob:gmv-csv-0']);
  assert.equal(page.documentRoot.body.children.length, 0, 'Temporary anchor is removed');
});

test('successful empty details download a header-only CSV with the captured whole-month filename', async t => {
  let calls = 0;
  t.mock.method(globalThis, 'fetch', async url => {
    calls++;
    return response(monthlyPayload(url, '0.00', []));
  });
  const page = fixture();
  const captured = captureDownloads(t, page);
  await page.openMonthly();
  assert.equal(page.details.downloadcsv.disabled, false);

  captured.click();

  assert.equal(await captured.text(), `\uFEFF${CSV_HEADER}`);
  assert.equal(captured.downloads[0].fileName, 'gmv-details-CODE-0-2026-01-01-to-2026-01-31.csv');
  assert.equal(calls, 1);
});

test('mismatched monthly CSV contains current contribution rows and excludes summary metadata', async t => {
  t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url, '-24.70', [
    contribution({status: 'Storno deduction', quantity: -2, unitPrice: '12.35', gmv: '-24.70'})
  ])));
  const page = fixture();
  const captured = captureDownloads(t, page);
  await page.openMonthly();
  assert.equal(page.details.mismatch.hidden, false);
  const context = page.details.context.textContent;
  const status = page.details.status.textContent;

  captured.click();

  assert.equal(await captured.text(), `\uFEFF${CSV_HEADER}\r\n000123,${START},Storno deduction,-2,12.35,0.00,-24.70`);
  assert.equal(page.details.reference.textContent, 'Stored monthly GMV: 123.40');
  assert.equal(page.details.total.textContent, '-24.70');
  assert.equal(page.rows[0].cells[3], '123.40');
  assert.equal(page.details.context.textContent, context);
  assert.equal(page.details.status.textContent, status);
  assert.equal(page.details.dialog.open, true);
});

test('CSV is unavailable initially, during loading, after failure and retry loading, then enables on success', async t => {
  const initial = deferred();
  const retry = deferred();
  const requests = [];
  t.mock.method(globalThis, 'fetch', url => {
    requests.push(url);
    return requests.length === 1 ? initial.promise : retry.promise;
  });
  const page = fixture();
  const captured = captureDownloads(t, page);
  assert.equal(page.details.downloadcsv.disabled, true);
  captured.click();
  const loading = page.openMonthly();
  assert.equal(page.details.downloadcsv.disabled, true);
  captured.click();
  initial.resolve(response(null, {ok: false, status: 500}));
  await loading;
  assert.equal(page.details.downloadcsv.disabled, true);
  captured.click();
  const retrying = page.details.retry.fire('click');
  assert.equal(page.details.downloadcsv.disabled, true);
  captured.click();
  assert.equal(captured.downloads.length, 0);

  retry.resolve(response(monthlyPayload(requests[1])));
  await retrying;
  assert.equal(page.details.downloadcsv.disabled, false);
  captured.click();
  assert.equal(captured.downloads.length, 1);
  assert.equal(requests.length, 2);
  assert.equal(requests[0], requests[1]);
});

for (const close of ['Close', 'Escape']) {
  test(`${close} disables CSV and a late monthly response cannot re-enable a closed dialog`, async t => {
    const request = deferred();
    let url;
    t.mock.method(globalThis, 'fetch', value => { url = value; return request.promise; });
    const page = fixture();
    const captured = captureDownloads(t, page);
    const loading = page.openMonthly();
    if (close === 'Close') page.closeDetails();
    else page.escapeDetails();
    request.resolve(response(monthlyPayload(url)));
    await loading;

    assert.equal(page.details.downloadcsv.disabled, true);
    captured.click();
    assert.equal(captured.downloads.length, 0);
  });
}

test('replacing loaded details disables the old CSV until the new response renders', async t => {
  const replacement = deferred();
  let calls = 0;
  let replacementUrl;
  t.mock.method(globalThis, 'fetch', url => {
    if (calls++ === 0) return Promise.resolve(response(monthlyPayload(url)));
    replacementUrl = url;
    return replacement.promise;
  });
  const page = fixture();
  const captured = captureDownloads(t, page);
  await page.openMonthly(0);
  captured.click();
  assert.equal(captured.downloads.length, 1);
  const loading = page.openMonthly(1);
  assert.equal(page.details.downloadcsv.disabled, true);
  captured.click();
  assert.equal(captured.downloads.length, 1);

  replacement.resolve(response(monthlyPayload(replacementUrl, '42.00')));
  await loading;
  captured.click();

  assert.deepEqual(captured.downloads.map(download => download.fileName), [
    'gmv-details-CODE-0-2026-01-01-to-2026-01-31.csv',
    'gmv-details-CODE-1-2026-01-01-to-2026-01-31.csv'
  ]);
  assert.match(await captured.text(1), /42\.00,0\.00,42\.00$/);
  assert.equal(calls, 2);
});

test('cached calculated details replace pending monthly export context and ignore its stale response', async t => {
  const monthly = deferred();
  let calls = 0;
  let monthlyUrl;
  t.mock.method(globalThis, 'fetch', url => {
    if (calls++ === 0) return Promise.resolve(response({...payload(), contributions: [contribution()]}));
    monthlyUrl = url;
    return monthly.promise;
  });
  const page = fixture();
  const captured = captureDownloads(t, page);
  page.selectProduct('CODE-0');
  await page.submit();
  const loading = page.openMonthly(1);
  assert.equal(page.details.downloadcsv.disabled, true);
  page.openCalculated();
  assert.equal(page.details.downloadcsv.disabled, false);
  monthly.resolve(response(monthlyPayload(monthlyUrl, '999.00')));
  await loading;

  captured.click();

  assert.equal(captured.downloads[0].fileName, `gmv-details-CODE-0-${START}-to-${END}.csv`);
  assert.equal(await captured.text(), `\uFEFF${CSV_HEADER}\r\n000123,${START},Finalized,1,1234.56,0.00,1234.56`);
  assert.equal(calls, 2);
});

test('a late failure cannot disable a newer successful CSV or change its filename', async t => {
  const oldRequest = deferred();
  let calls = 0;
  t.mock.method(globalThis, 'fetch', url => calls++ === 0 ? oldRequest.promise
    : Promise.resolve(response(monthlyPayload(url))));
  const page = fixture();
  const captured = captureDownloads(t, page);
  const loading = page.openMonthly(0);
  await page.openMonthly(1);

  oldRequest.reject(new Error('Old failure'));
  await loading;
  assert.equal(page.details.downloadcsv.disabled, false);
  captured.click();

  assert.equal(captured.downloads[0].fileName, 'gmv-details-CODE-1-2026-01-01-to-2026-01-31.csv');
});

for (const close of ['Close', 'Escape']) {
  test(`${close} disables a previously available CSV and prevents exporting hidden old rows`, async t => {
    t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url)));
    const page = fixture();
    const captured = captureDownloads(t, page);
    await page.openMonthly();
    assert.equal(page.details.downloadcsv.disabled, false);
    captured.click();
    if (close === 'Close') page.closeDetails();
    else {
      page.details.dialog.fire('cancel');
      assert.equal(page.details.downloadcsv.disabled, true, 'Escape invalidates before the queued close event');
      page.details.dialog.close();
    }

    assert.equal(page.details.downloadcsv.disabled, true);
    captured.click();
    assert.equal(captured.downloads.length, 1);
  });
}

test('a queued close event from the previous dialog cannot clear a reopened CSV context', async t => {
  t.mock.method(globalThis, 'fetch', async url => response(monthlyPayload(url)));
  const page = fixture();
  const captured = captureDownloads(t, page);
  await page.openMonthly(0);
  page.details.dialog.close = () => { page.details.dialog.open = false; };
  page.closeDetails();
  assert.equal(page.details.downloadcsv.disabled, true);
  await page.openMonthly(1);

  page.details.dialog.fire('close');

  assert.equal(page.details.dialog.open, true);
  assert.equal(page.details.downloadcsv.disabled, false);
  captured.click();
  assert.equal(captured.downloads[0].fileName, 'gmv-details-CODE-1-2026-01-01-to-2026-01-31.csv');
});

for (const [quantity, voucher, amount] of [[4, '-40.00', '360.00'], [-1, '10.00', '-90.00']]) {
  test(`voucher adjustment ${voucher} is rendered and exported with the cached result`, async t => {
    let calls = 0;
    const row = contribution({quantity, unitPrice: '100.00', sellerVoucherAdjustment: voucher, gmv: amount});
    t.mock.method(globalThis, 'fetch', async () => { calls++; return response({...payload('CODE-0', amount), contributions: [row]}); });
    const page = fixture();
    const captured = captureDownloads(t, page);
    page.selectProduct('CODE-0');
    await page.submit();
    page.openCalculated();
    assert.deepEqual(page.details.body.children[0].cells.map(cell => cell.textContent),
      [row.orderId, row.orderDate, row.status, String(quantity), '100.00', voucher, amount]);
    captured.click();
    assert.equal(await captured.text(), `\uFEFF${CSV_HEADER}\r\n000123,${START},Finalized,${quantity},100.00,${voucher},${amount}`);
    assert.equal(calls, 1);
  });
}
