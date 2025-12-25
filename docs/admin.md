# Admin Interface

Aether includes a powerful, automatic admin interface that reads your model metadata to provide a quick, model-centric interface where trusted users can manage content on your site. It's inspired by Django's admin interface.

## Setup

To use the admin interface, you need to include the `aether-admin` dependency in your project.

```kotlin
// build.gradle.kts
implementation(project(":aether-admin"))
```

Then, in your main application entry point, initialize the `AdminSite` and register your models.

```kotlin
import codes.yousef.aether.admin.AdminSite
import codes.yousef.aether.admin.ModelAdmin

fun main() = runBlocking {
    // ... database setup ...

    // Initialize Admin Site
    val adminSite = AdminSite()
    
    // Register Models
    adminSite.register(Users)
    
    // Register with custom configuration
    adminSite.register(Posts, object : ModelAdmin<Post>(Posts) {
        override val listDisplay = listOf("id", "title", "author", "published")
        override val listDisplayLinks = listOf("id", "title")
    })

    // Add to pipeline
    val pipeline = Pipeline().apply {
        // ... other middleware ...
        use(adminSite.urls().asMiddleware())
        // ... router ...
    }
    
    // ... start server ...
}
```

## Features

### Auto-CRUD
The admin interface automatically generates:
*   **List View**: A paginated table of your objects.
*   **Create Form**: A form to add new objects.
*   **Edit Form**: A form to update existing objects.
*   **Delete Confirmation**: A safety check before deletion.

### ModelAdmin Configuration

You can customize how a model is displayed by subclassing `ModelAdmin`.

*   `listDisplay`: A list of field names to display as columns in the change list page.
*   `listDisplayLinks`: A list of field names that should link to the change page.

### ModelForm
The admin uses `ModelForm` internally, which inspects your `Model` definition to generate appropriate HTML inputs:
*   `varchar` / `text` -> `<input type="text">`
*   `integer` / `long` / `double` -> `<input type="number">`
*   `boolean` -> `<input type="checkbox">`

## Security
The admin interface does not currently enforce authentication by default. You should wrap it in authentication middleware or ensure your `AdminSite` is only accessible to trusted users.

```kotlin
// Example of protecting the admin route
val adminRouter = adminSite.urls()
val protectedAdmin = router {
    use(authMiddleware(Users)) // Ensure user is logged in
    use { exchange, next ->
        val user = exchange.attributes[UserKey] as? User
        if (user?.isStaff == true) {
            next(exchange)
        } else {
            exchange.forbidden("Admins only")
        }
    }
    use(adminRouter.asMiddleware())
}
```
