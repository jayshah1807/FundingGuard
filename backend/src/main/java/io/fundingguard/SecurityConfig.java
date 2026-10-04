package io.fundingguard;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService users(JdbcTemplate db) {
        return email -> {
            var users = db.queryForList("select * from fg_users where email=? and active=true", email);
            if(users.isEmpty()) throw new UsernameNotFoundException("Invalid credentials");
            var u=users.getFirst();
            return User.withUsername(email).password((String)u.get("password_hash")).roles((String)u.get("role")).build();
        };
    }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        var csrf = new HttpSessionCsrfTokenRepository();
        csrf.setHeaderName("X-CSRF-TOKEN");
        http.csrf(c -> c.csrfTokenRepository(csrf))
            .authorizeHttpRequests(a -> a.requestMatchers("/api/csrf", "/api/login", "/", "/index.html", "/*.js", "/*.css", "/*.svg", "/favicon.ico").permitAll().anyRequest().authenticated())
            .formLogin(f -> f.loginProcessingUrl("/api/login").successHandler((q,r,a) -> {r.setContentType("application/json");r.getWriter().write("{\"ok\":true}");}).failureHandler((q,r,e) -> {r.setStatus(401);r.setContentType("application/json");r.getWriter().write("{\"message\":\"Email or password is incorrect.\"}");}))
            .logout(l -> l.logoutUrl("/api/logout").logoutSuccessHandler((q,r,a) -> r.setStatus(204)))
            .exceptionHandling(e -> e.authenticationEntryPoint((q,r,x) -> {r.setStatus(401);r.setContentType("application/json");r.getWriter().write("{\"message\":\"Please sign in.\"}");}).accessDeniedHandler((q,r,x) -> {r.setStatus(403);r.setContentType("application/json");r.getWriter().write("{\"message\":\"Access denied or session token expired. Refresh and try again.\"}");}))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")));
        return http.build();
    }
}
