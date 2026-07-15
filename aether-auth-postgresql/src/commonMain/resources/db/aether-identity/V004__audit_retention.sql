-- Bounded, storage-neutral audit retention. Earlier migrations remain immutable.

CREATE INDEX IF NOT EXISTS audit_events_retention_idx
    ON aether_identity.audit_events (occurred_at ASC, id ASC);

CREATE OR REPLACE FUNCTION aether_identity.v1_purge_audit_events(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
VOLATILE
AS $$
DECLARE
    payload JSONB;
    cutoff TIMESTAMPTZ;
    maximum_events INTEGER;
    deleted_count INTEGER;
    has_more BOOLEAN;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'purge_audit_events');
    IF payload->>'occurredBefore' IS NULL OR payload->>'maximumEvents' IS NULL THEN
        RAISE EXCEPTION 'audit retention request is invalid' USING ERRCODE = 'A0003';
    END IF;
    BEGIN
        cutoff := (payload->>'occurredBefore')::TIMESTAMPTZ;
        maximum_events := (payload->>'maximumEvents')::INTEGER;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'audit retention request is invalid' USING ERRCODE = 'A0003';
    END;
    IF maximum_events < 1 OR maximum_events > 500 THEN
        RAISE EXCEPTION 'audit retention batch is invalid' USING ERRCODE = 'A0003';
    END IF;

    WITH selected AS (
        SELECT event.id
          FROM aether_identity.audit_events AS event
         WHERE event.occurred_at < cutoff
         ORDER BY event.occurred_at ASC, event.id ASC
         FOR UPDATE SKIP LOCKED
         LIMIT maximum_events
    ), deleted AS (
        DELETE FROM aether_identity.audit_events AS event
         USING selected
         WHERE event.id = selected.id
         RETURNING event.id
    )
    SELECT count(*) INTO deleted_count FROM deleted;

    SELECT EXISTS (
        SELECT 1
          FROM aether_identity.audit_events AS event
         WHERE event.occurred_at < cutoff
    ) INTO has_more;

    RETURN aether_identity.rpc_success(
        'purge_audit_events',
        jsonb_build_object('deletedCount', deleted_count, 'hasMore', has_more)
    );
END;
$$;
