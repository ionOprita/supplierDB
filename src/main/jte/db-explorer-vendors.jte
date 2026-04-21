@import java.util.List
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param String activeSubPage
@param List<Vendor> vendors

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <nav class="app-menu" aria-label="DB Explorer menu">
        <ul class="menu-row">
            @if("products".equals(activeSubPage))
                <li><a href="/admin/db-explorer/products" aria-current="page">Products</a></li>
            @else
                <li><a href="/admin/db-explorer/products">Products</a></li>
            @endif
            @if("vendors".equals(activeSubPage))
                <li><a href="/admin/db-explorer/vendors" aria-current="page">Vendors</a></li>
            @else
                <li><a href="/admin/db-explorer/vendors">Vendors</a></li>
            @endif
            @if("order-counts-by-products".equals(activeSubPage))
                <li><a href="/admin/db-explorer/order-counts-by-products" aria-current="page">Order counts by products</a></li>
            @else
                <li><a href="/admin/db-explorer/order-counts-by-products">Order counts by products</a></li>
            @endif
        </ul>
    </nav>

    <div class="wrap">
        <table>
            <thead>
            <tr>
                <th>id</th>
                <th>vendor_name</th>
                <th>isfbe</th>
                <th>company_name</th>
                <th>account</th>
                <th>last_fetch</th>
            </tr>
            </thead>
            <tbody>
            @if(vendors.isEmpty())
                <tr>
                    <td colspan="6">No vendors found.</td>
                </tr>
            @else
                @for(var vendor : vendors)
                    <tr>
                        <td>${vendor.id() == null ? "" : vendor.id().toString()}</td>
                        <td>${vendor.name()}</td>
                        <td>${vendor.isFBE()}</td>
                        <td>${vendor.companyName()}</td>
                        <td>${vendor.account()}</td>
                        <td>${vendor.lastFetch() == null ? "" : vendor.lastFetch().toString()}</td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>
`)
