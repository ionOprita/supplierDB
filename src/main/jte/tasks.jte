@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
  <div class="page-actions">
    <button id="refreshBtn" title="Reload">Reload</button>
  </div>
  <div class="wrap">
    <table id="tasksTable">
      <thead id="tasksHead"></thead>
      <tbody id="tasksBody"></tbody>
    </table>
  </div>
  <script type="module" src="/js/tasks.js"></script>
`)
