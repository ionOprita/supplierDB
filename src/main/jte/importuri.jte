@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <section id="stockImports" lang="ro" aria-labelledby="title">
        <div class="imports-context">
            <label for="importGroup">Grup de firme
                <select id="importGroup" disabled>
                    <option>Se încarcă…</option>
                </select>
            </label>
            <a id="newImport" class="imports-add" hidden>Adaugă import</a>
            <a id="backToImports" hidden>Înapoi la registru</a>
        </div>
        <div id="importNotice" role="status" aria-live="polite"></div>
        <button id="importsRetry" type="button" hidden>Reîncearcă</button>
        <section id="importsRegister" hidden aria-labelledby="registerTitle">
            <div class="imports-register-tools">
                <h2 id="registerTitle">Registrul de importuri</h2>
                <label for="importSearch">Caută în registru
                    <input id="importSearch" type="search" placeholder="Referință, firmă, status…" autocomplete="off">
                </label>
            </div>
            <div id="importsList"></div>
        </section>
        <form id="importEditor" hidden>
            <div class="imports-savebar">
                <div>
                    <h2 id="importHeading">Import nou</h2>
                    <span id="importState"></span>
                </div>
                <div class="imports-save-actions">
                    <span id="importSaveStatus" role="status" aria-live="polite"></span>
                    <button id="saveImport" type="submit" class="primary-action">Salvează</button>
                </div>
            </div>
            <div id="importSections"></div>
            <section class="imports-section" aria-labelledby="importCostsHeading">
                <h3 id="importCostsHeading">Calculul importului</h3>
                <div id="importCosts" aria-live="polite"></div>
            </section>
        </form>
        <noscript>Activează JavaScript pentru a vedea și edita importurile.</noscript>
    </section>
    <script type="module" src="/js/importuri.js"></script>
`)
