import {createGMVDetails, fetchGMVForPeriod} from './gmv-details.js';

export function initMonthlyGMV(documentRoot = document) {
  const vendorFilter = documentRoot.getElementById('gmvVendorFilterSelect');
  const table = documentRoot.getElementById('monthlyGmvTable');
  const noRows = documentRoot.getElementById('gmvNoRows');
  const form = documentRoot.getElementById('gmvPeriodForm');
  const productSelect = documentRoot.getElementById('gmvProductSelect');
  const startDate = documentRoot.getElementById('gmvStartDate');
  const endDate = documentRoot.getElementById('gmvEndDate');
  const calculateButton = documentRoot.getElementById('gmvCalculateButton');
  const result = documentRoot.getElementById('gmvPeriodResult');
  const context = documentRoot.getElementById('gmvPeriodContext');
  const status = documentRoot.getElementById('gmvPeriodStatus');
  if (![vendorFilter, table, noRows, form, productSelect, startDate, endDate,
    calculateButton, result, context, status].every(Boolean)) return;

  const productRows = Array.from(table.querySelectorAll('tbody tr[data-vendor-id]'));
  const allProductsOption = productSelect.options[0].cloneNode(true);
  const productOptions = Array.from(productSelect.options).slice(1).map(option => option.cloneNode(true));
  const details = createGMVDetails(documentRoot);
  let requestGeneration = 0;
  let pending = false;
  let calculatedResponse = null;

  function selectedProduct() {
    return productOptions.find(option => option.value === productSelect.value);
  }

  function validDates() {
    endDate.setCustomValidity('');
    if (startDate.valueAsNumber > endDate.valueAsNumber) {
      endDate.setCustomValidity('End date must be on or after start date.');
    }
    return Boolean(startDate.value && endDate.value && startDate.checkValidity() && endDate.checkValidity());
  }

  function updateCalculateButton() {
    const datesValid = validDates();
    calculateButton.disabled = pending || !selectedProduct() || !datesValid;
  }

  function setStatus(message, isError = false) {
    status.textContent = message;
    status.classList.toggle('is-error', isError);
  }

  function updateReadiness() {
    updateCalculateButton();
    if (!selectedProduct()) {
      setStatus('Select a product to calculate GMV for a period.');
    } else if (!startDate.value || !endDate.value) {
      setStatus('Choose both start and end dates.');
    } else if (startDate.valueAsNumber > endDate.valueAsNumber) {
      setStatus('End date must be on or after start date.', true);
    } else if (!validDates()) {
      setStatus('Choose valid start and end dates.', true);
    } else {
      setStatus('');
    }
  }

  function clearResult() {
    ++requestGeneration;
    pending = false;
    result.hidden = true;
    result.textContent = '';
    result.setAttribute('aria-label', 'View contributing orders');
    context.textContent = '';
    calculatedResponse = null;
    details?.invalidateCalculated();
    setStatus('');
  }

  function filterProducts() {
    const selectedCode = productSelect.value;
    const vendorId = vendorFilter.value;
    const options = productOptions.filter(option => vendorId === '' || option.dataset.vendorId === vendorId);
    productSelect.replaceChildren(allProductsOption.cloneNode(true), ...options.map(option => option.cloneNode(true)));
    productSelect.value = options.some(option => option.value === selectedCode) ? selectedCode : '';
  }

  function filterRows() {
    const vendorId = vendorFilter.value;
    const productCode = productSelect.value;
    let visibleCount = 0;
    for (const row of productRows) {
      row.hidden = (vendorId !== '' && row.dataset.vendorId !== vendorId)
        || (productCode !== '' && row.dataset.productCode !== productCode);
      if (!row.hidden) visibleCount++;
    }
    noRows.hidden = visibleCount > 0;
  }

  vendorFilter.addEventListener('change', () => {
    clearResult();
    filterProducts();
    filterRows();
    updateReadiness();
  });
  productSelect.addEventListener('change', () => {
    clearResult();
    filterRows();
    updateReadiness();
  });
  for (const input of [startDate, endDate]) {
    for (const event of ['input', 'change']) {
      input.addEventListener(event, () => {
        clearResult();
        updateReadiness();
      });
    }
  }

  result.addEventListener('click', () => {
    if (calculatedResponse) {
      details?.openCalculated(result, calculatedResponse.data, calculatedResponse.label);
    }
  });

  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (pending) return;
    const product = selectedProduct();
    if (!product || !validDates()) {
      updateReadiness();
      form.reportValidity();
      return;
    }

    clearResult();
    const generation = requestGeneration;
    const request = {productCode: product.value, startDate: startDate.value, endDate: endDate.value};
    pending = true;
    setStatus('Calculating GMV…');
    updateCalculateButton();
    try {
      const data = await fetchGMVForPeriod(request);
      if (generation !== requestGeneration) return;
      calculatedResponse = {data, label: product.textContent.trim()};
      result.textContent = data.gmv;
      context.textContent = `${calculatedResponse.label} · ${request.startDate} to ${request.endDate}`;
      result.setAttribute('aria-label', `GMV ${data.gmv} for ${calculatedResponse.label}, ${request.startDate} to ${request.endDate}. View contributing orders.`);
      result.hidden = false;
      setStatus('');
    } catch (error) {
      if (generation !== requestGeneration) return;
      setStatus(error.message || 'Could not calculate GMV. Check your connection and try again.', true);
    } finally {
      if (generation === requestGeneration) {
        pending = false;
        updateCalculateButton();
      }
    }
  });

  filterProducts();
  filterRows();
  clearResult();
  updateReadiness();
}

if (typeof document !== 'undefined') {
  initMonthlyGMV();
}
