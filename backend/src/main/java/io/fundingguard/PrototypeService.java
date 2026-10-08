package io.fundingguard;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PrototypeService {
    static final List<String> ROLES = List.of("OPERATIONS", "VERIFIER", "APPROVER", "SECURITY", "AUDITOR");
    final JdbcTemplate db;
    final WorkflowService workflow;
    public PrototypeService(JdbcTemplate db, WorkflowService workflow) { this.db = db; this.workflow = workflow; }

    @Transactional
    public WorkflowService.Actor create() {
        // Serialize creation across sessions and instances; bound retained demo data.
        db.queryForObject("select id from fg_prototype_quota where id=1 for update", Integer.class);
        db.update("update fg_prototype_quota set window_start=now(),window_count=0 where id=1 and window_start < now()-interval '1 minute'");
        if (db.update("update fg_prototype_quota set total=total+1,window_count=window_count+1 where id=1 and total<100 and window_count<5") != 1)
            throw new ApiException(429, "Prototype capacity reached. Please try again later or contact the project owner.");
        String tenant = "prototype-" + UUID.randomUUID();
        db.update("insert into fg_tenants(id,name,prototype_expires_at) values(?, 'Visitor prototype', now()+interval '2 hours')", tenant);
        String[] names = {"Demo Operations", "Demo Verifier", "Demo Approver", "Demo Security", "Demo Auditor"};
        for (int i=0; i<ROLES.size(); i++) {
            String role = ROLES.get(i);
            db.update("insert into fg_users(id,tenant_id,name,email,role,password_hash) values(?,?,?,?,?,?)", tenant+role, tenant, names[i], email(tenant, role), role, "!disabled-password-login!");
        }
        String contact = tenant + "-contact";
        db.update("insert into fg_contacts(id,tenant_id,institution,name,channel,provenance) values(?,?,?,?,?,?)", contact, tenant, "Demo Mortgage Co", "Synthetic Contact", "+1 613 555 0101", "Synthetic prototype fixture; no real verification");
        var actor = workflow.actor(email(tenant,"OPERATIONS"));
        workflow.create(actor, new WorkflowService.Create("Alex Demo", "10 Example Street", "DEMO-001", contact, 42500000, "1234567890", LocalDate.now().plusDays(1).toString()));
        return actor;
    }
    static String email(String tenant, String role) { return role.toLowerCase(java.util.Locale.ROOT)+"@"+tenant+".prototype.local"; }
}
