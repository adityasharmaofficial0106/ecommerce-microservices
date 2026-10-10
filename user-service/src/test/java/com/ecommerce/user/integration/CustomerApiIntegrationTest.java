package com.ecommerce.user.integration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through every layer (HTTP -> controller -> service -> JPA -> PostgreSQL).
 * Few of these, covering the flows that matter most; the unit tests cover the edge cases.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        // Keys go to a throwaway folder under target/, so a test run never reads or
        // overwrites the real keys/ directory at the project root. "mvn clean" removes it.
        "jwt.key-directory=target/test-keys",
        "jwt.expiry-minutes=15"
})
class CustomerApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    /**
     * Logs in and returns the token, so the rest of a test can act as that customer.
     * These tests now go through the real filter chain, exactly as a client would.
     */
    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}""".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    @Test
    void customerLifecycle_registerAddAddressProfileDeactivate() throws Exception {
        String email = uniqueEmail();

        String body = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        String token = tokenFor(email);

        mockMvc.perform(withToken(post("/api/v1/customers/{id}/addresses", id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addressLine1":"100 King St W","city":"Toronto","state":"ON",
                                 "postalCode":"M5X 1A9","country":"CA","addressType":"SHIPPING"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(withToken(get("/api/v1/customers/{id}/profile", id), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.email").value(email))
                .andExpect(jsonPath("$.addresses.length()").value(1));

        mockMvc.perform(withToken(delete("/api/v1/customers/{id}", id), token))
                .andExpect(status().isNoContent());

        // Inactive customers are read-only. The token was issued BEFORE deactivation and is
        // still accepted - a token cannot be un-issued, which is why expiry is kept short.
        mockMvc.perform(withToken(put("/api/v1/customers/{id}", id), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Janet","lastName":"Doe"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(withToken(get("/api/v1/customers/{id}", id), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    @Test
    void registeringTheSameEmailTwice_returns409_evenWithDifferentCase() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON).content(registerJson(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email.toUpperCase())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("listing customers without a token is refused before validation even runs")
    void listCustomers_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers").param("sort", "doesNotExist,asc"))
                // 401, not the 400 this bad sort parameter would otherwise cause:
                // the filter chain stops the request before the controller is reached.
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a customer cannot read another customer's record")
    void customerCannotReadAnother() throws Exception {
        String emailA = uniqueEmail();
        String emailB = uniqueEmail();
        mockMvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(emailA))).andExpect(status().isCreated());
        String bodyB = mockMvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content(registerJson(emailB))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String idB = JsonPath.read(bodyB, "$.id");

        mockMvc.perform(withToken(get("/api/v1/customers/{id}", idB), tokenFor(emailA)))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerThenLogin_returnsAUsableToken() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}""".formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        // header.payload.signature
        String token = JsonPath.read(body, "$.accessToken");
        org.assertj.core.api.Assertions.assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void login_withWrongPassword_returns401() throws Exception {
        String email = uniqueEmail();
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"not-the-right-one"}""".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void login_afterDeactivation_isRefused() throws Exception {
        String email = uniqueEmail();
        String body = mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        // Deactivating is a protected action, so the request needs a token. Without one the
        // filter chain correctly answered 401 and this test failed on its own setup step -
        // the application was right and the test was wrong.
        mockMvc.perform(withToken(delete("/api/v1/customers/{id}", id), tokenFor(email)))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}""".formatted(email)))
                .andExpect(status().isUnauthorized());
    }

    private static String uniqueEmail() {
        return "it-" + UUID.randomUUID() + "@example.com";
    }

    private static String registerJson(String email) {
        return """
                {"firstName":"Jane","lastName":"Doe","email":"%s","password":"correct-horse-battery"}
                """.formatted(email);
    }
}
