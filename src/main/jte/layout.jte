@import gg.jte.Content
@import java.util.Locale

@param String userName
@param String userRole
@param String pageTitle
@param Content content

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width,initial-scale=1"/>
    <title>${pageTitle}</title>
    <link rel="stylesheet" href="/styles.css">
</head>

<body class="${userRole != null && !userRole.equals("nobody") ? "app-shell app-shell--with-nav" : "app-shell"}">
<a class="app-skip-link" href="#appMain">Skip to content</a>

<header id="appHeader" class="app-topbar">
    @if(userRole != null && !userRole.equals("nobody"))
        <button id="appNavToggle" class="app-nav-toggle" type="button" aria-controls="appSidebar" aria-expanded="false" aria-label="Open navigation">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true" focusable="false">
                <path d="M4 6h16M4 12h16M4 18h16" />
            </svg>
        </button>
    @endif
    <a class="app-brand" href="/private/orders">
        <svg class="app-brand-mark" width="28" height="28" viewBox="0 0 32 32" fill="none" aria-hidden="true" focusable="false">
            <rect x="1" y="1" width="30" height="30" rx="8" fill="currentColor" />
            <path d="M10 21V17M16 21V11M22 21V14" stroke="white" stroke-width="2.5" stroke-linecap="round" />
        </svg>
        <span>Sellfusion</span>
    </a>
    <div class="app-account" role="group" aria-label="Signed in as ${userName}">
        <span class="app-account-avatar" aria-hidden="true">${userName == null || userName.isBlank() ? "?" : userName.substring(0, 1).toUpperCase(Locale.ROOT)}</span>
        <div class="app-account-details">
            <strong class="app-account-name">${userName}</strong>
            @if(userRole != null)
                <span class="app-account-role">${userRole}</span>
            @endif
        </div>
        <form action="/logout" method="post" class="app-logout-form">
            <button type="submit">Logout</button>
        </form>
    </div>
</header>

@if(userRole != null && !userRole.equals("nobody"))
    <aside id="appSidebar" class="app-sidebar" aria-labelledby="appNavTitle">
        <div class="app-sidebar-header">
            <h2 id="appNavTitle">Navigation</h2>
            <button id="appNavClose" class="app-nav-close" type="button" aria-label="Close navigation">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true" focusable="false">
                    <path d="m6 6 12 12M18 6 6 18" />
                </svg>
            </button>
        </div>
        <nav id="appNavigation" class="app-navigation" aria-label="Main menu">
            <section class="app-nav-group">
                <h2 class="app-nav-heading">Sales performance</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/gmv">GMV</a></li>
                    <li><a class="app-nav-link" href="/private/product-performance">Product Performance</a></li>
                </ul>
            </section>
            <section class="app-nav-group">
                <h2 class="app-nav-heading">Orders &amp; returns</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/orders">Order Table</a></li>
                    <li><a class="app-nav-link" href="/private/storno">Storno Table</a></li>
                    <li><a class="app-nav-link" href="/private/returns">Return Table</a></li>
                </ul>
            </section>
            <section class="app-nav-group">
                <h2 class="app-nav-heading">Return analysis</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/storno-rate">Storno % Table</a></li>
                    <li><a class="app-nav-link" href="/private/return-rate">Return % Table</a></li>
                    <li><a class="app-nav-link" href="/private/current-rates">Current Rates</a></li>
                    <li><a class="app-nav-link" href="/private/current-rates-categories">Current Rates by Category</a></li>
                    <li><a class="app-nav-link" href="/private/returnCharts">Return Charts</a></li>
                    <li><a class="app-nav-link" href="/private/rrr">RRR (Smoothed)</a></li>
                </ul>
            </section>
            <section class="app-nav-group">
                <h2 class="app-nav-heading">Advertising</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/ads-campaigns">Ads Campaigns</a></li>
                </ul>
            </section>
            <section class="app-nav-group">
                <h2 class="app-nav-heading">Business data</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/products">Products</a></li>
                    <li><a class="app-nav-link" href="/private/categories">Categories</a></li>
                    <li><a class="app-nav-link" href="/private/employees">Employees</a></li>
                </ul>
            </section>
            <section class="app-nav-group">
                <h2 class="app-nav-heading">System</h2>
                <ul class="app-nav-list">
                    <li><a class="app-nav-link" href="/private/tasks">Tasks</a></li>
                    @if(userRole.equals("admin"))
                        <li><a class="app-nav-link" href="/admin/users">Administrate Users</a></li>
                        <li><a class="app-nav-link" href="/admin/db-explorer/products">DB Explorer</a></li>
                        <li><a class="app-nav-link" href="/admin/logs">Server Logs</a></li>
                    @endif
                </ul>
            </section>
        </nav>
        @if(userRole.equals("admin"))
            <div class="app-sidebar-footer">
                <button type="button" id="serverStopButton" class="danger-action server-stop-button">Stop Server</button>
            </div>
        @endif
    </aside>
    <button id="appNavBackdrop" class="app-nav-backdrop" type="button" hidden tabindex="-1" aria-label="Close navigation"></button>
@endif

<main id="appMain" class="app-main" tabindex="-1">
    <h1 id="title">${pageTitle}</h1>
    ${content}
</main>

<footer id="appFooter" class="app-footer">
    <small>© Sellfusion 2025 - 2026</small>
</footer>

@if(userRole != null && userRole.equals("admin"))
    <dialog id="serverStopDialog" class="server-stop-dialog">
        <form method="dialog" class="server-stop-dialog-content">
            <h2>Stop server?</h2>
            <p>The Java process will exit. The run script should restart it shortly.</p>
            <p id="serverStopStatus" class="server-stop-status" role="status" aria-live="polite"></p>
            <div class="server-stop-dialog-actions">
                <button type="submit" id="serverStopCancelButton">Cancel</button>
                <button type="button" id="serverStopConfirmButton" class="danger-action">Proceed</button>
            </div>
        </form>
    </dialog>
@endif

<script type="module" src="/js/app-shell.js"></script>
@if(userRole != null && userRole.equals("admin"))
    <script src="/js/passkey.js"></script>
    <script src="/js/server-stop.js"></script>
@endif
</body>
</html>
