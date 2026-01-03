package codes.yousef.aether.admin

import codes.yousef.aether.ui.ComposableScope
import codes.yousef.aether.core.Exchange

/**
 * Reusable UI components for the modern admin interface.
 */
object AdminComponents {
    
    /**
     * Renders the complete admin page layout with sidebar and main content.
     */
    fun ComposableScope.adminLayout(
        title: String,
        siteName: String,
        currentPath: String,
        models: List<Pair<String, String>>, // tableName to displayName
        content: ComposableScope.() -> Unit
    ) {
        element("html") {
            element("head") {
                element("meta", mapOf("charset" to "UTF-8"))
                element("meta", mapOf("name" to "viewport", "content" to "width=device-width, initial-scale=1.0"))
                element("title") { text("$title | $siteName Admin") }
                element("style") { text(AdminTheme.generateStyles()) }
            }
            element("body") {
                element("div", mapOf("class" to "admin-layout")) {
                    // Sidebar
                    adminSidebar(siteName, currentPath, models)
                    
                    // Main content area
                    element("main", mapOf("class" to "admin-main")) {
                        content()
                    }
                }
            }
        }
    }
    
    /**
     * Renders the sidebar navigation.
     */
    fun ComposableScope.adminSidebar(
        siteName: String,
        currentPath: String,
        models: List<Pair<String, String>>
    ) {
        val basePath = "/$siteName"
        
        element("aside", mapOf("class" to "admin-sidebar")) {
            // Header with logo
            element("div", mapOf("class" to "admin-sidebar-header")) {
                element("a", mapOf("href" to basePath, "class" to "admin-sidebar-logo")) {
                    rawHtml(AdminTheme.Icons.logo)
                    text("Aether Admin")
                }
            }
            
            // Navigation
            element("nav", mapOf("class" to "admin-sidebar-nav")) {
                // Dashboard link
                element("div", mapOf("class" to "admin-nav-section")) {
                    val dashboardActive = currentPath == basePath || currentPath == "$basePath/"
                    element("a", mapOf(
                        "href" to basePath,
                        "class" to "admin-nav-item${if (dashboardActive) " active" else ""}"
                    )) {
                        rawHtml(AdminTheme.Icons.dashboard)
                        text("Dashboard")
                    }
                }
                
                // Models section
                if (models.isNotEmpty()) {
                    element("div", mapOf("class" to "admin-nav-section")) {
                        element("div", mapOf("class" to "admin-nav-section-title")) {
                            text("Content")
                        }
                        
                        for ((tableName, displayName) in models) {
                            val modelPath = "$basePath/$tableName"
                            val isActive = currentPath.startsWith(modelPath)
                            
                            element("a", mapOf(
                                "href" to modelPath,
                                "class" to "admin-nav-item${if (isActive) " active" else ""}"
                            )) {
                                rawHtml(AdminTheme.Icons.database)
                                text(displayName)
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Renders the page header with title and optional actions.
     */
    fun ComposableScope.adminPageHeader(
        title: String,
        actions: ComposableScope.() -> Unit = {}
    ) {
        element("header", mapOf("class" to "admin-header")) {
            element("h1", mapOf("class" to "admin-header-title")) { text(title) }
            element("div", mapOf("class" to "admin-header-actions")) {
                actions()
            }
        }
    }
    
    /**
     * Renders breadcrumb navigation.
     */
    fun ComposableScope.adminBreadcrumbs(vararg items: Pair<String, String?>) {
        element("nav", mapOf("class" to "admin-breadcrumbs")) {
            items.forEachIndexed { index, (label, href) ->
                if (index > 0) {
                    element("span", mapOf("class" to "admin-breadcrumbs-separator")) { text("/") }
                }
                
                if (href != null) {
                    element("a", mapOf("href" to href)) { text(label) }
                } else {
                    element("span") { text(label) }
                }
            }
        }
    }
    
    /**
     * Renders a primary button.
     */
    fun ComposableScope.adminButton(
        text: String,
        href: String? = null,
        variant: String = "primary",
        type: String = "button",
        icon: String? = null
    ) {
        val classes = "admin-btn admin-btn-$variant"
        
        if (href != null) {
            element("a", mapOf("href" to href, "class" to classes)) {
                if (icon != null) rawHtml(icon)
                text(text)
            }
        } else {
            element("button", mapOf("type" to type, "class" to classes)) {
                if (icon != null) rawHtml(icon)
                text(text)
            }
        }
    }
    
    /**
     * Renders a card container.
     */
    fun ComposableScope.adminCard(
        title: String? = null,
        headerActions: ComposableScope.() -> Unit = {},
        content: ComposableScope.() -> Unit
    ) {
        element("div", mapOf("class" to "admin-card")) {
            if (title != null) {
                element("div", mapOf("class" to "admin-card-header")) {
                    element("h2", mapOf("class" to "admin-card-title")) { text(title) }
                    element("div", mapOf("class" to "admin-header-actions")) { headerActions() }
                }
            }
            element("div", mapOf("class" to "admin-card-body")) {
                content()
            }
        }
    }
    
    /**
     * Renders a statistics card.
     */
    fun ComposableScope.adminStatCard(label: String, value: String) {
        element("div", mapOf("class" to "admin-stat-card")) {
            element("div", mapOf("class" to "admin-stat-label")) { text(label) }
            element("div", mapOf("class" to "admin-stat-value")) { text(value) }
        }
    }
    
    /**
     * Renders a search input.
     */
    fun ComposableScope.adminSearchInput(
        name: String = "q",
        value: String? = null,
        placeholder: String = "Search..."
    ) {
        element("div", mapOf("class" to "admin-search")) {
            element("span", mapOf("class" to "admin-search-icon")) {
                rawHtml(AdminTheme.Icons.search)
            }
            element("input", mapOf(
                "type" to "text",
                "name" to name,
                "class" to "admin-search-input",
                "placeholder" to placeholder,
                "value" to (value ?: "")
            ))
        }
    }
    
    /**
     * Renders an admin table.
     */
    fun ComposableScope.adminTable(
        headers: List<String>,
        content: ComposableScope.() -> Unit
    ) {
        element("table", mapOf("class" to "admin-table")) {
            element("thead") {
                element("tr") {
                    for (header in headers) {
                        element("th") { text(header) }
                    }
                    element("th") { text("Actions") }
                }
            }
            element("tbody") {
                content()
            }
        }
    }
    
    /**
     * Renders a table row with action buttons.
     */
    fun ComposableScope.adminTableRow(
        editUrl: String,
        deleteUrl: String,
        content: ComposableScope.() -> Unit
    ) {
        element("tr") {
            content()
            element("td") {
                element("div", mapOf("class" to "admin-actions")) {
                    element("a", mapOf("href" to editUrl, "class" to "admin-action-btn", "title" to "Edit")) {
                        rawHtml(AdminTheme.Icons.edit)
                    }
                    element("a", mapOf("href" to deleteUrl, "class" to "admin-action-btn delete", "title" to "Delete")) {
                        rawHtml(AdminTheme.Icons.trash)
                    }
                }
            }
        }
    }
    
    /**
     * Renders an empty state message.
     */
    fun ComposableScope.adminEmptyState(
        title: String = "No items found",
        message: String = "Get started by creating your first item.",
        actionText: String? = null,
        actionUrl: String? = null
    ) {
        element("div", mapOf("class" to "admin-empty")) {
            element("div", mapOf("class" to "admin-empty-icon")) {
                rawHtml(AdminTheme.Icons.empty)
            }
            element("div", mapOf("class" to "admin-empty-title")) { text(title) }
            element("p") { text(message) }
            
            if (actionText != null && actionUrl != null) {
                element("div", mapOf("style" to "margin-top: 16px")) {
                    adminButton(actionText, href = actionUrl, icon = AdminTheme.Icons.plus)
                }
            }
        }
    }
    
    /**
     * Renders a form group with label and input.
     */
    fun ComposableScope.adminFormGroup(
        label: String,
        name: String,
        type: String = "text",
        value: String = "",
        required: Boolean = false,
        helpText: String? = null,
        error: String? = null,
        multiline: Boolean = false,
        rows: Int = 4
    ) {
        element("div", mapOf("class" to "admin-form-group")) {
            element("label", mapOf("for" to name, "class" to "admin-form-label")) {
                text(label)
                if (required) {
                    element("span", mapOf("style" to "color: ${AdminTheme.Colors.ERROR}")) { text(" *") }
                }
            }
            
            if (multiline) {
                element("textarea", mapOf(
                    "id" to name,
                    "name" to name,
                    "class" to "admin-form-input admin-form-textarea",
                    "rows" to rows.toString(),
                    "required" to required.toString()
                )) { text(value) }
            } else {
                element("input", mapOf(
                    "type" to type,
                    "id" to name,
                    "name" to name,
                    "class" to "admin-form-input",
                    "value" to value,
                    "required" to required.toString()
                ))
            }
            
            if (error != null) {
                element("div", mapOf("class" to "admin-form-error")) { text(error) }
            } else if (helpText != null) {
                element("div", mapOf("class" to "admin-form-help")) { text(helpText) }
            }
        }
    }
    
    /**
     * Renders a checkbox form group.
     */
    fun ComposableScope.adminFormCheckbox(
        label: String,
        name: String,
        checked: Boolean = false
    ) {
        element("div", mapOf("class" to "admin-form-group")) {
            element("label", mapOf("class" to "admin-form-checkbox")) {
                val attrs = mutableMapOf(
                    "type" to "checkbox",
                    "id" to name,
                    "name" to name
                )
                if (checked) attrs["checked"] = "checked"
                element("input", attrs)
                element("span") { text(label) }
            }
        }
    }
    
    /**
     * Renders a badge/tag element.
     */
    fun ComposableScope.adminBadge(
        text: String,
        variant: String = "info" // success, warning, error, info
    ) {
        element("span", mapOf("class" to "admin-badge admin-badge-$variant")) {
            text(text)
        }
    }
    
    /**
     * Renders a filter sidebar.
     */
    fun ComposableScope.adminFilterSidebar(
        content: ComposableScope.() -> Unit
    ) {
        element("aside", mapOf("class" to "admin-filter-sidebar")) {
            element("div", mapOf("class" to "admin-filter-card")) {
                element("div", mapOf("class" to "admin-filter-title")) { text("Filters") }
                content()
            }
        }
    }
    
    /**
     * Renders a filter section within the sidebar.
     */
    fun ComposableScope.adminFilterSection(
        title: String,
        content: ComposableScope.() -> Unit
    ) {
        element("div", mapOf("class" to "admin-filter-section")) {
            element("div", mapOf("class" to "admin-filter-section-title")) { text(title) }
            content()
        }
    }
    
    /**
     * Renders a filter option link.
     */
    fun ComposableScope.adminFilterOption(
        label: String,
        href: String,
        active: Boolean = false
    ) {
        element("a", mapOf(
            "href" to href,
            "class" to "admin-filter-option${if (active) " active" else ""}"
        )) {
            text(label)
        }
    }
    
    /**
     * Helper to output raw HTML (for SVG icons).
     * Note: This requires a special implementation in the renderer.
     */
    fun ComposableScope.rawHtml(html: String) {
        // We'll use a special element that the renderer handles
        element("span", mapOf("data-raw-html" to html))
    }
}
