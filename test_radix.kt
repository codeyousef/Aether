package codes.yousef.aether.web

import codes.yousef.aether.core.Exchange

fun main() {
    val root = RadixNode()
    val handler: suspend (Exchange) -> Unit = { println("Handler called") }
    
    println("Inserting /users/:id")
    root.insert("/users/:id", handler)
    
    println("\nSearching for /users/123")
    val match = root.search("/users/123")
    
    if (match != null) {
        println("Match found!")
        println("Params: ${match.params}")
    } else {
        println("No match found")
    }
}
