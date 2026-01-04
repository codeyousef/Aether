package codes.yousef.aether.admin

import codes.yousef.aether.ui.ComposableScope
import codes.yousef.aether.admin.AdminComponents.adminButton
import codes.yousef.aether.admin.AdminComponents.adminCard
import codes.yousef.aether.admin.AdminComponents.adminStatCard
import codes.yousef.aether.admin.AdminComponents.adminTable
import codes.yousef.aether.admin.AdminComponents.rawHtml

/**
 * Context provided to widgets for rendering.
 */
data class WidgetContext(
    /** Admin site name */
    val siteName: String,
    
    /** Current request path */
    val currentPath: String,
    
    /** List of registered models */
    val models: List<Pair<String, String>>
)

/**
 * Base interface for dashboard widgets.
 * 
 * Implement this interface to create custom dashboard widgets:
 * ```kotlin
 * class RevenueWidget : DashboardWidget {
 *     override val id = "revenue"
 *     override val title = "Total Revenue"
 *     override val order = 10
 *     
 *     override suspend fun getData(): Any {
 *         return Revenue.objects.sum("amount")
 *     }
 *     
 *     override fun ComposableScope.render(data: Any, context: WidgetContext) {
 *         adminStatCard(label = "Total Revenue", value = "$$data")
 *     }
 * }
 * ```
 */
interface DashboardWidget {
    /** Unique widget identifier */
    val id: String
    
    /** Widget title (used as card header if applicable) */
    val title: String
    
    /** Widget order (lower = higher priority) */
    val order: Int get() = 100
    
    /** Widget size: "small" (1 column), "medium" (2 columns), "large" (full width) */
    val size: String get() = "small"
    
    /**
     * Load data for the widget.
     * Called before rendering, can be suspended for async operations.
     */
    suspend fun getData(): Any?
    
    /**
     * Render the widget.
     * @param data Data returned from getData()
     * @param context Widget context
     */
    fun ComposableScope.render(data: Any?, context: WidgetContext)
}

/**
 * A simple stat widget showing a label and value.
 * 
 * Example:
 * ```kotlin
 * admin.registerWidget(StatWidget(
 *     id = "total-users",
 *     title = "Total Users",
 *     dataSource = { User.objects.count() },
 *     icon = AdminTheme.Icons.users,
 *     color = "green"
 * ))
 * ```
 */
class StatWidget(
    override val id: String,
    override val title: String,
    override val order: Int = 100,
    val icon: String? = null,
    val color: String? = null,
    val href: String? = null,
    private val dataSource: suspend () -> Any?
) : DashboardWidget {
    
    override val size: String = "small"
    
    override suspend fun getData(): Any? = dataSource()
    
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        // Wrap with optional link if href is provided
        if (href != null) {
            element("a", mapOf("href" to href, "style" to "text-decoration: none;")) {
                adminStatCard(
                    label = title,
                    value = data?.toString() ?: "-"
                )
            }
        } else {
            adminStatCard(
                label = title,
                value = data?.toString() ?: "-"
            )
        }
    }
}

/**
 * A list widget showing a table of items.
 * 
 * Example:
 * ```kotlin
 * admin.registerWidget(ListWidget(
 *     id = "recent-users",
 *     title = "Recent Users",
 *     headers = listOf("Name", "Email", "Created"),
 *     dataSource = { User.objects.orderBy("-created_at").limit(5).toList() },
 *     rowRenderer = { user ->
 *         listOf(user.name, user.email, user.createdAt.toString())
 *     }
 * ))
 * ```
 */
class ListWidget<T>(
    override val id: String,
    override val title: String,
    override val order: Int = 100,
    override val size: String = "medium",
    val headers: List<String>,
    val emptyMessage: String = "No items",
    private val dataSource: suspend () -> List<T>,
    private val rowRenderer: (T) -> List<String>
) : DashboardWidget {
    
    override suspend fun getData(): Any = dataSource()
    
    @Suppress("UNCHECKED_CAST")
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        val items = data as? List<T> ?: emptyList()
        
        adminCard(title = title) {
            if (items.isEmpty()) {
                element("div", mapOf(
                    "style" to "padding: 24px; text-align: center; color: ${AdminTheme.Colors.TEXT_SECONDARY};"
                )) {
                    text(emptyMessage)
                }
            } else {
                adminTable(headers = headers) {
                    for (item in items) {
                        element("tr") {
                            for (cell in rowRenderer(item)) {
                                element("td") { text(cell) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A quick actions widget showing action buttons.
 * 
 * Example:
 * ```kotlin
 * admin.registerWidget(QuickActionsWidget(
 *     id = "quick-actions",
 *     title = "Quick Actions",
 *     actions = listOf(
 *         QuickAction("Add User", "/admin/users/add", AdminTheme.Icons.plus),
 *         QuickAction("Export Data", "/admin/export", AdminTheme.Icons.download)
 *     )
 * ))
 * ```
 */
class QuickActionsWidget(
    override val id: String,
    override val title: String = "Quick Actions",
    override val order: Int = 50,
    override val size: String = "medium",
    val actions: List<QuickAction>
) : DashboardWidget {
    
    data class QuickAction(
        val label: String,
        val href: String,
        val icon: String? = null,
        val variant: String = "secondary"
    )
    
    override suspend fun getData(): Any? = null
    
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        adminCard(title = title) {
            element("div", mapOf("style" to "display: flex; gap: 12px; flex-wrap: wrap;")) {
                for (action in actions) {
                    adminButton(
                        text = action.label,
                        href = action.href,
                        icon = action.icon,
                        variant = action.variant
                    )
                }
            }
        }
    }
}

/**
 * A custom HTML widget for advanced use cases.
 * 
 * Example:
 * ```kotlin
 * admin.registerWidget(HtmlWidget(
 *     id = "chart",
 *     title = "Sales Chart",
 *     size = "large",
 *     dataSource = { Sales.objects.groupBy("month").aggregate() },
 *     renderer = { data, ctx ->
 *         adminCard(title = "Sales Chart") {
 *             // Render chart using data
 *         }
 *     }
 * ))
 * ```
 */
class HtmlWidget(
    override val id: String,
    override val title: String,
    override val order: Int = 100,
    override val size: String = "medium",
    private val dataSource: suspend () -> Any?,
    private val renderer: ComposableScope.(Any?, WidgetContext) -> Unit
) : DashboardWidget {
    
    override suspend fun getData(): Any? = dataSource()
    
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        renderer(data, context)
    }
}

/**
 * A notification/alert widget.
 */
class AlertWidget(
    override val id: String,
    override val title: String,
    override val order: Int = 10,
    override val size: String = "large",
    val variant: String = "info", // info, warning, error, success
    private val messageSource: suspend () -> String?
) : DashboardWidget {
    
    override suspend fun getData(): Any? = messageSource()
    
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        val message = data as? String ?: return
        
        val (bgColor, borderColor, textColor) = when (variant) {
            "warning" -> Triple("#FEF3C7", "#F59E0B", "#92400E")
            "error" -> Triple("#FEE2E2", "#EF4444", "#991B1B")
            "success" -> Triple("#D1FAE5", "#10B981", "#065F46")
            else -> Triple("#DBEAFE", "#3B82F6", "#1E40AF")
        }
        
        element("div", mapOf(
            "style" to """
                background: $bgColor;
                border: 1px solid $borderColor;
                border-radius: 8px;
                padding: 16px;
                color: $textColor;
                margin-bottom: 16px;
            """.trimIndent().replace("\n", " ")
        )) {
            element("strong") { text(title) }
            element("p", mapOf("style" to "margin: 8px 0 0 0;")) { text(message) }
        }
    }
}

/**
 * A progress/meter widget showing a value with a progress bar.
 */
class ProgressWidget(
    override val id: String,
    override val title: String,
    override val order: Int = 100,
    val maxValue: Long = 100,
    val unit: String = "",
    val color: String = AdminTheme.Colors.PRIMARY,
    private val dataSource: suspend () -> Long
) : DashboardWidget {
    
    override val size: String = "small"
    
    override suspend fun getData(): Any = dataSource()
    
    override fun ComposableScope.render(data: Any?, context: WidgetContext) {
        val value = (data as? Long) ?: 0L
        val percentage = (value.toDouble() / maxValue * 100).coerceIn(0.0, 100.0)
        
        adminCard(title = title) {
            element("div", mapOf("style" to "padding: 8px 0;")) {
                element("div", mapOf(
                    "style" to "display: flex; justify-content: space-between; margin-bottom: 8px;"
                )) {
                    element("span") { text("$value$unit") }
                    element("span", mapOf("style" to "color: ${AdminTheme.Colors.TEXT_SECONDARY};")) {
                        text("${percentage.toInt()}%")
                    }
                }
                element("div", mapOf(
                    "style" to """
                        background: ${AdminTheme.Colors.BORDER};
                        border-radius: 4px;
                        height: 8px;
                        overflow: hidden;
                    """.trimIndent().replace("\n", " ")
                )) {
                    element("div", mapOf(
                        "style" to """
                            background: $color;
                            height: 100%;
                            width: ${percentage}%;
                            transition: width 0.3s ease;
                        """.trimIndent().replace("\n", " ")
                    ))
                }
            }
        }
    }
}
