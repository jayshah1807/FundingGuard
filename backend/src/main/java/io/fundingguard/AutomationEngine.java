package io.fundingguard;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deterministic demo policy. All evidence and protective effects commit together. */
@Service
public class AutomationEngine {
    private final JdbcTemplate db;
    static final String RULE = "COR-01";
    public record Signal(String eventKey, String payoutId, int version, String kind, String occurredAt, String detail) {}

    public AutomationEngine(JdbcTemplate db) { this.db = db; }

    private String id(String prefix) { return prefix + UUID.randomUUID(); }
    private void security(WorkflowService.Actor a) {
        if (!"SECURITY".equals(a.role())) throw new ApiException(403, "Only Security can ingest signals or replay scenarios.");
    }
    private void lock(WorkflowService.Actor a) {
        db.queryForObject("select id from fg_tenants where id=? for update", String.class, a.tenant());
    }
    private Map<String,Object> payout(WorkflowService.Actor a, String id) {
        var rows = db.queryForList("select * from fg_payouts where tenant_id=? and id=?", a.tenant(), id);
        if (rows.isEmpty()) throw new ApiException(404, "Payout not found.");
        return rows.getFirst();
    }
    private String text(String s, int min, int max) {
        if (s == null || s.trim().length() < min || s.length() > max || s.matches("(?s).*[\\x00-\\x1F].*"))
            throw new ApiException(422, "Invalid event key or evidence note.");
        return s.trim();
    }

    @Transactional
    public Map<String,Object> ingest(WorkflowService.Actor a, Signal signal) {
        return ingestFrom(a, signal, "MANUAL_SIMULATION");
    }

    // Signed intake supplies this source internally; clients cannot select a source.
    @Transactional
    public Map<String,Object> ingestSigned(WorkflowService.Actor a, Signal signal) {
        return ingestFrom(a, signal, "SIGNED_SIMULATOR:" + a.id());
    }

    private Map<String,Object> ingestFrom(WorkflowService.Actor a, Signal signal, String source) {
        security(a);
        lock(a);
        String key = text(signal.eventKey(), 8, 100), detail = text(signal.detail(), 8, 500);
        if (!Set.of("LOGIN_ANOMALY", "CONTACT_CHANGE_REPORTED", "NORMAL_LOGIN").contains(Objects.toString(signal.kind(), "")))
            throw new ApiException(422, "Unsupported signal type. Instruction changes are emitted by the application.");
        var p = payout(a, signal.payoutId());
        Instant at;
        try { at = (signal.occurredAt() == null ? Instant.now() : Instant.parse(signal.occurredAt())).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
        catch (Exception e) { throw new ApiException(422, "Invalid event timestamp."); }
        var duplicate = db.queryForList("select * from fg_security_events where tenant_id=? and source=? and source_key=?", a.tenant(), source, key);
        if (!duplicate.isEmpty()) {
            var old = duplicate.getFirst();
            boolean sameTime = signal.occurredAt() == null || ((Timestamp)old.get("occurred_at")).toInstant().equals(at);
            if (!Objects.equals(old.get("payout_id"), signal.payoutId()) || !Objects.equals(old.get("kind"), signal.kind())
                || !Objects.equals(old.get("detail"), detail) || !Objects.equals(old.get("submitted_by"), a.id())
                || ((Number)old.get("instruction_version")).intValue() != signal.version() || !sameTime)
                throw new ApiException(409, "Event key already belongs to different evidence.");
            return Map.of("eventId", old.get("id"), "replayed", true);
        }
        if (at.isAfter(Instant.now()) || at.isBefore(Instant.now().minusSeconds(86400)))
            throw new ApiException(422, "Events must be within the past 24 hours and not in the future.");
        if (((Number)p.get("version")).intValue() != signal.version()) throw new ApiException(409, "Refresh the current instruction version.");
        String event = record(a, signal.payoutId(), signal.version(), source, key, signal.kind(), at, detail);
        evaluate(a, signal.payoutId());
        return Map.of("eventId", event, "replayed", false);
    }

    String record(WorkflowService.Actor a, String payoutId, int version, String source, String key, String kind, Instant at, String detail) {
        String event = id("EVT-");
        db.update("insert into fg_security_events(id,tenant_id,payout_id,instruction_version,source,source_key,kind,occurred_at,submitted_by,detail) values(?,?,?,?,?,?,?,?,?,?)",
            event, a.tenant(), payoutId, version, source, key, kind, Timestamp.from(at), a.id(), detail);
        return event;
    }

    // Called inside WorkflowService's existing tenant-locked revision transaction.
    void instructionChanged(WorkflowService.Actor a, String payoutId, int version, boolean accountChanged) {
        record(a, payoutId, version, "APPLICATION", payoutId + ":" + version,
            accountChanged ? "ACCOUNT_CHANGED" : "AMOUNT_CHANGED", Instant.now(),
            accountChanged ? "Receiving account changed; prior reviews invalidated." : "Amount changed; prior reviews invalidated.");
        evaluate(a, payoutId);
    }

    void evaluate(WorkflowService.Actor a, String payoutId) {
        var p = payout(a, payoutId);
        int version = ((Number)p.get("version")).intValue();
        if (db.queryForObject("select count(*) from fg_playbook_runs where tenant_id=? and payout_id=? and instruction_version=? and rule_code=?",
            Integer.class, a.tenant(), payoutId, version, RULE) > 0) return;
        Instant now = Instant.now();
        var evidence = db.queryForList("select * from fg_security_events where tenant_id=? and payout_id=? and occurred_at>=? and occurred_at<=? order by occurred_at,id",
            a.tenant(), payoutId, Timestamp.from(now.minusSeconds(900)), Timestamp.from(now));
        var changes = evidence.stream().filter(e -> "ACCOUNT_CHANGED".equals(e.get("kind")) && ((Number)e.get("instruction_version")).intValue() == version).toList();
        var risks = evidence.stream().filter(e -> Set.of("LOGIN_ANOMALY", "CONTACT_CHANGE_REPORTED").contains(e.get("kind"))).toList();
        if (changes.isEmpty() || risks.isEmpty()) return;
        var matched = new ArrayList<Map<String,Object>>(changes);
        matched.addAll(risks);
        boolean terminal = Set.of("RELEASED_SIMULATED", "CLOSED", "CANCELLED").contains(p.get("state"));
        boolean alreadyHeld = Boolean.TRUE.equals(p.get("hold_active"));
        String run = id("PB-"), caseId = id("INV-");
        String summary = "COR-01 v1 matched " + matched.size() + " events for instruction v" + version
            + ": an account change and a reported login/contact risk within 15 minutes. "
            + (terminal ? "Payout is terminal; observation only, no payment state changed." : "Protective hold enforced. Independent clearance and fresh approval required.")
            + " This rule is not a fraud determination. Summary generated from recorded evidence, not AI.";
        db.update("insert into fg_cases(id,tenant_id,payout_id,rule_code,severity,title,detail) values(?,?,?,?,?,?,?)",
            caseId, a.tenant(), payoutId, RULE, "HIGH", "Correlated account-change risk", summary);
        db.update("insert into fg_playbook_runs(id,tenant_id,payout_id,instruction_version,rule_code,rule_version,status,case_id,summary) values(?,?,?,?,?,1,?,?,?)",
            run, a.tenant(), payoutId, version, RULE, terminal ? "OBSERVE_ONLY" : "CONTAINED", caseId, summary);
        for (var e : matched) db.update("insert into fg_playbook_evidence(run_id,event_id) values(?,?)", run, e.get("id"));
        step(run, 1, "CORRELATE", "COMPLETED", matched.size() + " evidence records captured. Window: 15 minutes; rule version: 1.");
        step(run, 2, "OPEN_CASE", "COMPLETED", caseId);
        if (!terminal) {
            db.update("update fg_payouts set hold_active=true,hold_reason=?,clearance_by=null,clearance_reason=null,approved_at=null,approved_by=null,approved_version=null,state='REVIEW_REQUIRED',updated_at=now() where id=? and tenant_id=?",
                alreadyHeld ? p.get("hold_reason") : "Automatic COR-01 hold: account change correlated with reported risk.", payoutId, a.tenant());
        }
        step(run, 3, "PROTECTIVE_HOLD", terminal ? "SKIPPED" : "COMPLETED",
            terminal ? "Already terminal; no hold or payment mutation." : alreadyHeld ? "Existing hold retained; pending clearance and approval invalidated." : "Hold placed; pending clearance and approval invalidated.");
        db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail) values(?,?,?,'Automation / COR-01','PLAYBOOK_COMPLETED',?)", a.tenant(), payoutId, a.id(), summary + " Run: " + run);
        db.update("insert into fg_outbox(tenant_id,payout_id,kind) values(?,?,'AUTOMATION_CASE_OPENED')", a.tenant(), payoutId);
        step(run, 4, "NOTIFY", "QUEUED", "In-app notification queued in the transactional outbox.");
    }
    private void step(String run, int seq, String action, String outcome, String detail) {
        db.update("insert into fg_playbook_steps(run_id,sequence,action,outcome,detail) values(?,?,?,?,?)", run, seq, action, outcome, detail);
    }

    public Map<String,Object> workspace(WorkflowService.Actor a) {
        return Map.of("events", db.queryForList("select * from fg_security_events where tenant_id=? order by received_at desc,id limit 100", a.tenant()),
            "runs", db.queryForList("select * from fg_playbook_runs where tenant_id=? order by created_at desc limit 100", a.tenant()),
            "scenarios", db.queryForList("select * from fg_scenario_runs where tenant_id=? order by created_at desc limit 50", a.tenant()));
    }
    public Map<String,Object> detail(WorkflowService.Actor a, String id) {
        var rows = db.queryForList("select * from fg_playbook_runs where tenant_id=? and id=?", a.tenant(), id);
        if (rows.isEmpty()) throw new ApiException(404, "Playbook run not found.");
        return Map.of("run", rows.getFirst(), "steps", db.queryForList("select * from fg_playbook_steps where run_id=? order by sequence", id),
            "evidence", db.queryForList("select e.* from fg_security_events e join fg_playbook_evidence x on x.event_id=e.id where x.run_id=? order by e.occurred_at,e.id", id));
    }

    @Transactional
    public Map<String,Object> replay(WorkflowService.Actor a, String scenario) {
        security(a);
        if (!Set.of("BENIGN", "CORRELATED", "OUTSIDE_WINDOW").contains(Objects.toString(scenario, ""))) throw new ApiException(422, "Unknown scenario.");
        lock(a);
        long start = System.nanoTime();
        var contacts = db.queryForList("select * from fg_contacts where tenant_id=? and active=true order by id limit 1", a.tenant());
        if (contacts.isEmpty()) throw new ApiException(409, "Scenario requires an active synthetic contact.");
        var contact = contacts.getFirst();
        String payoutId = id("SIM-"), scenarioId = id("SCN-");
        db.update("insert into fg_payouts(id,tenant_id,borrower,property,lender,loan_reference,contact_id,amount_minor,account,maker_id,deadline,version,scenario_run) values(?,?,?,?,?,?,?,42500000,'9876543210',?,current_date,2,true)",
            payoutId, a.tenant(), "Scenario / " + scenario, "Synthetic replay property", contact.get("institution"), scenarioId, contact.get("id"), a.id());
        db.update("insert into fg_versions(payout_id,version,amount_minor,account,maker_id,reason) values(?,1,42500000,'1234567890',?,'Synthetic initial instructions'),(?,2,42500000,'9876543210',?,'Synthetic account revision')", payoutId, a.id(), payoutId, a.id());
        Instant now = Instant.now();
        record(a, payoutId, 1, "SCENARIO", scenarioId + ":signal", scenario.equals("BENIGN") ? "NORMAL_LOGIN" : "LOGIN_ANOMALY",
            now.minusSeconds(scenario.equals("OUTSIDE_WINDOW") ? 960 : 60), "Synthetic login signal from scenario replay.");
        instructionChanged(a, payoutId, 2, true);
        boolean held = Boolean.TRUE.equals(payout(a, payoutId).get("hold_active"));
        db.update("insert into fg_scenario_runs(id,tenant_id,payout_id,scenario,expected_hold,observed_hold,duration_ms) values(?,?,?,?,?,?,?)",
            scenarioId, a.tenant(), payoutId, scenario, scenario.equals("CORRELATED"), held, (System.nanoTime()-start)/1_000_000);
        db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail) values(?,?,?,?, 'SCENARIO_REPLAYED',?)", a.tenant(), payoutId, a.id(), a.name(), "Synthetic scenario: " + scenario);
        return Map.of("payoutId", payoutId, "held", held, "passed", held == scenario.equals("CORRELATED"));
    }
}
