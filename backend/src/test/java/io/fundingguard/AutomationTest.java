package io.fundingguard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"fundingguard.seed=true", "fundingguard.demo-password=Test-Only-Password-2026!",
    "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55439/postgres?sslmode=disable&preferQueryMode=simple}",
    "spring.datasource.username=${TEST_DATABASE_USER:postgres}", "spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
    "spring.datasource.hikari.maximum-pool-size=${TEST_POOL_SIZE:1}", "spring.flyway.enabled=${TEST_FLYWAY:false}",
    "fundingguard.outbox.initial-delay-ms=3600000",
    "fundingguard.ingest.keys={\"current\":{\"secret\":\"TEST-ONLY-NOT-A-SECRET-1234567890123456\",\"email\":\"security@demo.fundingguard.local\"},\"previous\":{\"secret\":\"TEST-ONLY-OLD-KEY-12345678901234567890\",\"email\":\"security@demo.fundingguard.local\"}}"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class AutomationTest {
    @Autowired WorkflowService workflow;
    @Autowired AutomationEngine engine;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired SignedIntake intake;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    static final String SECRET = "TEST-ONLY-NOT-A-SECRET-1234567890123456";
    @Autowired OutboxWorker worker;
    WorkflowService.Actor actor(String role) { return workflow.actor(role + "@demo.fundingguard.local"); }
    String create() { return workflow.create(actor("operations"), new WorkflowService.Create("Automation Test", "15 Synthetic Street", UUID.randomUUID().toString(), "ct-1", 42500000L, "1234567890", LocalDate.now().toString())); }
    AutomationEngine.Signal signal(String id, int version, String kind, Instant time) {
        return new AutomationEngine.Signal(UUID.randomUUID().toString(), id, version, kind, time == null ? null : time.toString(), "Synthetic reported signal evidence");
    }
    void change(String id) { workflow.revise(actor("operations"), id, 1, 42500000L, "9876543210", "Synthetic receiving account change"); }
    int count(String table, String id) { return db.queryForObject("select count(*) from " + table + " where payout_id=?", Integer.class, id); }
    void fails(int status, Runnable fn) { assertEquals(status, assertThrows(ApiException.class, fn::run).status); }

    @Test void accountChangeAloneDoesNotHold() {
        String id = create(); change(id);
        assertEquals(false, workflow.payout(actor("security"), id).get("hold_active"));
        assertEquals(0, count("fg_playbook_runs", id));
    }
    @Test void signalBeforeRevisionCorrelatesAndCapturesEvidence() {
        String id = create(); engine.ingest(actor("security"), signal(id,1,"LOGIN_ANOMALY",null)); change(id);
        assertEquals(true, workflow.payout(actor("security"),id).get("hold_active"));
        assertEquals(1,count("fg_playbook_runs",id));
        String run = db.queryForObject("select id from fg_playbook_runs where payout_id=?",String.class,id);
        assertEquals(2,((java.util.List<?>)engine.detail(actor("security"),run).get("evidence")).size());
        assertEquals(4,db.queryForObject("select count(*) from fg_playbook_steps where run_id=?",Integer.class,run));
        fails(409,()->workflow.release(actor("operations"),id,2,UUID.randomUUID().toString()));
        workflow.verify(actor("verifier"),id,2,"Independent synthetic verification evidence");
        fails(409,()->workflow.approve(actor("approver"),id,2));
    }
    @Test void signalAfterRevisionCorrelatesAndRetryDoesNotDuplicate() {
        String id=create();change(id);var s=signal(id,2,"CONTACT_CHANGE_REPORTED",Instant.now());
        engine.ingest(actor("security"),s);
        assertEquals(true,engine.ingest(actor("security"),s).get("replayed"));
        assertEquals(2,count("fg_security_events",id));assertEquals(1,count("fg_playbook_runs",id));
        var changed=new AutomationEngine.Signal(s.eventKey(),id,2,"NORMAL_LOGIN",s.occurredAt(),s.detail());
        fails(409,()->engine.ingest(actor("security"),changed));
    }
    @Test void staleSignalsAndNormalLoginsDoNotMatch() {
        String id=create();change(id);
        engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",Instant.now().minusSeconds(901)));
        engine.ingest(actor("security"),signal(id,2,"NORMAL_LOGIN",null));
        assertEquals(0,count("fg_playbook_runs",id));
    }
    @Test void amountOnlyDoesNotTriggerAccountRule() {
        String id=create();engine.ingest(actor("security"),signal(id,1,"LOGIN_ANOMALY",null));
        workflow.revise(actor("operations"),id,1,43000000L,"1234567890","Synthetic amount-only update");
        assertEquals(0,count("fg_playbook_runs",id));
    }
    @Test void signalsDoNotCrossPayouts() {
        String a=create(),b=create();engine.ingest(actor("security"),signal(a,1,"LOGIN_ANOMALY",null));change(b);
        assertEquals(0,count("fg_playbook_runs",b));
    }
    @Test void manualHoldIsPreservedButPendingClearanceInvalidated() {
        String id=create();change(id);workflow.hold(actor("security"),id,2,"Existing manual security investigation");
        workflow.clear(actor("security"),id,2,"Pending clearance from earlier review");
        engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",null));
        var p=workflow.payout(actor("security"),id);
        assertNull(p.get("clearance_by"));assertEquals("Existing manual security investigation",p.get("hold_reason"));
    }
    @Test void clearingCaseDoesNotClearAutomaticHold() {
        String id=create();change(id);engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",null));
        String caseId=db.queryForObject("select case_id from fg_playbook_runs where payout_id=?",String.class,id);
        workflow.resolve(actor("security"),caseId,"Recorded investigation outcome and evidence");
        assertEquals(true,workflow.payout(actor("security"),id).get("hold_active"));
        workflow.clear(actor("security"),id,2,"Independent evidence reviewed by security");
        workflow.clear(actor("approver"),id,2,"Countersigned independent clearance evidence");
        engine.ingest(actor("security"),signal(id,2,"CONTACT_CHANGE_REPORTED",null));
        assertEquals(false,workflow.payout(actor("security"),id).get("hold_active"));
        assertEquals(1,count("fg_playbook_runs",id));
    }
    @Test void terminalPayoutIsObservedWithoutChangingReleasedState() {
        String id=create();change(id);workflow.verify(actor("verifier"),id,2,"Independent evidence for simulated payout");workflow.approve(actor("approver"),id,2);
        workflow.release(actor("operations"),id,2,UUID.randomUUID().toString());
        engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",null));
        var p=workflow.payout(actor("security"),id);assertEquals("RELEASED_SIMULATED",p.get("state"));assertEquals(false,p.get("hold_active"));
        assertEquals("OBSERVE_ONLY",db.queryForObject("select status from fg_playbook_runs where payout_id=?",String.class,id));
    }
    @Test void invalidAndUnauthorizedSignalsAreRejected() {
        String id=create();
        fails(403,()->engine.ingest(actor("operations"),signal(id,1,"LOGIN_ANOMALY",null)));
        fails(422,()->engine.ingest(actor("security"),signal(id,1,"ACCOUNT_CHANGED",null)));
        fails(422,()->engine.ingest(actor("security"),signal(id,1,"LOGIN_ANOMALY",Instant.now().plusSeconds(60))));
        fails(422,()->engine.ingest(actor("security"),signal(id,1,"LOGIN_ANOMALY",Instant.now().minusSeconds(90000))));
        fails(409,()->engine.ingest(actor("security"),signal(id,99,"LOGIN_ANOMALY",null)));
        var foreign=new WorkflowService.Actor("other","tenant-other","Other","SECURITY","other@example.test");
        // A real second-tenant actor cannot see the first tenant's payout or evidence.
        foreign=new WorkflowService.Actor(actor("other").id(),actor("other").tenant(),"Other","SECURITY",actor("other").email());
        final var outsider=foreign;
        fails(404,()->engine.ingest(outsider,signal(id,1,"LOGIN_ANOMALY",null)));
    }
    @Test void replaysExerciseBenignRiskAndTimeWindow() {
        for(String scenario:java.util.List.of("BENIGN","CORRELATED","OUTSIDE_WINDOW")) {
            var r=engine.replay(actor("security"),scenario);assertEquals(true,r.get("passed"));
            assertEquals(true,workflow.payout(actor("security"),(String)r.get("payoutId")).get("scenario_run"));
        }
        fails(403,()->engine.replay(actor("auditor"),"CORRELATED"));
        fails(422,()->engine.replay(actor("security"),"UNKNOWN"));
    }
    @Test void eventEvidenceIsAppendOnly() {
        String id=create();engine.ingest(actor("security"),signal(id,1,"NORMAL_LOGIN",null));
        assertThrows(Exception.class,()->db.update("update fg_security_events set detail='altered' where payout_id=?",id));
    }
    @Test void httpAuthCsrfAndRolesAreEnforced() throws Exception {
        mvc.perform(get("/api/automation")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/automation/replay").with(user("security@demo.fundingguard.local")).contentType("application/json").content("{\"scenario\":\"BENIGN\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/automation/replay").with(user("auditor@demo.fundingguard.local")).with(csrf()).contentType("application/json").content("{\"scenario\":\"BENIGN\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/automation").with(user("auditor@demo.fundingguard.local"))).andExpect(status().isOk()).andExpect(jsonPath("$.events").isArray());
    }

    org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(byte[] body, String key, String secret, String stamp, String nonce) {
        return post(SignedIntake.PATH).contentType("application/json").content(body)
            .header("X-FG-Key-Id", key).header("X-FG-Timestamp", stamp).header("X-FG-Nonce", nonce)
            .header("X-FG-Signature", java.util.HexFormat.of().formatHex(SignedIntake.signature(secret,key,stamp,nonce,body)));
    }
    String stamp() { return Long.toString(Instant.now().getEpochSecond()); }

    @Test void signedHttpIngestRejectsReplayAndDeduplicatesFreshEnvelope() throws Exception {
        String id=create();change(id);
        byte[] body=json.writeValueAsBytes(signal(id,2,"LOGIN_ANOMALY",Instant.now()));
        String time=stamp(),nonce=UUID.randomUUID().toString();
        mvc.perform(signed(body,"current",SECRET,time,nonce)).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(signed(body,"current",SECRET,time,nonce)).andExpect(status().isConflict());
        mvc.perform(signed(body,"current",SECRET,time,UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1,count("fg_playbook_runs",id));assertEquals(2,count("fg_security_events",id));
        assertTrue(db.queryForObject("select source from fg_security_events where payout_id=? and kind='LOGIN_ANOMALY'",String.class,id).startsWith("SIGNED_SIMULATOR:"));
    }

    @Test void forgedExpiredFutureAndUnknownKeyRequestsAreRejected() throws Exception {
        String id=create();byte[] body=json.writeValueAsBytes(signal(id,1,"LOGIN_ANOMALY",null));
        for(String time:java.util.List.of(Long.toString(Instant.now().minusSeconds(301).getEpochSecond()),Long.toString(Instant.now().plusSeconds(60).getEpochSecond())))
            mvc.perform(signed(body,"current",SECRET,time,UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(signed(body,"unknown",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(signed(body,"current","wrong-secret",stamp(),UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(signed(body,"current",SECRET,stamp(),UUID.randomUUID().toString()).content("{}" )).andExpect(status().isUnauthorized());
        assertEquals(0,count("fg_security_events",id));
    }

    @Test void rotationPreservesBusinessEventIdentity() throws Exception {
        String id=create();byte[] body=json.writeValueAsBytes(signal(id,1,"NORMAL_LOGIN",null));
        mvc.perform(signed(body,"previous","TEST-ONLY-OLD-KEY-12345678901234567890",stamp(),UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(signed(body,"current",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        assertEquals(1,count("fg_security_events",id));
    }

    @Test void signedRequestsCannotForgeTenantOrInternalEvents() throws Exception {
        String id=create();
        mvc.perform(signed(json.writeValueAsBytes(signal(id,1,"ACCOUNT_CHANGED",null)),"current",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isUnprocessableEntity());
        mvc.perform(signed("{\"tenant\":\"other\"}".getBytes(),"current",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(signed(new byte[8193],"current",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isPayloadTooLarge());
        var foreign=workflow.create(actor("other"),new WorkflowService.Create("Other tenant", "25 Synthetic Street", UUID.randomUUID().toString(),"ct-other",10000L,"1234567890",LocalDate.now().toString()));
        mvc.perform(signed(json.writeValueAsBytes(signal(foreign,1,"LOGIN_ANOMALY",null)),"current",SECRET,stamp(),UUID.randomUUID().toString())).andExpect(status().isNotFound());
    }

    @Test void disabledIntakeAndRemovedKeysFailClosed() throws Exception {
        var disabled=new SignedIntake("{}",workflow,engine,db,json);
        fails(503,()->disabled.accept(null,null,null,null,new byte[0]));
        String config="{\"current\":{\"secret\":\""+SECRET+"\",\"email\":\"security@demo.fundingguard.local\"}}";
        var rotated=new SignedIntake(config,workflow,engine,db,json);
        fails(401,()->rotated.accept("previous",stamp(),UUID.randomUUID().toString(),"0".repeat(64),new byte[0]));
    }

    @Test void failedTransactionRollsBackReceiptEvidenceCaseAndHold() throws Exception {
        String id=create();change(id);byte[] body=json.writeValueAsBytes(signal(id,2,"LOGIN_ANOMALY",null));
        String time=stamp(),nonce=UUID.randomUUID().toString();
        String signature=java.util.HexFormat.of().formatHex(SignedIntake.signature(SECRET,"current",time,nonce,body));
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{
            intake.accept("current",time,nonce,signature,body);
            throw new IllegalStateException("Injected failure before commit");
        }));
        assertEquals(0,count("fg_playbook_runs",id));assertEquals(1,count("fg_security_events",id));
        assertEquals(false,workflow.payout(actor("security"),id).get("hold_active"));
        assertEquals(0,db.queryForObject("select count(*) from fg_ingest_receipts where nonce=?",Integer.class,nonce));
        intake.accept("current",time,nonce,signature,body);
        assertEquals(1,count("fg_playbook_runs",id));
    }

    record Scenario(String name, boolean malicious, String change, String kind, int age, boolean expectedHold) {}
    @Test void evaluateLabelledSyntheticDataset() throws Exception {
        Scenario[] scenarios;
        try(var input=getClass().getResourceAsStream("/detection-scenarios.json")) { scenarios=json.readValue(input,Scenario[].class); }
        var rows=new java.util.ArrayList<java.util.Map<String,Object>>();
        int tp=0,fp=0,tn=0,fn=0;
        for(var scenario:scenarios) {
            String id=create();
            if(scenario.change().equals("account")) change(id);
            if(scenario.change().equals("amount")) workflow.revise(actor("operations"),id,1,43000000L,"1234567890","Synthetic amount correction");
            int version=scenario.change().equals("none")?1:2;
            long start=System.nanoTime();
            engine.ingest(actor("security"),signal(id,version,scenario.kind(),Instant.now().minusSeconds(scenario.age())));
            long micros=(System.nanoTime()-start)/1000;
            boolean held=Boolean.TRUE.equals(workflow.payout(actor("security"),id).get("hold_active"));
            assertEquals(scenario.expectedHold(),held,scenario.name());
            if(scenario.malicious()) { if(held) tp++; else fn++; } else { if(held) fp++; else tn++; }
            rows.add(java.util.Map.of("name",scenario.name(),"maliciousLabel",scenario.malicious(),"held",held,"processingMicros",micros));
        }
        var report=java.util.Map.of("generatedAt",Instant.now().toString(),"rule","COR-01 v1","workload","10 sequential synthetic events; no concurrency; elapsed service call, not network latency",
            "truePositives",tp,"falsePositives",fp,"trueNegatives",tn,"falseNegatives",fn,"scenarios",rows);
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(java.nio.file.Path.of("target/detection-evaluation.json").toFile(),report);
    }

    @Test void notificationFailureRollsBackAndCanBeRetried() {
        String id=create();change(id);engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",null));
        long job=db.queryForObject("select id from fg_outbox where payout_id=? and kind='AUTOMATION_CASE_OPENED'",Long.class,id);
        int before=db.queryForObject("select count(*) from fg_notifications",Integer.class);
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(s->{
            worker.process();throw new IllegalStateException("Injected worker failure before commit");
        }));
        assertEquals(before,db.queryForObject("select count(*) from fg_notifications",Integer.class));
        assertEquals(true,workflow.payout(actor("security"),id).get("hold_active"));
        int batches=db.queryForObject("select count(*) from fg_outbox where processed_at is null",Integer.class)/30+2;
        for(int i=0;i<batches;i++) worker.process();
        worker.process();
        assertEquals(1,db.queryForObject("select count(*) from fg_notifications where event_id=?",Integer.class,job));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="REAL_POSTGRES",matches="true")
    void releaseAndAutomaticHoldSerialize() throws Exception {
        String id=create();change(id);
        workflow.verify(actor("verifier"),id,2,"Independent synthetic verification evidence");workflow.approve(actor("approver"),id,2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var release=pool.submit(()->{start.await();try {workflow.release(actor("operations"),id,2,UUID.randomUUID().toString());return true;}catch(ApiException e){assertEquals(409,e.status);return false;}});
            var hold=pool.submit(()->{start.await();engine.ingest(actor("security"),signal(id,2,"LOGIN_ANOMALY",null));return true;});
            start.countDown();boolean released=release.get(15,java.util.concurrent.TimeUnit.SECONDS);hold.get(15,java.util.concurrent.TimeUnit.SECONDS);
            var p=workflow.payout(actor("security"),id);
            assertEquals(released?"RELEASED_SIMULATED":"REVIEW_REQUIRED",p.get("state"));
            assertEquals(!released,p.get("hold_active"));
            assertEquals(released?"OBSERVE_ONLY":"CONTAINED",db.queryForObject("select status from fg_playbook_runs where payout_id=?",String.class,id));
            assertEquals(released?1:0,count("fg_ledger",id));
        }
    }
}
