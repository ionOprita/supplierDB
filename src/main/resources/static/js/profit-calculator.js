import {fetchJSON} from './common.js';
import {renderCategoryTable} from './profit-calculator-table.js';
import {OverrideDrafts} from './profit-calculator-overrides.js';

function populateSelect(select, values, emptyLabel) {
  select.innerHTML = '';
  for (const value of values.length ? values : ['']) {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = value || emptyLabel;
    select.appendChild(option);
  }
  select.disabled = values.length === 0;
}

export function initProfitCalculator() {
  const page = document.getElementById('profitCalculatorPage');
  if (!page) return;
  const groupSelect = document.getElementById('profitVendorGroupSelect');
  const categorySelect = document.getElementById('profitCategorySelect');
  const status = document.getElementById('profitCalculatorStatus');
  const retry = document.getElementById('profitCalculatorRetry');
  const tableWrap = document.getElementById('profitCategoryWrap');
  const reviewButton = document.getElementById('profitOverrideReview');
  const revertButton = document.getElementById('profitOverrideRevert');
  const editStatus = document.getElementById('profitOverrideStatus');
  const reviewDialog = document.getElementById('profitOverrideDialog');
  const reviewBody = document.getElementById('profitOverrideReviewBody');
  const reviewError = document.getElementById('profitOverrideReviewError');
  const saveButton = document.getElementById('profitOverrideSave');
  const continueButton = document.getElementById('profitOverrideContinue');
  const drafts = new OverrideDrafts();
  let groups = [];
  let requestVersion = 0;
  let retryAction = loadOptions;
  let products = [];
  let tableView;
  let previewVersion = 0;
  let previewTimer;
  let previewPending = false;
  let previewError = false;
  let saving = false;

  if (tableWrap) tableView = renderCategoryTable();

  function currentCodes() { return new Set(products.map(product => product.productCode)); }

  function setEditStatus(message) {
    if (!editStatus) return;
    editStatus.textContent = message;
    editStatus.hidden = !message;
  }

  function refreshEditingControls() {
    const invalid = drafts.hasErrors();
    if (reviewButton) reviewButton.disabled = saving || !drafts.hasChanges() || invalid || previewPending || previewError;
    if (revertButton) revertButton.disabled = saving || !drafts.hasChanges();
    if (saveButton) saveButton.disabled = saving || !drafts.hasChanges() || invalid || previewPending || previewError;
    if (continueButton) continueButton.disabled = saving;
    const reviewRevert = document.getElementById('profitOverrideReviewRevert');
    if (reviewRevert) reviewRevert.disabled = saving;
    tableView?.refreshInputs(drafts);
    tableView?.setDisabled(saving);
    if (saving) {
      groupSelect.disabled = true;
      if (categorySelect) categorySelect.disabled = true;
    }
  }

  function invalidatePreview() {
    clearTimeout(previewTimer);
    ++previewVersion;
    previewPending = false;
    previewError = false;
  }

  async function postJSON(url, body) {
    const response = await fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json', Accept: 'application/json'},
      body: JSON.stringify(body)});
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.error || `Request failed with HTTP ${response.status}.`);
      error.status = response.status;
      error.productCode = payload.productCode;
      throw error;
    }
    return payload;
  }

  function selectionBody(changes) {
    return {vendorGroup: groupSelect.value, category: categorySelect.value, changes};
  }

  async function preview(version) {
    if (version !== previewVersion || saving) return;
    const changes = drafts.changes(currentCodes());
    try {
      const data = await postJSON('/app/profitCalculatorPreview', selectionBody(changes));
      if (version !== previewVersion) return;
      if (!Array.isArray(data.products)) throw new Error('Invalid preview response');
      // Updating only result cells preserves the focused input and its selection/caret.
      tableView?.updateMetrics(data.products);
      previewError = false;
      setEditStatus(drafts.hasErrors() ? 'Fix the invalid override inputs before saving.' : 'Unsaved changes. Preview updated.');
    } catch (error) {
      if (version !== previewVersion) return;
      previewError = true;
      setEditStatus('Could not preview overrides. Retry preview before saving.');
      retryAction = () => schedulePreview(true);
      retry.hidden = false;
    } finally {
      if (version === previewVersion) {
        previewPending = false;
        refreshEditingControls();
      }
    }
  }

  function schedulePreview(immediate = false) {
    invalidatePreview();
    retry.hidden = true;
    const codes = currentCodes();
    tableView?.updateMetrics(products, drafts.pendingRows(products));
    if (drafts.hasErrors(codes)) {
      setEditStatus('Fix the invalid override inputs before saving.');
      refreshEditingControls();
      return;
    }
    if (!drafts.changes(codes).length) {
      setEditStatus(drafts.hasErrors() ? 'Fix the invalid override inputs before saving.'
        : drafts.hasChanges() ? 'Unsaved changes in another category.' : '');
      refreshEditingControls();
      return;
    }
    previewPending = true;
    setEditStatus('Updating preview…');
    refreshEditingControls();
    const version = previewVersion;
    if (immediate) return preview(version);
    previewTimer = setTimeout(() => preview(version), 200);
  }

  function renderProducts() {
    tableView = renderCategoryTable(products, {
      getOverrideValue: (product, field) => drafts.input(product, field), saving,
      onOverrideInput(productCode, field, raw) {
        drafts.edit(productCode, field.key, raw);
        schedulePreview();
      }
    });
    refreshEditingControls();
  }

  function closeReview() {
    if (reviewDialog?.close) reviewDialog.close();
    else reviewDialog?.removeAttribute('open');
  }

  function showReview() {
    if (reviewButton?.disabled) return;
    reviewBody.replaceChildren();
    for (const change of drafts.reviewRows()) {
      const row = document.createElement('tr');
      for (const text of [change.product, change.field, change.before, change.after]) {
        const value = document.createElement('td');
        value.textContent = text;
        row.appendChild(value);
      }
      reviewBody.appendChild(row);
    }
    reviewError.textContent = '';
    reviewError.hidden = true;
    if (reviewDialog.showModal) reviewDialog.showModal();
    else reviewDialog.setAttribute('open', '');
  }

  async function saveOverrides() {
    if (saveButton.disabled || saving) return;
    const changes = drafts.changes();
    saving = true;
    invalidatePreview();
    refreshEditingControls();
    reviewError.hidden = true;
    try {
      const result = await postJSON('/app/profitCalculatorOverrides', selectionBody(changes));
      if (result.saved !== true) throw new Error('The server did not confirm that overrides were saved.');
      drafts.clear();
      closeReview();
      setEditStatus('Overrides saved.');
      // A reload failure must never repeat the successful save request.
      await loadCategory();
    } catch (error) {
      reviewError.textContent = error.status === 409
        ? `Saved overrides changed${error.productCode ? ` for ${error.productCode}` : ''}. Your edits are still here. Revert to load the latest saved values before editing again.`
        : `Could not save overrides. Your edits are still here. ${error.message}`;
      reviewError.hidden = false;
    } finally {
      saving = false;
      groupSelect.disabled = groups.length === 0;
      if (categorySelect) categorySelect.disabled = !groups.find(group => group.vendorGroup === groupSelect.value)?.categories?.length;
      refreshEditingControls();
    }
  }

  async function revertOverrides() {
    if (saving) return;
    drafts.clear();
    closeReview();
    setEditStatus('');
    await loadCategory();
  }

  function showStatus(message) {
    status.textContent = message;
    status.hidden = !message;
  }

  function updateUrl() {
    const url = new URL(window.location.href);
    if (groupSelect.value) url.searchParams.set('vendorGroup', groupSelect.value);
    else url.searchParams.delete('vendorGroup');
    if (categorySelect?.value) url.searchParams.set('category', categorySelect.value);
    else url.searchParams.delete('category');
    window.history.replaceState(null, '', url);
  }

  function updateCategories(requestedCategory) {
    if (!categorySelect) return;
    const group = groups.find(item => item.vendorGroup === groupSelect.value);
    const categories = group?.categories || [];
    populateSelect(categorySelect, categories, group ? 'No categories available' : 'Select a vendor group first');
    categorySelect.value = categories.includes(requestedCategory) ? requestedCategory : (categories[0] || '');
    showStatus(group && !categories.length ? 'No categories found for this vendor group.' : '');
  }

  async function loadCategory() {
    if (!tableWrap) return;
    invalidatePreview();
    const version = ++requestVersion;
    products = [];
    tableView = renderCategoryTable();
    refreshEditingControls();
    retryAction = loadCategory;
    retry.hidden = true;
    retry.disabled = true;
    const vendorGroup = groupSelect.value;
    const category = categorySelect?.value;
    if (!vendorGroup || !category) {
      tableWrap.setAttribute('aria-busy', 'false');
      return;
    }
    showStatus('Loading products…');
    tableWrap.setAttribute('aria-busy', 'true');
    try {
      const params = new URLSearchParams({vendorGroup, category});
      const data = await fetchJSON(`/app/profitCalculatorCategory?${params}`);
      if (version !== requestVersion) return;
      if (!Array.isArray(data.products)) throw new Error('Invalid category products response');
      products = data.products;
      drafts.remember(products);
      renderProducts();
      showStatus(data.products.length ? '' : 'No products found for this vendor group and category.');
      if (drafts.changes(currentCodes()).length || drafts.hasErrors(currentCodes())) return schedulePreview(true);
      if (editStatus?.textContent !== 'Overrides saved.') {
        setEditStatus(drafts.hasErrors() ? 'Fix the invalid override inputs before saving.'
          : drafts.hasChanges() ? 'Unsaved changes in another category.' : '');
      }
    } catch (error) {
      if (version !== requestVersion) return;
      console.error(error);
      showStatus('Could not load products. Please try again.');
      retry.hidden = false;
    } finally {
      if (version === requestVersion) {
        tableWrap.setAttribute('aria-busy', 'false');
        retry.disabled = false;
      }
    }
  }

  async function loadOptions() {
    invalidatePreview();
    const version = ++requestVersion;
    retryAction = loadOptions;
    retry.hidden = true;
    retry.disabled = true;
    if (tableWrap) {
      renderCategoryTable();
      tableWrap.setAttribute('aria-busy', 'true');
    }
    populateSelect(groupSelect, [], 'Loading vendor groups…');
    if (categorySelect) populateSelect(categorySelect, [], 'Select a vendor group first');
    showStatus('Loading vendor groups…');
    try {
      const data = await fetchJSON('/app/profitCalculatorOptions');
      if (version !== requestVersion) return;
      groups = data.vendorGroups || [];
      const params = new URLSearchParams(window.location.search);
      const groupNames = groups.map(item => item.vendorGroup);
      const requested = params.get('vendorGroup');
      populateSelect(groupSelect, groupNames, 'No vendor groups available');
      groupSelect.value = groupNames.includes(requested) ? requested
        : groupNames.includes(data.defaultVendorGroup) ? data.defaultVendorGroup : (groupNames[0] || '');
      showStatus('');
      updateCategories(params.get('category'));
      if (!groups.length) showStatus('No vendor groups found.');
      updateUrl();
      return loadCategory();
    } catch (error) {
      if (version !== requestVersion) return;
      console.error(error);
      populateSelect(groupSelect, [], 'Unable to load vendor groups');
      showStatus('Could not load vendor groups and categories. Please try again.');
      retry.hidden = false;
    } finally {
      if (version === requestVersion) {
        retry.disabled = false;
        tableWrap?.setAttribute('aria-busy', 'false');
      }
    }
  }

  groupSelect.addEventListener('change', () => {
    updateCategories();
    updateUrl();
    loadCategory();
  });
  categorySelect?.addEventListener('change', () => {
    updateUrl();
    loadCategory();
  });
  retry.addEventListener('click', () => retryAction());
  reviewButton?.addEventListener('click', showReview);
  revertButton?.addEventListener('click', revertOverrides);
  document.getElementById('profitOverrideReviewRevert')?.addEventListener('click', revertOverrides);
  saveButton?.addEventListener('click', saveOverrides);
  continueButton?.addEventListener('click', closeReview);
  reviewDialog?.addEventListener('cancel', event => { if (saving) event.preventDefault(); });
  window.addEventListener('beforeunload', event => {
    if (!drafts.hasChanges() || saving) return;
    event.preventDefault();
    event.returnValue = '';
  });
  return loadOptions();
}

if (typeof document !== 'undefined') initProfitCalculator();
