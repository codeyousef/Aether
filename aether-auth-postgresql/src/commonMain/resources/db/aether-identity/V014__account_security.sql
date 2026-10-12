ALTER TABLE aether_identity.sessions
    DROP CONSTRAINT sessions_authentication_method_check,
    DROP CONSTRAINT sessions_authentication_assurance_check,
    ADD CONSTRAINT sessions_authentication_method_check CHECK (authentication_method IN
        ('passkey', 'password', 'recovery_code', 'administrative_recovery', 'bootstrap', 'invitation', 'oidc', 'saml')),
    ADD CONSTRAINT sessions_authentication_assurance_check CHECK (
        (authentication_method = 'passkey' AND document->>'assurance' IN ('passkey', 'step_up')) OR
        (authentication_method IN ('recovery_code', 'administrative_recovery', 'bootstrap', 'invitation') AND document->>'assurance' = 'recovery') OR
        (authentication_method IN ('password', 'oidc', 'saml') AND document->>'assurance' = 'session'));

-- Additive opt-in password verifier and independent contact verification lifecycle.
CREATE TABLE aether_identity.account_security (
    user_id TEXT PRIMARY KEY REFERENCES aether_identity.users(id),
    version BIGINT NOT NULL CHECK (version >= 0),
    document JSONB NOT NULL,
    CHECK (document->>'userId' = user_id),
    CHECK ((document->>'version')::BIGINT = version)
);

CREATE FUNCTION aether_identity.v1_find_account_security(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE payload JSONB; result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_account_security');
    SELECT document INTO result FROM aether_identity.account_security WHERE user_id = payload->>'userId';
    RETURN aether_identity.rpc_success('find_account_security', result);
END;
$$;

CREATE FUNCTION aether_identity.v1_mutate_account_security(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB; next JSONB; old JSONB; actor JSONB; issued JSONB; audit JSONB;
    op TEXT; at_time TIMESTAMPTZ; uid TEXT;
    stored_user aether_identity.users%ROWTYPE;
    stored_security aether_identity.account_security%ROWTYPE;
    challenge JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'mutate_account_security');
    next := payload->'replacement'; op := payload->>'operation'; uid := next->>'userId';
    audit := payload->'auditEvent'; at_time := (audit->>'occurredAt')::TIMESTAMPTZ;
    SELECT * INTO stored_user FROM aether_identity.users WHERE id = uid FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'user unavailable' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored_security FROM aether_identity.account_security WHERE user_id = uid FOR UPDATE;
    IF stored_user.version IS DISTINCT FROM (payload->>'expectedUserVersion')::BIGINT OR
       COALESCE(stored_security.version, -1) IS DISTINCT FROM (payload->>'expectedSecurityVersion')::BIGINT THEN
        RAISE EXCEPTION 'account version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_user.state <> 'active' OR next->>'version' IS NULL OR
       (next->>'version')::BIGINT <> COALESCE(stored_security.version, -1) + 1 OR
       audit->>'action' IS DISTINCT FROM 'account_security_changed' OR audit#>>'{target,id}' IS DISTINCT FROM uid OR
       at_time IS NULL THEN
        RAISE EXCEPTION 'invalid account transition' USING ERRCODE = 'A0003';
    END IF;
    old := COALESCE(stored_security.document, jsonb_build_object('userId', uid,
        'password', NULL, 'email', NULL, 'emailVerifiedAt', NULL, 'emailChallenge', NULL));
    IF op <> 'PASSWORD_LOGIN' THEN
        SELECT document INTO actor FROM aether_identity.sessions WHERE id = payload->>'actorSessionId' FOR UPDATE;
        IF actor IS NULL OR actor->>'userId' IS DISTINCT FROM uid OR actor->>'state' <> 'active' OR
           (actor->>'userSessionEpoch')::BIGINT <> stored_user.session_epoch OR
           (actor->>'idleExpiresAt')::TIMESTAMPTZ <= at_time OR (actor->>'absoluteExpiresAt')::TIMESTAMPTZ <= at_time THEN
            RAISE EXCEPTION 'session unavailable' USING ERRCODE = 'A0009';
        END IF;
    END IF;
    IF op IN ('PASSWORD_REPLACE', 'PASSWORD_DISABLE') THEN
        IF (op = 'PASSWORD_REPLACE' AND (next->'password' IS NULL OR next->'password' = 'null'::JSONB)) OR
           (op = 'PASSWORD_DISABLE' AND next->>'password' IS NOT NULL) OR
           (next - ARRAY['password', 'version']) IS DISTINCT FROM (old - ARRAY['password', 'version']) OR
           actor->>'assurance' NOT IN ('passkey', 'step_up', 'recovery') THEN
            RAISE EXCEPTION 'invalid password transition' USING ERRCODE = 'A0003';
        END IF;
        UPDATE aether_identity.users SET version = version + 1, session_epoch = session_epoch + 1,
            document = document || jsonb_build_object('version', version + 1, 'sessionEpoch', session_epoch + 1, 'updatedAt', audit->'occurredAt')
            WHERE id = uid;
        UPDATE aether_identity.sessions SET state = 'revoked', version = version + 1,
            document = document || jsonb_build_object('state', 'revoked', 'version', version + 1,
                'revokedAt', audit->'occurredAt', 'revocationReasonCode', 'password_replaced')
            WHERE user_id = uid AND state = 'active';
        PERFORM id FROM aether_identity.device_token_families WHERE user_id = uid ORDER BY id FOR UPDATE;
        UPDATE aether_identity.device_access_tokens SET state = 'revoked', version = version + 1,
            document = document || jsonb_build_object('state', 'revoked', 'version', version + 1, 'revokedAt', audit->'occurredAt')
            WHERE family_id IN (SELECT id FROM aether_identity.device_token_families WHERE user_id = uid) AND state = 'active';
        UPDATE aether_identity.device_refresh_tokens SET state = 'revoked', version = version + 1,
            document = document || jsonb_build_object('state', 'revoked', 'version', version + 1, 'revokedAt', audit->'occurredAt')
            WHERE family_id IN (SELECT id FROM aether_identity.device_token_families WHERE user_id = uid) AND state = 'active';
        UPDATE aether_identity.device_token_families SET state = 'revoked', version = version + 1,
            document = document || jsonb_build_object('state', 'revoked', 'version', version + 1,
                'revokedAt', audit->'occurredAt', 'revocationReasonCode', 'password_replaced')
            WHERE user_id = uid AND state = 'active';
    ELSIF op = 'PASSWORD_LOGIN' THEN
        issued := payload->'issuedSession';
        IF old->'password' IS NULL OR old->'password' = 'null'::JSONB OR
           (next - 'version') IS DISTINCT FROM (old - 'version') OR
           issued->>'userId' IS DISTINCT FROM uid OR issued->>'authenticationMethod' IS DISTINCT FROM 'password' OR
           issued->>'assurance' IS DISTINCT FROM 'session' THEN
            RAISE EXCEPTION 'invalid password session' USING ERRCODE = 'A0003';
        END IF;
        PERFORM aether_identity.insert_session(issued);
    ELSIF op = 'EMAIL_CHANGE' THEN
        IF next->'password' IS DISTINCT FROM old->'password' OR next->>'email' IS NULL OR
           next->>'emailVerifiedAt' IS NOT NULL OR next->>'emailChallenge' IS NOT NULL OR
           actor->>'assurance' NOT IN ('passkey', 'step_up') THEN
            RAISE EXCEPTION 'invalid email change' USING ERRCODE = 'A0003';
        END IF;
        UPDATE aether_identity.users SET primary_email = next->>'email', version = version + 1,
            document = document || jsonb_build_object('primaryEmail', next->'email', 'version', version + 1,
                'updatedAt', audit->'occurredAt') WHERE id = uid;
    ELSIF op IN ('EMAIL_REQUEST', 'EMAIL_DELIVERED', 'EMAIL_ATTEMPT', 'EMAIL_REVOKE') THEN
        IF next->'password' IS DISTINCT FROM old->'password' THEN
            RAISE EXCEPTION 'invalid email transition' USING ERRCODE = 'A0003';
        END IF;
        challenge := old->'emailChallenge';
        IF op = 'EMAIL_REQUEST' THEN
            IF next->>'email' IS DISTINCT FROM stored_user.primary_email OR next->>'email' IS NULL OR
               next->>'emailVerifiedAt' IS NOT NULL OR next#>>'{emailChallenge,delivered}' IS DISTINCT FROM 'false' OR
               next#>>'{emailChallenge,attemptsRemaining}' IS DISTINCT FROM '5' OR
               (next#>>'{emailChallenge,sessionEpoch}')::BIGINT IS DISTINCT FROM stored_user.session_epoch OR
               (next#>>'{emailChallenge,createdAt}')::TIMESTAMPTZ IS DISTINCT FROM at_time OR
               (next#>>'{emailChallenge,expiresAt}')::TIMESTAMPTZ IS NULL OR
               (next#>>'{emailChallenge,expiresAt}')::TIMESTAMPTZ <= at_time OR
               (next#>>'{emailChallenge,expiresAt}')::TIMESTAMPTZ > at_time + INTERVAL '15 minutes' THEN
                RAISE EXCEPTION 'invalid email request' USING ERRCODE = 'A0003';
            END IF;
        ELSIF op = 'EMAIL_DELIVERED' THEN
            IF challenge IS NULL OR challenge = 'null'::JSONB OR challenge->>'delivered' <> 'false' OR
               next->'email' IS DISTINCT FROM old->'email' OR next->'emailVerifiedAt' IS DISTINCT FROM old->'emailVerifiedAt' OR
               next->'emailChallenge' IS DISTINCT FROM (challenge || '{"delivered":true}'::JSONB) THEN
                RAISE EXCEPTION 'invalid email activation' USING ERRCODE = 'A0003';
            END IF;
        ELSIF op = 'EMAIL_ATTEMPT' THEN
            IF next->>'email' IS DISTINCT FROM stored_user.primary_email OR next->'email' IS DISTINCT FROM old->'email' OR
               challenge IS NULL OR challenge = 'null'::JSONB OR challenge->>'delivered' <> 'true' OR
               (challenge->>'sessionEpoch')::BIGINT <> stored_user.session_epoch OR
               (challenge->>'expiresAt')::TIMESTAMPTZ <= at_time OR
               (stored_user.document->>'updatedAt')::TIMESTAMPTZ > (challenge->>'createdAt')::TIMESTAMPTZ OR (challenge->>'attemptsRemaining')::INTEGER <= 0 OR
               NOT ((next->>'emailChallenge' IS NULL AND (next->>'emailVerifiedAt')::TIMESTAMPTZ = at_time) OR
                   (next->>'emailVerifiedAt' IS NULL AND next->'emailChallenge' =
                       (challenge || jsonb_build_object('attemptsRemaining', (challenge->>'attemptsRemaining')::INTEGER - 1)))) THEN
                RAISE EXCEPTION 'invalid email attempt' USING ERRCODE = 'A0003';
            END IF;
        ELSE
            IF next->>'email' IS DISTINCT FROM stored_user.primary_email OR next->>'emailVerifiedAt' IS NOT NULL OR
               next->>'emailChallenge' IS NOT NULL OR actor->>'assurance' NOT IN ('passkey', 'step_up') THEN
                RAISE EXCEPTION 'invalid email revocation' USING ERRCODE = 'A0003';
            END IF;
        END IF;
    ELSE RAISE EXCEPTION 'unknown account transition' USING ERRCODE = 'A0003';
    END IF;
    INSERT INTO aether_identity.account_security(user_id, version, document) VALUES(uid, (next->>'version')::BIGINT, next)
    ON CONFLICT(user_id) DO UPDATE SET version = EXCLUDED.version, document = EXCLUDED.document;
    PERFORM aether_identity.record_audit(audit);
    RETURN aether_identity.rpc_success('mutate_account_security', next);
END;
$$;
