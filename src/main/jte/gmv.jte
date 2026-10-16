@import java.util.List
@import ro.sellfluence.app.MonthlyGMVPage
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param List<Vendor> vendors
@param MonthlyGMVPage report
@param String defaultStartDate
@param String defaultEndDate

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    <div class="page-actions gmv-vendor-actions">
        <label for="gmvVendorFilterSelect">Vendor:</label>
        <select id="gmvVendorFilterSelect" class="vendor-filter-select">
            <option value="" selected>All vendors</option>
            @for(var vendor : vendors)
                <option value="${vendor.id().toString()}">${vendor.name()}</option>
            @endfor
        </select>
    </div>

    <section class="gmv-period" aria-labelledby="gmvPeriodHeading">
        <h2 id="gmvPeriodHeading">GMV for a period</h2>
        <form id="gmvPeriodForm" class="gmv-period-form">
            <div class="gmv-period-field gmv-period-product">
                <label for="gmvProductSelect">Product</label>
                <select id="gmvProductSelect" required>
                    <option value="" selected>All products</option>
                    @for(var row : report.rows())
                        <option value="${row.product().productCode()}" data-vendor-id="${row.product().vendor() == null ? "" : row.product().vendor().toString()}">${row.product().name() == null || row.product().name().isBlank() ? row.product().productCode() : row.product().name() + " (" + row.product().productCode() + ")"}</option>
                    @endfor
                </select>
            </div>
            <div class="gmv-period-field">
                <label for="gmvStartDate">Start date</label>
                <input id="gmvStartDate" type="date" value="${defaultStartDate}" required>
            </div>
            <div class="gmv-period-field">
                <label for="gmvEndDate">End date</label>
                <input id="gmvEndDate" type="date" value="${defaultEndDate}" required>
            </div>
            <button id="gmvCalculateButton" type="submit" class="primary-action" disabled>Calculate GMV</button>
        </form>
        <p class="gmv-period-help">Both dates included. Prices include VAT.</p>
        <div class="gmv-period-summary">
            <label for="gmvPeriodResult">GMV</label>
            <button id="gmvPeriodResult" type="button" class="gmv-amount-button" title="View contributing orders" aria-label="View contributing orders" hidden></button>
            <span id="gmvPeriodContext"></span>
        </div>
        <p id="gmvPeriodStatus" role="status" aria-live="polite">Select a product to calculate GMV.</p>
    </section>

    <h2 class="gmv-monthly-heading">Monthly GMV</h2>
    @if(report.months().isEmpty())
        <p class="gmv-monthly-empty muted">No stored GMV available.</p>
    @endif

    <div class="wrap product-sheet-wrap gmv-monthly-wrap">
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
                    <tr data-product-code="${row.product().productCode()}" data-vendor-id="${row.product().vendor() == null ? "" : row.product().vendor().toString()}">
                        <td class="sticky-col sticky-col-name">${row.product().name()}</td>
                        <td class="sticky-col sticky-col-pnk">${row.product().pnk()}</td>
                        <td class="sticky-col sticky-col-product-code">${row.product().productCode()}</td>
                        @for(var month : report.months())
                            <td class="numeric">
                                @if(row.gmvs().containsKey(month))
                                    <button type="button" class="gmv-amount-button gmv-details-trigger" data-product-code="${row.product().productCode()}" data-product-name="${row.product().name() == null ? "" : row.product().name()}" data-month="${month.toString()}" data-gmv="${row.amount(month)}" title="View contributing orders" aria-label="${"View contributing orders for GMV " + row.amount(month) + " for " + row.product().productCode() + " in " + month.toString()}">${row.amount(month)}</button>
                                @else
                                    —
                                @endif
                            </td>
                        @endfor
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>

    <dialog id="gmvDetailsDialog" class="gmv-details-dialog" aria-labelledby="gmvDetailsTitle" aria-describedby="gmvDetailsContext gmvDetailsReference">
        <div class="gmv-details-heading">
            <h2 id="gmvDetailsTitle">Contributing orders</h2>
            <p id="gmvDetailsContext"></p>
            <p id="gmvDetailsReference"></p>
            <p id="gmvDetailsStatus" class="gmv-details-status" role="status" aria-live="polite"></p>
            <p id="gmvDetailsMismatch" class="gmv-details-mismatch" hidden></p>
        </div>
        <div class="gmv-details-table-wrap">
            <table id="gmvDetailsTable" class="gmv-details-table" aria-label="Orders contributing to GMV">
                <thead>
                <tr>
                    <th scope="col">Order ID</th>
                    <th scope="col">Date</th>
                    <th scope="col">Status</th>
                    <th scope="col" class="numeric">Quantity used</th>
                    <th scope="col" class="numeric">Unit price incl. VAT</th>
                    <th scope="col" class="numeric">Seller voucher adjustment</th>
                    <th scope="col" class="numeric">GMV contribution</th>
                </tr>
                </thead>
                <tbody id="gmvDetailsBody"></tbody>
            </table>
        </div>
        <div class="gmv-details-footer">
            <p>Total GMV: <strong id="gmvDetailsTotal">—</strong></p>
            <div class="gmv-details-actions">
                <button id="gmvDetailsDownloadCsv" type="button" disabled>Download CSV</button>
                <button id="gmvDetailsRetry" type="button" hidden>Retry</button>
                <button id="gmvDetailsClose" type="button" autofocus>Close</button>
            </div>
        </div>
    </dialog>

    <script type="module" src="/js/gmv.js"></script>
`)
