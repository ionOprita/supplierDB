@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions">
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap">
        <table id="currentRatesTable">
            <thead id="currentRatesHead"></thead>
            <tbody id="currentRatesBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/current-rates.js"></script>
`)
