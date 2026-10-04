package io.fundingguard;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"fundingguard.seed=true","fundingguard.demo-password=Test-Only-Password-2026!",
  "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55439/postgres?sslmode=disable&preferQueryMode=simple}",
  "spring.datasource.username=${TEST_DATABASE_USER:postgres}","spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
  "spring.datasource.hikari.maximum-pool-size=${TEST_POOL_SIZE:1}","spring.flyway.enabled=${TEST_FLYWAY:false}"})
@AutoConfigureMockMvc
class WorkflowTest {
    @Autowired WorkflowService service;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    WorkflowService.Actor actor(String role){return service.actor(role+"@demo.fundingguard.local");}
    String create(){return service.create(actor("operations"),new WorkflowService.Create("Test Borrower","15 Synthetic Street","TEST-"+UUID.randomUUID(),"ct-1",42500000L,"1234567890",LocalDate.now().toString()));}
    void ready(String id){service.verify(actor("verifier"),id,1,"Synthetic callback confirmed exact amount and destination.");service.approve(actor("approver"),id,1);}
    void fails(int status,Runnable action){assertEquals(status,assertThrows(ApiException.class,action::run).status);}

    @Test void releaseRequiresVerificationAndApproval(){String id=create();fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));}
    @Test void separateRolesAreEnforced(){String id=create();fails(403,()->service.verify(actor("operations"),id,1,"Enough evidence text"));fails(403,()->service.approve(actor("verifier"),id,1));}
    @Test void tenantCannotReadAnotherTenant(){String id=create();fails(404,()->service.payout(actor("other"),id));}
    @Test void tenantCannotMutateAnotherTenant(){String id=create();fails(404,()->service.revise(actor("other"),id,1,500L,"1234567890","Changed destination"));}
    @Test void successfulReleaseIsIdempotent(){String id=create();ready(id);String key=UUID.randomUUID().toString();var first=service.release(actor("operations"),id,1,key);var second=service.release(actor("operations"),id,1,key);assertEquals(first.get("ledgerId"),second.get("ledgerId"));assertEquals(true,second.get("replayed"));assertEquals(1,db.queryForObject("select count(*) from fg_ledger where payout_id=?",Integer.class,id));}
    @Test void secondKeyCannotDoubleRelease(){String id=create();ready(id);service.release(actor("operations"),id,1,UUID.randomUUID().toString());fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));}
    @Test void idempotencyKeyCannotBeReusedForDifferentPayout(){String a=create(),b=create();ready(a);ready(b);String key=UUID.randomUUID().toString();service.release(actor("operations"),a,1,key);fails(409,()->service.release(actor("operations"),b,1,key));}
    @Test void revisionInvalidatesPriorApproval(){String id=create();ready(id);service.revise(actor("operations"),id,1,43000000L,"1234567891","Updated payout statement");var p=service.payout(actor("operations"),id);assertEquals(2,p.get("version"));assertNull(p.get("approved_by"));assertNull(p.get("verified_by"));fails(409,()->service.release(actor("operations"),id,2,UUID.randomUUID().toString()));}
    @Test void staleInstructionVersionIsRejected(){String id=create();service.revise(actor("operations"),id,1,43000000L,"1234567891","Updated payout statement");fails(409,()->service.verify(actor("verifier"),id,1,"Verified outdated statement"));}
    @Test void holdPreventsReleaseAndNeedsTwoPeople(){String id=create();ready(id);service.hold(actor("security"),id,1,"Suspicious replacement instructions");fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));fails(409,()->service.clear(actor("approver"),id,1,"Reviewed the evidence thoroughly"));service.clear(actor("security"),id,1,"Independent callback confirmed destination");assertEquals(true,service.payout(actor("operations"),id).get("hold_active"));service.clear(actor("approver"),id,1,"Countersigned independent evidence");assertEquals(false,service.payout(actor("operations"),id).get("hold_active"));assertNull(service.payout(actor("operations"),id).get("approved_at"));}
    @Test void revisionCancelsPendingHoldClearance(){String id=create();service.hold(actor("security"),id,1,"Suspicious replacement instructions");service.clear(actor("security"),id,1,"Independent callback confirmed destination");service.revise(actor("operations"),id,1,50000L,"1234567891","Updated payout statement");fails(409,()->service.clear(actor("approver"),id,2,"Countersign outdated proposal"));}
    @Test void expiredApprovalBlocksRelease(){String id=create();ready(id);db.update("update fg_payouts set approved_at=now()-interval '31 minutes' where id=?",id);fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));}
    @Test void expiredVerificationBlocksApproval(){String id=create();service.verify(actor("verifier"),id,1,"Independent verification evidence");db.update("update fg_payouts set verified_at=now()-interval '25 hours' where id=?",id);fails(409,()->service.approve(actor("approver"),id,1));}
    @Test void changedContactVersionBlocksRelease(){String id=create();ready(id);db.update("update fg_payouts set verified_contact_version=999 where id=?",id);fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));}
    @Test void revokedReviewerBlocksRelease(){String id=create();ready(id);db.update("update fg_users set active=false where id='verifier'");try{fails(409,()->service.release(actor("operations"),id,1,UUID.randomUUID().toString()));}finally{db.update("update fg_users set active=true where id='verifier'");}}
    @Test void receiptMustPrecedeDischarge(){String id=create();ready(id);service.release(actor("operations"),id,1,UUID.randomUUID().toString());fails(409,()->service.confirm(actor("operations"),id,"discharge","Synthetic discharge evidence"));service.confirm(actor("operations"),id,"receipt","Synthetic receipt evidence");service.confirm(actor("operations"),id,"discharge","Synthetic discharge evidence");assertEquals(true,service.payout(actor("operations"),id).get("discharge"));}
    @Test void amountsAndAccountsAreValidated(){assertThrows(ApiException.class,()->service.amount(0));assertThrows(ApiException.class,()->service.amount(100000001));assertThrows(ApiException.class,()->service.account("ABC123"));}
    @Test void duplicateLoanReferenceIsRejected(){String id=create();String loan=(String)service.payout(actor("operations"),id).get("loan_reference");fails(409,()->service.create(actor("operations"),new WorkflowService.Create("Other borrower","25 Synthetic Street",loan,"ct-1",1000,"1234567890",LocalDate.now().toString())));}
    @Test void auditCannotBeUpdated(){String id=create();assertThrows(Exception.class,()->db.update("update fg_audit set detail='Changed' where payout_id=?",id));}
    @Test void ledgerCannotBeDeleted(){String id=create();ready(id);service.release(actor("operations"),id,1,UUID.randomUUID().toString());assertThrows(Exception.class,()->db.update("delete from fg_ledger where payout_id=?",id));}
    @Test void anonymousWorkspaceIsDenied() throws Exception{mvc.perform(get("/api/workspace")).andExpect(status().isUnauthorized());}
    @Test void csrfIsRequiredForMutation() throws Exception{mvc.perform(post("/api/payouts").with(user("operations@demo.fundingguard.local")).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
    @Test void accountNumbersAreMasked() throws Exception{String id=create();mvc.perform(get("/api/payouts/"+id).with(user("operations@demo.fundingguard.local"))).andExpect(status().isOk()).andExpect(jsonPath("$.payout.account").value("**** 7890")).andExpect(jsonPath("$.versions[0].account").value("**** 7890"));}
    @Test void unknownRequestFieldsAreRejected() throws Exception{mvc.perform(post("/api/payouts").with(user("operations@demo.fundingguard.local")).with(csrf()).contentType("application/json").content("{\"state\":\"APPROVED\"}")).andExpect(status().isBadRequest());}
    @Test void auditorCannotCreatePayout() throws Exception{mvc.perform(post("/api/payouts").with(user("auditor@demo.fundingguard.local")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());}
    @Test @EnabledIfEnvironmentVariable(named="REAL_POSTGRES",matches="true")
    void simultaneousReleaseRequestsCommitOneEntry() throws Exception{
        String id=create();ready(id);var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            Callable<Boolean> action=()->{start.await();try{service.release(actor("operations"),id,1,UUID.randomUUID().toString());return true;}catch(ApiException e){assertEquals(409,e.status);return false;}};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("select count(*) from fg_ledger where payout_id=?",Integer.class,id));
        }
    }
}
