/**
 * table-common.js
 * Shared utilities to render a month-matrix table with a sticky header and first two columns,
 * plus a delegated click handler that opens a details window.
 */
import {fetchJSON, formatDuration, formatLocalDateTime} from "./common.js";

// --- helpers -------------------------------------------------------------

export function parseKey(key) {
  const extractField = (fieldName) => {
    const m = key.match(new RegExp(`${fieldName}=(.*?)(?=, [a-zA-Z0-9_]+=|\\]$)`));
    return m ? m[1].trim() : '';
  };

  const pnkMatch = key.match(/pnk=([^,\]]+)/);
  const nameMatch = key.match(/name=([^,\]]+)/);
  const vendorNameMatch = key.match(/vendorName=([^,\]]+)/);
  const pnk = extractField('pnk') || (pnkMatch ? pnkMatch[1].trim() : '');
  const name = extractField('name') || (nameMatch ? nameMatch[1].trim() : key);
  const vendorNameRaw = extractField('vendorName') || (vendorNameMatch ? vendorNameMatch[1].trim() : '');
  return {
    pnk,
    name,
    vendorName: vendorNameRaw === 'null' ? '' : vendorNameRaw
  };
}

export function toRows(jsonMap) {
  return Object.entries(jsonMap).map(([key, monthsObj]) => {
    const { pnk, name, vendorName } = parseKey(key);
    return { key, pnk, name, vendorName, months: monthsObj };
  });
}

export function collectAllMonths(rows) {
  const set = new Set();
  for (const row of rows) {
    for (const m of Object.keys(row.months)) set.add(m);
  }
  return [...set].sort();
}


// --- csv export ----------------------------------------------------------

export function csvEscape(value) {
  const normalized = String(value ?? '').replace(/\r?\n|\r/g, ' ').trim();
  if (/[",\n]/.test(normalized)) {
    return `"${normalized.replace(/"/g, '""')}"`;
  }
  return normalized;
}

export function tableToCsv(table) {
  if (!table) return '';
  const rows = Array.from(table.querySelectorAll('tr'));
  if (!rows.length) return '';

  return rows
    .map((row) => Array.from(row.cells).map((cell) => csvEscape(cell.textContent ?? '')).join(','))
    .join('\r\n');
}

export function downloadCsvFromTable(table, fileName) {
  const csv = tableToCsv(table);
  if (!csv) return false;

  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
  return true;
}

export function sanitizeFileNamePart(value) {
  return String(value ?? '')
    .trim()
    .replace(/[\\/:*?"<>|]+/g, '_')
    .replace(/\s+/g, '-');
}

export function bindTableCsvDownload({ buttonId, tableId, filePrefix = 'table', fileNameBuilder }) {
  const button = document.getElementById(buttonId);
  const table = document.getElementById(tableId);
  if (!button || !table) return;

  button.addEventListener('click', () => {
    const datePart = new Date().toISOString().slice(0, 10);
    let fileName = '';
    if (typeof fileNameBuilder === 'function') {
      fileName = String(fileNameBuilder({ datePart, filePrefix }) ?? '');
    }
    if (!fileName) {
      fileName = `${filePrefix}-${datePart}.csv`;
    } else if (!fileName.toLowerCase().endsWith('.csv')) {
      fileName = `${fileName}.csv`;
    }
    downloadCsvFromTable(table, fileName);
  });
}

// --- small shared DOM builders ------------------------------------------

function buildHeaderRow(labels) {
  const tr = document.createElement('tr');
  for (const label of labels) {
    const th = document.createElement('th');
    th.textContent = label;
    tr.appendChild(th);
  }
  return tr;
}

function renderTbody(tbodyEl, items, renderRowFn) {
  tbodyEl.innerHTML = '';
  const frag = document.createDocumentFragment();
  for (const item of items) {
    frag.appendChild(renderRowFn(item));
  }
  tbodyEl.appendChild(frag);
}

// --- rendering -----------------------------------------------------------

export function renderHeader(headEl, months) {
  const tr = buildHeaderRow(['Name', 'PNK', ...months]);
  headEl.innerHTML = '';
  headEl.appendChild(tr);
}

export function renderBody(tbodyEl, rows, months, tableEl, options = {}) {
  const valueFormatter = typeof options.valueFormatter === 'function'
    ? options.valueFormatter
    : (val) => (Number.isFinite(val) ? String(val) : '');
  const enableCellClick = options.enableCellClick !== false;

  function renderRow(row) {
    const tr = document.createElement('tr');

    const tdName = document.createElement('td');
    tdName.textContent = row.name;
    tr.appendChild(tdName);
    const tdPnk = document.createElement('td');
    tdPnk.textContent = row.pnk;
    tr.appendChild(tdPnk);

    for (const m of months) {
      const td = document.createElement('td');
      const rawValue = row.months[m];
      td.textContent = valueFormatter(rawValue, row, m);
      if (enableCellClick) {
        td.dataset.clickable = "true";
        td.dataset.pnk = row.pnk;
        td.dataset.month = m;
      }
      tr.appendChild(td);
    }
    return tr;
    }

  renderTbody(tbodyEl, rows, renderRow);
  applyStickyOffsets(tableEl);
}

export function applyStickyOffsets(table) {
  if (!table) return;

  const ths = table.tHead?.rows?.[0]?.cells ?? [];
  if (ths.length >= 1) ths[0].classList.add('sticky-col-1');
  if (ths.length >= 2) ths[1].classList.add('sticky-col-2');

  for (const row of table.tBodies[0].rows) {
    if (row.cells.length >= 1) row.cells[0].classList.add('sticky-col-1');
    if (row.cells.length >= 2) row.cells[1].classList.add('sticky-col-2');
  }

  const firstColCell =
    (ths.length ? ths[0] : null) ||
    (table.tBodies[0].rows[0]?.cells[0] ?? null);

  if (!firstColCell) return;

  const firstColWidth = firstColCell.getBoundingClientRect().width;
  const stickyCol2 = table.querySelectorAll('.sticky-col-2');
  for (const el of stickyCol2) {
    el.style.left = `${firstColWidth}px`;
  }
}

export function bindVendorFilter({ selectId, rows, onChange, allLabel = 'All vendors' }) {
  const select = document.getElementById(selectId);
  if (!select) return;

  const vendors = [...new Set(rows.map((row) => row.vendorName).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b));

  select.innerHTML = '';
  const allOption = document.createElement('option');
  allOption.value = '';
  allOption.textContent = allLabel;
  select.appendChild(allOption);

  for (const vendor of vendors) {
    const opt = document.createElement('option');
    opt.value = vendor;
    opt.textContent = vendor;
    select.appendChild(opt);
  }

  select.addEventListener('change', () => onChange(select.value));
}

export function bindMonthRangeFilter({
  fromSelectId,
  toSelectId,
  resetButtonId,
  months,
  onChange,
  fromAllLabel = 'From start',
  toAllLabel = 'To end'
}) {
  const fromSelect = fromSelectId ? document.getElementById(fromSelectId) : null;
  const toSelect = toSelectId ? document.getElementById(toSelectId) : null;
  const resetButton = resetButtonId ? document.getElementById(resetButtonId) : null;

  if (!fromSelect && !toSelect && !resetButton) return;

  const populate = (select, allLabel) => {
    if (!select) return;
    select.innerHTML = '';

    const allOption = document.createElement('option');
    allOption.value = '';
    allOption.textContent = allLabel;
    select.appendChild(allOption);

    for (const m of months) {
      const opt = document.createElement('option');
      opt.value = m;
      opt.textContent = m;
      select.appendChild(opt);
    }
  };

  populate(fromSelect, fromAllLabel);
  populate(toSelect, toAllLabel);

  if (fromSelect) fromSelect.value = '';
  if (toSelect) toSelect.value = '';

  const normalize = (changedSide) => {
    const fromMonth = fromSelect?.value || '';
    const toMonth = toSelect?.value || '';
    if (!fromMonth || !toMonth) return;

    const fromIdx = months.indexOf(fromMonth);
    const toIdx = months.indexOf(toMonth);
    if (fromIdx < 0 || toIdx < 0 || fromIdx <= toIdx) return;

    if (changedSide === 'from' && toSelect) {
      toSelect.value = fromMonth;
    } else if (changedSide === 'to' && fromSelect) {
      fromSelect.value = toMonth;
    }
  };

  const emitChange = () => {
    onChange({
      fromMonth: fromSelect?.value || '',
      toMonth: toSelect?.value || ''
    });
  };

  fromSelect?.addEventListener('change', () => {
    normalize('from');
    emitChange();
  });

  toSelect?.addEventListener('change', () => {
    normalize('to');
    emitChange();
  });

  resetButton?.addEventListener('click', () => {
    if (fromSelect) fromSelect.value = '';
    if (toSelect) toSelect.value = '';
    emitChange();
  });

  emitChange();
}

// --- main init -----------------------------------------------------------

/**
 * Initialise a matrix table page.
 * @param {Object} cfg
 * @param {string} cfg.tableId - DOM id of <table>
 * @param {string} cfg.theadId - DOM id of <thead>
 * @param {string} cfg.tbodyId - DOM id of <tbody>
 * @param {string} cfg.dataUrl - endpoint to load the matrix JSON from
 * @param {function} cfg.detailsUrlBuilder - (pnk, month) => string details URL
 * @param {string} [cfg.detailsWindowName] - name for the popup window
 * @param {string} [cfg.csvButtonId] - DOM id of CSV download button
 * @param {string} [cfg.csvFilenamePrefix] - downloaded CSV filename prefix
 * @param {string} [cfg.vendorFilterSelectId] - DOM id of vendor filter <select>
 * @param {string} [cfg.vendorFilterAllLabel] - "show all" text for vendor filter
 * @param {string} [cfg.monthFromSelectId] - DOM id of "from month" <select>
 * @param {string} [cfg.monthToSelectId] - DOM id of "to month" <select>
 * @param {string} [cfg.monthResetButtonId] - DOM id of reset month-range button
 * @param {string} [cfg.monthFromAllLabel] - label for no lower month bound
 * @param {string} [cfg.monthToAllLabel] - label for no upper month bound
 * @param {boolean} [cfg.enableCellClick] - whether matrix cells open a details window
 * @param {function} [cfg.valueFormatter] - (value, row, month) => display text for each matrix cell
 */
export function initMatrixTable(cfg) {
  const HEAD = document.getElementById(cfg.theadId);
  const BODY = document.getElementById(cfg.tbodyId);
  const TABLE = document.getElementById(cfg.tableId);
  let rows = [];
  let months = [];
  let selectedVendorName = '';
  let selectedFromMonth = '';
  let selectedToMonth = '';

  let detailsWin = null;

  function openOrUpdateDetails(pnk, month) {
    const url = cfg.detailsUrlBuilder(pnk, month);
    detailsWin = window.open(url, cfg.detailsWindowName || 'details');
    if (!detailsWin) {
      window.location.href = url;
      return;
    }
    detailsWin.focus?.();
  }

  const enableCellClick = cfg.enableCellClick !== false && typeof cfg.detailsUrlBuilder === 'function';
  if (enableCellClick) {
    TABLE.addEventListener('click', (ev) => {
      const td = ev.target.closest('td[data-clickable="true"]');
      if (!td) return;
      const { pnk, month } = td.dataset;
      openOrUpdateDetails(pnk, month);
    });
  }

  if (cfg.csvButtonId) {
    bindTableCsvDownload({
      buttonId: cfg.csvButtonId,
      tableId: cfg.tableId,
      filePrefix: cfg.csvFilenamePrefix || 'table'
    });
  }

  function resolveVisibleMonths() {
    if (!months.length) return [];

    const startIdx = selectedFromMonth ? months.indexOf(selectedFromMonth) : 0;
    const endIdx = selectedToMonth ? months.indexOf(selectedToMonth) : (months.length - 1);
    if (startIdx < 0 || endIdx < 0 || startIdx > endIdx) return [];

    return months.slice(startIdx, endIdx + 1);
  }

  function renderFiltered() {
    const visibleMonths = resolveVisibleMonths();
    const filteredRows = !selectedVendorName
      ? rows
      : rows.filter((row) => row.vendorName === selectedVendorName);
    renderHeader(HEAD, visibleMonths);
    renderBody(BODY, filteredRows, visibleMonths, TABLE, {
      enableCellClick,
      valueFormatter: cfg.valueFormatter
    });
  }

  (async function init() {
    try {
      const data = await fetchJSON(cfg.dataUrl);
      rows = toRows(data);
      months = collectAllMonths(rows);
      if (cfg.vendorFilterSelectId) {
        bindVendorFilter({
          selectId: cfg.vendorFilterSelectId,
          rows,
          onChange: (vendorName) => {
            selectedVendorName = vendorName;
            renderFiltered();
          },
          allLabel: cfg.vendorFilterAllLabel || 'All vendors'
        });
      }

      if (cfg.monthFromSelectId || cfg.monthToSelectId || cfg.monthResetButtonId) {
        bindMonthRangeFilter({
          fromSelectId: cfg.monthFromSelectId,
          toSelectId: cfg.monthToSelectId,
          resetButtonId: cfg.monthResetButtonId,
          months,
          onChange: ({ fromMonth, toMonth }) => {
            selectedFromMonth = fromMonth;
            selectedToMonth = toMonth;
            renderFiltered();
          },
          fromAllLabel: cfg.monthFromAllLabel || 'From start',
          toAllLabel: cfg.monthToAllLabel || 'To end'
        });
      } else {
        renderFiltered();
      }
    } catch (e) {
      HEAD.innerHTML = '';
      BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
      console.error(e);
    }
  })();

  window.addEventListener('resize', () => applyStickyOffsets(TABLE));
}

export function arrayToDateTime(arr) {
  if (!Array.isArray(arr)) return null;
  const [y, m, d, hh, mm, ss, nano] = arr;
  // JS Date months are 0-based; nano to millis
  const ms = Math.floor((nano ?? 0) / 1_000_000);
  return new Date(Date.UTC(y, (m ?? 1) - 1, d ?? 1, hh ?? 0, mm ?? 0, ss ?? 0, ms));
}

export function toTaskRows(jsonData, pausedTaskNames = []) {
  if (!Array.isArray(jsonData)) return [];
  const pausedNames = new Set(Array.isArray(pausedTaskNames) ? pausedTaskNames : []);

  return jsonData.map((item) => {
    const {
      name,
      started,
      terminated,
      lastSuccessfulRun,
      durationOfLastRun,
      unsuccessfulRuns,
      error,
    } = item || {};

    return {
      name: name ?? "",
      started: arrayToDateTime(started),
      terminated: arrayToDateTime(terminated),
      lastSuccessfulRun: arrayToDateTime(lastSuccessfulRun),
      durationOfLastRunSeconds:
          typeof durationOfLastRun === "number" ? durationOfLastRun : null,
      unsuccessfulRuns: typeof unsuccessfulRuns === "number" ? unsuccessfulRuns : 0,
      error: typeof error === "string" ? error : "",
      paused: pausedNames.has(name),
      // include original item if needed:
      // raw: item
    };
  });
}

export function renderTasksBody(tbodyEl, rows, options = {}) {
  const canRunTasks = options.canRunTasks === true;
  const taskLaneByName = options.taskLaneByName instanceof Map
    ? options.taskLaneByName
    : new Map();
  const activeTaskByLane = options.activeTaskByLane instanceof Map
    ? options.activeTaskByLane
    : new Map();
  const activeTaskNames = new Set(activeTaskByLane.values());
  const pendingTaskNames = options.pendingTaskNames instanceof Set
    ? options.pendingTaskNames
    : new Set();
  const checkingTaskNames = options.checkingTaskNames instanceof Set
    ? options.checkingTaskNames
    : new Set();
  const databaseRunningTaskNames = options.databaseRunningTaskNames instanceof Set
    ? options.databaseRunningTaskNames
    : new Set(rows
      .filter((row) => row.started != null && row.terminated == null)
      .map((row) => row.name));
  const blockingTaskByLane = new Map(activeTaskByLane);

  for (const taskName of [
    ...pendingTaskNames,
    ...checkingTaskNames,
    ...databaseRunningTaskNames
  ]) {
    const lane = taskLaneByName.get(taskName);
    if (lane != null && !blockingTaskByLane.has(lane)) {
      blockingTaskByLane.set(lane, taskName);
    }
  }

  function renderRow(row) {
    const tr = document.createElement('tr');

    const isRunning = row.started != null && row.terminated == null;
    const isStarting = !isRunning &&
      (activeTaskNames.has(row.name) || pendingTaskNames.has(row.name));
    const isCheckingResult = !isRunning && !isStarting && checkingTaskNames.has(row.name);
    const taskLane = taskLaneByName.get(row.name) ?? null;
    const blockingTaskName = taskLane == null ? null : blockingTaskByLane.get(taskLane) ?? null;
    const tdAction = document.createElement('td');
    if (canRunTasks) {
      if (!isRunning && !isStarting && !isCheckingResult) {
        const runButton = document.createElement('button');
        runButton.type = 'button';
        runButton.textContent = 'Run';
        runButton.classList.add('task-action-button', 'task-run-button');
        runButton.dataset.taskName = row.name;
        runButton.disabled = blockingTaskName != null;
        if (blockingTaskName != null) {
          runButton.title = checkingTaskNames.has(blockingTaskName)
            ? `Waiting for the result of task "${blockingTaskName}" in lane "${taskLane}".`
            : `Task "${blockingTaskName}" is already running or starting in lane "${taskLane}".`;
        }
        runButton.addEventListener('click', () => options.onRun?.(row.name, runButton));
        tdAction.appendChild(runButton);
      }

      const pauseButton = document.createElement('button');
      pauseButton.type = 'button';
      pauseButton.textContent = row.paused ? 'Resume' : 'Pause';
      pauseButton.classList.add('task-action-button', 'task-pause-button');
      pauseButton.addEventListener('click', () => options.onSetPaused?.(row.name, !row.paused, pauseButton));
      tdAction.appendChild(pauseButton);
    } else {
      tdAction.textContent = '-';
    }
    tr.appendChild(tdAction);

    const tdName = document.createElement('td');
    tdName.textContent = row.name;
    tr.appendChild(tdName);
    const tdStatus = document.createElement('td');
    if (isRunning) {
      tdStatus.textContent = `RUNNING since ${formatLocalDateTime(row.started)}`;
    } else if (isStarting) {
      tdStatus.textContent = 'STARTING (waiting for the background worker)';
    } else if (isCheckingResult) {
      tdStatus.textContent = 'CHECKING RESULT';
    } else if (row.paused) {
      tdStatus.textContent = 'PAUSED';
    } else if (row.error != null && String(row.error).trim() !== "") {
      tdStatus.textContent = "ERROR";
    } else {
      tdStatus.textContent = "-";
    }
    tr.appendChild(tdStatus);
    const tdLastRun = document.createElement('td');
    tdLastRun.textContent = formatLocalDateTime(row.terminated);
    tr.appendChild(tdLastRun);
    const tdDuration = document.createElement('td');
    tdDuration.textContent = formatDuration(row.durationOfLastRunSeconds);
    tr.appendChild(tdDuration);
    const tdLastSuccess = document.createElement('td');
    tdLastSuccess.textContent = formatLocalDateTime(row.lastSuccessfulRun);
    tr.appendChild(tdLastSuccess);
    const tdFailures = document.createElement('td');
    tdFailures.textContent = row.unsuccessfulRuns;
    tr.appendChild(tdFailures);
    const tdError = document.createElement('td');
    tdError.textContent = row.error;
    tr.appendChild(tdError);

    return tr;
  }

  renderTbody(tbodyEl, rows, renderRow);
}


/**
 * Initialise a task table page.
 * @param {Object} cfg
 * @param {string} cfg.tableId - DOM id of <table>
 * @param {string} cfg.theadId - DOM id of <thead>
 * @param {string} cfg.tbodyId - DOM id of <tbody>
 * @param {string} cfg.dataUrl - endpoint to load the matrix JSON from
 * @param {string} [cfg.activeDataUrl] - endpoint returning scheduler lanes and their active tasks
 * @param {string} [cfg.pausedDataUrl] - endpoint returning the names of paused tasks
 * @param {string} [cfg.actionStatusId] - DOM id used for run-request feedback
 * @param {string} [cfg.schedulerStatusId] - DOM id used for current worker feedback
 * @param {boolean} [cfg.canRunTasks] - whether Run controls should be displayed
 * @param {function} [cfg.runUrlBuilder] - (taskName) => URL for the run endpoint
 * @param {function} [cfg.pauseUrlBuilder] - (taskName) => URL for the pause endpoint
 * @param {function} [cfg.resumeUrlBuilder] - (taskName) => URL for the resume endpoint
 * @param {number} [cfg.refreshIntervalMs] - automatic refresh interval in milliseconds
 */
export function initTaskTable(cfg) {
  const HEAD = document.getElementById(cfg.theadId);
  const BODY = document.getElementById(cfg.tbodyId);
  const TABLE = document.getElementById(cfg.tableId);
  const ACTION_STATUS = cfg.actionStatusId ? document.getElementById(cfg.actionStatusId) : null;
  const SCHEDULER_STATUS = cfg.schedulerStatusId ? document.getElementById(cfg.schedulerStatusId) : null;
  const pendingTaskNames = new Set();
  const trackedRuns = new Map();
  let currentLaneStatuses = [];
  let currentTaskLaneByName = new Map();
  let currentActiveTaskByLane = new Map();
  let currentDatabaseRunningTaskNames = new Set();
  let latestTaskRows = [];
  let latestLoadRequest = 0;
  let activeTaskPollTimer = null;
  let clearRunStatusWhenIdle = false;
  let actionStatusSource = null;

  function setActionStatus(message, isError = false, clearWhenIdle = false, source = 'action') {
    if (!ACTION_STATUS) return;
    ACTION_STATUS.textContent = message;
    ACTION_STATUS.classList.toggle('is-error', isError);
    clearRunStatusWhenIdle = clearWhenIdle;
    actionStatusSource = message ? source : null;
  }

  function setSchedulerStatus(laneStatuses, databaseRunningTaskNames) {
    if (!SCHEDULER_STATUS) return;

    const activeTasks = laneStatuses
      .filter((status) => status.activeTaskName != null)
      .map((status) => ({ lane: status.lane, taskName: status.activeTaskName }));
    const reportedTaskNames = new Set(activeTasks.map(({ taskName }) => taskName));
    for (const taskName of databaseRunningTaskNames) {
      if (!reportedTaskNames.has(taskName)) {
        activeTasks.push({ lane: currentTaskLaneByName.get(taskName) ?? null, taskName });
      }
    }

    if (activeTasks.length === 0) {
      SCHEDULER_STATUS.textContent = '';
      return;
    }

    const taskDescriptions = activeTasks
      .map(({ lane, taskName }) => `"${taskName}"${lane == null ? '' : ` (${lane})`}`)
      .join(', ');
    SCHEDULER_STATUS.textContent = activeTasks.length === 1
      ? `Task ${taskDescriptions} is running or starting. Other tasks in that lane are unavailable until it finishes.`
      : `Tasks ${taskDescriptions} are running or starting. Other tasks in those lanes are unavailable until they finish.`;
  }

  function parseLaneStatuses(payload) {
    if (!Array.isArray(payload?.lanes)) return [];

    return payload.lanes.flatMap((item) => {
      const lane = typeof item?.lane === 'string' ? item.lane.trim() : '';
      if (!lane) return [];
      const activeTaskName = typeof item?.activeTaskName === 'string' && item.activeTaskName
        ? item.activeTaskName
        : null;
      const taskNames = Array.isArray(item?.taskNames)
        ? item.taskNames.filter((taskName) => typeof taskName === 'string' && taskName)
        : [];
      return [{ lane, activeTaskName, taskNames }];
    });
  }

  function buildLaneState(laneStatuses) {
    const taskLaneByName = new Map();
    const activeTaskByLane = new Map();
    for (const { lane, activeTaskName, taskNames } of laneStatuses) {
      for (const taskName of taskNames) {
        taskLaneByName.set(taskName, lane);
      }
      if (activeTaskName != null) {
        taskLaneByName.set(activeTaskName, lane);
        activeTaskByLane.set(lane, activeTaskName);
      }
    }
    return { taskLaneByName, activeTaskByLane };
  }

  async function readActionResponse(response, fallback) {
    const responseText = await response.text();
    if (!responseText) {
      return { message: fallback, code: null };
    }
    try {
      const payload = JSON.parse(responseText);
      return {
        message: typeof payload?.message === 'string' && payload.message.trim()
          ? payload.message
          : fallback,
        code: typeof payload?.code === 'string' ? payload.code : null
      };
    } catch {
      return { message: responseText, code: null };
    }
  }

  function scheduleActiveTaskPoll() {
    if (activeTaskPollTimer != null) return;
    activeTaskPollTimer = window.setTimeout(async () => {
      activeTaskPollTimer = null;
      await loadTasks();
    }, 1_000);
  }

  function taskTimestamp(value) {
    return value instanceof Date ? value.getTime() : null;
  }

  function updateTrackedRuns(rows) {
    const activeTaskNames = new Set(currentActiveTaskByLane.values());
    for (const [taskName, trackedRun] of trackedRuns) {
      const row = rows.find((candidate) => candidate.name === taskName);
      const rowIsRunning = row?.started != null && row?.terminated == null;
      if (activeTaskNames.has(taskName) || rowIsRunning) {
        continue;
      }

      const taskRecordChanged = row != null && (
        taskTimestamp(row.started) !== trackedRun.previousStarted ||
        taskTimestamp(row.terminated) !== trackedRun.previousTerminated
      );
      if (taskRecordChanged && row.terminated != null) {
        const error = String(row.error ?? '').trim();
        if (error) {
          setActionStatus(
            `Task "${taskName}" failed. See its Error column and the application logs for details.`,
            true,
            false,
            'run'
          );
        } else {
          setActionStatus(`Task "${taskName}" completed successfully.`, false, false, 'run');
        }
        trackedRuns.delete(taskName);
        continue;
      }

      if (Date.now() - trackedRun.acceptedAt >= 2_000) {
        setActionStatus(
          `Task "${taskName}" was accepted, but no execution result was recorded. Check the application logs.`,
          true,
          false,
          'run'
        );
        trackedRuns.delete(taskName);
      }
    }
  }

  function disableRunButtonsInTaskLane(taskName) {
    const taskLane = currentTaskLaneByName.get(taskName) ?? null;
    for (const runButton of BODY.querySelectorAll('.task-run-button')) {
      const buttonTaskName = runButton.dataset.taskName;
      if (buttonTaskName === taskName ||
          (taskLane != null && currentTaskLaneByName.get(buttonTaskName) === taskLane)) {
        runButton.disabled = true;
      }
    }
  }

  async function runTask(taskName, button) {
    if (typeof cfg.runUrlBuilder !== 'function') return;

    const previousRow = latestTaskRows.find((row) => row.name === taskName);
    const trackedRunCandidate = {
      previousStarted: taskTimestamp(previousRow?.started),
      previousTerminated: taskTimestamp(previousRow?.terminated),
      acceptedAt: 0
    };
    trackedRuns.delete(taskName);
    pendingTaskNames.add(taskName);
    disableRunButtonsInTaskLane(taskName);
    button.textContent = 'Starting...';
    setActionStatus(`Starting ${taskName}...`, false, false, 'run');

    try {
      const response = await fetch(cfg.runUrlBuilder(taskName), { method: 'POST' });
      if (response.redirected) {
        throw new Error('Your session may have expired. Please sign in again before running the task.');
      }
      const result = await readActionResponse(
        response,
        response.status === 202
          ? `Task "${taskName}" was accepted and will start shortly.`
          : response.status === 401 || response.status === 403
            ? 'Your session has expired or you are not allowed to run tasks. Please sign in again.'
            : `Could not start task (HTTP ${response.status}).`
      );
      if (response.status !== 202) {
        pendingTaskNames.delete(taskName);
        setActionStatus(
          result.message,
          true,
          result.code === 'BUSY' || response.status === 409,
          'run'
        );
        await loadTasks();
        return;
      }
      pendingTaskNames.delete(taskName);
      trackedRuns.set(taskName, {
        ...trackedRunCandidate,
        acceptedAt: Date.now()
      });
      setActionStatus(result.message, false, false, 'run');
      await loadTasks();
    } catch (error) {
      pendingTaskNames.delete(taskName);
      trackedRuns.delete(taskName);
      setActionStatus(error instanceof Error ? error.message : String(error), true, false, 'run');
      await loadTasks();
    } finally {
      pendingTaskNames.delete(taskName);
      scheduleActiveTaskPoll();
    }
  }

  async function setTaskPaused(taskName, paused, button) {
    const urlBuilder = paused ? cfg.pauseUrlBuilder : cfg.resumeUrlBuilder;
    if (typeof urlBuilder !== 'function') return;

    button.disabled = true;
    button.textContent = paused ? 'Pausing...' : 'Resuming...';
    setActionStatus(`${paused ? 'Pausing' : 'Resuming'} automatic scheduling for ${taskName}...`);

    try {
      const response = await fetch(urlBuilder(taskName), { method: 'POST' });
      if (!response.ok) {
        const detail = await response.text();
        throw new Error(detail || `Could not ${paused ? 'pause' : 'resume'} task (HTTP ${response.status}).`);
      }
      setActionStatus(`Automatic scheduling for ${taskName} is now ${paused ? 'paused' : 'resumed'}.`);
    } catch (error) {
      setActionStatus(error instanceof Error ? error.message : String(error), true);
    } finally {
      await loadTasks();
    }
  }

  async function loadTasks() {
    const loadRequest = ++latestLoadRequest;
    try {
      const [data, pausedTaskNames, activeTaskStatus] = await Promise.all([
        fetchJSON(cfg.dataUrl),
        cfg.pausedDataUrl ? fetchJSON(cfg.pausedDataUrl) : Promise.resolve([]),
        cfg.activeDataUrl ? fetchJSON(cfg.activeDataUrl) : Promise.resolve({ lanes: [] })
      ]);
      if (loadRequest !== latestLoadRequest) return;

      const rows = toTaskRows(data, pausedTaskNames);
      latestTaskRows = rows;
      currentDatabaseRunningTaskNames = new Set(rows
        .filter((row) => row.started != null && row.terminated == null)
        .map((row) => row.name));
      currentLaneStatuses = parseLaneStatuses(activeTaskStatus);
      const laneState = buildLaneState(currentLaneStatuses);
      currentTaskLaneByName = laneState.taskLaneByName;
      currentActiveTaskByLane = laneState.activeTaskByLane;
      if (actionStatusSource === 'load') {
        setActionStatus('');
      }
      if (pendingTaskNames.size === 0) {
        if (currentActiveTaskByLane.size === 0 &&
            currentDatabaseRunningTaskNames.size === 0 &&
            clearRunStatusWhenIdle) {
          setActionStatus('');
        }
      }
      updateTrackedRuns(rows);
      const tr = buildHeaderRow([
        'Action', 'Name', 'Status', 'Last Run', 'Runtime', 'Last Successful', 'Failures', 'Error'
      ]);
      HEAD.innerHTML = '';
      HEAD.appendChild(tr);
      renderTasksBody(BODY, rows, {
        canRunTasks: cfg.canRunTasks,
        taskLaneByName: currentTaskLaneByName,
        activeTaskByLane: currentActiveTaskByLane,
        pendingTaskNames,
        checkingTaskNames: new Set(trackedRuns.keys()),
        databaseRunningTaskNames: currentDatabaseRunningTaskNames,
        onRun: runTask,
        onSetPaused: setTaskPaused
      });
      setSchedulerStatus(currentLaneStatuses, currentDatabaseRunningTaskNames);
      if (currentActiveTaskByLane.size > 0 ||
          currentDatabaseRunningTaskNames.size > 0 ||
          pendingTaskNames.size > 0 ||
          trackedRuns.size > 0) {
        scheduleActiveTaskPoll();
      }
    } catch (e) {
      if (loadRequest !== latestLoadRequest) return;
      HEAD.innerHTML = '';
      BODY.innerHTML = '<tr><td>Failed to load data</td></tr>';
      setActionStatus(
        e instanceof Error ? `Failed to load tasks: ${e.message}` : 'Failed to load tasks.',
        true,
        false,
        'load'
      );
      console.error(e);
      if (currentActiveTaskByLane.size > 0 ||
          currentDatabaseRunningTaskNames.size > 0 ||
          pendingTaskNames.size > 0 ||
          trackedRuns.size > 0) {
        scheduleActiveTaskPoll();
      }
    }
  };

  document.getElementById('refreshBtn')?.addEventListener('click', loadTasks);
  window.addEventListener('resize', () => applyStickyOffsets(TABLE));

  if (Number.isFinite(cfg.refreshIntervalMs) && cfg.refreshIntervalMs > 0) {
    window.setInterval(loadTasks, cfg.refreshIntervalMs);
  }

  loadTasks();
}
