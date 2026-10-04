package io.fundingguard;

import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
    final JdbcTemplate db;
    public WorkflowService(JdbcTemplate db) { this.db=db; }
    public record Actor(String id,String tenant,String name,String role,String email) {}
    public Actor actor(String email) {
        var rows=db.queryForList("select * from fg_users where email=? and active=true",email);
        if(rows.isEmpty()) throw new ApiException(401,"This account is unavailable.");
        var u=rows.getFirst();return new Actor((String)u.get("id"),(String)u.get("tenant_id"),(String)u.get("name"),(String)u.get("role"),email);
    }
    void role(Actor a,String... allowed) { if(!Arrays.asList(allowed).contains(a.role())) throw new ApiException(403,"This action requires a different role. Switch to an authorized individual."); }
    void lock(Actor a) { db.queryForObject("select id from fg_tenants where id=? for update",String.class,a.tenant()); }
    public Map<String,Object> payout(Actor a,String id) {
        var rows=db.queryForList("select p.*,c.version as contact_version,c.active as contact_active,c.name as contact_name,c.channel as contact_channel,c.provenance as contact_provenance from fg_payouts p join fg_contacts c on c.id=p.contact_id and c.tenant_id=p.tenant_id where p.id=? and p.tenant_id=?",id,a.tenant());
        if(rows.isEmpty()) throw new ApiException(404,"Payout not found.");return rows.getFirst();
    }
    void current(Map<String,Object> p,int version) {
        if(((Number)p.get("version")).intValue()!=version) throw new ApiException(409,"Instructions changed. Refresh and review the current version.");
        if(List.of("RELEASED_SIMULATED","CLOSED","CANCELLED").contains(p.get("state"))) throw new ApiException(409,"This payout can no longer be changed or released.");
    }
    boolean fresh(Map<String,Object> p,String field,long seconds) {
        return p.get(field) instanceof Timestamp ts && Instant.now().isBefore(ts.toInstant().plusSeconds(seconds));
    }
    public List<String> blockers(Map<String,Object> p) {
        var b=new ArrayList<String>();var version=((Number)p.get("version")).intValue();
        if(!Boolean.TRUE.equals(p.get("contact_active"))) b.add("Trusted contact is inactive");
        boolean verified = Objects.equals(p.get("verified_version"),version) && Objects.equals(p.get("verified_contact_version"),p.get("contact_version")) && fresh(p,"verified_at",86400);
        if(!verified) b.add("Independent verification required");
        boolean approved = Objects.equals(p.get("approved_version"),version) && Objects.equals(p.get("approved_contact_version"),p.get("contact_version")) && fresh(p,"approved_at",1800);
        if(!approved) b.add("Current approval required");
        if(Boolean.TRUE.equals(p.get("hold_active"))) b.add("Security hold is active");
        if(!"APPROVED".equals(p.get("state"))) b.add("Payout is not ready for release");
        if(Objects.equals(p.get("maker_id"),p.get("verified_by")) || Objects.equals(p.get("maker_id"),p.get("approved_by")) || (p.get("verified_by")!=null && Objects.equals(p.get("verified_by"),p.get("approved_by")))) b.add("Independent reviewers required");
        for(String key:List.of("verified_by","approved_by")) if(p.get(key)!=null && db.queryForObject("select count(*) from fg_users where id=? and tenant_id=? and role=? and active=true",Integer.class,p.get(key),p.get("tenant_id"),key.equals("verified_by")?"VERIFIER":"APPROVER")==0) b.add("Reviewer is no longer authorized");
        return b;
    }
    void audit(Actor a,String id,String action,String detail) { db.update("insert into fg_audit(tenant_id,payout_id,actor_id,actor_name,action,detail) values(?,?,?,?,?,?)",a.tenant(),id,a.id(),a.name(),action,detail); }
    void event(Actor a,String id,String kind) { db.update("insert into fg_outbox(tenant_id,payout_id,kind) values(?,?,?)",a.tenant(),id,kind); }
    void caseFor(Actor a,String id,String rule,String title,String detail) { db.update("insert into fg_cases(id,tenant_id,payout_id,rule_code,severity,title,detail) values(?,?,?,?,?,?,?)","INV-"+UUID.randomUUID().toString().substring(0,8),a.tenant(),id,rule,"HIGH",title,detail); }
    String text(String s,int min,int max,String label) { if(s==null||s.trim().length()<min||s.length()>max||s.matches("(?s).*[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F].*")) throw new ApiException(422,label+" is invalid.");return s.trim(); }
    public record Create(String borrower,String property,String loanReference,String contactId,long amountMinor,String account,String deadline) {}
    @Transactional public String create(Actor a,Create r) {
        role(a,"OPERATIONS");lock(a);
        String borrower=text(r.borrower(),2,100,"Borrower"),property=text(r.property(),5,200,"Property"),loan=text(r.loanReference(),3,80,"Loan reference"),account=account(r.account());amount(r.amountMinor());
        if(db.queryForObject("select count(*) from fg_contacts where id=? and tenant_id=? and active=true",Integer.class,r.contactId(),a.tenant())!=1) throw new ApiException(422,"Select an active trusted contact.");
        if(db.queryForObject("select count(*) from fg_payouts where tenant_id=? and loan_reference=?",Integer.class,a.tenant(),loan)>0) throw new ApiException(409,"An obligation for this loan already exists.");
        LocalDate deadline;try{deadline=LocalDate.parse(r.deadline());}catch(Exception ex){throw new ApiException(422,"Enter a valid funding date.");}
        var lender=db.queryForObject("select institution from fg_contacts where id=?",String.class,r.contactId());String id="FG-"+UUID.randomUUID().toString().substring(0,8).toUpperCase();
        db.update("insert into fg_payouts(id,tenant_id,borrower,property,lender,loan_reference,contact_id,amount_minor,account,maker_id,deadline) values(?,?,?,?,?,?,?,?,?,?,?)",id,a.tenant(),borrower,property,lender,loan,r.contactId(),r.amountMinor(),account,a.id(),java.sql.Date.valueOf(deadline));
        db.update("insert into fg_versions(payout_id,version,amount_minor,account,maker_id,reason) values(?,1,?,?,?,?)",id,r.amountMinor(),account,a.id(),"Initial payout instructions");audit(a,id,"INSTRUCTIONS_SUBMITTED","Version 1 created; independent verification required.");event(a,id,"PAYOUT_CREATED");return id;
    }
    String account(String s){ if(s==null||!s.matches("[0-9]{6,18}")) throw new ApiException(422,"Use a synthetic account of 6 to 18 digits.");return s; }
    void amount(long n){if(n<1||n>100000000)throw new ApiException(422,"Amount must be between CAD 0.01 and CAD 1,000,000.00.");}
    @Transactional public void revise(Actor a,String id,int v,long amount,String account,String reason){
        role(a,"OPERATIONS");lock(a);var p=payout(a,id);current(p,v);amount(amount);account=account(account);reason=text(reason,8,500,"Revision reason");
        if(amount==((Number)p.get("amount_minor")).longValue()&&account.equals(p.get("account")))throw new ApiException(422,"Change the amount or destination before submitting a revision.");
        int next=v+1;db.update("update fg_payouts set clearance_by=null,clearance_reason=null where id=?",id);db.update("insert into fg_versions(payout_id,version,amount_minor,account,maker_id,reason) values(?,?,?,?,?,?)",id,next,amount,account,a.id(),reason);
        db.update("update fg_payouts set version=?,amount_minor=?,account=?,maker_id=?,state='REVIEW_REQUIRED',verified_by=null,verified_at=null,verified_version=null,approved_by=null,approved_at=null,approved_version=null,updated_at=now() where id=?",next,amount,account,a.id(),id);
        audit(a,id,"INSTRUCTIONS_REVISED","Version "+next+" replaces v"+v+". Prior verification and approval invalidated. "+reason);
        caseFor(a,id,"DR-01","Payment instructions changed","A new instruction version requires independent verification. This is a review signal, not proof of fraud.");event(a,id,"INSTRUCTIONS_REVISED");
    }
    @Transactional public void verify(Actor a,String id,int v,String evidence){
        role(a,"VERIFIER");lock(a);var p=payout(a,id);current(p,v);evidence=text(evidence,12,600,"Verification evidence");
        if(a.id().equals(p.get("maker_id")))throw new ApiException(403,"The preparer cannot verify their own instructions.");
        if(!Boolean.TRUE.equals(p.get("contact_active")))throw new ApiException(409,"The trusted contact is inactive.");
        db.update("insert into fg_verifications(payout_id,instruction_version,contact_version,actor_id,evidence) values(?,?,?,?,?)",id,v,p.get("contact_version"),a.id(),evidence);
        db.update("update fg_payouts set verified_by=?,verified_at=now(),verified_version=?,verified_contact_version=?,approved_by=null,approved_at=null,approved_version=null,state='REVIEW_REQUIRED',updated_at=now() where id=?",a.id(),v,p.get("contact_version"),id);audit(a,id,"VERIFICATION_RECORDED",evidence);event(a,id,"VERIFICATION_RECORDED");
    }
    @Transactional public void approve(Actor a,String id,int v){
        role(a,"APPROVER");lock(a);var p=payout(a,id);current(p,v);
        if(a.id().equals(p.get("maker_id"))||a.id().equals(p.get("verified_by")))throw new ApiException(403,"Approval requires a person independent of preparation and verification.");
        if(!Objects.equals(p.get("verified_version"),v)||!fresh(p,"verified_at",86400)||!Objects.equals(p.get("verified_contact_version"),p.get("contact_version"))||!Boolean.TRUE.equals(p.get("contact_active"))) throw new ApiException(409,"Fresh independent verification is required.");
        if(Boolean.TRUE.equals(p.get("hold_active")))throw new ApiException(409,"Clear the security hold before approval.");
        db.update("insert into fg_approvals(payout_id,instruction_version,contact_version,actor_id) values(?,?,?,?)",id,v,p.get("contact_version"),a.id());
        db.update("update fg_payouts set approved_by=?,approved_at=now(),approved_version=?,approved_contact_version=?,state='APPROVED',updated_at=now() where id=?",a.id(),v,p.get("contact_version"),id);audit(a,id,"PAYOUT_APPROVED","Approved exact instruction v"+v+". Expires in 30 minutes.");event(a,id,"PAYOUT_APPROVED");
    }
    @Transactional public Map<String,Object> release(Actor a,String id,int v,String key){
        role(a,"OPERATIONS");lock(a);var p=payout(a,id);text(key,8,100,"Idempotency key");String hash=id+":"+v;
        var existing=db.queryForList("select * from fg_idempotency where tenant_id=? and request_key=?",a.tenant(),key);
        if(!existing.isEmpty()){var e=existing.getFirst();if(!hash.equals(e.get("request_hash"))||!a.id().equals(e.get("actor_id")))throw new ApiException(409,"This request key belongs to a different release request.");return Map.of("ledgerId",e.get("ledger_id"),"replayed",true);}
        current(p,v);var blockers=blockers(p);if(!blockers.isEmpty())throw new ApiException(409,String.join(". ",blockers));
        String ledger="SIM-"+UUID.randomUUID().toString().substring(0,12).toUpperCase();
        db.update("insert into fg_ledger(id,tenant_id,payout_id,amount_minor,account,instruction_version,actor_id) values(?,?,?,?,?,?,?)",ledger,a.tenant(),id,p.get("amount_minor"),p.get("account"),v,a.id());
        db.update("insert into fg_idempotency(tenant_id,request_key,actor_id,payout_id,request_hash,ledger_id) values(?,?,?,?,?,?)",a.tenant(),key,a.id(),id,hash,ledger);
        db.update("update fg_payouts set state='RELEASED_SIMULATED',updated_at=now() where id=?",id);audit(a,id,"PAYOUT_RELEASED","Simulated ledger "+ledger+" committed for instruction v"+v+". No real funds moved.");event(a,id,"PAYOUT_RELEASED");return Map.of("ledgerId",ledger,"replayed",false);
    }
    @Transactional public void hold(Actor a,String id,int v,String reason){
        role(a,"SECURITY");lock(a);var p=payout(a,id);current(p,v);reason=text(reason,10,600,"Hold reason");
        if(Boolean.TRUE.equals(p.get("hold_active")))throw new ApiException(409,"A hold is already active.");
        db.update("update fg_payouts set hold_active=true,hold_reason=?,clearance_by=null,clearance_reason=null,updated_at=now() where id=?",reason,id);audit(a,id,"HOLD_PLACED",reason);caseFor(a,id,"MANUAL","Security hold requires review",reason);event(a,id,"HOLD_PLACED");
    }
    @Transactional public void clear(Actor a,String id,int v,String reason){
        role(a,"SECURITY","APPROVER");lock(a);var p=payout(a,id);current(p,v);reason=text(reason,10,600,"Clearance reason");
        if(!Boolean.TRUE.equals(p.get("hold_active")))throw new ApiException(409,"No active hold exists.");
        if(a.role().equals("SECURITY")){db.update("update fg_payouts set clearance_by=?,clearance_reason=? where id=?",a.id(),reason,id);audit(a,id,"CLEARANCE_PROPOSED",reason);}
        else {if(p.get("clearance_by")==null||a.id().equals(p.get("clearance_by"))||a.id().equals(p.get("maker_id")))throw new ApiException(409,"A different security analyst must first propose clearance.");
            db.update("update fg_payouts set hold_active=false,clearance_by=null,clearance_reason=null,approved_at=null,approved_by=null,approved_version=null,state='REVIEW_REQUIRED',updated_at=now() where id=?",id);audit(a,id,"HOLD_CLEARED",reason+" Fresh approval is required.");}
    }
    @Transactional public void confirm(Actor a,String id,String type,String reason){
        role(a,"OPERATIONS");lock(a);var p=payout(a,id);text(reason,10,600,"Evidence reference");
        if(!"RELEASED_SIMULATED".equals(p.get("state")))throw new ApiException(409,"Only a released simulated payout can be confirmed.");
        if(!List.of("receipt","discharge").contains(type))throw new ApiException(422,"Unknown confirmation type.");
        if(Boolean.TRUE.equals(p.get(type)))throw new ApiException(409,"This confirmation has already been recorded.");
        if(type.equals("discharge")&&!Boolean.TRUE.equals(p.get("receipt")))throw new ApiException(409,"Record recipient receipt first.");
        db.update(type.equals("receipt")?"update fg_payouts set receipt=true,updated_at=now() where id=?":"update fg_payouts set discharge=true,updated_at=now() where id=?",id);audit(a,id,type.toUpperCase()+"_CONFIRMED","Simulated evidence: "+reason);
    }
    @Transactional public void resolve(Actor a,String caseId,String reason){
        role(a,"SECURITY");lock(a);reason=text(reason,12,600,"Resolution");
        var rows=db.queryForList("select * from fg_cases where id=? and tenant_id=?",caseId,a.tenant());if(rows.isEmpty())throw new ApiException(404,"Case not found.");
        if("RESOLVED".equals(rows.getFirst().get("state")))throw new ApiException(409,"Case is already resolved.");
        db.update("update fg_cases set state='RESOLVED',resolution=? where id=?",reason,caseId);audit(a,(String)rows.getFirst().get("payout_id"),"CASE_RESOLVED",reason+" Holds are not cleared by case resolution.");
    }
}
