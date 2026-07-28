import { initTaskTable } from './table-common.js';

const tasksTable = document.getElementById('tasksTable');

initTaskTable({
  tableId: 'tasksTable',
  theadId: 'tasksHead',
  tbodyId: 'tasksBody',
  dataUrl: '/app/tasks',
  activeDataUrl: '/app/tasks/active',
  pausedDataUrl: '/app/tasks/paused',
  actionStatusId: 'taskActionStatus',
  schedulerStatusId: 'taskSchedulerStatus',
  canRunTasks: tasksTable?.dataset.canRun === 'true',
  runUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/run`,
  pauseUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/pause`,
  resumeUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/resume`,
  refreshIntervalMs: 30_000
});
