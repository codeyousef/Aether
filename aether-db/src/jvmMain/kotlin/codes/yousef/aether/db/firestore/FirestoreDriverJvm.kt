package codes.yousef.aether.db.firestore

import codes.yousef.aether.db.DatabaseException

/**
 * JVM-specific extension functions for FirestoreDriver.
 */

/**
 * Creates a FirestoreDriver from environment variables.
 * 
 * For API key auth, expects:
 * - FIREBASE_PROJECT_ID or GOOGLE_CLOUD_PROJECT
 * - FIREBASE_API_KEY
 * 
 * For service account auth, expects:
 * - FIREBASE_PROJECT_ID or GOOGLE_CLOUD_PROJECT
 * - GOOGLE_ACCESS_TOKEN (OAuth2 token)
 * 
 * @param databaseId The Firestore database ID (default: "(default)")
 * @return A configured FirestoreDriver
 * @throws DatabaseException if required environment variables are not set
 */
fun FirestoreDriver.Companion.fromEnvironment(databaseId: String = "(default)"): FirestoreDriver {
    val projectId = System.getenv("FIREBASE_PROJECT_ID")
        ?: System.getenv("GOOGLE_CLOUD_PROJECT")
        ?: throw DatabaseException("FIREBASE_PROJECT_ID or GOOGLE_CLOUD_PROJECT environment variable not set")
    
    val accessToken = System.getenv("GOOGLE_ACCESS_TOKEN")
    val apiKey = System.getenv("FIREBASE_API_KEY")
    
    return when {
        accessToken != null -> FirestoreDriver.createWithToken(projectId, accessToken, databaseId)
        apiKey != null -> FirestoreDriver.create(projectId, apiKey, databaseId)
        else -> throw DatabaseException(
            "Either GOOGLE_ACCESS_TOKEN or FIREBASE_API_KEY environment variable must be set"
        )
    }
}
