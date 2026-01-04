package codes.yousef.aether.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

/**
 * KSP processor for generating task registration code from @AetherTask annotations.
 * 
 * This processor finds functions annotated with @AetherTask and generates:
 * 1. A typed task wrapper for type-safe enqueueing
 * 2. Registration code for the TaskRegistry
 * 
 * Example:
 * ```kotlin
 * @AetherTask
 * suspend fun sendEmail(args: SendEmailArgs): EmailResult {
 *     // ... implementation
 * }
 * ```
 * 
 * Generates:
 * ```kotlin
 * object SendEmailTask {
 *     const val NAME = "com.example.sendEmail"
 *     
 *     suspend fun delay(args: SendEmailArgs, configure: TaskOptions.() -> Unit = {}): String {
 *         return TaskDispatcher.enqueue(NAME, args, configure)
 *     }
 * }
 * 
 * fun registerSendEmailTask() {
 *     TaskRegistry.register<SendEmailArgs, EmailResult>(NAME) { args ->
 *         sendEmail(args)
 *     }
 * }
 * ```
 */
class TaskProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val generatedPackage = options["aether.tasks.package"] ?: "codes.yousef.aether.tasks.generated"

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val annotationName = "codes.yousef.aether.tasks.AetherTask"
        val symbols = resolver.getSymbolsWithAnnotation(annotationName)
            .filterIsInstance<KSFunctionDeclaration>()

        if (!symbols.iterator().hasNext()) {
            return emptyList()
        }

        val notValid = symbols.filterNot { it.validate() }.toList()
        val validFunctions = symbols.filter { it.validate() }.toList()

        if (validFunctions.isEmpty()) {
            return notValid
        }

        val taskInfos = mutableListOf<TaskInfo>()

        for (function in validFunctions) {
            try {
                val taskInfo = processFunction(function)
                if (taskInfo != null) {
                    taskInfos.add(taskInfo)
                    generateTaskWrapper(taskInfo)
                }
            } catch (e: Exception) {
                logger.error("Failed to process @AetherTask function ${function.simpleName.asString()}: ${e.message}", function)
            }
        }

        if (taskInfos.isNotEmpty()) {
            generateRegistrationFile(taskInfos)
        }

        return notValid
    }

    private data class TaskInfo(
        val functionName: String,
        val packageName: String,
        val qualifiedName: String,
        val taskName: String,
        val argType: TypeName,
        val argTypeFqn: String,
        val returnType: TypeName,
        val returnTypeFqn: String,
        val queue: String,
        val priority: String,
        val maxRetries: Int,
        val timeoutMillis: Long
    )

    private fun processFunction(function: KSFunctionDeclaration): TaskInfo? {
        // Validate function signature
        if (!function.modifiers.contains(Modifier.SUSPEND)) {
            logger.error("@AetherTask functions must be suspend functions", function)
            return null
        }

        val parameters = function.parameters
        if (parameters.size != 1) {
            logger.error("@AetherTask functions must have exactly one parameter (the args)", function)
            return null
        }

        val argParam = parameters.first()
        val argType = argParam.type.resolve()
        val returnType = function.returnType?.resolve()

        if (returnType == null) {
            logger.error("@AetherTask functions must have an explicit return type", function)
            return null
        }

        // Get annotation values
        val annotation = function.annotations.find { 
            it.annotationType.resolve().declaration.qualifiedName?.asString() == "codes.yousef.aether.tasks.AetherTask"
        }

        val annotationArgs = annotation?.arguments?.associate { 
            it.name?.asString() to it.value 
        } ?: emptyMap()

        val functionName = function.simpleName.asString()
        val packageName = function.packageName.asString()
        val qualifiedName = "$packageName.$functionName"
        
        val taskName = annotationArgs["name"]?.toString()?.takeIf { it.isNotBlank() }
            ?: qualifiedName

        return TaskInfo(
            functionName = functionName,
            packageName = packageName,
            qualifiedName = qualifiedName,
            taskName = taskName,
            argType = argType.toTypeName(),
            argTypeFqn = argType.declaration.qualifiedName?.asString() ?: "",
            returnType = returnType.toTypeName(),
            returnTypeFqn = returnType.declaration.qualifiedName?.asString() ?: "",
            queue = annotationArgs["queue"]?.toString() ?: "default",
            priority = annotationArgs["priority"]?.toString() ?: "NORMAL",
            maxRetries = (annotationArgs["maxRetries"] as? Int) ?: 3,
            timeoutMillis = (annotationArgs["timeoutMillis"] as? Long) ?: 300_000L
        )
    }

    private fun generateTaskWrapper(info: TaskInfo) {
        val objectName = info.functionName.replaceFirstChar { it.uppercase() } + "Task"
        
        val taskOptionsClass = ClassName("codes.yousef.aether.tasks", "TaskOptions")
        val taskDispatcherClass = ClassName("codes.yousef.aether.tasks", "TaskDispatcher")
        
        val fileSpec = FileSpec.builder(generatedPackage, objectName)
            .addImport("codes.yousef.aether.tasks", "TaskDispatcher")
            .addImport("codes.yousef.aether.tasks", "TaskOptions")
            .addImport("kotlinx.serialization", "serializer")
            .addType(
                TypeSpec.objectBuilder(objectName)
                    .addKdoc("Generated task wrapper for ${info.functionName}\n")
                    .addProperty(
                        PropertySpec.builder("NAME", String::class)
                            .addModifiers(KModifier.CONST)
                            .initializer("%S", info.taskName)
                            .build()
                    )
                    .addFunction(
                        FunSpec.builder("delay")
                            .addModifiers(KModifier.SUSPEND)
                            .addKdoc("Queue this task for async execution.\n")
                            .addKdoc("@param args Task arguments\n")
                            .addKdoc("@param configure Optional configuration\n")
                            .addKdoc("@return Task ID\n")
                            .addParameter("args", info.argType)
                            .addParameter(
                                ParameterSpec.builder(
                                    "configure",
                                    LambdaTypeName.get(
                                        receiver = taskOptionsClass,
                                        returnType = Unit::class.asTypeName()
                                    )
                                ).defaultValue("{}").build()
                            )
                            .returns(String::class)
                            .addStatement(
                                "return %T.enqueue(NAME, args, serializer(), configure)",
                                taskDispatcherClass
                            )
                            .build()
                    )
                    .addFunction(
                        FunSpec.builder("delayFor")
                            .addModifiers(KModifier.SUSPEND)
                            .addKdoc("Queue this task to run after a delay.\n")
                            .addParameter("args", info.argType)
                            .addParameter("delayMillis", Long::class)
                            .returns(String::class)
                            .addStatement(
                                "return delay(args) { this.delayMillis = delayMillis }"
                            )
                            .build()
                    )
                    .build()
            )
            .build()
        
        fileSpec.writeTo(codeGenerator, Dependencies(false))
    }

    private fun generateRegistrationFile(tasks: List<TaskInfo>) {
        val taskRegistryClass = ClassName("codes.yousef.aether.tasks", "TaskRegistry")
        val taskOptionsClass = ClassName("codes.yousef.aether.tasks", "TaskOptions")
        val taskPriorityClass = ClassName("codes.yousef.aether.tasks", "TaskPriority")
        
        val fileSpec = FileSpec.builder(generatedPackage, "TaskRegistrations")
            .addImport("codes.yousef.aether.tasks", "TaskRegistry")
            .addImport("codes.yousef.aether.tasks", "TaskOptions")
            .addImport("codes.yousef.aether.tasks", "TaskPriority")
            .addImport("kotlinx.serialization", "serializer")
        
        // Add imports for each task's original function
        for (task in tasks) {
            fileSpec.addImport(task.packageName, task.functionName)
        }
        
        // Generate registration function for each task
        for (task in tasks) {
            val registerFnName = "register${task.functionName.replaceFirstChar { it.uppercase() }}Task"
            
            fileSpec.addFunction(
                FunSpec.builder(registerFnName)
                    .addKdoc("Register the ${task.functionName} task with TaskRegistry.\n")
                    .addStatement(
                        """%T.register(
                            name = %S,
                            argSerializer = serializer(),
                            resultSerializer = serializer(),
                            options = %T(
                                queue = %S,
                                priority = %T.%L,
                                maxRetries = %L,
                                timeoutMillis = %LL
                            )
                        ) { args -> %L(args) }""".trimIndent(),
                        taskRegistryClass,
                        task.taskName,
                        taskOptionsClass,
                        task.queue,
                        taskPriorityClass,
                        task.priority,
                        task.maxRetries,
                        task.timeoutMillis,
                        task.functionName
                    )
                    .build()
            )
        }
        
        // Generate a function to register all tasks at once
        fileSpec.addFunction(
            FunSpec.builder("registerAllTasks")
                .addKdoc("Register all @AetherTask annotated functions with TaskRegistry.\n")
                .addKdoc("Call this during application startup.\n")
                .apply {
                    for (task in tasks) {
                        val registerFnName = "register${task.functionName.replaceFirstChar { it.uppercase() }}Task"
                        addStatement("%L()", registerFnName)
                    }
                }
                .build()
        )
        
        fileSpec.build().writeTo(codeGenerator, Dependencies(false))
    }

    private fun KSType.toTypeName(): TypeName {
        val declaration = this.declaration
        val className = when (declaration) {
            is KSClassDeclaration -> declaration.toClassName()
            else -> ClassName.bestGuess(declaration.qualifiedName?.asString() ?: "kotlin.Any")
        }
        
        val typeArgs = this.arguments.mapNotNull { arg ->
            arg.type?.resolve()?.toTypeName()
        }
        
        return if (typeArgs.isEmpty()) {
            className
        } else {
            className.parameterizedBy(typeArgs)
        }
    }
}

/**
 * Provider for the TaskProcessor.
 */
class TaskProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return TaskProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
