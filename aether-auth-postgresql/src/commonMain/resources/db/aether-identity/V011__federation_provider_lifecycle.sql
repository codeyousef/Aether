-- Aether identity migration V011
-- Federation providers are controlled by one tenant-route row. Disabling advances a logical
-- session epoch instead of updating an unbounded set of sessions. Every command that creates or
-- consumes federation state validates the exact enabled-provider lease in the same transaction.

CREATE TABLE aether_identity.federation_provider_controls (
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    provider_id TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('oidc', 'saml')),
    storage_key TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('enabled', 'disabled')),
    session_epoch BIGINT NOT NULL CHECK (session_epoch >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    disabled_at TIMESTAMPTZ,
    disabled_reason_code TEXT,
    document JSONB NOT NULL,
    PRIMARY KEY (organization_id, provider_id),
    CHECK (provider_id ~ '^[a-z0-9][a-z0-9_-]{0,62}$'),
    CHECK (
        (kind = 'oidc' AND storage_key ~ '^oidc\.[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$') OR
        (kind = 'saml' AND storage_key ~ '^saml\.[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$')
    ),
    CHECK (updated_at >= created_at),
    CHECK (
        (state = 'enabled' AND disabled_at IS NULL AND disabled_reason_code IS NULL) OR
        (state = 'disabled' AND disabled_at IS NOT NULL AND disabled_at = updated_at AND
         disabled_reason_code IS NOT NULL AND
         length(disabled_reason_code) BETWEEN 1 AND 200 AND
         disabled_reason_code ~ '[^[:space:]]')
    ),
    CHECK (jsonb_typeof(document) = 'object'),
    CHECK (document->>'organizationId' IS NOT DISTINCT FROM organization_id),
    CHECK (document->>'providerId' IS NOT DISTINCT FROM provider_id),
    CHECK (document->>'kind' IS NOT DISTINCT FROM kind),
    CHECK (document->>'storageKey' IS NOT DISTINCT FROM storage_key),
    CHECK (document->>'state' IS NOT DISTINCT FROM state),
    CHECK ((document->>'sessionEpoch')::BIGINT IS NOT DISTINCT FROM session_epoch),
    CHECK ((document->>'version')::BIGINT IS NOT DISTINCT FROM version),
    CHECK ((document->>'createdAt')::TIMESTAMPTZ IS NOT DISTINCT FROM created_at),
    CHECK ((document->>'updatedAt')::TIMESTAMPTZ IS NOT DISTINCT FROM updated_at),
    CHECK ((document->>'disabledAt')::TIMESTAMPTZ IS NOT DISTINCT FROM disabled_at),
    CHECK ((document->>'disabledReasonCode') IS NOT DISTINCT FROM disabled_reason_code)
);

COMMENT ON TABLE aether_identity.federation_provider_controls IS
    'Canonical tenant federation lifecycle state; session invalidation uses session_epoch';

ALTER TABLE aether_identity.sessions
    ADD COLUMN federation_provider_session_epoch BIGINT CHECK (
        federation_provider_session_epoch IS NULL OR federation_provider_session_epoch >= 0
    );

-- V002 established provenance before the provider control plane existed. Preserve its federated
-- sessions as epoch zero records; because no provider row is backfilled, they fail closed until an
-- administrator explicitly creates and enables the provider. Non-federated sessions remain null.
UPDATE aether_identity.sessions
   SET federation_provider_session_epoch = 0,
       document = jsonb_set(document, '{federationProviderSessionEpoch}', '0'::JSONB, TRUE)
 WHERE authentication_method IN ('oidc', 'saml');

ALTER TABLE aether_identity.sessions
    ADD CONSTRAINT sessions_federation_provider_epoch_document_check CHECK (
        (document->>'federationProviderSessionEpoch')::BIGINT IS NOT DISTINCT FROM
            federation_provider_session_epoch
    ),
    DROP CONSTRAINT sessions_federation_provenance_check,
    ADD CONSTRAINT sessions_federation_provenance_check CHECK (
        (
            authentication_method IN ('oidc', 'saml') AND
            federation_organization_id IS NOT NULL AND
            federation_provider_key IS NOT NULL AND
            length(federation_provider_key) BETWEEN 1 AND 512 AND
            federation_provider_key ~ '[^[:space:]]' AND
            federation_provider_session_epoch IS NOT NULL AND
            external_identity_id IS NOT NULL
        ) OR (
            authentication_method NOT IN ('oidc', 'saml') AND
            federation_organization_id IS NULL AND
            federation_provider_key IS NULL AND
            federation_provider_session_epoch IS NULL AND
            external_identity_id IS NULL
        )
    );

CREATE OR REPLACE FUNCTION aether_identity_internal.federation_provider_lease(
    p_document JSONB
) RETURNS JSONB
LANGUAGE sql
IMMUTABLE
STRICT
AS $$
    SELECT jsonb_build_object(
        'organizationId', p_document->'organizationId',
        'kind', p_document->'kind',
        'providerId', p_document->'providerId',
        'storageKey', p_document->'storageKey',
        'sessionEpoch', p_document->'sessionEpoch',
        'version', p_document->'version'
    );
$$;
REVOKE ALL ON FUNCTION aether_identity_internal.federation_provider_lease(JSONB) FROM PUBLIC;

CREATE OR REPLACE FUNCTION aether_identity_internal.require_federation_provider_lease(
    p_lease JSONB
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    organization aether_identity.organizations%ROWTYPE;
    stored aether_identity.federation_provider_controls%ROWTYPE;
BEGIN
    IF jsonb_typeof(p_lease) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'federation provider lease is unavailable' USING ERRCODE = 'A0015';
    END IF;

    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = p_lease->>'organizationId'
     FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'federation provider lease is unavailable' USING ERRCODE = 'A0015';
    END IF;

    SELECT * INTO stored
      FROM aether_identity.federation_provider_controls
     WHERE organization_id = p_lease->>'organizationId'
       AND provider_id = p_lease->>'providerId'
     FOR UPDATE;

    IF NOT FOUND OR stored.state <> 'enabled' OR
       stored.kind IS DISTINCT FROM p_lease->>'kind' OR
       stored.storage_key IS DISTINCT FROM p_lease->>'storageKey' OR
       stored.session_epoch::TEXT IS DISTINCT FROM p_lease->>'sessionEpoch' OR
       stored.version::TEXT IS DISTINCT FROM p_lease->>'version' THEN
        RAISE EXCEPTION 'federation provider lease is unavailable' USING ERRCODE = 'A0015';
    END IF;

    RETURN aether_identity_internal.federation_provider_lease(stored.document);
END;
$$;
REVOKE ALL ON FUNCTION aether_identity_internal.require_federation_provider_lease(JSONB) FROM PUBLIC;

CREATE OR REPLACE FUNCTION aether_identity_internal.require_federated_session(
    p_session JSONB
) RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    method TEXT := p_session->>'authenticationMethod';
    expected_kind TEXT;
    organization aether_identity.organizations%ROWTYPE;
    stored aether_identity.federation_provider_controls%ROWTYPE;
BEGIN
    expected_kind := CASE method WHEN 'oidc' THEN 'oidc' WHEN 'saml' THEN 'saml' ELSE NULL END;
    IF expected_kind IS NULL THEN
        IF p_session->>'federationOrganizationId' IS NOT NULL OR
           p_session->>'federationProviderKey' IS NOT NULL OR
           p_session->>'federationProviderSessionEpoch' IS NOT NULL OR
           p_session->>'externalIdentityId' IS NOT NULL THEN
            RAISE EXCEPTION 'non-federated session contains federation provenance'
                USING ERRCODE = 'A0003';
        END IF;
        RETURN;
    END IF;

    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = p_session->>'federationOrganizationId'
     FOR KEY SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'federated session provider is unavailable' USING ERRCODE = 'A0015';
    END IF;

    SELECT * INTO stored
      FROM aether_identity.federation_provider_controls
     WHERE storage_key = p_session->>'federationProviderKey'
     FOR UPDATE;
    IF NOT FOUND OR stored.state <> 'enabled' OR
       stored.organization_id IS DISTINCT FROM p_session->>'federationOrganizationId' OR
       stored.kind IS DISTINCT FROM expected_kind OR
       stored.session_epoch::TEXT IS DISTINCT FROM p_session->>'federationProviderSessionEpoch' THEN
        RAISE EXCEPTION 'federated session provider is unavailable' USING ERRCODE = 'A0015';
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION aether_identity_internal.require_federated_session(JSONB) FROM PUBLIC;

CREATE OR REPLACE FUNCTION aether_identity.insert_session(p_session JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored_user aether_identity.users%ROWTYPE;
    predecessor aether_identity.sessions%ROWTYPE;
BEGIN
    SELECT * INTO stored_user FROM aether_identity.users
     WHERE id = p_session->>'userId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'session user not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_user.state <> 'active' OR
       stored_user.session_epoch <> (p_session->>'userSessionEpoch')::BIGINT OR
       p_session->>'state' <> 'active' OR
       (p_session->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new session is invalid' USING ERRCODE = 'A0003';
    END IF;

    -- A passkey step-up may replace a federated session without retaining federation provenance,
    -- but the predecessor's provider still has to be current at the instant of rotation.
    IF p_session->>'rotatedFromId' IS NOT NULL THEN
        SELECT * INTO predecessor
          FROM aether_identity.sessions
         WHERE id = p_session->>'rotatedFromId'
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'session predecessor not found' USING ERRCODE = 'A0012';
        END IF;
        PERFORM aether_identity_internal.require_federated_session(predecessor.document);
    END IF;

    PERFORM aether_identity_internal.require_federated_session(p_session);
    INSERT INTO aether_identity.sessions(
        id, family_id, user_id,
        token_digest_algorithm, token_digest_encoded, token_digest_key_version,
        csrf_digest_algorithm, csrf_digest_encoded, csrf_digest_key_version,
        authentication_method, federation_organization_id, federation_provider_key,
        federation_provider_session_epoch, external_identity_id, state, user_session_epoch, version,
        idle_expires_at, absolute_expires_at, document
    ) VALUES (
        p_session->>'id', p_session->>'familyId', p_session->>'userId',
        p_session#>>'{tokenDigest,algorithm}', p_session#>>'{tokenDigest,encoded}',
        p_session#>>'{tokenDigest,keyVersion}',
        p_session#>>'{csrfDigest,algorithm}', p_session#>>'{csrfDigest,encoded}',
        p_session#>>'{csrfDigest,keyVersion}',
        p_session->>'authenticationMethod', p_session->>'federationOrganizationId',
        p_session->>'federationProviderKey', (p_session->>'federationProviderSessionEpoch')::BIGINT,
        p_session->>'externalIdentityId', p_session->>'state',
        (p_session->>'userSessionEpoch')::BIGINT, (p_session->>'version')::BIGINT,
        (p_session->>'idleExpiresAt')::TIMESTAMPTZ,
        (p_session->>'absoluteExpiresAt')::TIMESTAMPTZ, p_session
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_federation_provider_control(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_federation_provider_control');
    SELECT document INTO result
      FROM aether_identity.federation_provider_controls
     WHERE organization_id = payload->>'organizationId'
       AND provider_id = payload->>'providerId';
    RETURN aether_identity.rpc_success('find_federation_provider_control', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_federation_provider_control_by_storage_key(
    p_request JSONB
) RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(
        p_request,
        'find_federation_provider_control_by_storage_key'
    );
    SELECT document INTO result
      FROM aether_identity.federation_provider_controls
     WHERE storage_key = payload->>'storageKey';
    RETURN aether_identity.rpc_success(
        'find_federation_provider_control_by_storage_key',
        result
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_acquire_federation_provider_lease(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    organization aether_identity.organizations%ROWTYPE;
    stored aether_identity.federation_provider_controls%ROWTYPE;
    storage_owner aether_identity.federation_provider_controls%ROWTYPE;
    initial_document JSONB;
    lease JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'acquire_federation_provider_lease');
    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = payload->>'organizationId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'federation provider organization not found' USING ERRCODE = 'A0012';
    END IF;
    IF organization.state <> 'active' THEN
        RAISE EXCEPTION 'federation provider organization is not active' USING ERRCODE = 'A0003';
    END IF;
    SELECT * INTO stored
      FROM aether_identity.federation_provider_controls
     WHERE organization_id = payload->>'organizationId'
       AND provider_id = payload->>'providerId'
     FOR UPDATE;
    IF NOT FOUND THEN
        SELECT * INTO storage_owner
          FROM aether_identity.federation_provider_controls
         WHERE storage_key = payload->>'storageKey'
         FOR UPDATE;
        IF FOUND THEN
            RAISE unique_violation USING MESSAGE = 'federation provider mapping already exists';
        END IF;
        initial_document := jsonb_build_object(
            'organizationId', payload->'organizationId',
            'kind', payload->'kind',
            'providerId', payload->'providerId',
            'storageKey', payload->'storageKey',
            'state', 'enabled',
            'sessionEpoch', 0,
            'version', 0,
            'createdAt', payload->'acquiredAt',
            'updatedAt', payload->'acquiredAt',
            'disabledAt', 'null'::JSONB,
            'disabledReasonCode', 'null'::JSONB
        );
        INSERT INTO aether_identity.federation_provider_controls(
            organization_id, provider_id, kind, storage_key, state, session_epoch, version,
            created_at, updated_at, disabled_at, disabled_reason_code, document
        ) VALUES (
            payload->>'organizationId', payload->>'providerId', payload->>'kind',
            payload->>'storageKey', 'enabled', 0, 0,
            (payload->>'acquiredAt')::TIMESTAMPTZ, (payload->>'acquiredAt')::TIMESTAMPTZ,
            NULL, NULL, initial_document
        ) RETURNING * INTO stored;
    END IF;
    IF stored.kind IS DISTINCT FROM payload->>'kind' OR
       stored.storage_key IS DISTINCT FROM payload->>'storageKey' THEN
        RAISE unique_violation USING MESSAGE = 'federation provider mapping already exists';
    END IF;
    IF stored.state <> 'enabled' THEN
        RAISE EXCEPTION 'federation provider is disabled' USING ERRCODE = 'A0015';
    END IF;
    lease := aether_identity_internal.federation_provider_lease(stored.document);
    RETURN aether_identity.rpc_success('acquire_federation_provider_lease', lease);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_validate_federation_provider_lease(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE payload JSONB; lease JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'validate_federation_provider_lease');
    lease := aether_identity_internal.require_federation_provider_lease(payload);
    RETURN aether_identity.rpc_success('validate_federation_provider_lease', lease);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_compare_and_set_federation_provider_state(
    p_request JSONB
) RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    replacement JSONB;
    audit_event JSONB;
    expected_version BIGINT;
    expected_version_absent BOOLEAN;
    organization aether_identity.organizations%ROWTYPE;
    stored aether_identity.federation_provider_controls%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(
        p_request,
        'compare_and_set_federation_provider_state'
    );
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    expected_version_absent := payload->'expectedVersion' IS NULL OR
        payload->'expectedVersion' = 'null'::JSONB;
    IF NOT expected_version_absent THEN
        BEGIN
            expected_version := (payload->>'expectedVersion')::BIGINT;
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'federation provider expected version is invalid'
                USING ERRCODE = 'A0003';
        END;
    END IF;

    IF jsonb_typeof(replacement) IS DISTINCT FROM 'object' OR
       jsonb_typeof(audit_event) IS DISTINCT FROM 'object' OR
       replacement->>'organizationId' IS NULL OR replacement->>'providerId' IS NULL OR
       replacement->>'storageKey' IS NULL THEN
        RAISE EXCEPTION 'federation provider transition is invalid' USING ERRCODE = 'A0003';
    END IF;

    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = replacement->>'organizationId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'federation provider organization not found' USING ERRCODE = 'A0012';
    END IF;
    IF organization.state <> 'active' THEN
        RAISE EXCEPTION 'federation provider organization is not active' USING ERRCODE = 'A0003';
    END IF;

    IF audit_event->>'organizationId' IS DISTINCT FROM replacement->>'organizationId' OR
       audit_event#>>'{target,type}' IS DISTINCT FROM 'federation_provider' OR
       audit_event#>>'{target,id}' IS DISTINCT FROM replacement->>'storageKey' OR
       audit_event->>'outcome' IS DISTINCT FROM 'succeeded' OR
       (audit_event->>'occurredAt')::TIMESTAMPTZ IS DISTINCT FROM
           (replacement->>'updatedAt')::TIMESTAMPTZ OR
       audit_event->>'action' IS DISTINCT FROM (
           CASE replacement->>'state'
               WHEN 'enabled' THEN 'federation_provider.enabled'
               WHEN 'disabled' THEN 'federation_provider.disabled'
               ELSE NULL
           END
       ) OR
       (replacement->>'state' = 'disabled' AND
        audit_event->>'reasonCode' IS DISTINCT FROM replacement->>'disabledReasonCode') THEN
        RAISE EXCEPTION 'federation provider audit is invalid' USING ERRCODE = 'A0003';
    END IF;

    IF expected_version_absent THEN
        IF replacement->>'state' IS DISTINCT FROM 'disabled' OR
           (replacement->>'version')::BIGINT IS DISTINCT FROM 0 OR
           (replacement->>'sessionEpoch')::BIGINT IS DISTINCT FROM 1 OR
           (replacement->>'createdAt')::TIMESTAMPTZ IS DISTINCT FROM
               (replacement->>'updatedAt')::TIMESTAMPTZ THEN
            RAISE EXCEPTION 'initial federation provider state is invalid' USING ERRCODE = 'A0003';
        END IF;
        SELECT * INTO stored
          FROM aether_identity.federation_provider_controls
         WHERE organization_id = replacement->>'organizationId'
           AND provider_id = replacement->>'providerId'
         FOR UPDATE;
        IF FOUND THEN
            IF stored.kind IS DISTINCT FROM replacement->>'kind' OR
               stored.storage_key IS DISTINCT FROM replacement->>'storageKey' THEN
                RAISE unique_violation USING MESSAGE = 'federation provider mapping already exists';
            END IF;
            RAISE EXCEPTION 'federation provider version conflict' USING ERRCODE = 'A0002';
        END IF;
        SELECT * INTO stored
          FROM aether_identity.federation_provider_controls
         WHERE storage_key = replacement->>'storageKey'
         FOR UPDATE;
        IF FOUND THEN
            RAISE unique_violation USING MESSAGE = 'federation provider mapping already exists';
        END IF;
        INSERT INTO aether_identity.federation_provider_controls(
            organization_id, provider_id, kind, storage_key, state, session_epoch, version,
            created_at, updated_at, disabled_at, disabled_reason_code, document
        ) VALUES (
            replacement->>'organizationId', replacement->>'providerId', replacement->>'kind',
            replacement->>'storageKey', replacement->>'state',
            (replacement->>'sessionEpoch')::BIGINT, (replacement->>'version')::BIGINT,
            (replacement->>'createdAt')::TIMESTAMPTZ,
            (replacement->>'updatedAt')::TIMESTAMPTZ,
            (replacement->>'disabledAt')::TIMESTAMPTZ,
            replacement->>'disabledReasonCode', replacement
        );
    ELSE
        SELECT * INTO stored
          FROM aether_identity.federation_provider_controls
         WHERE organization_id = replacement->>'organizationId'
           AND provider_id = replacement->>'providerId'
         FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'federation provider not found' USING ERRCODE = 'A0012';
        END IF;
        IF stored.version <> expected_version THEN
            RAISE EXCEPTION 'federation provider version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF stored.kind IS DISTINCT FROM replacement->>'kind' OR
           stored.storage_key IS DISTINCT FROM replacement->>'storageKey' THEN
            RAISE unique_violation USING MESSAGE = 'federation provider mapping already exists';
        END IF;
        IF stored.created_at IS DISTINCT FROM (replacement->>'createdAt')::TIMESTAMPTZ OR
           (replacement->>'version')::BIGINT IS DISTINCT FROM expected_version + 1 OR
           (replacement->>'updatedAt')::TIMESTAMPTZ < stored.updated_at OR
           stored.state = replacement->>'state' OR
           (stored.state = 'enabled' AND (
               replacement->>'state' IS DISTINCT FROM 'disabled' OR
               (replacement->>'sessionEpoch')::BIGINT IS DISTINCT FROM stored.session_epoch + 1
           )) OR
           (stored.state = 'disabled' AND (
               replacement->>'state' IS DISTINCT FROM 'enabled' OR
               (replacement->>'sessionEpoch')::BIGINT IS DISTINCT FROM stored.session_epoch
           )) THEN
            RAISE EXCEPTION 'federation provider transition is invalid' USING ERRCODE = 'A0003';
        END IF;
        UPDATE aether_identity.federation_provider_controls
           SET state = replacement->>'state',
               session_epoch = (replacement->>'sessionEpoch')::BIGINT,
               version = (replacement->>'version')::BIGINT,
               updated_at = (replacement->>'updatedAt')::TIMESTAMPTZ,
               disabled_at = (replacement->>'disabledAt')::TIMESTAMPTZ,
               disabled_reason_code = replacement->>'disabledReasonCode',
               document = replacement
         WHERE organization_id = stored.organization_id AND provider_id = stored.provider_id;
    END IF;

    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'compare_and_set_federation_provider_state',
        jsonb_build_object('control', replacement, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_challenge(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    challenge JSONB;
    command_lease JSONB;
    stored_lease JSONB;
    challenge_user aether_identity.users%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_challenge');
    challenge := payload->'challenge';
    command_lease := COALESCE(payload->'federationProviderLease', 'null'::JSONB);
    stored_lease := COALESCE(challenge->'federationProviderLease', 'null'::JSONB);
    IF challenge->>'state' <> 'pending' OR
       (challenge->>'version')::BIGINT <> 0 OR
       (challenge->>'attemptCount')::INTEGER <> 0 OR
       command_lease IS DISTINCT FROM stored_lease THEN
        RAISE EXCEPTION 'new challenge is invalid' USING ERRCODE = 'A0003';
    END IF;
    IF challenge->>'purpose' = 'external_identity_link' THEN
        IF stored_lease = 'null'::JSONB OR
           challenge->>'organizationId' IS DISTINCT FROM stored_lease->>'organizationId' THEN
            RAISE EXCEPTION 'external identity challenge is invalid' USING ERRCODE = 'A0003';
        END IF;
        IF challenge->>'userId' IS NOT NULL THEN
            SELECT * INTO challenge_user
              FROM aether_identity.users
             WHERE id = challenge->>'userId'
             FOR KEY SHARE;
            IF NOT FOUND THEN
                RAISE EXCEPTION 'external identity challenge user not found'
                    USING ERRCODE = 'A0012';
            END IF;
        END IF;
        PERFORM aether_identity_internal.require_federation_provider_lease(stored_lease);
    ELSIF stored_lease <> 'null'::JSONB THEN
        RAISE EXCEPTION 'non-federated challenge contains provider lease' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.challenges(
        id, purpose,
        challenge_digest_algorithm, challenge_digest_encoded, challenge_digest_key_version,
        binding_digest_algorithm, binding_digest_encoded, binding_digest_key_version,
        payload_digest_algorithm, payload_digest_encoded, payload_digest_key_version,
        user_id, organization_id, state, version, expires_at, document
    ) VALUES (
        challenge->>'id', challenge->>'purpose',
        challenge#>>'{challengeDigest,algorithm}', challenge#>>'{challengeDigest,encoded}',
        challenge#>>'{challengeDigest,keyVersion}', challenge#>>'{bindingDigest,algorithm}',
        challenge#>>'{bindingDigest,encoded}', challenge#>>'{bindingDigest,keyVersion}',
        challenge#>>'{payloadDigest,algorithm}', challenge#>>'{payloadDigest,encoded}',
        challenge#>>'{payloadDigest,keyVersion}', NULLIF(challenge->>'userId', ''),
        NULLIF(challenge->>'organizationId', ''), challenge->>'state',
        (challenge->>'version')::BIGINT, (challenge->>'expiresAt')::TIMESTAMPTZ, challenge
    );
    RETURN aether_identity.rpc_success('create_challenge', challenge);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_consume_challenge(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    stored aether_identity.challenges%ROWTYPE;
    command_lease JSONB;
    stored_lease JSONB;
    challenge JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'consume_challenge');
    SELECT * INTO stored
      FROM aether_identity.challenges
     WHERE id = payload->>'challengeId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'challenge not found' USING ERRCODE = 'A0012';
    END IF;
    command_lease := COALESCE(payload->'federationProviderLease', 'null'::JSONB);
    stored_lease := COALESCE(stored.document->'federationProviderLease', 'null'::JSONB);
    IF command_lease IS DISTINCT FROM stored_lease THEN
        RAISE EXCEPTION 'challenge provider lease changed' USING ERRCODE = 'A0003';
    END IF;
    IF stored.purpose = 'external_identity_link' THEN
        IF stored_lease = 'null'::JSONB THEN
            RAISE EXCEPTION 'external identity challenge has no provider lease'
                USING ERRCODE = 'A0003';
        END IF;
        PERFORM aether_identity_internal.require_federation_provider_lease(stored_lease);
    ELSIF stored_lease <> 'null'::JSONB THEN
        RAISE EXCEPTION 'non-federated challenge contains provider lease' USING ERRCODE = 'A0003';
    END IF;
    challenge := aether_identity.consume_challenge_model(
        payload->>'challengeId',
        (payload->>'expectedVersion')::BIGINT,
        payload->>'terminalState',
        (payload->>'consumedAt')::TIMESTAMPTZ
    );
    IF payload->'auditEvent' IS NOT NULL AND payload->'auditEvent' <> 'null'::JSONB THEN
        PERFORM aether_identity.record_audit(payload->'auditEvent');
    END IF;
    RETURN aether_identity.rpc_success('consume_challenge', challenge);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_touch_identity_session(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    expected_version BIGINT;
    requested_last_used_at TIMESTAMPTZ;
    requested_idle_expires_at TIMESTAMPTZ;
    stored aether_identity.sessions%ROWTYPE;
    replacement JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'touch_identity_session');
    IF payload->>'sessionId' IS NULL OR payload->>'expectedVersion' IS NULL OR
       payload->>'lastUsedAt' IS NULL OR payload->>'idleExpiresAt' IS NULL THEN
        RAISE EXCEPTION 'identity session touch request is invalid' USING ERRCODE = 'A0003';
    END IF;
    BEGIN
        expected_version := (payload->>'expectedVersion')::BIGINT;
        requested_last_used_at := (payload->>'lastUsedAt')::TIMESTAMPTZ;
        requested_idle_expires_at := (payload->>'idleExpiresAt')::TIMESTAMPTZ;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'identity session touch request is invalid' USING ERRCODE = 'A0003';
    END;
    IF expected_version < 0 THEN
        RAISE EXCEPTION 'identity session touch version is invalid' USING ERRCODE = 'A0003';
    END IF;
    SELECT * INTO stored FROM aether_identity.sessions
     WHERE id = payload->>'sessionId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> expected_version THEN
        RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN
        RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009';
    END IF;
    PERFORM aether_identity_internal.require_federated_session(stored.document);
    IF requested_last_used_at >= stored.idle_expires_at OR
       requested_last_used_at >= stored.absolute_expires_at THEN
        RAISE EXCEPTION 'session expired' USING ERRCODE = 'A0010';
    END IF;
    IF requested_last_used_at < (stored.document->>'lastUsedAt')::TIMESTAMPTZ OR
       requested_idle_expires_at < requested_last_used_at OR
       requested_idle_expires_at > stored.absolute_expires_at THEN
        RAISE EXCEPTION 'identity session touch is invalid' USING ERRCODE = 'A0003';
    END IF;
    replacement := stored.document || jsonb_build_object(
        'lastUsedAt', payload->'lastUsedAt',
        'idleExpiresAt', payload->'idleExpiresAt',
        'version', stored.version + 1
    );
    UPDATE aether_identity.sessions
       SET version = stored.version + 1,
           idle_expires_at = requested_idle_expires_at,
           document = replacement
     WHERE id = stored.id;
    RETURN aether_identity.rpc_success('touch_identity_session', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_rotate_session(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    stored aether_identity.sessions%ROWTYPE;
    previous JSONB;
    replacement JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'rotate_session');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    PERFORM 1 FROM aether_identity.users
     WHERE id = replacement->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session user not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored FROM aether_identity.sessions
     WHERE id = payload->>'sessionId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN
        RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009';
    END IF;
    PERFORM aether_identity_internal.require_federated_session(stored.document);
    IF stored.authentication_method IN ('oidc', 'saml') AND (
        replacement->>'authenticationMethod' IS DISTINCT FROM stored.authentication_method OR
        replacement->>'federationOrganizationId' IS DISTINCT FROM stored.federation_organization_id OR
        replacement->>'federationProviderKey' IS DISTINCT FROM stored.federation_provider_key OR
        replacement->>'federationProviderSessionEpoch' IS DISTINCT FROM
            stored.federation_provider_session_epoch::TEXT OR
        replacement->>'externalIdentityId' IS DISTINCT FROM stored.external_identity_id
    ) THEN
        RAISE EXCEPTION 'federated session rotation changed provenance' USING ERRCODE = 'A0003';
    END IF;
    IF stored.authentication_method NOT IN ('oidc', 'saml') AND
       replacement->>'authenticationMethod' IN ('oidc', 'saml') THEN
        RAISE EXCEPTION 'session rotation cannot introduce federation provenance'
            USING ERRCODE = 'A0003';
    END IF;
    IF stored.absolute_expires_at <= (payload->>'rotatedAt')::TIMESTAMPTZ OR
       stored.idle_expires_at <= (payload->>'rotatedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'session expired' USING ERRCODE = 'A0010';
    END IF;
    IF replacement->>'userId' <> stored.user_id OR
       replacement->>'familyId' <> stored.family_id OR
       replacement->>'rotatedFromId' <> stored.id OR
       (replacement->>'rotationCounter')::BIGINT <>
           (stored.document->>'rotationCounter')::BIGINT + 1 OR
       (replacement->>'createdAt')::TIMESTAMPTZ <>
           (payload->>'rotatedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'session rotation is invalid' USING ERRCODE = 'A0003';
    END IF;
    previous := stored.document || jsonb_build_object(
        'state', 'rotated', 'rotatedToId', replacement->'id',
        'rotatedAt', to_jsonb((payload->>'rotatedAt')::TIMESTAMPTZ),
        'version', stored.version + 1
    );
    UPDATE aether_identity.sessions
       SET state = 'rotated', version = stored.version + 1, document = previous
     WHERE id = stored.id;
    PERFORM aether_identity.insert_session(replacement);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'rotate_session',
        jsonb_build_object('previous', previous, 'replacement', replacement, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_link_external_identity(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    identity_document JSONB;
    receipt JSONB;
    lease JSONB;
    audit_event JSONB;
    jit_provisioning JSONB;
    provisioned_user JSONB := 'null'::JSONB;
    provisioned_membership JSONB := 'null'::JSONB;
    linked_user aether_identity.users%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'link_external_identity');
    identity_document := payload->'identity';
    receipt := payload->'replayReceipt';
    lease := payload->'federationProviderLease';
    audit_event := payload->'auditEvent';
    jit_provisioning := COALESCE(payload->'jitProvisioning', 'null'::JSONB);
    IF jit_provisioning = 'null'::JSONB THEN
        SELECT * INTO linked_user
          FROM aether_identity.users
         WHERE id = identity_document->>'userId'
         FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'external identity user not found' USING ERRCODE = 'A0012';
        END IF;
    END IF;
    PERFORM aether_identity_internal.require_federation_provider_lease(lease);
    IF identity_document->>'state' <> 'active' OR
       (identity_document->>'version')::BIGINT <> 0 OR
       identity_document->>'provider' IS DISTINCT FROM receipt->>'provider' OR
       identity_document->>'provider' IS DISTINCT FROM lease->>'storageKey' OR
       audit_event->>'organizationId' IS DISTINCT FROM lease->>'organizationId' OR
       audit_event->>'action' IS DISTINCT FROM 'external_identity.linked' OR
       (receipt->>'expiresAt')::TIMESTAMPTZ <= (audit_event->>'occurredAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'external identity link is invalid' USING ERRCODE = 'A0003';
    END IF;

    IF jit_provisioning <> 'null'::JSONB THEN
        IF jsonb_typeof(jit_provisioning) IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'federation JIT provisioning is invalid' USING ERRCODE = 'A0003';
        END IF;
        provisioned_user := jit_provisioning->'user';
        provisioned_membership := jit_provisioning->'membership';
        IF jsonb_typeof(provisioned_user) IS DISTINCT FROM 'object' OR
           jsonb_typeof(provisioned_membership) IS DISTINCT FROM 'object' OR
           provisioned_user->>'state' IS DISTINCT FROM 'active' OR
           (provisioned_user->>'version')::BIGINT IS DISTINCT FROM 0 OR
           provisioned_user->>'primaryEmail' IS NOT NULL OR
           provisioned_membership->>'userId' IS DISTINCT FROM provisioned_user->>'id' OR
           provisioned_membership->>'organizationId' IS DISTINCT FROM lease->>'organizationId' OR
           provisioned_membership->>'role' IS DISTINCT FROM 'viewer' OR
           provisioned_membership->>'state' IS DISTINCT FROM 'active' OR
           (provisioned_membership->>'version')::BIGINT IS DISTINCT FROM 0 OR
           identity_document->>'userId' IS DISTINCT FROM provisioned_user->>'id' OR
           (identity_document->>'createdAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (identity_document->>'updatedAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (provisioned_user->>'createdAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (provisioned_user->>'updatedAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (provisioned_user->>'activatedAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (provisioned_membership->>'createdAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ OR
           (provisioned_membership->>'updatedAt')::TIMESTAMPTZ IS DISTINCT FROM
               (audit_event->>'occurredAt')::TIMESTAMPTZ THEN
            RAISE EXCEPTION 'federation JIT provisioning is invalid' USING ERRCODE = 'A0003';
        END IF;
    END IF;

    PERFORM aether_identity.insert_external_replay(receipt);
    IF jit_provisioning <> 'null'::JSONB THEN
        PERFORM aether_identity.insert_user(provisioned_user);
        PERFORM aether_identity.insert_membership(provisioned_membership);
    END IF;
    INSERT INTO aether_identity.external_identities(
        id, user_id, provider, subject, state, version, document
    ) VALUES (
        identity_document->>'id', identity_document->>'userId', identity_document->>'provider',
        identity_document->>'subject', identity_document->>'state',
        (identity_document->>'version')::BIGINT, identity_document
    );
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'link_external_identity',
        jsonb_build_object(
            'identity', identity_document,
            'replayReceipt', receipt,
            'auditEvent', audit_event,
            'provisionedUser', provisioned_user,
            'provisionedMembership', provisioned_membership
        )
    );
EXCEPTION
    WHEN unique_violation THEN
        IF EXISTS (
            SELECT 1 FROM aether_identity.external_replay_receipts
             WHERE id = receipt->>'id' OR (
                 provider = receipt->>'provider' AND
                 assertion_digest_algorithm = receipt#>>'{assertionDigest,algorithm}' AND
                 assertion_digest_encoded = receipt#>>'{assertionDigest,encoded}' AND
                 COALESCE(assertion_digest_key_version, '') =
                     COALESCE(receipt#>>'{assertionDigest,keyVersion}', '')
             )
        ) THEN
            RAISE EXCEPTION 'external assertion replayed' USING ERRCODE = 'A0005';
        END IF;
        RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_record_external_identity_replay(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE payload JSONB; receipt JSONB; lease JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'record_external_identity_replay');
    receipt := payload->'replayReceipt';
    lease := payload->'federationProviderLease';
    PERFORM aether_identity_internal.require_federation_provider_lease(lease);
    IF receipt->>'provider' IS DISTINCT FROM lease->>'storageKey' THEN
        RAISE EXCEPTION 'external replay provider is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_external_replay(receipt);
    RETURN aether_identity.rpc_success('record_external_identity_replay', receipt);
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'external assertion replayed' USING ERRCODE = 'A0005';
END;
$$;

-- A provider disable discovered while a passkey step-up rotates a federated predecessor is a
-- deterministic ceremony rejection. Extend V010's terminal-attempt resolver without modifying
-- the reviewed earlier migration.
CREATE OR REPLACE FUNCTION aether_identity_internal.web_authn_store_error_code(p_sqlstate TEXT)
RETURNS TEXT
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT CASE p_sqlstate
        WHEN 'A0002' THEN 'version_conflict'
        WHEN 'A0003' THEN 'invalid_transition'
        WHEN 'A0004' THEN 'last_owner'
        WHEN 'A0005' THEN 'replay_detected'
        WHEN 'A0006' THEN 'idempotency_conflict'
        WHEN 'A0009' THEN 'session_not_active'
        WHEN 'A0010' THEN 'session_expired'
        WHEN 'A0011' THEN 'recovery_code_not_active'
        WHEN 'A0012' THEN 'not_found'
        WHEN 'A0013' THEN 'already_exists'
        WHEN 'A0015' THEN 'federation_provider_disabled'
        WHEN '23505' THEN 'unique_constraint'
        ELSE 'invalid_transition'
    END;
$$;
REVOKE ALL ON FUNCTION aether_identity_internal.web_authn_store_error_code(TEXT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION aether_identity_internal.resolve_web_authn_attempt(
    p_request JSONB,
    p_operation TEXT
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    attempted_at TIMESTAMPTZ;
    rejection_audit JSONB;
    stored_challenge aether_identity.challenges%ROWTYPE;
    completion_response JSONB;
    failed_challenge JSONB;
    rejected_sqlstate TEXT;
BEGIN
    IF p_operation NOT IN (
        'complete_credential_registration',
        'complete_credential_authentication',
        'quarantine_credential_authentication',
        'complete_recovery_enrollment'
    ) THEN
        RAISE EXCEPTION 'invalid WebAuthn completion operation' USING ERRCODE = 'A0014';
    END IF;

    payload := aether_identity.rpc_payload(p_request, p_operation);
    attempted_at := CASE p_operation
        WHEN 'complete_credential_registration' THEN
            (payload#>>'{auditEvent,occurredAt}')::TIMESTAMPTZ
        WHEN 'complete_credential_authentication' THEN
            (payload->>'authenticatedAt')::TIMESTAMPTZ
        WHEN 'quarantine_credential_authentication' THEN
            (payload->>'detectedAt')::TIMESTAMPTZ
        WHEN 'complete_recovery_enrollment' THEN
            (payload->>'completedAt')::TIMESTAMPTZ
    END;
    rejection_audit := payload->'rejectionAuditEvent';

    SELECT * INTO stored_challenge
      FROM aether_identity.challenges
     WHERE id = payload->>'challengeId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'WebAuthn challenge not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_challenge.version <> (payload->>'expectedChallengeVersion')::BIGINT THEN
        RAISE EXCEPTION 'WebAuthn challenge version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_challenge.state <> 'pending' THEN
        RAISE EXCEPTION 'WebAuthn challenge is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored_challenge.expires_at <= attempted_at THEN
        RAISE EXCEPTION 'WebAuthn challenge expired' USING ERRCODE = 'A0008';
    END IF;
    IF attempted_at IS NULL OR jsonb_typeof(rejection_audit) IS DISTINCT FROM 'object' OR
       rejection_audit->>'action' IS DISTINCT FROM 'webauthn.ceremony_rejected' OR
       rejection_audit->>'outcome' IS DISTINCT FROM 'denied' OR
       rejection_audit#>>'{target,type}' IS DISTINCT FROM 'challenge' OR
       rejection_audit#>>'{target,id}' IS DISTINCT FROM stored_challenge.id OR
       rejection_audit->>'reasonCode' IS DISTINCT FROM 'webauthn_store_rejected' OR
       (rejection_audit->>'occurredAt')::TIMESTAMPTZ IS DISTINCT FROM attempted_at THEN
        RAISE EXCEPTION 'invalid WebAuthn rejection audit' USING ERRCODE = 'A0014';
    END IF;

    BEGIN
        EXECUTE format(
            'SELECT aether_identity_internal.%I($1)',
            'v1_' || p_operation
        ) INTO completion_response USING p_request;
        RETURN aether_identity.rpc_success(
            p_operation,
            jsonb_build_object(
                'completion', completion_response->'result',
                'rejection', 'null'::JSONB
            )
        );
    EXCEPTION
        WHEN SQLSTATE 'A0002' OR SQLSTATE 'A0003' OR SQLSTATE 'A0004' OR
             SQLSTATE 'A0005' OR SQLSTATE 'A0006' OR SQLSTATE 'A0009' OR
             SQLSTATE 'A0010' OR SQLSTATE 'A0011' OR SQLSTATE 'A0012' OR
             SQLSTATE 'A0013' OR SQLSTATE 'A0015' OR
             integrity_constraint_violation OR data_exception THEN
            GET STACKED DIAGNOSTICS rejected_sqlstate = RETURNED_SQLSTATE;
    END;

    failed_challenge := aether_identity.consume_challenge_model(
        stored_challenge.id,
        stored_challenge.version,
        'failed',
        attempted_at
    );
    PERFORM aether_identity.record_audit(rejection_audit);
    RETURN aether_identity.rpc_success(
        p_operation,
        jsonb_build_object(
            'completion', 'null'::JSONB,
            'rejection', jsonb_build_object(
                'challenge', failed_challenge,
                'error', jsonb_build_object(
                    'code', aether_identity_internal.web_authn_store_error_code(rejected_sqlstate),
                    'retryable', FALSE
                ),
                'auditEvent', rejection_audit
            )
        )
    );
END;
$$;
REVOKE ALL ON FUNCTION aether_identity_internal.resolve_web_authn_attempt(JSONB, TEXT) FROM PUBLIC;

-- V002 exposed this compatibility RPC through PostgreSQL's default PUBLIC function privilege.
-- The storage adapter no longer has a corresponding operation; retain the function for upgrades
-- while making it unreachable to application and PostgREST roles unless explicitly granted.
REVOKE ALL ON FUNCTION aether_identity.v1_revoke_federated_sessions(JSONB) FROM PUBLIC;

DO $$
BEGIN
    IF has_function_privilege(
        'public',
        'aether_identity_internal.federation_provider_lease(jsonb)',
        'EXECUTE'
    ) OR has_function_privilege(
        'public',
        'aether_identity_internal.require_federation_provider_lease(jsonb)',
        'EXECUTE'
    ) OR has_function_privilege(
        'public',
        'aether_identity_internal.require_federated_session(jsonb)',
        'EXECUTE'
    ) OR has_function_privilege(
        'public',
        'aether_identity.v1_revoke_federated_sessions(jsonb)',
        'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'obsolete or internal federation functions remain publicly reachable';
    END IF;
END;
$$;
