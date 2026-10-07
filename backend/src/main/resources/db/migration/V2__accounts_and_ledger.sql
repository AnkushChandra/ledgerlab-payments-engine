-- Financial accounts (product level) and the double-entry ledger (accounting level).
-- Sign convention: ledger_entry.amount_minor > 0 is a debit, < 0 is a credit.

CREATE TABLE financial_account (
    id              uuid PRIMARY KEY,
    organization_id uuid         NOT NULL REFERENCES organization (id),
    account_type    varchar(16)  NOT NULL CHECK (account_type IN ('CUSTOMER', 'MERCHANT')),
    name            varchar(120) NOT NULL,
    reference       varchar(64)  NOT NULL,
    status          varchar(16)  NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    created_at      timestamptz  NOT NULL,
    created_by      uuid REFERENCES app_user (id),
    version         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT financial_account_reference_uq UNIQUE (organization_id, reference),
    CONSTRAINT financial_account_org_uq UNIQUE (id, organization_id)
);
CREATE INDEX financial_account_org_created_idx ON financial_account (organization_id, created_at DESC, id);

CREATE TABLE ledger_account (
    id                   uuid PRIMARY KEY,
    organization_id      uuid        NOT NULL REFERENCES organization (id),
    financial_account_id uuid,
    purpose              varchar(32) NOT NULL CHECK (purpose IN (
                             'EXTERNAL_CLEARING', 'CUSTOMER_AVAILABLE', 'CUSTOMER_RESERVED',
                             'MERCHANT_AVAILABLE', 'MERCHANT_DISPUTE_HOLD')),
    account_type         varchar(16) NOT NULL CHECK (account_type IN ('ASSET', 'LIABILITY')),
    normal_side          varchar(6)  NOT NULL CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    currency             varchar(3)  NOT NULL CHECK (currency = 'USD'),
    allow_negative       boolean     NOT NULL,
    balance_minor        bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    CONSTRAINT ledger_account_org_uq UNIQUE (id, organization_id),
    CONSTRAINT ledger_account_financial_account_fk
        FOREIGN KEY (financial_account_id, organization_id) REFERENCES financial_account (id, organization_id),
    CONSTRAINT ledger_account_type_matches_side CHECK (
        (account_type = 'ASSET' AND normal_side = 'DEBIT') OR
        (account_type = 'LIABILITY' AND normal_side = 'CREDIT')),
    CONSTRAINT ledger_account_owner CHECK ((purpose = 'EXTERNAL_CLEARING') = (financial_account_id IS NULL)),
    -- Backstop against overdrafts: a non-negative account may never move past zero on its normal side.
    CONSTRAINT ledger_account_no_overdraft CHECK (
        allow_negative OR
        (normal_side = 'CREDIT' AND balance_minor <= 0) OR
        (normal_side = 'DEBIT' AND balance_minor >= 0))
);
CREATE UNIQUE INDEX ledger_account_clearing_uq ON ledger_account (organization_id) WHERE purpose = 'EXTERNAL_CLEARING';
CREATE UNIQUE INDEX ledger_account_purpose_uq ON ledger_account (financial_account_id, purpose)
    WHERE financial_account_id IS NOT NULL;

CREATE TABLE ledger_transaction (
    id               uuid PRIMARY KEY,
    organization_id  uuid         NOT NULL REFERENCES organization (id),
    transaction_type varchar(40)  NOT NULL CHECK (transaction_type IN (
                         'SIMULATED_DEPOSIT', 'TRANSFER', 'PAYMENT_AUTHORIZATION', 'PAYMENT_CAPTURE',
                         'PAYMENT_VOID', 'PAYMENT_REFUND', 'DISPUTE_OPENED', 'DISPUTE_WON', 'DISPUTE_LOST')),
    currency         varchar(3)   NOT NULL CHECK (currency = 'USD'),
    description      varchar(255) NOT NULL,
    source_type      varchar(40)  NOT NULL,
    source_id        uuid         NOT NULL,
    posted_at        timestamptz  NOT NULL,
    created_by       uuid REFERENCES app_user (id),
    CONSTRAINT ledger_transaction_org_uq UNIQUE (id, organization_id)
);
CREATE INDEX ledger_transaction_org_posted_idx ON ledger_transaction (organization_id, posted_at DESC, id);
CREATE INDEX ledger_transaction_source_idx ON ledger_transaction (source_type, source_id);

CREATE TABLE ledger_entry (
    id                    uuid PRIMARY KEY,
    ledger_transaction_id uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    ledger_account_id     uuid        NOT NULL,
    amount_minor          bigint      NOT NULL CHECK (amount_minor <> 0),
    currency              varchar(3)  NOT NULL CHECK (currency = 'USD'),
    created_at            timestamptz NOT NULL,
    -- Composite keys guarantee an entry, its journal and its account share one organization.
    CONSTRAINT ledger_entry_transaction_fk
        FOREIGN KEY (ledger_transaction_id, organization_id) REFERENCES ledger_transaction (id, organization_id),
    CONSTRAINT ledger_entry_account_fk
        FOREIGN KEY (ledger_account_id, organization_id) REFERENCES ledger_account (id, organization_id)
);
CREATE INDEX ledger_entry_transaction_idx ON ledger_entry (ledger_transaction_id);
CREATE INDEX ledger_entry_account_idx ON ledger_entry (ledger_account_id, created_at DESC, id);

-- Immutability -------------------------------------------------------------------------------

CREATE TRIGGER ledger_transaction_immutable
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER ledger_transaction_no_truncate
    BEFORE TRUNCATE ON ledger_transaction
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE TRIGGER ledger_entry_no_truncate
    BEFORE TRUNCATE ON ledger_entry
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

-- Balance maintenance ------------------------------------------------------------------------

CREATE FUNCTION ledger_apply_entry() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    UPDATE ledger_account
       SET balance_minor = balance_minor + NEW.amount_minor
     WHERE id = NEW.ledger_account_id;
    RETURN NULL;
END;
$$;

CREATE TRIGGER ledger_entry_apply_balance
    AFTER INSERT ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_apply_entry();

-- balance_minor may only change through ledger_apply_entry (trigger depth > 1), and account
-- identity columns never change.
CREATE FUNCTION ledger_account_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger_account rows cannot be deleted' USING ERRCODE = 'LL001';
    END IF;
    IF NEW.balance_minor IS DISTINCT FROM OLD.balance_minor AND pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'ledger_account.balance_minor can only change by posting ledger entries'
            USING ERRCODE = 'LL001';
    END IF;
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.financial_account_id IS DISTINCT FROM OLD.financial_account_id
        OR NEW.purpose IS DISTINCT FROM OLD.purpose
        OR NEW.account_type IS DISTINCT FROM OLD.account_type
        OR NEW.normal_side IS DISTINCT FROM OLD.normal_side
        OR NEW.currency IS DISTINCT FROM OLD.currency THEN
        RAISE EXCEPTION 'ledger_account identity columns are immutable' USING ERRCODE = 'LL001';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_account_guard
    BEFORE UPDATE OR DELETE ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION ledger_account_guard();

-- Journal validation at commit -----------------------------------------------------------------

CREATE FUNCTION ledger_check_transaction(p_transaction_id uuid) RETURNS void
    LANGUAGE plpgsql AS
$$
DECLARE
    v_type         varchar(40);
    v_currency     varchar(3);
    v_count        integer;
    v_sum          numeric;
    v_bad_currency integer;
    v_clearing     integer;
BEGIN
    SELECT transaction_type, currency INTO v_type, v_currency
      FROM ledger_transaction WHERE id = p_transaction_id;

    SELECT count(*),
           coalesce(sum(e.amount_minor), 0),
           count(*) FILTER (WHERE e.currency <> v_currency),
           count(*) FILTER (WHERE a.purpose = 'EXTERNAL_CLEARING')
      INTO v_count, v_sum, v_bad_currency, v_clearing
      FROM ledger_entry e
      JOIN ledger_account a ON a.id = e.ledger_account_id
     WHERE e.ledger_transaction_id = p_transaction_id;

    IF v_count < 2 THEN
        RAISE EXCEPTION 'ledger transaction % has % entries; at least 2 are required', p_transaction_id, v_count
            USING ERRCODE = 'LL002';
    END IF;
    IF v_sum <> 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced by %', p_transaction_id, v_sum
            USING ERRCODE = 'LL002';
    END IF;
    IF v_bad_currency > 0 THEN
        RAISE EXCEPTION 'ledger transaction % mixes currencies', p_transaction_id
            USING ERRCODE = 'LL002';
    END IF;
    IF v_clearing > 0 AND v_type <> 'SIMULATED_DEPOSIT' THEN
        RAISE EXCEPTION 'only SIMULATED_DEPOSIT may post to EXTERNAL_CLEARING (transaction %)', p_transaction_id
            USING ERRCODE = 'LL002';
    END IF;
END;
$$;

CREATE FUNCTION ledger_validate_transaction_row() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM ledger_check_transaction(NEW.id);
    RETURN NULL;
END;
$$;

CREATE FUNCTION ledger_validate_entry_row() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM ledger_check_transaction(NEW.ledger_transaction_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transaction_balanced
    AFTER INSERT ON ledger_transaction
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_validate_transaction_row();

CREATE CONSTRAINT TRIGGER ledger_entry_balanced
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_validate_entry_row();
