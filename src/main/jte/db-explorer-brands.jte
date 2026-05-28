@import java.util.List
@import ro.sellfluence.db.Brand
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param String activeSubPage
@param List<Brand> brands
@param List<Vendor> vendors
@param String message
@param String error

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
            @if("brands".equals(activeSubPage))
                <li><a href="/admin/db-explorer/brands" aria-current="page">Brands</a></li>
            @else
                <li><a href="/admin/db-explorer/brands">Brands</a></li>
            @endif
            @if("order-counts-by-products".equals(activeSubPage))
                <li><a href="/admin/db-explorer/order-counts-by-products" aria-current="page">Order counts by products</a></li>
            @else
                <li><a href="/admin/db-explorer/order-counts-by-products">Order counts by products</a></li>
            @endif
        </ul>
    </nav>

    @if(message != null && !message.isBlank())
        <p class="flash-message">${message}</p>
    @endif
    @if(error != null && !error.isBlank())
        <p class="flash-message error-message">${error}</p>
    @endif

    <form class="page-actions brand-create-form" method="post" action="/admin/db-explorer/brands">
        <label for="brandNameInput">Brand name:</label>
        <input id="brandNameInput" class="brand-name-input" name="name" type="text" required autocomplete="off">

        <label for="brandVendorSelect">Vendor:</label>
        <select id="brandVendorSelect" name="vendor" required>
            <option value="">Select vendor</option>
            @for(var vendor : vendors)
                @if(vendor.id() != null)
                    <option value="${vendor.id().toString()}">${vendor.name()}</option>
                @endif
            @endfor
        </select>

        <button type="submit" class="primary-action">Add brand</button>
    </form>

    <div class="wrap">
        <table class="brands-table">
            <thead>
            <tr>
                <th>id</th>
                <th>name</th>
                <th>vendor</th>
                <th>vendor_name</th>
                <th>actions</th>
            </tr>
            </thead>
            <tbody>
            @if(brands.isEmpty())
                <tr>
                    <td colspan="5">No brands found.</td>
                </tr>
            @else
                @for(var brand : brands)
                    <tr>
                        <td>${brand.id() == null ? "" : brand.id().toString()}</td>
                        <td>${brand.name()}</td>
                        <td>${brand.vendor() == null ? "" : brand.vendor().toString()}</td>
                        <td>${brand.vendorName()}</td>
                        <td class="row-actions">
                            @if(brand.id() != null)
                                <form action="/admin/db-explorer/brands/${brand.id().toString()}/delete" method="post" onsubmit="return confirm('Delete this brand?');">
                                    <button type="submit">Delete</button>
                                </form>
                            @endif
                        </td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>
`)
