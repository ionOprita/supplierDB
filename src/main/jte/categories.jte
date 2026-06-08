@import java.util.List
@import java.util.Map
@import ro.sellfluence.app.Server.CategoryFormField
@import ro.sellfluence.app.Server.CategoryTableRow

@param String userName
@param String userRole
@param String pageTitle
@param String message
@param List<CategoryTableRow> categories
@param List<CategoryFormField> fields
@param Map<String, String> blankValues
@param List<String> divisions

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(message != null && !message.isBlank())
        <p class="flash-message">${message}</p>
    @endif

    <div class="page-actions product-list-actions">
        <label for="categoryDivisionFilterSelect">Division:</label>
        <select id="categoryDivisionFilterSelect" class="vendor-filter-select">
            <option value="">All divisions</option>
            @for(var division : divisions)
                <option value="${division}">${division}</option>
            @endfor
        </select>
        <button type="button" id="addCategoryRowButton">Add row</button>
        <button type="button" id="reviewCategoryChangesButton" class="primary-action" hidden>Review</button>
    </div>

    <div class="wrap product-sheet-wrap">
        <table id="categoryDataTable" class="product-sheet-table category-sheet-table">
            <thead>
            <tr>
                @for(var field : fields)
                    <th class="${"subcategory_country".equals(field.name()) ? "sticky-col sticky-col-category-subcategory-country" : "big_category".equals(field.name()) ? "sticky-col sticky-col-category-big-category" : "division".equals(field.name()) ? "sticky-col sticky-col-category-division" : ""}" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">${field.label()}</th>
                @endfor
            </tr>
            </thead>
            <tbody id="categoriesBody">
            @if(categories.isEmpty())
                <tr id="categoriesNoRows">
                    <td colspan="${fields.size()}">No category entries found.</td>
                </tr>
            @else
                <tr id="categoriesNoRows" hidden>
                    <td colspan="${fields.size()}">No category entries found.</td>
                </tr>
                @for(var row : categories)
                    <tr class="category-sheet-row" data-mode="update" data-source-row-number="${row.sourceRowNumber()}" data-division="${row.division() == null ? "" : row.division()}">
                        @for(var field : fields)
                            <td class="product-sheet-cell ${"subcategory_country".equals(field.name()) ? "sticky-col sticky-col-category-subcategory-country" : "big_category".equals(field.name()) ? "sticky-col sticky-col-category-big-category" : "division".equals(field.name()) ? "sticky-col sticky-col-category-division" : ""}" data-field-name="${field.name()}">
                                @if("textarea".equals(field.inputType()))
                                    <textarea class="category-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${row.values().get(field.name())}</textarea>
                                @endif

                                @if("integer".equals(field.inputType()))
                                    @if(field.required())
                                        <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${row.values().get(field.name())}" readonly required>
                                    @else
                                        <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${row.values().get(field.name())}">
                                    @endif
                                @endif

                                @if("text".equals(field.inputType()))
                                    @if(field.required())
                                        <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}" required>
                                    @else
                                        <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}">
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

    <template id="categoryRowTemplate">
        <tr class="category-sheet-row" data-mode="insert" data-source-row-number="" data-division="" data-new-row="true">
            @for(var field : fields)
                <td class="product-sheet-cell ${"subcategory_country".equals(field.name()) ? "sticky-col sticky-col-category-subcategory-country" : "big_category".equals(field.name()) ? "sticky-col sticky-col-category-big-category" : "division".equals(field.name()) ? "sticky-col sticky-col-category-division" : ""}" data-field-name="${field.name()}">
                    @if("textarea".equals(field.inputType()))
                        <textarea class="category-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${blankValues.get(field.name())}</textarea>
                    @endif

                    @if("integer".equals(field.inputType()))
                        @if(field.required())
                            <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${blankValues.get(field.name())}" required>
                        @else
                            <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${blankValues.get(field.name())}">
                        @endif
                    @endif

                    @if("text".equals(field.inputType()))
                        @if(field.required())
                            <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}" required>
                        @else
                            <input class="category-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}">
                        @endif
                    @endif
                </td>
            @endfor
        </tr>
    </template>

    <dialog id="categoryReviewDialog" class="product-review-dialog">
        <div class="product-review-content">
            <h2>Review changes</h2>
            <p id="categoryReviewSummary" class="muted"></p>
            <div class="product-review-changes-wrap">
                <table class="product-review-table">
                    <thead>
                    <tr>
                        <th>Category</th>
                        <th>Field</th>
                        <th>Original</th>
                        <th>New</th>
                    </tr>
                    </thead>
                    <tbody id="categoryReviewChangesBody"></tbody>
                </table>
            </div>
            <p id="categoryReviewError" class="flash-message error-message" hidden></p>
            <div class="product-review-actions">
                <button type="button" id="saveCategoryChangesButton" class="primary-action">Save</button>
                <button type="button" id="revertCategoryChangesButton">Revert</button>
                <button type="button" id="continueCategoryEditingButton">Continue to edit</button>
            </div>
        </div>
    </dialog>

    <script type="module" src="/js/categories.js"></script>
`)
