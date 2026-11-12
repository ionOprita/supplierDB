@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <p class="muted">Capture the JVM's platform threads, full stack traces, and lock information to investigate blocked work. Refresh takes a new snapshot; filtering keeps the current snapshot.</p>
    <div class="page-actions thread-dump-controls" role="group" aria-label="Thread dump controls">
        <button id="threadDumpCapture" type="button">Capture / Refresh</button>
        <div class="thread-dump-filter-field">
            <label for="threadDumpFilter">Thread name</label>
            <input id="threadDumpFilter" type="search" autocomplete="off" placeholder="e.g. BackgroundJob-Worker" disabled />
        </div>
        <button id="threadDumpCopy" type="button" disabled>Copy full dump</button>
        <button id="threadDumpDownload" type="button" disabled>Download full dump</button>
    </div>
    <p id="threadDumpStatus" class="muted" role="status" aria-live="polite"></p>
    <p id="threadDumpSummary" class="muted"></p>
    <pre id="threadDumpContent" class="thread-dump-content" tabindex="0" role="region" aria-label="Thread dump" aria-busy="false">No snapshot captured yet.</pre>
    <script type="module" src="/js/thread-dump.js"></script>
`)
