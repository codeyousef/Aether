-- Additive tenant audit read path. V001/V002 remain immutable after deployment.

CREATE INDEX IF NOT EXISTS audit_events_organization_time_id_idx
    ON aether_identity.audit_events (organization_id, occurred_at DESC, id DESC);

CREATE OR REPLACE FUNCTION aether_identity.v1_list_audit_events_for_organization(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    payload JSONB;
    requested_organization_id TEXT;
    page_limit INTEGER;
    cursor_document JSONB;
    cursor_occurred_at TIMESTAMPTZ := NULL;
    cursor_id TEXT := NULL;
    page_events JSONB;
    has_more BOOLEAN;
    next_cursor JSONB := NULL;
    last_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'list_audit_events_for_organization');
    requested_organization_id := payload->>'organizationId';
    IF requested_organization_id IS NULL OR requested_organization_id = '' THEN
        RAISE EXCEPTION 'organization audit scope is invalid' USING ERRCODE = 'A0003';
    END IF;

    BEGIN
        page_limit := (payload->>'limit')::INTEGER;
    EXCEPTION WHEN OTHERS THEN
        RAISE EXCEPTION 'organization audit limit is invalid' USING ERRCODE = 'A0003';
    END;
    IF page_limit < 1 OR page_limit > 100 THEN
        RAISE EXCEPTION 'organization audit limit is invalid' USING ERRCODE = 'A0003';
    END IF;

    cursor_document := payload->'cursor';
    IF cursor_document IS NOT NULL AND cursor_document <> 'null'::JSONB THEN
        IF jsonb_typeof(cursor_document) <> 'object' OR
           cursor_document->>'organizationId' IS NULL OR
           cursor_document->>'occurredAt' IS NULL OR cursor_document->>'id' IS NULL OR
           cursor_document->>'id' = '' THEN
            RAISE EXCEPTION 'organization audit cursor is invalid' USING ERRCODE = 'A0003';
        END IF;
        IF cursor_document->>'organizationId' <> requested_organization_id THEN
            RAISE EXCEPTION 'organization audit cursor tenant is invalid' USING ERRCODE = 'A0003';
        END IF;
        BEGIN
            cursor_occurred_at := (cursor_document->>'occurredAt')::TIMESTAMPTZ;
            cursor_id := cursor_document->>'id';
        EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'organization audit cursor is invalid' USING ERRCODE = 'A0003';
        END;
    END IF;

    WITH selected AS (
        SELECT event.document, event.occurred_at, event.id
          FROM aether_identity.audit_events AS event
         WHERE event.organization_id = requested_organization_id
           AND (
               cursor_occurred_at IS NULL OR
               (event.occurred_at, event.id) < (cursor_occurred_at, cursor_id)
           )
         ORDER BY event.occurred_at DESC, event.id DESC
         LIMIT page_limit + 1
    ), numbered AS (
        SELECT document, occurred_at, id,
               row_number() OVER (ORDER BY occurred_at DESC, id DESC) AS position
          FROM selected
    )
    SELECT COALESCE(
               jsonb_agg(document ORDER BY occurred_at DESC, id DESC)
                   FILTER (WHERE position <= page_limit),
               '[]'::JSONB
           ),
           count(*) > page_limit
      INTO page_events, has_more
      FROM numbered;

    IF has_more THEN
        last_event := page_events->(jsonb_array_length(page_events) - 1);
        next_cursor := jsonb_build_object(
            'organizationId', requested_organization_id,
            'occurredAt', last_event->'occurredAt',
            'id', last_event->'id'
        );
    END IF;

    RETURN aether_identity.rpc_success(
        'list_audit_events_for_organization',
        jsonb_build_object(
            'organizationId', requested_organization_id,
            'events', page_events,
            'nextCursor', next_cursor
        )
    );
END;
$$;
