@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="wrap">
        <table id="ordersTable">
            <thead id="ordersHead"></thead>
            <tbody id="ordersBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/orders.js"></script>
`)