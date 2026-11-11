import {fetchJSON} from './common.js';
import {renderCategoryTable} from './profit-calculator-table.js';

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
  let groups = [];
  let requestVersion = 0;
  let retryAction = loadOptions;

  if (tableWrap) renderCategoryTable();

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
    const version = ++requestVersion;
    renderCategoryTable();
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
      renderCategoryTable(data.products);
      showStatus(data.products.length ? '' : 'No products found for this vendor group and category.');
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
  return loadOptions();
}

if (typeof document !== 'undefined') initProfitCalculator();
