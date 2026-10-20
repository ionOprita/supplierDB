import { initTaskTable, sanitizeFileNamePart } from './table-common.js';

export function initTaskErrorDialog(documentRoot = document) {
  const dialog = documentRoot.getElementById('taskErrorDialog');
  const title = documentRoot.getElementById('taskErrorTitle');
  const content = documentRoot.getElementById('taskErrorContent');
  const download = documentRoot.getElementById('taskErrorDownload');
  const close = documentRoot.getElementById('taskErrorClose');
  if (![dialog, title, content, download, close].every(Boolean)) return null;

  let selected = null;
  let opener = null;
  download.disabled = true;

  download.addEventListener('click', () => {
    if (!selected) return;
    const blob = new Blob([selected.error], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = documentRoot.createElement('a');
    try {
      anchor.href = url;
      anchor.download = `task-error-${sanitizeFileNamePart(selected.taskName) || 'task'}.txt`;
      documentRoot.body.appendChild(anchor);
      anchor.click();
    } finally {
      anchor.remove();
      URL.revokeObjectURL(url);
    }
  });

  close.addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    const replacement = opener?.isConnected ? opener
      : Array.from(documentRoot.querySelectorAll('.task-error-button'))
        .find(button => button.dataset.taskName === selected?.taskName);
    const focusTarget = replacement ?? documentRoot.getElementById('refreshBtn');
    selected = null;
    opener = null;
    content.textContent = '';
    download.disabled = true;
    focusTarget?.focus({ preventScroll: true });
  });

  return {
    open(taskName, error, trigger) {
      selected = { taskName, error };
      opener = trigger;
      title.textContent = `Task error: ${taskName}`;
      content.textContent = error;
      content.scrollTop = 0;
      content.scrollLeft = 0;
      download.disabled = false;
      if (!dialog.open) dialog.showModal();
    }
  };
}

const tasksTable = typeof document === 'undefined' ? null : document.getElementById('tasksTable');
if (tasksTable) {
  const errorDialog = initTaskErrorDialog();
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
    canRunTasks: tasksTable.dataset.canRun === 'true',
    runUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/run`,
    pauseUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/pause`,
    resumeUrlBuilder: (taskName) => `/admin/tasks/${encodeURIComponent(taskName)}/resume`,
    onViewError: (taskName, error, trigger) => errorDialog?.open(taskName, error, trigger),
    refreshIntervalMs: 10_000
  });
}
