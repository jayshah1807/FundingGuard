CREATE TABLE fg_tenants (id text PRIMARY KEY, name text NOT NULL);
CREATE TABLE fg_users (id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), name text NOT NULL, email text NOT NULL UNIQUE, role text NOT NULL, password_hash text NOT NULL, active boolean NOT NULL DEFAULT true);
CREATE TABLE fg_contacts (id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), institution text NOT NULL, name text NOT NULL, channel text NOT NULL, version integer NOT NULL DEFAULT 1, active boolean NOT NULL DEFAULT true, provenance text NOT NULL);
CREATE TABLE fg_payouts (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), borrower text NOT NULL, property text NOT NULL, lender text NOT NULL,
 loan_reference text NOT NULL, contact_id text NOT NULL REFERENCES fg_contacts(id), amount_minor bigint NOT NULL CHECK(amount_minor BETWEEN 1 AND 100000000),
 account text NOT NULL, state text NOT NULL DEFAULT 'REVIEW_REQUIRED', version integer NOT NULL DEFAULT 1, maker_id text NOT NULL REFERENCES fg_users(id),
 verified_by text REFERENCES fg_users(id), verified_at timestamptz, verified_version integer, verified_contact_version integer,
 approved_by text REFERENCES fg_users(id), approved_at timestamptz, approved_version integer, approved_contact_version integer,
 hold_active boolean NOT NULL DEFAULT false, hold_reason text, clearance_by text REFERENCES fg_users(id), clearance_reason text,
 receipt boolean NOT NULL DEFAULT false, discharge boolean NOT NULL DEFAULT false,
 deadline date NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,loan_reference)
);
CREATE TABLE fg_versions (id bigserial PRIMARY KEY, payout_id text NOT NULL REFERENCES fg_payouts(id), version integer NOT NULL, amount_minor bigint NOT NULL, account text NOT NULL, maker_id text NOT NULL, reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(payout_id,version));
CREATE TABLE fg_verifications (id bigserial PRIMARY KEY, payout_id text NOT NULL REFERENCES fg_payouts(id), instruction_version integer NOT NULL, contact_version integer NOT NULL, actor_id text NOT NULL REFERENCES fg_users(id), evidence text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_approvals (id bigserial PRIMARY KEY, payout_id text NOT NULL REFERENCES fg_payouts(id), instruction_version integer NOT NULL, contact_version integer NOT NULL, actor_id text NOT NULL REFERENCES fg_users(id), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_ledger (id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), payout_id text NOT NULL UNIQUE REFERENCES fg_payouts(id), amount_minor bigint NOT NULL, account text NOT NULL, instruction_version integer NOT NULL, actor_id text NOT NULL REFERENCES fg_users(id), created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_idempotency (tenant_id text NOT NULL, request_key text NOT NULL, actor_id text NOT NULL, payout_id text NOT NULL, request_hash text NOT NULL, ledger_id text NOT NULL REFERENCES fg_ledger(id), PRIMARY KEY(tenant_id,request_key));
CREATE TABLE fg_audit (id bigserial PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), payout_id text, actor_id text, actor_name text NOT NULL, action text NOT NULL, detail text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_cases (id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id), payout_id text NOT NULL REFERENCES fg_payouts(id), rule_code text NOT NULL, severity text NOT NULL, title text NOT NULL, detail text NOT NULL, state text NOT NULL DEFAULT 'OPEN', resolution text, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_outbox (id bigserial PRIMARY KEY, tenant_id text NOT NULL, payout_id text NOT NULL, kind text NOT NULL, processed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fg_notifications (id bigserial PRIMARY KEY, tenant_id text NOT NULL, title text NOT NULL, detail text NOT NULL, event_id bigint UNIQUE REFERENCES fg_outbox(id), created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX audit_tenant_time ON fg_audit(tenant_id,created_at DESC);
CREATE INDEX payouts_tenant ON fg_payouts(tenant_id,deadline);
CREATE INDEX cases_tenant ON fg_cases(tenant_id,state);
CREATE INDEX outbox_pending ON fg_outbox(id) WHERE processed_at IS NULL;
CREATE FUNCTION fg_immutable() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Immutable evidence cannot be changed'; END; $$;
CREATE TRIGGER immutable_versions BEFORE UPDATE OR DELETE ON fg_versions FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_ledger BEFORE UPDATE OR DELETE ON fg_ledger FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON fg_audit FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_verification BEFORE UPDATE OR DELETE ON fg_verifications FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_approval BEFORE UPDATE OR DELETE ON fg_approvals FOR EACH ROW EXECUTE FUNCTION fg_immutable();
