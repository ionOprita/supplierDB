@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="profitDateCalculPage" aria-labelledby="title">
        <div class="page-actions profit-calculator-filters">
            <label for="dateCalculGroup">Vendor group
                <select id="dateCalculGroup" disabled>
                    <option value="">Loading vendor groups…</option>
                </select>
            </label>
        </div>
        <p id="dateCalculStatus" role="status" aria-live="polite">Loading vendor groups…</p>
        <button id="dateCalculRetry" type="button" hidden>Retry</button>
        <div id="dateCalculEditors" class="date-calcul-editors" aria-busy="true" hidden>
            <section class="card date-calcul-card" aria-labelledby="dateCalculGeneralTitle">
                <h2 id="dateCalculGeneralTitle">Date generale</h2>
                <div id="dateCalculGeneralFields"></div>
            </section>
            <section class="card date-calcul-card" aria-labelledby="dateCalculGeneralCostsTitle">
                <h2 id="dateCalculGeneralCostsTitle">Costuri generale (fără TVA)</h2>
                <div id="dateCalculGeneralCostsFields"></div>
            </section>
            <section class="card date-calcul-card" aria-labelledby="dateCalculEmployeesTitle">
                <h2 id="dateCalculEmployeesTitle">Angajați general</h2>
                <div id="dateCalculEmployeesFields"></div>
            </section>
            <section class="card date-calcul-card" aria-labelledby="dateCalculMiscellaneousTitle">
                <h2 id="dateCalculMiscellaneousTitle">Diverse costuri fără TVA</h2>
                <div id="dateCalculMiscellaneousFields"></div>
            </section>
        </div>
        <noscript><p>Enable JavaScript to view and edit Date Calcul.</p></noscript>
    </section>
    <script type="module" src="/js/profit-date-calcul.js"></script>
`)
