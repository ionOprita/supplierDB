@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="overviewDashboard" class="overview-dashboard" aria-label="Monthly orders and returns">
        <div class="overview-toolbar">
            <div>
                <h2 id="overviewMonth">Current month</h2>
                <p id="overviewContext" class="overview-context">Compare daily orders and returns across companies.</p>
                <p class="overview-definitions">Orders exclude canceled orders and use the order date. Returns include finalized returns and use the return request date.</p>
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
        <noscript><p>Enable JavaScript to view the orders and returns dashboard.</p></noscript>
    </section>
    <script src="/js/chart.umd.min.js"></script>
    <script type="module" src="/js/overview.js"></script>
`)
