@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="profitCalculatorPage" aria-labelledby="title">
        <div class="page-actions profit-calculator-filters">
            <label for="profitVendorGroupSelect">Vendor group
                <select id="profitVendorGroupSelect" disabled>
                    <option value="">Loading vendor groups…</option>
                </select>
            </label>
            <label for="profitCategorySelect">Category
                <select id="profitCategorySelect" disabled>
                    <option value="">Select a vendor group first</option>
                </select>
            </label>
        </div>
        <p id="profitCalculatorStatus" class="muted" role="status" aria-live="polite">Loading vendor groups and categories…</p>
        <button id="profitCalculatorRetry" type="button" hidden>Retry</button>
        <table class="profit-category-summary" aria-label="Category summary">
            <tbody id="profitCategorySummaryBody"></tbody>
        </table>
        <div id="profitCategoryWrap" class="wrap profit-category-wrap" role="region" aria-label="Product profit calculator; scroll to view all products and metrics" aria-busy="true" tabindex="0">
            <table id="profitCategoryTable" class="profit-category-table" aria-label="Profit calculator by product">
                <caption>Actual</caption>
                <thead id="profitCategoryHead"></thead>
                <tbody id="profitCategoryBody"></tbody>
            </table>
        </div>
        <noscript><p>Enable JavaScript to select a vendor group and category.</p></noscript>
    </section>
    <script type="module" src="/js/profit-calculator.js"></script>
`)
