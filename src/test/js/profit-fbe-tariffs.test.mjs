import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setImmediate} from 'node:timers/promises';
import test from 'node:test';
import {tariffFields, parseTariffInput} from '../../main/resources/static/js/profit-fbe-tariffs.js';

const endpoint = '/app/profitCalculatorFbeTariffs';
const source = await readFile(new URL('../../main/resources/static/js/profit-fbe-tariffs.js', import.meta.url), 'utf8');
const html = await readFile(new URL('../../main/jte/profit-fbe-tariffs.jte', import.meta.url), 'utf8');
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
    this.classes = new Set();
    this.classList = {toggle: (key, enabled) => enabled ? this.classes.add(key) : this.classes.delete(key)};
    this.value = '';
    this.text = '';
    this.disabled = false;
    this.hidden = false;
  }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren(...children) { this.children = children; this.text = ''; }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  removeAttribute(key) { this.attributes.delete(key); }
  setCustomValidity(message) { this.validationMessage = message; }
  addEventListener(name, callback) { this.listeners.set(name, [...(this.listeners.get(name) || []), callback]); }
  dispatch(name) { for (const callback of this.listeners.get(name) || []) callback({target: this}); }
  showModal() { this.open = true; }
  close() { this.open = false; }
}

const rows = () => Array.from({length: 12}, (_, index) => ({
  id: index + 1, revision: 1, sizeCategory: index < 8 ? `Size ${index + 1}` : 'Oversize Extra-Large',
  maxLengthMm: '200', maxWidthMm: '150', maxHeightMm: '10', maxGirthMm: '520', weightBandKg: '2',
  orderProcessingFeeRon: '3.5', returnProcessingFeeRon: '1.8', removalFeeRon: '2', disposalFeeRon: '2',
  weightRangeKg: index === 11 ? null : '5', extraOrderFeeRon: index === 11 ? null : '0.5',
  extraReturnFeeRon: index === 11 ? null : '0.3', extraRemovalFeeRon: index === 11 ? null : '2',
  extraDisposalFeeRon: index === 11 ? null : '2'
}));

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return {promise, resolve, reject};
}

async function openPage(t, {get = async () => ({tariffs: rows()}), post = async () => ({saved: true})} = {}) {
  const oldDocument = globalThis.document;
  const oldWindow = globalThis.window;
  t.after(() => {
    if (oldDocument === undefined) delete globalThis.document;
    else globalThis.document = oldDocument;
    if (oldWindow === undefined) delete globalThis.window;
    else globalThis.window = oldWindow;
  });
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(([, id]) => [id, new Element()]));
  const events = new Map();
  const calls = [];
  const posts = [];
  globalThis.document = {getElementById: id => elements.get(id), createElement: tag => new Element(tag)};
  globalThis.window = {addEventListener: (name, callback) => events.set(name, callback)};
  t.mock.method(globalThis, 'fetch', async (url, init) => {
    assert.equal(url, endpoint);
    calls.push({url, method: init?.method || 'GET'});
    const request = init?.method === 'POST' ? JSON.parse(init.body) : null;
    if (request) posts.push(request);
    const result = await (request ? post(request) : get());
    return {ok: !result.httpStatus, status: result.httpStatus || 200, json: async () => result};
  });
  await import(dataUrl(source.replace("'./common.js'", JSON.stringify(common)) + `\n// fixture ${moduleId++}`));
  await setImmediate();
  const body = elements.get('fbeTariffBody');
  return {
    elements, events, calls, posts, body,
    status: elements.get('fbeTariffStatus'), retry: elements.get('fbeTariffRetry'),
    review: elements.get('fbeTariffReview'), revert: elements.get('fbeTariffRevert'),
    save: elements.get('fbeTariffSave'), dialog: elements.get('fbeTariffDialog'),
    reviewError: elements.get('fbeTariffReviewError'), reviewBody: elements.get('fbeTariffReviewBody'),
    input(id, key) {
      const row = body.children.find(row => Number(row.dataset.tariffId) === id);
      return row.children[tariffFields.findIndex(field => field.key === key) + 1].children[0];
    }
  };
}

function edit(page, id, key, value) {
  const input = page.input(id, key);
  input.value = value;
  input.dispatch('input');
  return input;
}

test('tariff input validation preserves exact decimals, optional nulls, required values and positive thresholds', () => {
  const field = key => tariffFields.find(field => field.key === key);
  assert.equal(parseTariffInput(field('orderProcessingFeeRon'), '9007199254740993.123456789').value,
    '9007199254740993.123456789');
  assert.equal(parseTariffInput(field('weightBandKg'), '.000000000000000000001').value,
    '0.000000000000000000001');
  assert.equal(parseTariffInput(field('orderProcessingFeeRon'), '000.5000').value, '0.5');
  assert.equal(parseTariffInput(field('orderProcessingFeeRon'), '0').value, '0');
  assert.equal(parseTariffInput(field('extraOrderFeeRon'), ' ').value, null);
  assert.equal(parseTariffInput(field('sizeCategory'), '  Small  ').value, 'Small');
  assert.ok(parseTariffInput(field('sizeCategory'), '').error);
  assert.ok(parseTariffInput(field('sizeCategory'), 'x'.repeat(201)).error);
  assert.ok(parseTariffInput(field('orderProcessingFeeRon'), '').error);
  assert.ok(parseTariffInput(field('weightBandKg'), '0.000').error);
  assert.ok(parseTariffInput(field('weightRangeKg'), '0').error);
  assert.ok(parseTariffInput(field('orderProcessingFeeRon'), '.' + '0'.repeat(62) + '1').error,
    'The canonical leading zero must also fit the decimal length limit');
  for (const value of ['-1', '1e3', 'Infinity', 'NaN', '1,5', '1'.repeat(65)]) {
    assert.ok(parseTariffInput(field('orderProcessingFeeRon'), value).error, value);
  }
});

test('loads all twelve distinct tariff rows with exact values and optional blanks', async t => {
  const data = rows();
  data[0].orderProcessingFeeRon = '9007199254740993.123456789';
  data[0].sizeCategory = '<b>Small</b>';
  const page = await openPage(t, {get: async () => ({tariffs: [...data].reverse()})});
  assert.equal(page.body.children.length, 12);
  assert.ok(page.body.children.every(row => row.children.length === 16));
  assert.deepEqual(page.body.children.map(row => Number(row.dataset.tariffId)), Array.from({length: 12}, (_, i) => i + 1));
  assert.equal(page.input(1, 'orderProcessingFeeRon').value, '9007199254740993.123456789');
  assert.equal(page.input(1, 'sizeCategory').value, '<b>Small</b>');
  assert.equal(page.input(12, 'weightRangeKg').value, '');
  assert.equal(page.input(12, 'extraOrderFeeRon').value, '');
  assert.equal(page.input(9, 'sizeCategory').value, page.input(12, 'sizeCategory').value);
  assert.equal(page.review.disabled, true);
  assert.equal(page.revert.disabled, true);
  assert.equal(page.status.hidden, true);
  assert.equal(page.calls.length, 1);
});

test('failed initial loading can be retried without creating tariff edits', async t => {
  let attempts = 0;
  const page = await openPage(t, {get: async () => {
    if (++attempts === 1) throw new Error('Unavailable');
    return {tariffs: rows()};
  }});
  assert.equal(page.body.children.length, 0);
  assert.equal(page.retry.hidden, false);
  assert.equal(page.review.disabled, true);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.body.children.length, 12);
  assert.equal(page.retry.hidden, true);
  assert.equal(page.posts.length, 0);
});

test('rejects incomplete or inexact tariff responses rather than presenting an editable partial grid', async t => {
  const data = rows();
  data[1].orderProcessingFeeRon = 4.2;
  let attempts = 0;
  const page = await openPage(t, {get: async () => ({tariffs: ++attempts === 1 ? data : rows().slice(1)})});
  assert.equal(page.body.children.length, 0);
  assert.equal(page.retry.hidden, false);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.body.children.length, 0);
  assert.equal(page.review.disabled, true);
});

test('reviews changed fields and saves only canonical strings or explicit nulls with their original revisions', async t => {
  const data = rows();
  data[0].revision = 7;
  let saved = false;
  const page = await openPage(t, {
    get: async () => ({tariffs: saved ? rows() : data}),
    post: async request => {
      assert.deepEqual(request, {changes: [
        {id: 1, expectedRevision: 7, values: {sizeCategory: '<b>Renamed</b>', orderProcessingFeeRon: '9007199254740993.123456789'}},
        {id: 12, expectedRevision: 1, values: {extraOrderFeeRon: '0'}}
      ]});
      saved = true;
      return {saved: true};
    }
  });
  edit(page, 1, 'sizeCategory', ' <b>Renamed</b> ');
  edit(page, 1, 'orderProcessingFeeRon', '9007199254740993.123456789');
  edit(page, 12, 'extraOrderFeeRon', '0');
  assert.equal(page.posts.length, 0, 'Typing never autosaves');
  page.review.dispatch('click');
  assert.equal(page.dialog.open, true);
  assert.equal(page.reviewBody.children.length, 3);
  const renamed = page.reviewBody.children[0].children[3];
  assert.equal(renamed.textContent, '<b>Renamed</b>');
  assert.equal(renamed.children.length, 0, 'Labels remain literal text');
  page.save.dispatch('click');
  await setImmediate();
  assert.equal(page.posts.length, 1);
  assert.equal(page.dialog.open, false);
  assert.equal(page.review.disabled, true);
  assert.match(page.status.textContent, /tariffs saved/i);
});

test('numeric formatting alone is unchanged and invalid values block review with visible field feedback', async t => {
  const page = await openPage(t);
  edit(page, 1, 'orderProcessingFeeRon', '03.5000');
  assert.equal(page.review.disabled, true);
  const input = edit(page, 1, 'maxLengthMm', '0');
  assert.equal(input.attributes.get('aria-invalid'), 'true');
  assert.ok(input.validationMessage);
  assert.equal(page.body.children[0].children[2].children[1].hidden, false);
  assert.equal(page.review.disabled, true);
  assert.equal(page.save.disabled, true);
  page.review.dispatch('click');
  assert.notEqual(page.dialog.open, true);
  assert.equal(page.posts.length, 0);
  edit(page, 1, 'maxLengthMm', '200.1');
  assert.equal(input.validationMessage, '');
  assert.equal(page.review.disabled, false);
});

test('save errors and revision conflicts preserve drafts until an explicit revert reloads the latest tariffs', async t => {
  let response = 0;
  let reads = 0;
  const page = await openPage(t, {
    get: async () => {
      const data = rows();
      if (++reads > 1) { data[0].revision = 4; data[0].orderProcessingFeeRon = '8.5'; }
      return {tariffs: data};
    },
    post: async () => ({httpStatus: [500, 400, 409][response++], error: 'Changed or unavailable', id: 1})
  });
  edit(page, 1, 'orderProcessingFeeRon', '6.75');
  page.review.dispatch('click');
  for (let i = 0; i < 3; i++) {
    page.save.dispatch('click');
    await setImmediate();
    assert.equal(page.input(1, 'orderProcessingFeeRon').value, '6.75');
    assert.equal(page.dialog.open, true);
    assert.equal(page.reviewError.hidden, false);
    assert.equal(page.save.disabled, false);
  }
  assert.match(page.reviewError.textContent, /tariff 1.*edits are still here/i);
  assert.ok(page.posts.every(post => post.changes[0].expectedRevision === 1));
  page.elements.get('fbeTariffReviewRevert').dispatch('click');
  await setImmediate();
  assert.equal(page.input(1, 'orderProcessingFeeRon').value, '8.5');
  assert.equal(page.review.disabled, true);
  assert.equal(page.dialog.open, false);
  assert.equal(page.posts.length, 3);
});

test('clearing an optional value posts null while a failed revert retains drafts and the unsaved warning', async t => {
  let reads = 0;
  const page = await openPage(t, {
    get: async () => { if (++reads === 2) throw new Error('Reload failed'); return {tariffs: rows()}; },
    post: async request => {
      assert.equal(request.changes[0].values.weightRangeKg, null);
      return {httpStatus: 500, error: 'Try again'};
    }
  });
  let prevented = false;
  page.events.get('beforeunload')({preventDefault() { prevented = true; }});
  assert.equal(prevented, false);
  edit(page, 1, 'weightRangeKg', '');
  page.events.get('beforeunload')({preventDefault() { prevented = true; }});
  assert.equal(prevented, true);
  page.review.dispatch('click');
  page.save.dispatch('click');
  await setImmediate();
  page.elements.get('fbeTariffReviewRevert').dispatch('click');
  await setImmediate();
  assert.equal(page.input(1, 'weightRangeKg').value, '');
  assert.equal(page.retry.hidden, false);
  assert.equal(page.review.disabled, false);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.input(1, 'weightRangeKg').value, '5');
  assert.equal(page.review.disabled, true);
  prevented = false;
  page.events.get('beforeunload')({preventDefault() { prevented = true; }});
  assert.equal(prevented, false);
});

test('a committed save with failed readback only retries loading and never submits the save again', async t => {
  let reads = 0;
  const page = await openPage(t, {get: async () => {
    if (++reads === 2) throw new Error('Readback failed');
    const data = rows();
    if (reads > 2) { data[0].orderProcessingFeeRon = '7.75'; data[0].revision = 2; }
    return {tariffs: data};
  }});
  edit(page, 1, 'orderProcessingFeeRon', '7.75');
  page.review.dispatch('click');
  page.save.dispatch('click');
  await setImmediate();
  assert.equal(page.posts.length, 1);
  assert.equal(page.dialog.open, false);
  assert.equal(page.body.children.length, 0);
  assert.equal(page.review.disabled, true);
  assert.match(page.status.textContent, /were saved.*could not be reloaded/i);
  assert.equal(page.retry.hidden, false);
  page.retry.dispatch('click');
  await setImmediate();
  assert.equal(page.input(1, 'orderProcessingFeeRon').value, '7.75');
  assert.equal(page.posts.length, 1);
  assert.equal(page.retry.hidden, true);
});

test('a pending save disables editing and repeated save clicks', async t => {
  const pending = deferred();
  const page = await openPage(t, {post: () => pending.promise});
  edit(page, 1, 'orderProcessingFeeRon', '4');
  page.review.dispatch('click');
  page.save.dispatch('click');
  page.save.dispatch('click');
  assert.equal(page.posts.length, 1);
  assert.equal(page.input(1, 'orderProcessingFeeRon').disabled, true);
  assert.equal(page.save.disabled, true);
  assert.equal(page.elements.get('fbeTariffContinue').disabled, true);
  pending.resolve({saved: true});
  await setImmediate();
  assert.equal(page.input(1, 'orderProcessingFeeRon').disabled, false);
});
