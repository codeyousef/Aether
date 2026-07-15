-- Aether identity migration V008
-- Atomically bind device grant exchange and refresh rotation to the active membership snapshot.

CREATE OR REPLACE FUNCTION aether_identity.v1_exchange_device_grant(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    family_document JSONB;
    access_document JSONB;
    refresh_document JSONB;
    audit_event JSONB;
    stored aether_identity.device_grants%ROWTYPE;
    membership aether_identity.memberships%ROWTYPE;
    organization aether_identity.organizations%ROWTYPE;
    stored_user aether_identity.users%ROWTYPE;
    consumed JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'exchange_device_grant');
    family_document := payload->'family';
    access_document := payload->'accessToken';
    refresh_document := payload->'refreshToken';
    audit_event := payload->'auditEvent';

    SELECT * INTO organization FROM aether_identity.organizations
     WHERE id = family_document->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device organization not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO membership FROM aether_identity.memberships
     WHERE id = family_document->>'membershipId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device membership not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored_user FROM aether_identity.users
     WHERE id = family_document->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device user not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored FROM aether_identity.device_grants
     WHERE id = payload->>'deviceGrantId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device grant not found' USING ERRCODE = 'A0012'; END IF;
    IF stored.version <> (payload->>'expectedDeviceGrantVersion')::BIGINT THEN
        RAISE EXCEPTION 'device grant version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF stored.state <> 'authorized' OR stored.expires_at <= (payload->>'exchangedAt')::TIMESTAMPTZ OR
       organization.state <> 'active' OR membership.state <> 'active' OR
       stored_user.state <> 'active' OR
       membership.version <> (family_document->>'membershipVersion')::BIGINT OR
       membership.user_id <> family_document->>'userId' OR
       membership.organization_id <> family_document->>'organizationId' OR
       family_document->>'membershipId' IS DISTINCT FROM stored.document->>'membershipId' OR
       family_document->>'membershipVersion' IS DISTINCT FROM stored.document->>'membershipVersion' OR
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
       audit_event->>'organizationId' IS DISTINCT FROM family_document->>'organizationId' OR
       audit_event->'target'->>'type' IS DISTINCT FROM 'device_grant' OR
       audit_event->'target'->>'id' IS DISTINCT FROM stored.id OR
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
    family_preview JSONB;
    family aether_identity.device_token_families%ROWTYPE;
    membership aether_identity.memberships%ROWTYPE;
    organization aether_identity.organizations%ROWTYPE;
    stored_user aether_identity.users%ROWTYPE;
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
    SELECT document INTO family_preview FROM aether_identity.device_token_families WHERE id = family_id;
    IF NOT FOUND THEN RAISE EXCEPTION 'device token family not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO organization FROM aether_identity.organizations
     WHERE id = family_preview->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device organization not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO membership FROM aether_identity.memberships
     WHERE id = family_preview->>'membershipId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device membership not found' USING ERRCODE = 'A0012'; END IF;
    SELECT * INTO stored_user FROM aether_identity.users
     WHERE id = family_preview->>'userId' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'device user not found' USING ERRCODE = 'A0012'; END IF;
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
       organization.state <> 'active' OR membership.state <> 'active' OR
       stored_user.state <> 'active' OR
       membership.version <> (family.document->>'membershipVersion')::BIGINT OR
       membership.user_id <> family.user_id OR membership.organization_id <> family.organization_id OR
       membership.id <> family.document->>'membershipId' OR
       access_document->>'familyId' <> family.id OR refresh_document->>'familyId' <> family.id OR
       (refresh_document->>'rotationCounter')::BIGINT <> previous.rotation_counter + 1 OR
       (access_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'rotatedAt')::TIMESTAMPTZ OR
       (refresh_document->>'createdAt')::TIMESTAMPTZ <> (payload->>'rotatedAt')::TIMESTAMPTZ OR
       (access_document->>'expiresAt')::TIMESTAMPTZ > family.expires_at OR
       (refresh_document->>'expiresAt')::TIMESTAMPTZ > family.expires_at OR
       audit_event->>'organizationId' IS DISTINCT FROM family.document->>'organizationId' OR
       audit_event->'target'->>'type' IS DISTINCT FROM 'device_grant' OR
       audit_event->'target'->>'id' IS DISTINCT FROM family.document->>'deviceGrantId' OR
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
