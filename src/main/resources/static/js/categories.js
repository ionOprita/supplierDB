const divisionFilter = document.getElementById('categoryDivisionFilterSelect');
const categoriesBody = document.getElementById('categoriesBody');
const noRows = document.getElementById('categoriesNoRows');
const addRowButton = document.getElementById('addCategoryRowButton');
const reviewButton = document.getElementById('reviewCategoryChangesButton');
const rowTemplate = document.getElementById('categoryRowTemplate');
const reviewDialog = document.getElementById('categoryReviewDialog');
const reviewSummary = document.getElementById('categoryReviewSummary');
const reviewChangesBody = document.getElementById('categoryReviewChangesBody');
const reviewError = document.getElementById('categoryReviewError');
const saveButton = document.getElementById('saveCategoryChangesButton');
const revertButton = document.getElementById('revertCategoryChangesButton');
const continueButton = document.getElementById('continueCategoryEditingButton');

const originalValues = new WeakMap();
let saveInProgress = false;

function categoryRows() {
  return Array.from(document.querySelectorAll('#categoriesBody tr.category-sheet-row'));
}

function rowInputs(row) {
  return Array.from(row.querySelectorAll('.category-cell-input'));
}

function inputValue(input) {
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
    input.value = values[input.dataset.field] ?? '';
  }
  updateRowDivision(row);
  refreshRowState(row);
}

function cloneValues(values) {
  return { ...values };
}

function updateRowDivision(row) {
  const divisionInput = findInput(row, 'division');
  row.dataset.division = divisionInput?.value?.trim() || '';
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

function displayValue(_row, _fieldName, value) {
  return value ?? '';
}

function categoryLabel(row, values) {
  return values.subcategory_country?.trim()
    || values.subcategory?.trim()
    || row.dataset.sourceRowNumber?.trim()
    || 'New category';
}

function collectChanges() {
  const changes = [];

  for (const row of categoryRows()) {
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
      sourceRowNumber: row.dataset.sourceRowNumber || '',
      categoryLabel: categoryLabel(row, current),
      values: current,
      cells,
    });
  }

  return changes;
}

function applyDivisionFilter() {
  if (!divisionFilter) return;

  const selectedDivision = divisionFilter.value;
  let visibleRows = 0;

  for (const row of categoryRows()) {
    const matches = row.dataset.newRow === 'true' || !selectedDivision || row.dataset.division === selectedDivision;
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

function handleCategoryInput(event) {
  const row = event.target.closest('.category-sheet-row');
  if (!row) return;

  updateRowDivision(row);
  refreshRowState(row);
  applyDivisionFilter();
  updateReviewButton();
}

function initializeRow(row) {
  originalValues.set(row, cloneValues(readRowValues(row)));
  updateRowDivision(row);
  refreshRowState(row);

  for (const input of rowInputs(row)) {
    input.addEventListener('input', handleCategoryInput);
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
      appendTextCell(row, change.categoryLabel);
      appendTextCell(row, cellChange.label);
      appendTextCell(row, cellChange.oldValue);
      appendTextCell(row, cellChange.newValue);
      reviewChangesBody.appendChild(row);
    }
  }

  const changedCells = changes.reduce((total, change) => total + change.cells.length, 0);
  reviewSummary.textContent = `${changes.length} category row${changes.length === 1 ? '' : 's'}, ${changedCells} changed cell${changedCells === 1 ? '' : 's'}.`;
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
  for (const row of categoryRows()) {
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
  applyDivisionFilter();
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
    const response = await fetch('/private/categories/save', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rows: changes.map((change) => ({
          mode: change.mode,
          sourceRowNumber: change.sourceRowNumber,
          values: change.values,
        })),
      }),
    });
    const payload = await response.json().catch(() => ({}));

    if (!response.ok) {
      throw new Error(payload.error || `Save failed with HTTP ${response.status}.`);
    }

    reviewDialog.close();
    window.location.assign(`/private/categories?message=${encodeURIComponent(payload.message || 'Category changes saved.')}`);
  } catch (error) {
    saveInProgress = false;
    reviewError.textContent = error.message;
    reviewError.hidden = false;
    saveButton.disabled = false;
  }
}

function nextSourceRowNumber() {
  return categoryRows()
    .map((row) => Number.parseInt(findInput(row, 'source_row_number')?.value ?? row.dataset.sourceRowNumber ?? '', 10))
    .filter((value) => Number.isInteger(value))
    .reduce((max, value) => Math.max(max, value), 2) + 1;
}

function addCategoryRow() {
  if (!categoriesBody || !rowTemplate) {
    return;
  }

  const row = rowTemplate.content.firstElementChild.cloneNode(true);
  findInput(row, 'source_row_number').value = String(nextSourceRowNumber());
  categoriesBody.appendChild(row);
  initializeRow(row);
  applyDivisionFilter();
  updateReviewButton();

  findInput(row, 'subcategory_country')?.focus();
  row.scrollIntoView({ block: 'nearest', inline: 'start' });
}

for (const row of categoryRows()) {
  initializeRow(row);
}

if (divisionFilter) {
  divisionFilter.addEventListener('change', applyDivisionFilter);
}

addRowButton?.addEventListener('click', addCategoryRow);
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

applyDivisionFilter();
updateReviewButton();
