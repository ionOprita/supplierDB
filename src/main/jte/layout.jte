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
    <h1>${pageTitle}</h1>
    ${content}
</main>

<footer>
    <small>© Sellfusion 2025</small>
</footer>

</body>
</html>