package codes.yousef.aether.admin

import codes.yousef.aether.db.BaseEntity
import codes.yousef.aether.db.Model
import codes.yousef.aether.web.Router
import codes.yousef.aether.web.router
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
            val objects = model.objects.toList()
            
            exchange.render {
                element("html") {
                    head {
                        element("title") { text("Select $modelName to change | Aether Admin") }
                        element("link", mapOf("rel" to "stylesheet", "href" to "https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css"))
                    }
                    body {
                        div(mapOf("class" to "container mt-4")) {
                            div(mapOf("class" to "d-flex justify-content-between align-items-center mb-4")) {
                                h1 { text("Select $modelName to change") }
                                a(href = "$name$baseUrl/add", attributes = mapOf("class" to "btn btn-primary")) {
                                    text("Add $modelName")
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
                                                // TODO: Reflection or property access to get field values
                                                // For now, just show ID and toString
                                                td { text(obj.toString()) }
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
                    }
                }
            }
        }
        
        router.get("$baseUrl/add") { exchange ->
            // TODO: Render add view
            exchange.respond(body = "Add view for $modelName")
        }
        
        router.get("$baseUrl/:id") { exchange ->
            // TODO: Render change view
            exchange.respond(body = "Change view for $modelName")
        }
        
        router.get("$baseUrl/:id/delete") { exchange ->
            // TODO: Render delete view
            exchange.respond(body = "Delete view for $modelName")
        }
    }
}

/**
 * Default admin site instance.
 */
val site = AdminSite()
