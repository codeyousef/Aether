package codes.yousef.aether.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate

/**
 * KSP processor for generating Protocol Buffer definitions from Kotlin code.
 *
 * Processes:
 * - @AetherMessage data classes -> proto message definitions
 * - @AetherService interfaces -> proto service definitions
 *
 * Generates .proto files that can be used by protoc to generate client code
 * for other languages, or for documentation purposes.
 *
 * Example:
 * ```kotlin
 * @AetherMessage
 * data class User(
 *     @ProtoField(1) val id: String,
 *     @ProtoField(2) val name: String,
 *     @ProtoField(3) val age: Int? = null
 * )
 *
 * @AetherService
 * interface UserService {
 *     @AetherRpc
 *     suspend fun getUser(request: GetUserRequest): User
 *
 *     @AetherRpc
 *     suspend fun listUsers(request: ListUsersRequest): Flow<User>
 * }
 * ```
 *
 * Generates:
 * ```protobuf
 * syntax = "proto3";
 * package example.v1;
 *
 * message User {
 *     string id = 1;
 *     string name = 2;
 *     optional int32 age = 3;
 * }
 *
 * service UserService {
 *     rpc GetUser(GetUserRequest) returns (User);
 *     rpc ListUsers(ListUsersRequest) returns (stream User);
 * }
 * ```
 */
class ProtoProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {

    private val mapper = KotlinToProtoMapper()
    private val generator = ProtoGenerator()

    private val protoPackage = options["aether.proto.package"] ?: ""
    private val outputDir = options["aether.proto.output"] ?: "proto"

    companion object {
        private const val AETHER_MESSAGE = "codes.yousef.aether.grpc.annotation.AetherMessage"
        private const val AETHER_SERVICE = "codes.yousef.aether.grpc.annotation.AetherService"
        private const val AETHER_RPC = "codes.yousef.aether.grpc.annotation.AetherRpc"
        private const val PROTO_FIELD = "codes.yousef.aether.grpc.annotation.ProtoField"
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val messageSymbols = resolver.getSymbolsWithAnnotation(AETHER_MESSAGE)
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        val serviceSymbols = resolver.getSymbolsWithAnnotation(AETHER_SERVICE)
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.INTERFACE }
            .toList()

        if (messageSymbols.isEmpty() && serviceSymbols.isEmpty()) {
            return emptyList()
        }

        val notValidMessages = messageSymbols.filterNot { it.validate() }
        val notValidServices = serviceSymbols.filterNot { it.validate() }

        val validMessages = messageSymbols.filter { it.validate() }
        val validServices = serviceSymbols.filter { it.validate() }

        if (validMessages.isEmpty() && validServices.isEmpty()) {
            return notValidMessages + notValidServices
        }

        // Group by package for file generation
        val messagesByPackage = mutableMapOf<String, MutableList<ProtoMessageInfo>>()
        val servicesByPackage = mutableMapOf<String, MutableList<ProtoServiceInfo>>()

        // Process messages
        for (classDecl in validMessages) {
            try {
                val messageInfo = processMessage(classDecl)
                if (messageInfo != null) {
                    val pkg = getProtoPackage(classDecl)
                    messagesByPackage.getOrPut(pkg) { mutableListOf() }.add(messageInfo)
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to process @AetherMessage ${classDecl.simpleName.asString()}: ${e.message}",
                    classDecl
                )
            }
        }

        // Process services
        for (interfaceDecl in validServices) {
            try {
                val serviceInfo = processService(interfaceDecl)
                if (serviceInfo != null) {
                    val pkg = getProtoPackage(interfaceDecl)
                    servicesByPackage.getOrPut(pkg) { mutableListOf() }.add(serviceInfo)
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to process @AetherService ${interfaceDecl.simpleName.asString()}: ${e.message}",
                    interfaceDecl
                )
            }
        }

        // Generate proto files
        val allPackages = (messagesByPackage.keys + servicesByPackage.keys).distinct()
        for (pkg in allPackages) {
            val messages = messagesByPackage[pkg] ?: emptyList()
            val services = servicesByPackage[pkg] ?: emptyList()
            generateProtoFile(pkg, messages, services)
        }

        return notValidMessages + notValidServices
    }

    private fun getProtoPackage(declaration: KSClassDeclaration): String {
        // Check for explicit package in annotation
        val annotation = declaration.annotations.find {
            val name = it.annotationType.resolve().declaration.qualifiedName?.asString()
            name == AETHER_MESSAGE || name == AETHER_SERVICE
        }
        val annotationArgs = annotation?.arguments?.associate {
            it.name?.asString() to it.value
        } ?: emptyMap()

        // Use annotation value, option, or derive from Kotlin package
        return (annotationArgs["name"] as? String)?.takeIf { it.contains('.') }?.substringBeforeLast('.')
            ?: protoPackage.takeIf { it.isNotEmpty() }
            ?: declaration.packageName.asString().replace('.', '_')
    }

    private fun processMessage(classDecl: KSClassDeclaration): ProtoMessageInfo? {
        if (classDecl.classKind != ClassKind.CLASS) {
            logger.error("@AetherMessage can only be applied to data classes", classDecl)
            return null
        }

        val annotation = classDecl.annotations.find {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AETHER_MESSAGE
        }
        val annotationArgs = annotation?.arguments?.associate {
            it.name?.asString() to it.value
        } ?: emptyMap()

        val messageName = (annotationArgs["name"] as? String)?.takeIf { it.isNotEmpty() }?.substringAfterLast('.')
            ?: classDecl.simpleName.asString()

        val fields = mutableListOf<ProtoFieldInfo>()
        val seenFieldNumbers = mutableSetOf<Int>()

        // Process constructor parameters with @ProtoField
        val primaryConstructor = classDecl.primaryConstructor
        if (primaryConstructor == null) {
            logger.error("@AetherMessage class must have a primary constructor", classDecl)
            return null
        }

        for (param in primaryConstructor.parameters) {
            val protoFieldAnnotation = param.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PROTO_FIELD
            }

            if (protoFieldAnnotation == null) {
                logger.warn("Property ${param.name?.asString()} missing @ProtoField annotation, skipping", param)
                continue
            }

            val fieldArgs = protoFieldAnnotation.arguments.associate {
                it.name?.asString() to it.value
            }

            val fieldNumber = fieldArgs["id"] as? Int
            if (fieldNumber == null) {
                logger.error("@ProtoField must have an id parameter", param)
                continue
            }

            if (fieldNumber <= 0) {
                logger.error("@ProtoField id must be positive, got $fieldNumber", param)
                continue
            }

            if (fieldNumber in seenFieldNumbers) {
                logger.error("Duplicate field number $fieldNumber in message $messageName", param)
                continue
            }
            seenFieldNumbers.add(fieldNumber)

            val paramType = param.type.resolve()
            val fieldName = toSnakeCase(param.name?.asString() ?: "field_$fieldNumber")
            val deprecated = fieldArgs["deprecated"] as? Boolean ?: false

            val (protoType, repeated, optional) = resolveProtoType(paramType)

            fields.add(
                ProtoFieldInfo(
                    name = fieldName,
                    type = protoType,
                    number = fieldNumber,
                    optional = optional,
                    repeated = repeated,
                    deprecated = deprecated
                )
            )
        }

        if (fields.isEmpty()) {
            logger.warn("@AetherMessage ${classDecl.simpleName.asString()} has no @ProtoField properties", classDecl)
        }

        // Sort fields by number
        fields.sortBy { it.number }

        return ProtoMessageInfo(
            name = messageName,
            fields = fields
        )
    }

    private fun processService(interfaceDecl: KSClassDeclaration): ProtoServiceInfo? {
        val annotation = interfaceDecl.annotations.find {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == AETHER_SERVICE
        }
        val annotationArgs = annotation?.arguments?.associate {
            it.name?.asString() to it.value
        } ?: emptyMap()

        val serviceName = (annotationArgs["name"] as? String)?.takeIf { it.isNotEmpty() }
            ?: interfaceDecl.simpleName.asString()

        val methods = mutableListOf<ProtoMethodInfo>()

        // Process functions with @AetherRpc
        for (function in interfaceDecl.getAllFunctions()) {
            val rpcAnnotation = function.annotations.find {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == AETHER_RPC
            } ?: continue

            val rpcArgs = rpcAnnotation.arguments.associate {
                it.name?.asString() to it.value
            }

            val methodName = (rpcArgs["name"] as? String)?.takeIf { it.isNotEmpty() }
                ?: function.simpleName.asString().replaceFirstChar { it.uppercase() }
            val deprecated = rpcArgs["deprecated"] as? Boolean ?: false

            // Get input type (first parameter)
            val params = function.parameters
            if (params.isEmpty()) {
                logger.error("@AetherRpc method must have at least one parameter", function)
                continue
            }

            val inputParam = params.first()
            val inputType = inputParam.type.resolve()
            val (inputProtoType, clientStreaming) = resolveRpcType(inputType)

            // Get output type (return type)
            val returnType = function.returnType?.resolve()
            if (returnType == null) {
                logger.error("@AetherRpc method must have a return type", function)
                continue
            }
            val (outputProtoType, serverStreaming) = resolveRpcType(returnType)

            methods.add(
                ProtoMethodInfo(
                    name = methodName,
                    inputType = inputProtoType,
                    outputType = outputProtoType,
                    clientStreaming = clientStreaming,
                    serverStreaming = serverStreaming,
                    deprecated = deprecated
                )
            )
        }

        if (methods.isEmpty()) {
            logger.warn(
                "@AetherService ${interfaceDecl.simpleName.asString()} has no @AetherRpc methods",
                interfaceDecl
            )
        }

        return ProtoServiceInfo(
            name = serviceName,
            methods = methods
        )
    }

    private data class ProtoTypeResult(
        val type: String,
        val repeated: Boolean,
        val optional: Boolean
    )

    private fun resolveProtoType(ksType: KSType): ProtoTypeResult {
        val qualifiedName = ksType.declaration.qualifiedName?.asString() ?: ""
        val nullable = ksType.isMarkedNullable

        // Check for List
        if (qualifiedName == "kotlin.collections.List" || qualifiedName == "java.util.List") {
            val typeArg = ksType.arguments.firstOrNull()?.type?.resolve()
            val elementType = if (typeArg != null) {
                mapper.mapType(typeArg.declaration.qualifiedName?.asString() ?: "")
            } else {
                "string"
            }
            return ProtoTypeResult(elementType, repeated = true, optional = false)
        }

        // Check for Map
        if (qualifiedName == "kotlin.collections.Map" || qualifiedName == "java.util.Map") {
            val keyArg = ksType.arguments.getOrNull(0)?.type?.resolve()
            val valueArg = ksType.arguments.getOrNull(1)?.type?.resolve()
            val keyType = if (keyArg != null) {
                mapper.mapType(keyArg.declaration.qualifiedName?.asString() ?: "")
            } else {
                "string"
            }
            val valueType = if (valueArg != null) {
                mapper.mapType(valueArg.declaration.qualifiedName?.asString() ?: "")
            } else {
                "string"
            }
            return ProtoTypeResult("map<$keyType, $valueType>", repeated = false, optional = false)
        }

        val protoType = mapper.mapType(qualifiedName)
        return ProtoTypeResult(protoType, repeated = false, optional = nullable)
    }

    private fun resolveRpcType(ksType: KSType): Pair<String, Boolean> {
        val qualifiedName = ksType.declaration.qualifiedName?.asString() ?: ""

        // Check for Flow (streaming)
        if (qualifiedName == "kotlinx.coroutines.flow.Flow" || qualifiedName.endsWith(".Flow")) {
            val typeArg = ksType.arguments.firstOrNull()?.type?.resolve()
            val elementType = if (typeArg != null) {
                typeArg.declaration.qualifiedName?.asString()?.substringAfterLast('.') ?: "Unknown"
            } else {
                "Unknown"
            }
            return Pair(elementType, true)
        }

        // Non-streaming type
        val typeName = qualifiedName.substringAfterLast('.')
        return Pair(typeName, false)
    }

    private fun toSnakeCase(name: String): String {
        return buildString {
            for ((i, c) in name.withIndex()) {
                if (c.isUpperCase() && i > 0) {
                    append('_')
                }
                append(c.lowercase())
            }
        }
    }

    private fun generateProtoFile(
        packageName: String,
        messages: List<ProtoMessageInfo>,
        services: List<ProtoServiceInfo>
    ) {
        val fileInfo = ProtoFileInfo(
            packageName = packageName,
            messages = messages,
            services = services
        )

        val protoContent = generator.generateFile(fileInfo)
        val fileName = packageName.replace('.', '_').ifEmpty { "generated" }

        // Write to resources
        try {
            val file = codeGenerator.createNewFile(
                Dependencies(false),
                packageName,
                fileName,
                "proto"
            )
            file.bufferedWriter().use { writer ->
                writer.write(protoContent)
            }
            logger.info("Generated proto file: $fileName.proto")
        } catch (e: Exception) {
            logger.error("Failed to write proto file $fileName.proto: ${e.message}")
        }
    }
}

/**
 * Provider for the ProtoProcessor.
 */
class ProtoProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return ProtoProcessor(
            environment.codeGenerator,
            environment.logger,
            environment.options
        )
    }
}
