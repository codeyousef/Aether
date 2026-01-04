# Admin Interface

The `aether-admin` module provides an automatic admin interface for managing your models, similar to Django Admin.

## Overview

The admin interface auto-generates:
- **List views** with pagination, sorting, and filtering
- **Detail/edit views** with form validation
- **Create views** for adding new records
- **Delete confirmation** with cascading relationship warnings
- **Dashboard** with customizable widgets

## AdminSite

The `AdminSite` is the central registry for all admin functionality.

### Creating an Admin Site

```kotlin
val adminSite = AdminSite(
    title = "My Admin",
    urlPrefix = "/admin",
    authProvider = sessionAuthProvider,  // Optional: restrict access
)
```

### Configuration

```kotlin
val adminSite = AdminSite(
    title = "My Admin",
    urlPrefix = "/admin",
    authProvider = sessionAuthProvider,
    branding = AdminBranding(
        logo = "/static/logo.png",
        primaryColor = "#2563eb",
        favicon = "/static/favicon.ico"
    )
)
```

---

## ModelAdmin

`ModelAdmin` configures how a model appears in the admin interface.

### Basic Registration

```kotlin
adminSite.register(User) {
    listDisplay = listOf("id", "username", "email", "createdAt")
    searchFields = listOf("username", "email")
    listFilter = listOf("isActive", "role")
}
```

### Full Configuration

```kotlin
adminSite.register(User) {
    // List view
    listDisplay = listOf("id", "username", "email", "createdAt")
    listDisplayLinks = listOf("username")  // Clickable fields
    listPerPage = 25
    ordering = listOf("-createdAt")  // Default sort (- for DESC)
    
    // Filtering & search
    searchFields = listOf("username", "email")
    listFilter = listOf("isActive", "role")
    dateHierarchy = "createdAt"  // Date drill-down navigation
    
    // Form configuration
    fields = listOf("username", "email", "role", "isActive")
    exclude = listOf("password")  // Never show these
    readonly = listOf("createdAt", "updatedAt")
    
    // Fieldsets for organized forms
    fieldsets = listOf(
        Fieldset("Basic Info", listOf("username", "email")),
        Fieldset("Permissions", listOf("role", "isActive"), collapsed = true)
    )
    
    // Actions
    actions = listOf(
        BulkAction("activate") { ids ->
            User.filter { it.id inList ids }.update { it.isActive to true }
        },
        BulkAction("deactivate") { ids ->
            User.filter { it.id inList ids }.update { it.isActive to false }
        }
    )
    
    // Inline editing for related models
    inlines = listOf(
        TabularInline(UserProfile::class, "userId"),
        StackedInline(UserAddress::class, "userId")
    )
}
```

### Custom Display Methods

```kotlin
adminSite.register(Order) {
    listDisplay = listOf("id", "customer", "total", "statusBadge")
    
    // Custom computed column
    displayMethod("statusBadge") { order ->
        when (order.status) {
            "pending" -> """<span class="badge yellow">Pending</span>"""
            "completed" -> """<span class="badge green">Completed</span>"""
            "cancelled" -> """<span class="badge red">Cancelled</span>"""
            else -> order.status
        }
    }
}
```

---

## ModelForm

`ModelForm` handles form generation and validation for models.

### Automatic Form Generation

```kotlin
val form = ModelForm(User::class) {
    fields = listOf("username", "email", "password")
    widgets = mapOf(
        "password" to PasswordInput(),
        "email" to EmailInput()
    )
}
```

### Field Configuration

```kotlin
val form = ModelForm(User::class) {
    field("username") {
        required = true
        minLength = 3
        maxLength = 50
        helpText = "Letters, numbers, and underscores only"
        validators = listOf(RegexValidator("^[a-zA-Z0-9_]+$"))
    }
    
    field("email") {
        required = true
        widget = EmailInput()
        validators = listOf(EmailValidator())
    }
    
    field("role") {
        widget = Select(choices = Role.entries.map { it.name to it.displayName })
    }
}
```

### Form Processing

```kotlin
post("/admin/users/create") { exchange ->
    val form = UserForm()
    
    if (form.bind(exchange.request).isValid()) {
        val user = form.save()
        exchange.redirect("/admin/users/${user.id}")
    } else {
        exchange.render {
            userFormTemplate(form)  // Re-render with errors
        }
    }
}
```

---

## Dashboard Widgets

The admin dashboard supports customizable widgets for displaying metrics, lists, and actions.

### Built-in Widgets

#### StatWidget
Display a single metric with optional comparison.

```kotlin
adminSite.registerWidget(
    StatWidget(
        id = "total-users",
        title = "Total Users",
        value = { User.count() },
        icon = "users",
        color = "blue",
        comparison = {
            val today = User.filter { it.createdAt >= today() }.count()
            ComparisonValue(today, "new today")
        },
        link = "/admin/users/"
    )
)
```

#### ListWidget
Display a list of recent items.

```kotlin
adminSite.registerWidget(
    ListWidget(
        id = "recent-orders",
        title = "Recent Orders",
        items = {
            Order.all().orderBy("-createdAt").limit(5).map { order ->
                ListItem(
                    title = "Order #${order.id}",
                    subtitle = order.customer.name,
                    value = "$${order.total}",
                    link = "/admin/orders/${order.id}/"
                )
            }
        }
    )
)
```

#### QuickActionsWidget
Provide quick action buttons.

```kotlin
adminSite.registerWidget(
    QuickActionsWidget(
        id = "quick-actions",
        title = "Quick Actions",
        actions = listOf(
            QuickAction("Add User", "/admin/users/add/", icon = "user-plus"),
            QuickAction("Export Data", "/admin/export/", icon = "download"),
            QuickAction("Clear Cache", "/admin/cache/clear/", icon = "trash", method = "POST")
        )
    )
)
```

#### AlertWidget
Display system alerts or notifications.

```kotlin
adminSite.registerWidget(
    AlertWidget(
        id = "system-alerts",
        title = "System Status",
        alerts = {
            buildList {
                val pendingMigrations = Migration.pending().count()
                if (pendingMigrations > 0) {
                    add(Alert("warning", "$pendingMigrations pending migrations"))
                }
                
                val errorLogs = ErrorLog.recent(hours = 24).count()
                if (errorLogs > 100) {
                    add(Alert("error", "$errorLogs errors in last 24h"))
                }
            }
        }
    )
)
```

#### ProgressWidget
Display progress bars for quotas or goals.

```kotlin
adminSite.registerWidget(
    ProgressWidget(
        id = "storage-usage",
        title = "Storage Usage",
        items = {
            listOf(
                ProgressItem("Database", usedGB, totalGB, "GB"),
                ProgressItem("File Storage", usedFiles, maxFiles, "files")
            )
        }
    )
)
```

#### HtmlWidget
Render custom HTML content.

```kotlin
adminSite.registerWidget(
    HtmlWidget(
        id = "custom-chart",
        title = "Sales Chart",
        html = {
            """
            <canvas id="salesChart"></canvas>
            <script>
                // Chart.js initialization
            </script>
            """
        }
    )
)
```

### Widget Management

```kotlin
// Register a widget
adminSite.registerWidget(myWidget)

// Remove a widget
adminSite.unregisterWidget("widget-id")

// Update widget order
adminSite.setWidgetOrder(listOf("total-users", "recent-orders", "quick-actions"))

// Get all widgets
val widgets = adminSite.widgets
```

### Widget Context

Widgets receive context for dynamic content:

```kotlin
class MyCustomWidget : DashboardWidget {
    override val id = "my-widget"
    override val title = "My Widget"
    
    override suspend fun render(context: WidgetContext): String {
        val currentUser = context.user  // Admin user
        val site = context.adminSite    // AdminSite instance
        
        return """<div>Hello, ${currentUser.username}</div>"""
    }
}
```

---

## Mounting the Admin

### With Router

```kotlin
val router = router {
    mount("/admin", adminSite.routes())
    
    // Your other routes
    get("/") { exchange -> /* ... */ }
}
```

### With Pipeline

```kotlin
val pipeline = pipeline {
    installRecovery()
    installCallLogging()
    use(router.asMiddleware())
}
```

---

## Authentication

### Restricting Access

```kotlin
val adminSite = AdminSite(
    title = "Admin",
    urlPrefix = "/admin",
    authProvider = object : AdminAuthProvider {
        override suspend fun authenticate(exchange: Exchange): AdminUser? {
            val session = exchange.session ?: return null
            val user = User.findById(session.userId) ?: return null
            
            if (!user.isStaff) return null
            
            return AdminUser(
                id = user.id.toString(),
                username = user.username,
                isSuperuser = user.isSuperuser,
                permissions = user.permissions
            )
        }
        
        override fun loginUrl(): String = "/admin/login/"
    }
)
```

### Permission Checks

```kotlin
adminSite.register(User) {
    // Only superusers can delete
    hasDeletePermission = { user -> user.isSuperuser }
    
    // Custom add permission
    hasAddPermission = { user -> 
        user.hasPermission("users.add")
    }
    
    // Row-level permissions
    getQueryset = { user ->
        if (user.isSuperuser) {
            User.all()
        } else {
            User.filter { it.organizationId eq user.organizationId }
        }
    }
}
```

---

## Theming

### Custom CSS

```kotlin
val adminSite = AdminSite(
    title = "Admin",
    branding = AdminBranding(
        customCss = "/static/admin-custom.css"
    )
)
```

### Built-in Themes

```kotlin
val adminSite = AdminSite(
    title = "Admin",
    theme = AdminTheme.Dark  // Light, Dark, Auto
)
```
