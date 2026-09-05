@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <span id="adsKeywordsStatus" class="muted"></span>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap ads-campaigns-wrap">
        <table id="adsKeywordsTable" class="ads-campaigns-table ads-keywords-table">
            <thead id="adsKeywordsHead"></thead>
            <tbody id="adsKeywordsBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/ads-keywords.js"></script>
`)
