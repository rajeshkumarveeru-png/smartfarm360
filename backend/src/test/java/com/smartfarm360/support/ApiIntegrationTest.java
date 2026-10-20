package com.smartfarm360.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.ratelimit.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full-stack API tests: real security, real PostgreSQL, MockMvc. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiIntegrationTest.MailCapture.class)
public abstract class ApiIntegrationTest extends EmbeddedPostgresSupport {
    protected static final String PASSWORD = "correct-horse-battery";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CapturingEmailSender mail;
    @Autowired private RateLimiter rateLimiter;

    @TestConfiguration
    static class MailCapture {
        @Bean @Primary CapturingEmailSender capturingEmailSender() { return new CapturingEmailSender(); }
    }

    @BeforeEach
    void resetRateLimits() { rateLimiter.reset(); }

    public record User(long id, String email, String token) {}

    protected static String uniqueEmail(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com"; }

    protected User newUser(String prefix) throws Exception {
        String email = uniqueEmail(prefix);
        post("/api/v1/auth/register", null, Map.of("email", email, "password", PASSWORD, "displayName", prefix)).andExpect(status().isAccepted());
        return login(email, PASSWORD);
    }

    protected User login(String email, String password) throws Exception {
        JsonNode body = body(post("/api/v1/auth/login", null, Map.of("email", email, "password", password, "refreshTokenDelivery", "BODY"))
                .andExpect(status().isOk()));
        return new User(body.at("/user/id").asLong(), email, body.get("accessToken").asText());
    }

    /** Creates a tenant owned by {@code owner} with the default modules (crop, workforce, assets) and returns its id. */
    protected long newTenant(User owner, String name) throws Exception {
        return body(post("/api/v1/tenants", owner, Map.of("name", name)).andExpect(status().isCreated()))
                .get("id").asLong();
    }

    /** Adds an existing user to a tenant with the given role through the invitation flow. */
    protected long addMember(User inviter, long tenantId, User invitee, String role) throws Exception {
        post("/api/v1/tenants/" + tenantId + "/invitations", inviter, Map.of("email", invitee.email(), "roles", List.of(role)))
                .andExpect(status().isCreated());
        post("/api/v1/invitations/accept", invitee, Map.of("token", mail.lastToken(invitee.email(), "INVITATION"))).andExpect(status().isOk());
        return jdbc.queryForObject("select id from sf_tenant_memberships where tenant_id = ? and user_id = ?", Long.class, tenantId, invitee.id());
    }

    protected long newFarm(User user, long tenantId, String name) throws Exception {
        return body(post("/api/v1/tenants/" + tenantId + "/farms", user, Map.of("name", name)).andExpect(status().isCreated())).get("id").asLong();
    }

    /** System finance category id by code (for example SEEDS, CROP_SALE). */
    protected long categoryId(String code) {
        return jdbc.queryForObject("select id from sf_finance_categories where tenant_id is null and code = ?", Long.class, code);
    }

    /** The tenant's default "Cash in hand" account (created by the finance module on first use). */
    protected long cashAccountId(User user, long tenantId) throws Exception {
        return body(get("/api/v1/tenants/" + tenantId + "/finance/accounts", user)).get(0).get("id").asLong();
    }

    protected ResultActions get(String url, User user) throws Exception { return mvc.perform(auth(MockMvcRequestBuilders.get(url), user)); }
    protected ResultActions post(String url, User user, Object body) throws Exception { return mvc.perform(withBody(auth(MockMvcRequestBuilders.post(url), user), body)); }
    protected ResultActions put(String url, User user, Object body) throws Exception { return mvc.perform(withBody(auth(MockMvcRequestBuilders.put(url), user), body)); }
    protected ResultActions patch(String url, User user, Object body) throws Exception { return mvc.perform(withBody(auth(MockMvcRequestBuilders.patch(url), user), body)); }
    protected ResultActions delete(String url, User user) throws Exception { return mvc.perform(auth(MockMvcRequestBuilders.delete(url), user)); }

    protected JsonNode body(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        String content = result.getResponse().getContentAsString();
        return content.isEmpty() ? json.nullNode() : json.readTree(content);
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder builder, User user) {
        return user == null ? builder : builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token());
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        if (body == null) return builder;
        return builder.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
    }
}
