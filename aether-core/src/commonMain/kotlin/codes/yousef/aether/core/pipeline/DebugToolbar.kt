package codes.yousef.aether.core.pipeline

import codes.yousef.aether.core.AttributeKey
import codes.yousef.aether.core.Exchange
import codes.yousef.aether.core.Response
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.coroutines.withContext

/**
 * Middleware that injects a debug toolbar into HTML responses.
 * Shows request timing, headers, and SQL queries.
 */
class DebugToolbar(private val config: DebugToolbarConfig) {
    
    suspend operator fun invoke(exchange: Exchange, next: suspend () -> Unit) {
        if (!config.enabled) {
            next()
            return
        }

        val start = Clock.System.now()
        val queryLog = QueryLogContext()
        
        // Register hook to inject toolbar
        val hooks = exchange.attributes.getOrPut(Exchange.HtmlResponseHooksKey) { mutableListOf<(String) -> String>() } as MutableList<(String) -> String>
        
        hooks.add { html ->
            val end = Clock.System.now()
            val duration = end.toEpochMilliseconds() - start.toEpochMilliseconds()
            val queries = queryLog.logs
            
            val toolbarHtml = generateToolbarHtml(duration, queries)
            
            // Inject before </body>
            if (html.contains("</body>")) {
                html.replace("</body>", "$toolbarHtml</body>")
            } else {
                html + toolbarHtml
            }
        }
        
        withContext(queryLog) {
            next()
        }
    }
    
    private fun generateToolbarHtml(duration: Long, queries: List<QueryLogEntry>): String {
        val queryRows = queries.joinToString("") { query ->
            """
            <tr style="border-bottom: 1px solid #eee;">
                <td style="padding: 8px; font-family: monospace;">${query.sql}</td>
                <td style="padding: 8px; text-align: right;">${query.durationMs}ms</td>
            </tr>
            """
        }
        
        return """
        <div id="aether-debug-toolbar" style="
            position: fixed; 
            bottom: 0; 
            left: 0; 
            right: 0; 
            background: #333; 
            color: #fff; 
            font-family: sans-serif; 
            font-size: 14px; 
            z-index: 9999;
            box-shadow: 0 -2px 10px rgba(0,0,0,0.2);
        ">
            <div style="padding: 10px 20px; display: flex; justify-content: space-between; align-items: center; cursor: pointer;" onclick="document.getElementById('aether-debug-details').style.display = document.getElementById('aether-debug-details').style.display === 'none' ? 'block' : 'none'">
                <div>
                    <strong>Aether Debug</strong>
                    <span style="margin-left: 20px;">Time: ${duration}ms</span>
                    <span style="margin-left: 20px;">Queries: ${queries.size}</span>
                </div>
                <div>
                    <span>^</span>
                </div>
            </div>
            <div id="aether-debug-details" style="display: none; background: #fff; color: #333; max-height: 400px; overflow-y: auto; border-top: 1px solid #ccc;">
                <div style="padding: 20px;">
                    <h3>SQL Queries (${queries.size})</h3>
                    <table style="width: 100%; border-collapse: collapse;">
                        <thead>
                            <tr style="background: #f5f5f5; text-align: left;">
                                <th style="padding: 8px;">Query</th>
                                <th style="padding: 8px; text-align: right;">Duration</th>
                            </tr>
                        </thead>
                        <tbody>
                            $queryRows
                        </tbody>
                    </table>
                </div>
            </div>
        </div>
        """
    }
    
    companion object {
        // val QueryLogKey = AttributeKey("QueryLog", MutableList::class) // Not used anymore, using Context
    }
}

data class DebugToolbarConfig(
    var enabled: Boolean = true
)

fun Pipeline.installDebugToolbar(configure: DebugToolbarConfig.() -> Unit = {}) {
    val config = DebugToolbarConfig().apply(configure)
    use(DebugToolbar(config)::invoke)
}
