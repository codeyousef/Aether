#ifndef AETHER_IDENTITY_CRYPTO_HOST_H
#define AETHER_IDENTITY_CRYPTO_HOST_H

#include <stddef.h>
#include <stdint.h>

#include <openssl/types.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum aether_identity_host_status {
    AETHER_IDENTITY_HOST_OK = 0,
    AETHER_IDENTITY_HOST_INVALID_INPUT = 1,
    AETHER_IDENTITY_HOST_KEY_UNAVAILABLE = 2,
    AETHER_IDENTITY_HOST_UNSUPPORTED = 3,
    AETHER_IDENTITY_HOST_SELF_TEST_FAILED = 4,
    AETHER_IDENTITY_HOST_INTERNAL = 5
} aether_identity_host_status;

/*
 * Guest-controlled inputs are rejected before they reach an OpenSSL parser or
 * trigger allocation. These ceilings are part of the stable host ABI.
 */
#define AETHER_IDENTITY_HOST_MAX_RANDOM_LENGTH ((size_t)1048576U)
#define AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ((size_t)16777216U)
#define AETHER_IDENTITY_HOST_MAX_HMAC_KEY_LENGTH ((size_t)65536U)
#define AETHER_IDENTITY_HOST_MAX_SPKI_LENGTH ((size_t)16384U)
#define AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH ((size_t)2048U)
#define AETHER_IDENTITY_HOST_MAX_KEY_HANDLE_LENGTH ((size_t)256U)
#define AETHER_IDENTITY_HOST_MAX_SIGNING_KEYS ((size_t)64U)
#define AETHER_IDENTITY_HOST_MIN_RSA_BITS 2048
#define AETHER_IDENTITY_HOST_MAX_RSA_BITS 16384

typedef enum aether_identity_signing_algorithm {
    AETHER_IDENTITY_SIGNING_ES256 = 1,
    AETHER_IDENTITY_SIGNING_RSA_SHA256 = 2
} aether_identity_signing_algorithm;

/**
 * Host-owned signing-key registry. The guest supplies only an opaque key handle;
 * private key bytes and provider/HSM references never cross the WIT boundary.
 *
 * `put` retains an OpenSSL reference to `key`. This permits provider-managed and
 * hardware-backed EVP_PKEY instances without exporting their private material.
 */
typedef struct aether_identity_signing_key_store aether_identity_signing_key_store;

aether_identity_host_status aether_identity_signing_key_store_create(
    aether_identity_signing_key_store **output
);
void aether_identity_signing_key_store_destroy(
    aether_identity_signing_key_store *store
);
aether_identity_host_status aether_identity_signing_key_store_put(
    aether_identity_signing_key_store *store,
    const uint8_t *key_handle,
    size_t key_handle_length,
    EVP_PKEY *key
);
aether_identity_host_status aether_identity_signing_key_store_remove(
    aether_identity_signing_key_store *store,
    const uint8_t *key_handle,
    size_t key_handle_length
);

/**
 * Signs with the key resolved by `key_handle`. ES256 output is canonical 64-byte
 * raw `r || s`; RSA-SHA256 output is PKCS#1 v1.5 and exactly the RSA modulus size.
 * The output buffer must be at least AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH
 * bytes so a WIT adapter can allocate once and truncate to `output_length`.
 */
aether_identity_host_status aether_identity_sign(
    aether_identity_signing_key_store *store,
    aether_identity_signing_algorithm algorithm,
    const uint8_t *key_handle,
    size_t key_handle_length,
    const uint8_t *signed_data,
    size_t signed_data_length,
    uint8_t *output,
    size_t output_capacity,
    size_t *output_length
);

/** Every operation returns only a stable status; OpenSSL's error queue is never exposed. */
aether_identity_host_status aether_identity_random(uint8_t *output, size_t output_length);
aether_identity_host_status aether_identity_wall_clock_epoch_milliseconds(uint64_t *output);
aether_identity_host_status aether_identity_sha256(
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
);
aether_identity_host_status aether_identity_hmac_sha256(
    const uint8_t *key,
    size_t key_length,
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
);
aether_identity_host_status aether_identity_constant_time_equals(
    const uint8_t *left,
    size_t left_length,
    const uint8_t *right,
    size_t right_length,
    int *equal
);
aether_identity_host_status aether_identity_validate_p256_public_key(
    const uint8_t public_key[65],
    int *valid
);
aether_identity_host_status aether_identity_verify_es256(
    const uint8_t public_key[65],
    const uint8_t *signed_data,
    size_t signed_data_length,
    const uint8_t signature[64],
    int *valid
);
aether_identity_host_status aether_identity_verify_rsa_sha256(
    const uint8_t *spki_public_key,
    size_t spki_public_key_length,
    const uint8_t *signed_data,
    size_t signed_data_length,
    const uint8_t *signature,
    size_t signature_length,
    int *valid
);

/** Runs OpenSSL version, digest, MAC, CSPRNG, EC, and RSA known-operation checks. */
aether_identity_host_status aether_identity_self_test(void);

#ifdef __cplusplus
}
#endif

#endif
