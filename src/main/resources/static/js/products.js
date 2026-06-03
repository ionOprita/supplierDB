const vendorFilter = document.getElementById('productVendorFilterSelect');
const productsBody = document.getElementById('productsBody');
const noRows = document.getElementById('productsNoRows');
const addRowButton = document.getElementById('addProductRowButton');
const reviewButton = document.getElementById('reviewProductChangesButton');
const rowTemplate = document.getElementById('productRowTemplate');
const reviewDialog = document.getElementById('productReviewDialog');
const reviewSummary = document.getElementById('productReviewSummary');
const reviewChangesBody = document.getElementById('productReviewChangesBody');
const reviewError = document.getElementById('productReviewError');
const saveButton = document.getElementById('saveProductChangesButton');
const revertButton = document.getElementById('revertProductChangesButton');
const continueButton = document.getElementById('continueProductEditingButton');

const originalValues = new WeakMap();
let saveInProgress = false;

function productRows() {
  return Array.from(document.querySelectorAll('#productsBody tr.product-sheet-row'));
}

function rowInputs(row) {
  return Array.from(row.querySelectorAll('.product-cell-input'));
}

function inputValue(input) {
  if (input.type === 'checkbox') {
    return input.checked ? 'true' : 'false';
  }
  return input.value ?? '';
}

function findInput(row, fieldName) {
  return rowInputs(row).find((input) => input.dataset.field === fieldName);
}

function readRowValues(row) {
  const values = {};
  for (const input of rowInputs(row)) {
    values[input.dataset.field] = inputValue(input);
  }
  return values;
}

function writeRowValues(row, values) {
  for (const input of rowInputs(row)) {
    if (input.type === 'checkbox') {
      input.checked = values[input.dataset.field] === 'true';
    } else {
      input.value = values[input.dataset.field] ?? '';
    }
  }
  updateRowVendor(row);
  refreshRowState(row);
}

function cloneValues(values) {
  return { ...values };
}

function updateRowVendor(row) {
  const vendorInput = findInput(row, 'vendor');
  if (!vendorInput || vendorInput.tagName !== 'SELECT') {
    return;
  }
  const selectedOption = vendorInput.selectedOptions[0];
  row.dataset.vendor = vendorInput.value && selectedOption ? selectedOption.textContent.trim() : '';
}

function isChangedValue(current, original) {
  return (current ?? '') !== (original ?? '');
}

function refreshRowState(row) {
  const original = originalValues.get(row) ?? {};
  let rowChanged = false;

  for (const input of rowInputs(row)) {
    const changed = isChangedValue(inputValue(input), original[input.dataset.field]);
    input.closest('td')?.classList.toggle('is-dirty', changed);
    rowChanged ||= changed;
  }

  row.classList.toggle('is-dirty', rowChanged);
}

function displayValue(row, fieldName, value) {
  const input = findInput(row, fieldName);
  if (input?.type === 'checkbox') {
    return value === 'true' ? 'true' : 'false';
  }
  if (input?.tagName === 'SELECT') {
    const option = Array.from(input.options).find((item) => item.value === (value ?? ''));
    if (option) {
      return option.textContent.trim();
    }
  }
  return value ?? '';
}

function productLabel(row, values) {
  return values.name?.trim()
    || values.productCode?.trim()
    || row.dataset.productCode?.trim()
    || 'New product';
}

function collectChanges() {
  const changes = [];

  for (const row of productRows()) {
    const original = originalValues.get(row) ?? {};
    const current = readRowValues(row);
    const cells = [];

    for (const input of rowInputs(row)) {
      const fieldName = input.dataset.field;
      if (!isChangedValue(current[fieldName], original[fieldName])) {
        continue;
      }
      cells.push({
        fieldName,
        label: input.dataset.label || fieldName,
        oldValue: displayValue(row, fieldName, original[fieldName]),
        newValue: displayValue(row, fieldName, current[fieldName]),
      });
    }

    if (cells.length === 0) {
      continue;
    }

    changes.push({
      row,
      mode: row.dataset.mode || 'update',
      productCode: row.dataset.productCode || '',
      productLabel: productLabel(row, current),
      values: current,
      cells,
    });
  }

  return changes;
}

function applyVendorFilter() {
  if (!vendorFilter) return;

  const selectedVendor = vendorFilter.value;
  let visibleRows = 0;

  for (const row of productRows()) {
    const matches = row.dataset.newRow === 'true' || !selectedVendor || row.dataset.vendor === selectedVendor;
    row.hidden = !matches;
    if (matches) visibleRows += 1;
  }

  if (noRows) {
    noRows.hidden = visibleRows !== 0;
  }
}

function updateReviewButton() {
  if (!reviewButton) return;

  const changes = collectChanges();
  const changedCells = changes.reduce((total, change) => total + change.cells.length, 0);
  reviewButton.hidden = changedCells === 0;
  reviewButton.textContent = changedCells === 0 ? 'Review' : `Review (${changedCells})`;
}

function handleProductInput(event) {
  const row = event.target.closest('.product-sheet-row');
  if (!row) return;

  updateRowVendor(row);
  refreshRowState(row);
  applyVendorFilter();
  updateReviewButton();
}

function initializeRow(row) {
  originalValues.set(row, cloneValues(readRowValues(row)));
  updateRowVendor(row);
  refreshRowState(row);

  for (const input of rowInputs(row)) {
    const eventName = input.tagName === 'SELECT' || input.type === 'checkbox' ? 'change' : 'input';
    input.addEventListener(eventName, handleProductInput);
  }
}

function appendTextCell(row, text) {
  const cell = document.createElement('td');
  cell.textContent = text;
  row.appendChild(cell);
}

function renderReviewChanges(changes) {
  reviewChangesBody.textContent = '';

  for (const change of changes) {
    for (const cellChange of change.cells) {
      const row = document.createElement('tr');
      appendTextCell(row, change.productLabel);
      appendTextCell(row, cellChange.label);
      appendTextCell(row, cellChange.oldValue);
      appendTextCell(row, cellChange.newValue);
      reviewChangesBody.appendChild(row);
    }
  }

  const changedCells = changes.reduce((total, change) => total + change.cells.length, 0);
  reviewSummary.textContent = `${changes.length} product row${changes.length === 1 ? '' : 's'}, ${changedCells} changed cell${changedCells === 1 ? '' : 's'}.`;
}

function openReviewDialog() {
  const changes = collectChanges();
  if (changes.length === 0) {
    return;
  }

  renderReviewChanges(changes);
  reviewError.hidden = true;
  reviewError.textContent = '';

  if (typeof reviewDialog.showModal === 'function') {
    reviewDialog.showModal();
  } else {
    reviewDialog.setAttribute('open', '');
  }
}

function revertChanges() {
  for (const row of productRows()) {
    if (row.dataset.newRow === 'true') {
      row.remove();
      continue;
    }
    const original = originalValues.get(row);
    if (original) {
      writeRowValues(row, original);
    }
  }

  reviewDialog.close();
  applyVendorFilter();
  updateReviewButton();
}

async function saveChanges() {
  const changes = collectChanges();
  if (changes.length === 0) {
    reviewDialog.close();
    return;
  }

  saveButton.disabled = true;
  saveInProgress = true;
  reviewError.hidden = true;
  reviewError.textContent = '';

  try {
    const response = await fetch('/private/products/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rows: changes.map((change) => ({
          mode: change.mode,
          productCode: change.productCode,
          values: change.values,
        })),
      }),
    });
    const payload = await response.json().catch(() => ({}));

    if (!response.ok) {
      throw new Error(payload.error || `Save failed with HTTP ${response.status}.`);
    }

    reviewDialog.close();
    window.location.assign(`/private/products?message=${encodeURIComponent(payload.message || 'Product changes saved.')}`);
  } catch (error) {
    saveInProgress = false;
    reviewError.textContent = error.message;
    reviewError.hidden = false;
    saveButton.disabled = false;
  }
}

function addProductRow() {
  if (!productsBody || !rowTemplate) {
    return;
  }

  const row = rowTemplate.content.firstElementChild.cloneNode(true);
  productsBody.appendChild(row);
  initializeRow(row);
  applyVendorFilter();
  updateReviewButton();

  findInput(row, 'productCode')?.focus();
  row.scrollIntoView({ block: 'nearest', inline: 'start' });
}

for (const row of productRows()) {
  initializeRow(row);
}

if (vendorFilter) {
  vendorFilter.addEventListener('change', applyVendorFilter);
}

addRowButton?.addEventListener('click', addProductRow);
reviewButton?.addEventListener('click', openReviewDialog);
saveButton?.addEventListener('click', saveChanges);
revertButton?.addEventListener('click', revertChanges);
continueButton?.addEventListener('click', () => reviewDialog.close());

window.addEventListener('beforeunload', (event) => {
  if (saveInProgress || collectChanges().length === 0) {
    return;
  }
  event.preventDefault();
  event.returnValue = '';
});

applyVendorFilter();
updateReviewButton();
