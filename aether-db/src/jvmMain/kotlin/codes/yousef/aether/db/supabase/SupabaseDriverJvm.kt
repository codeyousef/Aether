package codes.yousef.aether.db.supabase

import codes.yousef.aether.db.DatabaseException

/**
 * JVM-specific extension functions for SupabaseDriver.
 */

/**
 * Creates a SupabaseDriver from environment variables.
 * Expects SUPABASE_URL and SUPABASE_KEY (or SUPABASE_ANON_KEY) to be set.
 * 
 * @param schema The PostgreSQL schema (default: "public")
 * @return A configured SupabaseDriver
 * @throws DatabaseException if required environment variables are not set
 */
fun SupabaseDriver.Companion.fromEnvironment(schema: String = "public"): SupabaseDriver {
    val url = System.getenv("SUPABASE_URL")
        ?: throw DatabaseException("SUPABASE_URL environment variable not set")
    val key = System.getenv("SUPABASE_KEY")
        ?: System.getenv("SUPABASE_ANON_KEY")
        ?: throw DatabaseException("SUPABASE_KEY or SUPABASE_ANON_KEY environment variable not set")
    return SupabaseDriver.create(url, key, schema)
}
