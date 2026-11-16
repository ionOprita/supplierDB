@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="profitFbeTariffsPage" aria-labelledby="title">
        <p class="muted">These FBE processing tariffs are shared across all vendor groups. Fees are in RON, excluding VAT. Dimensions are in millimetres and weight bands are in kilograms.</p>
        <div class="page-actions profit-override-actions">
            <button id="fbeTariffReview" type="button" disabled>Review changes</button>
            <button id="fbeTariffRevert" type="button" disabled>Revert changes</button>
        </div>
        <p id="fbeTariffStatus" role="status" aria-live="polite">Loading FBE tariffs…</p>
        <button id="fbeTariffRetry" type="button" hidden>Retry loading tariffs</button>
        <p class="muted profit-override-help">Blank optional weight ranges and extra-weight fees mean no value is provided. Zero is a supplied value.</p>
        <div id="fbeTariffWrap" class="wrap fbe-tariff-wrap" role="region" aria-label="FBE tariff grid; scroll to view all fields" aria-busy="true" tabindex="0">
            <table class="fbe-tariff-table" aria-label="Shared FBE processing tariffs">
                <thead>
                    <tr>
                        <th rowspan="2" scope="col" class="fbe-tariff-id">#</th>
                        <th rowspan="2" scope="col" class="fbe-tariff-category">Size category</th>
                        <th colspan="4" scope="colgroup">Maximum dimensions (mm)</th>
                        <th rowspan="2" scope="col">Weight band (kg)</th>
                        <th colspan="4" scope="colgroup">Base fees (RON)</th>
                        <th rowspan="2" scope="col">Weight range (kg)</th>
                        <th colspan="4" scope="colgroup">Extra-weight fees (RON)</th>
                    </tr>
                    <tr>
                        <th scope="col">Length</th><th scope="col">Width</th><th scope="col">Height</th><th scope="col">Girth</th>
                        <th scope="col">Order processing</th><th scope="col">Return processing</th><th scope="col">Withdrawal</th><th scope="col">Disposal</th>
                        <th scope="col">Order processing</th><th scope="col">Return processing</th><th scope="col">Withdrawal</th><th scope="col">Disposal</th>
                    </tr>
                </thead>
                <tbody id="fbeTariffBody"></tbody>
            </table>
        </div>
        <noscript><p>Enable JavaScript to view and edit FBE tariffs.</p></noscript>
        <dialog id="fbeTariffDialog" class="product-review-dialog profit-override-dialog" aria-labelledby="fbeTariffDialogTitle">
            <h2 id="fbeTariffDialogTitle">Review shared FBE tariffs</h2>
            <p>Saving updates these tariffs for all users and vendor groups.</p>
            <div class="wrap profit-override-review-wrap">
                <table class="profit-override-review-table">
                    <thead><tr><th>Tariff</th><th>Field</th><th>Saved value</th><th>New value</th></tr></thead>
                    <tbody id="fbeTariffReviewBody"></tbody>
                </table>
            </div>
            <p id="fbeTariffReviewError" class="profit-override-error" role="alert" hidden></p>
            <div class="page-actions profit-override-dialog-actions">
                <button id="fbeTariffSave" type="button" disabled>Save shared tariffs</button>
                <button id="fbeTariffContinue" type="button">Continue editing</button>
                <button id="fbeTariffReviewRevert" type="button">Revert changes</button>
            </div>
        </dialog>
    </section>
    <script type="module" src="/js/profit-fbe-tariffs.js"></script>
`)
