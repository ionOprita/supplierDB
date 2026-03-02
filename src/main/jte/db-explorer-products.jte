@import java.util.List
@import ro.sellfluence.db.ProductTable.ProductInfo

@param String userName
@param String userRole
@param String pageTitle
@param String activeSubPage
@param List<ProductInfo> products

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
        </ul>
    </nav>

    <div class="wrap">
        <table>
            <thead>
            <tr>
                <th>emag_pnk</th>
                <th>product_code</th>
                <th>name</th>
                <th>vendor</th>
                <th>continue_to_sell</th>
                <th>retracted</th>
                <th>category</th>
                <th>message_keyword</th>
                <th>employee_sheet_name</th>
                <th>employee_sheet_tab</th>
            </tr>
            </thead>
            <tbody>
            @if(products.isEmpty())
                <tr>
                    <td colspan="10">No products found.</td>
                </tr>
            @else
                @for(var product : products)
                    <tr>
                        <td>${product.pnk()}</td>
                        <td>${product.productCode()}</td>
                        <td>${product.name()}</td>
                        <td>${product.vendor() == null ? "" : product.vendor().toString()}</td>
                        <td>${product.continueToSell()}</td>
                        <td>${product.retracted()}</td>
                        <td>${product.category()}</td>
                        <td>${product.messageKeyword()}</td>
                        <td>${product.employeeSheetName()}</td>
                        <td>${product.employeeSheetTab()}</td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>
`)
