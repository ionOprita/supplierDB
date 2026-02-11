@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <script type="module" src="/js/returns.js"></script>
    <div class="wrap">
        <table id="stornoTable">
            <thead id="stornoHead"></thead>
            <tbody id="stornoBody"></tbody>
        </table>
    </div>
`)