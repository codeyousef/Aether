package codes.yousef.aether.grpc.annotation

/**
 * Marks an interface as a gRPC service.
 * The KSP processor will generate a corresponding .proto service definition
 * and service stubs.
 *
 * Example:
 * ```kotlin
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
 * service UserService {
 *     rpc GetUser(GetUserRequest) returns (User);
 *     rpc ListUsers(ListUsersRequest) returns (stream User);
 * }
 * ```
 *
 * @param name Optional custom service name. Defaults to the interface name.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class AetherService(
    val name: String = ""
)
