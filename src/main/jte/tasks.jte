@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
  <div class="page-actions task-page-actions">
    <div class="task-filter-controls" role="group" aria-label="Filter tasks">
      <span>Filter by:</span>
      <div class="task-filter-field">
        <label for="taskNameFilter">Name</label>
        <input id="taskNameFilter" type="search" autocomplete="off" />
      </div>
      <div class="task-filter-field">
        <label for="taskStatusFilter">Status</label>
        <select id="taskStatusFilter">
          <option value="">All</option>
          <option value="RUNNING">Running</option>
          <option value="ERROR">Error</option>
          <option value="PAUSED">Paused</option>
        </select>
      </div>
      <div class="task-filter-field">
        <label for="taskLaneFilter">Lane</label>
        <select id="taskLaneFilter">
          <option value="">All</option>
          <option value="emagApiLane">eMAG API</option>
          <option value="emagDashboardLane">eMAG Dashboard</option>
          <option value="emagReviewsLane">eMAG Reviews</option>
          <option value="googleApiLane">Google API</option>
        </select>
      </div>
      @if(userRole.equals("admin"))
        <button id="pauseAllBtn" type="button" title="Pause automatic scheduling for all tasks in every lane">Pause All</button>
        <button id="resumeAllBtn" type="button" title="Resume automatic scheduling for all paused tasks in every lane">Resume all</button>
      @endif
    </div>
    <button id="refreshBtn" type="button" title="Reload">Reload</button>
  </div>
  <p id="taskSchedulerStatus" class="task-status-message task-scheduler-status" role="status" aria-live="polite"></p>
  <p id="taskActionStatus" class="task-status-message task-action-status" role="status" aria-live="polite"></p>
  <div class="wrap">
    <table id="tasksTable" data-can-run="${userRole.equals("admin")}">
      <thead id="tasksHead"></thead>
      <tbody id="tasksBody"></tbody>
    </table>
  </div>
  <details id="taskHistoryPanel" class="task-history">
    <summary>Execution history and daily coverage</summary>
    <div class="task-history-content">
      <form id="taskHistoryFilters" class="task-history-filters" aria-label="Filter execution history">
        <label>From<input id="taskHistoryFrom" type="date" required /></label>
        <label>To<input id="taskHistoryTo" type="date" required /></label>
        <label>Task<select id="taskHistoryTask"><option value="">All tasks</option></select></label>
        <label>Status<select id="taskHistoryStatusFilter">
          <option value="">All statuses</option>
          <option value="RUNNING">Running</option>
          <option value="SUCCESS">Success</option>
          <option value="FAILED">Failed</option>
          <option value="INTERRUPTED">Interrupted</option>
        </select></label>
        <button id="taskHistoryApply" type="submit">Apply filters</button>
      </form>
      <p id="taskHistoryNamesStatus" class="muted" role="status"></p>
      <section aria-labelledby="taskHistoryTitle">
        <h2 id="taskHistoryTitle">Execution history</h2>
        <p id="taskHistoryStatus" class="muted" role="status" aria-live="polite"></p>
        <div id="taskHistoryWrap" class="wrap task-history-wrap" tabindex="0" role="region" aria-label="Execution history; scroll to view all columns" aria-busy="false">
          <table class="task-history-table">
            <thead><tr><th scope="col">Task</th><th scope="col">Started (Romania)</th><th scope="col">Finished (Romania)</th><th scope="col">Status</th><th scope="col">Error</th></tr></thead>
            <tbody id="taskHistoryBody"></tbody>
          </table>
        </div>
        <div class="task-history-pagination" aria-label="History pages">
          <button id="taskHistoryPrevious" type="button" disabled>Previous</button>
          <span id="taskHistoryPage" aria-live="polite"></span>
          <button id="taskHistoryNext" type="button" disabled>Next</button>
        </div>
      </section>
      <section aria-labelledby="taskCoverageTitle">
        <h2 id="taskCoverageTitle">Daily offers coverage</h2>
        <p class="muted">Coverage uses the date and task filters above. The status filter applies only to execution history. Run retries collection for the current day; missed past days cannot be recovered.</p>
        <p id="taskCoverageStatus" class="muted" role="status" aria-live="polite"></p>
        <div id="taskCoverageWrap" class="wrap task-history-wrap" tabindex="0" role="region" aria-label="Daily offers coverage; scroll to view all columns" aria-busy="false">
          <table class="task-history-table task-coverage-table">
            <thead><tr><th scope="col">Task</th><th scope="col">Vendor</th><th scope="col">Date</th><th scope="col">Status</th><th scope="col">Last fetched (Romania)</th><th scope="col">Attempts</th><th scope="col">Last error</th></tr></thead>
            <tbody id="taskCoverageBody"></tbody>
          </table>
        </div>
      </section>
    </div>
  </details>
  <dialog id="taskErrorDialog" class="task-error-dialog" aria-labelledby="taskErrorTitle">
    <div class="task-error-heading">
      <h2 id="taskErrorTitle">Task error</h2>
      <div class="task-error-actions">
        <button id="taskErrorDownload" type="button" disabled>Download error</button>
        <button id="taskErrorClose" type="button" autofocus>Close</button>
      </div>
    </div>
    <pre id="taskErrorContent" class="task-error-content" tabindex="0" aria-label="Full task error"></pre>
  </dialog>
  <script type="module" src="/js/tasks.js"></script>
  <script type="module" src="/js/task-history.js"></script>
`)
