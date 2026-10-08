package io.fundingguard;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    final WorkflowService service;
    final JdbcTemplate db;
    final AutomationEngine engine;

    public ApiController(WorkflowService service, JdbcTemplate db, AutomationEngine engine) {
        this.service = service;
        this.db = db;
        this.engine = engine;
    }

    WorkflowService.Actor actor(Principal p) {
        return service.actor(p.getName());
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "header", token.getHeaderName());
    }

    @GetMapping("/automation")
    public Map<String,Object> automation(Principal p) { return engine.workspace(actor(p)); }

    @GetMapping("/automation/runs/{id}")
    public Map<String,Object> automationRun(Principal p, @PathVariable String id) { return engine.detail(actor(p), id); }

    @PostMapping("/automation/events")
    public Map<String,Object> signal(Principal p, @RequestBody AutomationEngine.Signal signal) { return engine.ingest(actor(p), signal); }

    public record Scenario(String scenario) {}
    @PostMapping("/automation/replay")
    public Map<String,Object> replay(Principal p, @RequestBody Scenario r) { return engine.replay(actor(p), r.scenario()); }

    @GetMapping("/me")
    public WorkflowService.Actor me(Principal p) {
        return actor(p);
    }

    Map<String, Object> mask(Map<String, Object> p) {
        var m = new LinkedHashMap<>(p);
        if (m.get("account") instanceof String s) {
            m.put("account", "**** " + s.substring(Math.max(0, s.length() - 4)));
        
        }return m;
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace(Principal principal) {
        var a = actor(principal);
        var raw = db.queryForList("select p.*,c.version as contact_version,c.active as contact_active from fg_payouts p join fg_contacts c on p.contact_id=c.id where p.tenant_id=? order by p.deadline,p.id", a.tenant());
        var payouts = raw.stream().map(p -> {
            var m = mask(p);
            m.put("blockers", service.blockers(p));
            return m;
        }).toList();
        var ledger = db.queryForList("select l.*,p.borrower,p.lender from fg_ledger l join fg_payouts p on p.id=l.payout_id where l.tenant_id=? order by l.created_at desc limit 200", a.tenant()).stream().map(this::mask).toList();
        return Map.of("user", a, "payouts", payouts, "cases", db.queryForList("select c.*,p.borrower,p.amount_minor from fg_cases c join fg_payouts p on p.id=c.payout_id where c.tenant_id=? order by c.created_at desc limit 200", a.tenant()), "ledger", ledger, "audit", db.queryForList("select * from fg_audit where tenant_id=? order by id desc limit 200", a.tenant()), "contacts", db.queryForList("select * from fg_contacts where tenant_id=? order by institution", a.tenant()), "notifications", db.queryForList("select * from fg_notifications where tenant_id=? order by id desc limit 25", a.tenant()), "pendingJobs", db.queryForObject("select count(*) from fg_outbox where tenant_id=? and processed_at is null", Integer.class, a.tenant()), "serverTime", Instant.now().toString());
    }

    @GetMapping("/payouts/{id}")
    public Map<String, Object> detail(Principal p, @PathVariable String id) {
        var a = actor(p);
        var row = service.payout(a, id);
        return Map.of("payout", mask(row), "blockers", service.blockers(row), "versions", db.queryForList("select * from fg_versions where payout_id=? order by version desc", id).stream().map(this::mask).toList(), "audit", db.queryForList("select * from fg_audit where tenant_id=? and payout_id=? order by id desc", a.tenant(), id), "verifications", db.queryForList("select v.*,u.name as actor_name from fg_verifications v join fg_users u on u.id=v.actor_id where v.payout_id=? order by v.id desc", id), "approvals", db.queryForList("select v.*,u.name as actor_name from fg_approvals v join fg_users u on u.id=v.actor_id where v.payout_id=? order by v.id desc", id));
    }

    @PostMapping("/payouts")
    public Map<String, String> create(Principal p, @RequestBody WorkflowService.Create r) {
        return Map.of("id", service.create(actor(p), r));
    }

    public record Action(int version, String reason, long amountMinor, String account, String type) {

    }

    @PostMapping("/payouts/{id}/{action}")
    public Map<String, Object> act(Principal p, @PathVariable String id, @PathVariable String action, @RequestBody Action r, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var a = actor(p);
        switch (action) {
            case "revise" ->
                service.revise(a, id, r.version(), r.amountMinor(), r.account(), r.reason());
            case "verify" ->
                service.verify(a, id, r.version(), r.reason());
            case "approve" ->
                service.approve(a, id, r.version());
            case "release" -> {
                return service.release(a, id, r.version(), key);
            }
            case "hold" ->
                service.hold(a, id, r.version(), r.reason());
            case "clear" ->
                service.clear(a, id, r.version(), r.reason());
            case "confirm" ->
                service.confirm(a, id, r.type(), r.reason());
            default ->
                throw new ApiException(404, "Action not found.");
        }
        return Map.of("ok", true);
    }

    @PostMapping("/cases/{id}/resolve")
    public Map<String, Boolean> resolve(Principal p, @PathVariable String id, @RequestBody Action r) {
        service.resolve(actor(p), id, r.reason());
        return Map.of("ok", true);
    }

    @GetMapping(value = "/audit/export", produces = "text/csv")
    public ResponseEntity<String> export(Principal p) {
        var a = actor(p);
        var rows = db.queryForList("select created_at,actor_name,action,payout_id,detail from fg_audit where tenant_id=? order by id desc limit 10000", a.tenant());
        StringBuilder csv = new StringBuilder("Timestamp,Actor,Action,Payout,Detail\r\n");
        for (var r : rows) {
            for (String k : List.of("created_at", "actor_name", "action", "payout_id", "detail")) {
                String v = Objects.toString(r.get(k), "");
                if (v.matches("^[=+@\\-\\t\\r].*")) {
                    v = "'" + v;
                
                }csv.append('"').append(v.replace("\"", "\"\"")).append("\",");
            }
            csv.setLength(csv.length() - 1);
            csv.append("\r\n");
        }
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=fundingguard-audit.csv").header("Cache-Control", "no-store").body(csv.toString());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> problem(ApiException e) {
        return ResponseEntity.status(e.status).body(Map.of("message", e.getMessage(), "status", e.status));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict() {
        return ResponseEntity.status(409).body(Map.of("message", "This action conflicts with a committed record. Refresh before trying again."));
    }
}
