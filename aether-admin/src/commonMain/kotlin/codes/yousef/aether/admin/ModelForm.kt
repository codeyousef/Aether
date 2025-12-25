package codes.yousef.aether.admin

import codes.yousef.aether.db.*
import codes.yousef.aether.forms.*
import codes.yousef.aether.ui.ComposableScope

/**
 * A form that is automatically generated from a Model.
 */
class ModelForm<T : BaseEntity<T>>(
    val model: Model<T>,
    val instance: T? = null
) : Form() {
    
    init {
        // Auto-generate fields from model columns
        for (column in model.columns) {
            // Skip primary key if it's auto-increment
            if (column.isPrimaryKey && column.autoIncrement) continue
            
            val fieldName = column.name
            val label = fieldName.replace("_", " ").replaceFirstChar { it.uppercase() }
            val required = !column.nullable
            
            val field: Field<*> = when (column.type) {
                ColumnType.Varchar, is ColumnType.VarcharCustom, ColumnType.Text -> {
                    CharField(label, required)
                }
                ColumnType.Integer, ColumnType.Serial -> {
                    IntegerField(label, required)
                }
                ColumnType.Long, ColumnType.BigSerial -> {
                    LongField(label, required)
                }
                ColumnType.Boolean -> {
                    BooleanField(label, required = false) // Checkboxes are usually optional
                }
                ColumnType.Double -> {
                    DoubleField(label, required)
                }
                else -> CharField(label, required) // Fallback
            }
            
            register(fieldName, field)
        }

        if (instance != null) {
            fillFromInstance()
        }
    }

    private fun fillFromInstance() {
        for (column in model.columns) {
            if (column.isPrimaryKey && column.autoIncrement) continue

            val value = column.getValue(instance!!)
            val field = fields[column.name]

            if (field != null) {
                setFieldValue(field, value)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setFieldValue(field: Field<*>, value: Any?) {
        (field as Field<Any?>).value = value
    }
    
    /**
     * Saves the form data to the database.
     * If instance is present, updates it. Otherwise creates a new one.
     */
    suspend fun save(): T {
        if (!isValid()) throw IllegalStateException("Form is not valid")
        
        val entity = instance ?: model.createInstance()
        
        for (column in model.columns) {
            if (column.isPrimaryKey && column.autoIncrement) continue
            
            val value = get<Any>(column.name)
            
            // We need to set the value on the entity.
            // Since ColumnProperty.setValue takes T, we can use it.
            // But we need to cast the value to the correct type.
            
            // This is tricky because `get` returns the cleaned value, which matches the Field type.
            // We need to ensure Field type matches Column type.
            
            if (value != null) {
                setColumnValue(entity, column, value)
            } else if (column.nullable) {
                setColumnValue(entity, column, null)
            }
        }
        
        entity.save()
        return entity
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun setColumnValue(entity: T, column: ColumnProperty<T, *>, value: Any?) {
        // We need to bypass the type safety of setValue slightly or ensure types match exactly.
        // ColumnProperty<T, V>.setValue(thisRef: T, value: V)
        
        // Since we don't know V at compile time, we have to use unchecked casts.
        // But we know the runtime types should match if our mapping is correct.
        
        (column as ColumnProperty<T, Any?>).setValue(entity, value)
    }
}
