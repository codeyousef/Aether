-- Expiry is a terminal transition at or after the deadline, never a successful consumption.
-- Existing row lock, expected-version CAS and caller transaction/audit atomicity are retained.
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
    IF p_consumed_at IS NULL OR p_terminal_state IS NULL OR
       p_terminal_state NOT IN ('consumed', 'failed', 'expired') THEN
        RAISE EXCEPTION 'invalid terminal challenge transition' USING ERRCODE = 'A0003';
    END IF;
    IF p_terminal_state = 'expired' THEN
        IF p_consumed_at < stored.expires_at THEN
            RAISE EXCEPTION 'challenge has not expired' USING ERRCODE = 'A0003';
        END IF;
    ELSIF stored.expires_at <= p_consumed_at THEN
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

