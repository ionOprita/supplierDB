export function initMonthlyGMV(documentRoot = document) {
  const vendorFilter = documentRoot.getElementById('gmvVendorFilterSelect');
  const table = documentRoot.getElementById('monthlyGmvTable');
  const noRows = documentRoot.getElementById('gmvNoRows');
  if (!vendorFilter || !table || !noRows) return;

  const productRows = Array.from(table.querySelectorAll('tbody tr[data-vendor-id]'));
  function filterRows() {
    const vendorId = vendorFilter.value;
    let visibleCount = 0;
    for (const row of productRows) {
      row.hidden = vendorId !== '' && row.dataset.vendorId !== vendorId;
      if (!row.hidden) visibleCount++;
    }
    noRows.hidden = visibleCount > 0;
  }

  vendorFilter.addEventListener('change', filterRows);
  filterRows();
}

if (typeof document !== 'undefined') {
  initMonthlyGMV();
}
