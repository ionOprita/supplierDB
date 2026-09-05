@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <span id="adsTargetedProductsStatus" class="muted"></span>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap ads-campaigns-wrap">
        <table id="adsTargetedProductsTable" class="ads-campaigns-table ads-targeted-products-table">
            <thead id="adsTargetedProductsHead"></thead>
            <tbody id="adsTargetedProductsBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/ads-targeted-products.js"></script>
`)
