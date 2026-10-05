package io.fundingguard;

import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoSeed implements ApplicationRunner {

    final JdbcTemplate db;
    final PasswordEncoder encoder;
    @Value("${fundingguard.seed}")
    boolean enabled;
    @Value("${fundingguard.demo-password}")
    String password;

    public DemoSeed(JdbcTemplate db, PasswordEncoder encoder) {
        this.db = db;
        this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || db.queryForObject("select count(*) from fg_tenants", Integer.class) > 0) {
            return;
        }
        if (password.length() < 12) {
            throw new IllegalStateException("SEED_DEMO requires DEMO_PASSWORD with at least 12 characters.");
        }
        db.update("insert into fg_tenants values('maple','Maple Demo Lending'),('cedar','Cedar Demo Organization')");
        String[][] people = {{"ops", "Jay Shah", "operations", "OPERATIONS"}, {"verifier", "Noah Singh", "verifier", "VERIFIER"}, {"approver", "Maya Chen", "approver", "APPROVER"}, {"security", "Elena Brooks", "security", "SECURITY"}, {"auditor", "Owen Reed", "auditor", "AUDITOR"}};
        String hash = encoder.encode(password);
        for (var u : people) {
            db.update("insert into fg_users(id,tenant_id,name,email,role,password_hash) values(?,'maple',?,?,?,?)", u[0], u[1], u[2] + "@demo.fundingguard.local", u[3], hash);
        }
        db.update("insert into fg_users(id,tenant_id,name,email,role,password_hash) values('other','cedar','Alex Morgan','other@demo.fundingguard.local','OPERATIONS',?)", hash);
        String[][] contacts = {{"ct-1", "Cedar Demo Mortgages", "Lending operations", "+1 613 555 0101"}, {"ct-2", "Northstar Demo Credit", "Payout services", "+1 416 555 0102"}, {"ct-3", "Harbor Demo Financial", "Mortgage servicing", "+1 604 555 0103"}};
        for (var ct : contacts) {
            db.update("insert into fg_contacts(id,tenant_id,institution,name,channel,provenance) values(?,'maple',?,?,?,'SIMULATED: independently established contact. No real institution verified.')", ct[0], ct[1], ct[2], ct[3]);
        }
        db.update("insert into fg_contacts(id,tenant_id,institution,name,channel,provenance) values('ct-other','cedar','Other Demo Lender','Other Contact','+1 613 555 0199','SIMULATED')");
        String[][] rows = {{"FG-1042", "Samira Malik", "82 Richmond Road, Ottawa", "ct-1", "42500000", "0012342041", "hold"}, {"FG-1043", "Daniel & Julia Park", "16 Willow Avenue, Toronto", "ct-2", "61250000", "0044551088", "ready"}, {"FG-1044", "Oliver Thompson", "205 Albert Street, Ottawa", "ct-1", "31825000", "0012347712", "verify"}, {"FG-1045", "Amelia Laurent", "44 Riverside Drive, Gatineau", "ct-3", "79500000", "0099773301", "review"}, {"FG-1046", "Ethan & Sophie Clarke", "9 Maple Crescent, Kingston", "ct-2", "26780000", "0044558402", "ready"}, {"FG-1047", "Zara Ahmed", "71 Bank Street, Ottawa", "ct-3", "48590000", "0099772230", "verify"}, {"FG-1048", "Lucas Wilson", "33 Pine Street, Toronto", "ct-1", "51200000", "0012345524", "review"}};
        for (int i = 0; i < rows.length; i++) {
            var r = rows[i];
            var lender = db.queryForObject("select institution from fg_contacts where id=?", String.class, r[3]);
            db.update("insert into fg_payouts(id,tenant_id,borrower,property,lender,loan_reference,contact_id,amount_minor,account,maker_id,deadline) values(?,'maple',?,?,?,?,?,?,?,'ops',?)", r[0], r[1], r[2], lender, "DEMO-LOAN-" + (1042 + i), r[3], Long.parseLong(r[4]), r[5], java.sql.Date.valueOf(LocalDate.now().plusDays(i % 3)));
            db.update("insert into fg_versions(payout_id,version,amount_minor,account,maker_id,reason) values(?,1,?,?,'ops','Initial synthetic payout statement')", r[0], Long.parseLong(r[4]), r[5]);
            db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail,created_at) values('maple',?,'ops','Jay Shah','INSTRUCTIONS_SUBMITTED','Version 1 submitted for independent review.',now()-(? * interval '9 minutes'))", r[0], i + 1);
            if (List.of("ready", "review").contains(r[6])) {
                db.update("update fg_payouts set verified_by='verifier',verified_at=now(),verified_version=1,verified_contact_version=1 where id=?", r[0]);
                db.update("insert into fg_verifications(payout_id,instruction_version,contact_version,actor_id,evidence) values(?,1,1,'verifier','DEMO: callback completed against the seeded trusted registry.')", r[0]);
            }
            if (r[6].equals("ready")) {
                db.update("update fg_payouts set approved_by='approver',approved_at=now(),approved_version=1,approved_contact_version=1,state='APPROVED' where id=?", r[0]);
                db.update("insert into fg_approvals(payout_id,instruction_version,contact_version,actor_id) values(?,1,1,'approver')", r[0]);
                db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail) values('maple',?,'approver','Maya Chen','PAYOUT_APPROVED','Seeded demo approval; valid for 30 minutes.')", r[0]);
            }
        }
        db.update("insert into fg_versions(payout_id,version,amount_minor,account,maker_id,reason) values('FG-1042',2,42500000,'0012349988','ops','Receiving account changed in replacement payout instructions.')");
        db.update("update fg_payouts set version=2,account='0012349988',hold_active=true,hold_reason='Account changed shortly before funding. Independent verification is required.' where id='FG-1042'");
        db.update("insert into fg_cases(id,tenant_id,payout_id,rule_code,severity,title,detail) values('INV-2041','maple','FG-1042','DR-01','HIGH','Payout destination changed','The destination changed from account ending 2041 to 9988. This is a synthetic scenario. Verify using the established contact, not the replacement instructions.')");
        db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail) values('maple','FG-1042','security','Elena Brooks','HOLD_PLACED','Destination changed. Funding paused pending independent verification.')");
        db.update("insert into fg_notifications(tenant_id,title,detail) values('maple','Review required','FG-1042 has an active security hold. No simulated funds have been released.')");
    }
}
