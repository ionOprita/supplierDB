import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'returnsTable',
  theadId: 'returnsHead',
  tbodyId: 'returnsBody',
  dataUrl: '/app/returnTable',
  detailsUrlBuilder: (pnk, month) => `/private/return-details?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'returnDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'returns-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
