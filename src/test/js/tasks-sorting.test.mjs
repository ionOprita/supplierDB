// Run with: node --test src/test/js/tasks-sorting.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/table-common.js', import.meta.url), 'utf8');
const { initTaskTable, renderTasksBody, sortTaskTableBody, toTaskRows } =
  await import(moduleUrl(source.replace('"./common.js"', JSON.stringify(common))));

class Element {
  constructor(tag) {
    this.tag = tag;
    this.children = [];
    this.attributes = new Map();
    this.listeners = new Map();
    this.dataset = {};
    this.classList = { add() {} };
  }
  get rows() { return this.children; }
  get cells() { return this.children; }
  set textContent(value) { this.children = []; this.text = String(value); }
  get textContent() { return (this.text ?? '') + this.children.map(child => child.textContent).join(''); }
  set innerHTML(_) { this.children = []; this.text = ''; }
  appendChild(child) {
    if (child.tag === 'fragment') this.children.push(...child.children);
    else this.children.push(child);
    return child;
  }
  replaceChildren(...children) { this.children = children; this.text = ''; }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(name, listener) { this.listeners.set(name, listener); }
}

function fakeDocument(elements = {}) {
  return {
    getElementById: id => elements[id] ?? null,
    createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('fragment')
  };
}

function taskRow(name, values) {
  const cells = values.map(({ text = '', sortValue } = {}) => ({
    textContent: text,
    dataset: sortValue == null ? {} : { sortValue: String(sortValue) }
  }));
  return { name, cells };
}

function taskBody(rows) {
  return {
    rows,
    replaceChildren(...orderedRows) { this.rows = orderedRows; }
  };
}

test('renders Action as a plain heading and keeps the other headings sortable', async () => {
  const head = new Element('thead');
  const body = new Element('tbody');
  const table = new Element('table');
  const elements = { tasksHead: head, tasksBody: body, tasksTable: table };
  const originalDocument = globalThis.document;
  const originalWindow = globalThis.window;
  const originalFetch = globalThis.fetch;
  globalThis.document = fakeDocument(elements);
  globalThis.window = { addEventListener() {} };
  globalThis.fetch = async () => ({ ok: true, json: async () => [] });

  try {
    initTaskTable({ tableId: 'tasksTable', theadId: 'tasksHead', tbodyId: 'tasksBody', dataUrl: '/app/tasks' });
    await setImmediate();
    const headers = head.rows[0].cells;
    assert.equal(headers.length, 9);
    assert.equal(headers[0].textContent, 'Action');
    assert.equal(headers[0].children.length, 0);
    assert.equal(headers[4].textContent, 'Last runtime');
    assert.equal(headers[5].textContent, 'Current runtime');
    for (const header of headers.slice(1)) {
      assert.equal(header.children[0].tag, 'button');
      assert.equal(header.children[0].listeners.has('click'), true);
    }
  } finally {
    globalThis.document = originalDocument;
    globalThis.window = originalWindow;
    globalThis.fetch = originalFetch;
  }
});

test('shows the previous duration and refreshed elapsed time only while running', () => {
  const originalDocument = globalThis.document;
  globalThis.document = fakeDocument();
  try {
    const body = new Element('tbody');
    const running = {
      name: 'running', started: [2026, 9, 30, 10, 0, 0], terminated: null,
      durationOfLastRun: 120, currentRunSeconds: 90
    };
    const completed = {
      name: 'completed', started: [2026, 9, 30, 9, 0, 0],
      terminated: [2026, 9, 30, 9, 0, 30], durationOfLastRun: 30, currentRunSeconds: 999
    };
    const render = () => renderTasksBody(body, toTaskRows([running, completed, { name: 'new' }]));

    render();
    assert.equal(body.rows[0].cells[4].textContent, '2 min');
    assert.equal(body.rows[0].cells[5].textContent, '1 min 30 sec (≈75%)');
    assert.equal(body.rows[0].cells[5].children[0].tag, 'small');
    assert.equal(body.rows[0].cells[5].dataset.sortValue, '90');
    assert.equal(body.rows[1].cells[4].textContent, '30 sec');
    assert.equal(body.rows[1].cells[5].textContent, '');
    assert.equal(body.rows[2].cells[4].textContent, '');
    assert.equal(body.rows[2].cells[5].textContent, '');

    running.currentRunSeconds = 150;
    render();
    assert.equal(body.rows[0].cells[5].textContent, '2 min 30 sec (≈125%)');
    running.durationOfLastRun = null;
    running.currentRunSeconds = 0;
    render();
    assert.equal(body.rows[0].cells[5].textContent, '0 sec');
    running.durationOfLastRun = 0;
    render();
    assert.equal(body.rows[0].cells[4].textContent, '0 sec');
    assert.equal(body.rows[0].cells[5].textContent, '0 sec');
  } finally {
    globalThis.document = originalDocument;
  }
});

test('refresh updates elapsed runtimes and retains the current runtime sort', async () => {
  const head = new Element('thead');
  const body = new Element('tbody');
  const elements = { tasksHead: head, tasksBody: body, tasksTable: new Element('table') };
  const originalDocument = globalThis.document;
  const originalWindow = globalThis.window;
  const originalFetch = globalThis.fetch;
  let refresh;
  let payload = [
    { name: 'alpha', started: [2026, 9, 30, 10, 0, 0], durationOfLastRun: 120, currentRunSeconds: 90 },
    { name: 'bravo', started: [2026, 9, 30, 10, 0, 0], durationOfLastRun: 100, currentRunSeconds: 20 },
    { name: 'idle', terminated: [2026, 9, 30, 9, 0, 0], durationOfLastRun: 50 }
  ];
  globalThis.document = fakeDocument(elements);
  globalThis.window = { addEventListener() {}, setInterval(callback) { refresh = callback; } };
  globalThis.fetch = async () => ({ ok: true, json: async () => payload });

  try {
    initTaskTable({
      tableId: 'tasksTable', theadId: 'tasksHead', tbodyId: 'tasksBody',
      dataUrl: '/app/tasks', refreshIntervalMs: 10_000
    });
    await setImmediate();
    head.rows[0].cells[5].children[0].listeners.get('click')();
    assert.deepEqual(body.rows.map(row => row.cells[1].textContent), ['bravo', 'alpha', 'idle']);

    payload = payload.map(row => ({
      ...row,
      currentRunSeconds: row.name === 'alpha' ? 130 : row.name === 'bravo' ? 40 : null
    }));
    await refresh();
    assert.deepEqual(body.rows.map(row => row.cells[1].textContent), ['bravo', 'alpha', 'idle']);
    assert.equal(body.rows[1].cells[5].textContent, '2 min 10 sec (≈108%)');
    assert.equal(head.rows[0].cells[5].attributes.get('aria-sort'), 'ascending');
  } finally {
    globalThis.document = originalDocument;
    globalThis.window = originalWindow;
    globalThis.fetch = originalFetch;
  }
});

test('sorts text columns naturally in both directions', () => {
  const body = taskBody([
    taskRow('task 10', [{}, { text: 'task 10' }]),
    taskRow('task 2', [{}, { text: 'task 2' }]),
    taskRow('task 1', [{}, { text: 'task 1' }])
  ]);

  sortTaskTableBody(body, 1, 'ascending');
  assert.deepEqual(body.rows.map(row => row.name), ['task 1', 'task 2', 'task 10']);
  sortTaskTableBody(body, 1, 'descending');
  assert.deepEqual(body.rows.map(row => row.name), ['task 10', 'task 2', 'task 1']);
});

test('sorts date and both runtime columns by raw values, keeping missing values last', () => {
  const cells = (lastRun, lastRuntime, currentRuntime, failures) => [
    {}, {}, {},
    lastRun == null ? {} : { text: 'formatted date', sortValue: lastRun },
    lastRuntime == null ? {} : { text: 'formatted duration', sortValue: lastRuntime },
    currentRuntime == null ? {} : { text: 'formatted duration (≈125%)', sortValue: currentRuntime },
    {},
    { text: String(failures), sortValue: failures }
  ];
  const body = taskBody([
    taskRow('long', cells(2000, 120, 120, 10)),
    taskRow('missing', cells(null, null, null, 0)),
    taskRow('short', cells(1000, 9, 9, 2))
  ]);

  for (const column of [3, 4, 5]) {
    sortTaskTableBody(body, column, 'ascending');
    assert.deepEqual(body.rows.map(row => row.name), ['short', 'long', 'missing']);
    sortTaskTableBody(body, column, 'descending');
    assert.deepEqual(body.rows.map(row => row.name), ['long', 'short', 'missing']);
  }
  sortTaskTableBody(body, 7, 'ascending');
  assert.deepEqual(body.rows.map(row => row.name), ['missing', 'short', 'long']);
});
