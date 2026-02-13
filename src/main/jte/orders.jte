@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions">
        <label for="vendorFilterSelect">Vendor:</label>
        <select id="vendorFilterSelect" class="vendor-filter-select">
            <option value="">All vendors</option>
        </select>
        <label for="monthFromSelect">From:</label>
        <select id="monthFromSelect" class="month-filter-select">
            <option value="">From start</option>
        </select>
        <label for="monthToSelect">To:</label>
        <select id="monthToSelect" class="month-filter-select">
            <option value="">To end</option>
        </select>
        <button id="monthFilterResetBtn" type="button">Reset months</button>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap">
        <table id="ordersTable">
            <thead id="ordersHead"></thead>
            <tbody id="ordersBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/orders.js"></script>
`)