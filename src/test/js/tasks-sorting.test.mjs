// Run with: node --test src/test/js/tasks-sorting.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { setImmediate } from 'node:timers/promises';
import test from 'node:test';

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`;
const common = moduleUrl(await readFile(new URL('../../main/resources/static/js/common.js', import.meta.url), 'utf8'));
const source = await readFile(new URL('../../main/resources/static/js/table-common.js', import.meta.url), 'utf8');
const { initTaskTable, sortTaskTableBody } = await import(moduleUrl(source.replace('"./common.js"', JSON.stringify(common))));

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
  class Element {
    constructor(tag) {
      this.tag = tag;
      this.children = [];
      this.attributes = new Map();
      this.listeners = new Map();
    }
    get rows() { return this.children; }
    get cells() { return this.children; }
    set textContent(value) { this.children = []; this.text = String(value); }
    get textContent() { return this.text ?? this.children.map(child => child.textContent).join(''); }
    set innerHTML(_) { this.children = []; }
    appendChild(child) { this.children.push(child); return child; }
    replaceChildren(...children) { this.children = children; }
    setAttribute(name, value) { this.attributes.set(name, value); }
    removeAttribute(name) { this.attributes.delete(name); }
    addEventListener(name, listener) { this.listeners.set(name, listener); }
  }

  const head = new Element('thead');
  const body = new Element('tbody');
  const table = new Element('table');
  const elements = { tasksHead: head, tasksBody: body, tasksTable: table };
  const originalDocument = globalThis.document;
  const originalWindow = globalThis.window;
  const originalFetch = globalThis.fetch;
  globalThis.document = {
    getElementById: id => elements[id] ?? null,
    createElement: tag => new Element(tag),
    createDocumentFragment: () => new Element('fragment')
  };
  globalThis.window = { addEventListener() {} };
  globalThis.fetch = async () => ({ ok: true, json: async () => [] });

  try {
    initTaskTable({ tableId: 'tasksTable', theadId: 'tasksHead', tbodyId: 'tasksBody', dataUrl: '/app/tasks' });
    await setImmediate();
    const headers = head.rows[0].cells;
    assert.equal(headers.length, 8);
    assert.equal(headers[0].textContent, 'Action');
    assert.equal(headers[0].children.length, 0);
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

test('sorts date and numeric columns by raw values, keeping missing values last', () => {
  const cells = (lastRun, runtime, failures) => [
    {}, {}, {},
    lastRun == null ? {} : { text: 'formatted date', sortValue: lastRun },
    runtime == null ? {} : { text: 'formatted duration', sortValue: runtime },
    {},
    { text: String(failures), sortValue: failures }
  ];
  const body = taskBody([
    taskRow('long', cells(2000, 120, 10)),
    taskRow('missing', cells(null, null, 0)),
    taskRow('short', cells(1000, 9, 2))
  ]);

  for (const column of [3, 4]) {
    sortTaskTableBody(body, column, 'ascending');
    assert.deepEqual(body.rows.map(row => row.name), ['short', 'long', 'missing']);
    sortTaskTableBody(body, column, 'descending');
    assert.deepEqual(body.rows.map(row => row.name), ['long', 'short', 'missing']);
  }
  sortTaskTableBody(body, 6, 'ascending');
  assert.deepEqual(body.rows.map(row => row.name), ['missing', 'short', 'long']);
});
