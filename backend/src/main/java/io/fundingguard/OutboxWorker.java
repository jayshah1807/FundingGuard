package io.fundingguard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxWorker {

    final JdbcTemplate db;

    public OutboxWorker(JdbcTemplate db) {
        this.db = db;
    }

    @Scheduled(fixedDelayString = "${fundingguard.outbox.delay-ms:2000}", initialDelayString = "${fundingguard.outbox.initial-delay-ms:0}")
    @Transactional
    public void process() {
        var jobs = db.queryForList("select * from fg_outbox where processed_at is null order by id limit 30 for update skip locked");
        for (var j : jobs) {
            db.update("insert into fg_notifications(tenant_id,title,detail,event_id) values(?,?,?,?) on conflict(event_id) do nothing", j.get("tenant_id"), j.get("kind").toString().replace('_', ' '), j.get("payout_id") + " updated. Review the linked payout for current status.", j.get("id"));
            db.update("update fg_outbox set processed_at=now() where id=?", j.get("id"));
        }
    }
}
