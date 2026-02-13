@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <ul>
        ${
        userRole.equals("admin")?@`
            <li><a href="/admin/users">Administrate Users</a></li>
        `:@`
        `
}
        <li><a href="/private/orders">Order Table</a></li>
        <li><a href="/private/storno">Storno Table</a></li>
        <li><a href="/private/returns">Return Table</a></li>
        <li><a href="/private/returnCharts">Return Charts</a></li>
        <li><a href="/private/tasks">Tasks</a></li>
    </ul>
`)