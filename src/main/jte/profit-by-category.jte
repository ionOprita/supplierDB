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
        <noscript><p>Enable JavaScript to select a vendor group and category.</p></noscript>
    </section>
    <script type="module" src="/js/profit-calculator.js"></script>
`)
