@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <div class="ads-campaigns-filter-controls">
            <label for="adsAdsetDateSelect">Report date:</label>
            <select id="adsAdsetDateSelect" class="date-filter-select"></select>
        </div>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <p id="adsAdsetsStatus" class="muted ads-campaigns-status"></p>
    <div class="wrap ads-campaigns-wrap">
        <table id="adsAdsetsTable" class="ads-campaigns-table ads-adsets-table">
            <thead id="adsAdsetsHead"></thead>
            <tbody id="adsAdsetsBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/ads-adsets.js"></script>
`)
