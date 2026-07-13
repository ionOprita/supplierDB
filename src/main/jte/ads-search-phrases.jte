@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions ads-campaigns-actions">
        <span id="adsSearchPhrasesStatus" class="muted"></span>
        <button id="downloadCsvBtn" type="button">Download CSV</button>
    </div>
    <div class="wrap ads-campaigns-wrap">
        <table id="adsSearchPhrasesTable" class="ads-campaigns-table ads-search-phrases-table">
            <thead id="adsSearchPhrasesHead"></thead>
            <tbody id="adsSearchPhrasesBody"></tbody>
        </table>
    </div>
    <script type="module" src="/js/ads-search-phrases.js"></script>
`)
