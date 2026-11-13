import {summaryRows, productHeaderRows, categoryRows} from './profit-calculator-layout.js';

const priceFormat = new Intl.NumberFormat('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2});

function cell(tag, className, text) {
  const element = document.createElement(tag);
  element.className = className;
  if (text != null) element.textContent = text;
  return element;
}

function checkbox(checked, label) {
  const element = document.createElement('input');
  element.type = 'checkbox';
  element.checked = checked;
  element.disabled = true;
  element.setAttribute('aria-label', label);
  return element;
}

function populateMetric(value, definition, product) {
  if (product.retracted === true || typeof product.pnk !== 'string' || !product.pnk.trim()) return;
  if (definition.sourceRow === 48 && typeof product.emagSalePriceRon === 'number'
      && Number.isFinite(product.emagSalePriceRon)) {
    value.textContent = priceFormat.format(product.emagSalePriceRon);
  } else if (definition.sourceRow === 71 && product.incomeProfitTax != null) {
    value.textContent = product.incomeProfitTax;
  } else if (definition.sourceRow === 72 && typeof product.vatPayer === 'boolean') {
    value.appendChild(checkbox(product.vatPayer,
      `${definition.label} ${product.name || product.pnk || product.productCode}`));
  }
}

/** Populate identity and the confirmed database-backed fields; other metrics remain blank. */
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
        value.appendChild(checkbox(product.retracted === true,
          `${definition.label} ${product.name || product.pnk || product.productCode}`));
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
      populateMetric(value, definition, product);
      row.appendChild(value);
    }
    body.appendChild(row);
  }
}
