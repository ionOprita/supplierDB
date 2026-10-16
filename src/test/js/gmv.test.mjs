// Run with: node --test src/test/js/gmv.test.mjs
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/gmv.js', import.meta.url), 'utf8');
const {initMonthlyGMV} = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

const FIRST_VENDOR = '6a6fca21-f997-4589-ab95-4c75fe10c7b1';
const SECOND_VENDOR = '34aad1bc-a4f7-425a-8611-30a013788b4a';

function fixture(vendorIds = [FIRST_VENDOR, SECOND_VENDOR, ''], selectedVendor = '') {
  const listeners = new Map();
  const vendorFilter = {
    value: selectedVendor,
    addEventListener(type, listener) { listeners.set(type, listener); }
  };
  const rows = vendorIds.map((vendorId, index) => ({
    dataset: {vendorId},
    hidden: true,
    cells: [`Product ${index}`, `PNK-${index}`, `CODE-${index}`, '123.40', '—']
  }));
  const headers = ['Product', 'PNK', 'Product code', '2026-01', '2026-02'];
  const noRows = {hidden: true};
  const table = {
    headers,
    querySelectorAll(selector) {
      assert.equal(selector, 'tbody tr[data-vendor-id]');
      return rows;
    }
  };
  const elements = {gmvVendorFilterSelect: vendorFilter, monthlyGmvTable: table, gmvNoRows: noRows};
  initMonthlyGMV({getElementById: id => elements[id] ?? null});
  return {
    rows, headers, noRows,
    visibleRows: () => rows.filter(row => !row.hidden),
    selectVendor(vendorId) {
      vendorFilter.value = vendorId;
      listeners.get('change')();
    }
  };
}

test('initial All vendors selection shows products including those without a vendor', () => {
  const page = fixture();

  assert.equal(page.visibleRows().length, 3);
  assert.equal(page.noRows.hidden, true);
});

test('vendor filtering uses the complete UUID and hides products without a vendor', () => {
  const page = fixture([FIRST_VENDOR, SECOND_VENDOR, '', `${FIRST_VENDOR}-suffix`]);

  page.selectVendor(FIRST_VENDOR);

  assert.deepEqual(page.visibleRows().map(row => row.dataset.vendorId), [FIRST_VENDOR]);
  assert.equal(page.noRows.hidden, true);
});

test('a vendor with no matching products shows the empty row', () => {
  const page = fixture([FIRST_VENDOR, '']);

  page.selectVendor(SECOND_VENDOR);

  assert.deepEqual(page.visibleRows(), []);
  assert.equal(page.noRows.hidden, false);
});

test('restoring All vendors shows every original row and preserves months and amounts', t => {
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
});

test('an empty product list keeps the empty row visible for every filter', () => {
  const page = fixture([]);

  assert.equal(page.noRows.hidden, false);
  page.selectVendor(FIRST_VENDOR);
  assert.equal(page.noRows.hidden, false);
  page.selectVendor('');
  assert.equal(page.noRows.hidden, false);
});

test('initialization applies a vendor selection restored by the browser', () => {
  const page = fixture([FIRST_VENDOR, SECOND_VENDOR, ''], SECOND_VENDOR);

  assert.deepEqual(page.visibleRows().map(row => row.dataset.vendorId), [SECOND_VENDOR]);
});

test('initialization tolerates a page without the GMV table', () => {
  assert.doesNotThrow(() => initMonthlyGMV({getElementById: () => null}));
});
