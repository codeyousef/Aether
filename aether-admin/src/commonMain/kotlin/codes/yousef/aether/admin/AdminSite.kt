package codes.yousef.aether.admin

import codes.yousef.aether.db.*
import codes.yousef.aether.web.Router
import codes.yousef.aether.web.router
import codes.yousef.aether.web.pathParam
import codes.yousef.aether.ui.*

/**
 * The main admin site registry and router.
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

    /**
     * Returns the router for the admin site.
     */
    fun urls(): Router {
        return router {
            get("/") { exchange ->
                exchange.render {
                    element("html") {
                        head {
                            element("title") { text("Aether Admin") }
                            element("link", mapOf("rel" to "stylesheet", "href" to "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"))
                        }
                        body {
                            div(mapOf("class" to "container mt-4")) {
                                h1 { text("Site Administration") }
                                
                                div(mapOf("class" to "list-group mt-4")) {
                                    registry.forEach { registered ->
                                        val modelName = registered.model.tableName
                                        a(href = "$name/$modelName", attributes = mapOf("class" to "list-group-item list-group-item-action")) {
                                            text(modelName.replaceFirstChar { it.uppercase() })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
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
        val baseUrl = "/$modelName"
        
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
                element("html") {
                    head {
                        element("title") { text("Select $modelName to change | Aether Admin") }
                        element("link", mapOf("rel" to "stylesheet", "href" to "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"))
                    }
                    body {
                        div(mapOf("class" to "container-fluid mt-4")) {
                            div(mapOf("class" to "row")) {
                                // Main Content
                                div(mapOf("class" to if (admin.listFilter.isNotEmpty()) "col-md-9" else "col-12")) {
                                    div(mapOf("class" to "d-flex justify-content-between align-items-center mb-4")) {
                                        h1 { text("Select $modelName to change") }
                                        a(href = "$name$baseUrl/add", attributes = mapOf("class" to "btn btn-primary")) {
                                            text("Add $modelName")
                                        }
                                    }
                                    
                                    // Search Bar
                                    if (admin.searchFields.isNotEmpty()) {
                                         form(action = "$name$baseUrl", method = "get", attributes = mapOf("class" to "mb-4")) {
                                             div(mapOf("class" to "input-group")) {
                                                 input(type = "text", name = "q", attributes = mapOf("class" to "form-control", "placeholder" to "Search...", "value" to (searchQuery ?: "")))
                                                 // Preserve other filters
                                                 for (filter in admin.listFilter) {
                                                     val v = queryParams[filter]?.firstOrNull()
                                                     if (v != null) input(type="hidden", name=filter, attributes=mapOf("value" to v))
                                                 }
                                                 button(attributes = mapOf("class" to "btn btn-outline-secondary", "type" to "submit")) { text("Search") }
                                             }
                                         }
                                    }
                                    
                                    table(mapOf("class" to "table table-striped")) {
                                        thead {
                                            tr {
                                                // Headers
                                                val displayFields = if (admin.listDisplay.isNotEmpty()) {
                                                    admin.listDisplay
                                                } else {
                                                    listOf("ID", "String Representation")
                                                }
                                                
                                                for (field in displayFields) {
                                                    th { text(field.uppercase()) }
                                                }
                                            }
                                        }
                                        tbody {
                                            for (obj in objects) {
                                                tr {
                                                    if (admin.listDisplay.isNotEmpty()) {
                                                        for (fieldName in admin.listDisplay) {
                                                            val column = model.columns.find { it.name == fieldName }
                                                            val value = if (column != null) {
                                                                column.getValue(obj)?.toString() ?: "-"
                                                            } else {
                                                                "?"
                                                            }
                                                            
                                                            td { 
                                                                if (admin.listDisplayLinks.contains(fieldName) || 
                                                                    (admin.listDisplayLinks.isEmpty() && fieldName == admin.listDisplay.first())) {
                                                                    val pk = model.primaryKeyColumn.getValue(obj)
                                                                    a(href = "$name$baseUrl/$pk") {
                                                                        text(value)
                                                                    }
                                                                } else {
                                                                    text(value)
                                                                }
                                                            }
                                                        }
                                                    } else {
                                                        // Default display
                                                        val pk = model.primaryKeyColumn.getValue(obj)
                                                        td { 
                                                            a(href = "$name$baseUrl/$pk") {
                                                                text(pk.toString())
                                                            }
                                                        }
                                                        td { text(obj.toString()) }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Sidebar
                                if (admin.listFilter.isNotEmpty()) {
                                    div(mapOf("class" to "col-md-3")) {
                                        div(mapOf("class" to "card")) {
                                            div(mapOf("class" to "card-header")) { text("Filter") }
                                            div(mapOf("class" to "card-body")) {
                                                for (filterField in admin.listFilter) {
                                                    element("h5") { text(filterField.replaceFirstChar { it.uppercase() }) }
                                                    div(mapOf("class" to "list-group mb-3")) {
                                                        val currentVal = queryParams[filterField]?.firstOrNull()
                                                        val allParams = queryParams.toMutableMap()
                                                        allParams.remove(filterField)
                                                        val allQueryString = allParams.entries.joinToString("&") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                                                        
                                                        a(href = "$name$baseUrl?$allQueryString", attributes = mapOf("class" to "list-group-item list-group-item-action ${if(currentVal == null) "active" else ""}")) { text("All") }
                                                        
                                                        // TODO: Fetch distinct values.
                                                        // For now, if it's boolean, show True/False
                                                        val column = model.columns.find { it.name == filterField }
                                                        if (column?.type == ColumnType.Boolean) {
                                                            val trueParams = queryParams.toMutableMap()
                                                            trueParams[filterField] = listOf("true")
                                                            val trueQs = trueParams.entries.joinToString("&") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                                                            a(href = "$name$baseUrl?$trueQs", attributes = mapOf("class" to "list-group-item list-group-item-action ${if(currentVal == "true") "active" else ""}")) { text("Yes") }
                                                            
                                                            val falseParams = queryParams.toMutableMap()
                                                            falseParams[filterField] = listOf("false")
                                                            val falseQs = falseParams.entries.joinToString("&") { "${it.key}=${it.value.firstOrNull() ?: ""}" }
                                                            a(href = "$name$baseUrl?$falseQs", attributes = mapOf("class" to "list-group-item list-group-item-action ${if(currentVal == "false") "active" else ""}")) { text("No") }
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
        }
        
        router.get("$baseUrl/add") { exchange ->
            val form = ModelForm(model)
            renderForm(exchange, form, "Add $modelName", "$name$baseUrl/add")
        }
        
        router.post("$baseUrl/add") { exchange ->
            val body = exchange.request.bodyText()
            val formData = parseFormData(body)
            val form = ModelForm(model)
            form.bind(formData)
            
            if (form.isValid()) {
                form.save()
                exchange.redirect("$name$baseUrl")
            } else {
                renderForm(exchange, form, "Add $modelName", "$name$baseUrl/add")
            }
        }
        
        router.get("$baseUrl/:id") { exchange ->
            val id = exchange.pathParam("id") ?: run {
                exchange.notFound("ID not provided")
                return@get
            }
            // We need to convert ID to correct type. Assuming Long or Int.
            // Since we don't know the type easily here without reflection on PK column,
            // we'll try to parse as Long (covers Int too usually) or String.
            
            val pkValue: Any = id.toLongOrNull() ?: id
            val obj = model.get(pkValue)
            
            if (obj == null) {
                exchange.notFound("Object not found")
                return@get
            }
            
            val form = ModelForm(model, obj)
            // Pre-fill form data from object
            // ModelForm init automatically binds object values to fields.
            
            renderForm(exchange, form, "Change $modelName", "$name$baseUrl/$id")
        }
        
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
                exchange.redirect("$name$baseUrl")
            } else {
                renderForm(exchange, form, "Change $modelName", "$name$baseUrl/$id")
            }
        }
        
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
                 element("html") {
                     head {
                         element("title") { text("Delete $modelName | Aether Admin") }
                         element("link", mapOf("rel" to "stylesheet", "href" to "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"))
                     }
                     body {
                         div(mapOf("class" to "container mt-4")) {
                             h1 { text("Are you sure?") }
                             p { text("Are you sure you want to delete the $modelName \"$obj\"? All of the following related items will be deleted:") }
                             
                             form(action = "$name$baseUrl/$id/delete", method = "post") {
                                 csrfInput("dummy") // TODO: Real CSRF
                                 button(attributes = mapOf("type" to "submit", "class" to "btn btn-danger")) { text("Yes, I'm sure") }
                                 a(href = "$name$baseUrl", attributes = mapOf("class" to "btn btn-secondary ms-2")) { text("No, take me back") }
                             }
                         }
                     }
                 }
             }
        }
        
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
             
             exchange.redirect("$name$baseUrl")
        }
    }
    
    private suspend fun renderForm(exchange: codes.yousef.aether.core.Exchange, form: codes.yousef.aether.forms.Form, title: String, actionUrl: String) {
        exchange.render {
            element("html") {
                head {
                    element("title") { text("$title | Aether Admin") }
                    element("link", mapOf("rel" to "stylesheet", "href" to "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"))
                }
                body {
                    div(mapOf("class" to "container mt-4")) {
                        h1 { text(title) }
                        
                        form(action = actionUrl, method = "post", attributes = mapOf("class" to "mt-4")) {
                            csrfInput("dummy")
                            
                            // Render form fields
                            form.asP(this)
                            
                            div(attributes = mapOf("class" to "mt-3")) {
                                button(attributes = mapOf("type" to "submit", "class" to "btn btn-primary")) { text("Save") }
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
        // Basic URL decoding
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
