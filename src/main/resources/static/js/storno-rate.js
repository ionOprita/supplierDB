import { initMatrixTable } from './table-common.js';

function toPercent(value) {
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '';
}

initMatrixTable({
  tableId: 'stornoRateTable',
  theadId: 'stornoRateHead',
  tbodyId: 'stornoRateBody',
  dataUrl: '/app/stornoRateTable',
  enableCellClick: false,
  valueFormatter: toPercent,
  csvButtonId: 'downloadCsvBtn',
  csvFilenamePrefix: 'storno-rate-table',
  vendorFilterSelectId: 'vendorFilterSelect',
  monthFromSelectId: 'monthFromSelect',
  monthToSelectId: 'monthToSelect',
  monthResetButtonId: 'monthFilterResetBtn'
});
