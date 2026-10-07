-- Deposits, transfers, payments, refunds and disputes.

CREATE TABLE deposit (
    id                    uuid PRIMARY KEY,
    organization_id       uuid         NOT NULL REFERENCES organization (id),
    financial_account_id  uuid         NOT NULL,
    amount_minor          bigint       NOT NULL CHECK (amount_minor > 0),
    currency              varchar(3)   NOT NULL CHECK (currency = 'USD'),
    memo                  varchar(255),
    ledger_transaction_id uuid         NOT NULL UNIQUE REFERENCES ledger_transaction (id),
    created_by            uuid REFERENCES app_user (id),
    created_at            timestamptz  NOT NULL,
    CONSTRAINT deposit_account_fk
        FOREIGN KEY (financial_account_id, organization_id) REFERENCES financial_account (id, organization_id)
);
CREATE INDEX deposit_org_created_idx ON deposit (organization_id, created_at DESC, id);
CREATE INDEX deposit_account_idx ON deposit (financial_account_id);

CREATE TABLE transfer (
    id                     uuid PRIMARY KEY,
    organization_id        uuid        NOT NULL REFERENCES organization (id),
    source_account_id      uuid        NOT NULL,
    destination_account_id uuid        NOT NULL,
    amount_minor           bigint      NOT NULL CHECK (amount_minor > 0),
    currency               varchar(3)  NOT NULL CHECK (currency = 'USD'),
    memo                   varchar(255),
    ledger_transaction_id  uuid        NOT NULL UNIQUE REFERENCES ledger_transaction (id),
    created_by             uuid REFERENCES app_user (id),
    created_at             timestamptz NOT NULL,
    CONSTRAINT transfer_distinct_accounts CHECK (source_account_id <> destination_account_id),
    CONSTRAINT transfer_source_fk
        FOREIGN KEY (source_account_id, organization_id) REFERENCES financial_account (id, organization_id),
    CONSTRAINT transfer_destination_fk
        FOREIGN KEY (destination_account_id, organization_id) REFERENCES financial_account (id, organization_id)
);
CREATE INDEX transfer_org_created_idx ON transfer (organization_id, created_at DESC, id);
CREATE INDEX transfer_source_idx ON transfer (source_account_id);
CREATE INDEX transfer_destination_idx ON transfer (destination_account_id);

CREATE TABLE payment (
    id                    uuid PRIMARY KEY,
    organization_id       uuid         NOT NULL REFERENCES organization (id),
    customer_account_id   uuid         NOT NULL,
    merchant_account_id   uuid         NOT NULL,
    reference             varchar(64),
    description           varchar(255),
    currency              varchar(3)   NOT NULL CHECK (currency = 'USD'),
    authorized_amount_minor   bigint   NOT NULL CHECK (authorized_amount_minor > 0),
    captured_amount_minor     bigint   NOT NULL DEFAULT 0,
    released_amount_minor     bigint   NOT NULL DEFAULT 0,
    refunded_amount_minor     bigint   NOT NULL DEFAULT 0,
    disputed_amount_minor     bigint   NOT NULL DEFAULT 0,
    dispute_lost_amount_minor bigint   NOT NULL DEFAULT 0,
    status                varchar(24)  NOT NULL CHECK (status IN (
                              'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED', 'VOIDED', 'PARTIALLY_REFUNDED',
                              'REFUNDED', 'DISPUTED', 'RESOLVED', 'FAILED')),
    failure_code          varchar(40),
    created_at            timestamptz  NOT NULL,
    first_captured_at     timestamptz,
    updated_at            timestamptz  NOT NULL,
    created_by            uuid REFERENCES app_user (id),
    version               bigint       NOT NULL DEFAULT 0,
    CONSTRAINT payment_org_uq UNIQUE (id, organization_id),
    CONSTRAINT payment_customer_fk
        FOREIGN KEY (customer_account_id, organization_id) REFERENCES financial_account (id, organization_id),
    CONSTRAINT payment_merchant_fk
        FOREIGN KEY (merchant_account_id, organization_id) REFERENCES financial_account (id, organization_id),
    CONSTRAINT payment_distinct_parties CHECK (customer_account_id <> merchant_account_id),
    CONSTRAINT payment_amounts_non_negative CHECK (
        captured_amount_minor >= 0 AND released_amount_minor >= 0 AND refunded_amount_minor >= 0
        AND disputed_amount_minor >= 0 AND dispute_lost_amount_minor >= 0),
    -- Invariant: capture never exceeds authorization (including any released remainder).
    CONSTRAINT payment_capture_within_authorization CHECK (
        captured_amount_minor + released_amount_minor <= authorized_amount_minor),
    -- Invariant: refunds and chargebacks never exceed the captured amount.
    CONSTRAINT payment_refund_within_capture CHECK (
        refunded_amount_minor + dispute_lost_amount_minor <= captured_amount_minor),
    CONSTRAINT payment_dispute_within_capture CHECK (
        disputed_amount_minor <= captured_amount_minor - refunded_amount_minor),
    CONSTRAINT payment_failure_code CHECK ((status = 'FAILED') = (failure_code IS NOT NULL))
);
CREATE INDEX payment_org_created_idx ON payment (organization_id, created_at DESC, id);
CREATE INDEX payment_org_status_idx ON payment (organization_id, status);
CREATE INDEX payment_customer_idx ON payment (customer_account_id);
CREATE INDEX payment_merchant_idx ON payment (merchant_account_id);
CREATE INDEX payment_org_first_captured_idx ON payment (organization_id, first_captured_at)
    WHERE first_captured_at IS NOT NULL;

CREATE TABLE payment_event (
    id                    uuid PRIMARY KEY,
    payment_id            uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    event_type            varchar(32) NOT NULL CHECK (event_type IN (
                              'AUTHORIZED', 'AUTHORIZATION_FAILED', 'CAPTURED', 'VOIDED', 'REFUNDED',
                              'DISPUTE_OPENED', 'DISPUTE_WON', 'DISPUTE_LOST')),
    amount_minor          bigint      NOT NULL CHECK (amount_minor >= 0),
    from_status           varchar(24),
    to_status             varchar(24) NOT NULL,
    ledger_transaction_id uuid REFERENCES ledger_transaction (id),
    actor_user_id         uuid REFERENCES app_user (id),
    note                  varchar(255),
    created_at            timestamptz NOT NULL,
    CONSTRAINT payment_event_payment_fk
        FOREIGN KEY (payment_id, organization_id) REFERENCES payment (id, organization_id)
);
CREATE INDEX payment_event_payment_idx ON payment_event (payment_id, created_at, id);

CREATE TRIGGER payment_event_immutable
    BEFORE UPDATE OR DELETE ON payment_event
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER payment_event_no_truncate
    BEFORE TRUNCATE ON payment_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

CREATE TABLE refund (
    id                    uuid PRIMARY KEY,
    payment_id            uuid         NOT NULL,
    organization_id       uuid         NOT NULL,
    amount_minor          bigint       NOT NULL CHECK (amount_minor > 0),
    currency              varchar(3)   NOT NULL CHECK (currency = 'USD'),
    reason                varchar(255),
    ledger_transaction_id uuid         NOT NULL UNIQUE REFERENCES ledger_transaction (id),
    created_by            uuid REFERENCES app_user (id),
    created_at            timestamptz  NOT NULL,
    CONSTRAINT refund_payment_fk
        FOREIGN KEY (payment_id, organization_id) REFERENCES payment (id, organization_id)
);
CREATE INDEX refund_payment_idx ON refund (payment_id, created_at, id);

CREATE TABLE dispute (
    id              uuid PRIMARY KEY,
    payment_id      uuid         NOT NULL,
    organization_id uuid         NOT NULL,
    amount_minor    bigint       NOT NULL CHECK (amount_minor > 0),
    currency        varchar(3)   NOT NULL CHECK (currency = 'USD'),
    reason          varchar(255) NOT NULL,
    status          varchar(8)   NOT NULL CHECK (status IN ('OPEN', 'WON', 'LOST')),
    opened_by       uuid REFERENCES app_user (id),
    opened_at       timestamptz  NOT NULL,
    resolved_by     uuid REFERENCES app_user (id),
    resolved_at     timestamptz,
    resolution_note varchar(500),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT dispute_payment_fk
        FOREIGN KEY (payment_id, organization_id) REFERENCES payment (id, organization_id),
    CONSTRAINT dispute_resolution_consistent CHECK ((status = 'OPEN') = (resolved_at IS NULL))
);
CREATE UNIQUE INDEX dispute_one_open_per_payment ON dispute (payment_id) WHERE status = 'OPEN';
CREATE INDEX dispute_org_opened_idx ON dispute (organization_id, opened_at DESC, id);
CREATE INDEX dispute_org_status_idx ON dispute (organization_id, status);
