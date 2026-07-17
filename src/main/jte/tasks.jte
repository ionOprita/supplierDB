@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
  <div class="page-actions">
    <button id="refreshBtn" title="Reload">Reload</button>
  </div>
  <div class="wrap">
    <p id="taskActionStatus" role="status" aria-live="polite"></p>
    <table id="tasksTable" data-can-run="${userRole.equals("admin")}">
      <thead id="tasksHead"></thead>
      <tbody id="tasksBody"></tbody>
    </table>
  </div>
  <script type="module" src="/js/tasks.js"></script>
`)
