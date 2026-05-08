@import java.util.List
@import java.net.URLEncoder
@import java.nio.charset.StandardCharsets
@import ro.sellfluence.app.Server.ProductListItem
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param String message
@param List<ProductListItem> products
@param List<Vendor> vendors

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(message != null && !message.isBlank())
        <p class="flash-message">${message}</p>
    @endif

    <div class="page-actions product-list-actions">
        <label for="productVendorFilterSelect">Vendor:</label>
        <select id="productVendorFilterSelect" class="vendor-filter-select">
            <option value="">All vendors</option>
            @for(var vendor : vendors)
                <option value="${vendor.name()}">${vendor.name()}</option>
            @endfor
        </select>
        <form method="get" action="/private/products/new">
            <button type="submit">New product</button>
        </form>
    </div>

    <div class="wrap">
        <table id="productsTable">
            <thead>
            <tr>
                <th>Name</th>
                <th>product_code</th>
                <th>emag_pnk</th>
                <th>Vendor</th>
                <th>State</th>
            </tr>
            </thead>
            <tbody id="productsBody">
            @if(products.isEmpty())
                <tr id="productsNoRows">
                    <td colspan="5">No products found.</td>
                </tr>
            @else
                <tr id="productsNoRows" hidden>
                    <td colspan="5">No products found.</td>
                </tr>
                @for(var product : products)
                    <tr data-vendor="${product.vendorName()}">
                        <td>
                            @if(product.productCode() == null || product.productCode().isBlank())
                                ${product.name() == null ? "" : product.name()}
                            @else
                                <a href="/private/products/edit?productCode=${URLEncoder.encode(product.productCode(), StandardCharsets.UTF_8)}">${product.name() == null ? "" : product.name()}</a>
                            @endif
                        </td>
                        <td>${product.productCode() == null ? "" : product.productCode()}</td>
                        <td>${product.pnk() == null ? "" : product.pnk()}</td>
                        <td>${product.vendorName() == null ? "" : product.vendorName()}</td>
                        <td>${product.state()}</td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>
    <script type="module" src="/js/products.js"></script>
`)
