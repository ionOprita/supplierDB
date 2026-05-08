const vendorFilter = document.getElementById('productVendorFilterSelect');
const rows = Array.from(document.querySelectorAll('#productsBody tr[data-vendor]'));
const noRows = document.getElementById('productsNoRows');

function applyVendorFilter() {
  if (!vendorFilter) return;

  const selectedVendor = vendorFilter.value;
  let visibleRows = 0;

  for (const row of rows) {
    const matches = !selectedVendor || row.dataset.vendor === selectedVendor;
    row.hidden = !matches;
    if (matches) visibleRows += 1;
  }

  if (noRows) {
    noRows.hidden = visibleRows !== 0;
  }
}

if (vendorFilter) {
  vendorFilter.addEventListener('change', applyVendorFilter);
  applyVendorFilter();
}
