package io.fundingguard;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class PrototypeLimits implements WebMvcConfigurer {
    final JdbcTemplate db;
    public PrototypeLimits(JdbcTemplate db) { this.db=db; }
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getUserPrincipal()==null) return true;
                var rows=db.queryForList("select t.id,t.prototype_expires_at<=now() as expired from fg_tenants t join fg_users u on u.tenant_id=t.id where u.email=? and t.prototype_expires_at is not null", request.getUserPrincipal().getName());
                if (rows.isEmpty()) return true;
                int status=0;
                if (Boolean.TRUE.equals(rows.getFirst().get("expired"))) status=401;
                else if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD") && db.update("update fg_tenants set prototype_actions=prototype_actions+1 where id=? and prototype_actions<250", rows.getFirst().get("id"))!=1) status=429;
                if (status==0) return true;
                if (status==401 && request.getSession(false)!=null) request.getSession(false).invalidate();
                response.setStatus(status); response.setContentType("application/json");
                response.getWriter().write("{\"message\":\"Prototype session expired or action limit reached. Sign out to finish this session.\"}");
                return false;
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/csrf", "/api/logout");
    }
}
