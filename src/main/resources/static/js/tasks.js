import { initTaskTable } from './table-common.js';

const tasksTable = document.getElementById('tasksTable');

initTaskTable({
  tableId: 'tasksTable',
  theadId: 'tasksHead',
  tbodyId: 'tasksBody',
  dataUrl: '/app/tasks',
  actionStatusId: 'taskActionStatus',
  canRunTasks: tasksTable?.dataset.canRun === 'true',
  runUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/run`
});
