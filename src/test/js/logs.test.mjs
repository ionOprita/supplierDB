// Run with: node --test src/test/js/logs.test.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = await readFile(new URL('../../main/resources/static/js/logs.js', import.meta.url), 'utf8');
const { initLogTables } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);

class Element {
  constructor(tag, className = '') {
    this.tag = tag;
    this.className = className;
    this.children = [];
    this.dataset = {};
    this.attributes = new Map();
    this.listeners = new Map();
    this.value = '';
  }
  get cells() { return this.children; }
  get tBodies() { return this.children.filter(child => child.tag === 'tbody'); }
  get textContent() { return (this.text ?? '') + this.children.map(child => child.textContent).join(''); }
  set textContent(value) { this.text = String(value); this.children = []; }
  appendChild(child) { child.parentElement = this; this.children.push(child); }
  replaceChildren(...children) { this.children = []; children.forEach(child => this.appendChild(child)); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  addEventListener(event, callback) { this.listeners.set(event, callback); }
  fire(event) { this.listeners.get(event)?.(); }
  querySelector(selector) { return this.querySelectorAll(selector)[0] ?? null; }
  querySelectorAll(selector) {
    const matches = element => selector === '[data-log-file]'
      ? 'logFile' in element.dataset : element.className.split(' ').includes(selector.slice(1));
    return this.children.flatMap(child => [
      ...(matches(child) ? [child] : []), ...child.querySelectorAll(selector)
    ]);
  }
}

function logSection(files) {
  const section = new Element('section', 'log-files-section');
  const filter = new Element('input', 'log-name-filter');
  const table = new Element('table', 'log-files-table');
  const head = new Element('thead');
  const headerRow = new Element('tr');
  const buttons = [0, 1, 2].map(column => {
    const header = new Element('th');
    const button = new Element('button', 'log-sort-button');
    button.dataset.sortColumn = String(column);
    if (column === 2) header.setAttribute('aria-sort', 'descending');
    header.appendChild(button);
    headerRow.appendChild(header);
    return button;
  });
  headerRow.appendChild(new Element('th')); // Actions is not sortable.
  head.appendChild(headerRow);
  const body = new Element('tbody');
  const rows = files.map(([name, size, modified]) => {
    const row = new Element('tr');
    row.dataset.logFile = '';
    for (const [index, value] of [name, size, modified].entries()) {
      const cell = new Element('td');
      cell.textContent = value;
      if (index > 0) cell.dataset.sortValue = String(value);
      row.appendChild(cell);
    }
    const actions = new Element('td');
    const link = new Element('a');
    link.href = `/admin/logs/view?file=${encodeURIComponent(name)}`;
    actions.appendChild(link);
    row.appendChild(actions);
    body.appendChild(row);
    return row;
  });
  if (!rows.length) {
    const empty = new Element('tr');
    empty.textContent = 'No log files found.';
    body.appendChild(empty);
  }
  table.appendChild(head);
  table.appendChild(body);
  section.appendChild(filter);
  section.appendChild(table);
  return {
    section, filter, body, rows, buttons, headerRow,
    names: () => body.children.filter(row => 'logFile' in row.dataset).map(row => row.cells[0].textContent),
    search(value) { filter.value = value; filter.fire('input'); },
    sort(column) { buttons[column].fire('click'); }
  };
}

function init(...sections) {
  const root = new Element('document');
  root.createElement = tag => new Element(tag);
  sections.forEach(({ section }) => root.appendChild(section));
  initLogTables(root);
}

test('filters sections independently with case-insensitive substrings and restores original row links', () => {
  const application = logSection([['Server_10.log', 10, 300], ['Server_2.log', 100, 200], ['other.log', 2, 100]]);
  const supervisor = logSection([['java.log', 1, 200], ['Server_1.log', 2, 100]]);
  init(application, supervisor);
  application.search('sErVeR_');
  assert.deepEqual(application.names(), ['Server_10.log', 'Server_2.log']);
  assert.deepEqual(supervisor.names(), ['java.log', 'Server_1.log']);
  supervisor.search('JAVA');
  assert.deepEqual(supervisor.names(), ['java.log']);
  application.search('');
  assert.deepEqual(application.names(), ['Server_10.log', 'Server_2.log', 'other.log']);
  assert.equal(application.body.children[0], application.rows[0]);
  assert.equal(application.body.children[0].cells[3].children[0].href, '/admin/logs/view?file=Server_10.log');
});

test('sorts filenames naturally in both directions and retains sorting through filtering and clearing', () => {
  const logs = logSection([['file10.log', 1, 300], ['FILE2.log', 2, 200], ['file1.log', 3, 100]]);
  const other = logSection([['z.log', 1, 200], ['a.log', 2, 100]]);
  init(logs, other);
  assert.deepEqual(logs.names(), ['file10.log', 'FILE2.log', 'file1.log']);
  logs.sort(0);
  assert.deepEqual(logs.names(), ['file1.log', 'FILE2.log', 'file10.log']);
  assert.equal(logs.headerRow.cells[0].attributes.get('aria-sort'), 'ascending');
  assert.equal(logs.headerRow.cells[2].attributes.has('aria-sort'), false);
  logs.sort(0);
  assert.deepEqual(logs.names(), ['file10.log', 'FILE2.log', 'file1.log']);
  assert.equal(logs.headerRow.cells[0].attributes.get('aria-sort'), 'descending');
  logs.search('file1');
  assert.deepEqual(logs.names(), ['file10.log', 'file1.log']);
  logs.search('missing');
  assert.equal(logs.body.textContent, 'No matching log files.');
  assert.equal(logs.body.children[0].cells[0].colSpan, 4);
  logs.sort(0);
  logs.search('');
  assert.deepEqual(logs.names(), ['file1.log', 'FILE2.log', 'file10.log']);
  assert.deepEqual(other.names(), ['z.log', 'a.log']);
  assert.equal(other.headerRow.cells[2].attributes.get('aria-sort'), 'descending');
});

test('sorts sizes and modification times using raw numbers, with stable ties and ascending on column changes', () => {
  const logs = logSection([['large', 100, 300], ['small', 2, 100], ['medium', 10, 200], ['same-size', 10, 200]]);
  // Rendered text may differ from the raw sortable values.
  logs.rows.forEach(row => {
    row.cells[1].textContent = 'formatted size';
    row.cells[2].textContent = 'formatted date';
  });
  init(logs);
  logs.sort(1);
  assert.deepEqual(logs.names(), ['small', 'medium', 'same-size', 'large']);
  logs.sort(1);
  assert.deepEqual(logs.names(), ['large', 'medium', 'same-size', 'small']);
  logs.sort(2);
  assert.deepEqual(logs.names(), ['small', 'medium', 'same-size', 'large']);
  logs.sort(2);
  assert.deepEqual(logs.names(), ['large', 'medium', 'same-size', 'small']);
});

test('preserves empty-directory and error sections while other tables remain interactive', () => {
  const empty = logSection([]);
  const error = { section: new Element('section', 'log-files-section') };
  error.section.textContent = 'Logs unavailable.';
  const populated = logSection([['available.log', 0, 100]]);
  init(error, empty, populated);
  empty.search('missing');
  empty.sort(1);
  assert.equal(empty.body.textContent, 'No log files found.');
  assert.equal(error.section.textContent, 'Logs unavailable.');
  populated.search('missing');
  assert.equal(populated.body.textContent, 'No matching log files.');
  populated.search('');
  assert.deepEqual(populated.names(), ['available.log']);
});
