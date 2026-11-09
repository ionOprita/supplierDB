// Run with: node --test src/test/js/product-performance.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';

// Load the browser module without introducing a frontend package/build dependency.
const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const tableCommon = moduleUrl((await readFile(new URL('../../main/resources/static/js/table-common.js', import.meta.url), 'utf8'))
  .replace('"./common.js"', JSON.stringify(common)));
const infoTooltip = moduleUrl(await readFile(new URL('../../main/resources/static/js/info-tooltip.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/product-performance.js', import.meta.url), 'utf8');
const {formatPerformanceValue: format, formatPerformancePeriod: formatPeriod,
  visiblePerformanceRows, normalizePerformancePeriod, initProductPerformance} = await import(moduleUrl(source
    .replace("'./common.js'", JSON.stringify(common))
    .replace("'./table-common.js'", JSON.stringify(tableCommon))
    .replace("'./info-tooltip.js'", JSON.stringify(infoTooltip))));

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

test('shows ambiguous offer cells without treating them as numbers', async t => {
  const page = await openPage(t);
  const data = report(5);
  data.groups[0].columns.push({key: 'stock', label: 'Stock', type: 'integer'},
    {key: 'salesPrice', label: 'Sales Price (incl. VAT)', type: 'decimal'});
  data.rows = [
    performanceRow('2026-09-07', {stock: 0, salesPrice: 19.875, auto_clicks: 5, total_clicks: 5}),
    performanceRow('2026-09-14', {stock: '???', salesPrice: '???', auto_clicks: 6, total_clicks: 6}),
    performanceRow('2026-09-21', {stock: null, salesPrice: null, auto_clicks: 7, total_clicks: 7})
  ];
  await page.respond(0, data);
  const rows = page.byId('Body').children;
  assert.deepEqual(rows.map(row => row.children[1].textContent), ['0', '???', '—']);
  assert.deepEqual(rows.map(row => row.children[2].textContent), ['19.88', '???', '—']);
  assert.equal(rows[1].children[1].getAttribute('aria-label'),
    'Multiple matching offers in the latest daily snapshot');
  assert.equal(rows[1].children[2].getAttribute('aria-label'),
    'Multiple matching offers in a daily snapshot for this period');
});

test('period-average Sales Price ambiguity can describe an earlier daily snapshot while Stock still refers to the latest', async t => {
  const page = await openPage(t, '30days');
  const data = report(5, '2026-09-01');
  data.groups[0].columns.push({key: 'stock', label: 'Stock', type: 'integer'},
    {key: 'salesPrice', label: 'Sales Price (incl. VAT)', type: 'decimal'});
  data.rows = [
    performanceRow('2026-09-01', {stock: 7, salesPrice: '???', auto_clicks: 5, total_clicks: 5}),
    performanceRow('2026-10-01', {stock: '???', salesPrice: '???', auto_clicks: 5, total_clicks: 5})
  ];
  await page.respond(0, data);
  const rows = page.byId('Body').children;
  assert.equal(rows[0].children[1].textContent, '7');
  assert.equal(rows[0].children[2].textContent, '???');
  assert.equal(rows[0].children[2].title, 'Multiple matching offers in a daily snapshot for this period');
  assert.equal(rows[0].children[2].getAttribute('aria-label'), rows[0].children[2].title);
  assert.equal(rows[1].children[1].title, 'Multiple matching offers in the latest daily snapshot');
  assert.equal(rows[1].children[2].title, 'Multiple matching offers in a daily snapshot for this period');
});

test('VAT-inclusive Sales Price and its period mean render and export without Average price or shifted frozen cells', async t => {
  const page = await openPage(t);
  const pricedReport = (date, salesPrice) => {
    const data = report(5, date);
    data.groups[0].columns.push(
      {key: 'stock', label: 'Stock', type: 'integer'},
      {key: 'orders', label: 'Orders', type: 'integer'},
      {key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer'},
      {key: 'clicks', label: 'Clicks', type: 'integer'},
      {key: 'salesPrice', label: 'Sales Price (incl. VAT)', type: 'decimal'},
      {key: 'conversion', label: 'Conv.', type: 'percent'},
      {key: 'performance', label: 'Perform.', type: 'text'},
      {key: 'reviews', label: '# rev.', type: 'integer'},
      {key: 'rating', label: 'rating', type: 'decimal'});
    data.rows[0].visitsStatus = 'available';
    Object.assign(data.rows[0].values, {stock: 7, orders: 3, totalSales: 600, gmv: 726, clicks: 20,
      salesPrice, conversion: 0.25, performance: 'HOT', reviews: 4, rating: 4.5,
      averagePrice: 7777.77});
    return data;
  };
  await page.respond(0, pricedReport('2026-09-07', 199.99));
  const headers = page.byId('Head').children[1].children;
  assert.deepEqual(headers.slice(0, 9).map(cell => cell.textContent),
    ['Stock', 'Orders', 'Total Sales (excl. VAT)', 'Clicks', 'Sales Price (incl. VAT)', 'Conv.', 'Perform.', '# rev.', 'rating']);
  assert.equal(page.byId('Head').children[0].children[1].colSpan, 9);
  assert.equal(page.byId('Table').querySelectorAll('colgroup')[0].span, 10);
  assert.equal(headers[7].dataset.frozenIndex, 8);
  assert.equal(headers[8].dataset.frozenIndex, 9);
  const row = page.byId('Body').children[0];
  assert.equal(row.cells[5].textContent, '199.99');
  assert.equal(row.cells[8].dataset.frozenIndex, 8);
  assert.equal(row.cells[9].dataset.frozenIndex, 9);
  assert.equal(row.cells.length, 12);
  assert.doesNotMatch(page.byId('Head').textContent, /Average price/);
  assert.doesNotMatch(row.textContent, /7,777/);
  assert.doesNotMatch(row.textContent, /726/, 'Legacy gross GMV must not replace the net total');
  page.byId('DownloadCsv').click();
  const header = 'Stock,Orders,Total Sales (excl. VAT),Clicks,Sales Price (incl. VAT),Conv.,Perform.,# rev.,rating,Auto - Clicks,Total - Clicks';
  assert.equal(withoutMetadata(await page.csv()), `\uFEFFPeriod,${header}\r\n07 Sept 2026 - 20 Sept 2026,7,3,600,20,199.99,25.00%,HOT,4,4.50,5,5`);

  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  assert.equal(page.requests[1].url.searchParams.get('period'), '30days');
  await page.respond(1, pricedReport('2026-09-01', 193.32));
  assert.equal(page.byId('Body').children[0].cells[5].textContent, '193.32');
  page.byId('DownloadCsv').click();
  assert.equal(withoutMetadata(await page.csv()), `\uFEFFPeriod,${header}\r\n01 Sept 2026 - 14 Sept 2026,7,3,600,20,193.32,25.00%,HOT,4,4.50,5,5`);
});

test('financial columns expose tax explanations accessibly and export unchanged source amounts with VAT labels', async t => {
  const page = await openPage(t);
  const data = report(5);
  const adGroups = [['total', 'Total'], ['auto', 'Auto'], ['broad', 'Broad'], ['exact', 'Exact'], ['product', 'Product']];
  const descriptions = {
    spend: 'Consumption of Ads credit excluding VAT. The API does not explicitly identify the tax basis.',
    sales: 'Advertising sales reported by eMAG excluding VAT, without additional conversion.',
    cpc: 'Spend excluding VAT divided by Ads clicks.',
    acos: 'Spend / Sales × 100. Both amounts exclude VAT.',
    roas: 'Sales / Spend. Both amounts exclude VAT.'
  };
  const metricColumns = [
    ['spend', 'Spend (excl. VAT)', 'integer'],
    ['sales', 'Sales (excl. VAT)', 'integer'],
    ['cpc', 'CPC (excl. VAT)', 'decimal'],
    ['acos', 'ACOS', 'percent'],
    ['roas', 'ROAS', 'decimal']
  ];
  data.groups = [{key: 'overview', label: 'Product metrics', columns: [
    {key: 'period', label: 'Period', type: 'date'},
    {key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer',
      description: 'Revenue excluding VAT calculated from product orders.'},
    {key: 'salesPrice', label: 'Sales Price (incl. VAT)', type: 'decimal',
      description: 'Mean of daily offer prices, including VAT.'}
  ]}, ...adGroups.map(([key, label]) => ({key, label, columns: metricColumns.map(([metric, label, type]) => ({
    key: `${key}_${metric}`, label, type, description: descriptions[metric]
  }))}))];
  const values = {totalSales: 190, gmv: 229.9, salesPrice: 199.99};
  for (const [group] of adGroups) {
    Object.assign(values, {[`${group}_spend`]: 21, [`${group}_sales`]: 121,
      [`${group}_cpc`]: 1.25, [`${group}_acos`]: 21 / 121, [`${group}_roas`]: 121 / 21});
  }
  data.rows = [performanceRow('2026-09-07', values)];
  await page.respond(0, data);
  const headers = page.byId('Head').children[1].children;
  const cells = page.byId('Body').children[0].cells;
  assert.deepEqual(cells.slice(1, 3).map(cell => cell.textContent), ['190', '199.99']);
  assert.equal(headers[0].title, data.groups[0].columns[1].description);
  assert.match(headers[0].getAttribute('aria-label'), /^Total Sales \(excl. VAT\)\./);
  assert.match(cells[2].getAttribute('aria-label'), /^199\.99\. Sales Price \(incl. VAT\)\./);
  for (let groupIndex = 0; groupIndex < adGroups.length; groupIndex++) {
    const firstHeader = 2 + groupIndex * metricColumns.length;
    const firstCell = firstHeader + 1;
    assert.deepEqual(cells.slice(firstCell, firstCell + 5).map(cell => cell.textContent),
      ['21', '121', '1.25', '17.36%', '5.76']);
    metricColumns.forEach(([metric, label], metricIndex) => {
      const header = headers[firstHeader + metricIndex];
      const cell = cells[firstCell + metricIndex];
      assert.equal(header.textContent, label);
      assert.equal(header.title, descriptions[metric]);
      assert.equal(header.getAttribute('aria-label'), `${label}. ${descriptions[metric]}`);
      assert.equal(cell.title, descriptions[metric]);
      assert.equal(cell.getAttribute('aria-label'), `${cell.textContent}. ${label}. ${descriptions[metric]}`);
    });
  }
  page.byId('DownloadCsv').click();
  const csv = await page.csv();
  const [csvHeader, csvRow] = withoutMetadata(csv).split('\r\n');
  assert.equal(csvHeader, '\uFEFFPeriod,Total Sales (excl. VAT),Sales Price (incl. VAT),'
    + adGroups.flatMap(([, group]) => metricColumns.map(([, label]) => `${group} - ${label}`)).join(','));
  assert.equal(csvRow, '07 Sept 2026 - 20 Sept 2026,190,199.99,'
    + Array(5).fill('21,121,1.25,17.36%,5.76').join(','));
  assert.doesNotMatch(csv, /GMV|229\.9/, 'Legacy gross GMV remains out of the displayed and exported columns');
  assert.equal(csv.split('\r\n')[0].split(',').length, cells.length + 13,
    'Descriptions must not add columns beyond the visits and performance metadata');
});

test('renders and exports all TACOS groups and product shares without clamping or replacing unknown with zero', async t => {
  const page = await openPage(t);
  const data = report(5);
  const shareDescription = 'Data for the selected account. The Clicks window is approximate; values can exceed 100%.';
  data.groups = [{key: 'overview', label: 'Product metrics', columns: [
    {key: 'period', label: 'Period', type: 'date'},
    {key: 'orders', label: 'Orders', type: 'integer'},
    {key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer'}
  ]}, {key: 'total', label: 'Total', columns: [
    {key: 'total_tacos', label: 'TACOS', type: 'percent'},
    {key: 'total_clicks_share', label: '% Clicks Ads of Total', type: 'percent', description: shareDescription},
    {key: 'total_sales_share', label: '% Sales Ads of Total', type: 'percent'}
  ]}, ...['auto', 'broad', 'exact', 'product'].map(key => ({key, label: key, columns: [
    {key: `${key}_tacos`, label: 'TACOS', type: 'percent'}
  ]}))];
  data.rows = [performanceRow('2026-09-07', {
    orders: 8, totalSales: 1000, total_tacos: 0.05, total_clicks_share: 0.5, total_sales_share: 0.3,
    auto_tacos: 0.01, broad_tacos: 0.02, exact_tacos: 0.005, product_tacos: 0.015
  }), performanceRow('2026-09-21', {
    orders: 2, totalSales: 100, total_tacos: null, total_clicks_share: null, total_sales_share: 1.5,
    auto_tacos: 0, broad_tacos: null, exact_tacos: -0.01, product_tacos: 1.2
  })];
  await page.respond(0, data);
  const rows = page.byId('Body').children;
  assert.deepEqual(rows[0].cells.slice(3).map(cell => cell.textContent),
    ['5.00%', '50.00%', '30.00%', '1.00%', '2.00%', '0.50%', '1.50%']);
  assert.deepEqual(rows[1].cells.slice(3).map(cell => cell.textContent),
    ['—', '—', '150.00%', '0.00%', '—', '-1.00%', '120.00%']);
  assert.equal(rows[0].cells[4].title, shareDescription);
  page.byId('DownloadCsv').click();
  const csvRows = withoutMetadata(await page.csv()).split('\r\n');
  assert.match(csvRows[0], /Total - TACOS,Total - % Clicks Ads of Total,Total - % Sales Ads of Total/);
  assert.equal(csvRows[1], '07 Sept 2026 - 20 Sept 2026,8,"1,000",5.00%,50.00%,30.00%,1.00%,2.00%,0.50%,1.50%');
  assert.equal(csvRows[2], '21 Sept 2026 - 04 Oct 2026,2,100,—,—,150.00%,0.00%,—,-1.00%,120.00%');
});

test('renders net Total Sales as whole numbers and refreshes it for the selected period and product', async t => {
  const page = await openPage(t);
  const withNetSales = (amounts, dates, errors = []) => {
    const data = report(5, dates[0], errors);
    data.groups[0].columns.push({key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer'});
    data.rows = amounts.map((totalSales, index) => performanceRow(dates[index], {
      totalSales, auto_clicks: 5, total_clicks: errors.length ? null : 5
    }));
    return data;
  };
  const netSalesCells = () => page.byId('Body').children.map(row => row.children[1].textContent);
  await page.respond(0, withNetSales([1234.56, 0, -42.75], ['2026-09-07', '2026-09-14', '2026-09-21'],
    [classificationError]));
  assert.deepEqual(netSalesCells(), ['1,235', '0', '-43']);
  assert.equal(page.byId('Body').children[0].children[3].textContent, '—');

  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  assert.equal(page.requests[1].url.searchParams.get('period'), '30days');
  assert.equal(page.byId('Body').textContent, '');
  await page.respond(1, withNetSales([9876.54], ['2026-09-01']));
  assert.deepEqual(netSalesCells(), ['9,877']);

  page.byId('ProductSelect').value = 'OTHER';
  page.byId('ProductSelect').dispatch('change');
  assert.equal(page.requests[2].url.searchParams.get('productCode'), 'OTHER');
  assert.equal(page.requests[2].url.searchParams.get('period'), '30days');
  assert.equal(page.byId('Body').textContent, '');
  await page.respond(2, withNetSales([0], ['2026-09-01']));
  assert.deepEqual(netSalesCells(), ['0']);
});

test('renders and exports Orders directly after Stock with integer units and aligned frozen columns', async t => {
  const page = await openPage(t);
  const data = report(5);
  data.groups[0].columns.push(
    {key: 'stock', label: 'Stock', type: 'integer'},
    {key: 'orders', label: 'Orders', type: 'integer'},
    {key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer'});
  data.rows = [
    performanceRow('2026-09-07', {stock: 10, orders: 0, totalSales: 9.25, auto_clicks: 5, total_clicks: 5}),
    performanceRow('2026-09-14', {stock: 8, orders: 1234, totalSales: 10, auto_clicks: 5, total_clicks: 5}),
    performanceRow('2026-09-21', {stock: 7, orders: -2, totalSales: null, auto_clicks: 5, total_clicks: 5})
  ];
  await page.respond(0, data);
  const headers = page.byId('Head').children[1].children;
  assert.deepEqual(headers.slice(0, 3).map(cell => cell.textContent), ['Stock', 'Orders', 'Total Sales (excl. VAT)']);
  assert.equal(page.byId('Head').children[0].children[1].colSpan, 3);
  assert.equal(headers[1].dataset.frozenIndex, 2);
  const rows = page.byId('Body').children;
  assert.deepEqual(rows.map(row => row.cells[2].textContent), ['0', '1,234', '-2']);
  assert.ok(rows.every(row => row.cells[2].dataset.frozenIndex === 2));
  assert.ok(rows.every(row => row.cells[2].dataset.type === 'integer'));
  page.byId('DownloadCsv').click();
  assert.equal(withoutMetadata(await page.csv()), '\uFEFFPeriod,Stock,Orders,Total Sales (excl. VAT),Auto - Clicks,Total - Clicks\r\n'
    + '07 Sept 2026 - 20 Sept 2026,10,0,9,5,5\r\n'
    + '14 Sept 2026 - 27 Sept 2026,8,"1,234",10,5,5\r\n'
    + '21 Sept 2026 - 04 Oct 2026,7,-2,—,5,5');
});

test('renders ISO calendar dates without time-zone shifts or invalid date rollover', () => {
  assert.equal(format('2026-05-01', 'date'), '01 May 26');
  assert.equal(format('2026-09-04', 'date'), '04 Sep 26');
  for (const value of ['2026-02-30', '2026-13-01', '2026-05-01T00:00:00Z', 'bad']) {
    assert.equal(format(value, 'date'), '—');
  }
});

test('formats explicit date ranges across months, years and leap days with provisional status', () => {
  assert.equal(formatPeriod({startDate: '2026-12-25', endDate: '2027-01-07', provisional: false}),
    '25 Dec 2026 - 07 Jan 2027');
  assert.equal(formatPeriod({startDate: '2024-02-16', endDate: '2024-02-29', provisional: true}),
    '16 Feb 2024 - 29 Feb 2024 (provisional)');
  assert.equal(formatPeriod({startDate: '2026-02-30', endDate: '2026-03-14'}), '—');
  assert.equal(formatPeriod({startDate: '2026-03-14', endDate: '2026-03-01'}), '—');
  assert.equal(formatPeriod(null), '—');
  assert.equal(normalizePerformancePeriod('month'), '30days');
  assert.equal(normalizePerformancePeriod('week'), '14days');
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
    const classes = new Set();
    this.classList = {add(...names) { names.forEach(name => classes.add(name)); }, contains(name) { return classes.has(name); }};
    this.style = {setProperty() {}};
    this.hidden = false;
    this.disabled = false;
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

  get cells() {
    return this.children.filter(child => child.tag === 'td' || child.tag === 'th');
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

  contains(node) { return this === node || this.children.some(child => child.contains(node)); }

  removeEventListener(type, listener) {
    this.listeners.set(type, (this.listeners.get(type) || []).filter(item => item !== listener));
  }

  dispatch(type, event = {}) {
    event.target ??= this;
    for (const listener of this.listeners.get(type) || []) listener(event);
    return event;
  }

  click() {
    if (!this.disabled) this.dispatch('click');
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

function performanceRow(startDate, values, overrides = {}) {
  const end = new Date(`${startDate}T00:00:00Z`);
  end.setUTCDate(end.getUTCDate() + 13);
  return {
    period: {startDate, endDate: end.toISOString().slice(0, 10), windowDays: 14, provisional: false},
    values, visitsSnapshotDate: null, visitsCapturedAt: null, visitsStatus: 'missing', visitsReason: '',
    ...overrides
  };
}

function visitReport(rows) {
  const data = report(0);
  data.groups = [{key: 'overview', label: 'Product metrics', columns: [
    {key: 'period', label: 'Period', type: 'date'},
    {key: 'orders', label: 'Orders', type: 'integer'},
    {key: 'clicks', label: 'Clicks', type: 'integer'}
  ]}];
  data.rows = rows;
  return data;
}

// Metadata is verified separately; these assertions retain the existing formatted-metric checks.
function withoutMetadata(csv) {
  const escape = value => /[",\r\n]/.test(value) ? `"${value.replaceAll('"', '""')}"` : value;
  return parseCsv(csv).map(row => row.slice(0, -13).map(escape).join(',')).join('\r\n');
}

function parseCsv(csv) {
  const rows = [];
  let row = [], value = '', quoted = false;
  for (let i = 0; i < csv.length; i++) {
    const char = csv[i];
    if (char === '"') {
      if (quoted && csv[i + 1] === '"') { value += '"'; i++; }
      else quoted = !quoted;
    } else if (!quoted && (char === ',' || char === '\r' || char === '\n')) {
      row.push(value);
      value = '';
      if (char !== ',') {
        rows.push(row);
        row = [];
        if (char === '\r' && csv[i + 1] === '\n') i++;
      }
    } else value += char;
  }
  row.push(value);
  rows.push(row);
  return rows;
}

function report(clicks, date = '2026-09-07', errors = []) {
  return {
    mock: false,
    product: {name: 'Example product', pnk: 'EXAMPLE-PNK', url: 'https://emag.ro/product'},
    groups: [
      {key: 'overview', label: 'Product metrics', columns: [{key: 'period', label: 'Period', type: 'date'}]},
      {key: 'auto', label: 'Auto', columns: [{key: 'auto_clicks', label: 'Clicks', type: 'integer'}]},
      {key: 'total', label: 'Total', columns: [{key: 'total_clicks', label: 'Clicks', type: 'integer'}]}
    ],
    rows: [performanceRow(date, {auto_clicks: clicks, total_clicks: errors.length ? null : clicks})],
    errors
  };
}

function reviewReport(reviews, errors = [], windowDays = 14) {
  const data = report(5, reviews[0][0], errors);
  data.groups[0].columns.push(
    {key: 'reviews', label: '# rev.', type: 'integer'},
    {key: 'rating', label: 'rating', type: 'decimal'});
  data.rows = reviews.map(([startDate, count, rating, cellProvenance = {}]) => {
    const end = new Date(`${startDate}T00:00:00Z`);
    end.setUTCDate(end.getUTCDate() + windowDays - 1);
    return performanceRow(startDate, {
      reviews: count, rating, auto_clicks: 5, total_clicks: errors.length ? null : 5
    }, {period: {startDate, endDate: end.toISOString().slice(0, 10), windowDays, provisional: false},
      cellProvenance});
  });
  return data;
}

async function openPage(t, period, requestedProductCode = productCode) {
  const template = await readFile(new URL('../../main/jte/product-performance.jte', import.meta.url), 'utf8');
  const elements = new Map([...template.matchAll(/<([a-z]+)[^>]*\bid="([^"]+)"[^>]*>/g)]
    .map(([markup, tag, id]) => {
      const element = new Element(tag);
      element.hidden = /\bhidden\b/.test(markup);
      element.disabled = /\bdisabled\b/.test(markup);
      return [id, element];
    }));
  const byId = suffix => elements.get(`productPerformance${suffix}`)
    || [...elements.values()].flatMap(element => element.descendants()).find(element => element.id === `productPerformance${suffix}`);
  byId('Table').append(byId('Head'), byId('Body'));
  byId('Errors').append(byId('ErrorSummary'), byId('ErrorList'));
  const originals = new Map(['document', 'window', 'ResizeObserver'].map(key => [key, globalThis[key]]));
  t.after(() => originals.forEach((value, key) => {
    if (value === undefined) delete globalThis[key];
    else globalThis[key] = value;
  }));
  const downloads = [];
  const objectUrls = new Map();
  const revokedUrls = [];
  t.mock.method(URL, 'createObjectURL', blob => {
    const url = `blob:performance-test-${objectUrls.size}`;
    objectUrls.set(url, blob);
    return url;
  });
  t.mock.method(URL, 'revokeObjectURL', url => revokedUrls.push(url));
  const documentEvents = new Element('document');
  globalThis.document = {
    addEventListener: (type, listener) => documentEvents.addEventListener(type, listener),
    removeEventListener: (type, listener) => documentEvents.removeEventListener(type, listener),
    body: new Element('body'),
    getElementById: id => elements.get(id) || null,
    querySelector: () => null,
    createElement: tag => {
      const element = new Element(tag);
      if (tag === 'a') element.addEventListener('click', () => downloads.push({
        fileName: element.download, blob: objectUrls.get(element.href), url: element.href
      }));
      return element;
    },
    createDocumentFragment: () => new Element('fragment'),
    createTextNode: text => Object.assign(new Element('#text'), {text})
  };
  globalThis.window = {
    location: new URL(`/private/product-performance?${new URLSearchParams({vendorId,
      productCode: requestedProductCode, ...(period ? {period} : {})})}`, origin),
    history: {replaceState(state, title, url) { window.location = new URL(url, origin); }}
  };
  globalThis.ResizeObserver = class {observe() {} disconnect() {}};
  const requests = [];
  const visitsRequests = [];
  const loggedErrors = [];
  t.mock.method(console, 'error', (...args) => loggedErrors.push(args));
  t.mock.method(globalThis, 'fetch', async url => {
    const parsed = new URL(url, origin);
    if (parsed.pathname === '/app/productPerformanceOptions') {
      return {ok: true, json: async () => ({defaultVendorId: vendorId, vendors: [
        {vendorId, name: 'Example vendor', products: [
          {productCode: requestedProductCode, name: 'Example product'}, {productCode: 'OTHER', name: 'Other product'}
        ]}
      ]})};
    }
    if (parsed.pathname === '/app/productVisits') {
      return new Promise(resolve => visitsRequests.push({
        url: parsed,
        respond: data => resolve({ok: true, json: async () => data}),
        fail: () => resolve({ok: false, status: 500})
      }));
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
    byId, requests, visitsRequests, loggedErrors, template, downloads, revokedUrls,
    dispatch: (type, event) => documentEvents.dispatch(type, event),
    periodSelect() {
      return byId('Head').descendants().find(element => element.getAttribute('aria-label') === 'Performance period');
    },
    showEverything() {
      return byId('Head').descendants().find(element => element.tag === 'input' && element.type === 'checkbox');
    },
    async csv(index = downloads.length - 1) {
      return Buffer.from(await downloads[index].blob.arrayBuffer()).toString('utf8');
    },
    async respond(index, data) {
      requests[index].respond(data);
      await setImmediate();
    },
    async respondVisits(index, data) {
      visitsRequests[index].respond(data);
      await setImmediate();
    }
  };
}

test('CSV downloads align flat grouped headers with the displayed formatted cells', async t => {
  const page = await openPage(t);
  const data = report(25, '2026-09-07', [classificationError]);
  data.groups[0].columns.push(
    {key: 'stock', label: 'Stock', type: 'integer'},
    {key: 'totalSales', label: 'Total Sales (excl. VAT)', type: 'integer'},
    {key: 'conversion', label: 'Conv.', type: 'percent'},
    {key: 'performance', label: 'Perform.', type: 'text'},
    {key: 'rating', label: 'rating', type: 'decimal'});
  Object.assign(data.rows[0].values, {
    stock: '???', totalSales: 1234.56, conversion: 0.0267, performance: 'Șură, "SUPER HOT"', rating: null
  });
  await page.respond(0, data);
  assert.equal(page.byId('DownloadCsv').disabled, false, 'Partial reports remain exportable');
  page.byId('DownloadCsv').click();
  assert.equal(page.downloads.length, 1);
  assert.equal(withoutMetadata(await page.csv()), '\uFEFFPeriod,Stock,Total Sales (excl. VAT),Conv.,Perform.,rating,Auto - Clicks,Total - Clicks\r\n'
    + '07 Sept 2026 - 20 Sept 2026,???,"1,235",2.67%,"Șură, ""SUPER HOT""",—,25,—');
  assert.equal(page.downloads[0].blob.type, 'text/csv;charset=utf-8;');
  assert.deepEqual(page.revokedUrls, [page.downloads[0].url]);
  assert.equal(document.body.children.length, 0, 'Temporary download anchor is removed');
});

test('review cells and CSV distinguish counts, unrated periods, and unavailable fetch data', async t => {
  const page = await openPage(t);
  await page.respond(0, reviewReport([
    ['2026-09-07', 3, 4.6666666667],
    ['2026-09-21', 0, null],
    ['2026-10-05', null, null],
    ['2026-10-19', 2, null]
  ], [classificationError]));
  const cells = page.byId('Body').children.map(row => row.cells.slice(1, 3).map(cell => cell.textContent));
  assert.deepEqual(cells, [['3', '4.67'], ['0', '—'], ['—', '—'], ['2', '—']]);
  assert.equal(page.byId('Errors').hidden, false);
  assert.equal(page.byId('Body').children[0].cells[4].textContent, '—');
  page.byId('DownloadCsv').click();
  assert.equal(withoutMetadata(await page.csv()), '\uFEFFPeriod,# rev.,rating,Auto - Clicks,Total - Clicks\r\n'
    + '07 Sept 2026 - 20 Sept 2026,3,4.67,5,—\r\n'
    + '21 Sept 2026 - 04 Oct 2026,0,—,5,—\r\n'
    + '05 Oct 2026 - 18 Oct 2026,—,—,5,—\r\n'
    + '19 Oct 2026 - 01 Nov 2026,2,—,5,—');
});

test('review metrics refresh for 14/30-day and product changes while ignoring stale responses', async t => {
  const page = await openPage(t);
  await page.respond(0, reviewReport([['2026-09-07', 3, 4]]));
  const reviewCells = () => page.byId('Body').children[0].cells.slice(1, 3).map(cell => cell.textContent);
  assert.deepEqual(reviewCells(), ['3', '4.00']);

  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  assert.equal(page.requests[1].url.searchParams.get('period'), '30days');
  assert.equal(page.byId('Body').textContent, '');
  await page.respond(1, reviewReport([['2026-09-01', 12, 4.75]], [], 30));
  assert.deepEqual(reviewCells(), ['12', '4.75']);
  page.byId('DownloadCsv').click();
  assert.equal(withoutMetadata(await page.csv()), '\uFEFFPeriod,# rev.,rating,Auto - Clicks,Total - Clicks\r\n'
    + '01 Sept 2026 - 30 Sept 2026,12,4.75,5,5');
  const csv = parseCsv(await page.csv());
  assert.equal(csv[1][csv[0].indexOf('Window days')], '30');

  page.periodSelect().value = '14days';
  page.periodSelect().dispatch('change');
  page.byId('ProductSelect').value = 'OTHER';
  page.byId('ProductSelect').dispatch('change');
  assert.equal(page.requests[3].url.searchParams.get('productCode'), 'OTHER');
  assert.equal(page.requests[3].url.searchParams.get('period'), '14days');
  assert.equal(page.byId('Body').textContent, '');
  await page.respond(3, reviewReport([['2026-09-07', 0, null]]));
  await page.respond(2, reviewReport([['2026-09-07', 999, 1]]));
  assert.deepEqual(reviewCells(), ['0', '—']);
});

test('review provenance distinguishes native zero, historical family values, and unavailable cells', async t => {
  const page = await openPage(t);
  const ratingSource = {
    quality: 'estimated', source: 'Google Sheets: Rating Fam./W.', sourceDate: '2026-09-18',
    sourceWindowDays: null,
    method: 'Historical product-family rating observed on the source date; not an average across dates. Observation is 2 day(s) before the report end.',
    sourceUrl: 'https://docs.google.com/spreadsheets/d/example/edit#gid=12&range=FA10'
  };
  const countSource = {...ratingSource, source: 'Google Sheets: Nr. Rew. Fam./W.',
    method: 'Historical product-family review count observed on the source date; never summed across products.'};
  await page.respond(0, reviewReport([
    ['2026-09-07', 0, 4.6, {rating: ratingSource}],
    ['2026-09-07', 120, 4.6, {reviews: countSource, rating: ratingSource}],
    ['2026-09-21', null, null],
    ['2026-10-05', 3, 3]
  ]));
  const rows = page.byId('Body').children;
  assert.deepEqual(rows.map(row => row.cells.slice(1, 3).map(cell => cell.textContent)),
    [['0', '4.60'], ['120', '4.60'], ['—', '—'], ['3', '3.00']]);
  assert.deepEqual(rows.map(row => row.cells.slice(1, 3).map(cell => cell.classList.contains('pp-estimated'))),
    [[false, true], [true, true], [false, false], [false, false]]);
  const historicalRating = rows[0].cells[2];
  assert.match(historicalRating.title, /Estimated history\./);
  assert.match(historicalRating.title, /Source: Google Sheets: Rating Fam\.\/W\./);
  assert.match(historicalRating.title, /Source date: 2026-09-18/);
  assert.match(historicalRating.title, /Historical product-family rating/);
  assert.ok(historicalRating.title.includes(ratingSource.sourceUrl));
  assert.equal(historicalRating.getAttribute('aria-label'), `4.60. rating. ${historicalRating.title}`);
  for (const cell of [rows[0].cells[1], ...rows[2].cells.slice(1, 3), ...rows[3].cells.slice(1, 3)]) {
    assert.doesNotMatch(cell.title || '', /Estimated history|Google Sheets/);
  }

  page.byId('DownloadCsv').click();
  const csv = parseCsv(await page.csv());
  assert.deepEqual(csv.slice(1).map(row => row.slice(1, 3)),
    [['0', '4.60'], ['120', '4.60'], ['—', '—'], ['3', '3.00']]);
  assert.equal(csv[0].at(-1), 'Cell provenance');
  assert.deepEqual(csv.slice(1).map(row => JSON.parse(row.at(-1))),
    [{rating: ratingSource}, {reviews: countSource, rating: ratingSource}, {}, {}]);
});

test('Clicks renders without advertising data or a separate widget/request and preserves zero', async t => {
  const page = await openPage(t);
  assert.equal(page.visitsRequests.length, 0);
  assert.equal(page.byId('Visits'), undefined);
  assert.doesNotMatch(page.template, /productPerformanceVisits/);
  await page.respond(0, visitReport([performanceRow('2026-10-22', {orders: 3, clicks: 0}, {
    visitsStatus: 'available', visitsSnapshotDate: '2026-11-05',
    visitsCapturedAt: '2026-11-04T22:30:00Z'
  })]));
  const cells = page.byId('Body').children[0].cells;
  assert.equal(cells[2].textContent, '0');
  assert.match(cells[2].title, /paid and organic/);
  assert.match(cells[2].title, /Rolling 14-day counter/);
  assert.match(cells[2].title, /window is approximate/);
  assert.match(cells[2].title, /05 Nov 2026, 00:30 \(Romania\)/);
  assert.match(cells[2].getAttribute('aria-label'), /^0\./);
  assert.equal(page.byId('DownloadCsv').disabled, false);
});

test('missing and ambiguous capture periods remain visible without borrowing visits from another row', async t => {
  const page = await openPage(t);
  await page.respond(0, visitReport([
    performanceRow('2026-09-01', {orders: 2, clicks: 123}, {visitsStatus: 'available'}),
    performanceRow('2026-09-15', {orders: 4, clicks: null}, {
      visitsStatus: 'missing', visitsReason: 'No successful collection for the scheduled date.'
    }),
    performanceRow('2026-10-01', {orders: 1, clicks: null}, {visitsStatus: 'ambiguous'})
  ]));
  const rows = page.byId('Body').children;
  assert.deepEqual(rows.map(row => row.cells[2].textContent), ['123', '—', '???']);
  assert.deepEqual(rows.map(row => row.cells[1].textContent), ['2', '4', '1']);
  assert.match(rows[1].cells[2].title, /unknown, not zero/);
  assert.match(rows[1].cells[2].title, /No successful collection/);
  assert.match(rows[1].cells[2].title, /Captured: unavailable/);
  assert.match(rows[2].cells[2].title, /Multiple matching offers/);
});

test('30-day provisional dates and source metadata are preserved in CSV', async t => {
  const page = await openPage(t, '30days');
  const row = performanceRow('2026-10-07', {orders: 7, clicks: 1234}, {
    period: {startDate: '2026-10-07', endDate: '2026-11-05', windowDays: 30, provisional: true},
    visitsStatus: 'available', visitsSnapshotDate: '2026-11-06',
    visitsCapturedAt: '2026-11-06T00:30:00+02:00', visitsReason: 'Provisional, captured before the scheduled close.'
  });
  await page.respond(0, visitReport([row]));
  const cells = page.byId('Body').children[0].cells;
  assert.equal(cells[0].textContent, '07 Oct 2026 - 05 Nov 2026 (provisional)');
  assert.match(cells[2].title, /Rolling 30-day counter/);
  assert.match(cells[2].title, /06 Nov 2026, 00:30 \(Romania\)/);
  page.byId('DownloadCsv').click();
  assert.equal(await page.csv(), '\uFEFFPeriod,Orders,Clicks,Period start,Period end,Window days,Provisional,Visits snapshot date,Visits captured at,Visits status,Visits reason,Performance snapshot date,Performance status,Performance reason,Performance type,Cell provenance\r\n'
    + '07 Oct 2026 - 05 Nov 2026 (provisional),7,"1,234",2026-10-07,2026-11-05,30,true,2026-11-06,2026-11-06T00:30:00+02:00,available,"Provisional, captured before the scheduled close.",,,,,{}');
});

test('product conversion and historical hotness render independently and export for both report windows', async t => {
  for (const windowDays of [14, 30]) {
    await t.test(`${windowDays} days`, async t => {
      const page = await openPage(t, `${windowDays}days`);
      const endDate = windowDays === 14 ? '2026-10-14' : '2026-10-30';
      const row = (values, overrides = {}) => performanceRow('2026-10-01', values, {
        period: {startDate: '2026-10-01', endDate, windowDays, provisional: false},
        visitsStatus: 'available', visitsSnapshotDate: endDate,
        visitsCapturedAt: `${endDate}T20:00:00Z`,
        performanceStatus: 'available', performanceSnapshotDate: endDate,
        performanceReason: null, performanceType: 'super_hot', ...overrides
      });
      const unknownLabel = '<img src=x onerror=alert(1)>, "New hotness"';
      const data = visitReport([
        row({orders: 25, clicks: 1000, conversion: 0.025, performance: 'Super Hot'}),
        row({orders: 0, clicks: 100, conversion: 0, performance: null}, {
          performanceStatus: 'missing', performanceSnapshotDate: null,
          performanceReason: 'No snapshot for the closing date.', performanceType: null
        }),
        row({orders: 3, clicks: 0, conversion: null, performance: '???'}, {
          performanceStatus: 'ambiguous', performanceReason: 'Multiple offers, no unique candidate.',
          performanceType: null
        }),
        row({orders: 3, clicks: 2, conversion: 1.5, performance: unknownLabel}, {
          performanceType: 'future_type'
        }),
        row({orders: 3, clicks: null, conversion: null, performance: 'Super Cold'}, {
          visitsStatus: 'missing', visitsSnapshotDate: null, visitsCapturedAt: null,
          performanceType: 'super_cold'
        }),
        row({orders: 3, clicks: null, conversion: null, performance: 'Cold'}, {
          visitsStatus: 'ambiguous', visitsReason: 'Multiple matching offers.', performanceType: 'cold'
        })
      ]);
      const conversionDescription = 'Orders / Clicks × 100. Units from the selected account; returns are not subtracted.';
      data.groups[0].columns.push(
        {key: 'conversion', label: 'Conv.', type: 'percent', description: conversionDescription},
        {key: 'performance', label: 'Perform.', type: 'text'});
      await page.respond(0, data);
      const rows = page.byId('Body').children;
      assert.deepEqual(rows.map(row => row.cells[3].textContent), ['2.50%', '0.00%', '—', '150.00%', '—', '—']);
      assert.deepEqual(rows.map(row => row.cells[4].textContent), ['Super Hot', '—', '???', unknownLabel, 'Super Cold', 'Cold']);
      assert.match(rows[0].cells[3].title, /Orders \/ Clicks × 100/);
      assert.match(rows[0].cells[3].title, new RegExp(`Rolling ${windowDays}-day counter`));
      assert.match(rows[0].cells[3].title, /window is approximate/);
      assert.match(rows[0].cells[3].getAttribute('aria-label'), /^2\.50%\. Conv\./);
      assert.doesNotMatch(rows[1].cells[3].title, /Conversion is unavailable/);
      assert.match(rows[2].cells[3].title, /positive number of Clicks/);
      assert.match(rows[4].cells[3].title, /Clicks could not be determined/);
      assert.match(rows[5].cells[3].title, /Multiple matching offers/);
      assert.match(rows[0].cells[4].title, /eMAG 30-day classification/);
      assert.match(rows[0].cells[4].title, /including for 14-day rows/);
      assert.match(rows[0].cells[4].title, new RegExp(`Snapshot: ${endDate}`));
      assert.match(rows[1].cells[4].title, /No snapshot for the closing date/);
      assert.match(rows[1].cells[4].title, /Snapshot: unavailable/);
      assert.match(rows[2].cells[4].getAttribute('aria-label'), /^\?\?\?\. Perform\. Multiple matching offers/);
      assert.equal(rows[3].cells[4].children.length, 0, 'Unknown labels must be rendered as text, never HTML');
      assert.match(rows[3].cells[4].title, /Type: future_type/);
      assert.match(rows[4].cells[4].title, new RegExp(`Snapshot: ${endDate}`));
      assert.doesNotMatch(rows[4].cells[4].title, /unavailable|visits|Captured/,
        'Historical hotness remains usable without visits provenance');
      assert.equal(page.visitsRequests.length, 0);
      assert.equal(page.byId('Head').children[1].children[2].title, conversionDescription);

      page.byId('DownloadCsv').click();
      const csv = await page.csv();
      const lines = csv.split('\r\n');
      assert.equal(lines[0], '\uFEFFPeriod,Orders,Clicks,Conv.,Perform.,Period start,Period end,Window days,Provisional,Visits snapshot date,Visits captured at,Visits status,Visits reason,Performance snapshot date,Performance status,Performance reason,Performance type,Cell provenance');
      assert.match(lines[1], /,25,"1,000",2\.50%,Super Hot,/);
      assert.match(lines[1], new RegExp(`,${endDate},available,,super_hot,\\{\\}$`));
      assert.match(lines[2], /,0,100,0\.00%,—,/);
      assert.match(lines[3], /,3,0,—,\?\?\?,/);
      assert.match(lines[3], /,ambiguous,"Multiple offers, no unique candidate\.",,\{\}$/);
      assert.match(lines[4], /,3,2,150\.00%,"<img src=x onerror=alert\(1\)>, ""New hotness""",/);
      assert.match(lines[5], /,3,—,—,Super Cold,/);
      assert.match(lines[5], new RegExp(`,${endDate},available,,super_cold,\\{\\}$`));
    });
  }
});

test('mixed provenance colors only estimated cells and keeps full source evidence in tooltips and CSV', async t => {
  const page = await openPage(t);
  assert.equal(page.byId('InfoText').hidden, true);
  assert.equal(page.byId('Info').disabled, false);
  const sheetSource = {
    quality: 'estimated', source: 'Google Sheets', sourceDate: '2026-05-15', sourceWindowDays: 30,
    method: 'Historical value scaled to 14 days, with "rounding". <img src=x onerror=alert(1)>',
    sourceUrl: 'https://docs.google.com/spreadsheets/d/example/edit#gid=12'
  };
  const cellProvenance = {
    clicks: sheetSource,
    conversion: {...sheetSource, method: 'Orders / Clicks. Orders: native; Clicks: 15 May historical estimate.'},
    performance: {...sheetSource, method: 'Closest earlier classification; no interpolation.'},
    auto_clicks: {quality: 'partial', source: 'native', sourceDate: '2026-05-14', sourceWindowDays: 2,
      method: 'Only 2 of 14 report days have collected Ads data.', sourceUrl: null},
    salesPrice: {quality: 'unrecognized <script>', source: 'unknown', method: '<script>alert(1)</script>'}
  };
  const data = report(5);
  data.groups[0].columns.push(
    {key: 'orders', label: 'Orders', type: 'integer'},
    {key: 'clicks', label: 'Clicks', type: 'integer'},
    {key: 'conversion', label: 'Conv.', type: 'percent', description: 'Units / Clicks × 100.'},
    {key: 'performance', label: 'Perform.', type: 'text'},
    {key: 'salesPrice', label: 'Sales Price', type: 'decimal'});
  data.rows = [performanceRow('2026-05-04', {
    orders: 25, clicks: 1000, conversion: 0.025, performance: 'SUPER HOT', salesPrice: 199.99,
    auto_clicks: 5, total_clicks: null
  }, {visitsStatus: 'estimated', visitsSnapshotDate: '2026-05-15', visitsCapturedAt: null,
    performanceStatus: 'estimated', performanceSnapshotDate: '2026-05-15', cellProvenance}),
  performanceRow('2026-05-18', {
    orders: 0, clicks: 0, conversion: null, performance: 'Cold', salesPrice: 0,
    auto_clicks: 0, total_clicks: 0
  }, {visitsStatus: 'available', visitsSnapshotDate: '2026-05-31', visitsCapturedAt: '2026-05-31T21:00:00Z',
    performanceStatus: 'available', performanceSnapshotDate: '2026-05-31', cellProvenance: {}})];
  await page.respond(0, data);
  const [estimated, native] = page.byId('Body').children;
  assert.match(page.byId('InfoText').textContent, /Estimated history — yellow cells/);
  assert.doesNotMatch(page.template, /EstimatedLegend|PartialLegend/);
  assert.deepEqual(estimated.cells.map(cell => cell.classList.contains('pp-estimated')),
    [false, false, true, true, true, false, false, false]);
  assert.equal(estimated.cells[6].classList.contains('pp-partial'), true);
  assert.equal(estimated.cells[6].textContent, '5', 'The partial marker must not change the numeric value or CSV');
  assert.match(estimated.cells[6].title, /Partial data.*does not cover the complete report window/);
  assert.match(estimated.cells[6].title, /Only 2 of 14/);
  assert.equal(estimated.cells[2].classList.contains('pp-frozen'), true, 'Estimated overview cells remain frozen');
  assert.match(estimated.cells[2].title, /Estimated history/);
  assert.match(estimated.cells[2].title, /Source: Google Sheets/);
  assert.match(estimated.cells[2].title, /Source date: 2026-05-15/);
  assert.match(estimated.cells[2].title, /Source window: 30 days/);
  assert.match(estimated.cells[2].title, /source window may differ/);
  assert.doesNotMatch(estimated.cells[2].title, /Captured:|Visits are unavailable|Rolling 14-day counter/);
  assert.match(estimated.cells[3].title, /Units \/ Clicks × 100/);
  assert.match(estimated.cells[3].title, /Clicks input: Estimated history/);
  assert.match(estimated.cells[4].title, /observed on 2026-05-15, which may differ/);
  assert.match(estimated.cells[2].getAttribute('aria-label'), /1,000\. Clicks\. Estimated total eMAG visits/);
  for (const cell of estimated.cells) assert.equal(cell.children.length, 0, 'Untrusted provenance remains plain text');
  assert.equal(native.cells.some(cell => cell.classList.contains('pp-estimated') || cell.classList.contains('pp-partial')), false,
    'Old dates and real zero values alone must never mark native data estimated');
  assert.match(native.cells[2].title, /Captured:/);

  page.byId('DownloadCsv').click();
  const csv = parseCsv(await page.csv());
  assert.equal(csv[0].at(-1), 'Cell provenance');
  assert.equal(csv[0].length, estimated.cells.length + 13);
  assert.deepEqual(JSON.parse(csv[1].at(-1)), cellProvenance);
  assert.deepEqual(JSON.parse(csv[2].at(-1)), {});
  assert.equal(csv[1][6], '5');
  assert.equal(csv[1][3], '2.50%');
  assert.equal(csv[2][3], '—');
  assert.equal(withoutMetadata(await page.csv()).split('\r\n').length, 3);

  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  assert.equal(page.byId('InfoText').hidden, true, 'Open help dismisses while another report loads');
  assert.equal(page.byId('Info').disabled, false);
  const nativeOnly = report(0);
  await page.respond(1, nativeOnly);
  assert.equal(page.byId('InfoText').hidden, true);
  assert.equal(page.byId('Info').disabled, false);
});

test('report help remains discoverable when older estimated rows are initially hidden', async t => {
  const page = await openPage(t);
  const data = report(5);
  data.rows = Array.from({length: 21}, (_, index) => performanceRow('2026-05-01', {auto_clicks: 5, total_clicks: 5}, {
    cellProvenance: index === 0 ? {auto_clicks: {quality: 'estimated', source: 'Google Sheets',
      sourceDate: '2026-05-01', sourceWindowDays: 30, method: 'Estimated from a source snapshot.', sourceUrl: null}} : {}
  }));
  await page.respond(0, data);
  assert.equal(page.byId('Body').children.length, 20);
  assert.equal(page.byId('Info').disabled, false);
  assert.match(page.byId('InfoText').textContent, /Estimated history — yellow cells/);
  assert.equal(page.byId('Body').children.some(row => row.cells.some(cell => cell.classList.contains('pp-estimated'))), false);
  page.showEverything().checked = true;
  page.showEverything().dispatch('change');
  assert.equal(page.byId('Body').children[0].cells[1].classList.contains('pp-estimated'), true);
});

test('permanent English report help explains estimates and provisional timing independently through loading, errors and empty reports', async t => {
  const page = await openPage(t);
  const button = page.byId('Info');
  const wrap = page.byId('InfoWrap');
  const tooltip = page.byId('InfoText');
  assert.equal(button.getAttribute('aria-label'), 'About report periods and data quality');
  assert.equal(button.getAttribute('aria-describedby'), tooltip.id);
  assert.equal(tooltip.getAttribute('role'), 'tooltip');
  assert.equal(page.byId('InfoHost').children[0], wrap);
  assert.equal(page.byId('Table').descendants().includes(wrap), false, 'Help stays outside scrolling cells and CSV');
  assert.equal(button.disabled, false);
  assert.equal(tooltip.hidden, true);
  for (const explanation of ['Estimated history — yellow cells', '14/30', 'not scaled', 'Partial data — ◐',
    'Missing values (—)', 'Provisional period', 'full rolling 14- or 30-day preview', 'overlap earlier rows',
    'do not add it to closed rows', 'not whether clicks are estimated', 'not necessarily a calendar month']) {
    assert.ok(tooltip.textContent.includes(explanation), explanation);
  }
  assert.doesNotMatch(tooltip.textContent, /Istoric|parțiale|TVA|celulele/);
  wrap.dispatch('pointerenter', {pointerType: 'touch'});
  assert.equal(tooltip.hidden, true);
  button.dispatch('focus');
  button.dispatch('click');
  assert.equal(tooltip.hidden, false);
  button.dispatch('click');
  assert.equal(tooltip.hidden, true);
  button.dispatch('click');
  page.dispatch('keydown', {key: 'Escape'});
  assert.equal(tooltip.hidden, true);
  button.dispatch('blur');
  button.dispatch('focus');
  assert.equal(tooltip.hidden, false);
  page.requests[0].fail();
  await setImmediate();
  assert.equal(page.byId('Info'), button, 'Failure does not replace or disable the help control');
  assert.equal(button.disabled, false);
  page.byId('Retry').click();
  await page.respond(1, {...report(0), rows: []});
  assert.equal(page.byId('Info'), button);
  button.dispatch('click');
  assert.equal(tooltip.hidden, false, 'Empty reports still have useful help');
  page.dispatch('pointerdown', {target: page.byId('Status')});
  assert.equal(tooltip.hidden, true);
});

test('estimated backgrounds override row hover without weakening sticky positioning or tinting native partial cells', async () => {
  const css = await readFile(new URL('../../main/resources/static/styles.css', import.meta.url), 'utf8');
  const estimatedRule = '.pp-table tbody .pp-estimated,\n.pp-table tbody tr:hover > .pp-estimated';
  assert.ok(css.indexOf(estimatedRule) > css.indexOf('.pp-table tbody tr:hover > *'));
  assert.match(css, /\.pp-table tbody \.pp-estimated,\s*\.pp-table tbody tr:hover > \.pp-estimated\s*\{[^}]*background: var\(--estimated-bg\);[^}]*color: var\(--estimated-text\);/);
  for (const declaration of ['--estimated-bg: #fff6cc', '--estimated-text: #483b13',
    '--estimated-bg: #3b321e', '--estimated-text: #f7df98']) assert.ok(css.includes(declaration), declaration);
  assert.match(css, /\.pp-table \.pp-frozen\s*\{[^}]*position: sticky;/);
  const partialRule = css.match(/\.pp-table tbody \.pp-partial::after\s*\{([^}]*)\}/)?.[1];
  assert.match(partialRule, /content: " ◐"/);
  assert.doesNotMatch(partialRule, /background/);
});

test('malformed period metadata fails safely without enabling export', async t => {
  const page = await openPage(t);
  const data = report(3);
  data.rows[0].period.endDate = '2026-02-30';
  await page.respond(0, data);
  assert.match(page.byId('Status').textContent, /Unable to load/);
  assert.equal(page.byId('Retry').hidden, false);
  assert.equal(page.byId('DownloadCsv').disabled, true);
  assert.equal(page.byId('Body').children.length, 0);
});

test('wide overview columns release their horizontal freeze to leave room for advertising metrics', async t => {
  const page = await openPage(t);
  page.byId('Wrap').clientWidth = 450;
  const data = report(5);
  data.groups[0].columns.push(
    {key: 'stock', label: 'Stock', type: 'integer'},
    {key: 'orders', label: 'Orders', type: 'integer'},
    {key: 'clicks', label: 'Clicks', type: 'integer'});
  await page.respond(0, data);
  assert.equal(page.byId('Table').dataset.compactOverview, 'true');
  page.byId('Wrap').clientWidth = 1440;
  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  await page.respond(1, data);
  assert.equal(page.byId('Table').dataset.compactOverview, 'false');
});

test('CSV exports only the latest 20 displayed rows until Show everything is selected', async t => {
  const page = await openPage(t);
  const data = report(0);
  data.rows = Array.from({length: 25}, (_, index) => {
    const date = new Date(Date.UTC(2026, 0, 5 + index * 7)).toISOString().slice(0, 10);
    return performanceRow(date, {auto_clicks: index, total_clicks: index});
  });
  await page.respond(0, data);
  page.byId('DownloadCsv').click();
  const visible = (withoutMetadata(await page.csv())).split('\r\n');
  assert.equal(visible.length, 21);
  assert.equal(visible[1], '09 Feb 2026 - 22 Feb 2026,5,5');
  assert.equal(visible.at(-1), '22 Jun 2026 - 05 Jul 2026,24,24');
  assert.match((await page.csv()).split('\r\n')[1], /,2026-02-09,2026-02-22,14,false,,,missing,,,,,,\{\}$/);
  page.showEverything().checked = true;
  page.showEverything().dispatch('change');
  page.byId('DownloadCsv').click();
  const all = (withoutMetadata(await page.csv())).split('\r\n');
  assert.equal(all.length, 26);
  assert.equal(all[1], '05 Jan 2026 - 18 Jan 2026,0,0');
  assert.match((await page.csv()).split('\r\n')[1], /,2026-01-05,2026-01-18,14,false,,,missing,,,,,,\{\}$/);
  assert.deepEqual(all.slice(6), visible.slice(1));
});

test('CSV filenames and period headers follow the selected product and mode', async t => {
  const page = await openPage(t, undefined, 'SKU/Ș blue');
  const exportDate = new Date().toISOString().slice(0, 10);
  await page.respond(0, report(7));
  page.byId('DownloadCsv').click();
  assert.equal(page.downloads[0].fileName,
    `product-performance-${vendorId}-SKU_Ș-blue-14days-${exportDate}.csv`);
  page.periodSelect().value = '30days';
  page.periodSelect().dispatch('change');
  await page.respond(1, report(300, '2026-09-01'));
  page.byId('ProductSelect').value = 'OTHER';
  page.byId('ProductSelect').dispatch('change');
  await page.respond(2, report(42, '2026-10-01'));
  page.byId('DownloadCsv').click();
  assert.equal(page.downloads[1].fileName,
    `product-performance-${vendorId}-OTHER-30days-${exportDate}.csv`);
  assert.equal(withoutMetadata(await page.csv()), '\uFEFFPeriod,Auto - Clicks,Total - Clicks\r\n01 Oct 2026 - 14 Oct 2026,42,42');
});

test('CSV download stays disabled during loading, failure, empty results, and stale success', async t => {
  const page = await openPage(t);
  const assertDisabled = () => {
    assert.equal(page.byId('DownloadCsv').disabled, true);
    page.byId('DownloadCsv').dispatch('click');
    assert.equal(page.downloads.length, 0);
  };
  assertDisabled();
  await page.respond(0, report(7));
  assert.equal(page.byId('DownloadCsv').disabled, false);
  page.byId('ProductSelect').value = 'OTHER';
  page.byId('ProductSelect').dispatch('change');
  assertDisabled();
  page.requests[1].fail();
  await setImmediate();
  assertDisabled();
  page.byId('Retry').click();
  const empty = report(0);
  empty.rows = [];
  await page.respond(2, empty);
  assertDisabled();
  page.byId('Retry').click();
  page.byId('ProductSelect').value = productCode;
  page.byId('ProductSelect').dispatch('change');
  await page.respond(3, report(999));
  assertDisabled();
  await page.respond(4, report(4));
  assert.equal(page.byId('DownloadCsv').disabled, false);
  page.byId('DownloadCsv').click();
  assert.match(withoutMetadata(await page.csv()), /\r\n07 Sept 2026 - 20 Sept 2026,4,4$/);
});

test('period selection requests server aggregates and preserves vendor/product selection', async t => {
  const page = await openPage(t);
  assert.equal(page.requests[0].url.searchParams.get('period'), '14days');
  await page.respond(0, report(7));
  assert.equal(page.byId('Body').children[0].children[0].textContent, '07 Sept 2026 - 20 Sept 2026');
  const select = page.periodSelect();
  select.value = '30days';
  select.dispatch('change');
  assert.equal(page.byId('Body').textContent, '');
  assert.equal(page.requests.length, 2);
  for (const params of [page.requests[1].url.searchParams, window.location.searchParams]) {
    assert.equal(params.get('period'), '30days');
    assert.equal(params.get('vendorId'), vendorId);
    assert.equal(params.get('productCode'), productCode);
  }
  await page.respond(1, report(300, '2026-09-01'));
  assert.equal(page.byId('Body').children[0].children[0].textContent, '01 Sept 2026 - 14 Sept 2026');
  assert.equal(page.byId('Body').children[0].children[1].textContent, '300');
  assert.equal(page.periodSelect().value, '30days');
  assert.match(page.byId('Table').getAttribute('aria-label'), /^30-day/);
  assert.match(page.byId('Wrap').getAttribute('aria-label'), /^30-day/);
  assert.match(page.byId('Status').textContent, /^1 period/);
  assert.doesNotMatch(page.template, /mock|productPerformancePreview/);
});

test('canonical and legacy links normalize periods and invalid periods default to 14 days', async t => {
  for (const [requested, expected] of [['30days', '30days'], ['14days', '14days'], ['month', '30days'], ['week', '14days'], ['invalid', '14days']]) {
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
  select.value = '30days';
  select.dispatch('change');
  select.value = '14days';
  select.dispatch('change');
  await page.respond(2, report(70));
  await page.respond(1, report(300, '2026-09-01', [classificationError]));
  assert.equal(page.byId('Body').children[0].children[1].textContent, '70');
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('ErrorList').textContent, '');
  assert.match(page.byId('Status').textContent, /^1 period/);
  assert.equal(page.periodSelect().value, '14days');
  assert.equal(page.byId('DownloadCsv').disabled, false);
});

test('a stale period failure cannot clear newer data', async t => {
  const page = await openPage(t);
  await page.respond(0, report(7));
  const select = page.periodSelect();
  select.value = '30days';
  select.dispatch('change');
  select.value = '14days';
  select.dispatch('change');
  await page.respond(2, report(70));
  page.requests[1].fail();
  await setImmediate();
  assert.equal(page.byId('Body').children[0].children[1].textContent, '70');
  assert.equal(page.byId('Wrap').hidden, false);
  assert.equal(page.loggedErrors.length, 0);
  assert.equal(page.byId('DownloadCsv').disabled, false);
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
  assert.equal(page.requests[2].url.searchParams.get('period'), '14days');
  await page.respond(2, report(3));
  assert.equal(page.byId('Errors').hidden, true);
  assert.equal(page.byId('ErrorList').textContent, '');
  assert.equal(page.byId('Body').children[0].children[1].textContent, '3');
});
