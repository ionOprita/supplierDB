@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
  <div class="page-actions">
    <button id="refreshBtn" title="Reload">Reload</button>
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
