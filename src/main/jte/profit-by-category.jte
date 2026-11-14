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
        <div class="page-actions profit-override-actions">
            <button id="profitOverrideReview" type="button" disabled>Review changes</button>
            <button id="profitOverrideRevert" type="button" disabled>Revert changes</button>
            <p id="profitOverrideStatus" role="status" aria-live="polite" hidden></p>
        </div>
        <p class="muted profit-override-help">Blank overrides use available defaults. USD costs need both an amount and an exchange rate. Commission needs an entered override; fulfillment charges are not calculated yet.</p>
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
        <dialog id="profitOverrideDialog" class="product-review-dialog profit-override-dialog" aria-labelledby="profitOverrideDialogTitle">
            <h2 id="profitOverrideDialogTitle">Review shared overrides</h2>
            <p>Saving updates the overrides for all users. Product master data stays unchanged.</p>
            <div class="wrap profit-override-review-wrap">
                <table class="profit-override-review-table">
                    <thead><tr><th>Product</th><th>Field</th><th>Saved value</th><th>New value</th></tr></thead>
                    <tbody id="profitOverrideReviewBody"></tbody>
                </table>
            </div>
            <p id="profitOverrideReviewError" class="profit-override-error" role="alert" hidden></p>
            <div class="page-actions profit-override-dialog-actions">
                <button id="profitOverrideSave" type="button">Save shared overrides</button>
                <button id="profitOverrideContinue" type="button">Continue editing</button>
                <button id="profitOverrideReviewRevert" type="button">Revert changes</button>
            </div>
        </dialog>
    </section>
    <script type="module" src="/js/profit-calculator.js"></script>
`)
