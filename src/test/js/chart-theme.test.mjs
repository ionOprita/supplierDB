import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../../main/resources/static/js/chart-theme.js', import.meta.url), 'utf8');
const {themeChartOptions, createThemedChart, observeChartTheme} =
  await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

function themeFixture() {
  const colors = {'--text': '#e5e7eb', '--muted': '#9aa4b2', '--border': '#334155', '--surface': '#161d24'};
  const listeners = new Set();
  const window = {
    addEventListener(type, listener) { assert.equal(type, 'app:themechange'); listeners.add(listener); },
    removeEventListener(type, listener) { assert.equal(type, 'app:themechange'); listeners.delete(listener); }
  };
  const options = {window, element: {}, getComputedStyle: () => ({getPropertyValue: name => colors[name] ?? ''})};
  return {colors, options, change: () => listeners.forEach(listener => listener()), listeners};
}

test('themes all chart text and surfaces without changing callbacks, axes, or disabled plugins', () => {
  const theme = themeFixture();
  const callback = value => `${value}%`;
  const options = {scales: {
    x: {min: 12, max: 50, grid: {display: false}, ticks: {callback}},
    y: {ticks: {precision: 0}, title: {text: 'Units'}}
  }, plugins: {legend: {title: {text: 'Companies'}}, title: {text: 'Orders'}, subtitle: {text: 'November'},
    tooltip: {callbacks: {label: callback}}}};
  assert.equal(themeChartOptions(options, theme.options.element, theme.options.getComputedStyle), options);
  assert.equal(options.scales.x.ticks.callback, callback);
  assert.equal(options.scales.x.min, 12); assert.equal(options.scales.x.max, 50);
  assert.equal(options.scales.x.grid.display, false);
  assert.equal(options.scales.y.ticks.precision, 0);
  assert.equal(options.scales.y.title.text, 'Units');
  for (const axis of Object.values(options.scales)) {
    assert.equal(axis.ticks.color, '#9aa4b2');
    assert.equal(axis.title.color, '#9aa4b2');
    assert.equal(axis.grid.color, '#334155');
    assert.equal(axis.border.color, '#334155');
  }
  assert.equal(options.plugins.legend.labels.color, '#9aa4b2');
  assert.equal(options.plugins.legend.title.color, '#e5e7eb');
  assert.equal(options.plugins.title.color, '#e5e7eb');
  assert.equal(options.plugins.subtitle.color, '#e5e7eb');
  assert.equal(options.plugins.tooltip.callbacks.label, callback);
  assert.equal(options.plugins.tooltip.backgroundColor, '#161d24');
  assert.equal(options.plugins.tooltip.bodyColor, '#e5e7eb');
  const disabled = {scales: {x: {grid: false, title: false}}, plugins: {legend: false, tooltip: false}};
  themeChartOptions(disabled);
  assert.equal(disabled.scales.x.grid, false); assert.equal(disabled.scales.x.title, false);
  assert.equal(disabled.plugins.legend, false); assert.equal(disabled.plugins.tooltip, false);
  const noPlugins = {plugins: false};
  themeChartOptions(noPlugins);
  assert.equal(noPlugins.plugins, false);
});

for (const [file, count] of [['graph.js', 4], ['rrr.js', 3]]) {
  test(`${file} themes new and existing charts without refetching, rebuilding or changing their range`, async () => {
    const theme = themeFixture();
    const events = new Map();
    class Element {
      constructor() { this.value = ''; this.listeners = new Map(); }
      addEventListener(type, listener) { this.listeners.set(type, listener); }
      appendChild() {}
      getContext() { return this; }
    }
    const elements = Object.fromEntries(['productSelect', 'rrrChart', 'rrrMeta', 'ordersChart', 'stornoChart',
      'returnChart', 'dateFromInput', 'dateToInput', 'dateRangeResetBtn'].map(id => [id, new Element()]));
    const document = {getElementById: id => elements[id], createElement: () => new Element(),
      addEventListener: (type, listener) => events.set(type, listener)};
    const charts = [];
    class Chart {
      constructor(canvas, config) { Object.assign(this, {canvas, config, options: config.options, updates: []}); charts.push(this); }
      static getChart() { return null; }
      update(mode) { this.updates.push(mode); }
      destroy() { this.destroyed = true; }
    }
    const requests = [];
    const rows = [{date: [2026, 11, 1], count: 8, value: .1, rawRate: .2, smoothedRate: .15,
      soldQty: 8, returnedQty: 1, reliable: true}];
    const context = {
      document, Chart, console,
      fetchJSON: async url => { requests.push(url); return url === '/app/products' ? [{id: 'sku', name: 'Product'}] : rows; },
      createThemedChart: (Chart, canvas, config) => createThemedChart(Chart, canvas, config, theme.options),
      observeChartTheme: getCharts => observeChartTheme(getCharts, theme.options)
    };
    const script = (await readFile(new URL(`../../main/resources/static/js/${file}`, import.meta.url), 'utf8'))
      .replace(/^import .*;\n/gm, '');
    vm.runInNewContext(script, context, {filename: file});
    await events.get('DOMContentLoaded')();
    elements.productSelect.value = 'sku';
    elements.dateFromInput.value = '2026-10-01';
    elements.dateToInput.value = '2026-11-06';
    await elements.productSelect.listeners.get('change')();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(charts.length, count);
    const initialRequestCount = requests.length;
    const data = charts.map(chart => chart.config.data);
    const ranges = charts.map(chart => [chart.options.scales.x.min, chart.options.scales.x.max]);
    if (file === 'rrr.js') assert.equal(charts[0].config.data.datasets[1].hidden, true);
    charts.forEach(chart => assert.equal(chart.options.scales.y.ticks.color, '#9aa4b2'));
    theme.colors['--muted'] = '#5f6f85'; theme.colors['--surface'] = '#ffffff';
    theme.change();
    assert.equal(charts.length, count); assert.equal(requests.length, initialRequestCount);
    assert.equal(elements.productSelect.value, 'sku');
    assert.equal(elements.dateFromInput.value, '2026-10-01');
    charts.forEach((chart, index) => {
      assert.equal(chart.config.data, data[index]);
      assert.equal(chart.destroyed, undefined);
      assert.deepEqual([chart.options.scales.x.min, chart.options.scales.x.max], ranges[index]);
      assert.deepEqual(chart.updates, ['none']);
      assert.equal(chart.options.scales.y.ticks.color, '#5f6f85');
      assert.equal(chart.options.plugins.tooltip.backgroundColor, '#ffffff');
    });
    if (file === 'rrr.js') assert.equal(charts[0].config.data.datasets[1].hidden, true);
  });
}
