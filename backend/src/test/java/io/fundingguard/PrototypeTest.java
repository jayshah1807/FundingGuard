package io.fundingguard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"fundingguard.seed=true", "fundingguard.demo-password=Test-Only-Password-2026!",
    "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://127.0.0.1:55439/postgres?sslmode=disable&preferQueryMode=simple}",
    "spring.datasource.username=${TEST_DATABASE_USER:postgres}", "spring.datasource.password=${TEST_DATABASE_PASSWORD:}",
    "spring.datasource.hikari.maximum-pool-size=${TEST_POOL_SIZE:1}", "spring.flyway.enabled=${TEST_FLYWAY:false}"})
@AutoConfigureMockMvc
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class PrototypeTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    MockHttpSession start() throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/prototype/start").with(csrf())).andExpect(status().isOk()).andReturn().getRequest().getSession();
    }
    @Test void prototypeBoundaryAndRoles() throws Exception {
        mvc.perform(post("/api/prototype/start")).andExpect(status().isForbidden());
        var first=start(); var second=start();
        String tenant=(String)first.getAttribute("prototypeTenant");
        assertNotEquals(tenant,second.getAttribute("prototypeTenant"));
        mvc.perform(get("/api/me").session(first)).andExpect(jsonPath("$.tenant").value(tenant));
        String payout=db.queryForObject("select id from fg_payouts where tenant_id=?",String.class,tenant);
        mvc.perform(get("/api/payouts/"+payout).session(second)).andExpect(status().isNotFound());
        mvc.perform(post("/api/prototype/role").session(first).with(csrf()).contentType("application/json").content("{\"role\":\"ADMIN\"}")).andExpect(status().isUnprocessableEntity());
        for(String role:PrototypeService.ROLES) {
            mvc.perform(post("/api/prototype/role").session(first).with(csrf()).contentType("application/json").content("{\"role\":\""+role+"\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.tenant").value(tenant)).andExpect(jsonPath("$.role").value(role));
        }
        mvc.perform(post("/api/payouts").session(first).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        db.update("update fg_tenants set prototype_actions=250 where id=?",tenant);
        mvc.perform(post("/api/prototype/role").session(first).with(csrf()).contentType("application/json").content("{\"role\":\"OPERATIONS\"}")).andExpect(status().isTooManyRequests());
        db.update("update fg_tenants set prototype_expires_at=now()-interval '1 minute' where id=?",tenant);
        mvc.perform(get("/api/workspace").session(first)).andExpect(status().isUnauthorized());
    }
    @Test void normalUserCannotSwitchOrReplaceSession() throws Exception {
        mvc.perform(post("/api/prototype/role").with(user("operations@demo.fundingguard.local")).with(csrf()).contentType("application/json").content("{\"role\":\"SECURITY\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/prototype/start").with(user("operations@demo.fundingguard.local")).with(csrf())).andExpect(status().isConflict());
    }
}
