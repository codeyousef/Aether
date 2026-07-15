-- Add an atomic, audit-free sliding idle-expiration touch. Earlier migrations remain immutable.

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
    IF payload->>'sessionId' IS NULL OR
       payload->>'expectedVersion' IS NULL OR
       payload->>'lastUsedAt' IS NULL OR
       payload->>'idleExpiresAt' IS NULL THEN
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

    SELECT * INTO stored
      FROM aether_identity.sessions
     WHERE id = payload->>'sessionId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'session not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored.version <> expected_version THEN
        RAISE EXCEPTION 'session version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'active' THEN
        RAISE EXCEPTION 'session not active' USING ERRCODE = 'A0009';
    END IF;
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
