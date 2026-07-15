-- Normalize same-ID device-grant insert races to the IdentityStore CAS contract without changing
-- device-code or user-code uniqueness semantics. The transaction-scoped advisory lock is keyed
-- only by the grant ID: different grant IDs still race through the unique digest reservations and
-- report UNIQUE_CONSTRAINT when their device/user codes collide.

CREATE OR REPLACE FUNCTION aether_identity.v1_compare_and_set_device_grant(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; replacement JSONB; stored aether_identity.device_grants%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'compare_and_set_device_grant');
    replacement := payload->'replacement';
    IF length(COALESCE(replacement->>'clientId', '')) NOT BETWEEN 1 AND 200 OR
       replacement->>'clientId' ~ '[^!-~]' OR
       length(COALESCE(replacement->>'clientName', '')) NOT BETWEEN 1 AND 200 OR
       jsonb_typeof(replacement->'requestedCapabilities') IS DISTINCT FROM 'array' OR
       jsonb_array_length(replacement->'requestedCapabilities') = 0 OR
       jsonb_typeof(replacement->'approvedCapabilities') IS DISTINCT FROM 'array' OR
       NOT ((replacement->'requestedCapabilities') @> (replacement->'approvedCapabilities')) OR
       COALESCE((replacement->>'pollingIntervalSeconds')::INTEGER, 0) NOT BETWEEN 5 AND 300 OR
       COALESCE((replacement->>'pollCount')::INTEGER, -1) < 0 OR
       (replacement->>'expiresAt')::TIMESTAMPTZ <= (replacement->>'createdAt')::TIMESTAMPTZ OR
       (replacement->>'state' = 'pending' AND jsonb_array_length(replacement->'approvedCapabilities') <> 0) OR
       (replacement->>'state' IN ('authorized', 'consumed') AND (
           jsonb_array_length(replacement->'approvedCapabilities') = 0 OR
           replacement->>'userId' IS NULL OR replacement->>'organizationId' IS NULL OR
           replacement->>'authorizedByUserId' IS NULL OR replacement->>'authorizedAt' IS NULL
       )) OR
       (replacement->>'state' = 'denied' AND replacement->>'deniedAt' IS NULL) OR
       (replacement->>'state' = 'consumed' AND replacement->>'consumedAt' IS NULL) OR
       (replacement->>'state' = 'expired' AND replacement->>'expiredAt' IS NULL) OR
       (replacement->>'state' = 'cancelled' AND replacement->>'cancelledAt' IS NULL) THEN
        RAISE EXCEPTION 'device grant model is invalid' USING ERRCODE = 'A0003';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtextextended('aether_identity.device_grant:' || (replacement->>'id'), 0)
    );
    SELECT * INTO stored FROM aether_identity.device_grants WHERE id = replacement->>'id' FOR UPDATE;
    IF payload->'expectedVersion' IS NULL OR payload->'expectedVersion' = 'null'::JSONB THEN
        IF FOUND THEN
            RAISE EXCEPTION 'device grant version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF replacement->>'state' <> 'pending' OR
           (replacement->>'version')::BIGINT <> 0 OR
           replacement#>'{deviceCodeDigest}' = replacement#>'{userCodeDigest}' OR
           EXISTS (
               SELECT 1 FROM aether_identity.device_grants
                WHERE document#>'{deviceCodeDigest}' IN (
                          replacement#>'{deviceCodeDigest}', replacement#>'{userCodeDigest}'
                      ) OR
                      document#>'{userCodeDigest}' IN (
                          replacement#>'{deviceCodeDigest}', replacement#>'{userCodeDigest}'
                      )
           ) THEN
            RAISE EXCEPTION 'new device grant is invalid' USING ERRCODE = 'A0003';
        END IF;
        INSERT INTO aether_identity.device_grants(
            id, device_digest_algorithm, device_digest_encoded, device_digest_key_version,
            user_digest_algorithm, user_digest_encoded, user_digest_key_version,
            state, version, expires_at, document
        ) VALUES (
            replacement->>'id', replacement#>>'{deviceCodeDigest,algorithm}',
            replacement#>>'{deviceCodeDigest,encoded}', replacement#>>'{deviceCodeDigest,keyVersion}',
            replacement#>>'{userCodeDigest,algorithm}', replacement#>>'{userCodeDigest,encoded}',
            replacement#>>'{userCodeDigest,keyVersion}', replacement->>'state',
            (replacement->>'version')::BIGINT, (replacement->>'expiresAt')::TIMESTAMPTZ, replacement
        );
        INSERT INTO aether_identity.device_grant_digest_reservations(
            digest_algorithm, digest_encoded, digest_key_version, grant_id, digest_kind
        ) VALUES
            (
                replacement#>>'{deviceCodeDigest,algorithm}',
                replacement#>>'{deviceCodeDigest,encoded}',
                COALESCE(replacement#>>'{deviceCodeDigest,keyVersion}', ''),
                replacement->>'id',
                'device'
            ),
            (
                replacement#>>'{userCodeDigest,algorithm}',
                replacement#>>'{userCodeDigest,encoded}',
                COALESCE(replacement#>>'{userCodeDigest,keyVersion}', ''),
                replacement->>'id',
                'user'
            );
    ELSE
        IF NOT FOUND THEN RAISE EXCEPTION 'device grant not found' USING ERRCODE = 'A0012'; END IF;
        IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
            RAISE EXCEPTION 'device grant version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF (replacement->>'version')::BIGINT <> stored.version + 1 OR
           stored.document#>'{deviceCodeDigest}' <> replacement#>'{deviceCodeDigest}' OR
           stored.document#>'{userCodeDigest}' <> replacement#>'{userCodeDigest}' OR
           stored.document->>'clientId' <> replacement->>'clientId' OR
           stored.document->>'clientName' <> replacement->>'clientName' OR
           NOT ((stored.document->'requestedCapabilities') @> (replacement->'requestedCapabilities') AND
                (stored.document->'requestedCapabilities') <@ (replacement->'requestedCapabilities')) OR
           (stored.document->>'createdAt')::TIMESTAMPTZ <>
               (replacement->>'createdAt')::TIMESTAMPTZ OR
           stored.expires_at <> (replacement->>'expiresAt')::TIMESTAMPTZ OR
           NOT (
               (stored.state = 'pending' AND replacement->>'state' IN (
                   'pending', 'authorized', 'denied', 'expired', 'cancelled'
               )) OR
               (stored.state = 'authorized' AND replacement->>'state' IN (
                   'authorized', 'consumed', 'expired'
               ))
           ) THEN
            RAISE EXCEPTION 'device grant transition invalid' USING ERRCODE = 'A0003';
        END IF;
        UPDATE aether_identity.device_grants
           SET state = replacement->>'state', version = (replacement->>'version')::BIGINT,
               expires_at = (replacement->>'expiresAt')::TIMESTAMPTZ, document = replacement
         WHERE id = stored.id;
    END IF;
    PERFORM aether_identity.record_audit(payload->'auditEvent');
    RETURN aether_identity.rpc_success('compare_and_set_device_grant', replacement);
END;
$$;
