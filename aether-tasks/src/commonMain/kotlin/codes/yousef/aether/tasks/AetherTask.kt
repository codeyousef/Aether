package codes.yousef.aether.tasks

/**
 * Marks a suspend function as a background task that can be queued for async execution.
 * 
 * Functions annotated with @AetherTask must:
 * - Be suspend functions
 * - Have exactly one parameter (the task arguments)
 * - Have a serializable argument type (use @Serializable)
 * - Have a serializable return type (use @Serializable, or Unit)
 * 
 * Example:
 * ```kotlin
 * @Serializable
 * data class SendEmailArgs(val to: String, val subject: String, val body: String)
 * 
 * @Serializable
 * data class EmailResult(val messageId: String, val sentAt: Long)
 * 
 * @AetherTask(queue = "emails", maxRetries = 5)
 * suspend fun sendEmail(args: SendEmailArgs): EmailResult {
 *     val result = emailService.send(args.to, args.subject, args.body)
 *     return EmailResult(result.id, System.currentTimeMillis())
 * }
 * 
 * // Generated code allows:
 * // SendEmailTask.delay(SendEmailArgs("user@example.com", "Hello", "World"))
 * ```
 * 
 * The KSP processor generates:
 * 1. A task wrapper object (e.g., `SendEmailTask`) with a `delay()` method
 * 2. A registration function to register the task with `TaskRegistry`
 * 3. A `registerAllTasks()` function to register all annotated tasks
 * 
 * @param name Custom task name. Defaults to fully qualified function name.
 * @param queue Queue name for routing. Defaults to "default".
 * @param priority Task priority. Defaults to NORMAL.
 * @param maxRetries Maximum retry attempts. Defaults to 3.
 * @param timeoutMillis Task timeout in milliseconds. Defaults to 5 minutes.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AetherTask(
    val name: String = "",
    val queue: String = "default",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val maxRetries: Int = 3,
    val timeoutMillis: Long = 300_000
)
