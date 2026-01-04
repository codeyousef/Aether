package codes.yousef.aether.example

import codes.yousef.aether.admin.*
import codes.yousef.aether.ui.ComposableScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * E2E tests for Admin Dashboard Widgets.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminWidgetsE2ETest {
    
    private lateinit var adminSite: AdminSite
    
    @BeforeEach
    fun setup() {
        adminSite = AdminSite("Test Admin")
    }
    
    @Test
    fun `test StatWidget creation and data loading`() = runBlocking {
        var dataLoadCalled = false
        
        val widget = StatWidget(
            id = "user-count",
            title = "Total Users",
            order = 10,
            dataSource = { 
                dataLoadCalled = true
                42 
            }
        )
        
        assertEquals("user-count", widget.id)
        assertEquals("Total Users", widget.title)
        assertEquals(10, widget.order)
        assertEquals("small", widget.size)
        
        // Test data loading
        val data = widget.getData()
        assertTrue(dataLoadCalled)
        assertEquals(42, data)
    }
    
    @Test
    fun `test StatWidget with icon and color`() = runBlocking {
        val widget = StatWidget(
            id = "revenue",
            title = "Revenue",
            icon = "dollar",
            color = "green",
            href = "/admin/reports/revenue",
            dataSource = { 1234.56 }
        )
        
        assertEquals("dollar", widget.icon)
        assertEquals("green", widget.color)
        assertEquals("/admin/reports/revenue", widget.href)
    }
    
    @Test
    fun `test ListWidget creation and data loading`() = runBlocking {
        data class User(val id: Int, val name: String, val email: String)
        
        val testUsers = listOf(
            User(1, "Alice", "alice@example.com"),
            User(2, "Bob", "bob@example.com")
        )
        
        val widget = ListWidget(
            id = "recent-users",
            title = "Recent Users",
            headers = listOf("ID", "Name", "Email"),
            size = "medium",
            dataSource = { testUsers },
            rowRenderer = { user -> listOf(user.id.toString(), user.name, user.email) }
        )
        
        assertEquals("recent-users", widget.id)
        assertEquals("Recent Users", widget.title)
        assertEquals("medium", widget.size)
        assertEquals(listOf("ID", "Name", "Email"), widget.headers)
        
        // Test data loading
        val data = widget.getData() as List<*>
        assertEquals(2, data.size)
    }
    
    @Test
    fun `test ListWidget with empty data message`() = runBlocking {
        val widget = ListWidget<String>(
            id = "empty-list",
            title = "Empty List",
            headers = listOf("Column"),
            emptyMessage = "No items found",
            dataSource = { emptyList() },
            rowRenderer = { listOf(it) }
        )
        
        assertEquals("No items found", widget.emptyMessage)
        
        val data = widget.getData() as List<*>
        assertTrue(data.isEmpty())
    }
    
    @Test
    fun `test QuickActionsWidget creation`() = runBlocking {
        val widget = QuickActionsWidget(
            id = "quick-actions",
            title = "Quick Actions",
            order = 5,
            actions = listOf(
                QuickActionsWidget.QuickAction("Add User", "/admin/users/add", "plus"),
                QuickActionsWidget.QuickAction("Export Data", "/admin/export", "download", "primary")
            )
        )
        
        assertEquals("quick-actions", widget.id)
        assertEquals("Quick Actions", widget.title)
        assertEquals(5, widget.order)
        assertEquals(2, widget.actions.size)
        
        val addAction = widget.actions[0]
        assertEquals("Add User", addAction.label)
        assertEquals("/admin/users/add", addAction.href)
        assertEquals("plus", addAction.icon)
        assertEquals("secondary", addAction.variant)
        
        val exportAction = widget.actions[1]
        assertEquals("primary", exportAction.variant)
    }
    
    @Test
    fun `test HtmlWidget creation and data loading`() = runBlocking {
        var dataLoadCalled = false
        var renderCalled = false
        
        val widget = HtmlWidget(
            id = "custom-chart",
            title = "Sales Chart",
            size = "large",
            dataSource = { 
                dataLoadCalled = true
                mapOf("jan" to 100, "feb" to 200) 
            },
            renderer = { data, ctx ->
                renderCalled = true
                element("div") { text("Chart: $data") }
            }
        )
        
        assertEquals("custom-chart", widget.id)
        assertEquals("Sales Chart", widget.title)
        assertEquals("large", widget.size)
        
        // Test data loading
        val data = widget.getData()
        assertTrue(dataLoadCalled)
        assertNotNull(data)
    }
    
    @Test
    fun `test AlertWidget creation with different variants`() = runBlocking {
        val infoWidget = AlertWidget(
            id = "info-alert",
            title = "Information",
            variant = "info",
            messageSource = { "This is an info message" }
        )
        
        val warningWidget = AlertWidget(
            id = "warning-alert",
            title = "Warning",
            variant = "warning",
            messageSource = { "This is a warning" }
        )
        
        val errorWidget = AlertWidget(
            id = "error-alert",
            title = "Error",
            variant = "error",
            messageSource = { "This is an error" }
        )
        
        val successWidget = AlertWidget(
            id = "success-alert",
            title = "Success",
            variant = "success",
            messageSource = { "This is a success" }
        )
        
        assertEquals("info", infoWidget.variant)
        assertEquals("warning", warningWidget.variant)
        assertEquals("error", errorWidget.variant)
        assertEquals("success", successWidget.variant)
        assertEquals("large", infoWidget.size)
        assertEquals(10, infoWidget.order)
        
        // Test data loading
        val infoData = infoWidget.getData()
        assertEquals("This is an info message", infoData)
    }
    
    @Test
    fun `test AlertWidget with null message`() = runBlocking {
        val widget = AlertWidget(
            id = "conditional-alert",
            title = "Conditional",
            variant = "warning",
            messageSource = { null }
        )
        
        val data = widget.getData()
        assertNull(data)
    }
    
    @Test
    fun `test ProgressWidget creation and data loading`() = runBlocking {
        val widget = ProgressWidget(
            id = "storage-usage",
            title = "Storage Usage",
            maxValue = 1000,
            unit = " GB",
            color = "#3B82F6",
            dataSource = { 750L }
        )
        
        assertEquals("storage-usage", widget.id)
        assertEquals("Storage Usage", widget.title)
        assertEquals(1000L, widget.maxValue)
        assertEquals(" GB", widget.unit)
        assertEquals("#3B82F6", widget.color)
        assertEquals("small", widget.size)
        
        // Test data loading
        val data = widget.getData()
        assertEquals(750L, data)
    }
    
    @Test
    fun `test AdminSite widget registration`() = runBlocking {
        val widget1 = StatWidget(
            id = "widget-1",
            title = "Widget 1",
            order = 20,
            dataSource = { 1 }
        )
        
        val widget2 = StatWidget(
            id = "widget-2",
            title = "Widget 2",
            order = 10,
            dataSource = { 2 }
        )
        
        adminSite.registerWidget(widget1)
        adminSite.registerWidget(widget2)
        
        val widgets = adminSite.getWidgets()
        assertEquals(2, widgets.size)
        
        // Should be ordered by 'order' field
        assertEquals("widget-2", widgets[0].id) // order=10 comes first
        assertEquals("widget-1", widgets[1].id) // order=20 comes second
    }
    
    @Test
    fun `test AdminSite widget unregistration`() = runBlocking {
        val widget = StatWidget(
            id = "to-remove",
            title = "Temporary Widget",
            dataSource = { 0 }
        )
        
        adminSite.registerWidget(widget)
        assertEquals(1, adminSite.getWidgets().size)
        
        adminSite.unregisterWidget("to-remove")
        assertEquals(0, adminSite.getWidgets().size)
    }
    
    @Test
    fun `test WidgetContext contains correct data`() {
        val context = WidgetContext(
            siteName = "My Admin",
            currentPath = "/admin/dashboard",
            models = listOf("User" to "users", "Post" to "posts")
        )
        
        assertEquals("My Admin", context.siteName)
        assertEquals("/admin/dashboard", context.currentPath)
        assertEquals(2, context.models.size)
        assertEquals("User" to "users", context.models[0])
    }
    
    @Test
    fun `test DashboardWidget interface default values`() = runBlocking {
        val widget = object : DashboardWidget {
            override val id = "custom"
            override val title = "Custom Widget"
            
            override suspend fun getData(): Any? = null
            override fun ComposableScope.render(data: Any?, context: WidgetContext) {}
        }
        
        assertEquals(100, widget.order) // default
        assertEquals("small", widget.size) // default
    }
    
    @Test
    fun `test widget with same ID can be registered multiple times`() = runBlocking {
        val widget1 = StatWidget(
            id = "same-id",
            title = "Widget 1",
            dataSource = { 1 }
        )
        
        val widget2 = StatWidget(
            id = "same-id",
            title = "Widget 2",
            dataSource = { 2 }
        )
        
        adminSite.registerWidget(widget1)
        adminSite.registerWidget(widget2)
        
        val widgets = adminSite.getWidgets()
        // Both widgets are added (no automatic replacement by ID)
        assertEquals(2, widgets.size)
    }
    
    @Test
    fun `test multiple widget types together`() = runBlocking {
        adminSite.registerWidget(StatWidget(
            id = "stat",
            title = "Stat",
            order = 1,
            dataSource = { 42 }
        ))
        
        adminSite.registerWidget(AlertWidget(
            id = "alert",
            title = "Alert",
            order = 2,
            messageSource = { "Important!" }
        ))
        
        adminSite.registerWidget(ProgressWidget(
            id = "progress",
            title = "Progress",
            order = 3,
            dataSource = { 50L }
        ))
        
        adminSite.registerWidget(QuickActionsWidget(
            id = "actions",
            title = "Actions",
            order = 4,
            actions = listOf(QuickActionsWidget.QuickAction("Test", "/test"))
        ))
        
        val widgets = adminSite.getWidgets()
        assertEquals(4, widgets.size)
        
        // Verify order
        assertEquals("stat", widgets[0].id)
        assertEquals("alert", widgets[1].id)
        assertEquals("progress", widgets[2].id)
        assertEquals("actions", widgets[3].id)
    }
    
    @Test
    fun `test async data loading in widgets`() = runBlocking {
        var loadOrder = mutableListOf<String>()
        
        val widget1 = StatWidget(
            id = "async-1",
            title = "Async 1",
            dataSource = { 
                kotlinx.coroutines.delay(10)
                loadOrder.add("widget1")
                1 
            }
        )
        
        val widget2 = StatWidget(
            id = "async-2",
            title = "Async 2",
            dataSource = { 
                loadOrder.add("widget2")
                2 
            }
        )
        
        // Load data sequentially
        widget1.getData()
        widget2.getData()
        
        // Both should have loaded
        assertEquals(2, loadOrder.size)
        assertTrue(loadOrder.contains("widget1"))
        assertTrue(loadOrder.contains("widget2"))
    }
}
