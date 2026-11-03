// Run with: node --test src/test/js/overview.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/overview.js', import.meta.url), 'utf8');
const {initOverview, overviewCalendar} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const FIRST = 'company:first';
const SECOND = 'company:second';

class Element {
  constructor(tag = '') {
    this.tag = tag;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.dataset = {};
    this.hidden = false;
    this.disabled = false;
    this.text = '';
    this.value = '';
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...children) { this.text = ''; this.children = children; }
  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  getAttribute(name) { return this.attributes.get(name); }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
  removeEventListener(name, listener) {
    if (this.listeners.get(name) === listener) this.listeners.delete(name);
  }
  dispatch(name) { return this.listeners.get(name)?.(); }
}

function report(overrides = {}) {
  return {month: '2026-11', throughDate: '2026-11-03', days: ['2026-11-01', '2026-11-02', '2026-11-03'],
    companies: [{key: FIRST, name: 'First Company', orders: [3, 0, 12], returns: [0, 2, 1]},
      {key: SECOND, name: 'Second Company', orders: [2, 1, 0], returns: [4, 0, 1]}],
    unassignedReturns: 0, ...overrides};
}

function fullMonthReport(month, overrides = {}) {
  const days = overviewCalendar(month);
  return report({month, days, throughDate: days.at(-1),
    companies: [{key: FIRST, name: 'First Company', orders: days.map(() => 1), returns: days.map(() => 0)},
      {key: SECOND, name: 'Second Company', orders: days.map(() => 2), returns: days.map(() => 1)}], ...overrides});
}

function fixture() {
  const elements = Object.fromEntries(['Dashboard', 'Month', 'MonthSelect', 'Context', 'Refresh', 'Status', 'Unassigned', 'Empty', 'Companies']
    .map(suffix => [`overview${suffix}`, new Element()]));
  elements.overviewEmpty.hidden = true;
  elements.overviewUnassigned.hidden = true;
  elements.overviewMonthSelect.max = '';
  elements.overviewMonthSelect.disabled = true;
  function find(node, id) {
    if (node.id === id) return node;
    for (const child of node.children) {
      const found = find(child, id);
      if (found) return found;
    }
    return null;
  }
  const document = {getElementById: id => elements[id] ?? find(elements.overviewCompanies, id),
    createElement: tag => new Element(tag)};
  const requests = [];
  const charts = [];
  const chartState = {attempts: 0, failOnAttempt: null};
  class Chart {
    constructor(canvas, config) {
      if (++chartState.attempts === chartState.failOnAttempt) throw new Error('Chart unavailable');
      Object.assign(this, {canvas, config, destroyed: false});
      charts.push(this);
    }
    destroy() { this.destroyed = true; }
  }
  const fetch = (url, options) => new Promise((resolve, reject) => requests.push({url, options, resolve, reject}));
  const controller = initOverview({document, fetch, Chart,
    getComputedStyle: () => ({getPropertyValue: name => name === '--muted' ? '#abcdef' : '#123456'})});
  const byId = suffix => document.getElementById(`overview${suffix}`);
  const respond = (index, data, status = 200) => requests[index].resolve({ok: status < 400, status, json: async () => data});
  const activeCharts = () => charts.filter(chart => !chart.destroyed);
  const chart = metric => activeCharts().find(chart => chart.canvas.id === `overview${metric}Chart`);
  const select = (metric, key) => {
    const input = byId(`${metric}Company`);
    input.value = key;
    return input.dispatch('change');
  };
  return {controller, requests, charts, chartState, byId, respond, activeCharts, chart, select};
}

test('builds complete 28, 29, 30 and 31 day calendars without local timezone shifts', () => {
  for (const [month, length] of [['2026-02', 28], ['2028-02', 29], ['2026-11', 30], ['2026-12', 31]]) {
    const days = overviewCalendar(month);
    assert.equal(days.length, length);
    assert.equal(days[0], `${month}-01`);
    assert.equal(days.at(-1), `${month}-${length}`);
  }
  for (const month of ['', 'bad', '2026-00', '2026-13', '2026-1', null]) assert.throws(() => overviewCalendar(month));
});

test('default report shows exactly two charts with a separate exact daily line for each company', async () => {
  const page = fixture();
  assert.equal(page.requests[0].url, '/app/overview');
  assert.equal(page.requests[0].options.cache, 'no-store');
  assert.equal(page.byId('Refresh').disabled, true);
  assert.equal(page.byId('MonthSelect').disabled, true);
  assert.equal(page.byId('Dashboard').getAttribute('aria-busy'), 'true');
  page.respond(0, report());
  await page.controller.ready;

  assert.equal(page.byId('Month').textContent, 'November 2026');
  assert.equal(page.byId('MonthSelect').value, '2026-11');
  assert.equal(page.byId('MonthSelect').max, '2026-11');
  assert.equal(page.byId('Companies').children.length, 2);
  assert.equal(page.byId('Companies').children[0].getAttribute('aria-labelledby'), 'overviewOrdersTitle');
  assert.equal(page.byId('Companies').children[1].getAttribute('aria-labelledby'), 'overviewReturnsTitle');
  assert.equal(page.activeCharts().length, 2);
  assert.equal(page.byId('OrdersTitle').textContent, 'Orders');
  assert.equal(page.byId('ReturnsTitle').textContent, 'Returns');
  assert.equal(page.byId('OrdersTotal').textContent, '18');
  assert.equal(page.byId('ReturnsTotal').textContent, '8');
  assert.equal(page.byId('OrdersScope').textContent, 'All companies · month to date');
  for (const metric of ['Orders', 'Returns']) {
    const select = page.byId(`${metric}Company`);
    assert.equal(select.value, '');
    assert.equal(select.disabled, false);
    assert.deepEqual(select.children.map(option => option.textContent), ['All companies', 'First Company', 'Second Company']);
    assert.deepEqual(select.children.map(option => option.value), ['', FIRST, SECOND]);
    assert.equal(page.chart(metric).config.data.datasets.length, 2);
  }
  const orders = page.chart('Orders').config;
  const returns = page.chart('Returns').config;
  assert.equal(orders.data.labels.length, 30);
  assert.equal(orders.data.labels.at(-1), '2026-11-30');
  assert.deepEqual(orders.data.datasets.map(series => series.label), ['First Company', 'Second Company']);
  assert.deepEqual(orders.data.datasets[0].data.slice(0, 4), [3, 0, 12, null]);
  assert.deepEqual(orders.data.datasets[1].data.slice(0, 4), [2, 1, 0, null]);
  assert.deepEqual(returns.data.datasets[0].data.slice(0, 3), [0, 2, 1]);
  assert.deepEqual(returns.data.datasets[1].data.slice(0, 3), [4, 0, 1]);
  assert.ok(orders.data.datasets.every(series => series.data.slice(3).every(count => count === null)));
  assert.notEqual(orders.data.datasets[0].borderColor, orders.data.datasets[1].borderColor);
  assert.deepEqual(orders.data.datasets.map(series => series.borderColor), returns.data.datasets.map(series => series.borderColor));
  assert.equal(orders.data.datasets[0].fill, false);
  assert.equal(orders.data.datasets[0].cubicInterpolationMode, 'monotone');
  assert.equal(orders.data.datasets[0].spanGaps, false);
  assert.equal(orders.options.scales.y.min, 0);
  assert.equal(orders.options.scales.y.ticks.precision, 0);
  assert.equal(orders.options.scales.y.ticks.callback(1.5), '');
  assert.equal(orders.options.scales.x.ticks.color, '#abcdef');
  assert.equal(orders.options.scales.y.grid.color, '#123456');
  assert.equal(orders.options.scales.x.ticks.callback.call({getLabelForValue: () => '2026-11-03'}, 2), '3');
  assert.equal(orders.options.plugins.legend.display, true);
  assert.equal(orders.options.plugins.legend.onClick(), undefined);
  assert.equal(orders.options.plugins.tooltip.callbacks.title([{label: '2026-11-03'}]), '2026-11-03');
  assert.equal(orders.options.plugins.tooltip.callbacks.label({dataset: {label: 'First Company'}, parsed: {y: 12}}), 'First Company: 12 orders');
  assert.match(page.byId('OrdersChart').getAttribute('aria-label'), /All companies: daily orders.*2026-11-03/);
  assert.equal(page.byId('Unassigned').hidden, true);
  assert.equal(page.byId('Empty').hidden, true);
  assert.equal(page.byId('Status').textContent, '');
  assert.equal(page.byId('Refresh').disabled, false);
  assert.equal(page.byId('MonthSelect').disabled, false);
  assert.equal(page.byId('Dashboard').getAttribute('aria-busy'), 'false');
});

test('each chart independently filters cached company data without fetching or changing the other chart', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const originalOrders = page.chart('Orders');
  const originalReturns = page.chart('Returns');
  const firstColor = originalOrders.config.data.datasets[0].borderColor;
  page.select('Orders', FIRST);
  assert.equal(originalOrders.destroyed, true);
  assert.equal(page.chart('Returns'), originalReturns);
  assert.equal(page.byId('ReturnsCompany').value, '');
  assert.equal(page.byId('OrdersTotal').textContent, '15');
  assert.equal(page.byId('OrdersScope').textContent, 'First Company · month to date');
  assert.deepEqual(page.chart('Orders').config.data.datasets.map(series => series.label), ['First Company']);
  assert.equal(page.chart('Orders').config.data.datasets[0].borderColor, firstColor);
  assert.match(page.byId('OrdersChart').getAttribute('aria-label'), /^First Company: daily orders/);
  const filteredOrders = page.chart('Orders');
  page.select('Returns', SECOND);
  assert.equal(originalReturns.destroyed, true);
  assert.equal(page.chart('Orders'), filteredOrders);
  assert.equal(page.byId('ReturnsTotal').textContent, '5');
  assert.equal(page.byId('ReturnsScope').textContent, 'Second Company · month to date');
  assert.deepEqual(page.chart('Returns').config.data.datasets.map(series => series.label), ['Second Company']);
  page.select('Orders', '');
  assert.equal(page.chart('Orders').config.data.datasets.length, 2);
  assert.equal(page.byId('OrdersTotal').textContent, '18');
  assert.equal(page.byId('ReturnsCompany').value, SECOND);
  assert.equal(page.requests.length, 1);
  assert.equal(page.activeCharts().length, 2);
});

test('month changes and Refresh preserve independent keys and colors despite report reordering', async () => {
  const page = fixture();
  page.respond(0, report({unassignedReturns: 1}));
  await page.controller.ready;
  page.select('Orders', FIRST);
  page.select('Returns', SECOND);
  const orderColor = page.chart('Orders').config.data.datasets[0].borderColor;
  const returnColor = page.chart('Returns').config.data.datasets[0].borderColor;
  const oldOrdersSelect = page.byId('OrdersCompany');
  const oldCharts = page.activeCharts();
  assert.match(page.byId('Unassigned').textContent, /1 return could not be assigned.*is excluded/);
  page.byId('MonthSelect').value = '2026-10';
  const switching = page.byId('MonthSelect').dispatch('change');
  await page.controller.refresh();
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].url, '/app/overview?month=2026-10');
  assert.equal(page.byId('OrdersCompany').disabled, true);
  assert.equal(page.byId('ReturnsCompany').disabled, true);
  assert.equal(page.byId('Refresh').disabled, true);
  assert.equal(page.byId('MonthSelect').disabled, true);
  const historical = fullMonthReport('2026-10', {unassignedReturns: 2});
  historical.companies.reverse();
  page.respond(1, historical);
  await switching;
  assert.ok(oldCharts.every(chart => chart.destroyed));
  assert.equal(oldOrdersSelect.listeners.has('change'), false);
  assert.equal(page.byId('OrdersCompany').value, FIRST);
  assert.equal(page.byId('ReturnsCompany').value, SECOND);
  assert.equal(page.chart('Orders').config.data.datasets[0].borderColor, orderColor);
  assert.equal(page.chart('Returns').config.data.datasets[0].borderColor, returnColor);
  assert.equal(page.byId('OrdersTotal').textContent, '31');
  assert.equal(page.byId('ReturnsTotal').textContent, '31');
  assert.equal(page.byId('OrdersScope').textContent, 'First Company · monthly total');
  assert.ok(page.chart('Orders').config.data.datasets[0].data.every(count => count === 1));
  assert.match(page.byId('Context').textContent, /through 2026-10-31\. Full month\./);
  assert.doesNotMatch(page.byId('Context').textContent, /Today/);
  assert.match(page.byId('Unassigned').textContent, /2 returns could not be assigned.*are excluded/);
  const refreshing = page.byId('Refresh').dispatch('click');
  assert.equal(page.requests[2].url, '/app/overview?month=2026-10');
  page.respond(2, fullMonthReport('2026-10'));
  await refreshing;
  assert.equal(page.byId('OrdersCompany').value, FIRST);
  assert.equal(page.byId('ReturnsCompany').value, SECOND);
  assert.equal(page.byId('Unassigned').hidden, true);
  assert.equal(page.byId('Unassigned').textContent, '');
  assert.equal(page.activeCharts().length, 2);
});

test('only a disappeared selection resets to All companies while the other selection stays intact', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  page.select('Orders', FIRST);
  page.select('Returns', SECOND);
  const color = page.chart('Returns').config.data.datasets[0].borderColor;
  const refreshing = page.controller.refresh();
  page.respond(1, report({companies: [report().companies[1]]}));
  await refreshing;
  assert.equal(page.byId('OrdersCompany').value, '');
  assert.equal(page.byId('ReturnsCompany').value, SECOND);
  assert.equal(page.byId('OrdersScope').textContent, 'All companies · month to date');
  assert.equal(page.byId('OrdersTotal').textContent, '3');
  assert.equal(page.chart('Orders').config.data.datasets[0].borderColor, color);
});

test('duplicate names remain distinct choices, lines and totals through opaque company keys', async () => {
  const page = fixture();
  const data = report();
  data.companies.forEach(company => { company.name = 'Same Company'; });
  page.respond(0, data);
  await page.controller.ready;
  assert.deepEqual(page.byId('OrdersCompany').children.map(option => [option.value, option.textContent]),
    [['', 'All companies'], [FIRST, 'Same Company (1)'], [SECOND, 'Same Company (2)']]);
  assert.deepEqual(page.chart('Orders').config.data.datasets.map(series => series.label), ['Same Company (1)', 'Same Company (2)']);
  page.select('Orders', SECOND);
  assert.equal(page.byId('OrdersTotal').textContent, '3');
  assert.equal(page.chart('Orders').config.data.datasets[0].label, 'Same Company (2)');
  assert.equal(page.chart('Returns').config.data.datasets.length, 2);
});

test('initial HTTP failure is retryable and does not initialize the month picker prematurely', async () => {
  const page = fixture();
  page.respond(0, null, 500);
  await page.controller.ready;
  assert.equal(page.byId('Status').dataset.state, 'error');
  assert.match(page.byId('Status').textContent, /Unable to load.*Refresh to retry/);
  assert.equal(page.charts.length, 0);
  assert.equal(page.byId('MonthSelect').disabled, true);
  const retry = page.byId('Refresh').dispatch('click');
  assert.equal(page.requests[1].url, '/app/overview');
  page.respond(1, report());
  await retry;
  assert.equal(page.byId('Status').dataset.state, 'ready');
  assert.equal(page.activeCharts().length, 2);
  assert.equal(page.byId('MonthSelect').disabled, false);
});

test('failed selection retains old filtered charts, names both months, and retries the requested month', async () => {
  const page = fixture();
  page.respond(0, report({unassignedReturns: 2}));
  await page.controller.ready;
  page.select('Orders', FIRST);
  const cards = page.byId('Companies').children;
  const charts = page.activeCharts();
  page.byId('MonthSelect').value = '2026-10';
  const switching = page.byId('MonthSelect').dispatch('change');
  page.requests[1].reject(new Error('Offline'));
  await switching;
  assert.equal(page.byId('Companies').children, cards);
  assert.ok(charts.every(chart => !chart.destroyed));
  assert.equal(page.byId('OrdersCompany').value, FIRST);
  assert.equal(page.byId('OrdersCompany').disabled, false);
  assert.equal(page.byId('MonthSelect').value, '2026-10');
  assert.equal(page.byId('Month').textContent, 'November 2026');
  assert.match(page.byId('Status').textContent, /Unable to load October 2026.*Displayed figures are for November 2026.*Refresh to retry/);
  assert.equal(page.byId('Unassigned').hidden, false);
  const retry = page.byId('Refresh').dispatch('click');
  assert.equal(page.requests[2].url, '/app/overview?month=2026-10');
  page.respond(2, fullMonthReport('2026-10'));
  await retry;
  assert.equal(page.byId('Month').textContent, 'October 2026');
  assert.equal(page.byId('OrdersCompany').value, FIRST);
  assert.equal(page.byId('Status').textContent, '');
});

test('invalid or future month input sends no request and preserves displayed charts', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const cards = page.byId('Companies').children;
  for (const month of ['', 'bad', '2026-1', '2026-00', '2026-13', '0000-01', '2026-12', '2027-01']) {
    page.byId('MonthSelect').value = month;
    await page.byId('MonthSelect').dispatch('change');
    await page.byId('Refresh').dispatch('click');
    assert.equal(page.requests.length, 1, month);
    assert.equal(page.byId('Companies').children, cards);
    assert.match(page.byId('Status').textContent, /Choose a valid month up to November 2026/);
    assert.equal(page.byId('MonthSelect').disabled, false);
  }
});

test('current final day remains month to date while historical leap month includes every day', async () => {
  const page = fixture();
  page.respond(0, fullMonthReport('2028-03'));
  await page.controller.ready;
  assert.match(page.byId('OrdersScope').textContent, /month to date/);
  assert.match(page.byId('Context').textContent, /Today is still in progress/);
  page.byId('MonthSelect').value = '2028-02';
  const switching = page.controller.refresh();
  page.respond(1, fullMonthReport('2028-02'));
  await switching;
  assert.equal(page.chart('Orders').config.data.labels.length, 29);
  assert.equal(page.chart('Orders').config.data.labels.at(-1), '2028-02-29');
  assert.equal(page.byId('OrdersTotal').textContent, '87');
  assert.match(page.byId('OrdersScope').textContent, /monthly total/);
  assert.doesNotMatch(page.byId('Context').textContent, /Today/);
});

test('malformed, duplicate-key and incomplete historical reports cannot replace valid charts', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const cards = page.byId('Companies').children;
  const invalid = [report({days: ['2026-11-02', '2026-11-01', '2026-11-03']}),
    report({throughDate: '2026-11-04'}),
    report({companies: [{key: FIRST, name: 'Bad', orders: [1, 2], returns: [0, 0, 0]}]}),
    report({companies: [{key: FIRST, name: 'Bad', orders: [1, -2, 3], returns: [0, 0, 0]}]}),
    report({companies: [{key: FIRST, name: 'Bad', orders: [1, null, 3], returns: [0, 0, 0]}]}),
    report({companies: [{name: 'Missing key', orders: [1, 2, 3], returns: [0, 0, 0]}]}),
    report({companies: [report().companies[0], {...report().companies[1], key: FIRST}]})];
  for (const [index, data] of invalid.entries()) {
    const refreshing = page.controller.refresh();
    page.respond(index + 1, data);
    await refreshing;
    assert.equal(page.byId('Status').dataset.state, 'error');
    assert.equal(page.byId('Companies').children, cards);
    assert.equal(page.activeCharts().length, 2);
  }
  page.byId('MonthSelect').value = '2026-10';
  for (const [index, data] of [report(), report({month: '2026-10', throughDate: '2026-10-03',
    days: ['2026-10-01', '2026-10-02', '2026-10-03']})].entries()) {
    const refreshing = page.controller.refresh();
    page.respond(invalid.length + index + 1, data);
    await refreshing;
    assert.equal(page.byId('Companies').children, cards);
    assert.equal(page.byId('Status').dataset.state, 'error');
  }
});

test('zero-activity companies retain flat lines and empty reports show two safe empty cards', async () => {
  const page = fixture();
  page.respond(0, report({companies: [{key: FIRST, name: 'Zero Company', orders: [0, 0, 0], returns: [0, 0, 0]}]}));
  await page.controller.ready;
  assert.deepEqual(page.chart('Orders').config.data.datasets[0].data.slice(0, 3), [0, 0, 0]);
  assert.equal(page.byId('OrdersTotal').textContent, '0');
  const oldCharts = page.activeCharts();
  const refreshing = page.controller.refresh();
  page.respond(1, report({companies: []}));
  await refreshing;
  assert.ok(oldCharts.every(chart => chart.destroyed));
  assert.equal(page.byId('Empty').hidden, false);
  assert.equal(page.byId('Companies').children.length, 2);
  assert.equal(page.activeCharts().length, 2);
  for (const metric of ['Orders', 'Returns']) {
    assert.deepEqual(page.chart(metric).config.data.datasets, []);
    assert.equal(page.byId(`${metric}Company`).disabled, true);
    assert.equal(page.byId(`${metric}Company`).value, '');
    assert.equal(page.byId(`${metric}Total`).textContent, '0');
  }
});

test('failed chart creation rolls back replacements without destroying existing charts or selections', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const oldCharts = page.activeCharts();
  page.chartState.failOnAttempt = page.chartState.attempts + 1;
  page.select('Orders', FIRST);
  assert.equal(page.byId('OrdersCompany').value, '');
  assert.equal(page.byId('OrdersTotal').textContent, '18');
  assert.deepEqual(page.activeCharts(), oldCharts);
  assert.match(page.byId('Status').textContent, /Unable to update the chart/);
  page.select('Orders', FIRST);
  assert.equal(page.byId('OrdersCompany').value, FIRST);
  assert.equal(page.byId('Status').textContent, '');
  assert.equal(page.byId('Status').dataset.state, 'ready');
  const filteredCharts = page.activeCharts();
  page.chartState.failOnAttempt = page.chartState.attempts + 2;
  const oldCards = page.byId('Companies').children;
  const refreshing = page.controller.refresh();
  page.respond(1, report());
  await refreshing;
  assert.deepEqual(page.byId('Companies').children, oldCards);
  assert.deepEqual(page.activeCharts(), filteredCharts);
  assert.equal(page.byId('Status').dataset.state, 'error');
});

test('a successful local filter retry preserves the prior stale-report warning', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const refreshing = page.controller.refresh();
  page.respond(1, null, 500);
  await refreshing;
  const staleWarning = page.byId('Status').textContent;
  page.chartState.failOnAttempt = page.chartState.attempts + 1;
  page.select('Orders', FIRST);
  assert.match(page.byId('Status').textContent, /Unable to update the chart/);
  page.select('Orders', FIRST);
  assert.equal(page.byId('Status').textContent, staleWarning);
  assert.equal(page.byId('Status').dataset.state, 'error');
});

test('company labels are safe text and disposal removes chart handlers and ignores pending responses', async () => {
  const page = fixture();
  const name = '<img src=x onerror=alert(1)>';
  page.respond(0, report({companies: [{key: FIRST, name, orders: [0, 1, 0], returns: [0, 0, 0]}]}));
  await page.controller.ready;
  const select = page.byId('OrdersCompany');
  assert.equal(select.children[1].textContent, name);
  assert.equal(select.children[1].children.length, 0);
  page.select('Orders', FIRST);
  assert.equal(page.chart('Orders').config.data.datasets[0].label, name);
  const attempts = page.chartState.attempts;
  const refreshing = page.controller.refresh();
  page.controller.destroy();
  page.respond(1, report());
  await refreshing;
  assert.equal(page.activeCharts().length, 0);
  assert.equal(page.chartState.attempts, attempts);
  assert.equal(select.listeners.has('change'), false);
  assert.equal(page.byId('Refresh').listeners.has('click'), false);
  assert.equal(page.byId('MonthSelect').listeners.has('change'), false);
  await page.controller.refresh();
  assert.equal(page.requests.length, 2);
});
