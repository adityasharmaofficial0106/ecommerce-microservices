package com.ecommerce.user.controller;

import com.ecommerce.user.configuration.JwtAuthenticationFilter;
import com.ecommerce.user.configuration.SecurityConfig;
import com.ecommerce.user.configuration.SecurityProblemHandlers;
import com.ecommerce.user.dto.CustomerResponse;
import com.ecommerce.user.entity.CustomerStatus;
import com.ecommerce.user.service.AuthService;
import com.ecommerce.user.service.CustomerService;
import com.ecommerce.user.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization rules themselves, tested as behaviour rather than trusted as annotations.
 *
 * <p>Security rules are exactly the kind of code that looks right and is wrong: an annotation
 * with a typo in the expression silently allows everything. These tests would catch that.
 *
 * <p>Note that BOTH controllers are loaded into the slice. A @WebMvcTest builds a cut-down
 * Spring context containing only the controllers it is told about, so with CustomerController
 * alone the login URL did not exist and the "login is public" test received 404 instead of the
 * 400 it expected. A 404 would also be returned if the endpoint were deleted entirely, so the
 * test was incapable of failing for the reason it was written to catch.
 */
@WebMvcTest({CustomerController.class, AuthController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityProblemHandlers.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;
    @MockitoBean
    private JwtService jwtService;
    /** AuthController needs this bean to exist; the login test never reaches it. */
    @MockitoBean
    private AuthService authService;

    private final UUID jane = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    // ---------- no token at all ----------

    @Test
    @DisplayName("without a token a protected endpoint returns 401 in problem JSON")
    void noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", jane))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Authentication required"));

        // The controller never ran - the request was stopped in the filter chain
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("login stays public: you cannot require a token to obtain a token")
    void loginIsPublic() throws Exception {
        // 400 (bad body), NOT 401 - proving the path itself is reachable without a token.
        // An empty JSON object fails bean validation in the controller, which is only
        // possible if the filter chain let the request through in the first place.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // And the request really did reach validation rather than the service
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("registration stays public: a new customer has no account yet")
    void registrationIsPublic() throws Exception {
        when(customerService.register(any())).thenReturn(response(jane));

        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Jane","lastName":"Doe","email":"jane@example.com",
                                 "password":"correct-horse-battery"}
                                """))
                .andExpect(status().isCreated());
    }

    // ---------- customer reading their own data ----------

    @Test
    @DisplayName("a customer may read their own record")
    void customerReadsOwnRecord() throws Exception {
        when(customerService.getCustomer(jane)).thenReturn(response(jane));

        mockMvc.perform(get("/api/v1/customers/{id}", jane).with(authentication(customer(jane))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a customer may NOT read someone else's record - this is the IDOR guard")
    void customerCannotReadAnotherCustomer() throws Exception {
        mockMvc.perform(get("/api/v1/customers/{id}", bob).with(authentication(customer(jane))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Access denied"));

        // 403, not 500: the @PreAuthorize failure must not fall through to the catch-all handler
        verifyNoInteractions(customerService);
    }

    @Test
    @DisplayName("a customer may not list every customer")
    void customerCannotListEveryone() throws Exception {
        mockMvc.perform(get("/api/v1/customers").with(authentication(customer(jane))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(customerService);
    }

    // ---------- admin ----------

    @Test
    @DisplayName("an admin may list every customer")
    void adminListsEveryone() throws Exception {
        when(customerService.listCustomers(any(), any()))
                .thenReturn(new com.ecommerce.user.dto.PageResponse<>(
                        java.util.List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/v1/customers").with(authentication(admin(UUID.randomUUID()))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("an admin may read any customer's record")
    void adminReadsAnyRecord() throws Exception {
        when(customerService.getCustomer(bob)).thenReturn(response(bob));

        mockMvc.perform(get("/api/v1/customers/{id}", bob).with(authentication(admin(jane))))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    /**
     * Builds the same Authentication our JWT filter would create: the principal is the
     * caller's UUID and the authority carries the ROLE_ prefix Spring Security expects.
     */
    private static org.springframework.security.core.Authentication customer(UUID id) {
        return authFor(id, "ROLE_CUSTOMER");
    }

    private static org.springframework.security.core.Authentication admin(UUID id) {
        return authFor(id, "ROLE_ADMIN");
    }

    private static org.springframework.security.core.Authentication authFor(UUID id, String authority) {
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                id, null,
                java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(authority)));
    }

    private static CustomerResponse response(UUID id) {
        return new CustomerResponse(id, "Jane", "Doe", "jane@example.com", null,
                CustomerStatus.ACTIVE, Instant.now(), Instant.now());
    }
}
