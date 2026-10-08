package io.fundingguard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prototype")
public class PrototypeController {
    final PrototypeService prototypes;
    final WorkflowService workflow;
    public PrototypeController(PrototypeService prototypes, WorkflowService workflow) { this.prototypes=prototypes; this.workflow=workflow; }
    @PostMapping("/start")
    public WorkflowService.Actor start(Principal principal, HttpServletRequest request, HttpServletResponse response) {
        synchronized(request.getSession()) {
            if (principal != null) throw new ApiException(409, "Sign out before starting another prototype.");
            var actor = prototypes.create();
            request.getSession().setAttribute("prototypeTenant", actor.tenant());
            authenticate(actor, request, response);
            return actor;
        }
    }
    public record Role(String role) {}
    @PostMapping("/role")
    public WorkflowService.Actor role(Principal principal, @RequestBody Role body, HttpServletRequest request, HttpServletResponse response) {
        var current = workflow.actor(principal.getName());
        if (!current.tenant().equals(request.getSession().getAttribute("prototypeTenant")))
            throw new ApiException(403, "Role simulation is available only in your visitor prototype.");
        if (body.role()==null || !PrototypeService.ROLES.contains(body.role())) throw new ApiException(422, "Select a supported demo role.");
        var next = workflow.actor(PrototypeService.email(current.tenant(),body.role()));
        authenticate(next,request,response);
        return next;
    }
    private void authenticate(WorkflowService.Actor actor, HttpServletRequest request, HttpServletResponse response) {
        request.changeSessionId();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor.email(), null, AuthorityUtils.createAuthorityList("ROLE_"+actor.role())));
        SecurityContextHolder.setContext(context);
        new HttpSessionSecurityContextRepository().saveContext(context,request,response);
        new HttpSessionCsrfTokenRepository().saveToken(null,request,response);
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> failure(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("message",e.getMessage())); }
}
