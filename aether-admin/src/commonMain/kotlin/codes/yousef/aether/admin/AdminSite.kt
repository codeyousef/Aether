package codes.yousef.aether.admin

import codes.yousef.aether.db.*
import codes.yousef.aether.web.Router
import codes.yousef.aether.web.router
import codes.yousef.aether.web.pathParam
import codes.yousef.aether.ui.*
import codes.yousef.aether.admin.AdminComponents.adminLayout
import codes.yousef.aether.admin.AdminComponents.adminPageHeader
import codes.yousef.aether.admin.AdminComponents.adminBreadcrumbs
import codes.yousef.aether.admin.AdminComponents.adminButton
import codes.yousef.aether.admin.AdminComponents.adminCard
import codes.yousef.aether.admin.AdminComponents.adminStatCard
import codes.yousef.aether.admin.AdminComponents.adminSearchInput
import codes.yousef.aether.admin.AdminComponents.adminTable
import codes.yousef.aether.admin.AdminComponents.adminTableRow
import codes.yousef.aether.admin.AdminComponents.adminEmptyState
import codes.yousef.aether.admin.AdminComponents.adminFormGroup
import codes.yousef.aether.admin.AdminComponents.adminFormCheckbox
import codes.yousef.aether.admin.AdminComponents.adminBadge
import codes.yousef.aether.admin.AdminComponents.adminFilterSidebar
import codes.yousef.aether.admin.AdminComponents.adminFilterSection
import codes.yousef.aether.admin.AdminComponents.adminFilterOption
import codes.yousef.aether.admin.AdminComponents.rawHtml

/**
 * The main admin site registry and router.
 * Generates a modern CMS-style admin interface.
 */
class AdminSite(val name: String = "admin") {
    private class RegisteredModel<T : BaseEntity<T>>(val model: Model<T>, val admin: ModelAdmin<T>)
    
    private val registry = mutableListOf<RegisteredModel<*>>()

    /**
     * Registers a model with the admin site.
     */
    fun <T : BaseEntity<T>> register(model: Model<T>, admin: ModelAdmin<T>? = null) {
        registry.add(RegisteredModel(model, admin ?: ModelAdmin(model)))
    }
    
    private fun getModelList(): List<Pair<String, String>> {
        return registry.map { registered ->
            val tableName = registered.model.tableName
            val displayName = tableName.replaceFirstChar { it.uppercase() }
            tableName to displayName
        }
    }

    /**
     * Returns the router for the admin site.
     */
    fun urls(): Router {
        return router {
            val dashboardHandler: codes.yousef.aether.web.RouteHandler = { exchange ->
                // Compute model counts before render (suspend functions must be called here)
                val modelCounts = registry.map { registered ->
                    val modelName = registered.model.tableName.replaceFirstChar { it.uppercase() }
                    val count = try {
                        registered.model.objects.count()
                    } catch (e: Exception) {
                        0L
                    }
                    modelName to count
                }
                
                exchange.render {
                    adminLayout(
                        title = "Dashboard",
                        siteName = name,
                        currentPath = "/$name",
                        models = getModelList()
                    ) {
                        // Page header
                        adminPageHeader(title = "Dashboard")
                        
                        // Content
                        element("div", mapOf("class" to "admin-content")) {
                            // Stats grid
                            element("div", mapOf("class" to "admin-stats-grid")) {
                                for ((modelName, count) in modelCounts) {
                                    adminStatCard(
                                        label = "Total $modelName",
                                        value = count.toString()
                                    )
                                }
                            }
                            
                            // Quick actions
                            adminCard(title = "Quick Actions") {
                                element("div", mapOf("style" to "display: flex; gap: 12px; flex-wrap: wrap;")) {
                                    for (registered in registry) {
                                        val modelName = registered.model.tableName
                                        val displayName = modelName.replaceFirstChar { it.uppercase() }
                                        adminButton(
                                            text = "Add $displayName",
                                            href = "/$name/$modelName/add",
                                            icon = AdminTheme.Icons.plus
                                        )
                                    }
                                }
                            }
                            
                            // Models overview
                            element("div", mapOf("style" to "margin-top: 24px")) {
                                adminCard(title = "Content Overview") {
                                    adminTable(
                                        headers = listOf("Model", "Records")
                                    ) {
                                        for ((idx, registered) in registry.withIndex()) {
                                            val modelName = registered.model.tableName
                                            val displayName = modelName.replaceFirstChar { it.uppercase() }
                                            val count = modelCounts.getOrNull(idx)?.second ?: 0L
                                            
                                            element("tr") {
                                                element("td") {
                                                    element("a", mapOf(
                                                        "href" to "/$name/$modelName",
                                                        "class" to "admin-table-link"
                                                    )) {
                                                        rawHtml(AdminTheme.Icons.database)
                                                        text(" $displayName")
                                                    }
                                                }
                                                element("td") { text(count.toString()) }
                                                element("td") {
                                                    element("div", mapOf("class" to "admin-actions")) {
                                                        element("a", mapOf(
                                                            "href" to "/$name/$modelName",
                                                            "class" to "admin-action-btn",
                                                            "title" to "View all"
                                                        )) {
                                                            rawHtml(AdminTheme.Icons.folder)
                                                        }
                                                        element("a", mapOf(
                                                            "href" to "/$name/$modelName/add",
                                                            "class" to "admin-action-btn",
                                                            "title" to "Add new"
                                                        )) {
                                                            rawHtml(AdminTheme.Icons.plus)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            get("/$name", dashboardHandler)
            get("/$name/", dashboardHandler)
            
            // Register routes for each model
            registry.forEach { registered ->
                registerModelRoutes(this, registered)
            }
        }
    }

    private fun <T : BaseEntity<T>> registerModelRoutes(router: Router, registered: RegisteredModel<T>) {
        val model = registered.model
        val admin = registered.admin
        val modelName = model.tableName
        val displayName = modelName.replaceFirstChar { it.uppercase() }
        val baseUrl = "/$name/$modelName"
        
        // List view
        router.get(baseUrl) { exchange ->
            var qs = model.objects
            val queryParams = exchange.request.queryParameters()
            
            // Search
            val searchQuery = exchange.request.queryParameter("q")
            if (!searchQuery.isNullOrBlank() && admin.searchFields.isNotEmpty()) {
                val searchConditions = admin.searchFields.map { field ->
                    WhereClause.Like(
                        column = Expression.ColumnRef(column = field),
                        pattern = Expression.Literal(SqlValue.StringValue("%$searchQuery%"))
                    )
                }
                qs = qs.filter(WhereClause.Or(searchConditions))
            }
            
            // Filters
            for (filterField in admin.listFilter) {
                val filterValue = queryParams[filterField]?.firstOrNull()
                if (!filterValue.isNullOrBlank()) {
                    val column = model.columns.find { it.name == filterField }
                    if (column != null) {
                         val typedValue: SqlValue = when(column.type) {
                             ColumnType.Integer, ColumnType.Serial -> SqlValue.IntValue(filterValue.toIntOrNull() ?: 0)
                             ColumnType.Long, ColumnType.BigSerial -> SqlValue.LongValue(filterValue.toLongOrNull() ?: 0L)
                             ColumnType.Boolean -> SqlValue.BooleanValue(filterValue.toBoolean())
                             else -> SqlValue.StringValue(filterValue)
                         }
                         
                         qs = qs.filter(WhereClause.Condition(
                             left = Expression.ColumnRef(column = filterField),
                             operator = ComparisonOperator.EQUALS,
                             right = Expression.Literal(typedValue)
                         ))
                    }
                }
            }

            val objects = qs.toList()
            
            exchange.render {
                adminLayout(
                    title = displayName,
                    siteName = name,
                    currentPath = baseUrl,
                    models = getModelList()
                ) {
                    // Page header
                    adminPageHeader(title = displayName) {
                        adminButton(
                            text = "Add $displayName",
                            href = "$baseUrl/add",
                            icon = AdminTheme.Icons.plus
                        )
                    }
                    
                    // Content
                    element("div", mapOf("class" to "admin-content")) {
                        // Breadcrumbs
                        adminBreadcrumbs(
                            "Dashboard" to "/$name",
                            displayName to null
                        )
                        
                        // Layout with optional filter sidebar
                        val hasFilters = admin.listFilter.isNotEmpty()
                        
                        element("div", mapOf("class" to if (hasFilters) "admin-list-layout" else "")) {
                            // Main content
                            element("div", mapOf("class" to if (hasFilters) "admin-list-main" else "")) {
                                adminCard {
                                    // Search toolbar
                                    if (admin.searchFields.isNotEmpty()) {
                                        element("form", mapOf(
                                            "action" to baseUrl,
                                            "method" to "get",
                                            "class" to "admin-toolbar"
                                        )) {
                                            adminSearchInput(
                                                value = searchQuery,
                                                placeholder = "Search ${admin.searchFields.joinToString(", ")}..."
                                            )
                                            // Preserve filter params
                                            for (filter in admin.listFilter) {
                                                val v = queryParams[filter]?.firstOrNull()
                                                if (v != null) {
                                                    element("input", mapOf(
                                                        "type" to "hidden",
                                                        "name" to filter,
                                                        "value" to v
                                                    ))
                                                }
                                            }
                                            adminButton(
                                                text = "Search",
                                                type = "submit",
                                                variant = "secondary"
                                            )
                                        }
                                    }
                                    
                                    // Table or empty state
                                    if (objects.isEmpty()) {
                                        adminEmptyState(
                                            title = "No $displayName found",
                                            message = if (!searchQuery.isNullOrBlank()) 
                                                "Try adjusting your search or filters." 
                                                else "Get started by creating your first $displayName.",
                                            actionText = if (searchQuery.isNullOrBlank()) "Add $displayName" else null,
                                            actionUrl = if (searchQuery.isNullOrBlank()) "$baseUrl/add" else null
                                        )
                                    } else {
                                        // Determine display fields
                                        val displayFields = if (admin.listDisplay.isNotEmpty()) {
                                            admin.listDisplay
                                        } else {
                                            listOf("id")
                                        }
                                        
                                        adminTable(headers = displayFields.map { it.uppercase() }) {
                                            for (obj in objects) {
                                                val pk = model.primaryKeyColumn.getValue(obj)
                                                
                                                adminTableRow(
                                                    editUrl = "$baseUrl/$pk",
                                                    deleteUrl = "$baseUrl/$pk/delete"
                                                ) {
                                                    for (fieldName in displayFields) {
                                                        val column = model.columns.find { it.name == fieldName }
                                                        val value = if (column != null) {
                                                            column.getValue(obj)?.toString() ?: "-"
                                                        } else if (fieldName == "id") {
                                                            pk.toString()
                                                        } else {
                                                            "?"
                                                        }
                                                        
                                                        element("td") {
                                                            // Link for first column or specified link fields
                                                            val isLinked = admin.listDisplayLinks.contains(fieldName) || 
                                                                (admin.listDisplayLinks.isEmpty() && fieldName == displayFields.first())
                                                            
                                                            if (isLinked) {
                                                                element("a", mapOf(
                                                                    "href" to "$baseUrl/$pk",
                                                                    "class" to "admin-table-link"
                                                                )) {
                                                                    // Render boolean as badge
                                                                    if (column?.type == ColumnType.Boolean) {
                                                                        val boolVal = column.getValue(obj) as? Boolean ?: false
                                                                        adminBadge(
                                                                            text = if (boolVal) "Yes" else "No",
                                                                            variant = if (boolVal) "success" else "warning"
                                                                        )
                                                                    } else {
                                                                        text(value)
                                                                    }
                                                                }
                                                            } else {
                                                                // Render boolean as badge
                                                                if (column?.type == ColumnType.Boolean) {
                                                                    val boolVal = column.getValue(obj) as? Boolean ?: false
                                                                    adminBadge(
                                                                        text = if (boolVal) "Yes" else "No",
                                                                        variant = if (boolVal) "success" else "warning"
                                                                    )
                                                                } else {
                                                                    text(value)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // Filter sidebar
                            if (hasFilters) {
                                adminFilterSidebar {
                                    for (filterField in admin.listFilter) {
                                        adminFilterSection(title = filterField.replaceFirstChar { it.uppercase() }) {
                                            val currentVal = queryParams[filterField]?.firstOrNull()
                                            
                                            // Build "All" link (removes this filter)
                                            val allParams = queryParams.toMutableMap()
                                            allParams.remove(filterField)
                                            val allQueryString = allParams.entries
                                                .joinToString("&") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                                            
                                            adminFilterOption(
                                                label = "All",
                                                href = if (allQueryString.isNotEmpty()) "$baseUrl?$allQueryString" else baseUrl,
                                                active = currentVal == null
                                            )
                                            
                                            // Boolean filter options
                                            val column = model.columns.find { it.name == filterField }
                                            if (column?.type == ColumnType.Boolean) {
                                                for ((value, label) in listOf("true" to "Yes", "false" to "No")) {
                                                    val params = queryParams.toMutableMap()
                                                    params[filterField] = listOf(value)
                                                    val qs = params.entries
                                                        .joinToString("&") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                                                    
                                                    adminFilterOption(
                                                        label = label,
                                                        href = "$baseUrl?$qs",
                                                        active = currentVal == value
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Add form
        router.get("$baseUrl/add") { exchange ->
            val form = ModelForm(model, defaultValues = admin.defaultValues)
            renderForm(exchange, form, "Add $displayName", "$baseUrl/add", displayName, baseUrl, isNew = true, admin = admin)
        }
        
        // Add submit
        router.post("$baseUrl/add") { exchange ->
            val body = exchange.request.bodyText()
            val formData = parseFormData(body)
            // Merge default values with form data for excluded fields
            val mergedData = admin.defaultValues.mapValues { it.value?.toString() ?: "" } + formData
            val form = ModelForm(model, defaultValues = admin.defaultValues)
            form.bind(mergedData)
            
            if (form.isValid()) {
                form.save()
                exchange.redirect(baseUrl)
            } else {
                renderForm(exchange, form, "Add $displayName", "$baseUrl/add", displayName, baseUrl, isNew = true, admin = admin)
            }
        }
        
        // Edit form
        router.get("$baseUrl/:id") { exchange ->
            val id = exchange.pathParam("id") ?: run {
                exchange.notFound("ID not provided")
                return@get
            }
            
            val pkValue: Any = id.toLongOrNull() ?: id
            val obj = model.get(pkValue)
            
            if (obj == null) {
                exchange.notFound("Object not found")
                return@get
            }
            
            val form = ModelForm(model, obj)
            renderForm(exchange, form, "Edit $displayName", "$baseUrl/$id", displayName, baseUrl, isNew = false, objectId = id, admin = admin)
        }
        
        // Edit submit
        router.post("$baseUrl/:id") { exchange ->
            val id = exchange.pathParam("id") ?: run {
                exchange.notFound("ID not provided")
                return@post
            }
            val pkValue: Any = id.toLongOrNull() ?: id
            val obj = model.get(pkValue)
            
            if (obj == null) {
                exchange.notFound("Object not found")
                return@post
            }
            
            val body = exchange.request.bodyText()
            val formData = parseFormData(body)
            val form = ModelForm(model, obj)
            form.bind(formData)
            
            if (form.isValid()) {
                form.save()
                exchange.redirect(baseUrl)
            } else {
                renderForm(exchange, form, "Edit $displayName", "$baseUrl/$id", displayName, baseUrl, isNew = false, objectId = id, admin = admin)
            }
        }
        
        // Delete confirmation
        router.get("$baseUrl/:id/delete") { exchange ->
            val id = exchange.pathParam("id") ?: run {
                exchange.notFound("ID not provided")
                return@get
            }
            val pkValue: Any = id.toLongOrNull() ?: id
            val obj = model.get(pkValue)
            
            if (obj == null) {
                exchange.notFound("Object not found")
                return@get
            }
            
            exchange.render {
                adminLayout(
                    title = "Delete $displayName",
                    siteName = name,
                    currentPath = baseUrl,
                    models = getModelList()
                ) {
                    adminPageHeader(title = "Delete $displayName")
                    
                    element("div", mapOf("class" to "admin-content")) {
                        adminBreadcrumbs(
                            "Dashboard" to "/$name",
                            displayName to baseUrl,
                            "Delete" to null
                        )
                        
                        adminCard {
                            element("div", mapOf("style" to "text-align: center; padding: 32px 16px;")) {
                                element("div", mapOf("style" to "color: ${AdminTheme.Colors.ERROR}; margin-bottom: 16px;")) {
                                    rawHtml("""<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="currentColor" style="width: 48px; height: 48px;"><path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m-9.303 3.376c-.866 1.5.217 3.374 1.948 3.374h14.71c1.73 0 2.813-1.874 1.948-3.374L13.949 3.378c-.866-1.5-3.032-1.5-3.898 0L2.697 16.126zM12 15.75h.007v.008H12v-.008z" /></svg>""")
                                }
                                
                                element("h2", mapOf("style" to "font-size: 20px; font-weight: 600; margin-bottom: 8px;")) {
                                    text("Are you sure?")
                                }
                                element("p", mapOf("style" to "color: ${AdminTheme.Colors.TEXT_MUTED}; margin-bottom: 24px;")) {
                                    text("Are you sure you want to delete this $displayName? This action cannot be undone.")
                                }
                                
                                element("div", mapOf("style" to "display: flex; gap: 12px; justify-content: center;")) {
                                    element("form", mapOf(
                                        "action" to "$baseUrl/$id/delete",
                                        "method" to "post",
                                        "style" to "display: inline;"
                                    )) {
                                        csrfToken(exchange)
                                        adminButton(
                                            text = "Yes, Delete",
                                            type = "submit",
                                            variant = "danger"
                                        )
                                    }
                                    adminButton(
                                        text = "Cancel",
                                        href = baseUrl,
                                        variant = "secondary"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Delete submit
        router.post("$baseUrl/:id/delete") { exchange ->
            val id = exchange.pathParam("id") ?: run {
                exchange.notFound("ID not provided")
                return@post
            }
            val pkValue: Any = id.toLongOrNull() ?: id
            val obj = model.get(pkValue)
            
            if (obj != null) {
                obj.delete()
            }
            
            exchange.redirect(baseUrl)
        }
    }
    
    private suspend fun <T : BaseEntity<T>> renderForm(
        exchange: codes.yousef.aether.core.Exchange,
        form: codes.yousef.aether.forms.Form,
        title: String,
        actionUrl: String,
        displayName: String,
        baseUrl: String,
        isNew: Boolean,
        objectId: String? = null,
        admin: ModelAdmin<T>? = null
    ) {
        exchange.render {
            adminLayout(
                title = title,
                siteName = name,
                currentPath = baseUrl,
                models = getModelList()
            ) {
                adminPageHeader(title = title)
                
                element("div", mapOf("class" to "admin-content")) {
                    // Breadcrumbs
                    val breadcrumbs = mutableListOf<Pair<String, String?>>(
                        "Dashboard" to "/$name",
                        displayName to baseUrl
                    )
                    if (isNew) {
                        breadcrumbs.add("Add" to null)
                    } else {
                        breadcrumbs.add("Edit #$objectId" to null)
                    }
                    adminBreadcrumbs(*breadcrumbs.toTypedArray())
                    
                    adminCard {
                        element("form", mapOf(
                            "action" to actionUrl,
                            "method" to "post"
                        )) {
                            csrfToken(exchange)
                            
                            // Render form fields
                            element("div", mapOf("style" to "display: grid; gap: 16px;")) {
                                for ((fieldName, field) in form.allFields()) {
                                    // Skip excluded fields
                                    if (admin != null && fieldName in admin.excludeFields) {
                                        continue
                                    }
                                    
                                    val error = form.getFieldError(fieldName)
                                    val value = field.value?.toString() ?: ""
                                    
                                    // Check if field should be multiline
                                    val shouldBeMultiline = admin != null && fieldName in admin.multilineFields
                                    
                                    when (field) {
                                        is codes.yousef.aether.forms.BooleanField -> {
                                            adminFormCheckbox(
                                                label = field.label,
                                                name = fieldName,
                                                checked = field.value == true
                                            )
                                        }
                                        else -> {
                                            adminFormGroup(
                                                label = field.label,
                                                name = fieldName,
                                                value = value,
                                                required = field.required,
                                                error = error,
                                                multiline = shouldBeMultiline || (field is codes.yousef.aether.forms.CharField && value.length > 100),
                                                type = when (field) {
                                                    is codes.yousef.aether.forms.IntegerField, 
                                                    is codes.yousef.aether.forms.LongField,
                                                    is codes.yousef.aether.forms.DoubleField -> "number"
                                                    else -> "text"
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                            
                            // Form actions
                            element("div", mapOf("style" to "display: flex; gap: 12px; margin-top: 24px; padding-top: 24px; border-top: 1px solid #e2e8f0;")) {
                                adminButton(
                                    text = if (isNew) "Create $displayName" else "Save Changes",
                                    type = "submit"
                                )
                                adminButton(
                                    text = "Cancel",
                                    href = baseUrl,
                                    variant = "secondary"
                                )
                                
                                if (!isNew && objectId != null) {
                                    element("div", mapOf("style" to "margin-left: auto;")) {
                                        adminButton(
                                            text = "Delete",
                                            href = "$baseUrl/$objectId/delete",
                                            variant = "danger"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun parseFormData(body: String): Map<String, String> {
        return body.split("&")
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val name = decodeUrl(part.substring(0, index))
                    val value = decodeUrl(part.substring(index + 1))
                    name to value
                } else {
                    null
                }
            }
            .toMap()
    }
    
    private fun decodeUrl(value: String): String {
        return value.replace("+", " ")
            .replace(Regex("%([0-9A-Fa-f]{2})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
    }
}

/**
 * Default admin site instance.
 */
val site = AdminSite()
