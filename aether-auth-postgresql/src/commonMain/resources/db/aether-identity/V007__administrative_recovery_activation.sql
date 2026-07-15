-- A recovery ticket is created inactive. Successful delivery and activation become durable in one
-- transaction, so a notification sink cannot race redemption before its delivery result is audited.

CREATE OR REPLACE FUNCTION aether_identity.v1_activate_administrative_recovery_ticket(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    stored_challenge aether_identity.challenges%ROWTYPE;
    audit_event JSONB;
    activated_at TIMESTAMPTZ;
    activated JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'activate_administrative_recovery_ticket');
    audit_event := payload->'auditEvent';
    activated_at := (payload->>'activatedAt')::TIMESTAMPTZ;

    SELECT * INTO stored_challenge FROM aether_identity.challenges
     WHERE id = payload->>'challengeId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'recovery ticket not found' USING ERRCODE = 'A0012';
    END IF;
    IF stored_challenge.version IS DISTINCT FROM (payload->>'expectedChallengeVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery ticket version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_challenge.state <> 'pending' THEN
        RAISE EXCEPTION 'recovery ticket is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored_challenge.expires_at <= activated_at THEN
        RAISE EXCEPTION 'recovery ticket expired' USING ERRCODE = 'A0008';
    END IF;
    IF activated_at IS NULL OR stored_challenge.purpose <> 'account_recovery' OR
       stored_challenge.user_id IS NULL OR
       (stored_challenge.document->'activatedAt' IS NOT NULL AND
        stored_challenge.document->'activatedAt' <> 'null'::JSONB) OR
       audit_event->>'action' IS DISTINCT FROM 'recovery.admin_ticket_delivered' OR
       audit_event->>'outcome' IS DISTINCT FROM 'succeeded' OR
       audit_event#>>'{target,type}' IS DISTINCT FROM 'user' OR
       audit_event#>>'{target,id}' IS DISTINCT FROM stored_challenge.user_id OR
       (audit_event->>'occurredAt')::TIMESTAMPTZ IS DISTINCT FROM activated_at THEN
        RAISE EXCEPTION 'administrative recovery activation is invalid' USING ERRCODE = 'A0003';
    END IF;

    activated := stored_challenge.document || jsonb_build_object(
        'activatedAt', to_jsonb(activated_at),
        'version', stored_challenge.version + 1
    );
    UPDATE aether_identity.challenges
       SET version = stored_challenge.version + 1, document = activated
     WHERE id = stored_challenge.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'activate_administrative_recovery_ticket',
        jsonb_build_object('challenge', activated, 'auditEvent', audit_event)
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
    IF stored_challenge.version IS DISTINCT FROM (payload->>'expectedChallengeVersion')::BIGINT THEN
        RAISE EXCEPTION 'recovery ticket version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored_challenge.state <> 'pending' THEN
        RAISE EXCEPTION 'recovery ticket is not pending' USING ERRCODE = 'A0007';
    END IF;
    IF stored_challenge.expires_at <= (payload->>'redeemedAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'recovery ticket expired' USING ERRCODE = 'A0008';
    END IF;
    IF stored_challenge.purpose <> 'account_recovery' OR stored_challenge.user_id IS NULL OR
       stored_challenge.document->'activatedAt' IS NULL OR
       stored_challenge.document->'activatedAt' = 'null'::JSONB OR
       (stored_challenge.document->>'activatedAt')::TIMESTAMPTZ > (payload->>'redeemedAt')::TIMESTAMPTZ OR
       session_document->>'userId' IS DISTINCT FROM stored_challenge.user_id OR
       session_document->>'assurance' IS DISTINCT FROM 'recovery' OR
       session_document->>'familyId' IS DISTINCT FROM session_document->>'id' OR
       session_document->'rotatedFromId' IS DISTINCT FROM 'null'::JSONB OR
       (session_document->>'rotationCounter')::BIGINT IS DISTINCT FROM 0 OR
       (session_document->>'createdAt')::TIMESTAMPTZ IS DISTINCT FROM
           (payload->>'redeemedAt')::TIMESTAMPTZ OR
       audit_event->>'action' IS DISTINCT FROM 'recovery.admin_ticket_used' THEN
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
