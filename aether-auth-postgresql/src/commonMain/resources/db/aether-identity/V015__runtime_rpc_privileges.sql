-- Only supported, envelope-validated RPCs may cross the private helper boundary.
-- Existing explicit application-role grants survive ALTER FUNCTION; PUBLIC execution does not.
-- The deployment/schema owner must be a dedicated non-superuser authority role (NOLOGIN is recommended).
-- Every operation retains its existing environment, CAS and provenance validation. Helpers,
-- deployment provisioning and obsolete federation RPCs receive no elevated runtime access.
ALTER FUNCTION aether_identity.v1_acquire_federation_provider_lease(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_acquire_federation_provider_lease(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_bootstrap_identity(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_bootstrap_identity(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_complete_credential_authentication(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_complete_credential_authentication(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_complete_credential_registration(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_complete_credential_registration(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_complete_recovery_enrollment(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_complete_recovery_enrollment(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_consume_challenge(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_consume_challenge(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_consume_recovery_code(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_consume_recovery_code(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_create_challenge(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_create_challenge(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_create_session(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_create_session(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_enroll_invitation(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_enroll_invitation(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_link_external_identity(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_link_external_identity(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_mutate_account_security(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_mutate_account_security(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_quarantine_credential_authentication(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_quarantine_credential_authentication(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_record_external_identity_replay(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_record_external_identity_replay(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_redeem_administrative_recovery_ticket(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_redeem_administrative_recovery_ticket(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_rotate_session(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_rotate_session(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_touch_identity_session(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_touch_identity_session(JSONB) FROM PUBLIC;

ALTER FUNCTION aether_identity.v1_validate_federation_provider_lease(JSONB) SECURITY DEFINER
    SET search_path = pg_catalog;
REVOKE ALL ON FUNCTION aether_identity.v1_validate_federation_provider_lease(JSONB) FROM PUBLIC;
