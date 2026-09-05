@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <div class="ads-campaigns-filter-controls">
            <label for="adsAdsetPeriodSelect">Period:</label>
            <select id="adsAdsetPeriodSelect" class="ads-period-select">
                <option value="day">Day</option>
                <option value="last7">Last 7 days</option>
                <option value="last30">Last 30 days</option>
                <option value="custom">Custom</option>
            </select>
            <span id="adsAdsetDateControls" class="ads-period-anchor-controls">
                <label id="adsAdsetDateLabel" for="adsAdsetDateSelect">Report date:</label>
                <select id="adsAdsetDateSelect" class="date-filter-select"></select>
            </span>
            <span id="adsAdsetCustomControls" class="ads-period-custom-controls" hidden>
                <label for="adsAdsetDateFromInput">From:</label>
                <input id="adsAdsetDateFromInput" class="ads-period-date-input" type="date">
                <label for="adsAdsetDateToInput">To:</label>
                <input id="adsAdsetDateToInput" class="ads-period-date-input" type="date">
                <button id="adsAdsetApplyRangeBtn" type="button">Apply</button>
            </span>
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
