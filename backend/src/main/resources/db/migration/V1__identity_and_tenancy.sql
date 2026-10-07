-- Identity, organizations and memberships.

CREATE TABLE organization (
    id          uuid PRIMARY KEY,
    name        varchar(120) NOT NULL,
    slug        varchar(60)  NOT NULL UNIQUE,
    created_at  timestamptz  NOT NULL
);

CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    email         varchar(254) NOT NULL,
    password_hash varchar(100) NOT NULL,
    display_name  varchar(120) NOT NULL,
    status        varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at    timestamptz  NOT NULL,
    CONSTRAINT app_user_email_lowercase CHECK (email = lower(email))
);
CREATE UNIQUE INDEX app_user_email_uq ON app_user (email);

CREATE TABLE organization_membership (
    id              uuid PRIMARY KEY,
    organization_id uuid        NOT NULL REFERENCES organization (id),
    user_id         uuid        NOT NULL REFERENCES app_user (id),
    role            varchar(16) NOT NULL CHECK (role IN ('ADMIN', 'OPERATIONS', 'VIEWER')),
    created_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT organization_membership_uq UNIQUE (organization_id, user_id)
);
CREATE INDEX organization_membership_user_idx ON organization_membership (user_id);

-- Shared trigger function used by every append-only table.
CREATE FUNCTION reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'LL001';
END;
$$;
