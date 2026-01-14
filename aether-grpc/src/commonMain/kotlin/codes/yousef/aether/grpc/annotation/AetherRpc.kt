package codes.yousef.aether.grpc.annotation

/**
 * Marks a function as a gRPC RPC method within an @AetherService interface.
 *
 * The method signature determines the RPC type:
 * - `suspend fun method(request: Req): Resp` -> Unary
 * - `suspend fun method(request: Req): Flow<Resp>` -> Server streaming
 * - `suspend fun method(requests: Flow<Req>): Resp` -> Client streaming
 * - `suspend fun method(requests: Flow<Req>): Flow<Resp>` -> Bidirectional streaming
 *
 * Example:
 * ```kotlin
 * @AetherService
 * interface UserService {
 *     @AetherRpc
 *     suspend fun getUser(request: GetUserRequest): User
 *
 *     @AetherRpc(name = "StreamUsers")
 *     suspend fun listUsers(request: ListUsersRequest): Flow<User>
 *
 *     @AetherRpc(deprecated = true)
 *     suspend fun oldMethod(request: OldRequest): OldResponse
 * }
 * ```
 *
 * @param name Optional custom RPC method name. Defaults to the function name with first letter capitalized.
 * @param deprecated Whether the RPC method is deprecated
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AetherRpc(
    val name: String = "",
    val deprecated: Boolean = false
)
