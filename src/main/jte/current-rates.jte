@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions current-rates-actions">
        <div class="current-rates-filter-controls">
            <span>Show the rate over the last</span>
            <select id="currentRatesWindowMonthsSelect" aria-label="Number of months">
                <option value="3" selected>3</option>
                <option value="6">6</option>
                <option value="9">9</option>
                <option value="12">12</option>
            </select>
            <span>months before</span>
            <select id="currentRatesBeforeMonthSelect" aria-label="Month"></select>
            <span>and show range with confidence level</span>
            <select id="currentRatesConfidenceLevelSelect" aria-label="Confidence Level">
                <option value="0.95" selected>95 %</option>
                <option value="0.9">90 %</option>
                <option value="0.8">80 %</option>
                <option value="0.67">67 %</option>
                <option value="0.5">50 %</option>
            </select>
            <button id="currentRatesUpdateBtn" type="button">Update</button>
        </div>
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
