import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'returnsTable',
  theadId: 'returnsHead',
  tbodyId: 'returnsBody',
  dataUrl: '/returnTable',
  detailsUrlBuilder: (pnk, month) => `/return-details.html?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'returnDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'returns-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
