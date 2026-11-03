@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="overviewDashboard" class="overview-dashboard" aria-label="Monthly ordered and returned units">
        <div class="overview-toolbar">
            <div>
                <h2 id="overviewMonth">Current month</h2>
                <p id="overviewContext" class="overview-context">Compare daily ordered and returned units across companies.</p>
                <p class="overview-definitions">Orders show product units from finalized and returned orders by order date. Returns show product units from finalized return requests by request date, without subtracting them from Orders.</p>
            </div>
            <div class="overview-controls">
                <div class="overview-month-field">
                    <label for="overviewMonthSelect">Month</label>
                    <input id="overviewMonthSelect" type="month" required disabled>
                </div>
                <button id="overviewRefresh" type="button">Refresh</button>
            </div>
        </div>
        <p id="overviewStatus" class="overview-status" role="status" aria-live="polite">Loading dashboard…</p>
        <p id="overviewUnassigned" class="overview-notice" hidden></p>
        <p id="overviewEmpty" class="overview-empty" hidden>No companies are available.</p>
        <div id="overviewCompanies" class="overview-chart-pair"></div>
        <noscript><p>Enable JavaScript to view the ordered and returned units dashboard.</p></noscript>
    </section>
    <script src="/js/chart.umd.min.js"></script>
    <script type="module" src="/js/overview.js"></script>
`)
