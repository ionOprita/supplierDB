@import java.util.List
@import java.util.Map
@import ro.sellfluence.app.Server.ProductFormField
@import ro.sellfluence.app.Server.ProductTableRow
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param String message
@param List<ProductTableRow> products
@param List<ProductFormField> fields
@param Map<String, String> blankValues
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
        <button type="button" id="addProductRowButton">Add row</button>
        <button type="button" id="reviewProductChangesButton" class="primary-action" hidden>Review</button>
    </div>

    <div class="wrap product-sheet-wrap">
        <table id="productsTable" class="product-sheet-table">
            <thead>
            <tr>
                @for(var field : fields)
                    <th class="product-sheet-header ${"name".equals(field.name()) ? "sticky-col sticky-col-name" : "pnk".equals(field.name()) ? "sticky-col sticky-col-pnk" : "productCode".equals(field.name()) ? "sticky-col sticky-col-product-code" : ""}" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">${field.label()}</th>
                @endfor
            </tr>
            </thead>
            <tbody id="productsBody">
            @if(products.isEmpty())
                <tr id="productsNoRows">
                    <td colspan="${fields.size()}">No products found.</td>
                </tr>
            @else
                <tr id="productsNoRows" hidden>
                    <td colspan="${fields.size()}">No products found.</td>
                </tr>
                @for(var row : products)
                    <tr class="product-sheet-row" data-mode="update" data-product-code="${row.productCode() == null ? "" : row.productCode()}" data-vendor="${row.vendorName() == null ? "" : row.vendorName()}">
                        @for(var field : fields)
                            <td class="product-sheet-cell ${"name".equals(field.name()) ? "sticky-col sticky-col-name" : "pnk".equals(field.name()) ? "sticky-col sticky-col-pnk" : "productCode".equals(field.name()) ? "sticky-col sticky-col-product-code" : ""}" data-field-name="${field.name()}">
                                @if("vendor".equals(field.inputType()))
                                    <select class="product-cell-input product-sheet-select" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                                        @if(row.values().get(field.name()) == null || row.values().get(field.name()).isBlank())
                                            <option value="" selected>No vendor</option>
                                        @else
                                            <option value="">No vendor</option>
                                        @endif
                                        @for(var vendor : vendors)
                                            @if(vendor.id() != null && vendor.id().toString().equals(row.values().get(field.name())))
                                                <option value="${vendor.id().toString()}" selected>${vendor.name()}</option>
                                            @else
                                                <option value="${vendor.id().toString()}">${vendor.name()}</option>
                                            @endif
                                        @endfor
                                    </select>
                                @else
                                    @if("boolean".equals(field.inputType()))
                                        <select class="product-cell-input product-sheet-select product-sheet-select-boolean" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                                            @if("true".equals(row.values().get(field.name())))
                                                <option value="true" selected>true</option>
                                            @else
                                                <option value="true">true</option>
                                            @endif
                                            @if("false".equals(row.values().get(field.name())))
                                                <option value="false" selected>false</option>
                                            @else
                                                <option value="false">false</option>
                                            @endif
                                        </select>
                                    @else
                                        @if("nullableBoolean".equals(field.inputType()))
                                            <select class="product-cell-input product-sheet-select product-sheet-select-boolean" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                                                @if(row.values().get(field.name()) == null || row.values().get(field.name()).isBlank())
                                                    <option value="" selected></option>
                                                @else
                                                    <option value=""></option>
                                                @endif
                                                @if("true".equals(row.values().get(field.name())))
                                                    <option value="true" selected>true</option>
                                                @else
                                                    <option value="true">true</option>
                                                @endif
                                                @if("false".equals(row.values().get(field.name())))
                                                    <option value="false" selected>false</option>
                                                @else
                                                    <option value="false">false</option>
                                                @endif
                                            </select>
                                        @else
                                            @if("textarea".equals(field.inputType()))
                                                <textarea class="product-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${row.values().get(field.name())}</textarea>
                                            @else
                                                @if("integer".equals(field.inputType()))
                                                    <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${row.values().get(field.name())}">
                                                @else
                                                    @if("decimal".equals(field.inputType()))
                                                        <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" inputmode="decimal" value="${row.values().get(field.name())}">
                                                    @else
                                                        @if("productCode".equals(field.name()))
                                                            <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}" readonly required>
                                                        @else
                                                            @if(field.required())
                                                                <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}" required>
                                                            @else
                                                                <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}">
                                                            @endif
                                                        @endif
                                                    @endif
                                                @endif
                                            @endif
                                        @endif
                                    @endif
                                @endif
                            </td>
                        @endfor
                    </tr>
                @endfor
            @endif
            </tbody>
        </table>
    </div>

    <template id="productRowTemplate">
        <tr class="product-sheet-row" data-mode="insert" data-product-code="" data-vendor="" data-new-row="true">
            @for(var field : fields)
                <td class="product-sheet-cell ${"name".equals(field.name()) ? "sticky-col sticky-col-name" : "pnk".equals(field.name()) ? "sticky-col sticky-col-pnk" : "productCode".equals(field.name()) ? "sticky-col sticky-col-product-code" : ""}" data-field-name="${field.name()}">
                    @if("vendor".equals(field.inputType()))
                        <select class="product-cell-input product-sheet-select" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                            <option value="" selected>No vendor</option>
                            @for(var vendor : vendors)
                                <option value="${vendor.id().toString()}">${vendor.name()}</option>
                            @endfor
                        </select>
                    @else
                        @if("boolean".equals(field.inputType()))
                            <select class="product-cell-input product-sheet-select product-sheet-select-boolean" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                                @if("true".equals(blankValues.get(field.name())))
                                    <option value="true" selected>true</option>
                                @else
                                    <option value="true">true</option>
                                @endif
                                @if("false".equals(blankValues.get(field.name())))
                                    <option value="false" selected>false</option>
                                @else
                                    <option value="false">false</option>
                                @endif
                            </select>
                        @else
                            @if("nullableBoolean".equals(field.inputType()))
                                <select class="product-cell-input product-sheet-select product-sheet-select-boolean" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">
                                    <option value="" selected></option>
                                    <option value="true">true</option>
                                    <option value="false">false</option>
                                </select>
                            @else
                                @if("textarea".equals(field.inputType()))
                                    <textarea class="product-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${blankValues.get(field.name())}</textarea>
                                @else
                                    @if("integer".equals(field.inputType()))
                                        <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${blankValues.get(field.name())}">
                                    @else
                                        @if("decimal".equals(field.inputType()))
                                            <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" inputmode="decimal" value="${blankValues.get(field.name())}">
                                        @else
                                            @if(field.required())
                                                <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}" required>
                                            @else
                                                <input class="product-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}">
                                            @endif
                                        @endif
                                    @endif
                                @endif
                            @endif
                        @endif
                    @endif
                </td>
            @endfor
        </tr>
    </template>

    <dialog id="productReviewDialog" class="product-review-dialog">
        <div class="product-review-content">
            <h2>Review changes</h2>
            <p id="productReviewSummary" class="muted"></p>
            <div class="product-review-changes-wrap">
                <table class="product-review-table">
                    <thead>
                    <tr>
                        <th>Product</th>
                        <th>Field</th>
                        <th>Original</th>
                        <th>New</th>
                    </tr>
                    </thead>
                    <tbody id="productReviewChangesBody"></tbody>
                </table>
            </div>
            <p id="productReviewError" class="flash-message error-message" hidden></p>
            <div class="product-review-actions">
                <button type="button" id="saveProductChangesButton" class="primary-action">Save</button>
                <button type="button" id="revertProductChangesButton">Revert</button>
                <button type="button" id="continueProductEditingButton">Continue to edit</button>
            </div>
        </div>
    </dialog>

    <script type="module" src="/js/products.js"></script>
`)
