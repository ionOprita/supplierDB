import { initTaskTable } from './table-common.js';

initTaskTable({
  tableId: 'tasksTable',
  theadId: 'tasksHead',
  tbodyId: 'tasksBody',
  dataUrl: '/app/tasks'
});
