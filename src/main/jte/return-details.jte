@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <script src="/js/return-details.js"></script>
    <span id="subtitle" class="muted"></span>
    <div class="toolbar">
        <button id="refreshBtn" title="Reload">Reload</button>
        <button id="copyBtn" title="Copy table">Copy</button>
    </div>
    <div class="wrap">
        <table>
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
`)