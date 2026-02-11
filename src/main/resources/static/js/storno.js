import { initMatrixTable } from './table-common.js';

initMatrixTable({
  tableId: 'stornoTable',
  theadId: 'stornoHead',
  tbodyId: 'stornoBody',
  dataUrl: '/app/stornoTable',
  detailsUrlBuilder: (pnk, month) => `/private/storno-details?pnk=${encodeURIComponent(pnk)}&month=${encodeURIComponent(month)}`,
  detailsWindowName: 'stornoDetails',
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'storno-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
