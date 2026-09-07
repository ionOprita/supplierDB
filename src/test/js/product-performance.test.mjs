// Run with: node --test src/test/js/product-performance.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

// Load the browser module without introducing a frontend package/build dependency.
const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/product-performance.js', import.meta.url), 'utf8');
const {formatPerformanceValue: format, formatPerformancePeriod: formatPeriod,
  visiblePerformanceRows} = await import(moduleUrl(source.replace("'./common.js'", JSON.stringify(common))));

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

test('renders ISO calendar dates without time-zone shifts or invalid date rollover', () => {
  assert.equal(format('2026-05-01', 'date'), '01 May 26');
  assert.equal(format('2026-09-04', 'date'), '04 Sep 26');
  for (const value of ['2026-02-30', '2026-13-01', '2026-05-01T00:00:00Z', 'bad']) {
    assert.equal(format(value, 'date'), '—');
  }
});

test('formats weekly ranges and monthly period labels', () => {
  assert.equal(formatPeriod('2026-05-08'), '08 May 26 - 14 May 26');
  assert.equal(formatPeriod('2026-05-08', 'month'), 'May 2026');
  assert.equal(formatPeriod('2026-02-30'), '—');
});

test('limits visible rows to the latest 20 unless everything is requested', () => {
  const rows = Array.from({length: 25}, (_, index) => index);
  assert.deepEqual(visiblePerformanceRows(rows, false), rows.slice(5));
  assert.deepEqual(visiblePerformanceRows(rows, true), rows);
  assert.deepEqual(visiblePerformanceRows(rows.slice(0, 3), false), [0, 1, 2]);
});
