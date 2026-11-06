import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const infoTooltip = `data:text/javascript;base64,${Buffer.from(await readFile(new URL('../../main/resources/static/js/info-tooltip.js', import.meta.url), 'utf8')).toString('base64')}`;
const chartTheme = `data:text/javascript;base64,${Buffer.from(await readFile(new URL('../../main/resources/static/js/chart-theme.js', import.meta.url), 'utf8')).toString('base64')}`;
const source = (await readFile(new URL('../../main/resources/static/js/gmv-dashboard.js', import.meta.url), 'utf8')).replace("'./info-tooltip.js'", JSON.stringify(infoTooltip)).replace("'./chart-theme.js'", JSON.stringify(chartTheme));
const {initGMVDashboard, gmvCalendar, gmvCents, formatDashboardGMV} =
  await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const FIRST = 'company:first';
const SECOND = 'company:second';

class Element {
  constructor(tag = '') {
    Object.assign(this, {tag, children: [], attributes: new Map(), listeners: new Map(), dataset: {},
      hidden: false, disabled: false, text: '', value: ''});
  }
  set textContent(value) { this.text = String(value); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...children) { this.text = ''; this.children = children; }
  contains(node) { return this === node || this.children.some(child => child.contains(node)); }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  getAttribute(key) { return this.attributes.get(key); }
  addEventListener(type, listener) { this.listeners.set(type, listener); }
  removeEventListener(type, listener) { if (this.listeners.get(type) === listener) this.listeners.delete(type); }
  dispatch(type, event = {}) { event.target ??= this; return this.listeners.get(type)?.(event); }
}

function report(overrides = {}) {
  return {month: '2026-11', throughDate: '2026-11-03', days: ['2026-11-01', '2026-11-02', '2026-11-03'],
    currency: 'RON', total: '110.16', companies: [
      {key: FIRST, name: 'Alpha Company', total: '97.95', gmv: ['100.05', '0.00', '-2.10']},
      {key: SECOND, name: 'Beta Company', total: '12.21', gmv: ['10.00', '-0.01', '2.22']}
    ], ...overrides};
}

function monthReport(month, overrides = {}) {
  const days = gmvCalendar(month);
  return report({month, days, throughDate: days.at(-1), total: '3.75', companies: [
    {key: FIRST, name: 'Alpha Company', total: '1.25', gmv: days.map((_, i) => i === 0 ? '1.25' : '0.00')},
    {key: SECOND, name: 'Beta Company', total: '2.50', gmv: days.map((_, i) => i === 0 ? '2.50' : '0.00')}
  ], ...overrides});
}

function fixture() {
  const elements = Object.fromEntries(['', 'Month', 'MonthSelect', 'Calculate', 'Status', 'Empty', 'Cards']
    .map(suffix => [`gmvDashboard${suffix}`, new Element()]));
  elements.gmvDashboardEmpty.hidden = true;
  elements.gmvDashboardMonthSelect.disabled = true;
  const find = (node, id) => node.id === id ? node : node.children.map(child => find(child, id)).find(Boolean);
  const listeners = new Map();
  const document = {
    getElementById: id => elements[id] ?? find(elements.gmvDashboardCards, id),
    createElement: tag => new Element(tag),
    addEventListener(type, listener) {
      const set = listeners.get(type) ?? new Set(); set.add(listener); listeners.set(type, set);
    },
    removeEventListener(type, listener) { listeners.get(type)?.delete(listener); }
  };
  const requests = [];
  const charts = [];
  const chartState = {attempts: 0, failOnAttempt: null};
  class Chart {
    constructor(canvas, config) {
      if (++chartState.attempts === chartState.failOnAttempt) throw new Error('Chart failed');
      Object.assign(this, {canvas, config, destroyed: false}); charts.push(this);
    }
    update(mode) { this.updates ??= []; this.updates.push(mode); }
    destroy() { this.destroyed = true; }
  }
  const fetch = (url, options) => new Promise((resolve, reject) => requests.push({url, options, resolve, reject, jsonCalls: 0}));
  const theme = {muted: '#abcdef', other: '#123456'};
  const controller = initGMVDashboard({document, fetch, Chart, window: document,
    getComputedStyle: () => ({getPropertyValue: key => key === '--muted' ? theme.muted : theme.other})});
  const byId = suffix => document.getElementById(`gmvDashboard${suffix}`);
  const respond = (index, data, {status = 200, redirected = false, contentType = 'application/json'} = {}) => {
    const request = requests[index];
    request.resolve({ok: status >= 200 && status < 300, status, redirected,
      headers: {get: () => contentType}, json: async () => { request.jsonCalls++; return data; }});
  };
  const activeCharts = () => charts.filter(chart => !chart.destroyed);
  const chart = () => activeCharts()[0];
  const chooseCompany = key => { byId('Company').value = key; return byId('Company').dispatch('change'); };
  const dispatch = (type, event = {}) => { for (const listener of listeners.get(type) ?? []) listener(event); return event; };
  return {controller, theme, byId, respond, requests, charts, chartState, activeCharts, chart, chooseCompany, dispatch, listeners};
}

test('calendar and exact money helpers preserve signed cents and amounts above JavaScript safe integers', () => {
  for (const [month, length] of [['2026-02', 28], ['2028-02', 29], ['2026-11', 30], ['2026-12', 31]]) {
    assert.equal(gmvCalendar(month).length, length);
    assert.equal(gmvCalendar(month).at(-1), `${month}-${length}`);
  }
  assert.equal(gmvCents('0.10') + gmvCents('0.20'), 30n);
  assert.equal(gmvCents('-0.01'), -1n);
  assert.equal(formatDashboardGMV('9007199254740993.12'), '9,007,199,254,740,993.12 RON');
  assert.equal(formatDashboardGMV('-9007199254740993.12'), '-9,007,199,254,740,993.12 RON');
  assert.equal(formatDashboardGMV('-0.00'), '0.00 RON');
  for (const amount of [null, 0.10, '0.1', '0.001', 'NaN', '1e2', '1,000.00']) assert.throws(() => gmvCents(amount));
  for (const month of ['0000-01', '2026-00', '2026-13', '2026-1', 'bad']) assert.throws(() => gmvCalendar(month));
});

test('theme changes repaint GMV without recalculation or loss of the selected company and exact amounts', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  page.chooseCompany(SECOND);
  const chart = page.chart();
  const datasets = chart.config.data.datasets;
  const total = page.byId('Total').textContent;
  const tooltip = chart.config.options.plugins.tooltip.callbacks;
  page.theme.muted = '#aabbcc'; page.theme.other = '#112233';
  page.dispatch('app:themechange');
  assert.equal(page.chart(), chart);
  assert.equal(chart.config.data.datasets, datasets);
  assert.equal(chart.config.options.plugins.tooltip.callbacks, tooltip);
  assert.equal(page.requests.length, 1);
  assert.equal(page.byId('Company').value, SECOND);
  assert.equal(page.byId('MonthSelect').value, '2026-11');
  assert.equal(page.byId('Total').textContent, total);
  assert.deepEqual(chart.updates, ['none']);
  assert.equal(chart.config.options.scales.y.ticks.color, '#aabbcc');
  assert.equal(chart.config.options.plugins.legend.labels.color, '#aabbcc');
  assert.equal(chart.config.options.plugins.tooltip.backgroundColor, '#112233');
  page.controller.destroy();
  page.dispatch('app:themechange');
  assert.deepEqual(chart.updates, ['none']);
  assert.equal(page.listeners.get('app:themechange').size, 0);
});

test('initial load shows one company chart with signed daily GMV, future nulls and exact RON totals', async () => {
  const page = fixture();
  assert.equal(page.requests[0].url, '/app/gmvDashboard');
  assert.equal(page.requests[0].options.cache, 'no-store');
  assert.equal(page.byId('Calculate').disabled, true);
  assert.equal(page.byId('MonthSelect').disabled, true);
  page.respond(0, report());
  await page.controller.ready;
  assert.equal(page.byId('Cards').children.length, 1);
  assert.equal(page.activeCharts().length, 1);
  assert.equal(page.byId('Title').textContent, 'GMV (incl. VAT)');
  assert.equal(page.byId('Total').textContent, '110.16 RON');
  assert.equal(page.byId('Scope').textContent, 'All companies · month to date');
  assert.equal(page.byId('MonthSelect').value, '2026-11');
  assert.equal(page.byId('MonthSelect').max, '2026-11');
  assert.equal(page.byId('Company').value, '');
  assert.deepEqual(page.byId('Company').children.map(option => [option.value, option.textContent]),
    [['', 'All companies'], [FIRST, 'Alpha Company'], [SECOND, 'Beta Company']]);
  const config = page.chart().config;
  assert.equal(config.data.labels.length, 30);
  assert.deepEqual(config.data.datasets[0].data.slice(0, 4), [100.05, 0, -2.1, null]);
  assert.deepEqual(config.data.datasets[1].data.slice(0, 4), [10, -0.01, 2.22, null]);
  assert.ok(config.data.datasets.every(series => series.data.slice(3).every(value => value === null)));
  assert.notEqual(config.data.datasets[0].borderColor, config.data.datasets[1].borderColor);
  assert.equal(config.data.datasets[0].cubicInterpolationMode, 'monotone');
  assert.equal(config.data.datasets[0].spanGaps, false);
  assert.equal(config.options.scales.y.min, undefined);
  assert.equal(config.options.scales.y.title.text, 'GMV (incl. VAT) · RON');
  assert.equal(config.options.scales.y.grid.color, '#123456');
  assert.equal(config.options.scales.x.ticks.color, '#abcdef');
  assert.equal(config.options.scales.x.ticks.callback.call({getLabelForValue: () => '2026-11-03'}, 2), '3');
  assert.equal(config.options.plugins.legend.display, true);
  assert.equal(config.options.plugins.legend.onClick(), undefined);
  assert.equal(page.byId('Calculate').disabled, false);
  assert.equal(page.byId('MonthSelect').disabled, false);
  assert.equal(page.byId('Company').disabled, false);
  assert.equal(page.byId('Status').textContent, '');
  assert.equal(page.byId('').getAttribute('aria-busy'), 'false');
});

test('headline totals and tooltips retain exact huge decimal values rather than rounded graph coordinates', async () => {
  const page = fixture();
  page.respond(0, report({total: '9007199254740993.11', companies: [
    {key: FIRST, name: 'Exact Company', total: '9007199254740993.11', gmv: ['9007199254740993.12', '-0.01', '0.00']}
  ]}));
  await page.controller.ready;
  assert.equal(page.byId('Total').textContent, '9,007,199,254,740,993.11 RON');
  const dataset = page.chart().config.data.datasets[0];
  const tooltip = page.chart().config.options.plugins.tooltip.callbacks;
  assert.equal(tooltip.title([{label: '2026-11-01'}]), '2026-11-01');
  assert.equal(tooltip.label({dataset, dataIndex: 0, parsed: {y: 1}}), 'Exact Company: 9,007,199,254,740,993.12 RON');
  assert.equal(tooltip.label({dataset, dataIndex: 1, parsed: {y: -0.01}}), 'Exact Company: -0.01 RON');
  assert.equal(tooltip.label({dataset, dataIndex: 2, parsed: {y: 0}}), 'Exact Company: 0.00 RON');
});

test('company selection filters cached lines and totals locally while preserving the company color', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const original = page.chart();
  const betaColor = original.config.data.datasets[1].borderColor;
  page.chooseCompany(SECOND);
  assert.equal(original.destroyed, true);
  assert.equal(page.chart().config.data.datasets.length, 1);
  assert.equal(page.chart().config.data.datasets[0].label, 'Beta Company');
  assert.equal(page.chart().config.data.datasets[0].borderColor, betaColor);
  assert.equal(page.byId('Total').textContent, '12.21 RON');
  assert.equal(page.byId('Scope').textContent, 'Beta Company · month to date');
  assert.match(page.byId('Chart').getAttribute('aria-label'), /^Beta Company: daily GMV \(incl. VAT\) in RON/);
  page.chooseCompany('');
  assert.equal(page.chart().config.data.datasets.length, 2);
  assert.equal(page.byId('Total').textContent, '110.16 RON');
  assert.equal(page.requests.length, 1);
  assert.equal(page.activeCharts().length, 1);
});

test('month changes recalculate, retain company keys and colors, and show complete historical periods', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  page.chooseCompany(FIRST);
  const original = page.chart();
  const color = original.config.data.datasets[0].borderColor;
  page.byId('MonthSelect').value = '2026-10';
  const switching = page.byId('MonthSelect').dispatch('change');
  await page.controller.calculate();
  assert.equal(page.requests.length, 2);
  assert.equal(page.requests[1].url, '/app/gmvDashboard?month=2026-10');
  for (const suffix of ['MonthSelect', 'Calculate', 'Company']) assert.equal(page.byId(suffix).disabled, true);
  assert.equal(original.destroyed, false);
  const data = monthReport('2026-10'); data.companies.reverse();
  page.respond(1, data);
  await switching;
  assert.equal(original.destroyed, true);
  assert.equal(page.byId('Company').value, FIRST);
  assert.equal(page.chart().config.data.datasets[0].borderColor, color);
  assert.equal(page.byId('Month').textContent, 'October 2026');
  assert.equal(page.byId('Total').textContent, '1.25 RON');
  assert.equal(page.byId('Scope').textContent, 'Alpha Company · monthly total');
  assert.equal(page.chart().config.data.labels.length, 31);
  assert.ok(page.chart().config.data.datasets[0].data.every(value => value !== null));
  assert.match(page.byId('InfoText').textContent, /through 2026-10-31\. Full month\./);
  assert.doesNotMatch(page.byId('InfoText').textContent, /Today/);
  const recalculate = page.byId('Calculate').dispatch('click');
  assert.equal(page.requests[2].url, '/app/gmvDashboard?month=2026-10');
  assert.equal(page.requests[2].options.cache, 'no-store');
  page.respond(2, monthReport('2026-10'));
  await recalculate;
  assert.equal(page.byId('Company').value, FIRST);
});

test('a removed selected company resets to All and duplicate names remain independent safe choices', async () => {
  const page = fixture();
  const duplicate = report(); duplicate.companies.forEach(company => { company.name = '<Same & Company>'; });
  page.respond(0, duplicate);
  await page.controller.ready;
  assert.deepEqual(page.byId('Company').children.map(option => option.textContent),
    ['All companies', '<Same & Company> (1)', '<Same & Company> (2)']);
  page.chooseCompany(FIRST);
  assert.equal(page.byId('Total').textContent, '97.95 RON');
  const recalculate = page.controller.calculate();
  page.respond(1, report({total: '12.21', companies: [report().companies[1]]}));
  await recalculate;
  assert.equal(page.byId('Company').value, '');
  assert.equal(page.byId('Total').textContent, '12.21 RON');
  assert.equal(page.byId('Scope').textContent, 'All companies · month to date');
});

test('failed recalculation keeps the last chart, selection and info date, and retries the requested month', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  page.chooseCompany(SECOND);
  const previous = page.chart();
  const info = page.byId('InfoText').textContent;
  page.byId('MonthSelect').value = '2026-10';
  const switching = page.controller.calculate();
  page.requests[1].reject(new Error('Offline'));
  await switching;
  assert.equal(page.chart(), previous);
  assert.equal(page.byId('Company').value, SECOND);
  assert.equal(page.byId('Total').textContent, '12.21 RON');
  assert.equal(page.byId('InfoText').textContent, info);
  assert.equal(page.byId('MonthSelect').value, '2026-10');
  assert.match(page.byId('Status').textContent, /Unable to calculate October 2026.*Displayed figures are for November 2026.*Calculate GMV to retry/);
  for (const suffix of ['Calculate', 'MonthSelect', 'Company']) assert.equal(page.byId(suffix).disabled, false);
  const retry = page.byId('Calculate').dispatch('click');
  assert.equal(page.requests[2].url, '/app/gmvDashboard?month=2026-10');
  page.respond(2, monthReport('2026-10'));
  await retry;
  assert.equal(page.byId('Company').value, SECOND);
  assert.equal(page.byId('Total').textContent, '2.50 RON');
});

test('invalid and future months disable Calculate GMV and never send a request', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const old = page.chart();
  for (const value of ['', 'bad', '0000-01', '2026-1', '2026-00', '2026-13', '2026-12']) {
    page.byId('MonthSelect').value = value;
    page.byId('MonthSelect').dispatch('input');
    assert.equal(page.byId('Calculate').disabled, true);
    await page.byId('MonthSelect').dispatch('change');
    await page.controller.calculate();
    assert.equal(page.requests.length, 1);
    assert.equal(page.chart(), old);
  }
  page.byId('MonthSelect').value = '2026-10';
  page.byId('MonthSelect').dispatch('input');
  assert.equal(page.byId('Calculate').disabled, false);
});

test('redirected, non-JSON and unauthorized responses show session-safe errors without parsing HTML', async () => {
  for (const options of [{redirected: true}, {status: 401}, {contentType: 'text/html'}, {status: 403}]) {
    const page = fixture();
    page.respond(0, '<html>Sign in</html>', options);
    await page.controller.ready;
    assert.equal(page.requests[0].jsonCalls, 0);
    assert.equal(page.chart(), undefined);
    assert.match(page.byId('Status').textContent, /session|not allowed/);
    assert.equal(page.byId('Calculate').disabled, false);
    assert.equal(page.byId('MonthSelect').disabled, true);
    page.controller.destroy();
  }
  const page = fixture();
  page.respond(0, null, {status: 500});
  await page.controller.ready;
  const retry = page.controller.calculate();
  assert.equal(page.requests[1].url, '/app/gmvDashboard');
  page.respond(1, report());
  await retry;
  assert.equal(page.activeCharts().length, 1);
});

test('unsupported source currency shows a fixed explanation without leaking server error details', async () => {
  const page = fixture(); page.respond(0, report()); await page.controller.ready;
  const previous = page.chart();
  const recalculate = page.controller.calculate();
  page.respond(1, {error: 'internal-source-diagnostics'}, {status: 422});
  await recalculate;
  assert.equal(page.chart(), previous);
  assert.match(page.byId('Status').textContent,
    /GMV supports RON amounts only\. Source data contains a missing or unsupported currency\./);
  assert.doesNotMatch(page.byId('Status').textContent, /internal-source-diagnostics/);
  assert.equal(page.requests[1].jsonCalls, 0);
  assert.equal(page.byId('Calculate').disabled, false);
});

test('exact cent reconciliation rejects inconsistent or malformed reports while preserving the successful chart', async () => {
  const page = fixture();
  page.respond(0, report());
  await page.controller.ready;
  const chart = page.chart();
  const invalid = [report({currency: 'EUR'}), report({total: '110.17'}), report({total: '110.1'}),
    report({throughDate: '2026-11-04'}), report({days: ['2026-11-02', '2026-11-01', '2026-11-03']}),
    report({companies: [{...report().companies[0], total: '97.96'}, report().companies[1]]}),
    report({companies: [{...report().companies[0], gmv: [100.05, '0.00', '-2.10']}, report().companies[1]]}),
    report({companies: [{...report().companies[0], gmv: ['100.05', '0.000', '-2.10']}, report().companies[1]]}),
    report({companies: [report().companies[0], {...report().companies[1], key: FIRST}]})];
  for (const [index, data] of invalid.entries()) {
    const recalculate = page.controller.calculate(); page.respond(index + 1, data); await recalculate;
    assert.equal(page.chart(), chart);
    assert.equal(page.byId('Total').textContent, '110.16 RON');
    assert.equal(page.byId('Status').dataset.state, 'error');
  }
  page.byId('MonthSelect').value = '2026-10';
  const wrongMonth = page.controller.calculate(); page.respond(invalid.length + 1, report()); await wrongMonth;
  assert.equal(page.chart(), chart);
  const shortMonth = page.controller.calculate();
  page.respond(invalid.length + 2, report({month: '2026-10', throughDate: '2026-10-03',
    days: ['2026-10-01', '2026-10-02', '2026-10-03']}));
  await shortMonth;
  assert.equal(page.chart(), chart);
});

test('info supports hover, focus, tap and dismissal and explains the displayed GMV calculation', async () => {
  const page = fixture(); page.respond(0, report()); await page.controller.ready;
  const button = page.byId('Info'); const wrap = page.byId('InfoWrap'); const tooltip = page.byId('InfoText');
  assert.equal(button.getAttribute('aria-label'), 'About GMV (incl. VAT)');
  assert.equal(button.getAttribute('aria-describedby'), 'gmvDashboardInfoText');
  assert.equal(tooltip.getAttribute('role'), 'tooltip');
  assert.match(tooltip.textContent, /through 2026-11-03\. Today is still in progress/);
  for (const text of ['VAT', 'seller-funded voucher', 'storno', 'excludes shipping', 'monthly GMV', 'revise earlier days']) {
    assert.ok(tooltip.textContent.includes(text), text);
  }
  assert.equal(tooltip.hidden, true);
  wrap.dispatch('pointerenter', {pointerType: 'mouse'}); assert.equal(tooltip.hidden, false);
  page.dispatch('pointerdown', {target: tooltip}); assert.equal(tooltip.hidden, false);
  wrap.dispatch('pointerleave'); assert.equal(tooltip.hidden, true);
  button.dispatch('focus'); assert.equal(tooltip.hidden, false);
  button.dispatch('click'); assert.equal(tooltip.hidden, false);
  button.dispatch('click'); assert.equal(tooltip.hidden, true);
  button.dispatch('click'); assert.equal(tooltip.hidden, false);
  const escape = page.dispatch('keydown', {key: 'Escape', defaultPrevented: false});
  assert.equal(escape.defaultPrevented, false); assert.equal(tooltip.hidden, true);
  button.dispatch('focus'); assert.equal(tooltip.hidden, true);
  button.dispatch('blur', {relatedTarget: page.byId('Calculate')});
  button.dispatch('focus'); assert.equal(tooltip.hidden, false);
  page.dispatch('pointerdown', {target: page.byId('Calculate')}); assert.equal(tooltip.hidden, true);
});

test('zero and empty reports remain valid and the current month final day is still partial', async () => {
  const page = fixture();
  page.respond(0, monthReport('2028-03', {total: '0.00', companies: [
    {key: FIRST, name: 'Zero Company', total: '0.00', gmv: gmvCalendar('2028-03').map(() => '0.00')}
  ]}));
  await page.controller.ready;
  assert.equal(page.byId('Total').textContent, '0.00 RON');
  assert.ok(page.chart().config.data.datasets[0].data.every(value => value === 0));
  assert.match(page.byId('InfoText').textContent, /Today is still in progress/);
  page.byId('MonthSelect').value = '2028-02'; const change = page.controller.calculate();
  page.respond(1, monthReport('2028-02', {total: '0.00', companies: []})); await change;
  assert.equal(page.byId('Empty').hidden, false);
  assert.equal(page.byId('Company').disabled, true);
  assert.equal(page.byId('Total').textContent, '0.00 RON');
  assert.equal(page.chart().config.data.labels.length, 29);
  assert.deepEqual(page.chart().config.data.datasets, []);
  assert.match(page.byId('InfoText').textContent, /through 2028-02-29\. Full month/);
});

test('failed chart replacement restores the old chart and local retry preserves stale calculation warnings', async () => {
  const page = fixture(); page.respond(0, report()); await page.controller.ready;
  const old = page.chart(); const card = page.byId('Cards').children[0];
  page.chartState.failOnAttempt = page.chartState.attempts + 1;
  const recalculate = page.controller.calculate(); page.respond(1, report()); await recalculate;
  assert.equal(page.chart(), old); assert.equal(page.byId('Cards').children[0], card);
  const stale = page.byId('Status').textContent;
  page.chartState.failOnAttempt = page.chartState.attempts + 1;
  page.chooseCompany(FIRST);
  assert.equal(page.byId('Company').value, '');
  assert.match(page.byId('Status').textContent, /Unable to update/);
  page.chooseCompany(FIRST);
  assert.equal(page.byId('Company').value, FIRST);
  assert.equal(page.byId('Status').textContent, stale);
  assert.equal(page.activeCharts().length, 1);
});

test('replacement and disposal clean handlers and ignore late responses', async () => {
  const page = fixture(); page.respond(0, report()); await page.controller.ready;
  const oldInfo = page.byId('Info'); const oldSelect = page.byId('Company');
  const recalculate = page.controller.calculate(); page.respond(1, report()); await recalculate;
  assert.equal(oldInfo.listeners.size, 0); assert.equal(oldSelect.listeners.size, 0);
  assert.equal(page.listeners.get('keydown').size, 1);
  const pending = page.controller.calculate();
  const attempts = page.chartState.attempts;
  const info = page.byId('Info');
  page.controller.destroy(); page.respond(2, report()); await pending;
  assert.equal(page.activeCharts().length, 0);
  assert.equal(page.chartState.attempts, attempts);
  assert.equal(info.listeners.size, 0);
  assert.equal(page.listeners.get('keydown').size, 0); assert.equal(page.listeners.get('pointerdown').size, 0);
  assert.equal(page.byId('Calculate').listeners.size, 0); assert.equal(page.byId('MonthSelect').listeners.size, 0);
  await page.controller.calculate(); assert.equal(page.requests.length, 3);
});
