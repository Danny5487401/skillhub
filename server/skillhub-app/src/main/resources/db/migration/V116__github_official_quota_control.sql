-- Observations are not a reservation ledger. Unknown account identities share conservative gates.
CREATE TABLE github_official_quota_observation (
    observation_key VARCHAR(64) NOT NULL,
    resource VARCHAR(16) NOT NULL CHECK (resource IN ('CORE','SEARCH','GRAPHQL','OTHER')),
    credential_id UUID REFERENCES github_system_credential(id) ON DELETE CASCADE,
    github_subject_id BIGINT,
    state VARCHAR(32) NOT NULL CHECK (state IN ('OBSERVED','UNKNOWN','PRIMARY_EXHAUSTED','SECONDARY_COOLDOWN','AUTH_INVALID')),
    remaining INTEGER CHECK (remaining >= 0),
    reset_at TIMESTAMPTZ,
    observed_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    valid_until TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (observation_key, resource)
);
CREATE TABLE github_official_resource_cooldown (
    resource VARCHAR(16) PRIMARY KEY CHECK (resource IN ('CORE','SEARCH','GRAPHQL','OTHER')),
    primary_until TIMESTAMPTZ NOT NULL
);
CREATE TABLE github_official_secondary_cooldown (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    cooldown_until TIMESTAMPTZ
);
INSERT INTO github_official_secondary_cooldown (id) VALUES (TRUE);
