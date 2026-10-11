-- Opt-in idle renewal; legacy commands retain absolute family expiry.
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
    replacement_family JSONB;
    effective_family JSONB;
    effective_expiry TIMESTAMPTZ;
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
    replacement_family := NULLIF(payload->'replacementFamily', 'null'::JSONB);
    effective_family := family.document;
    effective_expiry := family.expires_at;
    IF replacement_family IS NOT NULL THEN
        IF jsonb_typeof(replacement_family) IS DISTINCT FROM 'object' OR
           (replacement_family - 'version' - 'expiresAt') IS DISTINCT FROM
               (family.document - 'version' - 'expiresAt') OR
           (replacement_family->>'version')::BIGINT IS DISTINCT FROM family.version + 1 OR
           replacement_family->>'expiresAt' IS NULL OR
           (replacement_family->>'expiresAt')::TIMESTAMPTZ <= (payload->>'rotatedAt')::TIMESTAMPTZ OR
           (replacement_family->>'expiresAt')::TIMESTAMPTZ >
               (payload->>'rotatedAt')::TIMESTAMPTZ + INTERVAL '90 days' THEN
            RAISE EXCEPTION 'device family renewal is invalid' USING ERRCODE = 'A0003';
        END IF;
        effective_family := replacement_family;
        effective_expiry := (replacement_family->>'expiresAt')::TIMESTAMPTZ;
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
       (access_document->>'expiresAt')::TIMESTAMPTZ > effective_expiry OR
       (refresh_document->>'expiresAt')::TIMESTAMPTZ > effective_expiry OR
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
    IF replacement_family IS NOT NULL THEN
        UPDATE aether_identity.device_token_families
           SET version = (replacement_family->>'version')::BIGINT,
               expires_at = effective_expiry, document = replacement_family
         WHERE id = family.id;
    END IF;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'rotate_device_refresh_token',
        jsonb_build_object(
            'family', effective_family, 'previousRefreshToken', rotated,
            'accessToken', access_document, 'refreshToken', refresh_document,
            'auditEvent', audit_event
        )
    );
END;
$$;
