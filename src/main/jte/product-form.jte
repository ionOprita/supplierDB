@import java.util.List
@import java.util.Map
@import ro.sellfluence.app.Server.ProductFormField
@import ro.sellfluence.db.Vendor

@param String userName
@param String userRole
@param String pageTitle
@param String action
@param String submitLabel
@param boolean readOnlyProductCode
@param String error
@param List<ProductFormField> fields
@param Map<String, String> values
@param List<Vendor> vendors

@template.layout(userName = userName, userRole = userRole, pageTitle = pageTitle, content = @`
    @if(error != null && !error.isBlank())
        <p class="flash-message error-message">${error}</p>
    @endif

    <div class="product-form-actions">
        <button type="submit" form="productForm">${submitLabel}</button>
        <form method="get" action="/private/products">
            <button type="submit">Cancel</button>
        </form>
    </div>

    <form id="productForm" class="product-form" method="post" action="${action}">
        <div class="product-form-grid">
            @for(var field : fields)
                <div class="product-form-field">
                    <label for="${field.name()}">${field.label()}</label>
                    @if("productCode".equals(field.name()))
                        @if(readOnlyProductCode)
                            <input id="${field.name()}" name="${field.name()}" type="text" value="${values.get(field.name())}" readonly required>
                        @else
                            <input id="${field.name()}" name="${field.name()}" type="text" value="${values.get(field.name())}" required>
                        @endif
                    @else
                        @if("vendor".equals(field.inputType()))
                            <select id="${field.name()}" name="${field.name()}">
                                @if(values.get(field.name()) == null || values.get(field.name()).isBlank())
                                    <option value="" selected>No vendor</option>
                                @else
                                    <option value="">No vendor</option>
                                @endif
                                @for(var vendor : vendors)
                                    @if(vendor.id() != null && vendor.id().toString().equals(values.get(field.name())))
                                        <option value="${vendor.id().toString()}" selected>${vendor.name()}</option>
                                    @else
                                        <option value="${vendor.id().toString()}">${vendor.name()}</option>
                                    @endif
                                @endfor
                            </select>
                        @else
                            @if("boolean".equals(field.inputType()))
                                <select id="${field.name()}" name="${field.name()}">
                                    @if("true".equals(values.get(field.name())))
                                        <option value="true" selected>true</option>
                                    @else
                                        <option value="true">true</option>
                                    @endif
                                    @if("false".equals(values.get(field.name())))
                                        <option value="false" selected>false</option>
                                    @else
                                        <option value="false">false</option>
                                    @endif
                                </select>
                            @else
                                @if("nullableBoolean".equals(field.inputType()))
                                    <select id="${field.name()}" name="${field.name()}">
                                        @if(values.get(field.name()) == null || values.get(field.name()).isBlank())
                                            <option value="" selected></option>
                                        @else
                                            <option value=""></option>
                                        @endif
                                        @if("true".equals(values.get(field.name())))
                                            <option value="true" selected>true</option>
                                        @else
                                            <option value="true">true</option>
                                        @endif
                                        @if("false".equals(values.get(field.name())))
                                            <option value="false" selected>false</option>
                                        @else
                                            <option value="false">false</option>
                                        @endif
                                    </select>
                                @else
                                    @if("textarea".equals(field.inputType()))
                                        <textarea id="${field.name()}" name="${field.name()}" rows="3">${values.get(field.name())}</textarea>
                                    @else
                                        @if("integer".equals(field.inputType()))
                                            <input id="${field.name()}" name="${field.name()}" type="number" step="1" value="${values.get(field.name())}">
                                        @else
                                            @if("decimal".equals(field.inputType()))
                                                <input id="${field.name()}" name="${field.name()}" type="text" inputmode="decimal" value="${values.get(field.name())}">
                                            @else
                                                @if(field.required())
                                                    <input id="${field.name()}" name="${field.name()}" type="text" value="${values.get(field.name())}" required>
                                                @else
                                                    <input id="${field.name()}" name="${field.name()}" type="text" value="${values.get(field.name())}">
                                                @endif
                                            @endif
                                        @endif
                                    @endif
                                @endif
                            @endif
                        @endif
                    @endif
                </div>
            @endfor
        </div>
    </form>
`)
