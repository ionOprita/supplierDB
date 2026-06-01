@import java.util.List
@import java.util.Map
@import ro.sellfluence.app.Server.EmployeeFormField
@import ro.sellfluence.app.Server.EmployeeTableRow

@param String userName
@param String userRole
@param String pageTitle
@param String message
@param List<EmployeeTableRow> employees
@param List<EmployeeFormField> fields
@param Map<String, String> blankValues
@param List<String> departments

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(message != null && !message.isBlank())
        <p class="flash-message">${message}</p>
    @endif

    <div class="page-actions product-list-actions">
        <label for="employeeDepartmentFilterSelect">Department:</label>
        <select id="employeeDepartmentFilterSelect" class="vendor-filter-select">
            <option value="">All departments</option>
            @for(var department : departments)
                <option value="${department}">${department}</option>
            @endfor
        </select>
        <button type="button" id="addEmployeeRowButton">Add row</button>
        <button type="button" id="reviewEmployeeChangesButton" class="primary-action" hidden>Review</button>
    </div>

    <div class="wrap product-sheet-wrap">
        <table id="employeeDataTable" class="product-sheet-table employee-sheet-table">
            <thead>
            <tr>
                @for(var field : fields)
                    <th class="${"full_name".equals(field.name()) ? "sticky-col sticky-col-employee-name" : "company".equals(field.name()) ? "sticky-col sticky-col-employee-company" : "department".equals(field.name()) ? "sticky-col sticky-col-employee-department" : ""}" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}">${field.label()}</th>
                @endfor
            </tr>
            </thead>
            <tbody id="employeesBody">
            @if(employees.isEmpty())
                <tr id="employeesNoRows">
                    <td colspan="${fields.size()}">No employee entries found.</td>
                </tr>
            @else
                <tr id="employeesNoRows" hidden>
                    <td colspan="${fields.size()}">No employee entries found.</td>
                </tr>
                @for(var row : employees)
                    <tr class="employee-sheet-row" data-mode="update" data-source-row-number="${row.sourceRowNumber()}" data-department="${row.department() == null ? "" : row.department()}">
                        @for(var field : fields)
                            <td class="product-sheet-cell ${"full_name".equals(field.name()) ? "sticky-col sticky-col-employee-name" : "company".equals(field.name()) ? "sticky-col sticky-col-employee-company" : "department".equals(field.name()) ? "sticky-col sticky-col-employee-department" : ""}" data-field-name="${field.name()}">
                                @if("textarea".equals(field.inputType()))
                                    <textarea class="employee-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${row.values().get(field.name())}</textarea>
                                @endif

                                @if("integer".equals(field.inputType()))
                                    <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${row.values().get(field.name())}" readonly required>
                                @endif

                                @if("text".equals(field.inputType()))
                                    @if(field.required())
                                        <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}" required>
                                    @else
                                        <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${row.values().get(field.name())}">
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

    <template id="employeeRowTemplate">
        <tr class="employee-sheet-row" data-mode="insert" data-source-row-number="" data-department="" data-new-row="true">
            @for(var field : fields)
                <td class="product-sheet-cell ${"full_name".equals(field.name()) ? "sticky-col sticky-col-employee-name" : "company".equals(field.name()) ? "sticky-col sticky-col-employee-company" : "department".equals(field.name()) ? "sticky-col sticky-col-employee-department" : ""}" data-field-name="${field.name()}">
                    @if("textarea".equals(field.inputType()))
                        <textarea class="employee-cell-input product-sheet-textarea" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" rows="1">${blankValues.get(field.name())}</textarea>
                    @endif

                    @if("integer".equals(field.inputType()))
                        <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="number" step="1" value="${blankValues.get(field.name())}" required>
                    @endif

                    @if("text".equals(field.inputType()))
                        @if(field.required())
                            <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}" required>
                        @else
                            <input class="employee-cell-input product-sheet-input" data-field="${field.name()}" data-label="${field.label()}" data-input-type="${field.inputType()}" type="text" value="${blankValues.get(field.name())}">
                        @endif
                    @endif
                </td>
            @endfor
        </tr>
    </template>

    <dialog id="employeeReviewDialog" class="product-review-dialog">
        <div class="product-review-content">
            <h2>Review changes</h2>
            <p id="employeeReviewSummary" class="muted"></p>
            <div class="product-review-changes-wrap">
                <table class="product-review-table">
                    <thead>
                    <tr>
                        <th>Employee</th>
                        <th>Field</th>
                        <th>Original</th>
                        <th>New</th>
                    </tr>
                    </thead>
                    <tbody id="employeeReviewChangesBody"></tbody>
                </table>
            </div>
            <p id="employeeReviewError" class="flash-message error-message" hidden></p>
            <div class="product-review-actions">
                <button type="button" id="saveEmployeeChangesButton" class="primary-action">Save</button>
                <button type="button" id="revertEmployeeChangesButton">Revert</button>
                <button type="button" id="continueEmployeeEditingButton">Continue to edit</button>
            </div>
        </div>
    </dialog>

    <script type="module" src="/js/employees.js"></script>
`)
