-- Idempotency records and the append-only audit trail.

CREATE TABLE idempotency_record (
    id              uuid PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organization (id),
    operation       varchar(40)  NOT NULL,
    idempotency_key varchar(100) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    response_status integer,
    response_body   jsonb,
    resource_id     uuid,
    created_at      timestamptz  NOT NULL,
    completed_at    timestamptz,
    CONSTRAINT idempotency_record_scope_uq UNIQUE (organization_id, operation, idempotency_key)
);

CREATE TABLE audit_event (
    id              uuid PRIMARY KEY,
    organization_id uuid REFERENCES organization (id),
    actor_user_id   uuid REFERENCES app_user (id),
    actor_email     varchar(254),
    action          varchar(48)  NOT NULL,
    target_type     varchar(40)  NOT NULL,
    target_id       varchar(64),
    correlation_id  varchar(64),
    details         jsonb        NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     timestamptz  NOT NULL
);
CREATE INDEX audit_event_org_occurred_idx ON audit_event (organization_id, occurred_at DESC, id);
CREATE INDEX audit_event_target_idx ON audit_event (organization_id, target_type, target_id);
CREATE INDEX audit_event_action_idx ON audit_event (organization_id, action);

CREATE TRIGGER audit_event_immutable
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
