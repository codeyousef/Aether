ALTER TABLE aether_identity.sessions
    ADD COLUMN authentication_method TEXT,
    ADD COLUMN federation_organization_id TEXT,
    ADD COLUMN federation_provider_key TEXT,
    ADD COLUMN external_identity_id TEXT;

-- V001 predates explicit provenance. Its passkey/recovery documents can be classified safely;
-- new federated sessions already carry all three provenance values in their canonical document.
UPDATE aether_identity.sessions
   SET authentication_method = COALESCE(
           document->>'authenticationMethod',
           CASE WHEN document->>'assurance' = 'recovery' THEN 'recovery_code' ELSE 'passkey' END
       ),
       federation_organization_id = document->>'federationOrganizationId',
       federation_provider_key = document->>'federationProviderKey',
       external_identity_id = document->>'externalIdentityId';

UPDATE aether_identity.sessions
   SET document = jsonb_set(document, '{authenticationMethod}', to_jsonb(authentication_method))
 WHERE document->>'authenticationMethod' IS NULL;

ALTER TABLE aether_identity.sessions
    ALTER COLUMN authentication_method SET NOT NULL,
    ADD CONSTRAINT sessions_authentication_method_check CHECK (
        authentication_method IN (
            'passkey', 'recovery_code', 'administrative_recovery', 'bootstrap', 'invitation', 'oidc', 'saml'
        )
    ),
    ADD CONSTRAINT sessions_authentication_method_document_check CHECK (
        document->>'authenticationMethod' = authentication_method
    ),
    ADD CONSTRAINT sessions_authentication_assurance_check CHECK (
        (authentication_method = 'passkey' AND document->>'assurance' IN ('passkey', 'step_up')) OR
        (authentication_method IN ('recovery_code', 'administrative_recovery', 'bootstrap', 'invitation') AND
         document->>'assurance' = 'recovery') OR
        (authentication_method IN ('oidc', 'saml') AND document->>'assurance' = 'session')
    ),
    ADD CONSTRAINT sessions_federation_organization_document_check CHECK (
        (document->>'federationOrganizationId') IS NOT DISTINCT FROM federation_organization_id
    ),
    ADD CONSTRAINT sessions_federation_provider_document_check CHECK (
        (document->>'federationProviderKey') IS NOT DISTINCT FROM federation_provider_key
    ),
    ADD CONSTRAINT sessions_external_identity_document_check CHECK (
        (document->>'externalIdentityId') IS NOT DISTINCT FROM external_identity_id
    ),
    ADD CONSTRAINT sessions_federation_provenance_check CHECK (
        (
            authentication_method IN ('oidc', 'saml') AND
            federation_organization_id IS NOT NULL AND
            federation_provider_key IS NOT NULL AND
            length(federation_provider_key) BETWEEN 1 AND 512 AND
            federation_provider_key ~ '[^[:space:]]' AND
            external_identity_id IS NOT NULL
        ) OR (
            authentication_method NOT IN ('oidc', 'saml') AND
            federation_organization_id IS NULL AND
            federation_provider_key IS NULL AND
            external_identity_id IS NULL
        )
    );

CREATE INDEX sessions_active_federation_provider_idx
    ON aether_identity.sessions (
        federation_organization_id, federation_provider_key, authentication_method, id
    )
    WHERE state = 'active' AND authentication_method IN ('oidc', 'saml');

CREATE INDEX sessions_external_identity_idx
    ON aether_identity.sessions (external_identity_id, id)
    WHERE external_identity_id IS NOT NULL;

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
        authentication_method, federation_organization_id, federation_provider_key,
        external_identity_id, state, user_session_epoch, version,
        idle_expires_at, absolute_expires_at, document
    ) VALUES (
        p_session->>'id', p_session->>'familyId', p_session->>'userId',
        p_session#>>'{tokenDigest,algorithm}', p_session#>>'{tokenDigest,encoded}',
        p_session#>>'{tokenDigest,keyVersion}',
        p_session#>>'{csrfDigest,algorithm}', p_session#>>'{csrfDigest,encoded}',
        p_session#>>'{csrfDigest,keyVersion}',
        p_session->>'authenticationMethod', p_session->>'federationOrganizationId',
        p_session->>'federationProviderKey', p_session->>'externalIdentityId',
        p_session->>'state', (p_session->>'userSessionEpoch')::BIGINT,
        (p_session->>'version')::BIGINT,
        (p_session->>'idleExpiresAt')::TIMESTAMPTZ,
        (p_session->>'absoluteExpiresAt')::TIMESTAMPTZ, p_session
    );
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
    enrollment_session JSONB;
    audit_event JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'bootstrap_identity');
    receipt := payload->'bootstrapSecretDigest';
    user_document := payload->'user';
    organization_document := payload->'organization';
    membership_document := payload->'ownerMembership';
    enrollment_session := payload->'enrollmentSession';
    audit_event := payload->'auditEvent';

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
       enrollment_session->>'state' IS DISTINCT FROM 'active' OR
       (enrollment_session->>'version')::BIGINT IS DISTINCT FROM 0 OR
       enrollment_session->>'userId' IS DISTINCT FROM user_document->>'id' OR
       (enrollment_session->>'userSessionEpoch')::BIGINT IS DISTINCT FROM
           (user_document->>'sessionEpoch')::BIGINT OR
       enrollment_session->>'assurance' IS DISTINCT FROM 'recovery' OR
       enrollment_session->>'authenticationMethod' IS DISTINCT FROM 'bootstrap' OR
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
    PERFORM aether_identity.insert_session(enrollment_session);
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'bootstrap_identity',
        jsonb_build_object(
            'user', user_document,
            'organization', organization_document,
            'ownerMembership', membership_document,
            'enrollmentSession', enrollment_session,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_enroll_invitation(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    user_document JSONB;
    membership_document JSONB;
    session_document JSONB;
    audit_event JSONB;
    invitation aether_identity.invitations%ROWTYPE;
    organization aether_identity.organizations%ROWTYPE;
    accepted_invitation JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'enroll_invitation');
    user_document := payload->'user';
    membership_document := payload->'membership';
    session_document := payload->'enrollmentSession';
    audit_event := payload->'auditEvent';

    SELECT * INTO invitation
      FROM aether_identity.invitations
     WHERE id = payload->>'invitationId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'invitation not found' USING ERRCODE = 'A0012';
    END IF;
    IF invitation.version <> (payload->>'expectedInvitationVersion')::BIGINT THEN
        RAISE EXCEPTION 'invitation version conflict' USING ERRCODE = 'A0002';
    END IF;
    IF invitation.token_digest_algorithm IS DISTINCT FROM payload#>>'{expectedTokenDigest,algorithm}' OR
       invitation.token_digest_encoded IS DISTINCT FROM payload#>>'{expectedTokenDigest,encoded}' OR
       COALESCE(invitation.token_digest_key_version, '') IS DISTINCT FROM
           COALESCE(payload#>>'{expectedTokenDigest,keyVersion}', '') THEN
        RAISE EXCEPTION 'invitation credential mismatch' USING ERRCODE = 'A0012';
    END IF;
    IF invitation.state <> 'pending' OR
       (invitation.document->>'expiresAt')::TIMESTAMPTZ <= (payload->>'enrolledAt')::TIMESTAMPTZ THEN
        RAISE EXCEPTION 'invitation is not enrollable' USING ERRCODE = 'A0003';
    END IF;

    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = invitation.organization_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'invitation organization not found' USING ERRCODE = 'A0012';
    END IF;
    IF organization.state <> 'active' OR
       user_document->>'state' IS DISTINCT FROM 'active' OR
       (user_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       (user_document->>'sessionEpoch')::BIGINT IS DISTINCT FROM 0 OR
       lower(user_document->>'primaryEmail') IS DISTINCT FROM lower(invitation.email) OR
       user_document->>'createdAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       user_document->>'updatedAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       user_document->>'activatedAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       membership_document->>'organizationId' IS DISTINCT FROM invitation.organization_id OR
       membership_document->>'userId' IS DISTINCT FROM user_document->>'id' OR
       membership_document->>'role' IS DISTINCT FROM invitation.document->>'role' OR
       membership_document->>'state' IS DISTINCT FROM 'active' OR
       (membership_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       membership_document->>'createdAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       membership_document->>'updatedAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       session_document->>'userId' IS DISTINCT FROM user_document->>'id' OR
       session_document->>'familyId' IS DISTINCT FROM session_document->>'id' OR
       session_document->>'state' IS DISTINCT FROM 'active' OR
       (session_document->>'version')::BIGINT IS DISTINCT FROM 0 OR
       (session_document->>'rotationCounter')::BIGINT IS DISTINCT FROM 0 OR
       (session_document->>'userSessionEpoch')::BIGINT IS DISTINCT FROM 0 OR
       session_document->>'assurance' IS DISTINCT FROM 'recovery' OR
       session_document->>'authenticationMethod' IS DISTINCT FROM 'invitation' OR
       session_document->>'createdAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       session_document->>'authenticatedAt' IS DISTINCT FROM payload->>'enrolledAt' OR
       (session_document->>'idleExpiresAt')::TIMESTAMPTZ IS DISTINCT FROM
           (payload->>'enrolledAt')::TIMESTAMPTZ + INTERVAL '15 minutes' OR
       (session_document->>'absoluteExpiresAt')::TIMESTAMPTZ IS DISTINCT FROM
           (payload->>'enrolledAt')::TIMESTAMPTZ + INTERVAL '15 minutes' OR
       session_document->>'rotatedFromId' IS NOT NULL OR
       audit_event->>'action' IS DISTINCT FROM 'invitation.accepted' OR
       audit_event->>'organizationId' IS DISTINCT FROM invitation.organization_id OR
       audit_event#>>'{target,type}' IS DISTINCT FROM 'invitation' OR
       audit_event#>>'{target,id}' IS DISTINCT FROM invitation.id OR
       audit_event->>'occurredAt' IS DISTINCT FROM payload->>'enrolledAt' THEN
        RAISE EXCEPTION 'invitation enrollment is invalid' USING ERRCODE = 'A0003';
    END IF;

    PERFORM aether_identity.insert_user(user_document);
    PERFORM aether_identity.insert_membership(membership_document);
    PERFORM aether_identity.insert_session(session_document);
    accepted_invitation := invitation.document || jsonb_build_object(
        'state', 'accepted',
        'version', invitation.version + 1,
        'acceptedAt', payload->'enrolledAt',
        'acceptedByUserId', user_document->'id'
    );
    UPDATE aether_identity.invitations
       SET state = 'accepted',
           version = invitation.version + 1,
           document = accepted_invitation
     WHERE id = invitation.id;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'enroll_invitation',
        jsonb_build_object(
            'invitation', accepted_invitation,
            'user', user_document,
            'membership', membership_document,
            'enrollmentSession', session_document,
            'auditEvent', audit_event
        )
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_revoke_federated_sessions(p_request JSONB)
RETURNS JSONB LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    audit_event JSONB;
    revoked_ids JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'revoke_federated_sessions');
    audit_event := payload->'auditEvent';
    PERFORM 1 FROM aether_identity.organizations
     WHERE id = payload->>'organizationId' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'federation organization not found' USING ERRCODE = 'A0012';
    END IF;
    IF COALESCE(length(payload->>'providerKey'), 0) NOT BETWEEN 1 AND 512 OR
       COALESCE(payload->>'providerKey', '') !~ '[^[:space:]]' OR
       COALESCE(length(payload->>'reasonCode'), 0) NOT BETWEEN 1 AND 200 OR
       COALESCE(payload->>'reasonCode', '') !~ '[^[:space:]]' OR
       audit_event->>'organizationId' IS DISTINCT FROM payload->>'organizationId' OR
       audit_event->>'action' IS DISTINCT FROM 'session.revoked' THEN
        RAISE EXCEPTION 'federated session revocation is invalid' USING ERRCODE = 'A0003';
    END IF;
    IF EXISTS (
        SELECT 1 FROM aether_identity.sessions
         WHERE state = 'active' AND
               authentication_method IN ('oidc', 'saml') AND
               federation_organization_id = payload->>'organizationId' AND
               federation_provider_key = payload->>'providerKey' AND
               (document->>'createdAt')::TIMESTAMPTZ > (payload->>'revokedAt')::TIMESTAMPTZ
    ) THEN
        RAISE EXCEPTION 'federated session revocation precedes creation' USING ERRCODE = 'A0003';
    END IF;
    WITH revoked AS (
        UPDATE aether_identity.sessions
           SET state = 'revoked',
               version = version + 1,
               document = document || jsonb_build_object(
                   'state', 'revoked',
                   'version', version + 1,
                   'revokedAt', payload->'revokedAt',
                   'revocationReasonCode', payload->'reasonCode'
               )
         WHERE state = 'active' AND
               authentication_method IN ('oidc', 'saml') AND
               federation_organization_id = payload->>'organizationId' AND
               federation_provider_key = payload->>'providerKey'
        RETURNING id
    )
    SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB)
      INTO revoked_ids FROM revoked;
    PERFORM aether_identity.record_audit(audit_event);
    RETURN aether_identity.rpc_success(
        'revoke_federated_sessions',
        jsonb_build_object(
            'organizationId', payload->'organizationId',
            'providerKey', payload->'providerKey',
            'revokedSessionIds', revoked_ids,
            'auditEvent', audit_event
        )
    );
END;
$$;

-- V002 also freezes the complete SCIM command as the idempotency fingerprint. V001 stored only
-- the mutation document, so existing receipts are losslessly backfilled from their canonical
-- commit before the column becomes mandatory.
ALTER TABLE aether_identity.scim_operations
    ADD COLUMN command_document JSONB;

UPDATE aether_identity.scim_operations
   SET command_document = jsonb_build_object(
       'mutation', mutation,
       'auditEvent', commit_result->'auditEvent'
   );

ALTER TABLE aether_identity.scim_operations
    ALTER COLUMN command_document SET NOT NULL;

CREATE TABLE aether_identity.scim_groups (
    id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    provider TEXT NOT NULL,
    external_id TEXT,
    state TEXT NOT NULL CHECK (state IN ('active', 'deleted')),
    version BIGINT NOT NULL CHECK (version >= 1),
    document JSONB NOT NULL,
    CHECK (document->>'id' = id),
    CHECK (document->>'organizationId' = organization_id),
    CHECK (document->>'provider' = provider),
    CHECK ((document->>'version')::BIGINT = version),
    CHECK (document->>'state' = state),
    CHECK ((document->>'externalId') IS NOT DISTINCT FROM external_id)
);

CREATE INDEX scim_groups_provider_organization_idx
    ON aether_identity.scim_groups (provider, organization_id, id);

CREATE TABLE aether_identity.scim_batch_operations (
    operation_id TEXT PRIMARY KEY,
    organization_id TEXT NOT NULL REFERENCES aether_identity.organizations(id),
    provider TEXT NOT NULL,
    command_document JSONB NOT NULL,
    commit_result JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION aether_identity.sorted_distinct_text_jsonb(p_values JSONB)
RETURNS JSONB
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT COALESCE(jsonb_agg(to_jsonb(value) ORDER BY value), '[]'::JSONB)
      FROM (
          SELECT DISTINCT jsonb_array_elements_text(COALESCE(p_values, '[]'::JSONB)) AS value
      ) AS distinct_values;
$$;

-- Shared by the legacy one-mutation RPC and the all-or-nothing batch RPC. The caller decides
-- whether last-owner protection is evaluated per mutation or once against the batch final state.
CREATE OR REPLACE FUNCTION aether_identity.apply_scim_mutation_command(
    p_command JSONB,
    p_enforce_last_owner BOOLEAN
) RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    mutation_document JSONB;
    audit_event JSONB;
    user_document JSONB;
    membership_document JSONB;
    stored_user aether_identity.users%ROWTYPE;
    current_membership aether_identity.memberships%ROWTYPE;
    existing_command JSONB;
    existing_commit JSONB;
    commit_result JSONB;
    remaining_owners BIGINT;
BEGIN
    IF jsonb_typeof(p_command) IS DISTINCT FROM 'object' OR
       jsonb_typeof(p_command->'mutation') IS DISTINCT FROM 'object' OR
       jsonb_typeof(p_command->'auditEvent') IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'SCIM mutation command is invalid' USING ERRCODE = 'A0003';
    END IF;
    mutation_document := p_command->'mutation';
    audit_event := p_command->'auditEvent';

    IF COALESCE(length(mutation_document->>'operationId'), 0) = 0 OR
       COALESCE(length(mutation_document->>'provider'), 0) NOT BETWEEN 1 AND 512 OR
       COALESCE(mutation_document->>'provider', '') !~ '[^[:space:]]' OR
       audit_event->>'action' IS DISTINCT FROM 'scim.mutation_applied' THEN
        RAISE EXCEPTION 'SCIM mutation command is invalid' USING ERRCODE = 'A0003';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtext('aether_identity.scim:' || (mutation_document->>'operationId'))
    );
    SELECT scim_operations.command_document, scim_operations.commit_result
      INTO existing_command, existing_commit
      FROM aether_identity.scim_operations
     WHERE operation_id = mutation_document->>'operationId'
     FOR UPDATE;
    IF FOUND THEN
        IF existing_command IS DISTINCT FROM p_command THEN
            RAISE EXCEPTION 'SCIM operation idempotency conflict' USING ERRCODE = 'A0006';
        END IF;
        RETURN existing_commit || jsonb_build_object(
            'alreadyApplied', TRUE,
            'auditEvent', 'null'::JSONB
        );
    END IF;

    IF mutation_document->>'type' IN ('upsert_user', 'deactivate_user') THEN
        user_document := mutation_document->'user';
        membership_document := 'null'::JSONB;
        IF user_document IS NULL OR user_document = 'null'::JSONB OR
           (mutation_document->>'type' = 'deactivate_user' AND
            user_document->>'state' IS DISTINCT FROM 'deactivated') THEN
            RAISE EXCEPTION 'SCIM user mutation is invalid' USING ERRCODE = 'A0003';
        END IF;
        SELECT * INTO stored_user
          FROM aether_identity.users
         WHERE id = user_document->>'id'
         FOR UPDATE;
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
           (mutation_document->>'type' = 'remove_membership' AND
            membership_document->>'state' IS DISTINCT FROM 'removed') THEN
            RAISE EXCEPTION 'SCIM membership mutation is invalid' USING ERRCODE = 'A0003';
        END IF;
        PERFORM 1 FROM aether_identity.organizations
         WHERE id = membership_document->>'organizationId' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'SCIM organization not found' USING ERRCODE = 'A0012';
        END IF;
        PERFORM 1 FROM aether_identity.users
         WHERE id = membership_document->>'userId' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'SCIM membership user not found' USING ERRCODE = 'A0012';
        END IF;
        SELECT * INTO current_membership
          FROM aether_identity.memberships
         WHERE id = membership_document->>'id'
         FOR UPDATE;
        IF NOT FOUND THEN
            IF (membership_document->>'version')::BIGINT IS DISTINCT FROM 0 THEN
                RAISE EXCEPTION 'new SCIM membership version is invalid' USING ERRCODE = 'A0003';
            END IF;
            INSERT INTO aether_identity.memberships(
                id, organization_id, user_id, role, state, version, document
            ) VALUES (
                membership_document->>'id', membership_document->>'organizationId',
                membership_document->>'userId', membership_document->>'role',
                membership_document->>'state', (membership_document->>'version')::BIGINT,
                membership_document
            );
        ELSE
            IF current_membership.organization_id IS DISTINCT FROM membership_document->>'organizationId' OR
               current_membership.user_id IS DISTINCT FROM membership_document->>'userId' OR
               (membership_document->>'version')::BIGINT IS DISTINCT FROM current_membership.version + 1 THEN
                RAISE EXCEPTION 'SCIM membership version conflict' USING ERRCODE = 'A0002';
            END IF;
            IF p_enforce_last_owner AND
               current_membership.role = 'owner' AND current_membership.state = 'active' AND
               NOT (membership_document->>'role' = 'owner' AND
                    membership_document->>'state' = 'active') THEN
                SELECT COUNT(*) INTO remaining_owners
                  FROM aether_identity.memberships
                 WHERE organization_id = current_membership.organization_id AND
                       id <> current_membership.id AND role = 'owner' AND state = 'active';
                IF remaining_owners = 0 THEN
                    RAISE EXCEPTION 'last owner cannot be removed' USING ERRCODE = 'A0004';
                END IF;
            END IF;
            UPDATE aether_identity.memberships
               SET role = membership_document->>'role',
                   state = membership_document->>'state',
                   version = (membership_document->>'version')::BIGINT,
                   document = membership_document
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
    INSERT INTO aether_identity.scim_operations(
        operation_id, provider, mutation, command_document, commit_result, occurred_at
    ) VALUES (
        mutation_document->>'operationId', mutation_document->>'provider', mutation_document,
        p_command, commit_result, (mutation_document->>'occurredAt')::TIMESTAMPTZ
    );
    RETURN commit_result;
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_apply_scim_mutation(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'apply_scim_mutation');
    RETURN aether_identity.rpc_success(
        'apply_scim_mutation',
        aether_identity.apply_scim_mutation_command(payload, TRUE)
    );
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_find_scim_group(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'find_scim_group');
    SELECT document INTO result
      FROM aether_identity.scim_groups
     WHERE id = payload->>'id' AND
           organization_id = payload->>'organizationId' AND
           provider = payload->>'provider';
    RETURN aether_identity.rpc_success('find_scim_group', result);
END;
$$;

CREATE OR REPLACE FUNCTION aether_identity.v1_apply_scim_batch(p_request JSONB)
RETURNS JSONB
LANGUAGE plpgsql
AS $$
DECLARE
    payload JSONB;
    organization aether_identity.organizations%ROWTYPE;
    existing_command JSONB;
    existing_commit JSONB;
    replay_mutation_commits JSONB;
    mutations JSONB;
    revocations JSONB;
    group_document JSONB;
    audit_event JSONB;
    expected_group_version BIGINT;
    current_group aether_identity.scim_groups%ROWTYPE;
    child JSONB;
    revocation JSONB;
    mutation_commits JSONB := '[]'::JSONB;
    revoked_session_ids JSONB := '[]'::JSONB;
    revoked_family_ids JSONB := '[]'::JSONB;
    revoked_access_ids JSONB := '[]'::JSONB;
    revoked_refresh_ids JSONB := '[]'::JSONB;
    newly_revoked JSONB;
    current_owner_count BIGINT;
    prospective_owner_count BIGINT;
    distinct_count BIGINT;
    total_count BIGINT;
    commit_result JSONB;
BEGIN
    payload := aether_identity.rpc_payload(p_request, 'apply_scim_batch');
    mutations := payload->'mutations';
    revocations := payload->'revocations';
    group_document := payload->'group';
    audit_event := payload->'auditEvent';

    IF jsonb_typeof(payload) IS DISTINCT FROM 'object' OR
       COALESCE(length(payload->>'operationId'), 0) = 0 OR
       COALESCE(length(payload->>'provider'), 0) NOT BETWEEN 1 AND 512 OR
       COALESCE(payload->>'provider', '') !~ '[^[:space:]]' OR
       jsonb_typeof(mutations) IS DISTINCT FROM 'array' OR
       jsonb_typeof(revocations) IS DISTINCT FROM 'array' OR
       jsonb_array_length(mutations) > 10000 OR
       jsonb_array_length(revocations) > 10000 OR
       jsonb_typeof(audit_event) IS DISTINCT FROM 'object' OR
       audit_event->>'organizationId' IS DISTINCT FROM payload->>'organizationId' THEN
        RAISE EXCEPTION 'SCIM batch command is invalid' USING ERRCODE = 'A0003';
    END IF;

    PERFORM pg_advisory_xact_lock(
        hashtext('aether_identity.scim_batch:' || (payload->>'operationId'))
    );
    SELECT command_document, scim_batch_operations.commit_result
      INTO existing_command, existing_commit
      FROM aether_identity.scim_batch_operations
     WHERE operation_id = payload->>'operationId'
     FOR UPDATE;
    IF FOUND THEN
        IF existing_command IS DISTINCT FROM payload THEN
            RAISE EXCEPTION 'SCIM batch idempotency conflict' USING ERRCODE = 'A0006';
        END IF;
        SELECT COALESCE(
                   jsonb_agg(
                       value || jsonb_build_object(
                           'alreadyApplied', TRUE,
                           'auditEvent', 'null'::JSONB
                       ) ORDER BY ordinal
                   ),
                   '[]'::JSONB
               )
          INTO replay_mutation_commits
          FROM jsonb_array_elements(existing_commit->'mutationCommits')
               WITH ORDINALITY AS replay(value, ordinal);
        RETURN aether_identity.rpc_success(
            'apply_scim_batch',
            existing_commit || jsonb_build_object(
                'mutationCommits', replay_mutation_commits,
                'alreadyApplied', TRUE,
                'auditEvent', 'null'::JSONB
            )
        );
    END IF;

    SELECT * INTO organization
      FROM aether_identity.organizations
     WHERE id = payload->>'organizationId'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'SCIM organization not found' USING ERRCODE = 'A0012';
    END IF;
    IF organization.state <> 'active' THEN
        RAISE EXCEPTION 'SCIM organization is not active' USING ERRCODE = 'A0003';
    END IF;

    -- Validate the cross-command invariants before taking any child receipt locks or applying work.
    IF EXISTS (
        SELECT 1
          FROM jsonb_array_elements(mutations) AS mutation(value)
         WHERE jsonb_typeof(value) IS DISTINCT FROM 'object' OR
               jsonb_typeof(value->'mutation') IS DISTINCT FROM 'object' OR
               jsonb_typeof(value->'auditEvent') IS DISTINCT FROM 'object' OR
               value#>>'{mutation,provider}' IS DISTINCT FROM payload->>'provider' OR
               value#>>'{auditEvent,organizationId}' IS DISTINCT FROM payload->>'organizationId' OR
               value#>>'{auditEvent,action}' IS DISTINCT FROM 'scim.mutation_applied' OR
               (
                   value#>>'{mutation,type}' IN ('upsert_membership', 'remove_membership') AND
                   value#>>'{mutation,membership,organizationId}' IS DISTINCT FROM payload->>'organizationId'
               )
    ) THEN
        RAISE EXCEPTION 'SCIM child mutation is outside the batch tenant' USING ERRCODE = 'A0003';
    END IF;

    SELECT COUNT(*), COUNT(DISTINCT value#>>'{mutation,operationId}')
      INTO total_count, distinct_count
      FROM jsonb_array_elements(mutations) AS mutation(value);
    IF total_count <> distinct_count THEN
        RAISE EXCEPTION 'SCIM child operation ids are not unique' USING ERRCODE = 'A0003';
    END IF;
    SELECT COUNT(*), COUNT(DISTINCT audit_id)
      INTO total_count, distinct_count
      FROM (
          SELECT value#>>'{auditEvent,id}' AS audit_id
            FROM jsonb_array_elements(mutations) AS mutation(value)
          UNION ALL
          SELECT audit_event->>'id'
      ) AS audit_ids;
    IF total_count <> distinct_count THEN
        RAISE EXCEPTION 'SCIM audit ids are not unique' USING ERRCODE = 'A0003';
    END IF;
    SELECT COUNT(*), COUNT(DISTINCT value#>>'{mutation,user,id}')
      INTO total_count, distinct_count
      FROM jsonb_array_elements(mutations) AS mutation(value)
     WHERE value#>>'{mutation,user,id}' IS NOT NULL;
    IF total_count <> distinct_count THEN
        RAISE EXCEPTION 'SCIM users are mutated more than once' USING ERRCODE = 'A0003';
    END IF;
    SELECT COUNT(*), COUNT(DISTINCT value#>>'{mutation,membership,id}')
      INTO total_count, distinct_count
      FROM jsonb_array_elements(mutations) AS mutation(value)
     WHERE value#>>'{mutation,membership,id}' IS NOT NULL;
    IF total_count <> distinct_count THEN
        RAISE EXCEPTION 'SCIM memberships are mutated more than once' USING ERRCODE = 'A0003';
    END IF;
    SELECT COUNT(*), COUNT(DISTINCT value->>'userId')
      INTO total_count, distinct_count
      FROM jsonb_array_elements(revocations) AS tenant_revocation(value);
    IF total_count <> distinct_count OR EXISTS (
        SELECT 1 FROM jsonb_array_elements(revocations) AS tenant_revocation(value)
         WHERE COALESCE(length(value->>'reasonCode'), 0) NOT BETWEEN 1 AND 200 OR
               COALESCE(value->>'reasonCode', '') !~ '[^[:space:]]' OR
               NOT (
                   COALESCE((value->>'revokeSessions')::BOOLEAN, FALSE) OR
                   COALESCE((value->>'revokeDeviceTokenFamilies')::BOOLEAN, FALSE)
               )
    ) THEN
        RAISE EXCEPTION 'SCIM tenant revocations are invalid' USING ERRCODE = 'A0003';
    END IF;

    IF group_document IS NULL OR group_document = 'null'::JSONB THEN
        IF payload->'expectedGroupVersion' IS NOT NULL AND
           payload->'expectedGroupVersion' <> 'null'::JSONB THEN
            RAISE EXCEPTION 'SCIM group version without a group' USING ERRCODE = 'A0003';
        END IF;
        group_document := 'null'::JSONB;
        IF audit_event->>'action' IS DISTINCT FROM 'scim.mutation_applied' THEN
            RAISE EXCEPTION 'SCIM user batch audit is invalid' USING ERRCODE = 'A0003';
        END IF;
    ELSE
        expected_group_version := (payload->>'expectedGroupVersion')::BIGINT;
        IF expected_group_version IS NULL OR expected_group_version < 0 OR
           (group_document->>'version')::BIGINT IS DISTINCT FROM expected_group_version + 1 OR
           group_document->>'id' !~ '^[A-Za-z0-9_-][A-Za-z0-9._:-]{0,254}$' OR
           group_document->>'organizationId' IS DISTINCT FROM payload->>'organizationId' OR
           group_document->>'provider' IS DISTINCT FROM payload->>'provider' OR
           audit_event->>'action' IS DISTINCT FROM 'scim.group_changed' OR
           audit_event#>>'{target,type}' IS DISTINCT FROM 'scim_group' OR
           audit_event#>>'{target,id}' IS DISTINCT FROM group_document->>'id' OR
           COALESCE(length(group_document->>'displayName'), 0) NOT BETWEEN 1 AND 200 OR
           COALESCE(group_document->>'displayName', '') !~ '[^[:space:]]' OR
           (
               group_document->>'externalId' IS NOT NULL AND
               (
                   length(group_document->>'externalId') NOT BETWEEN 1 AND 1024 OR
                   COALESCE(group_document->>'externalId', '') !~ '[^[:space:]]'
               )
           ) OR
           group_document->>'state' NOT IN ('active', 'deleted') OR
           jsonb_typeof(group_document->'memberUserIds') IS DISTINCT FROM 'array' OR
           jsonb_array_length(group_document->'memberUserIds') > 5000 OR
           (
               SELECT COUNT(*) FROM jsonb_array_elements_text(group_document->'memberUserIds')
           ) <> (
               SELECT COUNT(DISTINCT value)
                 FROM jsonb_array_elements_text(group_document->'memberUserIds') AS member(value)
           ) OR
           (group_document->>'updatedAt')::TIMESTAMPTZ < (group_document->>'createdAt')::TIMESTAMPTZ OR
           (group_document->>'state' = 'active' AND group_document->>'deletedAt' IS NOT NULL) OR
           (group_document->>'state' = 'deleted' AND group_document->>'deletedAt' IS NULL) OR
           (
               group_document->>'deletedAt' IS NOT NULL AND
               (group_document->>'deletedAt')::TIMESTAMPTZ <
                   (group_document->>'createdAt')::TIMESTAMPTZ
           ) THEN
            RAISE EXCEPTION 'SCIM group command is invalid' USING ERRCODE = 'A0003';
        END IF;
    END IF;

    -- Lock child receipts in a stable order to avoid two overlapping batches deadlocking.
    PERFORM pg_advisory_xact_lock(hashtext('aether_identity.scim:' || operation_id))
      FROM (
          SELECT value#>>'{mutation,operationId}' AS operation_id
            FROM jsonb_array_elements(mutations) AS mutation(value)
           ORDER BY operation_id
      ) AS ordered_operations;

    -- Batch last-owner protection evaluates the complete membership fan-out, not mutation order.
    PERFORM 1
      FROM aether_identity.memberships
     WHERE organization_id = organization.id
     ORDER BY id
     FOR UPDATE;
    SELECT COUNT(*) INTO current_owner_count
      FROM aether_identity.memberships
     WHERE organization_id = organization.id AND role = 'owner' AND state = 'active';
    WITH replacements AS (
        SELECT value->'mutation'->'membership' AS document
          FROM jsonb_array_elements(mutations) AS mutation(value)
         WHERE value#>>'{mutation,type}' IN ('upsert_membership', 'remove_membership')
    ), prospective AS (
        SELECT membership.document
          FROM aether_identity.memberships AS membership
         WHERE membership.organization_id = organization.id AND
               NOT EXISTS (
                   SELECT 1 FROM replacements
                    WHERE replacements.document->>'id' = membership.id
               )
        UNION ALL
        SELECT document FROM replacements
    )
    SELECT COUNT(*) INTO prospective_owner_count
      FROM prospective
     WHERE document->>'organizationId' = organization.id AND
           document->>'role' = 'owner' AND document->>'state' = 'active';
    IF current_owner_count > 0 AND prospective_owner_count = 0 THEN
        RAISE EXCEPTION 'last owner cannot be removed' USING ERRCODE = 'A0004';
    END IF;

    FOR child IN SELECT value FROM jsonb_array_elements(mutations) AS mutation(value)
    LOOP
        mutation_commits := mutation_commits || jsonb_build_array(
            aether_identity.apply_scim_mutation_command(child, FALSE)
        );
    END LOOP;

    IF group_document <> 'null'::JSONB THEN
        SELECT * INTO current_group
          FROM aether_identity.scim_groups
         WHERE id = group_document->>'id'
         FOR UPDATE;
        IF expected_group_version = 0 THEN
            IF FOUND THEN
                RAISE EXCEPTION 'SCIM group already exists' USING ERRCODE = 'A0013';
            END IF;
        ELSE
            IF NOT FOUND OR current_group.organization_id IS DISTINCT FROM organization.id OR
               current_group.provider IS DISTINCT FROM payload->>'provider' THEN
                RAISE EXCEPTION 'SCIM group not found' USING ERRCODE = 'A0012';
            END IF;
            IF current_group.version IS DISTINCT FROM expected_group_version THEN
                RAISE EXCEPTION 'SCIM group version conflict' USING ERRCODE = 'A0002';
            END IF;
            IF current_group.document->>'createdAt' IS DISTINCT FROM group_document->>'createdAt' THEN
                RAISE EXCEPTION 'SCIM group creation timestamp changed' USING ERRCODE = 'A0003';
            END IF;
        END IF;
        IF EXISTS (
            SELECT 1
              FROM jsonb_array_elements_text(group_document->'memberUserIds') AS member(user_id)
             WHERE NOT EXISTS (
                 SELECT 1 FROM aether_identity.users WHERE id = member.user_id
             )
        ) THEN
            RAISE EXCEPTION 'SCIM group member not found' USING ERRCODE = 'A0012';
        END IF;
        IF expected_group_version = 0 THEN
            INSERT INTO aether_identity.scim_groups(
                id, organization_id, provider, external_id, state, version, document
            ) VALUES (
                group_document->>'id', organization.id, payload->>'provider',
                NULLIF(group_document->>'externalId', ''), group_document->>'state',
                (group_document->>'version')::BIGINT, group_document
            );
        ELSE
            UPDATE aether_identity.scim_groups
               SET external_id = NULLIF(group_document->>'externalId', ''),
                   state = group_document->>'state',
                   version = (group_document->>'version')::BIGINT,
                   document = group_document
             WHERE id = current_group.id;
        END IF;
    END IF;

    FOR revocation IN SELECT value FROM jsonb_array_elements(revocations) AS tenant_revocation(value)
    LOOP
        PERFORM 1 FROM aether_identity.users WHERE id = revocation->>'userId' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'SCIM revocation user not found' USING ERRCODE = 'A0012';
        END IF;

        IF COALESCE((revocation->>'revokeSessions')::BOOLEAN, FALSE) THEN
            IF EXISTS (
                SELECT 1 FROM aether_identity.sessions
                 WHERE user_id = revocation->>'userId' AND state = 'active' AND
                       federation_organization_id = organization.id AND
                       (document->>'createdAt')::TIMESTAMPTZ > (audit_event->>'occurredAt')::TIMESTAMPTZ
            ) THEN
                RAISE EXCEPTION 'SCIM revocation predates a session' USING ERRCODE = 'A0003';
            END IF;
            WITH revoked AS (
                UPDATE aether_identity.sessions
                   SET state = 'revoked',
                       version = version + 1,
                       document = document || jsonb_build_object(
                           'state', 'revoked',
                           'version', version + 1,
                           'revokedAt', audit_event->'occurredAt',
                           'revocationReasonCode', revocation->'reasonCode'
                       )
                 WHERE user_id = revocation->>'userId' AND state = 'active' AND
                       federation_organization_id = organization.id
                RETURNING id
            )
            SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB)
              INTO newly_revoked FROM revoked;
            revoked_session_ids := revoked_session_ids || newly_revoked;
        END IF;

        IF COALESCE((revocation->>'revokeDeviceTokenFamilies')::BOOLEAN, FALSE) THEN
            PERFORM 1
              FROM aether_identity.device_token_families
             WHERE user_id = revocation->>'userId' AND organization_id = organization.id AND
                   state = 'active'
             ORDER BY id
             FOR UPDATE;
            IF EXISTS (
                SELECT 1 FROM aether_identity.device_token_families
                 WHERE user_id = revocation->>'userId' AND organization_id = organization.id AND
                       state = 'active' AND
                       (document->>'createdAt')::TIMESTAMPTZ > (audit_event->>'occurredAt')::TIMESTAMPTZ
            ) OR EXISTS (
                SELECT 1
                  FROM aether_identity.device_access_tokens AS token
                  JOIN aether_identity.device_token_families AS family ON family.id = token.family_id
                 WHERE family.user_id = revocation->>'userId' AND
                       family.organization_id = organization.id AND family.state = 'active' AND
                       token.state = 'active' AND
                       (token.document->>'createdAt')::TIMESTAMPTZ > (audit_event->>'occurredAt')::TIMESTAMPTZ
            ) OR EXISTS (
                SELECT 1
                  FROM aether_identity.device_refresh_tokens AS token
                  JOIN aether_identity.device_token_families AS family ON family.id = token.family_id
                 WHERE family.user_id = revocation->>'userId' AND
                       family.organization_id = organization.id AND family.state = 'active' AND
                       token.state = 'active' AND
                       (token.document->>'createdAt')::TIMESTAMPTZ > (audit_event->>'occurredAt')::TIMESTAMPTZ
            ) THEN
                RAISE EXCEPTION 'SCIM revocation predates a device token' USING ERRCODE = 'A0003';
            END IF;
            WITH revoked AS (
                UPDATE aether_identity.device_access_tokens AS token
                   SET state = 'revoked',
                       version = token.version + 1,
                       document = token.document || jsonb_build_object(
                           'state', 'revoked',
                           'version', token.version + 1,
                           'revokedAt', audit_event->'occurredAt'
                       )
                  FROM aether_identity.device_token_families AS family
                 WHERE token.family_id = family.id AND family.user_id = revocation->>'userId' AND
                       family.organization_id = organization.id AND family.state = 'active' AND
                       token.state = 'active'
                RETURNING token.id
            )
            SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB)
              INTO newly_revoked FROM revoked;
            revoked_access_ids := revoked_access_ids || newly_revoked;

            WITH revoked AS (
                UPDATE aether_identity.device_refresh_tokens AS token
                   SET state = 'revoked',
                       version = token.version + 1,
                       document = token.document || jsonb_build_object(
                           'state', 'revoked',
                           'version', token.version + 1,
                           'revokedAt', audit_event->'occurredAt'
                       )
                  FROM aether_identity.device_token_families AS family
                 WHERE token.family_id = family.id AND family.user_id = revocation->>'userId' AND
                       family.organization_id = organization.id AND family.state = 'active' AND
                       token.state = 'active'
                RETURNING token.id
            )
            SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB)
              INTO newly_revoked FROM revoked;
            revoked_refresh_ids := revoked_refresh_ids || newly_revoked;

            WITH revoked AS (
                UPDATE aether_identity.device_token_families
                   SET state = 'revoked',
                       version = version + 1,
                       document = document || jsonb_build_object(
                           'state', 'revoked',
                           'version', version + 1,
                           'revokedAt', audit_event->'occurredAt',
                           'revocationReasonCode', revocation->'reasonCode'
                       )
                 WHERE user_id = revocation->>'userId' AND organization_id = organization.id AND
                       state = 'active'
                RETURNING id
            )
            SELECT COALESCE(jsonb_agg(to_jsonb(id) ORDER BY id), '[]'::JSONB)
              INTO newly_revoked FROM revoked;
            revoked_family_ids := revoked_family_ids || newly_revoked;
        END IF;
    END LOOP;

    revoked_session_ids := aether_identity.sorted_distinct_text_jsonb(revoked_session_ids);
    revoked_family_ids := aether_identity.sorted_distinct_text_jsonb(revoked_family_ids);
    revoked_access_ids := aether_identity.sorted_distinct_text_jsonb(revoked_access_ids);
    revoked_refresh_ids := aether_identity.sorted_distinct_text_jsonb(revoked_refresh_ids);

    PERFORM aether_identity.record_audit(audit_event);
    commit_result := jsonb_build_object(
        'mutationCommits', mutation_commits,
        'group', group_document,
        'revokedSessionIds', revoked_session_ids,
        'revokedDeviceTokenFamilyIds', revoked_family_ids,
        'revokedDeviceAccessTokenIds', revoked_access_ids,
        'revokedDeviceRefreshTokenIds', revoked_refresh_ids,
        'alreadyApplied', FALSE,
        'auditEvent', audit_event
    );
    INSERT INTO aether_identity.scim_batch_operations(
        operation_id, organization_id, provider, command_document, commit_result, occurred_at
    ) VALUES (
        payload->>'operationId', organization.id, payload->>'provider', payload, commit_result,
        (audit_event->>'occurredAt')::TIMESTAMPTZ
    );
    RETURN aether_identity.rpc_success('apply_scim_batch', commit_result);
END;
$$;
