-- Settlement batches and reconciliation results.

CREATE TABLE settlement_batch (
    id                  uuid PRIMARY KEY,
    organization_id     uuid         NOT NULL REFERENCES organization (id),
    file_name           varchar(255) NOT NULL,
    content_sha256      varchar(64)  NOT NULL,
    period_start        date         NOT NULL,
    period_end          date         NOT NULL,
    record_count        integer      NOT NULL CHECK (record_count >= 0),
    matched_count            integer NOT NULL DEFAULT 0,
    missing_internal_count   integer NOT NULL DEFAULT 0,
    missing_external_count   integer NOT NULL DEFAULT 0,
    amount_mismatch_count    integer NOT NULL DEFAULT 0,
    status_mismatch_count    integer NOT NULL DEFAULT 0,
    duplicate_external_count integer NOT NULL DEFAULT 0,
    uploaded_by         uuid REFERENCES app_user (id),
    uploaded_at         timestamptz  NOT NULL,
    CONSTRAINT settlement_batch_period CHECK (period_end >= period_start),
    CONSTRAINT settlement_batch_content_uq UNIQUE (organization_id, content_sha256),
    CONSTRAINT settlement_batch_org_uq UNIQUE (id, organization_id)
);
CREATE INDEX settlement_batch_org_uploaded_idx ON settlement_batch (organization_id, uploaded_at DESC, id);

CREATE TABLE settlement_record (
    id                  uuid PRIMARY KEY,
    batch_id            uuid         NOT NULL,
    organization_id     uuid         NOT NULL,
    row_number          integer      NOT NULL,
    processor_record_id varchar(64)  NOT NULL,
    payment_id          uuid         NOT NULL,
    status              varchar(24)  NOT NULL,
    amount_minor        bigint       NOT NULL CHECK (amount_minor >= 0),
    currency            varchar(3)   NOT NULL CHECK (currency = 'USD'),
    settled_at          timestamptz  NOT NULL,
    CONSTRAINT settlement_record_batch_fk
        FOREIGN KEY (batch_id, organization_id) REFERENCES settlement_batch (id, organization_id),
    CONSTRAINT settlement_record_processor_uq UNIQUE (batch_id, processor_record_id)
);
CREATE INDEX settlement_record_batch_idx ON settlement_record (batch_id, row_number);

CREATE TABLE reconciliation_result (
    id                    uuid PRIMARY KEY,
    batch_id              uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    settlement_record_id  uuid REFERENCES settlement_record (id),
    payment_id            uuid,
    classification        varchar(24) NOT NULL CHECK (classification IN (
                              'MATCHED', 'MISSING_INTERNAL', 'MISSING_EXTERNAL', 'AMOUNT_MISMATCH',
                              'STATUS_MISMATCH', 'DUPLICATE_EXTERNAL')),
    expected_amount_minor bigint,
    actual_amount_minor   bigint,
    expected_status       varchar(24),
    actual_status         varchar(24),
    message               varchar(255) NOT NULL,
    sequence_number       integer     NOT NULL,
    CONSTRAINT reconciliation_result_batch_fk
        FOREIGN KEY (batch_id, organization_id) REFERENCES settlement_batch (id, organization_id),
    CONSTRAINT reconciliation_result_record_required CHECK (
        (classification = 'MISSING_EXTERNAL') = (settlement_record_id IS NULL))
);
CREATE INDEX reconciliation_result_batch_idx ON reconciliation_result (batch_id, sequence_number);
CREATE INDEX reconciliation_result_batch_class_idx ON reconciliation_result (batch_id, classification, sequence_number);
