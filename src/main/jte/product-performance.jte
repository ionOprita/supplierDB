@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="productPerformancePage" class="product-performance" aria-labelledby="title">
        <div class="pp-heading">
            <div class="pp-product">
                <select id="productPerformanceVendorSelect" class="pp-header-select pp-vendor-select" aria-label="Vendor" disabled>
                    <option value="">Loading vendors…</option>
                </select>
                <select id="productPerformanceProductSelect" class="pp-header-select pp-product-select" aria-label="Product" disabled>
                    <option value="">Select a vendor first</option>
                </select>
                <div id="productPerformanceProduct" class="pp-product-details" hidden>
                    <span class="pp-product-code">PNK <code id="productPerformancePnk"></code></span>
                    <a id="productPerformanceLink" target="_blank" rel="noopener noreferrer" hidden>View on eMAG ↗</a>
                </div>
            </div>
            <p id="productPerformancePreview" class="pp-preview">Preview — generated mock data</p>
        </div>
        <div class="pp-status-bar">
            <p id="productPerformanceStatus" class="muted" role="status" aria-live="polite">Loading vendors and products…</p>
            <button id="productPerformanceRetry" type="button" hidden>Retry</button>
        </div>
        <div id="productPerformanceWrap" class="wrap pp-wrap" role="region" aria-label="Weekly product performance; scroll to view all advertising groups" aria-busy="true" tabindex="0" hidden>
            <table id="productPerformanceTable" class="pp-table" aria-label="Weekly product and advertising performance" aria-describedby="productPerformancePreview">
                <thead id="productPerformanceHead"></thead>
                <tbody id="productPerformanceBody"></tbody>
            </table>
        </div>
        <noscript><p>Enable JavaScript to view product performance.</p></noscript>
    </section>
    <script type="module" src="/js/product-performance.js"></script>
`)
