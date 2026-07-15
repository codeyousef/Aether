package codes.yousef.aether.auth.firestore

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class FirestoreIndexResourceTest {
    @Test
    fun everyPayloadCollectionDisablesPayloadIndexing() {
        val resource = requireNotNull(
            FirestoreIndexResourceTest::class.java.getResourceAsStream(
                "/aether-identity/firestore.indexes.json"
            )
        ) { "Missing shipped Firestore index resource" }
        val root = resource.bufferedReader().use { reader ->
            Json.parseToJsonElement(reader.readText()).jsonObject
        }
        val payloadOverrides = root.getValue("fieldOverrides").jsonArray
            .map { it.jsonObject }
            .filter { it.getValue("fieldPath").jsonPrimitive.content == "payload" }

        val overriddenCollections = payloadOverrides
            .map { it.getValue("collectionGroup").jsonPrimitive.content }
        assertEquals(
            overriddenCollections.toSet().size,
            overriddenCollections.size,
            "Payload index exemptions must not be duplicated"
        )
        payloadOverrides.forEach { override ->
            assertTrue(
                override.getValue("indexes").jsonArray.isEmpty(),
                "The payload field must have all indexes disabled for " +
                    override.getValue("collectionGroup").jsonPrimitive.content
            )
        }

        assertEquals(
            payloadCollectionsDeclaredByStore(),
            overriddenCollections.toSortedSet(),
            "Every encodeDocument-backed identity collection must disable payload indexing"
        )
    }

    private fun payloadCollectionsDeclaredByStore(): Set<String> =
        FirestoreIdentityStore::class.java.declaredFields
            .asSequence()
            .filter { field ->
                field.name.startsWith("COLLECTION_") &&
                    field.type == String::class.java &&
                    Modifier.isStatic(field.modifiers)
            }
            .map { field ->
                field.isAccessible = true
                field.get(null) as String
            }
            .toSortedSet()
}
