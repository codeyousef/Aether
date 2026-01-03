package codes.yousef.aether.forms

import codes.yousef.aether.ui.ComposableScope
import codes.yousef.aether.ui.div
import codes.yousef.aether.ui.label
import codes.yousef.aether.ui.input

/**
 * Base class for all forms.
 */
abstract class Form {
    protected val fields = mutableMapOf<String, Field<*>>()
    private val errors = mutableMapOf<String, MutableList<String>>()
    private val cleanedData = mutableMapOf<String, Any?>()

    init {
        // Reflection would be nice here to auto-discover fields,
        // but for KMP we might need explicit registration or KSP.
        // For now, we'll rely on manual registration or init block.
    }

    protected fun <T> register(name: String, field: Field<T>): Field<T> {
        field.name = name
        fields[name] = field
        return field
    }

    /**
     * Bind data to the form.
     */
    fun bind(data: Map<String, String>) {
        errors.clear()
        cleanedData.clear()

        for ((name, field) in fields) {
            val rawValue = data[name]
            try {
                val value = field.clean(rawValue)
                cleanedData[name] = value
            } catch (e: ValidationError) {
                addError(name, e.message ?: "Invalid value")
            }
        }
        
        if (errors.isEmpty()) {
            try {
                clean()
            } catch (e: ValidationError) {
                addError("__all__", e.message ?: "Validation error")
            }
        }
    }

    /**
     * Hook for cross-field validation.
     * Override this method to perform validation that depends on multiple fields.
     * Throw ValidationError if validation fails.
     */
    open fun clean() {}

    fun isValid(): Boolean {
        return errors.isEmpty()
    }

    fun addError(fieldName: String, error: String) {
        errors.getOrPut(fieldName) { mutableListOf() }.add(error)
    }

    fun getErrors(): Map<String, List<String>> = errors
    
    /**
     * Get all registered fields.
     */
    fun allFields(): Map<String, Field<*>> = fields
    
    /**
     * Get the error for a specific field.
     */
    fun getFieldError(name: String): String? = errors[name]?.firstOrNull()

    @Suppress("UNCHECKED_CAST")
    fun <T> get(name: String): T? = cleanedData[name] as? T

    /**
     * Render the form as HTML paragraphs.
     */
    fun asP(scope: ComposableScope) {
        with(scope) {
            for ((name, field) in fields) {
                div {
                    val fieldErrors = errors[name]
                    if (fieldErrors != null) {
                        for (error in fieldErrors) {
                            div(attributes = mapOf("class" to "error")) { text(error) }
                        }
                    }
                    label(forId = "id_$name") { text(field.label) }
                    field.render(this)
                }
            }
        }
    }
}

class ValidationError(message: String) : Exception(message)

abstract class Field<T>(
    val label: String,
    val required: Boolean = true,
    var name: String = "",
    var value: T? = null
) {
    abstract fun clean(value: String?): T?
    abstract fun render(scope: ComposableScope)
}

class CharField(
    label: String,
    required: Boolean = true
) : Field<String>(label, required) {
    override fun clean(value: String?): String? {
        if (value.isNullOrBlank()) {
            if (required) throw ValidationError("This field is required.")
            return null
        }
        return value
    }

    override fun render(scope: ComposableScope) {
        val attrs = mutableMapOf("id" to "id_$name")
        value?.let { attrs["value"] = it }
        
        scope.input(
            type = "text",
            name = name,
            attributes = attrs
        )
    }
}

class IntegerField(
    label: String,
    required: Boolean = true
) : Field<Int>(label, required) {
    override fun clean(value: String?): Int? {
        if (value.isNullOrBlank()) {
            if (required) throw ValidationError("This field is required.")
            return null
        }
        return value.toIntOrNull() ?: throw ValidationError("Enter a valid integer.")
    }

    override fun render(scope: ComposableScope) {
        val attrs = mutableMapOf("id" to "id_$name")
        value?.let { attrs["value"] = it.toString() }
        
        scope.input(
            type = "number",
            name = name,
            attributes = attrs
        )
    }
}

class PasswordField(
    label: String,
    required: Boolean = true
) : Field<String>(label, required) {
    override fun clean(value: String?): String? {
        if (value.isNullOrBlank()) {
            if (required) throw ValidationError("This field is required.")
            return null
        }
        return value
    }

    override fun render(scope: ComposableScope) {
        // Passwords usually don't pre-fill value for security
        scope.input(
            type = "password",
            name = name,
            attributes = mapOf("id" to "id_$name")
        )
    }
}

class BooleanField(
    label: String,
    required: Boolean = false // Checkboxes are usually optional (unchecked = false)
) : Field<Boolean>(label, required) {
    override fun clean(value: String?): Boolean? {
        // HTML checkboxes send "on" or nothing.
        // If value is present, it's true.
        return value != null
    }

    override fun render(scope: ComposableScope) {
        val attrs = mutableMapOf("id" to "id_$name")
        if (value == true) {
            attrs["checked"] = "checked"
        }
        
        scope.input(
            type = "checkbox",
            name = name,
            attributes = attrs
        )
    }
}

class LongField(
    label: String,
    required: Boolean = true
) : Field<Long>(label, required) {
    override fun clean(value: String?): Long? {
        if (value.isNullOrBlank()) {
            if (required) throw ValidationError("This field is required.")
            return null
        }
        return value.toLongOrNull() ?: throw ValidationError("Enter a valid integer.")
    }

    override fun render(scope: ComposableScope) {
        val attrs = mutableMapOf("id" to "id_$name")
        value?.let { attrs["value"] = it.toString() }
        
        scope.input(
            type = "number",
            name = name,
            attributes = attrs
        )
    }
}

class DoubleField(
    label: String,
    required: Boolean = true
) : Field<Double>(label, required) {
    override fun clean(value: String?): Double? {
        if (value.isNullOrBlank()) {
            if (required) throw ValidationError("This field is required.")
            return null
        }
        return value.toDoubleOrNull() ?: throw ValidationError("Enter a valid number.")
    }

    override fun render(scope: ComposableScope) {
        val attrs = mutableMapOf("id" to "id_$name", "step" to "any")
        value?.let { attrs["value"] = it.toString() }
        
        scope.input(
            type = "number",
            name = name,
            attributes = attrs
        )
    }
}
