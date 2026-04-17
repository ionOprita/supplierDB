@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions current-rates-actions">
        <div class="current-rates-filter-controls">
            <span>Show the rate over the last</span>
            <select id="currentRatesCategoriesWindowMonthsSelect" aria-label="Number of months">
                <option value="3" selected>3</option>
                <option value="6">6</option>
                <option value="9">9</option>
                <option value="12">12</option>
            </select>
            <span>months before</span>
            <select id="currentRatesCategoriesBeforeMonthSelect" aria-label="Month"></select>
            <span>and show range with confidence level</span>
            <select id="currentRatesCategoriesConfidenceLevelSelect" aria-label="Confidence Level">
                <option value="0.95" selected>95 %</option>
                <option value="0.9">90 %</option>
                <option value="0.8">80 %</option>
                <option value="0.67">67 %</option>
                <option value="0.5">50 %</option>
            </select>
            <button id="currentRatesCategoriesUpdateBtn" type="button">Update</button>
        </div>
        <button id="downloadCurrentRatesCategoriesCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap">
        <table id="currentRatesCategoriesTable">
            <thead id="currentRatesCategoriesHead"></thead>
            <tbody id="currentRatesCategoriesBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/current-rates-categories.js"></script>
`)
