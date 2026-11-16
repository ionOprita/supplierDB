import {fetchJSON} from './common.js';

export const tariffFields = [
  {key: 'sizeCategory', label: 'Size category', text: true},
  {key: 'maxLengthMm', label: 'Maximum length (mm)', positive: true},
  {key: 'maxWidthMm', label: 'Maximum width (mm)', positive: true},
  {key: 'maxHeightMm', label: 'Maximum height (mm)', positive: true},
  {key: 'maxGirthMm', label: 'Maximum girth (mm)', positive: true},
  {key: 'weightBandKg', label: 'Weight band (kg)', positive: true},
  {key: 'orderProcessingFeeRon', label: 'Order processing fee (RON)'},
  {key: 'returnProcessingFeeRon', label: 'Return processing fee (RON)'},
  {key: 'removalFeeRon', label: 'Withdrawal fee (RON)'},
  {key: 'disposalFeeRon', label: 'Disposal fee (RON)'},
  {key: 'weightRangeKg', label: 'Weight range (kg)', positive: true, optional: true},
  {key: 'extraOrderFeeRon', label: 'Extra order processing fee (RON)', optional: true},
  {key: 'extraReturnFeeRon', label: 'Extra return processing fee (RON)', optional: true},
  {key: 'extraRemovalFeeRon', label: 'Extra withdrawal fee (RON)', optional: true},
  {key: 'extraDisposalFeeRon', label: 'Extra disposal fee (RON)', optional: true}
];

export function parseTariffInput(field, raw) {
  const text = String(raw ?? '').trim();
  if (!text) return field.optional ? {value: null, error: ''} : {error: 'A value is required.'};
  if (field.text) return text.length <= 200
    ? {value: text, error: ''} : {error: 'Use at most 200 characters.'};
  if (text.length > 64) return {error: 'Use at most 64 decimal characters.'};
  if (!/^(?:\d+(?:\.\d*)?|\.\d+)$/.test(text)) {
    return {error: 'Enter a nonnegative decimal using a decimal point.'};
  }
  const [integer, fraction = ''] = text.split('.');
  const whole = integer.replace(/^0+(?=\d)/, '') || '0';
  const tail = fraction.replace(/0+$/, '');
  const value = tail ? `${whole}.${tail}` : whole;
  if (value.length > 64) return {error: 'Use at most 64 decimal characters.'};
  if (field.positive && value === '0') return {error: 'The value must be greater than zero.'};
  return {value, error: ''};
}

function validateTariffs(data) {
  if (!Array.isArray(data?.tariffs) || data.tariffs.length !== 12) throw new Error('Invalid tariff response');
  const ids = new Set();
  for (const row of data.tariffs) {
    if (!Number.isSafeInteger(row?.id) || row.id < 1 || row.id > 12 || ids.has(row.id)
        || !Number.isSafeInteger(row.revision) || row.revision < 1) throw new Error('Invalid tariff response');
    ids.add(row.id);
    for (const field of tariffFields) {
      const value = row[field.key];
      if (!(typeof value === 'string' || field.optional && value === null)
          || parseTariffInput(field, value).error) throw new Error('Invalid tariff response');
    }
  }
  return [...data.tariffs].sort((a, b) => a.id - b.id);
}

export function initFbeTariffs() {
  if (!document.getElementById('profitFbeTariffsPage')) return;
  const body = document.getElementById('fbeTariffBody');
  const wrap = document.getElementById('fbeTariffWrap');
  const status = document.getElementById('fbeTariffStatus');
  const retry = document.getElementById('fbeTariffRetry');
  const review = document.getElementById('fbeTariffReview');
  const revert = document.getElementById('fbeTariffRevert');
  const dialog = document.getElementById('fbeTariffDialog');
  const reviewBody = document.getElementById('fbeTariffReviewBody');
  const reviewError = document.getElementById('fbeTariffReviewError');
  const save = document.getElementById('fbeTariffSave');
  const continueEditing = document.getElementById('fbeTariffContinue');
  const reviewRevert = document.getElementById('fbeTariffReviewRevert');
  let entries = [];
  let controls = [];
  let loading = false;
  let saving = false;
  let loadVersion = 0;
  let savedReadback = false;
  const endpoint = '/app/profitCalculatorFbeTariffs';

  function fieldState(entry, field) {
    const parsed = parseTariffInput(field, entry.inputs[field.key]);
    const original = parseTariffInput(field, entry.saved[field.key]);
    return {...parsed, dirty: Boolean(parsed.error) || parsed.value !== original.value};
  }

  function changedFields(entry) { return tariffFields.filter(field => fieldState(entry, field).dirty); }
  function dirty() { return entries.some(entry => changedFields(entry).length); }
  function invalid() { return entries.some(entry => tariffFields.some(field => fieldState(entry, field).error)); }

  function setStatus(message) {
    status.textContent = message;
    status.hidden = !message;
  }

  function refresh() {
    const busy = loading || saving;
    const changed = dirty();
    review.disabled = busy || !changed || invalid();
    save.disabled = review.disabled;
    revert.disabled = busy || !changed;
    reviewRevert.disabled = revert.disabled;
    continueEditing.disabled = busy;
    retry.disabled = busy;
    for (const control of controls) {
      const state = fieldState(control.entry, control.field);
      control.input.disabled = busy;
      control.input.setCustomValidity(state.error || '');
      control.input.setAttribute('aria-invalid', state.error ? 'true' : 'false');
      control.cell.classList.toggle('is-dirty', state.dirty);
      control.error.textContent = state.error || '';
      control.error.hidden = !state.error;
    }
  }

  function element(tag, text, className) {
    const result = document.createElement(tag);
    if (text != null) result.textContent = text;
    if (className) result.className = className;
    return result;
  }

  function renderRows() {
    body.replaceChildren();
    controls = [];
    for (const entry of entries) {
      const row = element('tr');
      row.dataset.tariffId = entry.saved.id;
      const identifier = element('th', entry.saved.id, 'fbe-tariff-id');
      identifier.scope = 'row';
      row.appendChild(identifier);
      for (const field of tariffFields) {
        const cell = element('td', null, field.text ? 'fbe-tariff-category' : '');
        const input = element('input');
        input.type = 'text';
        input.className = 'fbe-tariff-input';
        input.autocomplete = 'off';
        if (!field.text) input.inputMode = 'decimal';
        input.value = entry.inputs[field.key];
        input.dataset.field = field.key;
        input.dataset.tariffId = entry.saved.id;
        input.setAttribute('aria-label', `${field.label}, tariff ${entry.saved.id}, ${entry.saved.sizeCategory}`);
        const error = element('span', '', 'fbe-tariff-error');
        error.id = `fbeTariffError-${entry.saved.id}-${field.key}`;
        error.hidden = true;
        input.setAttribute('aria-describedby', error.id);
        input.addEventListener('input', () => {
          entry.inputs[field.key] = input.value;
          refresh();
          setStatus(invalid() ? 'Fix the highlighted tariff values before saving.'
            : dirty() ? 'Unsaved tariff changes.' : '');
        });
        cell.appendChild(input);
        cell.appendChild(error);
        row.appendChild(cell);
        controls.push({entry, field, cell, input, error});
      }
      body.appendChild(row);
    }
    refresh();
  }

  function closeReview() {
    if (dialog.close) dialog.close();
    else dialog.removeAttribute('open');
  }

  async function loadTariffs() {
    if (loading) return;
    const version = ++loadVersion;
    loading = true;
    retry.hidden = true;
    wrap.setAttribute('aria-busy', 'true');
    setStatus(savedReadback ? 'Tariffs saved. Reloading tariffs…' : 'Loading FBE tariffs…');
    refresh();
    try {
      const rows = validateTariffs(await fetchJSON(endpoint));
      if (version !== loadVersion) return;
      entries = rows.map(saved => ({saved, inputs: Object.fromEntries(
        tariffFields.map(field => [field.key, saved[field.key] ?? '']))}));
      renderRows();
      closeReview();
      setStatus(savedReadback ? 'Tariffs saved.' : '');
      savedReadback = false;
    } catch (error) {
      if (version !== loadVersion) return;
      setStatus(savedReadback
        ? 'Tariffs were saved, but could not be reloaded. Retry loading tariffs.'
        : 'Could not load FBE tariffs. Your current edits are retained. Retry loading tariffs.');
      retry.hidden = false;
    } finally {
      if (version === loadVersion) {
        loading = false;
        wrap.setAttribute('aria-busy', 'false');
        refresh();
      }
    }
  }

  function showReview() {
    if (review.disabled) return;
    reviewBody.replaceChildren();
    for (const entry of entries) {
      for (const field of changedFields(entry)) {
        const row = element('tr');
        for (const text of [`#${entry.saved.id} ${entry.saved.sizeCategory}`, field.label,
          entry.saved[field.key] ?? 'Blank', fieldState(entry, field).value ?? 'Blank']) {
          row.appendChild(element('td', text));
        }
        reviewBody.appendChild(row);
      }
    }
    reviewError.hidden = true;
    reviewError.textContent = '';
    if (dialog.showModal) dialog.showModal();
    else dialog.setAttribute('open', '');
  }

  async function saveTariffs() {
    if (save.disabled || saving) return;
    const changes = entries.flatMap(entry => {
      const fields = changedFields(entry);
      return fields.length ? [{id: entry.saved.id, expectedRevision: entry.saved.revision,
        values: Object.fromEntries(fields.map(field => [field.key, fieldState(entry, field).value]))}] : [];
    });
    saving = true;
    refresh();
    reviewError.hidden = true;
    try {
      const response = await fetch(endpoint, {method: 'POST',
        headers: {'Content-Type': 'application/json', Accept: 'application/json'}, body: JSON.stringify({changes})});
      const result = await response.json().catch(() => ({}));
      if (!response.ok) {
        const error = new Error(result.error || `HTTP ${response.status}`);
        error.status = response.status;
        error.id = result.id;
        throw error;
      }
      if (result.saved !== true) throw new Error('The server did not confirm that the tariffs were saved.');
      // A committed save is finished; all retries after this point only reload.
      entries = [];
      renderRows();
      closeReview();
      savedReadback = true;
      await loadTariffs();
    } catch (error) {
      reviewError.textContent = error.status === 409
        ? `Saved tariffs changed${error.id ? ` for tariff ${error.id}` : ''}. Your edits are still here. Revert to load the latest tariffs before editing again.`
        : `Could not save tariffs. Your edits are still here. ${error.message}`;
      reviewError.hidden = false;
    } finally {
      saving = false;
      refresh();
    }
  }

  review.addEventListener('click', showReview);
  save.addEventListener('click', saveTariffs);
  continueEditing.addEventListener('click', closeReview);
  revert.addEventListener('click', () => { if (!revert.disabled) loadTariffs(); });
  reviewRevert.addEventListener('click', () => {
    if (reviewRevert.disabled) return;
    closeReview();
    loadTariffs();
  });
  retry.addEventListener('click', () => { if (!retry.disabled) loadTariffs(); });
  window.addEventListener('beforeunload', event => {
    if (!dirty()) return;
    event.preventDefault();
    event.returnValue = '';
  });
  loadTariffs();
}

if (typeof document !== 'undefined') initFbeTariffs();
