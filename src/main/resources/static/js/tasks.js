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
  nameFilterInputId: 'taskNameFilter',
  statusFilterSelectId: 'taskStatusFilter',
  laneFilterSelectId: 'taskLaneFilter',
  pauseAllButtonId: 'pauseAllBtn',
  pauseAllUrl: '/admin/tasks/pause-all',
  resumeAllButtonId: 'resumeAllBtn',
  resumeAllUrl: '/admin/tasks/resume-all',
  canRunTasks: tasksTable?.dataset.canRun === 'true',
  runUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/run`,
  pauseUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/pause`,
  resumeUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/resume`,
  refreshIntervalMs: 10_000
});
