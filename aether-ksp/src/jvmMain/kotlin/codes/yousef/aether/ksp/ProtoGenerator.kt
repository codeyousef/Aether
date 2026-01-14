package codes.yousef.aether.ksp

/**
 * Generates Protocol Buffers syntax from proto model objects.
 *
 * Generates valid proto3 syntax for messages, services, and complete files.
 */
class ProtoGenerator {

    /**
     * Generates a proto message definition.
     *
     * @param message The message info to generate
     * @param indent The indentation level (for nested messages)
     * @return The proto message syntax
     */
    fun generateMessage(message: ProtoMessageInfo, indent: String = ""): String {
        return buildString {
            append("${indent}message ${message.name} {\n")

            // Generate nested enums first
            for (enum in message.nestedEnums) {
                append(generateEnum(enum, "$indent    "))
                append("\n")
            }

            // Generate nested messages
            for (nested in message.nestedMessages) {
                append(generateMessage(nested, "$indent    "))
                append("\n")
            }

            // Generate fields
            for (field in message.fields) {
                append(generateField(field, "$indent    "))
                append("\n")
            }

            append("$indent}")
        }
    }

    /**
     * Generates a proto field definition.
     *
     * @param field The field info to generate
     * @param indent The indentation level
     * @return The proto field syntax
     */
    fun generateField(field: ProtoFieldInfo, indent: String = "    "): String {
        return buildString {
            append(indent)

            // Add optional keyword if needed (proto3 optional)
            if (field.optional) {
                append("optional ")
            }

            // Add repeated keyword if needed
            if (field.repeated) {
                append("repeated ")
            }

            // Type and name
            append("${field.type} ${field.name} = ${field.number}")

            // Add options if present
            if (field.deprecated) {
                append(" [deprecated = true]")
            }

            append(";")
        }
    }

    /**
     * Generates a proto enum definition.
     *
     * @param enum The enum info to generate
     * @param indent The indentation level
     * @return The proto enum syntax
     */
    fun generateEnum(enum: ProtoEnumInfo, indent: String = ""): String {
        return buildString {
            append("${indent}enum ${enum.name} {\n")

            for (value in enum.values) {
                append("$indent    ${value.name} = ${value.number};\n")
            }

            append("$indent}")
        }
    }

    /**
     * Generates a proto service definition.
     *
     * @param service The service info to generate
     * @param indent The indentation level
     * @return The proto service syntax
     */
    fun generateService(service: ProtoServiceInfo, indent: String = ""): String {
        return buildString {
            append("${indent}service ${service.name} {\n")

            for (method in service.methods) {
                append(generateMethod(method, "$indent    "))
                append("\n")
            }

            append("$indent}")
        }
    }

    /**
     * Generates a proto RPC method definition.
     *
     * @param method The method info to generate
     * @param indent The indentation level
     * @return The proto method syntax
     */
    fun generateMethod(method: ProtoMethodInfo, indent: String = "    "): String {
        return buildString {
            append(indent)
            append("rpc ${method.name}(")

            // Client streaming
            if (method.clientStreaming) {
                append("stream ")
            }
            append(method.inputType)
            append(") returns (")

            // Server streaming
            if (method.serverStreaming) {
                append("stream ")
            }
            append(method.outputType)
            append(")")

            // Add options if deprecated
            if (method.deprecated) {
                append(" {\n")
                append("$indent    option deprecated = true;\n")
                append("$indent}")
            } else {
                append(";")
            }
        }
    }

    /**
     * Generates a complete proto file.
     *
     * @param file The file info to generate
     * @return The complete proto file content
     */
    fun generateFile(file: ProtoFileInfo): String {
        return buildString {
            // Syntax declaration
            append("syntax = \"proto3\";\n\n")

            // Package declaration
            if (file.packageName.isNotEmpty()) {
                append("package ${file.packageName};\n\n")
            }

            // Imports
            if (file.imports.isNotEmpty()) {
                for (import in file.imports) {
                    append("import \"$import\";\n")
                }
                append("\n")
            }

            // File options
            for ((option, value) in file.options) {
                append("option $option = \"$value\";\n")
            }
            if (file.options.isNotEmpty()) {
                append("\n")
            }

            // Messages
            for (message in file.messages) {
                append(generateMessage(message))
                append("\n\n")
            }

            // Services
            for (service in file.services) {
                append(generateService(service))
                append("\n")
            }
        }.trimEnd() + "\n"
    }
}
