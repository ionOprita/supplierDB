@import java.util.List
@import ro.sellfluence.app.MonthlyGMVPage
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param List<Vendor> vendors
@param MonthlyGMVPage report

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(report.months().isEmpty())
        <p class="muted">No stored GMV available.</p>
    @endif

    <div class="page-actions product-list-actions">
        <label for="gmvVendorFilterSelect">Vendor:</label>
        <select id="gmvVendorFilterSelect" class="vendor-filter-select">
            <option value="" selected>All vendors</option>
            @for(var vendor : vendors)
                <option value="${vendor.id().toString()}">${vendor.name()}</option>
            @endfor
        </select>
    </div>

    <div class="wrap product-sheet-wrap">
        <table id="monthlyGmvTable" class="product-sheet-table gmv-sheet-table">
            <thead>
            <tr>
                <th scope="col" class="sticky-col sticky-col-name">Product</th>
                <th scope="col" class="sticky-col sticky-col-pnk">PNK</th>
                <th scope="col" class="sticky-col sticky-col-product-code">Product code</th>
                @for(var month : report.months())
                    <th scope="col" class="numeric">${month.toString()}</th>
                @endfor
            </tr>
            </thead>
            <tbody>
            @if(report.rows().isEmpty())
                <tr id="gmvNoRows">
                    <td colspan="${3 + report.months().size()}">No products found.</td>
                </tr>
            @else
                <tr id="gmvNoRows" hidden>
                    <td colspan="${3 + report.months().size()}">No products found.</td>
                </tr>
                @for(var row : report.rows())
                    <tr data-vendor-id="${row.product().vendor() == null ? "" : row.product().vendor().toString()}">
                        <td class="sticky-col sticky-col-name">${row.product().name()}</td>
                        <td class="sticky-col sticky-col-pnk">${row.product().pnk()}</td>
                        <td class="sticky-col sticky-col-product-code">${row.product().productCode()}</td>
                        @for(var month : report.months())
                            <td class="numeric">${row.amount(month)}</td>
                        @endfor
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>

    <script type="module" src="/js/gmv.js"></script>
`)
