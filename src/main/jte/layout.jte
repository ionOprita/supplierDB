@import gg.jte.Content

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

<body>

<header style="position: relative;">
    <div style="position: absolute; top: 10px; right: 10px; background: #333; color: white;
        padding: 6px 10px; border-radius: 4px; font-size: 13px;">
        Logged in as <strong>${userName}</strong>
        @if(userRole != null)
            (${userRole})
        @endif

        <form action="/logout" method="post" style="display:inline; margin-left:8px;">
            <button type="submit">Logout</button>
        </form>
    </div>
</header>

<main style="margin-top: 60px;">
    @if(userRole != null && !userRole.equals("nobody"))
        <nav class="app-menu" aria-label="Main menu">
            <ul>
                @if(userRole.equals("admin"))
                    <li><a href="/admin/users">Administrate Users</a></li>
                @endif
                <li><a href="/private/orders">Order Table</a></li>
                <li><a href="/private/storno">Storno Table</a></li>
                <li><a href="/private/returns">Return Table</a></li>
                <li><a href="/private/returnCharts">Return Charts</a></li>
                <li><a href="/private/tasks">Tasks</a></li>
            </ul>
        </nav>
    @endif
    <h1 id="title">${pageTitle}</h1>
    ${content}
</main>

<footer>
    <small>© Sellfusion 2025 - 2026</small>
</footer>

</body>
</html>
