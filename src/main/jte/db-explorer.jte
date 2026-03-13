@param String userName
@param String userRole
@param String pageTitle

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <nav class="app-menu" aria-label="DB Explorer menu">
        <ul class="menu-row">
            <li><a href="/admin/db-explorer/products">Products</a></li>
            <li><a href="/admin/db-explorer/vendors">Vendors</a></li>
            <li><a href="/admin/db-explorer/order-counts-by-products">Order counts by products</a></li>
        </ul>
    </nav>
    <p>Select a DB Explorer page.</p>
`)
