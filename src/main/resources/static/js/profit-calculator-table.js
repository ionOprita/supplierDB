import {summaryRows, productHeaderRows, categoryRows} from './profit-calculator-layout.js';
import {overrideFields, overrideInputValue} from './profit-calculator-overrides.js';

const priceFormat = new Intl.NumberFormat('en-US', {minimumFractionDigits: 2, maximumFractionDigits: 2});
const percentFormat = new Intl.NumberFormat('en-US', {style: 'percent', maximumFractionDigits: 4});
const fieldsByRow = new Map(overrideFields.map(field => [field.row, field]));
const calculatedFields = new Map([[26, 'effectiveSalePriceRon'], [49, 'purchaseCostRon'],
  [50, 'transportCostRon'], [75, 'effectiveSalePriceRon'], [76, 'effectiveCommissionRate'],
  [77, 'purchaseCostRon'], [78, 'transportCostRon'], [86, 'commissionCostRon']]);

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
  } else if (definition.sourceRow === 70 && product.companyName != null) {
    value.textContent = product.companyName;
  } else {
    const amount = product.calculated?.[calculatedFields.get(definition.sourceRow)];
    if (typeof amount === 'number' && Number.isFinite(amount)) {
      value.textContent = (definition.sourceRow === 76 ? percentFormat : priceFormat).format(amount);
    }
  }
}

/** Populate identity and the confirmed database-backed fields; other metrics remain blank. */
export function renderCategoryTable(products = [], options = {}) {
  const summary = document.getElementById('profitCategorySummaryBody');
  const head = document.getElementById('profitCategoryHead');
  const body = document.getElementById('profitCategoryBody');
  if (!summary || !head || !body) return;
  const controls = [];
  const metrics = new Map();
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
      const field = fieldsByRow.get(definition.sourceRow);
      if (field) {
        const input = document.createElement(field.choices ? 'select' : 'input');
        input.className = 'profit-override-input';
        input.dataset.field = field.key;
        input.dataset.productCode = product.productCode;
        input.setAttribute('aria-label', `${field.label} ${product.name || product.pnk || product.productCode}`);
        if (field.choices) {
          for (const choice of ['', ...field.choices]) {
            const option = document.createElement('option');
            option.value = choice;
            option.textContent = choice || 'Use default';
            input.appendChild(option);
          }
        } else {
          input.type = 'text';
          input.inputMode = 'decimal';
          input.placeholder = 'Use default';
          input.autocomplete = 'off';
        }
        input.value = options.getOverrideValue?.(product, field)
          ?? overrideInputValue(field, product.overrides?.[field.key]);
        input.disabled = options.saving === true;
        input.addEventListener(field.choices ? 'change' : 'input', () =>
          options.onOverrideInput?.(product.productCode, field, input.value));
        controls.push({productCode: product.productCode, field, input, cell: value});
        value.appendChild(input);
      } else {
        populateMetric(value, definition, product);
        if (!metrics.has(product.productCode)) metrics.set(product.productCode, []);
        metrics.get(product.productCode).push({definition, cell: value});
      }
      row.appendChild(value);
    }
    body.appendChild(row);
  }
  return {
    controls,
    refreshInputs(drafts) {
      for (const control of controls) {
        const state = drafts.fieldState(control.productCode, control.field);
        control.cell.classList.toggle('is-dirty', state.dirty);
        control.input.setCustomValidity(state.error || '');
        control.input.setAttribute('aria-invalid', state.error ? 'true' : 'false');
        control.input.title = state.error || '';
      }
    },
    setDisabled(disabled) { for (const control of controls) control.input.disabled = disabled; },
    updateMetrics(nextProducts, unavailableRows = new Map()) {
      for (const product of nextProducts) {
        for (const metric of metrics.get(product.productCode) || []) {
          metric.cell.replaceChildren();
          if (!unavailableRows.get(product.productCode)?.has(metric.definition.sourceRow)) {
            populateMetric(metric.cell, metric.definition, product);
          }
        }
      }
    }
  };
}
