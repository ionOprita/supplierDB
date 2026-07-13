@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <div class="ads-campaigns-filter-controls">
            <label for="adsCampaignDateSelect">Report date:</label>
            <select id="adsCampaignDateSelect" class="date-filter-select"></select>
        </div>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <p id="adsCampaignsStatus" class="muted ads-campaigns-status"></p>
    <div class="wrap ads-campaigns-wrap">
        <table id="adsCampaignsTable" class="ads-campaigns-table">
            <thead id="adsCampaignsHead"></thead>
            <tbody id="adsCampaignsBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/ads-campaigns.js"></script>
`)
