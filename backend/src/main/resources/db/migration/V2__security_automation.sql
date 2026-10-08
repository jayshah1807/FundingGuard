ALTER TABLE fg_payouts ADD COLUMN scenario_run boolean NOT NULL DEFAULT false;
CREATE TABLE fg_security_events (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id),
 payout_id text NOT NULL REFERENCES fg_payouts(id), instruction_version integer NOT NULL,
 source text NOT NULL, source_key text NOT NULL, kind text NOT NULL,
 occurred_at timestamptz NOT NULL, received_at timestamptz NOT NULL DEFAULT now(),
 submitted_by text NOT NULL REFERENCES fg_users(id), detail text NOT NULL,
 UNIQUE(tenant_id,source,source_key)
);
CREATE INDEX security_event_correlation ON fg_security_events(tenant_id,payout_id,occurred_at DESC);
CREATE TABLE fg_playbook_runs (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id),
 payout_id text NOT NULL REFERENCES fg_payouts(id), instruction_version integer NOT NULL,
 rule_code text NOT NULL, rule_version integer NOT NULL, status text NOT NULL,
 case_id text NOT NULL REFERENCES fg_cases(id), summary text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,payout_id,instruction_version,rule_code)
);
CREATE TABLE fg_playbook_evidence (
 run_id text NOT NULL REFERENCES fg_playbook_runs(id), event_id text NOT NULL REFERENCES fg_security_events(id),
 PRIMARY KEY(run_id,event_id)
);
CREATE TABLE fg_playbook_steps (
 run_id text NOT NULL REFERENCES fg_playbook_runs(id), sequence integer NOT NULL,
 action text NOT NULL, outcome text NOT NULL, detail text NOT NULL,
 PRIMARY KEY(run_id,sequence)
);
CREATE TABLE fg_scenario_runs (
 id text PRIMARY KEY, tenant_id text NOT NULL REFERENCES fg_tenants(id),
 payout_id text NOT NULL REFERENCES fg_payouts(id), scenario text NOT NULL,
 expected_hold boolean NOT NULL, observed_hold boolean NOT NULL,
 duration_ms bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER immutable_security_events BEFORE UPDATE OR DELETE ON fg_security_events FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_playbooks BEFORE UPDATE OR DELETE ON fg_playbook_runs FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_playbook_evidence BEFORE UPDATE OR DELETE ON fg_playbook_evidence FOR EACH ROW EXECUTE FUNCTION fg_immutable();
CREATE TRIGGER immutable_playbook_steps BEFORE UPDATE OR DELETE ON fg_playbook_steps FOR EACH ROW EXECUTE FUNCTION fg_immutable();
