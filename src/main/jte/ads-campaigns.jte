@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <div class="ads-campaigns-filter-controls">
            <label for="adsCampaignPeriodSelect">Period:</label>
            <select id="adsCampaignPeriodSelect" class="ads-period-select">
                <option value="day">Day</option>
                <option value="last7">Last 7 days</option>
                <option value="last30">Last 30 days</option>
                <option value="custom">Custom</option>
            </select>
            <span id="adsCampaignDateControls" class="ads-period-anchor-controls">
                <label id="adsCampaignDateLabel" for="adsCampaignDateSelect">Report date:</label>
                <select id="adsCampaignDateSelect" class="date-filter-select"></select>
            </span>
            <span id="adsCampaignCustomControls" class="ads-period-custom-controls" hidden>
                <label for="adsCampaignDateFromInput">From:</label>
                <input id="adsCampaignDateFromInput" class="ads-period-date-input" type="date">
                <label for="adsCampaignDateToInput">To:</label>
                <input id="adsCampaignDateToInput" class="ads-period-date-input" type="date">
                <button id="adsCampaignApplyRangeBtn" type="button">Apply</button>
            </span>
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
