@import java.time.LocalDate
@import java.time.YearMonth
@import java.util.List
@import java.util.Map
@import ro.sellfluence.db.ProductTable.ProductInfo

@param String userName
@param String userRole
@param String pageTitle
@param String activeSubPage
@param List<ProductInfo> products
@param List<YearMonth> months
@param String selectedProductCode
@param String selectedMonth
@param List<LocalDate> monthDays
@param Map<LocalDate, Long> soldQtyByDay

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

    <form id="orderCountsByProductForm" class="page-actions" method="get" action="/admin/db-explorer/order-counts-by-products">
        <label for="productCodeSelect">Product:</label>
        <select id="productCodeSelect" name="productCode">
            @for(var product : products)
                @if(product.productCode() == null || product.productCode().isBlank())
                    <option value="" disabled>${product.name()}</option>
                @else
                    @if(product.productCode().equals(selectedProductCode))
                        <option value="${product.productCode()}" selected>${product.name()}</option>
                    @else
                        <option value="${product.productCode()}">${product.name()}</option>
                    @endif
                @endif
            @endfor
        </select>

        <label for="monthSelect">Year-Month:</label>
        <select id="monthSelect" name="month">
            @for(var month : months)
                @if(month.toString().equals(selectedMonth))
                    <option value="${month.toString()}" selected>${month.toString()}</option>
                @else
                    <option value="${month.toString()}">${month.toString()}</option>
                @endif
            @endfor
        </select>
        <button type="submit">Apply</button>
    </form>

    <div class="wrap">
        <table>
            <thead>
            <tr>
                <th>day</th>
                <th class="numeric">items_sold</th>
            </tr>
            </thead>
            <tbody>
            @if(months.isEmpty())
                <tr>
                    <td colspan="2">No data available in sales_daily.</td>
                </tr>
            @else
                @for(var day : monthDays)
                    <tr>
                        <td>${day.toString()}</td>
                        <td class="numeric">${soldQtyByDay.getOrDefault(day, 0L)}</td>
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>

    <script>
        (() => {
          const form = document.getElementById('orderCountsByProductForm');
          document.getElementById('productCodeSelect')?.addEventListener('change', () => form?.submit());
          document.getElementById('monthSelect')?.addEventListener('change', () => form?.submit());
        })();
    </script>
`)
