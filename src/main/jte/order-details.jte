@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <span id="subtitle" class="muted"></span>
    <div class="page-actions">
        <button id="refreshBtn" title="Reload">Reload</button>
        <button id="copyBtn" title="Copy table">Copy</button>
        <button id="downloadCsvBtn" title="Download as CSV">CSV</button>
    </div>
    <div class="wrap">
  <table id="detailsTable">
            <thead>
            <tr>
                <th>Time</th>
                <th>Order ID</th>
                <th>Vendor</th>
                <th>PNK</th>
                <th>Name</th>
                <th>Quantity</th>
            </tr>
            </thead>
            <tbody id="tbody"></tbody>
        </table>
    </div>
    <div id="status" class="muted"></div>
    <script type="module" src="/js/order-details.js"></script>
`)