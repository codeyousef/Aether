-- Aether identity migration V009
-- Runtime environment checks are read-only. Provisioning is an explicit deployment action.

CREATE OR REPLACE FUNCTION aether_identity.assert_environment(
    p_environment TEXT,
    p_namespace TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored aether_identity.environment%ROWTYPE;
BEGIN
    SELECT * INTO stored
      FROM aether_identity.environment
     WHERE singleton = TRUE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'identity environment marker is not provisioned' USING ERRCODE = 'A0001';
    END IF;
    IF stored.environment <> p_environment OR stored.namespace <> p_namespace THEN
        RAISE EXCEPTION 'identity environment mismatch' USING ERRCODE = 'A0001';
    END IF;
END;
$$;

-- This invoker-rights function is for a short-lived deployment role only. It creates the singleton
-- once and treats an exact existing marker as success; it can never replace another environment.
CREATE OR REPLACE FUNCTION aether_identity.provision_environment(
    p_environment TEXT,
    p_namespace TEXT
) RETURNS VOID
LANGUAGE plpgsql
AS $$
DECLARE
    stored aether_identity.environment%ROWTYPE;
BEGIN
    IF p_environment IS NULL OR
       p_environment NOT IN ('development', 'test', 'staging', 'production') OR
       p_namespace IS NULL OR
       p_namespace !~ '^[a-z][a-z0-9_-]{2,63}$' OR
       position(p_environment IN p_namespace) = 0 THEN
        RAISE EXCEPTION 'invalid identity environment marker' USING ERRCODE = 'A0014';
    END IF;

    INSERT INTO aether_identity.environment(singleton, environment, namespace)
    VALUES (TRUE, p_environment, p_namespace)
    ON CONFLICT (singleton) DO NOTHING;

    SELECT * INTO stored
      FROM aether_identity.environment
     WHERE singleton = TRUE;
    IF NOT FOUND OR stored.environment <> p_environment OR stored.namespace <> p_namespace THEN
        RAISE EXCEPTION 'identity environment mismatch' USING ERRCODE = 'A0001';
    END IF;
END;
$$;

-- PostgreSQL grants function execution to PUBLIC by default. Never expose this operation through
-- the normal application or PostgREST role; deployment automation grants it only while provisioning.
REVOKE ALL ON FUNCTION aether_identity.provision_environment(TEXT, TEXT) FROM PUBLIC;
