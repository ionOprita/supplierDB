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
  let requestGeneration = 0;
  let pending = false;

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
    context.textContent = '';
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
      const response = await fetch(`/app/gmvForPeriod?${new URLSearchParams(request)}`, {
        method: 'GET', headers: {'Accept': 'application/json'}
      });
      if (generation !== requestGeneration) return;
      if (response.redirected || response.status === 401) {
        throw new Error('Your session expired. Sign in again and retry.');
      }
      if (!response.ok) {
        const message = response.status === 400 ? 'Choose a valid product and date range.'
          : response.status === 404 ? 'This product is no longer available.'
          : response.status === 403 ? 'You are not allowed to calculate GMV.'
          : 'Could not calculate GMV. Try again.';
        throw new Error(`${message} (HTTP ${response.status})`);
      }
      const contentType = response.headers.get('content-type') || '';
      if (!contentType.includes('application/json')) {
        throw new Error('Your session may have expired. Sign in again and retry.');
      }
      const data = await response.json();
      if (generation !== requestGeneration) return;
      if (typeof data?.gmv !== 'string' || data.productCode !== request.productCode
        || data.startDate !== request.startDate || data.endDate !== request.endDate) {
        throw new Error('The server returned an invalid GMV result. Try again.');
      }
      result.textContent = data.gmv;
      context.textContent = `${product.textContent.trim()} · ${request.startDate} to ${request.endDate}`;
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
