#include "aether_identity_crypto_host.h"

#include <stdio.h>
#include <string.h>

#include <openssl/err.h>
#include <openssl/core_names.h>
#include <openssl/evp.h>
#include <openssl/params.h>
#include <openssl/x509.h>

#define REQUIRE(condition)                                                        \
    do {                                                                          \
        if (!(condition)) {                                                       \
            (void)fprintf(stderr, "requirement failed at line %d: %s\n",        \
                __LINE__, #condition);                                             \
            return 0;                                                             \
        }                                                                         \
    } while (0)

static int test_digest_and_mac_vectors(void) {
    static const uint8_t input[] = {'a', 'b', 'c'};
    static const uint8_t expected_digest[32] = {
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
        0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
        0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad
    };
    static const uint8_t expected_empty_digest[32] = {
        0xe3, 0xb0, 0xc4, 0x42, 0x98, 0xfc, 0x1c, 0x14,
        0x9a, 0xfb, 0xf4, 0xc8, 0x99, 0x6f, 0xb9, 0x24,
        0x27, 0xae, 0x41, 0xe4, 0x64, 0x9b, 0x93, 0x4c,
        0xa4, 0x95, 0x99, 0x1b, 0x78, 0x52, 0xb8, 0x55
    };
    static const uint8_t hmac_key[20] = {
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b, 0x0b,
        0x0b, 0x0b, 0x0b, 0x0b
    };
    static const uint8_t hmac_input[] = {
        'H', 'i', ' ', 'T', 'h', 'e', 'r', 'e'
    };
    static const uint8_t expected_hmac[32] = {
        0xb0, 0x34, 0x4c, 0x61, 0xd8, 0xdb, 0x38, 0x53,
        0x5c, 0xa8, 0xaf, 0xce, 0xaf, 0x0b, 0xf1, 0x2b,
        0x88, 0x1d, 0xc2, 0x00, 0xc9, 0x83, 0x3d, 0xa7,
        0x26, 0xe9, 0x37, 0x6c, 0x2e, 0x32, 0xcf, 0xf7
    };
    static const uint8_t expected_empty_hmac[32] = {
        0xb6, 0x13, 0x67, 0x9a, 0x08, 0x14, 0xd9, 0xec,
        0x77, 0x2f, 0x95, 0xd7, 0x78, 0xc3, 0x5f, 0xc5,
        0xff, 0x16, 0x97, 0xc4, 0x93, 0x71, 0x56, 0x53,
        0xc6, 0xc7, 0x12, 0x14, 0x42, 0x92, 0xc5, 0xad
    };
    uint8_t output[32] = {0U};

    REQUIRE(aether_identity_sha256(input, sizeof(input), output) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(memcmp(output, expected_digest, sizeof(output)) == 0);
    REQUIRE(aether_identity_sha256(NULL, 0U, output) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(memcmp(output, expected_empty_digest, sizeof(output)) == 0);
    REQUIRE(aether_identity_hmac_sha256(
        hmac_key,
        sizeof(hmac_key),
        hmac_input,
        sizeof(hmac_input),
        output
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(memcmp(output, expected_hmac, sizeof(output)) == 0);
    REQUIRE(aether_identity_hmac_sha256(
        NULL,
        0U,
        NULL,
        0U,
        output
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(memcmp(output, expected_empty_hmac, sizeof(output)) == 0);
    return 1;
}

static int test_random_clock_and_comparison(void) {
    uint8_t random_output[32] = {0U};
    uint8_t left[4] = {1U, 2U, 3U, 4U};
    uint8_t right[4] = {1U, 2U, 3U, 4U};
    uint64_t wall_clock = 0U;
    int equal = 0;

    REQUIRE(aether_identity_random(NULL, 0U) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(aether_identity_random(random_output, sizeof(random_output)) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(aether_identity_wall_clock_epoch_milliseconds(&wall_clock) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(wall_clock >= UINT64_C(1577836800000));
    REQUIRE(aether_identity_constant_time_equals(
        left,
        sizeof(left),
        right,
        sizeof(right),
        &equal
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(equal == 1);
    right[3] ^= 1U;
    REQUIRE(aether_identity_constant_time_equals(
        left,
        sizeof(left),
        right,
        sizeof(right),
        &equal
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(equal == 0);
    REQUIRE(aether_identity_constant_time_equals(
        NULL,
        0U,
        NULL,
        0U,
        &equal
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(equal == 1);
    REQUIRE(aether_identity_constant_time_equals(
        left,
        sizeof(left),
        right,
        sizeof(right) - 1U,
        &equal
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(equal == 0);
    return 1;
}

static int test_es256_vector_and_failure(void) {
    static const uint8_t public_key[65] = {
        0x04,
        0x60, 0xfe, 0xd4, 0xba, 0x25, 0x5a, 0x9d, 0x31,
        0xc9, 0x61, 0xeb, 0x74, 0xc6, 0x35, 0x6d, 0x68,
        0xc0, 0x49, 0xb8, 0x92, 0x3b, 0x61, 0xfa, 0x6c,
        0xe6, 0x69, 0x62, 0x2e, 0x60, 0xf2, 0x9f, 0xb6,
        0x79, 0x03, 0xfe, 0x10, 0x08, 0xb8, 0xbc, 0x99,
        0xa4, 0x1a, 0xe9, 0xe9, 0x56, 0x28, 0xbc, 0x64,
        0xf2, 0xf1, 0xb2, 0x0c, 0x2d, 0x7e, 0x9f, 0x51,
        0x77, 0xa3, 0xc2, 0x94, 0xd4, 0x46, 0x22, 0x99
    };
    static const uint8_t input[] = {'s', 'a', 'm', 'p', 'l', 'e'};
    static const uint8_t signature[64] = {
        0xef, 0xd4, 0x8b, 0x2a, 0xac, 0xb6, 0xa8, 0xfd,
        0x11, 0x40, 0xdd, 0x9c, 0xd4, 0x5e, 0x81, 0xd6,
        0x9d, 0x2c, 0x87, 0x7b, 0x56, 0xaa, 0xf9, 0x91,
        0xc3, 0x4d, 0x0e, 0xa8, 0x4e, 0xaf, 0x37, 0x16,
        0xf7, 0xcb, 0x1c, 0x94, 0x2d, 0x65, 0x7c, 0x41,
        0xd4, 0x36, 0xc7, 0xa1, 0xb6, 0xe2, 0x9f, 0x65,
        0xf3, 0xe9, 0x00, 0xdb, 0xb9, 0xaf, 0xf4, 0x06,
        0x4d, 0xc4, 0xab, 0x2f, 0x84, 0x3a, 0xcd, 0xa8
    };
    uint8_t changed_signature[64];
    uint8_t changed_public_key[65];
    int valid = 0;

    REQUIRE(aether_identity_validate_p256_public_key(public_key, &valid) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 1);
    REQUIRE(aether_identity_verify_es256(
        public_key,
        input,
        sizeof(input),
        signature,
        &valid
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 1);
    memcpy(changed_signature, signature, sizeof(changed_signature));
    changed_signature[63] ^= 1U;
    REQUIRE(aether_identity_verify_es256(
        public_key,
        input,
        sizeof(input),
        changed_signature,
        &valid
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 0);
    memcpy(changed_public_key, public_key, sizeof(changed_public_key));
    changed_public_key[0] = 0x02U;
    REQUIRE(aether_identity_validate_p256_public_key(changed_public_key, &valid) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 0);
    REQUIRE(aether_identity_verify_es256(
        changed_public_key,
        input,
        sizeof(input),
        signature,
        &valid
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(valid == 0);
    return 1;
}

static int test_rsa_minimum_and_der_validation(void) {
    static const uint8_t rsa_1024_spki[162] = {
        0x30, 0x81, 0x9f, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86, 0xf7,
        0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x81, 0x8d, 0x00, 0x30, 0x81,
        0x89, 0x02, 0x81, 0x81, 0x00, 0xbd, 0x74, 0x83, 0x02, 0xdb, 0x66, 0x0a,
        0x37, 0x50, 0x65, 0xe7, 0xdc, 0xb7, 0x47, 0x12, 0x96, 0xee, 0x19, 0x37,
        0xa7, 0x41, 0xe3, 0x61, 0xd4, 0x93, 0xd3, 0x01, 0x60, 0xed, 0x01, 0x72,
        0xc4, 0x1f, 0xff, 0x2c, 0x52, 0x39, 0x93, 0x10, 0x8e, 0x40, 0xc1, 0xfa,
        0xb3, 0xa7, 0x17, 0xc6, 0x71, 0xe9, 0xd7, 0x56, 0x3e, 0x52, 0x50, 0x6b,
        0xdd, 0x23, 0xa3, 0xb6, 0x60, 0xf3, 0x87, 0x48, 0x32, 0x7e, 0x7c, 0x53,
        0xcf, 0x43, 0xcd, 0x4c, 0x2f, 0x14, 0x1a, 0x57, 0xf8, 0x95, 0x78, 0x23,
        0xfb, 0x00, 0xa9, 0x14, 0xee, 0x60, 0xdf, 0xeb, 0x8d, 0xde, 0x38, 0x4d,
        0x1e, 0x49, 0x13, 0x9a, 0x23, 0xa6, 0xea, 0x9a, 0xf2, 0x9d, 0x96, 0x32,
        0xbb, 0x22, 0x46, 0xcc, 0x47, 0x5a, 0xcf, 0x2e, 0x13, 0x6f, 0x31, 0x73,
        0x1b, 0x1b, 0xd9, 0x73, 0x2f, 0x86, 0xd9, 0x3e, 0xb4, 0x93, 0x14, 0xa6,
        0x25, 0x02, 0x03, 0x01, 0x00, 0x01
    };
    uint8_t dummy_signature[128] = {0U};
    uint8_t malformed_spki[sizeof(rsa_1024_spki) + 1U];
    static const uint8_t input[] = {'x'};
    int valid = 1;

    REQUIRE(aether_identity_verify_rsa_sha256(
        rsa_1024_spki,
        sizeof(rsa_1024_spki),
        input,
        sizeof(input),
        dummy_signature,
        sizeof(dummy_signature),
        &valid
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(valid == 0);
    memcpy(malformed_spki, rsa_1024_spki, sizeof(rsa_1024_spki));
    malformed_spki[sizeof(rsa_1024_spki)] = 0U;
    valid = 1;
    REQUIRE(aether_identity_verify_rsa_sha256(
        malformed_spki,
        sizeof(malformed_spki),
        input,
        sizeof(input),
        dummy_signature,
        sizeof(dummy_signature),
        &valid
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(valid == 0);
    return 1;
}

static int test_bounds_and_error_queue(void) {
    uint8_t byte = 0U;
    uint8_t output[32] = {0U};
    int equal = 0;

    REQUIRE(aether_identity_random(
        &byte,
        AETHER_IDENTITY_HOST_MAX_RANDOM_LENGTH + 1U
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(aether_identity_sha256(
        &byte,
        AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH + 1U,
        output
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(aether_identity_hmac_sha256(
        &byte,
        AETHER_IDENTITY_HOST_MAX_HMAC_KEY_LENGTH + 1U,
        NULL,
        0U,
        output
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(aether_identity_constant_time_equals(
        NULL,
        1U,
        NULL,
        1U,
        &equal
    ) == AETHER_IDENTITY_HOST_INVALID_INPUT);
    ERR_raise(ERR_LIB_USER, 1);
    REQUIRE(ERR_peek_error() != 0UL);
    REQUIRE(aether_identity_sha256(NULL, 1U, output) ==
        AETHER_IDENTITY_HOST_INVALID_INPUT);
    REQUIRE(ERR_peek_error() == 0UL);
    return 1;
}

static EVP_PKEY *generate_signing_key(const char *algorithm) {
    EVP_PKEY_CTX *context = NULL;
    EVP_PKEY *key = NULL;
    int rsa_bits = 2048;
    char group_name[] = "prime256v1";
    OSSL_PARAM ec_parameters[] = {
        OSSL_PARAM_construct_utf8_string(
            OSSL_PKEY_PARAM_GROUP_NAME,
            group_name,
            0U
        ),
        OSSL_PARAM_construct_end()
    };
    OSSL_PARAM rsa_parameters[] = {
        OSSL_PARAM_construct_int(OSSL_PKEY_PARAM_BITS, &rsa_bits),
        OSSL_PARAM_construct_end()
    };

    context = EVP_PKEY_CTX_new_from_name(NULL, algorithm, NULL);
    if (context == NULL || EVP_PKEY_keygen_init(context) != 1 ||
        EVP_PKEY_CTX_set_params(
            context,
            strcmp(algorithm, "EC") == 0 ? ec_parameters : rsa_parameters
        ) != 1 || EVP_PKEY_generate(context, &key) != 1) {
        EVP_PKEY_free(key);
        key = NULL;
    }
    EVP_PKEY_CTX_free(context);
    return key;
}

static int test_handle_bound_secure_signing(void) {
    static const uint8_t ec_handle[] = "identity-signing-key:v1:es256";
    static const uint8_t rsa_handle[] = "identity-signing-key:v1:rsa";
    static const uint8_t missing_handle[] = "identity-signing-key:missing";
    static const uint8_t input[] = "host-owned signing key operation";
    aether_identity_signing_key_store *store = NULL;
    EVP_PKEY *ec_key = NULL;
    EVP_PKEY *rsa_key = NULL;
    uint8_t output[AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH] = {0U};
    uint8_t ec_public_key[65] = {0U};
    uint8_t rsa_spki[512] = {0U};
    unsigned char *rsa_cursor = rsa_spki;
    size_t output_length = 0U;
    size_t ec_public_key_length = 0U;
    int rsa_spki_length;
    int valid = 0;

    REQUIRE(aether_identity_signing_key_store_create(&store) ==
        AETHER_IDENTITY_HOST_OK);
    REQUIRE(store != NULL);
    ec_key = generate_signing_key("EC");
    rsa_key = generate_signing_key("RSA");
    REQUIRE(ec_key != NULL);
    REQUIRE(rsa_key != NULL);
    REQUIRE(aether_identity_signing_key_store_put(
        store,
        ec_handle,
        sizeof(ec_handle) - 1U,
        ec_key
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(aether_identity_signing_key_store_put(
        store,
        rsa_handle,
        sizeof(rsa_handle) - 1U,
        rsa_key
    ) == AETHER_IDENTITY_HOST_OK);

    REQUIRE(aether_identity_sign(
        store,
        AETHER_IDENTITY_SIGNING_ES256,
        ec_handle,
        sizeof(ec_handle) - 1U,
        input,
        sizeof(input) - 1U,
        output,
        sizeof(output),
        &output_length
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(output_length == 64U);
    REQUIRE(EVP_PKEY_get_octet_string_param(
        ec_key,
        OSSL_PKEY_PARAM_PUB_KEY,
        ec_public_key,
        sizeof(ec_public_key),
        &ec_public_key_length
    ) == 1);
    REQUIRE(ec_public_key_length == sizeof(ec_public_key));
    REQUIRE(aether_identity_verify_es256(
        ec_public_key,
        input,
        sizeof(input) - 1U,
        output,
        &valid
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 1);

    output_length = 99U;
    REQUIRE(aether_identity_sign(
        store,
        AETHER_IDENTITY_SIGNING_RSA_SHA256,
        ec_handle,
        sizeof(ec_handle) - 1U,
        input,
        sizeof(input) - 1U,
        output,
        sizeof(output),
        &output_length
    ) == AETHER_IDENTITY_HOST_UNSUPPORTED);
    REQUIRE(output_length == 0U);
    REQUIRE(aether_identity_sign(
        store,
        AETHER_IDENTITY_SIGNING_ES256,
        missing_handle,
        sizeof(missing_handle) - 1U,
        input,
        sizeof(input) - 1U,
        output,
        sizeof(output),
        &output_length
    ) == AETHER_IDENTITY_HOST_KEY_UNAVAILABLE);

    REQUIRE(aether_identity_sign(
        store,
        AETHER_IDENTITY_SIGNING_RSA_SHA256,
        rsa_handle,
        sizeof(rsa_handle) - 1U,
        input,
        sizeof(input) - 1U,
        output,
        sizeof(output),
        &output_length
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(output_length == 256U);
    rsa_spki_length = i2d_PUBKEY(rsa_key, &rsa_cursor);
    REQUIRE(rsa_spki_length > 0);
    REQUIRE((size_t)rsa_spki_length <= sizeof(rsa_spki));
    REQUIRE(aether_identity_verify_rsa_sha256(
        rsa_spki,
        (size_t)rsa_spki_length,
        input,
        sizeof(input) - 1U,
        output,
        output_length,
        &valid
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(valid == 1);

    REQUIRE(aether_identity_signing_key_store_remove(
        store,
        ec_handle,
        sizeof(ec_handle) - 1U
    ) == AETHER_IDENTITY_HOST_OK);
    REQUIRE(aether_identity_signing_key_store_remove(
        store,
        ec_handle,
        sizeof(ec_handle) - 1U
    ) == AETHER_IDENTITY_HOST_KEY_UNAVAILABLE);
    output_length = 99U;
    REQUIRE(aether_identity_sign(
        store,
        AETHER_IDENTITY_SIGNING_ES256,
        ec_handle,
        sizeof(ec_handle) - 1U,
        input,
        sizeof(input) - 1U,
        output,
        sizeof(output),
        &output_length
    ) == AETHER_IDENTITY_HOST_KEY_UNAVAILABLE);
    REQUIRE(output_length == 0U);

    EVP_PKEY_free(ec_key);
    EVP_PKEY_free(rsa_key);
    aether_identity_signing_key_store_destroy(store);
    return 1;
}

int main(void) {
    if (aether_identity_self_test() != AETHER_IDENTITY_HOST_OK) {
        (void)fprintf(stderr, "host self-test failed\n");
        return 1;
    }
    if (!test_digest_and_mac_vectors() ||
        !test_random_clock_and_comparison() ||
        !test_es256_vector_and_failure() ||
        !test_rsa_minimum_and_der_validation() ||
        !test_bounds_and_error_queue() ||
        !test_handle_bound_secure_signing()) {
        return 1;
    }
    return 0;
}
