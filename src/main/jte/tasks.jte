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
  <script type="module" src="/js/tasks.js"></script>
`)
