@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="row">
        <div class="card">
            <label for="productSelect">Product</label>
            <select id="productSelect" disabled>
                <option>Loading…</option>
            </select>
            <label for="dateFromInput">From</label>
            <input id="dateFromInput" type="date">
            <label for="dateToInput">To</label>
            <input id="dateToInput" type="date">
            <button id="dateRangeResetBtn" type="button">Reset range</button>
            <p id="rrrMeta" class="muted">
                Cohort-linked 90-day rolling return rate with Bayesian smoothing (m = 100). Markers are shown when S_d >= 20.
            </p>
        </div>

        <div class="card"><canvas id="rrrChart" aria-label="Smoothed rolling return rate chart" role="img"></canvas></div>
        <div class="card"><canvas id="ordersChart" aria-label="Orders chart" role="img"></canvas></div>
        <div class="card"><canvas id="returnChart" aria-label="Returns chart" role="img"></canvas></div>
    </div>
    <script src="/js/chart.umd.min.js"></script>
    <script src="/js/chartjs-adapter-date-fns.bundle.min.js"></script>
    <script type="module" src="/js/rrr.js"></script>
`)
