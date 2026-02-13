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
        </div>

        <div class="card"><canvas id="rrrChart"  aria-label="RRR chart"  role="img"></canvas></div>
        <div class="card"><canvas id="ordersChart"  aria-label="Order chart"  role="img"></canvas></div>
        <div class="card"><canvas id="stornoChart"  aria-label="Storno chart" role="img"></canvas></div>
        <div class="card"><canvas id="returnChart"  aria-label="Return chart" role="img"></canvas></div>
    </div>
    <script src="/js/chart.umd.min.js"></script>
    <script src="/js/chartjs-adapter-date-fns.bundle.min.js"></script>
    <script type="module" src="/js/graph.js"></script>
`)