@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="gmvDashboard" class="overview-dashboard gmv-dashboard" aria-label="Monthly GMV (incl. VAT) by company">
        <div class="overview-toolbar">
            <h2 id="gmvDashboardMonth">Current month</h2>
            <div class="overview-controls">
                <div class="overview-month-field">
                    <label for="gmvDashboardMonthSelect">Month</label>
                    <input id="gmvDashboardMonthSelect" type="month" required disabled>
                </div>
                <button id="gmvDashboardCalculate" class="primary-action" type="button">Calculate GMV</button>
            </div>
        </div>
        <p id="gmvDashboardStatus" class="overview-status" role="status" aria-live="polite">Calculating GMV…</p>
        <p id="gmvDashboardEmpty" class="overview-empty" hidden>No companies are available.</p>
        <div id="gmvDashboardCards" class="gmv-dashboard-cards"></div>
        <noscript><p>Enable JavaScript to view the GMV dashboard.</p></noscript>
    </section>
    <script src="/js/chart.umd.min.js"></script>
    <script type="module" src="/js/gmv-dashboard.js"></script>
`)
