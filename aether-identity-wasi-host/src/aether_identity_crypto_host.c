#include "aether_identity_crypto_host.h"

#include <limits.h>
#include <string.h>
#include <time.h>

#include <openssl/bn.h>
#include <openssl/core_names.h>
#include <openssl/crypto.h>
#include <openssl/ec.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/macros.h>
#include <openssl/params.h>
#include <openssl/rand.h>
#include <openssl/rsa.h>
#include <openssl/x509.h>

#define AETHER_SHA256_LENGTH ((size_t)32U)
#define AETHER_P256_PUBLIC_KEY_LENGTH ((size_t)65U)
#define AETHER_ES256_RAW_SIGNATURE_LENGTH ((size_t)64U)
#define AETHER_ES256_DER_SIGNATURE_MAX_LENGTH ((size_t)72U)
#define AETHER_CLOCK_MINIMUM_EPOCH_MILLISECONDS UINT64_C(1577836800000)

typedef struct aether_identity_signing_key_entry {
    uint8_t handle[AETHER_IDENTITY_HOST_MAX_KEY_HANDLE_LENGTH];
    size_t handle_length;
    EVP_PKEY *key;
} aether_identity_signing_key_entry;

struct aether_identity_signing_key_store {
    CRYPTO_RWLOCK *lock;
    size_t count;
    aether_identity_signing_key_entry entries[AETHER_IDENTITY_HOST_MAX_SIGNING_KEYS];
};

static aether_identity_host_status aether_finish(aether_identity_host_status status) {
    ERR_clear_error();
    return status;
}

static int aether_optional_buffer_is_valid(const uint8_t *buffer, size_t length) {
    return length == 0U || buffer != NULL;
}

static int aether_key_handle_is_valid(const uint8_t *handle, size_t length) {
    return handle != NULL && length > 0U &&
        length <= AETHER_IDENTITY_HOST_MAX_KEY_HANDLE_LENGTH;
}

static int aether_key_handle_matches(
    const aether_identity_signing_key_entry *entry,
    const uint8_t *handle,
    size_t handle_length
) {
    return entry->handle_length == handle_length &&
        CRYPTO_memcmp(entry->handle, handle, handle_length) == 0;
}

static aether_identity_host_status aether_validate_signing_key(EVP_PKEY *key) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    EVP_PKEY_CTX *check_context = NULL;
    char group_name[80] = {0};
    size_t group_name_length = 0U;
    int key_bits;
    int check_result;

    if (key == NULL) {
        return AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    if (EVP_PKEY_is_a(key, "EC") == 1) {
        if (EVP_PKEY_get_utf8_string_param(
                key,
                OSSL_PKEY_PARAM_GROUP_NAME,
                group_name,
                sizeof(group_name),
                &group_name_length
            ) != 1 || group_name_length == 0U ||
            (strcmp(group_name, "prime256v1") != 0 &&
                strcmp(group_name, "P-256") != 0)) {
            return AETHER_IDENTITY_HOST_UNSUPPORTED;
        }
    } else if (EVP_PKEY_is_a(key, "RSA") == 1) {
        key_bits = EVP_PKEY_get_bits(key);
        if (key_bits < AETHER_IDENTITY_HOST_MIN_RSA_BITS ||
            key_bits > AETHER_IDENTITY_HOST_MAX_RSA_BITS) {
            return AETHER_IDENTITY_HOST_INVALID_INPUT;
        }
    } else {
        return AETHER_IDENTITY_HOST_UNSUPPORTED;
    }

    check_context = EVP_PKEY_CTX_new_from_pkey(NULL, key, NULL);
    if (check_context == NULL) {
        return AETHER_IDENTITY_HOST_INTERNAL;
    }
    check_result = EVP_PKEY_private_check(check_context);
    if (check_result == 1) {
        status = AETHER_IDENTITY_HOST_OK;
    } else if (check_result == 0) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    EVP_PKEY_CTX_free(check_context);
    return status;
}

static aether_identity_host_status aether_sign_es256(
    EVP_PKEY *key,
    const uint8_t *signed_data,
    size_t signed_data_length,
    uint8_t *output,
    size_t output_capacity,
    size_t *output_length
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    EVP_MD *digest = NULL;
    EVP_MD_CTX *context = NULL;
    ECDSA_SIG *parsed_signature = NULL;
    const BIGNUM *r = NULL;
    const BIGNUM *s = NULL;
    const unsigned char *cursor;
    uint8_t der_signature[AETHER_ES256_DER_SIGNATURE_MAX_LENGTH] = {0U};
    size_t der_signature_length = sizeof(der_signature);

    if (EVP_PKEY_is_a(key, "EC") != 1) {
        return AETHER_IDENTITY_HOST_UNSUPPORTED;
    }
    if (output_capacity < AETHER_ES256_RAW_SIGNATURE_LENGTH) {
        return AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    digest = EVP_MD_fetch(NULL, "SHA256", NULL);
    context = EVP_MD_CTX_new();
    if (digest == NULL || context == NULL) {
        goto cleanup;
    }
    if (EVP_DigestSignInit(context, NULL, digest, NULL, key) != 1 ||
        EVP_DigestSign(
            context,
            der_signature,
            &der_signature_length,
            signed_data,
            signed_data_length
        ) != 1 || der_signature_length == 0U ||
        der_signature_length > sizeof(der_signature)) {
        goto cleanup;
    }
    cursor = der_signature;
    parsed_signature = d2i_ECDSA_SIG(
        NULL,
        &cursor,
        (long)der_signature_length
    );
    if (parsed_signature == NULL ||
        cursor != der_signature + der_signature_length) {
        goto cleanup;
    }
    ECDSA_SIG_get0(parsed_signature, &r, &s);
    if (r == NULL || s == NULL || BN_is_zero(r) || BN_is_negative(r) ||
        BN_is_zero(s) || BN_is_negative(s) ||
        BN_num_bits(r) > 256 || BN_num_bits(s) > 256 ||
        BN_bn2binpad(r, output, 32) != 32 ||
        BN_bn2binpad(s, output + 32, 32) != 32) {
        goto cleanup;
    }
    *output_length = AETHER_ES256_RAW_SIGNATURE_LENGTH;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    OPENSSL_cleanse(der_signature, sizeof(der_signature));
    ECDSA_SIG_free(parsed_signature);
    EVP_MD_CTX_free(context);
    EVP_MD_free(digest);
    return status;
}

static aether_identity_host_status aether_sign_rsa_sha256(
    EVP_PKEY *key,
    const uint8_t *signed_data,
    size_t signed_data_length,
    uint8_t *output,
    size_t output_capacity,
    size_t *output_length
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    EVP_MD *digest = NULL;
    EVP_MD_CTX *context = NULL;
    EVP_PKEY_CTX *key_context = NULL;
    int key_bits;
    int key_size;
    size_t signature_length;

    if (EVP_PKEY_is_a(key, "RSA") != 1) {
        return AETHER_IDENTITY_HOST_UNSUPPORTED;
    }
    key_bits = EVP_PKEY_get_bits(key);
    key_size = EVP_PKEY_get_size(key);
    if (key_bits < AETHER_IDENTITY_HOST_MIN_RSA_BITS ||
        key_bits > AETHER_IDENTITY_HOST_MAX_RSA_BITS || key_size <= 0 ||
        (size_t)key_size > AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH) {
        return AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    if (output_capacity < (size_t)key_size) {
        return AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    digest = EVP_MD_fetch(NULL, "SHA256", NULL);
    context = EVP_MD_CTX_new();
    if (digest == NULL || context == NULL) {
        goto cleanup;
    }
    if (EVP_DigestSignInit(context, &key_context, digest, NULL, key) != 1 ||
        key_context == NULL ||
        EVP_PKEY_CTX_set_rsa_padding(key_context, RSA_PKCS1_PADDING) != 1) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    signature_length = output_capacity;
    if (EVP_DigestSign(
            context,
            output,
            &signature_length,
            signed_data,
            signed_data_length
        ) != 1 || signature_length != (size_t)key_size) {
        goto cleanup;
    }
    *output_length = signature_length;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    EVP_MD_CTX_free(context);
    EVP_MD_free(digest);
    return status;
}

static aether_identity_host_status aether_sha256_impl(
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    EVP_MD *digest = NULL;
    EVP_MD_CTX *context = NULL;
    unsigned int output_length = 0U;

    digest = EVP_MD_fetch(NULL, "SHA256", NULL);
    if (digest == NULL) {
        status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
        goto cleanup;
    }
    context = EVP_MD_CTX_new();
    if (context == NULL) {
        goto cleanup;
    }
    if (EVP_DigestInit_ex2(context, digest, NULL) != 1 ||
        (input_length > 0U && EVP_DigestUpdate(context, input, input_length) != 1) ||
        EVP_DigestFinal_ex(context, output, &output_length) != 1 ||
        output_length != AETHER_SHA256_LENGTH) {
        goto cleanup;
    }
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    EVP_MD_CTX_free(context);
    EVP_MD_free(digest);
    if (status != AETHER_IDENTITY_HOST_OK) {
        OPENSSL_cleanse(output, AETHER_SHA256_LENGTH);
    }
    return status;
}

static aether_identity_host_status aether_hmac_sha256_impl(
    const uint8_t *key,
    size_t key_length,
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
) {
    static const char digest_name[] = "SHA256";
    static const uint8_t empty_key = 0U;
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    const uint8_t *effective_key = key_length == 0U ? &empty_key : key;
    EVP_MAC *mac = NULL;
    EVP_MAC_CTX *context = NULL;
    size_t output_length = 0U;
    OSSL_PARAM parameters[] = {
        OSSL_PARAM_construct_utf8_string(
            OSSL_MAC_PARAM_DIGEST,
            (char *)digest_name,
            0U
        ),
        OSSL_PARAM_construct_end()
    };

    mac = EVP_MAC_fetch(NULL, "HMAC", NULL);
    if (mac == NULL) {
        status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
        goto cleanup;
    }
    context = EVP_MAC_CTX_new(mac);
    if (context == NULL) {
        goto cleanup;
    }
    if (EVP_MAC_init(context, effective_key, key_length, parameters) != 1 ||
        (input_length > 0U && EVP_MAC_update(context, input, input_length) != 1) ||
        EVP_MAC_final(context, output, &output_length, AETHER_SHA256_LENGTH) != 1 ||
        output_length != AETHER_SHA256_LENGTH) {
        goto cleanup;
    }
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    EVP_MAC_CTX_free(context);
    EVP_MAC_free(mac);
    if (status != AETHER_IDENTITY_HOST_OK) {
        OPENSSL_cleanse(output, AETHER_SHA256_LENGTH);
    }
    return status;
}

static aether_identity_host_status aether_p256_public_key(
    const uint8_t public_key[65],
    EVP_PKEY **output
) {
    static const char group_name[] = "prime256v1";
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    EVP_PKEY_CTX *from_data_context = NULL;
    EVP_PKEY_CTX *check_context = NULL;
    EVP_PKEY *key = NULL;
    int check_result;
    OSSL_PARAM parameters[] = {
        OSSL_PARAM_construct_utf8_string(
            OSSL_PKEY_PARAM_GROUP_NAME,
            (char *)group_name,
            0U
        ),
        OSSL_PARAM_construct_octet_string(
            OSSL_PKEY_PARAM_PUB_KEY,
            (void *)public_key,
            AETHER_P256_PUBLIC_KEY_LENGTH
        ),
        OSSL_PARAM_construct_end()
    };

    if (public_key[0] != 0x04U) {
        return AETHER_IDENTITY_HOST_INVALID_INPUT;
    }
    from_data_context = EVP_PKEY_CTX_new_from_name(NULL, "EC", NULL);
    if (from_data_context == NULL) {
        return AETHER_IDENTITY_HOST_UNSUPPORTED;
    }
    if (EVP_PKEY_fromdata_init(from_data_context) != 1) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    if (EVP_PKEY_fromdata(
            from_data_context,
            &key,
            EVP_PKEY_PUBLIC_KEY,
            parameters
        ) != 1 || key == NULL) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    check_context = EVP_PKEY_CTX_new_from_pkey(NULL, key, NULL);
    if (check_context == NULL) {
        goto cleanup;
    }
    check_result = EVP_PKEY_public_check(check_context);
    if (check_result == 0) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    if (check_result != 1) {
        goto cleanup;
    }
    *output = key;
    key = NULL;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    EVP_PKEY_CTX_free(check_context);
    EVP_PKEY_CTX_free(from_data_context);
    EVP_PKEY_free(key);
    return status;
}

static aether_identity_host_status aether_es256_signature_to_der(
    const uint8_t signature[64],
    uint8_t output[72],
    size_t *output_length
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    ECDSA_SIG *ecdsa_signature = NULL;
    BIGNUM *r = NULL;
    BIGNUM *s = NULL;
    unsigned char *cursor = output;
    int encoded_length;

    ecdsa_signature = ECDSA_SIG_new();
    r = BN_bin2bn(signature, 32, NULL);
    s = BN_bin2bn(signature + 32, 32, NULL);
    if (ecdsa_signature == NULL || r == NULL || s == NULL) {
        goto cleanup;
    }
    if (ECDSA_SIG_set0(ecdsa_signature, r, s) != 1) {
        goto cleanup;
    }
    r = NULL;
    s = NULL;
    encoded_length = i2d_ECDSA_SIG(ecdsa_signature, NULL);
    if (encoded_length <= 0 ||
        (size_t)encoded_length > AETHER_ES256_DER_SIGNATURE_MAX_LENGTH) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    if (i2d_ECDSA_SIG(ecdsa_signature, &cursor) != encoded_length) {
        goto cleanup;
    }
    *output_length = (size_t)encoded_length;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    BN_clear_free(r);
    BN_clear_free(s);
    ECDSA_SIG_free(ecdsa_signature);
    return status;
}

aether_identity_host_status aether_identity_signing_key_store_create(
    aether_identity_signing_key_store **output
) {
    aether_identity_signing_key_store *store;

    ERR_clear_error();
    if (output == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *output = NULL;
    store = OPENSSL_zalloc(sizeof(*store));
    if (store == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    store->lock = CRYPTO_THREAD_lock_new();
    if (store->lock == NULL) {
        OPENSSL_clear_free(store, sizeof(*store));
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    *output = store;
    return aether_finish(AETHER_IDENTITY_HOST_OK);
}

void aether_identity_signing_key_store_destroy(
    aether_identity_signing_key_store *store
) {
    size_t index;

    ERR_clear_error();
    if (store == NULL) {
        return;
    }
    for (index = 0U; index < store->count; index++) {
        EVP_PKEY_free(store->entries[index].key);
        store->entries[index].key = NULL;
        OPENSSL_cleanse(
            store->entries[index].handle,
            sizeof(store->entries[index].handle)
        );
        store->entries[index].handle_length = 0U;
    }
    CRYPTO_THREAD_lock_free(store->lock);
    store->lock = NULL;
    OPENSSL_clear_free(store, sizeof(*store));
    ERR_clear_error();
}

aether_identity_host_status aether_identity_signing_key_store_put(
    aether_identity_signing_key_store *store,
    const uint8_t *key_handle,
    size_t key_handle_length,
    EVP_PKEY *key
) {
    aether_identity_host_status status;
    EVP_PKEY *retained_key = NULL;
    EVP_PKEY *replaced_key = NULL;
    size_t index;

    ERR_clear_error();
    if (store == NULL || !aether_key_handle_is_valid(key_handle, key_handle_length) ||
        key == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    status = aether_validate_signing_key(key);
    if (status != AETHER_IDENTITY_HOST_OK) {
        return aether_finish(status);
    }
    if (EVP_PKEY_up_ref(key) != 1) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    retained_key = key;
    if (CRYPTO_THREAD_write_lock(store->lock) != 1) {
        EVP_PKEY_free(retained_key);
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    for (index = 0U; index < store->count; index++) {
        if (aether_key_handle_matches(
                &store->entries[index],
                key_handle,
                key_handle_length
            )) {
            replaced_key = store->entries[index].key;
            store->entries[index].key = retained_key;
            retained_key = NULL;
            break;
        }
    }
    if (index == store->count) {
        if (store->count >= AETHER_IDENTITY_HOST_MAX_SIGNING_KEYS) {
            status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
        } else {
            aether_identity_signing_key_entry *entry =
                &store->entries[store->count];
            memcpy(entry->handle, key_handle, key_handle_length);
            entry->handle_length = key_handle_length;
            entry->key = retained_key;
            retained_key = NULL;
            store->count++;
            status = AETHER_IDENTITY_HOST_OK;
        }
    } else {
        status = AETHER_IDENTITY_HOST_OK;
    }
    if (CRYPTO_THREAD_unlock(store->lock) != 1) {
        status = AETHER_IDENTITY_HOST_INTERNAL;
    }
    EVP_PKEY_free(replaced_key);
    EVP_PKEY_free(retained_key);
    return aether_finish(status);
}

aether_identity_host_status aether_identity_signing_key_store_remove(
    aether_identity_signing_key_store *store,
    const uint8_t *key_handle,
    size_t key_handle_length
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
    EVP_PKEY *removed_key = NULL;
    size_t index;
    size_t last_index;

    ERR_clear_error();
    if (store == NULL || !aether_key_handle_is_valid(key_handle, key_handle_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    if (CRYPTO_THREAD_write_lock(store->lock) != 1) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    for (index = 0U; index < store->count; index++) {
        if (aether_key_handle_matches(
                &store->entries[index],
                key_handle,
                key_handle_length
            )) {
            last_index = store->count - 1U;
            removed_key = store->entries[index].key;
            OPENSSL_cleanse(&store->entries[index], sizeof(store->entries[index]));
            if (index != last_index) {
                store->entries[index] = store->entries[last_index];
                OPENSSL_cleanse(
                    &store->entries[last_index],
                    sizeof(store->entries[last_index])
                );
            }
            store->count--;
            status = AETHER_IDENTITY_HOST_OK;
            break;
        }
    }
    if (CRYPTO_THREAD_unlock(store->lock) != 1) {
        status = AETHER_IDENTITY_HOST_INTERNAL;
    }
    EVP_PKEY_free(removed_key);
    return aether_finish(status);
}

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
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
    EVP_PKEY *key = NULL;
    size_t index;

    ERR_clear_error();
    if (output_length == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *output_length = 0U;
    if (store == NULL || !aether_key_handle_is_valid(key_handle, key_handle_length) ||
        signed_data_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        !aether_optional_buffer_is_valid(signed_data, signed_data_length) ||
        output == NULL || output_capacity == 0U ||
        output_capacity > AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH ||
        (algorithm != AETHER_IDENTITY_SIGNING_ES256 &&
            algorithm != AETHER_IDENTITY_SIGNING_RSA_SHA256)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    if (CRYPTO_THREAD_read_lock(store->lock) != 1) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    for (index = 0U; index < store->count; index++) {
        if (aether_key_handle_matches(
                &store->entries[index],
                key_handle,
                key_handle_length
            )) {
            if (EVP_PKEY_up_ref(store->entries[index].key) == 1) {
                key = store->entries[index].key;
            } else {
                status = AETHER_IDENTITY_HOST_INTERNAL;
            }
            break;
        }
    }
    if (CRYPTO_THREAD_unlock(store->lock) != 1) {
        EVP_PKEY_free(key);
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    if (key == NULL) {
        OPENSSL_cleanse(output, output_capacity);
        return aether_finish(status);
    }
    if (algorithm == AETHER_IDENTITY_SIGNING_ES256) {
        status = aether_sign_es256(
            key,
            signed_data,
            signed_data_length,
            output,
            output_capacity,
            output_length
        );
    } else {
        status = aether_sign_rsa_sha256(
            key,
            signed_data,
            signed_data_length,
            output,
            output_capacity,
            output_length
        );
    }
    EVP_PKEY_free(key);
    if (status != AETHER_IDENTITY_HOST_OK) {
        OPENSSL_cleanse(output, output_capacity);
        *output_length = 0U;
    }
    return aether_finish(status);
}

aether_identity_host_status aether_identity_random(uint8_t *output, size_t output_length) {
    ERR_clear_error();
    if (output_length > AETHER_IDENTITY_HOST_MAX_RANDOM_LENGTH ||
        (output_length > 0U && output == NULL)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    if (output_length == 0U) {
        return aether_finish(AETHER_IDENTITY_HOST_OK);
    }
    if (RAND_status() != 1 ||
        RAND_priv_bytes_ex(NULL, output, output_length, 256U) != 1) {
        OPENSSL_cleanse(output, output_length);
        return aether_finish(AETHER_IDENTITY_HOST_KEY_UNAVAILABLE);
    }
    return aether_finish(AETHER_IDENTITY_HOST_OK);
}

aether_identity_host_status aether_identity_wall_clock_epoch_milliseconds(uint64_t *output) {
    struct timespec timestamp;
    uint64_t seconds;
    uint64_t milliseconds;

    ERR_clear_error();
    if (output == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *output = 0U;
    if (timespec_get(&timestamp, TIME_UTC) != TIME_UTC ||
        timestamp.tv_sec < (time_t)0 || timestamp.tv_nsec < 0L ||
        timestamp.tv_nsec >= 1000000000L) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    seconds = (uint64_t)timestamp.tv_sec;
    milliseconds = (uint64_t)timestamp.tv_nsec / UINT64_C(1000000);
    if (seconds > (UINT64_MAX - milliseconds) / UINT64_C(1000)) {
        return aether_finish(AETHER_IDENTITY_HOST_INTERNAL);
    }
    *output = seconds * UINT64_C(1000) + milliseconds;
    return aether_finish(AETHER_IDENTITY_HOST_OK);
}

aether_identity_host_status aether_identity_sha256(
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
) {
    aether_identity_host_status status;

    ERR_clear_error();
    if (output == NULL || input_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        !aether_optional_buffer_is_valid(input, input_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    status = aether_sha256_impl(input, input_length, output);
    return aether_finish(status);
}

aether_identity_host_status aether_identity_hmac_sha256(
    const uint8_t *key,
    size_t key_length,
    const uint8_t *input,
    size_t input_length,
    uint8_t output[32]
) {
    aether_identity_host_status status;

    ERR_clear_error();
    if (output == NULL || key_length > AETHER_IDENTITY_HOST_MAX_HMAC_KEY_LENGTH ||
        input_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        !aether_optional_buffer_is_valid(key, key_length) ||
        !aether_optional_buffer_is_valid(input, input_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    status = aether_hmac_sha256_impl(key, key_length, input, input_length, output);
    return aether_finish(status);
}

aether_identity_host_status aether_identity_constant_time_equals(
    const uint8_t *left,
    size_t left_length,
    const uint8_t *right,
    size_t right_length,
    int *equal
) {
    ERR_clear_error();
    if (equal == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *equal = 0;
    if (left_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        right_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        !aether_optional_buffer_is_valid(left, left_length) ||
        !aether_optional_buffer_is_valid(right, right_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    if (left_length != right_length) {
        return aether_finish(AETHER_IDENTITY_HOST_OK);
    }
    if (left_length == 0U) {
        *equal = 1;
        return aether_finish(AETHER_IDENTITY_HOST_OK);
    }
    *equal = CRYPTO_memcmp(left, right, left_length) == 0 ? 1 : 0;
    return aether_finish(AETHER_IDENTITY_HOST_OK);
}

aether_identity_host_status aether_identity_validate_p256_public_key(
    const uint8_t public_key[65],
    int *valid
) {
    aether_identity_host_status status;
    EVP_PKEY *key = NULL;

    ERR_clear_error();
    if (public_key == NULL || valid == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *valid = 0;
    status = aether_p256_public_key(public_key, &key);
    if (status == AETHER_IDENTITY_HOST_OK) {
        *valid = 1;
    } else if (status == AETHER_IDENTITY_HOST_INVALID_INPUT) {
        status = AETHER_IDENTITY_HOST_OK;
    }
    EVP_PKEY_free(key);
    return aether_finish(status);
}

aether_identity_host_status aether_identity_verify_es256(
    const uint8_t public_key[65],
    const uint8_t *signed_data,
    size_t signed_data_length,
    const uint8_t signature[64],
    int *valid
) {
    aether_identity_host_status status;
    EVP_PKEY *key = NULL;
    EVP_MD *digest = NULL;
    EVP_MD_CTX *verify_context = NULL;
    uint8_t der_signature[72] = {0U};
    size_t der_signature_length = 0U;
    int verify_result;

    ERR_clear_error();
    if (valid == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *valid = 0;
    if (public_key == NULL || signature == NULL ||
        signed_data_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        !aether_optional_buffer_is_valid(signed_data, signed_data_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    status = aether_p256_public_key(public_key, &key);
    if (status != AETHER_IDENTITY_HOST_OK) {
        goto cleanup;
    }
    status = aether_es256_signature_to_der(
        signature,
        der_signature,
        &der_signature_length
    );
    if (status != AETHER_IDENTITY_HOST_OK) {
        goto cleanup;
    }
    digest = EVP_MD_fetch(NULL, "SHA256", NULL);
    if (digest == NULL) {
        status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
        goto cleanup;
    }
    verify_context = EVP_MD_CTX_new();
    if (verify_context == NULL) {
        status = AETHER_IDENTITY_HOST_INTERNAL;
        goto cleanup;
    }
    if (EVP_DigestVerifyInit(verify_context, NULL, digest, NULL, key) != 1) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    verify_result = EVP_DigestVerify(
        verify_context,
        der_signature,
        der_signature_length,
        signed_data,
        signed_data_length
    );
    if (verify_result < 0) {
        status = AETHER_IDENTITY_HOST_INTERNAL;
        goto cleanup;
    }
    *valid = verify_result == 1 ? 1 : 0;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    OPENSSL_cleanse(der_signature, sizeof(der_signature));
    EVP_MD_CTX_free(verify_context);
    EVP_MD_free(digest);
    EVP_PKEY_free(key);
    return aether_finish(status);
}

aether_identity_host_status aether_identity_verify_rsa_sha256(
    const uint8_t *spki_public_key,
    size_t spki_public_key_length,
    const uint8_t *signed_data,
    size_t signed_data_length,
    const uint8_t *signature,
    size_t signature_length,
    int *valid
) {
    aether_identity_host_status status = AETHER_IDENTITY_HOST_INTERNAL;
    const unsigned char *cursor;
    EVP_PKEY *key = NULL;
    EVP_MD *digest = NULL;
    EVP_MD_CTX *verify_context = NULL;
    EVP_PKEY_CTX *key_context = NULL;
    EVP_PKEY_CTX *check_context = NULL;
    int key_bits;
    int key_size;
    int check_result;
    int verify_result;

    ERR_clear_error();
    if (valid == NULL) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    *valid = 0;
    if (spki_public_key == NULL || spki_public_key_length == 0U ||
        spki_public_key_length > AETHER_IDENTITY_HOST_MAX_SPKI_LENGTH ||
        signed_data_length > AETHER_IDENTITY_HOST_MAX_INPUT_LENGTH ||
        signature == NULL || signature_length == 0U ||
        signature_length > AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH ||
        !aether_optional_buffer_is_valid(signed_data, signed_data_length)) {
        return aether_finish(AETHER_IDENTITY_HOST_INVALID_INPUT);
    }
    cursor = spki_public_key;
    key = d2i_PUBKEY(NULL, &cursor, (long)spki_public_key_length);
    if (key == NULL || cursor != spki_public_key + spki_public_key_length) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    if (EVP_PKEY_is_a(key, "RSA") != 1) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    check_context = EVP_PKEY_CTX_new_from_pkey(NULL, key, NULL);
    if (check_context == NULL) {
        goto cleanup;
    }
    check_result = EVP_PKEY_public_check(check_context);
    if (check_result == 0) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    if (check_result != 1) {
        goto cleanup;
    }
    key_bits = EVP_PKEY_get_bits(key);
    if (key_bits < AETHER_IDENTITY_HOST_MIN_RSA_BITS ||
        key_bits > AETHER_IDENTITY_HOST_MAX_RSA_BITS) {
        status = AETHER_IDENTITY_HOST_INVALID_INPUT;
        goto cleanup;
    }
    key_size = EVP_PKEY_get_size(key);
    if (key_size <= 0) {
        goto cleanup;
    }
    if (signature_length != (size_t)key_size) {
        status = AETHER_IDENTITY_HOST_OK;
        goto cleanup;
    }
    digest = EVP_MD_fetch(NULL, "SHA256", NULL);
    if (digest == NULL) {
        status = AETHER_IDENTITY_HOST_KEY_UNAVAILABLE;
        goto cleanup;
    }
    verify_context = EVP_MD_CTX_new();
    if (verify_context == NULL) {
        goto cleanup;
    }
    if (EVP_DigestVerifyInit(
            verify_context,
            &key_context,
            digest,
            NULL,
            key
        ) != 1 || key_context == NULL) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    if (EVP_PKEY_CTX_set_rsa_padding(key_context, RSA_PKCS1_PADDING) != 1) {
        status = AETHER_IDENTITY_HOST_UNSUPPORTED;
        goto cleanup;
    }
    verify_result = EVP_DigestVerify(
        verify_context,
        signature,
        signature_length,
        signed_data,
        signed_data_length
    );
    if (verify_result < 0) {
        status = AETHER_IDENTITY_HOST_INTERNAL;
        goto cleanup;
    }
    *valid = verify_result == 1 ? 1 : 0;
    status = AETHER_IDENTITY_HOST_OK;

cleanup:
    EVP_PKEY_CTX_free(check_context);
    EVP_MD_CTX_free(verify_context);
    EVP_MD_free(digest);
    EVP_PKEY_free(key);
    return aether_finish(status);
}

static EVP_PKEY *aether_self_test_generate_key(const char *algorithm) {
    EVP_PKEY_CTX *context = NULL;
    EVP_PKEY *key = NULL;
    int rsa_bits = AETHER_IDENTITY_HOST_MIN_RSA_BITS;
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

aether_identity_host_status aether_identity_self_test(void) {
    static const uint8_t sha_input[] = {'a', 'b', 'c'};
    static const uint8_t expected_sha256[32] = {
        0xba, 0x78, 0x16, 0xbf, 0x8f, 0x01, 0xcf, 0xea,
        0x41, 0x41, 0x40, 0xde, 0x5d, 0xae, 0x22, 0x23,
        0xb0, 0x03, 0x61, 0xa3, 0x96, 0x17, 0x7a, 0x9c,
        0xb4, 0x10, 0xff, 0x61, 0xf2, 0x00, 0x15, 0xad
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
    static const uint8_t es256_public_key[65] = {
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
    static const uint8_t es256_input[] = {'s', 'a', 'm', 'p', 'l', 'e'};
    static const uint8_t es256_signature[64] = {
        0xef, 0xd4, 0x8b, 0x2a, 0xac, 0xb6, 0xa8, 0xfd,
        0x11, 0x40, 0xdd, 0x9c, 0xd4, 0x5e, 0x81, 0xd6,
        0x9d, 0x2c, 0x87, 0x7b, 0x56, 0xaa, 0xf9, 0x91,
        0xc3, 0x4d, 0x0e, 0xa8, 0x4e, 0xaf, 0x37, 0x16,
        0xf7, 0xcb, 0x1c, 0x94, 0x2d, 0x65, 0x7c, 0x41,
        0xd4, 0x36, 0xc7, 0xa1, 0xb6, 0xe2, 0x9f, 0x65,
        0xf3, 0xe9, 0x00, 0xdb, 0xb9, 0xaf, 0xf4, 0x06,
        0x4d, 0xc4, 0xab, 0x2f, 0x84, 0x3a, 0xcd, 0xa8
    };
    static const uint8_t rsa_input[] =
        "Aether OpenSSL 3 RSA-SHA256 self-test";
    static const uint8_t ephemeral_es256_handle[] = "self-test:es256";
    static const uint8_t ephemeral_rsa_handle[] = "self-test:rsa-sha256";
    static const uint8_t rsa_spki[294] = {
        0x30, 0x82, 0x01, 0x22, 0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86, 0x48, 0x86,
        0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0f, 0x00,
        0x30, 0x82, 0x01, 0x0a, 0x02, 0x82, 0x01, 0x01, 0x00, 0xe3, 0x55, 0x47,
        0x34, 0x66, 0x08, 0x17, 0xcc, 0x61, 0x42, 0x5b, 0x45, 0xfe, 0x43, 0xae,
        0x4a, 0xe3, 0xdb, 0xf6, 0xdb, 0x1a, 0x1b, 0x0a, 0x79, 0x6f, 0xb8, 0xa1,
        0x65, 0x17, 0xbe, 0x2c, 0xfd, 0xff, 0x93, 0x8e, 0xf0, 0x11, 0xc8, 0x20,
        0x37, 0x6a, 0xcf, 0xdf, 0xb6, 0x20, 0xfe, 0xfd, 0x7f, 0xfe, 0xb0, 0x65,
        0x8d, 0x54, 0x80, 0xf7, 0x3b, 0xbc, 0x54, 0xbb, 0x2c, 0x42, 0xd7, 0x26,
        0x67, 0xe9, 0xeb, 0x27, 0x8b, 0x14, 0xe8, 0xb5, 0xbb, 0x97, 0x6e, 0x64,
        0xc1, 0x55, 0x99, 0x86, 0x3c, 0xf9, 0x41, 0x6d, 0x2e, 0x73, 0xf7, 0x53,
        0xf6, 0x36, 0x74, 0x3a, 0xda, 0x11, 0xa4, 0x3b, 0xc7, 0xdd, 0xa7, 0x32,
        0xbd, 0x0b, 0x44, 0x1a, 0xf7, 0xff, 0x24, 0xd7, 0x33, 0xcd, 0x91, 0xa7,
        0x6a, 0x1f, 0x61, 0x1d, 0x8a, 0x40, 0x21, 0xd1, 0x5a, 0x81, 0xb8, 0xf1,
        0x62, 0xb1, 0xdc, 0x05, 0xd7, 0xdf, 0x5e, 0xa2, 0x35, 0x79, 0xa5, 0x8d,
        0x38, 0x66, 0x54, 0xfc, 0x42, 0x02, 0x70, 0xca, 0x94, 0x47, 0x97, 0x2c,
        0x2c, 0x27, 0xa2, 0x9e, 0xab, 0xa1, 0x10, 0xa0, 0xb1, 0xe0, 0xa8, 0x2a,
        0x27, 0x31, 0x6a, 0xee, 0x65, 0x51, 0x74, 0x3b, 0xf4, 0xf1, 0x07, 0x01,
        0xf4, 0xc9, 0xf4, 0x43, 0x45, 0x3a, 0x5b, 0x23, 0x39, 0x14, 0xaa, 0x62,
        0x92, 0xa8, 0xd6, 0x1a, 0x1c, 0x68, 0x53, 0xd5, 0xf4, 0x84, 0x4c, 0xcd,
        0xf5, 0xd3, 0x0c, 0x84, 0xad, 0xd7, 0xe0, 0x50, 0x0b, 0x76, 0xd1, 0xe9,
        0x29, 0xf0, 0xa6, 0x6e, 0x9d, 0xba, 0xe5, 0xa2, 0xe7, 0xc9, 0x1f, 0xca,
        0xe4, 0x2c, 0x1c, 0x24, 0x57, 0xf2, 0xe5, 0x8f, 0xd9, 0x74, 0x56, 0x10,
        0x42, 0xa9, 0x6a, 0x3b, 0x7c, 0xa6, 0x62, 0x27, 0x5f, 0x97, 0x94, 0x2c,
        0x5f, 0x66, 0xfc, 0xda, 0x01, 0x9c, 0xd4, 0x1a, 0xe2, 0xd9, 0xf8, 0xc7,
        0x7d, 0x02, 0x03, 0x01, 0x00, 0x01
    };
    static const uint8_t rsa_signature[256] = {
        0x41, 0x76, 0xcb, 0x7e, 0x3a, 0xe5, 0x47, 0xc7, 0xa6, 0xbc, 0x28, 0x50,
        0xc3, 0x2e, 0x55, 0x14, 0xd0, 0xba, 0x61, 0x0a, 0xac, 0x9a, 0x0e, 0xd7,
        0x6f, 0xe3, 0x7b, 0x10, 0x1c, 0x5c, 0xcd, 0xd3, 0xcb, 0xf4, 0xf6, 0xee,
        0x6d, 0x82, 0xc8, 0x75, 0x08, 0xf5, 0x88, 0x4c, 0x56, 0x8a, 0xd7, 0x93,
        0xc8, 0xcb, 0xbb, 0x56, 0x27, 0x7f, 0xb5, 0x39, 0xf9, 0x04, 0x92, 0x49,
        0xb0, 0x56, 0xb9, 0xae, 0xa5, 0x74, 0xcc, 0x68, 0x79, 0x14, 0xe0, 0xe9,
        0xe3, 0xbe, 0xc7, 0x91, 0xdb, 0xbe, 0x6c, 0x2b, 0x94, 0x7f, 0xf4, 0x05,
        0x30, 0xa2, 0x30, 0x1e, 0x52, 0xd7, 0xd0, 0x14, 0xe7, 0xaf, 0x2d, 0xe3,
        0x9c, 0x9b, 0xe0, 0x72, 0x9e, 0xbd, 0x61, 0x85, 0x9e, 0x80, 0xf5, 0x32,
        0x6f, 0xf2, 0x82, 0x7d, 0xca, 0xd1, 0x00, 0x59, 0xdb, 0x46, 0x95, 0x2e,
        0xd7, 0x05, 0xb6, 0xb2, 0x57, 0x0f, 0xe7, 0x69, 0x5d, 0x78, 0x05, 0x00,
        0x9c, 0x39, 0xab, 0xc6, 0xbc, 0x6c, 0x68, 0x83, 0xed, 0x30, 0xab, 0xe5,
        0x70, 0xd3, 0xd0, 0xaa, 0x6d, 0x92, 0xac, 0x01, 0xe3, 0x76, 0x45, 0xbd,
        0x73, 0x0b, 0x9b, 0x21, 0x18, 0x6a, 0x9a, 0x27, 0x1f, 0x52, 0xfc, 0x36,
        0xbe, 0xe5, 0x70, 0xb5, 0x39, 0x37, 0xa0, 0x5b, 0xaf, 0xed, 0xb2, 0xa6,
        0xf9, 0xb6, 0x24, 0x5d, 0x5b, 0xd2, 0x4c, 0x6d, 0x1d, 0x8e, 0xb1, 0x8b,
        0x38, 0xe8, 0x5c, 0x45, 0x89, 0xb6, 0x93, 0x3b, 0xca, 0x04, 0x0f, 0x12,
        0xb7, 0xb9, 0xc9, 0x7c, 0x5f, 0xe3, 0xc4, 0x06, 0x98, 0x4c, 0x8e, 0x8c,
        0x50, 0x4a, 0xb1, 0xc5, 0x13, 0xd8, 0x66, 0xc3, 0x88, 0xdb, 0xcd, 0xa4,
        0x50, 0x56, 0x8c, 0x6c, 0xe7, 0x77, 0x33, 0x5e, 0x06, 0x90, 0x83, 0x7a,
        0x16, 0xc3, 0x30, 0x6c, 0xf2, 0x0a, 0xc7, 0xa0, 0x55, 0x67, 0xb9, 0x04,
        0xad, 0x6a, 0x9b, 0x84
    };
    uint8_t output[32] = {0U};
    uint8_t random_output[32] = {0U};
    uint8_t changed_signature[64];
    uint8_t changed_rsa_signature[256];
    uint8_t signing_output[AETHER_IDENTITY_HOST_MAX_RSA_SIGNATURE_LENGTH] = {0U};
    uint8_t ephemeral_es256_public_key[AETHER_P256_PUBLIC_KEY_LENGTH] = {0U};
    uint8_t ephemeral_rsa_spki[512] = {0U};
    unsigned char *ephemeral_rsa_cursor = ephemeral_rsa_spki;
    aether_identity_signing_key_store *signing_keys = NULL;
    EVP_PKEY *ephemeral_es256_key = NULL;
    EVP_PKEY *ephemeral_rsa_key = NULL;
    size_t signing_output_length = 0U;
    size_t ephemeral_es256_public_key_length = 0U;
    uint64_t wall_clock = 0U;
    int ephemeral_rsa_spki_length = 0;
    int equal = 0;
    int valid = 0;

    ERR_clear_error();
    if (OPENSSL_version_major() < 3U || RAND_status() != 1) {
        return aether_finish(AETHER_IDENTITY_HOST_SELF_TEST_FAILED);
    }
    if (aether_identity_sha256(sha_input, sizeof(sha_input), output) !=
            AETHER_IDENTITY_HOST_OK ||
        CRYPTO_memcmp(output, expected_sha256, sizeof(output)) != 0) {
        goto failed;
    }
    if (aether_identity_hmac_sha256(
            hmac_key,
            sizeof(hmac_key),
            hmac_input,
            sizeof(hmac_input),
            output
        ) != AETHER_IDENTITY_HOST_OK ||
        CRYPTO_memcmp(output, expected_hmac, sizeof(output)) != 0) {
        goto failed;
    }
    if (aether_identity_constant_time_equals(
            expected_sha256,
            sizeof(expected_sha256),
            expected_sha256,
            sizeof(expected_sha256),
            &equal
        ) != AETHER_IDENTITY_HOST_OK || equal != 1) {
        goto failed;
    }
    if (aether_identity_random(random_output, sizeof(random_output)) !=
        AETHER_IDENTITY_HOST_OK) {
        goto failed;
    }
    if (aether_identity_wall_clock_epoch_milliseconds(&wall_clock) !=
            AETHER_IDENTITY_HOST_OK ||
        wall_clock < AETHER_CLOCK_MINIMUM_EPOCH_MILLISECONDS) {
        goto failed;
    }
    if (aether_identity_verify_es256(
            es256_public_key,
            es256_input,
            sizeof(es256_input),
            es256_signature,
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 1) {
        goto failed;
    }
    memcpy(changed_signature, es256_signature, sizeof(changed_signature));
    changed_signature[0] ^= 0x01U;
    if (aether_identity_verify_es256(
            es256_public_key,
            es256_input,
            sizeof(es256_input),
            changed_signature,
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 0) {
        goto failed;
    }
    if (aether_identity_verify_rsa_sha256(
            rsa_spki,
            sizeof(rsa_spki),
            rsa_input,
            sizeof(rsa_input) - 1U,
            rsa_signature,
            sizeof(rsa_signature),
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 1) {
        goto failed;
    }
    memcpy(changed_rsa_signature, rsa_signature, sizeof(changed_rsa_signature));
    changed_rsa_signature[0] ^= 0x01U;
    if (aether_identity_verify_rsa_sha256(
            rsa_spki,
            sizeof(rsa_spki),
            rsa_input,
            sizeof(rsa_input) - 1U,
            changed_rsa_signature,
            sizeof(changed_rsa_signature),
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 0) {
        goto failed;
    }
    ephemeral_es256_key = aether_self_test_generate_key("EC");
    ephemeral_rsa_key = aether_self_test_generate_key("RSA");
    if (ephemeral_es256_key == NULL || ephemeral_rsa_key == NULL ||
        aether_identity_signing_key_store_create(&signing_keys) !=
            AETHER_IDENTITY_HOST_OK ||
        aether_identity_signing_key_store_put(
            signing_keys,
            ephemeral_es256_handle,
            sizeof(ephemeral_es256_handle) - 1U,
            ephemeral_es256_key
        ) != AETHER_IDENTITY_HOST_OK ||
        aether_identity_signing_key_store_put(
            signing_keys,
            ephemeral_rsa_handle,
            sizeof(ephemeral_rsa_handle) - 1U,
            ephemeral_rsa_key
        ) != AETHER_IDENTITY_HOST_OK) {
        goto failed;
    }
    if (aether_identity_sign(
            signing_keys,
            AETHER_IDENTITY_SIGNING_ES256,
            ephemeral_es256_handle,
            sizeof(ephemeral_es256_handle) - 1U,
            es256_input,
            sizeof(es256_input),
            signing_output,
            sizeof(signing_output),
            &signing_output_length
        ) != AETHER_IDENTITY_HOST_OK || signing_output_length != 64U ||
        EVP_PKEY_get_octet_string_param(
            ephemeral_es256_key,
            OSSL_PKEY_PARAM_PUB_KEY,
            ephemeral_es256_public_key,
            sizeof(ephemeral_es256_public_key),
            &ephemeral_es256_public_key_length
        ) != 1 ||
        ephemeral_es256_public_key_length != sizeof(ephemeral_es256_public_key) ||
        aether_identity_verify_es256(
            ephemeral_es256_public_key,
            es256_input,
            sizeof(es256_input),
            signing_output,
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 1) {
        goto failed;
    }
    if (aether_identity_sign(
            signing_keys,
            AETHER_IDENTITY_SIGNING_RSA_SHA256,
            ephemeral_rsa_handle,
            sizeof(ephemeral_rsa_handle) - 1U,
            rsa_input,
            sizeof(rsa_input) - 1U,
            signing_output,
            sizeof(signing_output),
            &signing_output_length
        ) != AETHER_IDENTITY_HOST_OK || signing_output_length != 256U) {
        goto failed;
    }
    ephemeral_rsa_spki_length = i2d_PUBKEY(ephemeral_rsa_key, &ephemeral_rsa_cursor);
    if (ephemeral_rsa_spki_length <= 0 ||
        (size_t)ephemeral_rsa_spki_length > sizeof(ephemeral_rsa_spki) ||
        aether_identity_verify_rsa_sha256(
            ephemeral_rsa_spki,
            (size_t)ephemeral_rsa_spki_length,
            rsa_input,
            sizeof(rsa_input) - 1U,
            signing_output,
            signing_output_length,
            &valid
        ) != AETHER_IDENTITY_HOST_OK || valid != 1 ||
        aether_identity_signing_key_store_remove(
            signing_keys,
            ephemeral_es256_handle,
            sizeof(ephemeral_es256_handle) - 1U
        ) != AETHER_IDENTITY_HOST_OK ||
        aether_identity_signing_key_store_remove(
            signing_keys,
            ephemeral_rsa_handle,
            sizeof(ephemeral_rsa_handle) - 1U
        ) != AETHER_IDENTITY_HOST_OK) {
        goto failed;
    }
    OPENSSL_cleanse(output, sizeof(output));
    OPENSSL_cleanse(random_output, sizeof(random_output));
    OPENSSL_cleanse(changed_signature, sizeof(changed_signature));
    OPENSSL_cleanse(changed_rsa_signature, sizeof(changed_rsa_signature));
    OPENSSL_cleanse(signing_output, sizeof(signing_output));
    OPENSSL_cleanse(ephemeral_es256_public_key, sizeof(ephemeral_es256_public_key));
    OPENSSL_cleanse(ephemeral_rsa_spki, sizeof(ephemeral_rsa_spki));
    aether_identity_signing_key_store_destroy(signing_keys);
    EVP_PKEY_free(ephemeral_es256_key);
    EVP_PKEY_free(ephemeral_rsa_key);
    return aether_finish(AETHER_IDENTITY_HOST_OK);

failed:
    OPENSSL_cleanse(output, sizeof(output));
    OPENSSL_cleanse(random_output, sizeof(random_output));
    OPENSSL_cleanse(changed_signature, sizeof(changed_signature));
    OPENSSL_cleanse(changed_rsa_signature, sizeof(changed_rsa_signature));
    OPENSSL_cleanse(signing_output, sizeof(signing_output));
    OPENSSL_cleanse(ephemeral_es256_public_key, sizeof(ephemeral_es256_public_key));
    OPENSSL_cleanse(ephemeral_rsa_spki, sizeof(ephemeral_rsa_spki));
    aether_identity_signing_key_store_destroy(signing_keys);
    EVP_PKEY_free(ephemeral_es256_key);
    EVP_PKEY_free(ephemeral_rsa_key);
    return aether_finish(AETHER_IDENTITY_HOST_SELF_TEST_FAILED);
}
