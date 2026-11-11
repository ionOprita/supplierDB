import {summaryRows, productHeaderRows, categoryRows} from './profit-calculator-layout.js';

function cell(tag, className, text) {
  const element = document.createElement(tag);
  element.className = className;
  if (text != null) element.textContent = text;
  return element;
}

/** Only product identity and retracted state are populated in this first version. */
export function renderCategoryTable(products = []) {
  const summary = document.getElementById('profitCategorySummaryBody');
  const head = document.getElementById('profitCategoryHead');
  const body = document.getElementById('profitCategoryBody');
  if (!summary || !head || !body) return;
  summary.replaceChildren();
  head.replaceChildren();
  body.replaceChildren();

  for (const definition of summaryRows) {
    const row = document.createElement('tr');
    row.dataset.sourceRow = definition.sourceRow;
    const label = cell('th', 'profit-category-label', definition.label);
    label.scope = 'row';
    row.appendChild(label);
    row.appendChild(cell('td', 'profit-category-value'));
    summary.appendChild(row);
  }

  for (const definition of productHeaderRows) {
    const row = document.createElement('tr');
    row.dataset.sourceRow = definition.sourceRow;
    const label = cell('th', 'profit-category-label', definition.label);
    label.scope = 'row';
    row.appendChild(label);
    for (const product of products) {
      const value = cell(definition.sourceRow === 7 ? 'th' : 'td', 'profit-category-value');
      value.dataset.productCode = product.productCode;
      if (definition.sourceRow === 7) {
        value.scope = 'col';
        value.textContent = product.name ?? '';
      } else if (definition.sourceRow === 8) {
        value.textContent = product.pnk ?? '';
      } else if (definition.sourceRow === 9) {
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.checked = product.retracted === true;
        checkbox.disabled = true;
        checkbox.setAttribute('aria-label', `${definition.label} ${product.name || product.pnk || product.productCode}`);
        value.appendChild(checkbox);
      }
      row.appendChild(value);
    }
    head.appendChild(row);
  }

  for (const definition of categoryRows) {
    const row = document.createElement('tr');
    row.dataset.sourceRow = definition.sourceRow;
    if (definition.kind === 'spacer') row.className = 'profit-category-spacer';
    if (definition.kind === 'note') row.className = 'profit-category-note';
    const label = cell('th', 'profit-category-label');
    label.scope = 'row';
    if (definition.section) {
      label.appendChild(cell('span', 'profit-category-section', definition.section));
    }
    if (definition.label) label.appendChild(cell('span', '', definition.label));
    row.appendChild(label);
    for (const product of products) {
      const value = cell('td', 'profit-category-value');
      value.dataset.productCode = product.productCode;
      row.appendChild(value);
    }
    body.appendChild(row);
  }
}
