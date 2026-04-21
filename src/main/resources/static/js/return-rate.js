import { initMatrixTable } from './table-common.js';

function toPercent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '';
}

initMatrixTable({
  tableId: 'returnRateTable',
  theadId: 'returnRateHead',
  tbodyId: 'returnRateBody',
  dataUrl: '/app/returnRateTable',
  enableCellClick: false,
  valueFormatter: toPercent,
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'return-rate-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
