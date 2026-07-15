cmake_minimum_required(VERSION 3.20)
project(aether_identity_wasi_host VERSION 0.6.0.0 LANGUAGES C)

find_package(OpenSSL 3.0 REQUIRED COMPONENTS Crypto)

add_library(aether_identity_wasi_host STATIC
    src/aether_identity_crypto_host.c
)
target_include_directories(aether_identity_wasi_host PUBLIC include)
target_link_libraries(aether_identity_wasi_host PUBLIC OpenSSL::Crypto)
target_compile_features(aether_identity_wasi_host PUBLIC c_std_11)
target_compile_options(aether_identity_wasi_host PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    -fstack-protector-strong
)

include(CTest)
if(BUILD_TESTING)
    add_executable(aether_identity_wasi_host_test
        tests/aether_identity_crypto_host_test.c
    )
    target_link_libraries(aether_identity_wasi_host_test PRIVATE aether_identity_wasi_host)
    target_compile_options(aether_identity_wasi_host_test PRIVATE
        -Wall -Wextra -Wpedantic -Werror
        -fstack-protector-strong
    )
    add_test(NAME aether_identity_wasi_host_self_test COMMAND aether_identity_wasi_host_test)
endif()
