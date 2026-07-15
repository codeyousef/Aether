-- Aether identity migration V010
-- Resolve every valid pending WebAuthn finish in one transaction. Deterministic completion
-- failures roll back credential/session/recovery writes, then commit only FAILED challenge state
-- and a redacted rejection audit.

CREATE SCHEMA IF NOT EXISTS aether_identity_internal;
REVOKE ALL ON SCHEMA aether_identity_internal FROM PUBLIC;

-- Preserve the reviewed V001 implementations behind an unexposed schema. PostgREST deployments
-- must expose only aether_identity; aether_identity_internal is never an API schema.
ALTER FUNCTION aether_identity.v1_complete_credential_registration(JSONB)
    SET SCHEMA aether_identity_internal;
ALTER FUNCTION aether_identity.v1_complete_credential_authentication(JSONB)
    SET SCHEMA aether_identity_internal;
ALTER FUNCTION aether_identity.v1_quarantine_credential_authentication(JSONB)
    SET SCHEMA aether_identity_internal;
ALTER FUNCTION aether_identity.v1_complete_recovery_enrollment(JSONB)
    SET SCHEMA aether_identity_internal;

REVOKE ALL ON FUNCTION aether_identity_internal.v1_complete_credential_registration(JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION aether_identity_internal.v1_complete_credential_authentication(JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION aether_identity_internal.v1_quarantine_credential_authentication(JSONB) FROM PUBLIC;
REVOKE ALL ON FUNCTION aether_identity_internal.v1_complete_recovery_enrollment(JSONB) FROM PUBLIC;

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
             SQLSTATE 'A0013' OR integrity_constraint_violation OR data_exception THEN
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

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_credential_registration(p_request JSONB)
RETURNS JSONB
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT aether_identity_internal.resolve_web_authn_attempt(
        p_request,
        'complete_credential_registration'
    );
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_credential_authentication(p_request JSONB)
RETURNS JSONB
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT aether_identity_internal.resolve_web_authn_attempt(
        p_request,
        'complete_credential_authentication'
    );
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_quarantine_credential_authentication(p_request JSONB)
RETURNS JSONB
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT aether_identity_internal.resolve_web_authn_attempt(
        p_request,
        'quarantine_credential_authentication'
    );
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_complete_recovery_enrollment(p_request JSONB)
RETURNS JSONB
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
    SELECT aether_identity_internal.resolve_web_authn_attempt(
        p_request,
        'complete_recovery_enrollment'
    );
$$;

COMMENT ON SCHEMA aether_identity_internal IS
    'Aether implementation functions; never expose this schema through PostgREST';

DO $$
DECLARE
    wrapper_name TEXT;
    internal_function_name TEXT;
    wrapper_security_definer BOOLEAN;
    wrapper_config TEXT[];
BEGIN
    IF has_schema_privilege('public', 'aether_identity_internal', 'USAGE') OR
       has_function_privilege(
           'public',
           'aether_identity_internal.resolve_web_authn_attempt(jsonb,text)',
           'EXECUTE'
       ) OR
       has_function_privilege(
           'public',
           'aether_identity_internal.web_authn_store_error_code(text)',
           'EXECUTE'
       ) THEN
        RAISE EXCEPTION 'internal WebAuthn functions remain publicly reachable';
    END IF;

    FOREACH internal_function_name IN ARRAY ARRAY[
        'v1_complete_credential_registration',
        'v1_complete_credential_authentication',
        'v1_quarantine_credential_authentication',
        'v1_complete_recovery_enrollment'
    ] LOOP
        IF has_function_privilege(
            'public',
            format('aether_identity_internal.%I(jsonb)', internal_function_name),
            'EXECUTE'
        ) THEN
            RAISE EXCEPTION 'internal WebAuthn function % remains publicly reachable',
                internal_function_name;
        END IF;
    END LOOP;

    FOREACH wrapper_name IN ARRAY ARRAY[
        'v1_complete_credential_registration',
        'v1_complete_credential_authentication',
        'v1_quarantine_credential_authentication',
        'v1_complete_recovery_enrollment'
    ] LOOP
        SELECT p.prosecdef, p.proconfig
          INTO wrapper_security_definer, wrapper_config
          FROM pg_catalog.pg_proc p
          JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'aether_identity' AND p.proname = wrapper_name
           AND pg_catalog.pg_get_function_identity_arguments(p.oid) = 'p_request jsonb';
        IF NOT FOUND OR NOT wrapper_security_definer OR
           wrapper_config IS DISTINCT FROM ARRAY['search_path=pg_catalog']::TEXT[] THEN
            RAISE EXCEPTION 'WebAuthn wrapper % is not locked SECURITY DEFINER', wrapper_name;
        END IF;
    END LOOP;
END;
$$;
