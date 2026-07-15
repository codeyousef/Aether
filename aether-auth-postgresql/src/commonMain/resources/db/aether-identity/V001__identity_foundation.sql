CREATE SCHEMA IF NOT EXISTS aether_identity;

CREATE TABLE IF NOT EXISTS aether_identity.schema_migrations (
    module TEXT NOT NULL,
    version INTEGER NOT NULL,
    checksum TEXT NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (module, version)
);

CREATE TABLE IF NOT EXISTS aether_identity.environment (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    environment TEXT NOT NULL CHECK (environment IN ('development', 'test', 'staging', 'production')),
    namespace TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS aether_identity.bootstrap_receipts (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton),
    digest_algorithm TEXT NOT NULL CHECK (digest_algorithm = 'sha256'),
    digest_encoded TEXT NOT NULL UNIQUE,
    consumed_at TIMESTAMPTZ NOT NULL
);

-- Canonical model JSON is retained alongside relational lookup/CAS columns. This keeps the wire
-- representation lossless while the columns below enforce tenant, uniqueness, and state rules.
CREATE TABLE IF NOT EXISTS aether_identity.users (
    id TEXT PRIMARY KEY,
    primary_email TEXT,
    state TEXT NOT NULL CHECK (state IN ('pending', 'active', 'suspended', 'deactivated')),
    session_epoch BIGINT NOT NULL CHECK (session_epoch >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK ((document->>'sessionEpoch')::BIGINT = session_epoch),
    CHECK (document->>'state' = state)
);

CREATE UNIQUE INDEX IF NOT EXISTS users_primary_email_normalized_idx
    ON aether_identity.users (lower(primary_email))
    WHERE primary_email IS NOT NULL;

CREATE TABLE IF NOT EXISTS aether_identity.credentials (
    id TEXT PRIMARY KEY,
    web_authn_id TEXT NOT NULL UNIQUE,
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    state TEXT NOT NULL CHECK (state IN ('active', 'suspended', 'suspected_clone', 'revoked')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'webAuthnId' = web_authn_id),
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS credentials_user_idx
    ON aether_identity.credentials (user_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.sessions (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL,
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    token_digest_algorithm TEXT NOT NULL CHECK (token_digest_algorithm IN ('sha256', 'hmac_sha256')),
    token_digest_encoded TEXT NOT NULL,
    token_digest_key_version TEXT,
    csrf_digest_algorithm TEXT NOT NULL CHECK (csrf_digest_algorithm IN ('sha256', 'hmac_sha256')),
    csrf_digest_encoded TEXT NOT NULL,
    csrf_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'rotated', 'revoked', 'expired')),
    user_session_epoch BIGINT NOT NULL CHECK (user_session_epoch >= 0),
    version BIGINT NOT NULL CHECK (version >= 0),
    idle_expires_at TIMESTAMPTZ NOT NULL,
    absolute_expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'familyId' = family_id),
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK ((document->>'userSessionEpoch')::BIGINT = user_session_epoch),
    CHECK (document->>'state' = state),
    CHECK (idle_expires_at <= absolute_expires_at)
);

CREATE INDEX IF NOT EXISTS sessions_user_idx
    ON aether_identity.sessions (user_id, id);

CREATE INDEX IF NOT EXISTS sessions_family_idx
    ON aether_identity.sessions (family_id, id);

CREATE UNIQUE INDEX IF NOT EXISTS sessions_token_digest_idx
    ON aether_identity.sessions (
        token_digest_algorithm, token_digest_encoded, COALESCE(token_digest_key_version, '')
    );

CREATE UNIQUE INDEX IF NOT EXISTS sessions_csrf_digest_idx
    ON aether_identity.sessions (
        csrf_digest_algorithm, csrf_digest_encoded, COALESCE(csrf_digest_key_version, '')
    );

CREATE TABLE IF NOT EXISTS aether_identity.organizations (
    id TEXT PRIMARY KEY,
    slug TEXT NOT NULL UNIQUE,
    state TEXT NOT NULL CHECK (state IN ('active', 'suspended', 'deleted')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'slug' = slug),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE TABLE IF NOT EXISTS aether_identity.memberships (
    id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    role TEXT NOT NULL CHECK (role IN ('owner', 'admin', 'publisher', 'viewer')),
    state TEXT NOT NULL CHECK (state IN ('active', 'suspended', 'removed')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    UNIQUE (organization_id, user_id),
    CHECK (document->>'id' = id),
    CHECK (document->>'organizationId' = organization_id),
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'role' = role),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS memberships_user_idx
    ON aether_identity.memberships (user_id, organization_id);

CREATE TABLE IF NOT EXISTS aether_identity.invitations (
    id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    email TEXT NOT NULL,
    token_digest_algorithm TEXT NOT NULL CHECK (token_digest_algorithm IN ('sha256', 'hmac_sha256')),
    token_digest_encoded TEXT NOT NULL,
    token_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('pending', 'accepted', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'organizationId' = organization_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE UNIQUE INDEX IF NOT EXISTS invitations_token_digest_idx
    ON aether_identity.invitations (
        token_digest_algorithm, token_digest_encoded, COALESCE(token_digest_key_version, '')
    );

CREATE UNIQUE INDEX IF NOT EXISTS invitations_pending_organization_email_idx
    ON aether_identity.invitations (organization_id, lower(email))
    WHERE state = 'pending';

CREATE INDEX IF NOT EXISTS invitations_organization_idx
    ON aether_identity.invitations (organization_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.service_identities (
    id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    name TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active', 'suspended', 'revoked')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    UNIQUE (organization_id, name),
    CHECK (document->>'id' = id),
    CHECK (document->>'organizationId' = organization_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS service_identities_organization_idx
    ON aether_identity.service_identities (organization_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.service_credentials (
    id TEXT PRIMARY KEY,
    service_identity_id TEXT NOT NULL REFERENCES aether_identity.service_identities(id),
    public_prefix TEXT NOT NULL UNIQUE,
    secret_digest_algorithm TEXT NOT NULL CHECK (secret_digest_algorithm IN ('sha256', 'hmac_sha256')),
    secret_digest_encoded TEXT NOT NULL,
    secret_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'rotated', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'serviceIdentityId' = service_identity_id),
    CHECK (document->>'publicPrefix' = public_prefix),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE UNIQUE INDEX IF NOT EXISTS service_credentials_secret_digest_idx
    ON aether_identity.service_credentials (
        secret_digest_algorithm, secret_digest_encoded, COALESCE(secret_digest_key_version, '')
    );

CREATE INDEX IF NOT EXISTS service_credentials_identity_idx
    ON aether_identity.service_credentials (service_identity_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.challenges (
    id TEXT PRIMARY KEY,
    purpose TEXT NOT NULL CHECK (purpose IN (
        'webauthn_registration', 'webauthn_authentication', 'step_up', 'account_recovery',
        'invitation_acceptance', 'device_authorization', 'external_identity_link',
        'service_credential_rotation'
    )),
    challenge_digest_algorithm TEXT NOT NULL CHECK (challenge_digest_algorithm IN ('sha256', 'hmac_sha256')),
    challenge_digest_encoded TEXT NOT NULL,
    challenge_digest_key_version TEXT,
    binding_digest_algorithm TEXT NOT NULL CHECK (binding_digest_algorithm IN ('sha256', 'hmac_sha256')),
    binding_digest_encoded TEXT NOT NULL,
    binding_digest_key_version TEXT,
    payload_digest_algorithm TEXT,
    payload_digest_encoded TEXT,
    payload_digest_key_version TEXT,
    user_id TEXT REFERENCES aether_identity.users(id),
    organization_id TEXT REFERENCES aether_identity.organizations(id),
    state TEXT NOT NULL CHECK (state IN ('pending', 'consumed', 'failed', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'purpose' = purpose),
    CHECK (document->>'state' = state),
    CHECK ((payload_digest_algorithm IS NULL) = (payload_digest_encoded IS NULL))
);

CREATE INDEX IF NOT EXISTS challenges_expiry_idx
    ON aether_identity.challenges (expires_at)
    WHERE state = 'pending';

CREATE UNIQUE INDEX IF NOT EXISTS challenges_digest_idx
    ON aether_identity.challenges (
        challenge_digest_algorithm, challenge_digest_encoded, COALESCE(challenge_digest_key_version, '')
    );

CREATE TABLE IF NOT EXISTS aether_identity.recovery_codes (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    generation BIGINT NOT NULL CHECK (generation >= 0),
    public_selector TEXT NOT NULL UNIQUE,
    secret_digest_algorithm TEXT NOT NULL CHECK (secret_digest_algorithm IN ('sha256', 'hmac_sha256')),
    secret_digest_encoded TEXT NOT NULL,
    secret_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'consumed', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'generation')::BIGINT = generation),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'publicSelector' = public_selector),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS recovery_codes_user_generation_idx
    ON aether_identity.recovery_codes (user_id, generation, id);

CREATE UNIQUE INDEX IF NOT EXISTS recovery_codes_secret_digest_idx
    ON aether_identity.recovery_codes (
        secret_digest_algorithm, secret_digest_encoded, COALESCE(secret_digest_key_version, '')
    );

CREATE TABLE IF NOT EXISTS aether_identity.device_grants (
    id TEXT PRIMARY KEY,
    device_digest_algorithm TEXT NOT NULL CHECK (device_digest_algorithm IN ('sha256', 'hmac_sha256')),
    device_digest_encoded TEXT NOT NULL,
    device_digest_key_version TEXT,
    user_digest_algorithm TEXT NOT NULL CHECK (user_digest_algorithm IN ('sha256', 'hmac_sha256')),
    user_digest_encoded TEXT NOT NULL,
    user_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('pending', 'authorized', 'denied', 'consumed', 'expired', 'cancelled')),
    version BIGINT NOT NULL CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE UNIQUE INDEX IF NOT EXISTS device_grants_device_digest_idx
    ON aether_identity.device_grants (
        device_digest_algorithm, device_digest_encoded, COALESCE(device_digest_key_version, '')
    );

CREATE UNIQUE INDEX IF NOT EXISTS device_grants_user_digest_idx
    ON aether_identity.device_grants (
        user_digest_algorithm, user_digest_encoded, COALESCE(user_digest_key_version, '')
    );

CREATE TABLE IF NOT EXISTS aether_identity.device_grant_digest_reservations (
    digest_algorithm TEXT NOT NULL CHECK (digest_algorithm IN ('sha256', 'hmac_sha256')),
    digest_encoded TEXT NOT NULL,
    digest_key_version TEXT NOT NULL,
    grant_id TEXT NOT NULL REFERENCES aether_identity.device_grants(id) ON DELETE CASCADE,
    digest_kind TEXT NOT NULL CHECK (digest_kind IN ('device', 'user')),
    PRIMARY KEY (digest_algorithm, digest_encoded, digest_key_version),
    UNIQUE (grant_id, digest_kind)
);

CREATE TABLE IF NOT EXISTS aether_identity.device_token_families (
    id TEXT PRIMARY KEY,
    device_grant_id TEXT NOT NULL REFERENCES aether_identity.device_grants(id),
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    state TEXT NOT NULL CHECK (state IN ('active', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'deviceGrantId' = device_grant_id),
    CHECK (document->>'userId' = user_id),
    CHECK (document->>'organizationId' = organization_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS device_token_families_user_organization_idx
    ON aether_identity.device_token_families (user_id, organization_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.device_access_tokens (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES aether_identity.device_token_families(id),
    public_selector TEXT NOT NULL,
    secret_digest_algorithm TEXT NOT NULL CHECK (secret_digest_algorithm IN ('sha256', 'hmac_sha256')),
    secret_digest_encoded TEXT NOT NULL,
    secret_digest_key_version TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'familyId' = family_id),
    CHECK (document->>'publicSelector' = public_selector),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS device_access_tokens_family_idx
    ON aether_identity.device_access_tokens (family_id, id);

CREATE TABLE IF NOT EXISTS aether_identity.device_refresh_tokens (
    id TEXT PRIMARY KEY,
    family_id TEXT NOT NULL REFERENCES aether_identity.device_token_families(id),
    public_selector TEXT NOT NULL,
    secret_digest_algorithm TEXT NOT NULL CHECK (secret_digest_algorithm IN ('sha256', 'hmac_sha256')),
    secret_digest_encoded TEXT NOT NULL,
    secret_digest_key_version TEXT,
    rotation_counter BIGINT NOT NULL CHECK (rotation_counter >= 0),
    state TEXT NOT NULL CHECK (state IN ('active', 'rotated', 'revoked', 'expired')),
    version BIGINT NOT NULL CHECK (version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'familyId' = family_id),
    CHECK (document->>'publicSelector' = public_selector),
    CHECK ((document->>'rotationCounter')::BIGINT = rotation_counter),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state)
);

CREATE INDEX IF NOT EXISTS device_refresh_tokens_family_idx
    ON aether_identity.device_refresh_tokens (family_id, id);

-- A single reservation surface prevents selector or digest reuse across access and refresh tokens.
CREATE TABLE IF NOT EXISTS aether_identity.device_token_credential_reservations (
    token_kind TEXT NOT NULL CHECK (token_kind IN ('access', 'refresh')),
    token_id TEXT NOT NULL,
    public_selector TEXT NOT NULL UNIQUE,
    digest_algorithm TEXT NOT NULL CHECK (digest_algorithm IN ('sha256', 'hmac_sha256')),
    digest_encoded TEXT NOT NULL,
    digest_key_version TEXT NOT NULL,
    PRIMARY KEY (token_kind, token_id),
    UNIQUE (digest_algorithm, digest_encoded, digest_key_version)
);

CREATE TABLE IF NOT EXISTS aether_identity.external_identities (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES aether_identity.users(id),
    provider TEXT NOT NULL,
    subject TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('active', 'suspended', 'unlinked')),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    UNIQUE (provider, subject),
    CHECK (document->>'id' = id),
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'provider' = provider),
    CHECK (document->>'subject' = subject),
    CHECK (document->>'state' = state)
);

CREATE TABLE IF NOT EXISTS aether_identity.external_replay_receipts (
    id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    assertion_digest_algorithm TEXT NOT NULL CHECK (assertion_digest_algorithm IN ('sha256', 'hmac_sha256')),
    assertion_digest_encoded TEXT NOT NULL,
    assertion_digest_key_version TEXT,
    expires_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'provider' = provider)
);

CREATE UNIQUE INDEX IF NOT EXISTS external_replay_receipts_digest_idx
    ON aether_identity.external_replay_receipts (
        provider, assertion_digest_algorithm, assertion_digest_encoded, COALESCE(assertion_digest_key_version, '')
    );

CREATE TABLE IF NOT EXISTS aether_identity.audit_events (
    id TEXT PRIMARY KEY,
    organization_id TEXT REFERENCES aether_identity.organizations(id),
    action TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('succeeded', 'denied', 'failed')),
    occurred_at TIMESTAMPTZ NOT NULL,
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'action' = action),
    CHECK (document->>'outcome' = outcome)
);

CREATE INDEX IF NOT EXISTS audit_events_organization_time_idx
    ON aether_identity.audit_events (organization_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS aether_identity.scim_operations (
    operation_id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    mutation JSONB NOT NULL,
    commit_result JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION aether_identity.rpc_success(p_operation TEXT, p_result JSONB)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT jsonb_build_object(
        'protocolVersion', 1,
        'operation', p_operation,
        'outcome', 'success',
        'result', COALESCE(p_result, 'null'::JSONB)
    );
$$;

CREATE OR REPLACE FUNCTION aether_identity.assert_environment(
    p_environment TEXT,
    p_namespace TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored aether_identity.environment%ROWTYPE;
BEGIN
    INSERT INTO aether_identity.environment(singleton, environment, namespace)
    VALUES (TRUE, p_environment, p_namespace)
    ON CONFLICT (singleton) DO NOTHING;

    SELECT * INTO stored FROM aether_identity.environment WHERE singleton = TRUE;
    IF stored.environment <> p_environment OR stored.namespace <> p_namespace THEN
        RAISE EXCEPTION 'identity environment mismatch' USING ERRCODE = 'A0001';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.rpc_payload(
    p_request JSONB,
    p_expected_operation TEXT
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_request IS NULL OR
       jsonb_typeof(p_request) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'invalid identity rpc envelope root' USING ERRCODE = 'A0014';
    ELSIF jsonb_typeof(p_request->'protocolVersion') IS DISTINCT FROM 'number' OR
          p_request->>'protocolVersion' <> '1' THEN
        RAISE EXCEPTION 'invalid identity rpc protocol version' USING ERRCODE = 'A0014';
    ELSIF p_request->>'operation' IS DISTINCT FROM p_expected_operation THEN
        RAISE EXCEPTION 'invalid identity rpc operation' USING ERRCODE = 'A0014';
    ELSIF p_request->>'namespace' IS NULL OR p_request->>'environment' IS NULL THEN
        RAISE EXCEPTION 'invalid identity rpc environment marker' USING ERRCODE = 'A0014';
    ELSIF jsonb_typeof(p_request->'payload') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'invalid identity rpc payload' USING ERRCODE = 'A0014';
    END IF;
    PERFORM aether_identity.assert_environment(
        p_request->>'environment',
        p_request->>'namespace'
    );
    RETURN p_request->'payload';
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.record_audit(p_event JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO aether_identity.audit_events(id, organization_id, action, outcome, occurred_at, document)
    VALUES (
        p_event->>'id',
        NULLIF(p_event->>'organizationId', ''),
        p_event->>'action',
        p_event->>'outcome',
        (p_event->>'occurredAt')::TIMESTAMPTZ,
        p_event
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_user(p_user JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF (p_user->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new user version is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.users(id, primary_email, state, session_epoch, version, document)
    VALUES (
        p_user->>'id', NULLIF(p_user->>'primaryEmail', ''), p_user->>'state',
        (p_user->>'sessionEpoch')::BIGINT, (p_user->>'version')::BIGINT, p_user
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.replace_user(p_user JSONB, p_expected_version BIGINT)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF (p_user->>'version')::BIGINT <> p_expected_version + 1 THEN
        RAISE EXCEPTION 'replacement user version is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.users
       SET primary_email = NULLIF(p_user->>'primaryEmail', ''),
           state = p_user->>'state',
           session_epoch = (p_user->>'sessionEpoch')::BIGINT,
           version = (p_user->>'version')::BIGINT,
           document = p_user
     WHERE id = p_user->>'id' AND version = p_expected_version;
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM aether_identity.users WHERE id = p_user->>'id') THEN
            RAISE EXCEPTION 'user version conflict' USING ERRCODE = 'A0002';
        END IF;
        RAISE EXCEPTION 'user not found' USING ERRCODE = 'A0012';
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_credential(p_credential JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_credential->>'state' <> 'active' OR (p_credential->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new credential is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.credentials(id, web_authn_id, user_id, state, version, document)
    VALUES (
        p_credential->>'id', p_credential->>'webAuthnId', p_credential->>'userId', p_credential->>'state',
        (p_credential->>'version')::BIGINT, p_credential
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_session(p_session JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored_user aether_identity.users%ROWTYPE;
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
    INSERT INTO aether_identity.sessions(
        id, family_id, user_id,
        token_digest_algorithm, token_digest_encoded, token_digest_key_version,
        csrf_digest_algorithm, csrf_digest_encoded, csrf_digest_key_version,
        state, user_session_epoch, version, idle_expires_at, absolute_expires_at, document
    ) VALUES (
        p_session->>'id', p_session->>'familyId', p_session->>'userId',
        p_session#>>'{tokenDigest,algorithm}', p_session#>>'{tokenDigest,encoded}', p_session#>>'{tokenDigest,keyVersion}',
        p_session#>>'{csrfDigest,algorithm}', p_session#>>'{csrfDigest,encoded}', p_session#>>'{csrfDigest,keyVersion}',
        p_session->>'state', (p_session->>'userSessionEpoch')::BIGINT, (p_session->>'version')::BIGINT,
        (p_session->>'idleExpiresAt')::TIMESTAMPTZ, (p_session->>'absoluteExpiresAt')::TIMESTAMPTZ, p_session
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_membership(p_membership JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_membership->>'state' <> 'active' OR (p_membership->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new membership is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.memberships(
        id, organization_id, user_id, role, state, version, document
    ) VALUES (
        p_membership->>'id', p_membership->>'organizationId', p_membership->>'userId',
        p_membership->>'role', p_membership->>'state', (p_membership->>'version')::BIGINT, p_membership
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_recovery_code(p_code JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_code->>'state' <> 'active' OR (p_code->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new recovery code is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.recovery_codes(
        id, user_id, generation, public_selector,
        secret_digest_algorithm, secret_digest_encoded, secret_digest_key_version,
        state, version, document
    ) VALUES (
        p_code->>'id', p_code->>'userId', (p_code->>'generation')::BIGINT, p_code->>'publicSelector',
        p_code#>>'{secretDigest,algorithm}', p_code#>>'{secretDigest,encoded}', p_code#>>'{secretDigest,keyVersion}',
        p_code->>'state', (p_code->>'version')::BIGINT, p_code
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_service_credential(p_credential JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_credential->>'state' <> 'active' OR (p_credential->>'version')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'new service credential is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.service_credentials(
        id, service_identity_id, public_prefix,
        secret_digest_algorithm, secret_digest_encoded, secret_digest_key_version,
        state, version, document
    ) VALUES (
        p_credential->>'id', p_credential->>'serviceIdentityId', p_credential->>'publicPrefix',
        p_credential#>>'{secretDigest,algorithm}', p_credential#>>'{secretDigest,encoded}',
        p_credential#>>'{secretDigest,keyVersion}', p_credential->>'state',
        (p_credential->>'version')::BIGINT, p_credential
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_external_replay(p_receipt JSONB)
RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO aether_identity.external_replay_receipts(
        id, provider, assertion_digest_algorithm, assertion_digest_encoded,
        assertion_digest_key_version, expires_at, document
    ) VALUES (
        p_receipt->>'id', p_receipt->>'provider', p_receipt#>>'{assertionDigest,algorithm}',
        p_receipt#>>'{assertionDigest,encoded}', p_receipt#>>'{assertionDigest,keyVersion}',
        (p_receipt->>'expiresAt')::TIMESTAMPTZ, p_receipt
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.consume_challenge_model(
    p_id TEXT,
    p_expected_version BIGINT,
    p_terminal_state TEXT,
    p_consumed_at TIMESTAMPTZ
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    stored aether_identity.challenges%ROWTYPE;
    replacement JSONB;
BEGIN
    SELECT * INTO stored FROM aether_identity.challenges WHERE id = p_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'challenge not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored.version <> p_expected_version THEN
        RAISE EXCEPTION 'challenge version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'pending' THEN
        RAISE EXCEPTION 'challenge is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored.expires_at <= p_consumed_at THEN
        RAISE EXCEPTION 'challenge expired' USING ERRCODE = 'A0008';
    END IF;

    replacement := stored.document || jsonb_build_object(
        'state', p_terminal_state,
        'consumedAt', to_jsonb(p_consumed_at),
        'attemptCount', (stored.document->>'attemptCount')::INTEGER +
            CASE WHEN p_terminal_state = 'failed' THEN 1 ELSE 0 END,
        'version', stored.version + 1
    );
    UPDATE aether_identity.challenges
       SET state = p_terminal_state, version = stored.version + 1, document = replacement
     WHERE id = p_id;
    RETURN replacement;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_assert_environment(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM aether_identity.rpc_payload(p_request, 'assert_environment');
    RETURN aether_identity.rpc_success(
        'assert_environment',
        jsonb_build_object(
            'verified', TRUE,
            'environment', p_request->>'environment',
            'namespace', p_request->>'namespace'
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_user');
    SELECT document INTO result FROM aether_identity.users WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_user_by_email(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_user_by_email');
    SELECT document INTO result FROM aether_identity.users WHERE primary_email = payload->>'email';
    RETURN aether_identity.rpc_success('find_user_by_email', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_credential(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_credential');
    SELECT document INTO result FROM aether_identity.credentials WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_credential', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_credential_by_web_authn_id(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_credential_by_web_authn_id');
    SELECT document INTO result FROM aether_identity.credentials
     WHERE web_authn_id = payload->>'webAuthnId';
    RETURN aether_identity.rpc_success('find_credential_by_web_authn_id', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_credentials_for_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_credentials_for_user');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.credentials WHERE user_id = payload->>'userId';
    RETURN aether_identity.rpc_success('list_credentials_for_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_session(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_session');
    SELECT document INTO result FROM aether_identity.sessions WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_session', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_sessions_for_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_sessions_for_user');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.sessions WHERE user_id = payload->>'userId';
    RETURN aether_identity.rpc_success('list_sessions_for_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_organization');
    SELECT document INTO result FROM aether_identity.organizations WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_organization', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_membership(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_membership');
    SELECT document INTO result FROM aether_identity.memberships WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_membership', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_membership_for_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_membership_for_user');
    SELECT document INTO result FROM aether_identity.memberships
     WHERE user_id = payload->>'userId' AND organization_id = payload->>'organizationId';
    RETURN aether_identity.rpc_success('find_membership_for_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_invitation(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_invitation');
    SELECT document INTO result FROM aether_identity.invitations WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_invitation', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_service_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_service_identity');
    SELECT document INTO result FROM aether_identity.service_identities WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_service_identity', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_service_credential_by_prefix(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_service_credential_by_prefix');
    SELECT document INTO result FROM aether_identity.service_credentials
     WHERE public_prefix = payload->>'publicPrefix';
    RETURN aether_identity.rpc_success('find_service_credential_by_prefix', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_external_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_external_identity');
    SELECT document INTO result FROM aether_identity.external_identities
     WHERE provider = payload->>'provider' AND subject = payload->>'subject';
    RETURN aether_identity.rpc_success('find_external_identity', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_challenge(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_challenge');
    SELECT document INTO result FROM aether_identity.challenges WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_challenge', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_recovery_code_by_selector(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_recovery_code_by_selector');
    SELECT document INTO result FROM aether_identity.recovery_codes
     WHERE public_selector = payload->>'publicSelector';
    RETURN aether_identity.rpc_success('find_recovery_code_by_selector', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_grant(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_grant');
    SELECT document INTO result FROM aether_identity.device_grants WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_device_grant', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_challenge(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; challenge JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_challenge');
    challenge := payload->'challenge';
    IF challenge->>'state' <> 'pending' OR
       (challenge->>'version')::BIGINT <> 0 OR
       (challenge->>'attemptCount')::INTEGER <> 0 THEN
        RAISE EXCEPTION 'new challenge is invalid' USING ERRCODE = 'A0003';
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
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; challenge JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'consume_challenge');
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

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_credential_registration(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; challenge JSONB; credential JSONB; user_document JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'complete_credential_registration');
    credential := payload->'credential';
    user_document := payload->'user';
    audit_event := payload->'auditEvent';

    challenge := aether_identity.consume_challenge_model(
        payload->>'challengeId',
        (payload->>'expectedChallengeVersion')::BIGINT,
        'consumed',
        (audit_event->>'occurredAt')::TIMESTAMPTZ
    );
    IF challenge->>'purpose' <> 'webauthn_registration' OR
       (challenge->'userId' <> 'null'::JSONB AND challenge->>'userId' <> credential->>'userId') THEN
        RAISE EXCEPTION 'registration challenge is invalid' USING ERRCODE = 'A0003';
    END IF;

    IF user_document IS NOT NULL AND user_document <> 'null'::JSONB THEN
        IF (payload->>'expectedUserVersion')::BIGINT = -1 THEN
            PERFORM aether_identity.insert_user(user_document);
        ELSE
            PERFORM aether_identity.replace_user(user_document, (payload->>'expectedUserVersion')::BIGINT);
        END IF;
    ELSIF NOT EXISTS (
        SELECT 1 FROM aether_identity.users WHERE id = credential->>'userId'
    ) THEN
        RAISE EXCEPTION 'credential user not found' USING ERRCODE = 'A0012';
    END IF;
    PERFORM aether_identity.insert_credential(credential);
    PERFORM aether_identity.record_audit(audit_event);

    RETURN aether_identity.rpc_success(
        'complete_credential_registration',
        jsonb_build_object(
            'challenge', challenge,
            'credential', credential,
            'user', user_document,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_credential_authentication(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    challenge JSONB;
    stored_user aether_identity.users%ROWTYPE;
    stored_credential aether_identity.credentials%ROWTYPE;
    credential JSONB;
    session_document JSONB;
    replaced_session aether_identity.sessions%ROWTYPE;
    replaced_document JSONB := NULL;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'complete_credential_authentication');
    session_document := payload->'session';
    audit_event := payload->'auditEvent';

    SELECT * INTO stored_user FROM aether_identity.users
     WHERE id = session_document->>'userId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'authentication user not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_user.state <> 'active' OR
       stored_user.session_epoch <> (session_document->>'userSessionEpoch')::BIGINT OR
       (session_document->>'createdAt')::TIMESTAMPTZ <>
           (payload->>'authenticatedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'authentication session epoch is invalid' USING ERRCODE = 'A0003';
    END IF;

    SELECT * INTO stored_credential FROM aether_identity.credentials
     WHERE id = payload->>'credentialId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'credential not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_credential.version <> (payload->>'expectedCredentialVersion')::BIGINT THEN
        RAISE EXCEPTION 'credential version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_credential.state <> 'active' THEN
        RAISE EXCEPTION 'credential transition invalid' USING ERRCODE = 'A0003';
    END IF;
    IF stored_credential.user_id <> stored_user.id OR
       ((stored_credential.document->>'signCount')::BIGINT <> 0 AND
        (payload->>'newSignCount')::BIGINT <> 0 AND
        (payload->>'newSignCount')::BIGINT <= (stored_credential.document->>'signCount')::BIGINT) OR
       (stored_credential.document->>'backupEligible')::BOOLEAN IS DISTINCT FROM
           (payload->>'backupEligible')::BOOLEAN THEN
        RAISE EXCEPTION 'credential authentication transition invalid' USING ERRCODE = 'A0003';
    END IF;

    credential := stored_credential.document || jsonb_build_object(
        'signCount', (payload->>'newSignCount')::BIGINT,
        'backupEligible', (payload->>'backupEligible')::BOOLEAN,
        'backedUp', (payload->>'backedUp')::BOOLEAN,
        'lastUsedAt', payload->'authenticatedAt',
        'updatedAt', payload->'authenticatedAt',
        'version', stored_credential.version + 1
    );
    UPDATE aether_identity.credentials
       SET version = stored_credential.version + 1, document = credential
     WHERE id = stored_credential.id;

    IF payload->'replacedSessionId' IS NOT NULL AND payload->'replacedSessionId' <> 'null'::JSONB THEN
        SELECT * INTO replaced_session FROM aether_identity.sessions
         WHERE id = payload->>'replacedSessionId' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012';
        END IF;
        IF replaced_session.version <> (payload->>'expectedReplacedSessionVersion')::BIGINT THEN
            RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF replaced_session.state <> 'active' THEN
            RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009';
        END IF;
        IF replaced_session.absolute_expires_at <= (payload->>'authenticatedAt')::TIMESTAMPTZ OR
           replaced_session.idle_expires_at <= (payload->>'authenticatedAt')::TIMESTAMPTZ THEN
            RAISE EXCEPTION 'session expired' USING ERRCODE = 'A0010';
        END IF;
        IF replaced_session.user_id <> stored_credential.user_id OR
           session_document->>'rotatedFromId' <> replaced_session.id OR
           session_document->>'familyId' <> replaced_session.family_id OR
           (session_document->>'rotationCounter')::BIGINT <>
               (replaced_session.document->>'rotationCounter')::BIGINT + 1 THEN
            RAISE EXCEPTION 'authentication session rotation is invalid' USING ERRCODE = 'A0003';
        END IF;
        replaced_document := replaced_session.document || jsonb_build_object(
            'state', 'rotated',
            'rotatedToId', session_document->'id',
            'version', replaced_session.version + 1
        );
        UPDATE aether_identity.sessions
           SET state = 'rotated', version = replaced_session.version + 1, document = replaced_document
         WHERE id = replaced_session.id;
    ELSIF session_document->>'familyId' <> session_document->>'id' OR
          session_document->'rotatedFromId' <> 'null'::JSONB OR
          (session_document->>'rotationCounter')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'standalone authentication session is invalid' USING ERRCODE = 'A0003';
    END IF;

    challenge := aether_identity.consume_challenge_model(
        payload->>'challengeId',
        (payload->>'expectedChallengeVersion')::BIGINT,
        'consumed',
        (payload->>'authenticatedAt')::TIMESTAMPTZ
    );
    IF challenge->>'purpose' NOT IN ('webauthn_authentication', 'step_up') OR
       (challenge->'userId' <> 'null'::JSONB AND challenge->>'userId' <> stored_credential.user_id) THEN
        RAISE EXCEPTION 'authentication challenge is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_session(session_document);
    PERFORM aether_identity.record_audit(audit_event);

    RETURN aether_identity.rpc_success(
        'complete_credential_authentication',
        jsonb_build_object(
            'challenge', challenge,
            'credential', credential,
            'session', session_document,
            'replacedSession', replaced_document,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_quarantine_credential_authentication(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    challenge JSONB;
    stored_credential aether_identity.credentials%ROWTYPE;
    credential JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'quarantine_credential_authentication');
    audit_event := payload->'auditEvent';

    SELECT * INTO stored_credential FROM aether_identity.credentials
     WHERE id = payload->>'credentialId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'credential not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_credential.version <> (payload->>'expectedCredentialVersion')::BIGINT THEN
        RAISE EXCEPTION 'credential version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_credential.state <> 'active' OR
       (stored_credential.document->>'signCount')::BIGINT = 0 OR
       (payload->>'observedSignCount')::BIGINT = 0 OR
       (payload->>'observedSignCount')::BIGINT >
           (stored_credential.document->>'signCount')::BIGINT OR
       (stored_credential.document->>'backupEligible')::BOOLEAN IS DISTINCT FROM
           (payload->>'backupEligible')::BOOLEAN THEN
        RAISE EXCEPTION 'credential counter anomaly is invalid' USING ERRCODE = 'A0003';
    END IF;

    challenge := aether_identity.consume_challenge_model(
        payload->>'challengeId',
        (payload->>'expectedChallengeVersion')::BIGINT,
        'consumed',
        (payload->>'detectedAt')::TIMESTAMPTZ
    );
    IF challenge->>'purpose' NOT IN ('webauthn_authentication', 'step_up') OR
       (challenge->'userId' <> 'null'::JSONB AND
        challenge->>'userId' <> stored_credential.user_id) THEN
        RAISE EXCEPTION 'quarantine challenge is invalid' USING ERRCODE = 'A0003';
    END IF;

    credential := stored_credential.document || jsonb_build_object(
        'signCount', (payload->>'observedSignCount')::BIGINT,
        'backupEligible', (payload->>'backupEligible')::BOOLEAN,
        'backedUp', (payload->>'backedUp')::BOOLEAN,
        'state', 'suspected_clone',
        'version', stored_credential.version + 1,
        'updatedAt', payload->'detectedAt',
        'lastUsedAt', payload->'detectedAt',
        'revocationReasonCode', 'signature_counter_anomaly'
    );
    UPDATE aether_identity.credentials
       SET state = 'suspected_clone', version = stored_credential.version + 1, document = credential
     WHERE id = stored_credential.id;
    PERFORM aether_identity.record_audit(audit_event);

    RETURN aether_identity.rpc_success(
        'quarantine_credential_authentication',
        jsonb_build_object('challenge', challenge, 'credential', credential, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_session(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; session_document JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_session');
    session_document := payload->'session';
    IF session_document->>'familyId' <> session_document->>'id' OR
       session_document->'rotatedFromId' <> 'null'::JSONB OR
       (session_document->>'rotationCounter')::BIGINT <> 0 THEN
        RAISE EXCEPTION 'standalone session is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_session(session_document);
    PERFORM aether_identity.record_audit(payload->'auditEvent');
    RETURN aether_identity.rpc_success('create_session', session_document);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_rotate_session(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; stored aether_identity.sessions%ROWTYPE; previous JSONB; replacement JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'rotate_session');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    PERFORM 1 FROM aether_identity.users
     WHERE id = replacement->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session user not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored FROM aether_identity.sessions WHERE id = payload->>'sessionId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009'; END IF;
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

CREATE OR REPLACE FUNCTION aether_identity.v1_revoke_session(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; stored aether_identity.sessions%ROWTYPE; replacement JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'revoke_session');
    SELECT * INTO stored FROM aether_identity.sessions WHERE id = payload->>'sessionId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009'; END IF;
    replacement := stored.document || jsonb_build_object(
        'state', 'revoked',
        'revokedAt', payload->'revokedAt',
        'revocationReasonCode', payload->'reasonCode',
        'version', stored.version + 1
    );
    UPDATE aether_identity.sessions
       SET state = 'revoked', version = stored.version + 1, document = replacement
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(payload->'auditEvent');
    RETURN aether_identity.rpc_success('revoke_session', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_revoke_user_sessions(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    stored_user aether_identity.users%ROWTYPE;
    except_session aether_identity.sessions%ROWTYPE;
    user_document JSONB;
    revoked_ids JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'revoke_user_sessions');
    audit_event := payload->'auditEvent';
    SELECT * INTO stored_user FROM aether_identity.users WHERE id = payload->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'user not found' USING ERRCODE = 'A0012'; END IF;
    IF stored_user.version <> (payload->>'expectedUserVersion')::BIGINT OR
       stored_user.session_epoch <> (payload->>'expectedSessionEpoch')::BIGINT THEN
        RAISE EXCEPTION 'user version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF payload->>'exceptSessionId' IS NOT NULL THEN
        SELECT * INTO except_session FROM aether_identity.sessions
         WHERE id = payload->>'exceptSessionId' FOR UPDATE;
        IF NOT FOUND OR except_session.user_id <> stored_user.id OR except_session.state <> 'active' THEN
            RAISE EXCEPTION 'excepted session is not active' USING ERRCODE = 'A0009';
        END IF;
    END IF;
    user_document := stored_user.document || jsonb_build_object(
        'sessionEpoch', (payload->>'newSessionEpoch')::BIGINT,
        'version', stored_user.version + 1,
        'updatedAt', payload->'revokedAt'
    );
    UPDATE aether_identity.users
       SET session_epoch = (payload->>'newSessionEpoch')::BIGINT,
           version = stored_user.version + 1,
           document = user_document
     WHERE id = stored_user.id;

    WITH revoked AS (
        UPDATE aether_identity.sessions
           SET state = 'revoked',
               version = version + 1,
               document = document || jsonb_build_object(
                   'state', 'revoked', 'revokedAt', payload->'revokedAt',
                   'revocationReasonCode', payload->'reasonCode', 'version', version + 1
               )
         WHERE user_id = stored_user.id
           AND state = 'active'
           AND (payload->>'exceptSessionId' IS NULL OR id <> payload->>'exceptSessionId')
        RETURNING id
    ) SELECT COALESCE(jsonb_agg(id ORDER BY id), '[]'::JSONB) INTO revoked_ids FROM revoked;
    IF payload->>'exceptSessionId' IS NOT NULL THEN
        UPDATE aether_identity.sessions
           SET user_session_epoch = (payload->>'newSessionEpoch')::BIGINT,
               version = version + 1,
               document = document || jsonb_build_object(
                   'userSessionEpoch', (payload->>'newSessionEpoch')::BIGINT,
                   'version', version + 1
               )
         WHERE id = except_session.id;
    END IF;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'revoke_user_sessions',
        jsonb_build_object('user', user_document, 'revokedSessionIds', revoked_ids, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_replace_recovery_codes(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; current_generation BIGINT; code JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'replace_recovery_codes');
    audit_event := payload->'auditEvent';
    PERFORM 1 FROM aether_identity.users WHERE id = payload->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery user not found' USING ERRCODE = 'A0012'; END IF;
    PERFORM 1 FROM aether_identity.recovery_codes
     WHERE user_id = payload->>'userId' FOR UPDATE;
    SELECT MAX(generation) INTO current_generation FROM aether_identity.recovery_codes
     WHERE user_id = payload->>'userId';
    IF current_generation IS DISTINCT FROM (payload->>'expectedGeneration')::BIGINT THEN
        RAISE EXCEPTION 'recovery generation conflict' USING ERRCODE = 'A0002';
    END IF;
    UPDATE aether_identity.recovery_codes
       SET state = 'revoked', version = version + 1,
           document = document || jsonb_build_object('state', 'revoked', 'version', version + 1)
    WHERE user_id = payload->>'userId' AND state = 'active';
    FOR code IN SELECT value FROM jsonb_array_elements(payload->'codes') LOOP
        IF code->>'userId' <> payload->>'userId' OR
           (code->>'generation')::BIGINT <> (payload->>'newGeneration')::BIGINT THEN
            RAISE EXCEPTION 'replacement recovery code is invalid' USING ERRCODE = 'A0003';
        END IF;
        PERFORM aether_identity.insert_recovery_code(code);
    END LOOP;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'replace_recovery_codes',
        jsonb_build_object(
            'generation', (payload->>'newGeneration')::BIGINT,
            'codes', payload->'codes',
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_consume_recovery_code(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; stored aether_identity.recovery_codes%ROWTYPE; code JSONB; session_document JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'consume_recovery_code');
    session_document := payload->'recoverySession';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.recovery_codes
     WHERE id = payload->>'recoveryCodeId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery code not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery code version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' OR
       (stored.document->>'expiresAt' IS NOT NULL AND
        (stored.document->>'expiresAt')::TIMESTAMPTZ <= (payload->>'consumedAt')::TIMESTAMPTZ) THEN
        RAISE EXCEPTION 'recovery code is not active' USING ERRCODE = 'A0011';
    END IF;
    code := stored.document || jsonb_build_object(
        'state', 'consumed', 'consumedAt', payload->'consumedAt', 'version', stored.version + 1
    );
    IF session_document->>'userId' <> stored.user_id OR
       session_document->>'familyId' <> session_document->>'id' OR
       session_document->'rotatedFromId' <> 'null'::JSONB OR
       (session_document->>'rotationCounter')::BIGINT <> 0 OR
       (session_document->>'createdAt')::TIMESTAMPTZ <>
           (payload->>'consumedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'recovery session is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.recovery_codes
       SET state = 'consumed', version = stored.version + 1, document = code WHERE id = stored.id;
    PERFORM aether_identity.insert_session(session_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'consume_recovery_code',
        jsonb_build_object('recoveryCode', code, 'recoverySession', session_document, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_membership(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    membership JSONB;
    invitation aether_identity.invitations%ROWTYPE;
    invitation_document JSONB;
    membership_user aether_identity.users%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_membership');
    membership := payload->'membership';
    IF payload->'invitationId' IS NOT NULL AND payload->'invitationId' <> 'null'::JSONB THEN
        SELECT * INTO invitation FROM aether_identity.invitations
         WHERE id = payload->>'invitationId' FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'invitation not found' USING ERRCODE = 'A0012'; END IF;
        IF invitation.version <> (payload->>'expectedInvitationVersion')::BIGINT THEN
            RAISE EXCEPTION 'invitation version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF invitation.state <> 'pending' OR
           (invitation.document->>'expiresAt')::TIMESTAMPTZ <= (payload#>>'{auditEvent,occurredAt}')::TIMESTAMPTZ THEN
            RAISE EXCEPTION 'invitation transition invalid' USING ERRCODE = 'A0003';
        END IF;
        SELECT * INTO membership_user FROM aether_identity.users
         WHERE id = membership->>'userId';
        IF NOT FOUND THEN RAISE EXCEPTION 'membership user not found' USING ERRCODE = 'A0012'; END IF;
        IF invitation.organization_id <> membership->>'organizationId' OR
           (membership_user.primary_email IS NOT NULL AND
            lower(membership_user.primary_email) <> lower(invitation.email)) THEN
            RAISE EXCEPTION 'invitation does not match membership' USING ERRCODE = 'A0003';
        END IF;
        invitation_document := invitation.document || jsonb_build_object(
            'state', 'accepted',
            'acceptedAt', payload#>'{auditEvent,occurredAt}',
            'acceptedByUserId', membership->'userId',
            'version', invitation.version + 1
        );
        UPDATE aether_identity.invitations
           SET state = 'accepted', version = invitation.version + 1, document = invitation_document
         WHERE id = invitation.id;
    END IF;
    PERFORM aether_identity.insert_membership(membership);
    PERFORM aether_identity.record_audit(payload->'auditEvent');
    RETURN aether_identity.rpc_success('create_membership', membership);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_mutate_membership(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    stored aether_identity.memberships%ROWTYPE;
    replacement JSONB;
    remaining_owners BIGINT;
    locked_organization_id TEXT;
    stored_user aether_identity.users%ROWTYPE;
    replacement_user JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_membership');
    replacement := payload->'replacement';
    SELECT organization_id INTO locked_organization_id
      FROM aether_identity.memberships WHERE id = payload->>'membershipId';
    IF NOT FOUND THEN RAISE EXCEPTION 'membership not found' USING ERRCODE = 'A0012'; END IF;
    PERFORM 1 FROM aether_identity.organizations
     WHERE id = locked_organization_id FOR UPDATE;
    SELECT * INTO stored FROM aether_identity.memberships WHERE id = payload->>'membershipId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'membership not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'membership version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.organization_id <> replacement->>'organizationId' OR stored.user_id <> replacement->>'userId' THEN
        RAISE EXCEPTION 'membership identity changed' USING ERRCODE = 'A0003';
    END IF;
    IF (replacement->>'version')::BIGINT <> stored.version + 1 THEN
        RAISE EXCEPTION 'membership replacement version is invalid' USING ERRCODE = 'A0003';
    END IF;
    IF stored.role = 'owner' AND stored.state = 'active' AND
       NOT (replacement->>'role' = 'owner' AND replacement->>'state' = 'active') THEN
        SELECT COUNT(*) INTO remaining_owners FROM aether_identity.memberships
         WHERE organization_id = stored.organization_id AND id <> stored.id
           AND role = 'owner' AND state = 'active';
        IF remaining_owners = 0 THEN
            RAISE EXCEPTION 'last owner cannot be removed' USING ERRCODE = 'A0004';
        END IF;
    END IF;
    IF payload->'expectedUserVersion' IS NOT NULL AND payload->'expectedUserVersion' <> 'null'::JSONB THEN
        SELECT * INTO stored_user FROM aether_identity.users WHERE id = stored.user_id FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'membership user not found' USING ERRCODE = 'A0012'; END IF;
        IF stored_user.version <> (payload->>'expectedUserVersion')::BIGINT OR
           stored_user.session_epoch <> (payload->>'expectedSessionEpoch')::BIGINT THEN
            RAISE EXCEPTION 'membership user version conflict' USING ERRCODE = 'A0002';
        END IF;
        IF (payload->>'newSessionEpoch')::BIGINT <> stored_user.session_epoch + 1 OR
           payload->>'sessionsRevokedAt' IS NULL OR
           COALESCE(payload->>'sessionRevocationReasonCode', '') = '' OR
           length(payload->>'sessionRevocationReasonCode') > 200 THEN
            RAISE EXCEPTION 'membership session revocation is invalid' USING ERRCODE = 'A0003';
        END IF;
        replacement_user := stored_user.document || jsonb_build_object(
            'sessionEpoch', stored_user.session_epoch + 1,
            'version', stored_user.version + 1,
            'updatedAt', payload->'sessionsRevokedAt'
        );
        UPDATE aether_identity.users
           SET session_epoch = stored_user.session_epoch + 1,
               version = stored_user.version + 1,
               document = replacement_user
         WHERE id = stored_user.id;
        UPDATE aether_identity.sessions
           SET state = 'revoked', version = version + 1,
               document = document || jsonb_build_object(
                   'state', 'revoked',
                   'version', version + 1,
                   'revokedAt', payload->'sessionsRevokedAt',
                   'revocationReasonCode', payload->'sessionRevocationReasonCode'
               )
         WHERE user_id = stored_user.id AND state = 'active';
    ELSIF payload->'expectedSessionEpoch' <> 'null'::JSONB OR
          payload->'newSessionEpoch' <> 'null'::JSONB OR
          payload->'sessionsRevokedAt' <> 'null'::JSONB OR
          payload->'sessionRevocationReasonCode' <> 'null'::JSONB THEN
        RAISE EXCEPTION 'incomplete membership session mutation' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.memberships
       SET role = replacement->>'role', state = replacement->>'state',
           version = (replacement->>'version')::BIGINT, document = replacement
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(payload->'auditEvent');
    RETURN aether_identity.rpc_success('mutate_membership', replacement);
END;
$$;

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
    SELECT * INTO stored FROM aether_identity.device_grants WHERE id = replacement->>'id' FOR UPDATE;
    IF payload->'expectedVersion' IS NULL OR payload->'expectedVersion' = 'null'::JSONB THEN
        IF FOUND THEN RAISE EXCEPTION 'device grant already exists' USING ERRCODE = 'A0013'; END IF;
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

CREATE OR REPLACE FUNCTION aether_identity.v1_rotate_service_credential(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    stored aether_identity.service_credentials%ROWTYPE;
    identity_document JSONB;
    previous JSONB;
    replacement JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'rotate_service_credential');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.service_credentials
     WHERE id = payload->>'credentialId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'service credential not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'service credential version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN
        RAISE EXCEPTION 'service credential transition invalid' USING ERRCODE = 'A0003';
    END IF;
    IF stored.document->>'expiresAt' IS NOT NULL AND
       (stored.document->>'expiresAt')::TIMESTAMPTZ <= (payload->>'rotatedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'service credential expired' USING ERRCODE = 'A0003';
    END IF;
    IF stored.service_identity_id <> replacement->>'serviceIdentityId' THEN
        RAISE EXCEPTION 'service identity changed' USING ERRCODE = 'A0003';
    END IF;
    SELECT document INTO identity_document FROM aether_identity.service_identities
     WHERE id = stored.service_identity_id FOR UPDATE;
    IF NOT FOUND OR identity_document->>'state' <> 'active' OR
       NOT ((identity_document->'capabilities') @> (replacement->'capabilities')) THEN
        RAISE EXCEPTION 'service credential capabilities are invalid' USING ERRCODE = 'A0003';
    END IF;
    previous := stored.document || jsonb_build_object(
        'state', 'rotated', 'rotatedToId', replacement->'id',
        'rotatedAt', payload->'rotatedAt', 'version', stored.version + 1
    );
    UPDATE aether_identity.service_credentials
       SET state = 'rotated', version = stored.version + 1, document = previous
     WHERE id = stored.id;
    PERFORM aether_identity.insert_service_credential(replacement);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'rotate_service_credential',
        jsonb_build_object('previous', previous, 'replacement', replacement, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_link_external_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; identity_document JSONB; receipt JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'link_external_identity');
    identity_document := payload->'identity';
    receipt := payload->'replayReceipt';
    audit_event := payload->'auditEvent';
    IF identity_document->>'state' <> 'active' OR
       (identity_document->>'version')::BIGINT <> 0 OR
       identity_document->>'provider' <> receipt->>'provider' OR
       (receipt->>'expiresAt')::TIMESTAMPTZ <= (audit_event->>'occurredAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'external identity link is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_external_replay(receipt);
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
        jsonb_build_object('identity', identity_document, 'replayReceipt', receipt, 'auditEvent', audit_event)
    );
EXCEPTION
    WHEN unique_violation THEN
        IF EXISTS (
            SELECT 1 FROM aether_identity.external_replay_receipts
             WHERE id = receipt->>'id' OR (
                 provider = receipt->>'provider' AND
                 assertion_digest_algorithm = receipt#>>'{assertionDigest,algorithm}' AND
                 assertion_digest_encoded = receipt#>>'{assertionDigest,encoded}' AND
                 COALESCE(assertion_digest_key_version, '') = COALESCE(receipt#>>'{assertionDigest,keyVersion}', '')
             )
        ) THEN
            RAISE EXCEPTION 'external assertion replayed' USING ERRCODE = 'A0005';
        END IF;
        RAISE;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_record_external_identity_replay(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; receipt JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'record_external_identity_replay');
    receipt := payload->'replayReceipt';
    PERFORM aether_identity.insert_external_replay(receipt);
    RETURN aether_identity.rpc_success('record_external_identity_replay', receipt);
EXCEPTION
    WHEN unique_violation THEN
        RAISE EXCEPTION 'external assertion replayed' USING ERRCODE = 'A0005';
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_apply_scim_mutation(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    mutation_document JSONB;
    audit_event JSONB;
    user_document JSONB;
    membership_document JSONB;
    stored_user aether_identity.users%ROWTYPE;
    current_membership aether_identity.memberships%ROWTYPE;
    existing_mutation JSONB;
    existing_commit JSONB;
    commit_result JSONB;
    remaining_owners BIGINT;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'apply_scim_mutation');
    mutation_document := payload->'mutation';
    audit_event := payload->'auditEvent';

    PERFORM pg_advisory_xact_lock(
        hashtext('aether_identity.scim:' || (mutation_document->>'operationId'))
    );
    SELECT scim_operations.mutation, scim_operations.commit_result INTO existing_mutation, existing_commit
      FROM aether_identity.scim_operations
     WHERE operation_id = mutation_document->>'operationId' FOR UPDATE;
    IF FOUND THEN
        IF existing_mutation <> mutation_document THEN
            RAISE EXCEPTION 'SCIM operation idempotency conflict' USING ERRCODE = 'A0006';
        END IF;
        RETURN aether_identity.rpc_success(
            'apply_scim_mutation',
            existing_commit || jsonb_build_object('alreadyApplied', TRUE, 'auditEvent', 'null'::JSONB)
        );
    END IF;

    IF mutation_document->>'type' IN ('upsert_user', 'deactivate_user') THEN
        user_document := mutation_document->'user';
        membership_document := 'null'::JSONB;
        IF user_document IS NULL OR user_document = 'null'::JSONB OR
           (mutation_document->>'type' = 'deactivate_user' AND user_document->>'state' <> 'deactivated') THEN
            RAISE EXCEPTION 'SCIM user mutation is invalid' USING ERRCODE = 'A0003';
        END IF;
        SELECT * INTO stored_user FROM aether_identity.users WHERE id = user_document->>'id' FOR UPDATE;
        IF NOT FOUND THEN
            PERFORM aether_identity.insert_user(user_document);
        ELSIF (user_document->>'version')::BIGINT = stored_user.version + 1 THEN
            PERFORM aether_identity.replace_user(user_document, stored_user.version);
        ELSE
            RAISE EXCEPTION 'SCIM user version conflict' USING ERRCODE = 'A0002';
        END IF;
    ELSIF mutation_document->>'type' IN ('upsert_membership', 'remove_membership') THEN
        user_document := 'null'::JSONB;
        membership_document := mutation_document->'membership';
        IF membership_document IS NULL OR membership_document = 'null'::JSONB OR
           (mutation_document->>'type' = 'remove_membership' AND membership_document->>'state' <> 'removed') THEN
            RAISE EXCEPTION 'SCIM membership mutation is invalid' USING ERRCODE = 'A0003';
        END IF;
        PERFORM 1 FROM aether_identity.organizations
         WHERE id = membership_document->>'organizationId' FOR UPDATE;
        IF NOT FOUND THEN RAISE EXCEPTION 'SCIM organization not found' USING ERRCODE = 'A0012'; END IF;
        SELECT * INTO current_membership FROM aether_identity.memberships
         WHERE id = membership_document->>'id' FOR UPDATE;
        IF NOT FOUND THEN
            PERFORM aether_identity.insert_membership(membership_document);
        ELSE
            IF current_membership.organization_id <> membership_document->>'organizationId' OR
               current_membership.user_id <> membership_document->>'userId' THEN
                RAISE EXCEPTION 'SCIM membership identity changed' USING ERRCODE = 'A0003';
            END IF;
            IF current_membership.role = 'owner' AND current_membership.state = 'active' AND
               NOT (membership_document->>'role' = 'owner' AND membership_document->>'state' = 'active') THEN
                SELECT COUNT(*) INTO remaining_owners FROM aether_identity.memberships
                 WHERE organization_id = current_membership.organization_id
                   AND id <> current_membership.id AND role = 'owner' AND state = 'active';
                IF remaining_owners = 0 THEN
                    RAISE EXCEPTION 'last owner cannot be removed' USING ERRCODE = 'A0004';
                END IF;
            END IF;
            IF (membership_document->>'version')::BIGINT <> current_membership.version + 1 THEN
                RAISE EXCEPTION 'SCIM membership version conflict' USING ERRCODE = 'A0002';
            END IF;
            UPDATE aether_identity.memberships
               SET role = membership_document->>'role', state = membership_document->>'state',
                   version = (membership_document->>'version')::BIGINT, document = membership_document
             WHERE id = current_membership.id;
        END IF;
    ELSE
        RAISE EXCEPTION 'unsupported SCIM mutation type' USING ERRCODE = 'A0003';
    END IF;

    PERFORM aether_identity.record_audit(audit_event);
    commit_result := jsonb_build_object(
        'user', user_document,
        'membership', membership_document,
        'alreadyApplied', FALSE,
        'auditEvent', audit_event
    );
    INSERT INTO aether_identity.scim_operations(operation_id, provider, mutation, commit_result, occurred_at)
    VALUES (
        mutation_document->>'operationId', mutation_document->>'provider', mutation_document, commit_result,
        (mutation_document->>'occurredAt')::TIMESTAMPTZ
    );
    RETURN aether_identity.rpc_success('apply_scim_mutation', commit_result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_device_token_family(p_family JSONB)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_family->>'state' <> 'active' OR
       (p_family->>'version')::BIGINT <> 0 OR
       length(COALESCE(p_family->>'clientId', '')) NOT BETWEEN 1 AND 200 OR
       p_family->>'clientId' ~ '[^!-~]' OR
       jsonb_typeof(p_family->'capabilities') IS DISTINCT FROM 'array' OR
       jsonb_array_length(p_family->'capabilities') = 0 OR
       (p_family->>'expiresAt')::TIMESTAMPTZ <= (p_family->>'createdAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'new device token family is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.device_token_families(
        id, device_grant_id, user_id, organization_id, state, version, expires_at, document
    ) VALUES (
        p_family->>'id', p_family->>'deviceGrantId', p_family->>'userId',
        p_family->>'organizationId', p_family->>'state', (p_family->>'version')::BIGINT,
        (p_family->>'expiresAt')::TIMESTAMPTZ, p_family
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_device_access_token(p_token JSONB)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_token->>'state' <> 'active' OR
       (p_token->>'version')::BIGINT <> 0 OR
       p_token->>'publicSelector' !~ '^[A-Za-z0-9_-]{6,64}$' OR
       (p_token->>'expiresAt')::TIMESTAMPTZ <= (p_token->>'createdAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'new device access token is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.device_token_credential_reservations(
        token_kind, token_id, public_selector, digest_algorithm, digest_encoded, digest_key_version
    ) VALUES (
        'access', p_token->>'id', p_token->>'publicSelector',
        p_token#>>'{secretDigest,algorithm}', p_token#>>'{secretDigest,encoded}',
        COALESCE(p_token#>>'{secretDigest,keyVersion}', '')
    );
    INSERT INTO aether_identity.device_access_tokens(
        id, family_id, public_selector,
        secret_digest_algorithm, secret_digest_encoded, secret_digest_key_version,
        state, version, expires_at, document
    ) VALUES (
        p_token->>'id', p_token->>'familyId', p_token->>'publicSelector',
        p_token#>>'{secretDigest,algorithm}', p_token#>>'{secretDigest,encoded}',
        p_token#>>'{secretDigest,keyVersion}', p_token->>'state',
        (p_token->>'version')::BIGINT, (p_token->>'expiresAt')::TIMESTAMPTZ, p_token
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.insert_device_refresh_token(p_token JSONB)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_token->>'state' <> 'active' OR
       (p_token->>'version')::BIGINT <> 0 OR
       (p_token->>'rotationCounter')::BIGINT < 0 OR
       p_token->>'publicSelector' !~ '^[A-Za-z0-9_-]{6,64}$' OR
       (p_token->>'expiresAt')::TIMESTAMPTZ <= (p_token->>'createdAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'new device refresh token is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.device_token_credential_reservations(
        token_kind, token_id, public_selector, digest_algorithm, digest_encoded, digest_key_version
    ) VALUES (
        'refresh', p_token->>'id', p_token->>'publicSelector',
        p_token#>>'{secretDigest,algorithm}', p_token#>>'{secretDigest,encoded}',
        COALESCE(p_token#>>'{secretDigest,keyVersion}', '')
    );
    INSERT INTO aether_identity.device_refresh_tokens(
        id, family_id, public_selector,
        secret_digest_algorithm, secret_digest_encoded, secret_digest_key_version,
        rotation_counter, state, version, expires_at, document
    ) VALUES (
        p_token->>'id', p_token->>'familyId', p_token->>'publicSelector',
        p_token#>>'{secretDigest,algorithm}', p_token#>>'{secretDigest,encoded}',
        p_token#>>'{secretDigest,keyVersion}', (p_token->>'rotationCounter')::BIGINT,
        p_token->>'state', (p_token->>'version')::BIGINT,
        (p_token->>'expiresAt')::TIMESTAMPTZ, p_token
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_grant_by_device_code_digest(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_grant_by_device_code_digest');
    SELECT document INTO result FROM aether_identity.device_grants
     WHERE device_digest_algorithm = payload#>>'{digest,algorithm}'
       AND device_digest_encoded = payload#>>'{digest,encoded}'
       AND COALESCE(device_digest_key_version, '') = COALESCE(payload#>>'{digest,keyVersion}', '');
    RETURN aether_identity.rpc_success('find_device_grant_by_device_code_digest', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_grant_by_user_code_digest(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_grant_by_user_code_digest');
    SELECT document INTO result FROM aether_identity.device_grants
     WHERE user_digest_algorithm = payload#>>'{digest,algorithm}'
       AND user_digest_encoded = payload#>>'{digest,encoded}'
       AND COALESCE(user_digest_key_version, '') = COALESCE(payload#>>'{digest,keyVersion}', '');
    RETURN aether_identity.rpc_success('find_device_grant_by_user_code_digest', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_token_family(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_token_family');
    SELECT document INTO result FROM aether_identity.device_token_families WHERE id = payload->>'id';
    RETURN aether_identity.rpc_success('find_device_token_family', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_access_token_by_selector(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_access_token_by_selector');
    SELECT document INTO result FROM aether_identity.device_access_tokens
     WHERE public_selector = payload->>'publicSelector';
    RETURN aether_identity.rpc_success('find_device_access_token_by_selector', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_device_refresh_token_by_selector(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_device_refresh_token_by_selector');
    SELECT document INTO result FROM aether_identity.device_refresh_tokens
     WHERE public_selector = payload->>'publicSelector';
    RETURN aether_identity.rpc_success('find_device_refresh_token_by_selector', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_mutate_credential(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    replacement JSONB;
    audit_event JSONB;
    stored aether_identity.credentials%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_credential');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.credentials
     WHERE id = payload->>'credentialId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'credential not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'credential version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF replacement->>'id' <> stored.id OR
       (replacement->>'version')::BIGINT <> stored.version + 1 OR
       (stored.document - 'name' - 'state' - 'version' - 'updatedAt' - 'revokedAt' - 'revocationReasonCode') <>
           (replacement - 'name' - 'state' - 'version' - 'updatedAt' - 'revokedAt' - 'revocationReasonCode') OR
       (replacement->>'updatedAt')::TIMESTAMPTZ < (stored.document->>'updatedAt')::TIMESTAMPTZ OR
       audit_event#>>'{target,type}' <> 'credential' OR
       audit_event#>>'{target,id}' <> stored.id THEN
        RAISE EXCEPTION 'credential mutation is invalid' USING ERRCODE = 'A0003';
    END IF;
    IF audit_event->>'action' = 'credential.renamed' THEN
        IF replacement->>'name' = stored.document->>'name' OR
           replacement->>'state' <> stored.state OR
           replacement->'revokedAt' IS DISTINCT FROM stored.document->'revokedAt' OR
           replacement->'revocationReasonCode' IS DISTINCT FROM stored.document->'revocationReasonCode' THEN
            RAISE EXCEPTION 'credential rename is invalid' USING ERRCODE = 'A0003';
        END IF;
    ELSIF audit_event->>'action' = 'credential.revoked' THEN
        IF stored.state = 'revoked' OR replacement->>'state' <> 'revoked' OR
           replacement->>'revokedAt' IS NULL OR COALESCE(replacement->>'revocationReasonCode', '') = '' THEN
            RAISE EXCEPTION 'credential revocation is invalid' USING ERRCODE = 'A0003';
        END IF;
    ELSE
        RAISE EXCEPTION 'credential audit action is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.credentials
       SET state = replacement->>'state', version = (replacement->>'version')::BIGINT, document = replacement
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('mutate_credential', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_exchange_device_grant(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    family_document JSONB;
    access_document JSONB;
    refresh_document JSONB;
    audit_event JSONB;
    stored aether_identity.device_grants%ROWTYPE;
    consumed JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'exchange_device_grant');
    family_document := payload->'family';
    access_document := payload->'accessToken';
    refresh_document := payload->'refreshToken';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.device_grants
     WHERE id = payload->>'deviceGrantId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device grant not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedDeviceGrantVersion')::BIGINT THEN
        RAISE EXCEPTION 'device grant version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'authorized' OR stored.expires_at <= (payload->>'exchangedAt')::TIMESTAMPTZ OR
       family_document->>'deviceGrantId' <> stored.id OR
       family_document->>'clientId' IS DISTINCT FROM stored.document->>'clientId' OR
       family_document->>'userId' IS DISTINCT FROM stored.document->>'userId' OR
       family_document->>'organizationId' IS DISTINCT FROM stored.document->>'organizationId' OR
       NOT ((family_document->'capabilities') @> (stored.document->'approvedCapabilities') AND
            (family_document->'capabilities') <@ (stored.document->'approvedCapabilities')) OR
       (family_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'exchangedAt')::TIMESTAMPTZ OR
       access_document->>'familyId' <> family_document->>'id' OR
       refresh_document->>'familyId' <> family_document->>'id' OR
       (refresh_document->>'rotationCounter')::BIGINT <> 0 OR
       (access_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'exchangedAt')::TIMESTAMPTZ OR
       (refresh_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'exchangedAt')::TIMESTAMPTZ OR
       (access_document->>'expiresAt')::TIMESTAMPTZ > (family_document->>'expiresAt')::TIMESTAMPTZ OR
       (refresh_document->>'expiresAt')::TIMESTAMPTZ > (family_document->>'expiresAt')::TIMESTAMPTZ OR
       audit_event->>'action' <> 'device_token.issued' THEN
        RAISE EXCEPTION 'device grant exchange is invalid' USING ERRCODE = 'A0003';
    END IF;
    consumed := stored.document || jsonb_build_object(
        'state', 'consumed', 'version', stored.version + 1,
        'consumedAt', to_jsonb((payload->>'exchangedAt')::TIMESTAMPTZ)
    );
    UPDATE aether_identity.device_grants
       SET state = 'consumed', version = stored.version + 1, document = consumed
     WHERE id = stored.id;
    PERFORM aether_identity.insert_device_token_family(family_document);
    PERFORM aether_identity.insert_device_access_token(access_document);
    PERFORM aether_identity.insert_device_refresh_token(refresh_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'exchange_device_grant',
        jsonb_build_object(
            'deviceGrant', consumed, 'family', family_document, 'accessToken', access_document,
            'refreshToken', refresh_document, 'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_rotate_device_refresh_token(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    access_document JSONB;
    refresh_document JSONB;
    audit_event JSONB;
    family_id TEXT;
    family aether_identity.device_token_families%ROWTYPE;
    previous aether_identity.device_refresh_tokens%ROWTYPE;
    rotated JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'rotate_device_refresh_token');
    access_document := payload->'replacementAccessToken';
    refresh_document := payload->'replacementRefreshToken';
    audit_event := payload->'auditEvent';
    SELECT device_refresh_tokens.family_id INTO family_id
      FROM aether_identity.device_refresh_tokens
     WHERE id = payload->>'refreshTokenId';
    IF NOT FOUND THEN RAISE EXCEPTION 'device refresh token not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO family FROM aether_identity.device_token_families
     WHERE id = family_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device token family not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO previous FROM aether_identity.device_refresh_tokens
     WHERE id = payload->>'refreshTokenId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device refresh token not found' USING ERRCODE = 'A0012'; END IF;
    IF previous.version <> (payload->>'expectedRefreshTokenVersion')::BIGINT OR
       family.version <> (payload->>'expectedFamilyVersion')::BIGINT THEN
        RAISE EXCEPTION 'device token version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF previous.family_id <> family.id OR previous.state <> 'active' OR
       previous.expires_at <= (payload->>'rotatedAt')::TIMESTAMPTZ OR
       family.state <> 'active' OR family.expires_at <= (payload->>'rotatedAt')::TIMESTAMPTZ OR
       access_document->>'familyId' <> family.id OR refresh_document->>'familyId' <> family.id OR
       (refresh_document->>'rotationCounter')::BIGINT <> previous.rotation_counter + 1 OR
       (access_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'rotatedAt')::TIMESTAMPTZ OR
       (refresh_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'rotatedAt')::TIMESTAMPTZ OR
       (access_document->>'expiresAt')::TIMESTAMPTZ > family.expires_at OR
       (refresh_document->>'expiresAt')::TIMESTAMPTZ > family.expires_at OR
       audit_event->>'action' <> 'device_token.refreshed' THEN
        RAISE EXCEPTION 'device refresh rotation is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_device_access_token(access_document);
    PERFORM aether_identity.insert_device_refresh_token(refresh_document);
    rotated := previous.document || jsonb_build_object(
        'state', 'rotated', 'version', previous.version + 1,
        'rotatedToId', refresh_document->'id',
        'consumedAt', to_jsonb((payload->>'rotatedAt')::TIMESTAMPTZ)
    );
    UPDATE aether_identity.device_refresh_tokens
       SET state = 'rotated', version = previous.version + 1, document = rotated
     WHERE id = previous.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'rotate_device_refresh_token',
        jsonb_build_object(
            'family', family.document, 'previousRefreshToken', rotated,
            'accessToken', access_document, 'refreshToken', refresh_document,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_revoke_device_token_family(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    audit_event JSONB;
    family aether_identity.device_token_families%ROWTYPE;
    revoked_family JSONB;
    access_ids JSONB;
    refresh_ids JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'revoke_device_token_family');
    audit_event := payload->'auditEvent';
    SELECT * INTO family FROM aether_identity.device_token_families
     WHERE id = payload->>'familyId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device token family not found' USING ERRCODE = 'A0012'; END IF;
    IF family.version <> (payload->>'expectedFamilyVersion')::BIGINT THEN
        RAISE EXCEPTION 'device token family version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF family.state <> 'active' OR COALESCE(payload->>'reasonCode', '') = '' OR
       length(payload->>'reasonCode') > 200 OR
       audit_event->>'action' <> (CASE WHEN COALESCE((payload->>'replayDetected')::BOOLEAN, FALSE)
           THEN 'device_token.replay_detected' ELSE 'device_token.revoked' END) THEN
        RAISE EXCEPTION 'device token family revocation is invalid' USING ERRCODE = 'A0003';
    END IF;
    SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB) INTO access_ids
      FROM aether_identity.device_access_tokens WHERE family_id = family.id AND state = 'active';
    SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB) INTO refresh_ids
      FROM aether_identity.device_refresh_tokens WHERE family_id = family.id AND state = 'active';
    UPDATE aether_identity.device_access_tokens
       SET state = 'revoked', version = version + 1,
           document = document || jsonb_build_object(
               'state', 'revoked', 'version', version + 1,
               'revokedAt', to_jsonb((payload->>'revokedAt')::TIMESTAMPTZ)
           )
     WHERE family_id = family.id AND state = 'active';
    UPDATE aether_identity.device_refresh_tokens
       SET state = 'revoked', version = version + 1,
           document = document || jsonb_build_object(
               'state', 'revoked', 'version', version + 1,
               'revokedAt', to_jsonb((payload->>'revokedAt')::TIMESTAMPTZ)
           )
     WHERE family_id = family.id AND state = 'active';
    revoked_family := family.document || jsonb_build_object(
        'state', 'revoked', 'version', family.version + 1,
        'revokedAt', to_jsonb((payload->>'revokedAt')::TIMESTAMPTZ),
        'revocationReasonCode', payload->'reasonCode'
    );
    UPDATE aether_identity.device_token_families
       SET state = 'revoked', version = family.version + 1, document = revoked_family
     WHERE id = family.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'revoke_device_token_family',
        jsonb_build_object(
            'family', revoked_family, 'revokedAccessTokenIds', access_ids,
            'revokedRefreshTokenIds', refresh_ids, 'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_organization_by_slug(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_organization_by_slug');
    SELECT document INTO result FROM aether_identity.organizations WHERE slug = payload->>'slug';
    RETURN aether_identity.rpc_success('find_organization_by_slug', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_organizations_for_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_organizations_for_user');
    SELECT COALESCE(jsonb_agg(o.document ORDER BY o.id), '[]'::JSONB) INTO result
      FROM aether_identity.memberships m
      JOIN aether_identity.organizations o ON o.id = m.organization_id
     WHERE m.user_id = payload->>'userId' AND m.state = 'active' AND o.state = 'active';
    RETURN aether_identity.rpc_success('list_organizations_for_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_memberships_for_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_memberships_for_organization');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.memberships WHERE organization_id = payload->>'organizationId';
    RETURN aether_identity.rpc_success('list_memberships_for_organization', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_invitation_by_token_digest(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_invitation_by_token_digest');
    SELECT document INTO result FROM aether_identity.invitations
     WHERE token_digest_algorithm = payload#>>'{digest,algorithm}'
       AND token_digest_encoded = payload#>>'{digest,encoded}'
       AND COALESCE(token_digest_key_version, '') = COALESCE(payload#>>'{digest,keyVersion}', '');
    RETURN aether_identity.rpc_success('find_invitation_by_token_digest', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_invitations_for_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_invitations_for_organization');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.invitations WHERE organization_id = payload->>'organizationId';
    RETURN aether_identity.rpc_success('list_invitations_for_organization', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_service_identities_for_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_service_identities_for_organization');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.service_identities WHERE organization_id = payload->>'organizationId';
    RETURN aether_identity.rpc_success('list_service_identities_for_organization', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_service_credentials_for_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_service_credentials_for_identity');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.service_credentials WHERE service_identity_id = payload->>'serviceIdentityId';
    RETURN aether_identity.rpc_success('list_service_credentials_for_identity', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; organization_document JSONB; owner_document JSONB; audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_organization');
    organization_document := payload->'organization';
    owner_document := payload->'ownerMembership';
    audit_event := payload->'auditEvent';
    IF organization_document->>'state' <> 'active' OR (organization_document->>'version')::BIGINT <> 0 OR
       owner_document->>'state' <> 'active' OR owner_document->>'role' <> 'owner' OR
       (owner_document->>'version')::BIGINT <> 0 OR
       owner_document->>'organizationId' <> organization_document->>'id' OR
       audit_event->>'action' <> 'organization.created' OR
       audit_event#>>'{target,type}' <> 'organization' OR
       audit_event#>>'{target,id}' <> organization_document->>'id' THEN
        RAISE EXCEPTION 'organization creation is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.organizations(id, slug, state, version, document)
    VALUES (
        organization_document->>'id', organization_document->>'slug', organization_document->>'state',
        (organization_document->>'version')::BIGINT, organization_document
    );
    PERFORM aether_identity.insert_membership(owner_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'create_organization',
        jsonb_build_object(
            'organization', organization_document, 'ownerMembership', owner_document, 'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_mutate_organization(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; replacement JSONB; audit_event JSONB; stored aether_identity.organizations%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_organization');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.organizations
     WHERE id = payload->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'organization not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'organization version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state = 'deleted' OR replacement->>'id' <> stored.id OR
       replacement->>'slug' <> stored.slug OR (replacement->>'version')::BIGINT <> stored.version + 1 OR
       replacement->'createdAt' IS DISTINCT FROM stored.document->'createdAt' OR
       audit_event#>>'{target,type}' <> 'organization' OR audit_event#>>'{target,id}' <> stored.id OR
       audit_event->>'action' <> (CASE WHEN replacement->>'state' = 'deleted'
           THEN 'organization.deleted' ELSE 'organization.changed' END) THEN
        RAISE EXCEPTION 'organization mutation is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.organizations
       SET state = replacement->>'state', version = (replacement->>'version')::BIGINT, document = replacement
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('mutate_organization', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_invitation(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; invitation_document JSONB; audit_event JSONB; organization_state TEXT;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_invitation');
    invitation_document := payload->'invitation';
    audit_event := payload->'auditEvent';
    SELECT state INTO organization_state FROM aether_identity.organizations
     WHERE id = invitation_document->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'organization not found' USING ERRCODE = 'A0012'; END IF;
    IF organization_state <> 'active' OR invitation_document->>'state' <> 'pending' OR
       (invitation_document->>'version')::BIGINT <> 0 OR
       (invitation_document->>'expiresAt')::TIMESTAMPTZ <= (invitation_document->>'createdAt')::TIMESTAMPTZ OR
       audit_event->>'action' <> 'invitation.created' OR
       audit_event#>>'{target,type}' <> 'invitation' OR
       audit_event#>>'{target,id}' <> invitation_document->>'id' THEN
        RAISE EXCEPTION 'invitation creation is invalid' USING ERRCODE = 'A0003';
    END IF;
    IF invitation_document->>'invitedByUserId' IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM aether_identity.users WHERE id = invitation_document->>'invitedByUserId'
    ) THEN
        RAISE EXCEPTION 'inviting user not found' USING ERRCODE = 'A0012';
    END IF;
    INSERT INTO aether_identity.invitations(
        id, organization_id, email,
        token_digest_algorithm, token_digest_encoded, token_digest_key_version,
        state, version, document
    ) VALUES (
        invitation_document->>'id', invitation_document->>'organizationId', invitation_document->>'email',
        invitation_document#>>'{tokenDigest,algorithm}', invitation_document#>>'{tokenDigest,encoded}',
        invitation_document#>>'{tokenDigest,keyVersion}', invitation_document->>'state',
        (invitation_document->>'version')::BIGINT, invitation_document
    );
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('create_invitation', invitation_document);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_mutate_invitation(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; replacement JSONB; audit_event JSONB; stored aether_identity.invitations%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_invitation');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.invitations
     WHERE id = payload->>'invitationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'invitation not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'invitation version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'pending' OR replacement->>'id' <> stored.id OR
       replacement->>'organizationId' <> stored.organization_id OR replacement->>'email' <> stored.email OR
       replacement->>'role' <> stored.document->>'role' OR
       replacement->'tokenDigest' IS DISTINCT FROM stored.document->'tokenDigest' OR
       replacement->'createdAt' IS DISTINCT FROM stored.document->'createdAt' OR
       replacement->'expiresAt' IS DISTINCT FROM stored.document->'expiresAt' OR
       replacement->>'state' <> 'revoked' OR replacement->>'revokedAt' IS NULL OR
       (replacement->>'version')::BIGINT <> stored.version + 1 OR
       audit_event->>'action' <> 'invitation.revoked' OR
       audit_event#>>'{target,type}' <> 'invitation' OR audit_event#>>'{target,id}' <> stored.id THEN
        RAISE EXCEPTION 'invitation mutation is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.invitations
       SET state = 'revoked', version = (replacement->>'version')::BIGINT, document = replacement
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('mutate_invitation', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_service_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; identity_document JSONB; credential_document JSONB; audit_event JSONB; organization_state TEXT;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_service_identity');
    identity_document := payload->'identity';
    credential_document := payload->'initialCredential';
    audit_event := payload->'auditEvent';
    SELECT state INTO organization_state FROM aether_identity.organizations
     WHERE id = identity_document->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'organization not found' USING ERRCODE = 'A0012'; END IF;
    IF organization_state <> 'active' OR identity_document->>'state' <> 'active' OR
       (identity_document->>'version')::BIGINT <> 0 OR credential_document->>'state' <> 'active' OR
       (credential_document->>'version')::BIGINT <> 0 OR
       credential_document->>'serviceIdentityId' <> identity_document->>'id' OR
       NOT ((identity_document->'capabilities') @> (credential_document->'capabilities')) OR
       credential_document->>'expiresAt' IS NULL OR
       (credential_document->>'expiresAt')::TIMESTAMPTZ <= (identity_document->>'createdAt')::TIMESTAMPTZ OR
       audit_event->>'action' <> 'service_identity.created' OR
       audit_event#>>'{target,type}' <> 'service_identity' OR
       audit_event#>>'{target,id}' <> identity_document->>'id' THEN
        RAISE EXCEPTION 'service identity creation is invalid' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.service_identities(
        id, organization_id, name, state, version, document
    ) VALUES (
        identity_document->>'id', identity_document->>'organizationId', identity_document->>'name',
        identity_document->>'state', (identity_document->>'version')::BIGINT, identity_document
    );
    PERFORM aether_identity.insert_service_credential(credential_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'create_service_identity',
        jsonb_build_object(
            'identity', identity_document, 'initialCredential', credential_document, 'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_mutate_service_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; replacement JSONB; audit_event JSONB; stored aether_identity.service_identities%ROWTYPE;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_service_identity');
    replacement := payload->'replacement';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.service_identities
     WHERE id = payload->>'serviceIdentityId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'service identity not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'service identity version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state = 'revoked' OR replacement->>'id' <> stored.id OR
       replacement->>'organizationId' <> stored.organization_id OR
       replacement->'createdAt' IS DISTINCT FROM stored.document->'createdAt' OR
       replacement->'updatedAt' IS DISTINCT FROM payload->'changedAt' OR
       (replacement->>'version')::BIGINT <> stored.version + 1 OR
       audit_event#>>'{target,type}' <> 'service_identity' OR audit_event#>>'{target,id}' <> stored.id OR
       audit_event->>'action' <> (CASE WHEN replacement->>'state' = 'revoked'
           THEN 'service_identity.revoked' ELSE 'service_identity.changed' END) THEN
        RAISE EXCEPTION 'service identity mutation is invalid' USING ERRCODE = 'A0003';
    END IF;
    UPDATE aether_identity.service_identities
       SET name = replacement->>'name', state = replacement->>'state',
           version = (replacement->>'version')::BIGINT, document = replacement
     WHERE id = stored.id;
    IF replacement->>'state' = 'revoked' THEN
        UPDATE aether_identity.service_credentials
           SET state = 'revoked', version = version + 1,
               document = document || jsonb_build_object(
                   'state', 'revoked', 'version', version + 1,
                   'revokedAt', payload->'changedAt'
               )
         WHERE service_identity_id = stored.id AND state IN ('active', 'rotated');
    END IF;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('mutate_service_identity', replacement);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_create_service_credential(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; credential_document JSONB; audit_event JSONB; identity_document JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'create_service_credential');
    credential_document := payload->'credential';
    audit_event := payload->'auditEvent';
    SELECT document INTO identity_document FROM aether_identity.service_identities
     WHERE id = credential_document->>'serviceIdentityId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'service identity not found' USING ERRCODE = 'A0012'; END IF;
    IF identity_document->>'state' <> 'active' OR credential_document->>'state' <> 'active' OR
       (credential_document->>'version')::BIGINT <> 0 OR
       NOT ((identity_document->'capabilities') @> (credential_document->'capabilities')) OR
       audit_event->>'action' <> 'service_credential.created' OR
       audit_event#>>'{target,type}' <> 'service_credential' OR
       audit_event#>>'{target,id}' <> credential_document->>'id' THEN
        RAISE EXCEPTION 'service credential creation is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM aether_identity.insert_service_credential(credential_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('create_service_credential', credential_document);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_revoke_service_credential(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; audit_event JSONB; stored aether_identity.service_credentials%ROWTYPE; revoked JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'revoke_service_credential');
    audit_event := payload->'auditEvent';
    SELECT * INTO stored FROM aether_identity.service_credentials
     WHERE id = payload->>'credentialId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'service credential not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedVersion')::BIGINT THEN
        RAISE EXCEPTION 'service credential version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state NOT IN ('active', 'rotated') OR
       (payload->>'revokedAt')::TIMESTAMPTZ < (stored.document->>'createdAt')::TIMESTAMPTZ OR
       audit_event->>'action' <> 'service_credential.revoked' OR
       audit_event#>>'{target,type}' <> 'service_credential' OR
       audit_event#>>'{target,id}' <> stored.id THEN
        RAISE EXCEPTION 'service credential revocation is invalid' USING ERRCODE = 'A0003';
    END IF;
    revoked := stored.document || jsonb_build_object(
        'state', 'revoked', 'version', stored.version + 1, 'revokedAt', payload->'revokedAt'
    );
    UPDATE aether_identity.service_credentials
       SET state = 'revoked', version = stored.version + 1, document = revoked
     WHERE id = stored.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success('revoke_service_credential', revoked);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_list_recovery_codes_for_user(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_recovery_codes_for_user');
    SELECT COALESCE(jsonb_agg(document ORDER BY id), '[]'::JSONB) INTO result
      FROM aether_identity.recovery_codes WHERE user_id = payload->>'userId';
    RETURN aether_identity.rpc_success('list_recovery_codes_for_user', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_append_audit_event(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; event_document JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'append_audit_event');
    event_document := payload->'event';
    PERFORM aether_identity.record_audit(event_document);
    RETURN aether_identity.rpc_success('append_audit_event', event_document);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_bootstrap_identity(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    receipt JSONB;
    user_document JSONB;
    organization_document JSONB;
    membership_document JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'bootstrap_identity');
    receipt := payload->'bootstrapSecretDigest';
    user_document := payload->'user';
    organization_document := payload->'organization';
    membership_document := payload->'ownerMembership';
    audit_event := payload->'auditEvent';

    -- Bootstrap is a first-install operation. Blocking concurrent identity writers here ensures
    -- the emptiness decision, receipt consumption, first owner, and audit event commit together.
    LOCK TABLE
        aether_identity.bootstrap_receipts,
        aether_identity.users,
        aether_identity.credentials,
        aether_identity.sessions,
        aether_identity.organizations,
        aether_identity.memberships,
        aether_identity.invitations,
        aether_identity.service_identities,
        aether_identity.service_credentials,
        aether_identity.external_identities,
        aether_identity.challenges,
        aether_identity.recovery_codes,
        aether_identity.device_grants,
        aether_identity.device_token_families,
        aether_identity.device_access_tokens,
        aether_identity.device_refresh_tokens,
        aether_identity.audit_events
    IN SHARE ROW EXCLUSIVE MODE;

    IF EXISTS (SELECT 1 FROM aether_identity.bootstrap_receipts) OR
       EXISTS (SELECT 1 FROM aether_identity.users) OR
       EXISTS (SELECT 1 FROM aether_identity.credentials) OR
       EXISTS (SELECT 1 FROM aether_identity.sessions) OR
       EXISTS (SELECT 1 FROM aether_identity.organizations) OR
       EXISTS (SELECT 1 FROM aether_identity.memberships) OR
       EXISTS (SELECT 1 FROM aether_identity.invitations) OR
       EXISTS (SELECT 1 FROM aether_identity.service_identities) OR
       EXISTS (SELECT 1 FROM aether_identity.service_credentials) OR
       EXISTS (SELECT 1 FROM aether_identity.external_identities) OR
       EXISTS (SELECT 1 FROM aether_identity.challenges) OR
       EXISTS (SELECT 1 FROM aether_identity.recovery_codes) OR
       EXISTS (SELECT 1 FROM aether_identity.device_grants) OR
       EXISTS (SELECT 1 FROM aether_identity.device_token_families) OR
       EXISTS (SELECT 1 FROM aether_identity.device_access_tokens) OR
       EXISTS (SELECT 1 FROM aether_identity.device_refresh_tokens) THEN
        RAISE EXCEPTION 'identity is already bootstrapped' USING ERRCODE = 'A0013';
    END IF;

    IF jsonb_typeof(receipt) IS DISTINCT FROM 'object' OR
       receipt->>'algorithm' IS DISTINCT FROM 'sha256' OR
       COALESCE(length(receipt->>'encoded'), 0) NOT BETWEEN 1 AND 1024 OR
       receipt->>'keyVersion' IS NOT NULL OR
       user_document->>'state' IS DISTINCT FROM 'active' OR
       (user_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       organization_document->>'state' IS DISTINCT FROM 'active' OR
       (organization_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       membership_document->>'state' IS DISTINCT FROM 'active' OR
       membership_document->>'role' IS DISTINCT FROM 'owner' OR
       (membership_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       membership_document->>'userId' IS DISTINCT FROM user_document->>'id' OR
       membership_document->>'organizationId' IS DISTINCT FROM organization_document->>'id' OR
       audit_event->>'action' IS DISTINCT FROM 'identity.bootstrapped' OR
       audit_event#>>'{target,type}' IS DISTINCT FROM 'user' OR
       audit_event#>>'{target,id}' IS DISTINCT FROM user_document->>'id' OR
       (audit_event->>'organizationId' IS NOT NULL AND
        audit_event->>'organizationId' IS DISTINCT FROM organization_document->>'id') THEN
        RAISE EXCEPTION 'identity bootstrap payload is invalid' USING ERRCODE = 'A0003';
    END IF;

    INSERT INTO aether_identity.bootstrap_receipts(
        singleton, digest_algorithm, digest_encoded, consumed_at
    ) VALUES (
        TRUE, receipt->>'algorithm', receipt->>'encoded',
        (audit_event->>'occurredAt')::TIMESTAMPTZ
    );
    PERFORM aether_identity.insert_user(user_document);
    INSERT INTO aether_identity.organizations(id, slug, state, version, document)
    VALUES (
        organization_document->>'id', organization_document->>'slug',
        organization_document->>'state', (organization_document->>'version')::BIGINT,
        organization_document
    );
    PERFORM aether_identity.insert_membership(membership_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'bootstrap_identity',
        jsonb_build_object(
            'user', user_document,
            'organization', organization_document,
            'ownerMembership', membership_document,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_redeem_administrative_recovery_ticket(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    stored_challenge aether_identity.challenges%ROWTYPE;
    session_document JSONB;
    audit_event JSONB;
    consumed JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'redeem_administrative_recovery_ticket');
    session_document := payload->'recoverySession';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored_challenge FROM aether_identity.challenges
     WHERE id = payload->>'challengeId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery ticket not found' USING ERRCODE = 'A0012'; END IF;
    IF stored_challenge.version <> (payload->>'expectedChallengeVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery ticket version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_challenge.state <> 'pending' THEN
        RAISE EXCEPTION 'recovery ticket is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored_challenge.expires_at <= (payload->>'redeemedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'recovery ticket expired' USING ERRCODE = 'A0008';
    END IF;
    IF stored_challenge.purpose <> 'account_recovery' OR stored_challenge.user_id IS NULL OR
       session_document->>'userId' <> stored_challenge.user_id OR
       session_document->>'assurance' <> 'recovery' OR
       session_document->>'familyId' <> session_document->>'id' OR
       session_document->'rotatedFromId' <> 'null'::JSONB OR
       (session_document->>'rotationCounter')::BIGINT <> 0 OR
       (session_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'redeemedAt')::TIMESTAMPTZ OR
       audit_event->>'action' <> 'recovery.admin_ticket_used' THEN
        RAISE EXCEPTION 'administrative recovery redemption is invalid' USING ERRCODE = 'A0003';
    END IF;
    consumed := aether_identity.consume_challenge_model(
        stored_challenge.id,
        stored_challenge.version,
        'consumed',
        (payload->>'redeemedAt')::TIMESTAMPTZ
    );
    PERFORM aether_identity.insert_session(session_document);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'redeem_administrative_recovery_ticket',
        jsonb_build_object('challenge', consumed, 'recoverySession', session_document, 'auditEvent', audit_event)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_recovery_enrollment(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    credential_document JSONB;
    replacement_codes JSONB;
    audit_event JSONB;
    stored_challenge aether_identity.challenges%ROWTYPE;
    stored_user aether_identity.users%ROWTYPE;
    recovery_session aether_identity.sessions%ROWTYPE;
    current_generation BIGINT;
    expected_generation BIGINT;
    updated_user JSONB;
    consumed_challenge JSONB;
    revoked_session_ids JSONB;
    code_document JSONB;
    code_count INTEGER;
    unique_id_count INTEGER;
    unique_selector_count INTEGER;
    unique_digest_count INTEGER;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'complete_recovery_enrollment');
    credential_document := payload->'credential';
    replacement_codes := payload->'replacementRecoveryCodes';
    audit_event := payload->'auditEvent';
    SELECT * INTO stored_challenge FROM aether_identity.challenges
     WHERE id = payload->>'challengeId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery enrollment challenge not found' USING ERRCODE = 'A0012'; END IF;
    IF stored_challenge.version <> (payload->>'expectedChallengeVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery enrollment challenge version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_challenge.state <> 'pending' THEN
        RAISE EXCEPTION 'recovery enrollment challenge is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored_challenge.expires_at <= (payload->>'completedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'recovery enrollment challenge expired' USING ERRCODE = 'A0008';
    END IF;
    IF stored_challenge.purpose <> 'webauthn_registration' OR
       stored_challenge.user_id IS DISTINCT FROM credential_document->>'userId' THEN
        RAISE EXCEPTION 'recovery enrollment challenge is invalid' USING ERRCODE = 'A0003';
    END IF;
    SELECT * INTO stored_user FROM aether_identity.users
     WHERE id = credential_document->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery user not found' USING ERRCODE = 'A0012'; END IF;
    IF stored_user.version <> (payload->>'expectedUserVersion')::BIGINT OR
       stored_user.session_epoch <> (payload->>'expectedSessionEpoch')::BIGINT THEN
        RAISE EXCEPTION 'recovery user version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF (payload->>'newSessionEpoch')::BIGINT <> stored_user.session_epoch + 1 THEN
        RAISE EXCEPTION 'recovery session epoch is invalid' USING ERRCODE = 'A0003';
    END IF;
    SELECT * INTO recovery_session FROM aether_identity.sessions
     WHERE id = payload->>'recoverySessionId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'recovery session not found' USING ERRCODE = 'A0012'; END IF;
    IF recovery_session.version <> (payload->>'expectedRecoverySessionVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF recovery_session.state <> 'active' THEN
        RAISE EXCEPTION 'recovery session is not active' USING ERRCODE = 'A0009';
    END IF;
    IF recovery_session.idle_expires_at <= (payload->>'completedAt')::TIMESTAMPTZ OR
       recovery_session.absolute_expires_at <= (payload->>'completedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'recovery session expired' USING ERRCODE = 'A0010';
    END IF;
    IF recovery_session.user_id <> stored_user.id OR
       recovery_session.user_session_epoch <> stored_user.session_epoch OR
       recovery_session.document->>'assurance' <> 'recovery' THEN
        RAISE EXCEPTION 'recovery session is invalid' USING ERRCODE = 'A0003';
    END IF;
    PERFORM 1 FROM aether_identity.recovery_codes WHERE user_id = stored_user.id FOR UPDATE;
    SELECT MAX(generation) INTO current_generation
      FROM aether_identity.recovery_codes WHERE user_id = stored_user.id;
    expected_generation := CASE
        WHEN payload->'expectedRecoveryGeneration' IS NULL OR
             payload->'expectedRecoveryGeneration' = 'null'::JSONB THEN NULL
        ELSE (payload->>'expectedRecoveryGeneration')::BIGINT
    END;
    IF current_generation IS DISTINCT FROM expected_generation THEN
        RAISE EXCEPTION 'recovery generation conflict' USING ERRCODE = 'A0002';
    END IF;
    IF (payload->>'newRecoveryGeneration')::BIGINT <> COALESCE(expected_generation + 1, 0) OR
       jsonb_typeof(replacement_codes) IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'recovery generation replacement is invalid' USING ERRCODE = 'A0003';
    END IF;
    SELECT COUNT(*), COUNT(DISTINCT value->>'id'), COUNT(DISTINCT value->>'publicSelector'),
           COUNT(DISTINCT value->'secretDigest')
      INTO code_count, unique_id_count, unique_selector_count, unique_digest_count
      FROM jsonb_array_elements(replacement_codes);
    IF code_count <> 10 OR unique_id_count <> 10 OR unique_selector_count <> 10 OR unique_digest_count <> 10 OR
       EXISTS (
           SELECT 1 FROM jsonb_array_elements(replacement_codes) code
            WHERE code->>'userId' <> stored_user.id OR
                  (code->>'generation')::BIGINT <> (payload->>'newRecoveryGeneration')::BIGINT OR
                  (code->>'version')::BIGINT <> 0 OR code->>'state' <> 'active'
       ) OR credential_document->>'state' <> 'active' OR
       (credential_document->>'version')::BIGINT <> 0 OR
       audit_event->>'action' <> 'recovery.enrollment_completed' THEN
        RAISE EXCEPTION 'recovery enrollment replacement is invalid' USING ERRCODE = 'A0003';
    END IF;
    consumed_challenge := aether_identity.consume_challenge_model(
        stored_challenge.id,
        stored_challenge.version,
        'consumed',
        (payload->>'completedAt')::TIMESTAMPTZ
    );
    PERFORM aether_identity.insert_credential(credential_document);
    updated_user := stored_user.document || jsonb_build_object(
        'sessionEpoch', (payload->>'newSessionEpoch')::BIGINT,
        'version', stored_user.version + 1,
        'updatedAt', payload->'completedAt'
    );
    UPDATE aether_identity.users
       SET session_epoch = (payload->>'newSessionEpoch')::BIGINT,
           version = stored_user.version + 1,
           document = updated_user
     WHERE id = stored_user.id;
    SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB) INTO revoked_session_ids
      FROM aether_identity.sessions WHERE user_id = stored_user.id AND state = 'active';
    UPDATE aether_identity.sessions
       SET state = 'revoked', version = version + 1,
           document = document || jsonb_build_object(
               'state', 'revoked', 'version', version + 1,
               'revokedAt', payload->'completedAt',
               'revocationReasonCode', 'recovery_enrollment_completed'
           )
     WHERE user_id = stored_user.id AND state = 'active';
    UPDATE aether_identity.recovery_codes
       SET state = 'revoked', version = version + 1,
           document = document || jsonb_build_object('state', 'revoked', 'version', version + 1)
     WHERE user_id = stored_user.id AND state = 'active';
    FOR code_document IN SELECT value FROM jsonb_array_elements(replacement_codes)
    LOOP
        PERFORM aether_identity.insert_recovery_code(code_document);
    END LOOP;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'complete_recovery_enrollment',
        jsonb_build_object(
            'challenge', consumed_challenge,
            'credential', credential_document,
            'user', updated_user,
            'revokedSessionIds', revoked_session_ids,
            'recoveryGeneration', (payload->>'newRecoveryGeneration')::BIGINT,
            'recoveryCodes', replacement_codes,
            'auditEvent', audit_event
        )
    );
END;
$$;
