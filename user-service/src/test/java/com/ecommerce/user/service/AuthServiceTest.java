package com.ecommerce.user.service;

import com.ecommerce.user.configuration.JwtProperties;
import com.ecommerce.user.configuration.RsaKeyProvider;
import com.ecommerce.user.dto.LoginRequest;
import com.ecommerce.user.dto.TokenResponse;
import com.ecommerce.user.entity.Customer;
import com.ecommerce.user.exception.InvalidCredentialsException;
import com.ecommerce.user.exception.TooManyLoginAttemptsException;
import com.ecommerce.user.ratelimit.LoginRateLimiter;
import com.ecommerce.user.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static com.ecommerce.user.service.CustomerServiceTest.customerWithId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private LoginRateLimiter loginRateLimiter;

    private AuthService authService;

    private static final String IP = "203.0.113.7";

    /**
     * Static, so one throwaway key pair is generated for the whole class rather than one per
     * test. RsaKeyProvider loads the existing pair on every call after the first.
     */
    @TempDir
    static Path keyDir;

    @BeforeEach
    void setUp() {
        JwtService jwtService = new JwtService(
                new JwtProperties(15, "issuer"),
                new RsaKeyProvider(keyDir.toString()));
        authService = new AuthService(customerRepository, passwordEncoder, jwtService, loginRateLimiter);
    }

    @Test
    void login_correctPassword_issuesToken() {
        when(customerRepository.findByEmail("jane.doe@example.com"))
                .thenReturn(Optional.of(customerWithId(UUID.randomUUID())));
        when(passwordEncoder.matches("correct-horse-battery", "bcrypt-hash")).thenReturn(true);

        TokenResponse response = authService.login(new LoginRequest("jane.doe@example.com", "correct-horse-battery"), IP);

        assertThat(response.accessToken().split("\\.")).hasSize(3);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    @DisplayName("the email is normalised, so JANE.DOE@EXAMPLE.COM logs in fine")
    void login_normalisesEmail() {
        when(customerRepository.findByEmail("jane.doe@example.com"))
                .thenReturn(Optional.of(customerWithId(UUID.randomUUID())));
        when(passwordEncoder.matches("correct-horse-battery", "bcrypt-hash")).thenReturn(true);

        assertThat(authService.login(new LoginRequest("  JANE.DOE@EXAMPLE.COM  ", "correct-horse-battery"), IP).accessToken())
                .isNotBlank();
    }

    @Test
    @DisplayName("an unknown email and a wrong password give the SAME error - no user enumeration")
    void login_failuresAreIndistinguishable() {
        when(customerRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        Throwable unknownEmail = catchThrowable(
                () -> authService.login(new LoginRequest("nobody@example.com", "whatever"), IP));

        when(customerRepository.findByEmail("jane.doe@example.com"))
                .thenReturn(Optional.of(customerWithId(UUID.randomUUID())));
        when(passwordEncoder.matches("wrong", "bcrypt-hash")).thenReturn(false);
        Throwable wrongPassword = catchThrowable(
                () -> authService.login(new LoginRequest("jane.doe@example.com", "wrong"), IP));

        assertThat(unknownEmail).isInstanceOf(InvalidCredentialsException.class);
        assertThat(wrongPassword).isInstanceOf(InvalidCredentialsException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    @DisplayName("a deactivated customer cannot log in, even with the right password")
    void login_inactiveCustomer_isRefused() {
        Customer inactive = customerWithId(UUID.randomUUID());
        inactive.deactivate();
        when(customerRepository.findByEmail("jane.doe@example.com")).thenReturn(Optional.of(inactive));
        when(passwordEncoder.matches("correct-horse-battery", "bcrypt-hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login(new LoginRequest("jane.doe@example.com", "correct-horse-battery"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    @DisplayName("a rate-limited attempt is refused before the database or BCrypt is touched")
    void login_rateLimited_neverReachesDatabaseOrPasswordCheck() {
        doThrow(new TooManyLoginAttemptsException(42))
                .when(loginRateLimiter).checkAndConsume(anyString(), anyString());

        assertThatThrownBy(() -> authService.login(
                new LoginRequest("jane.doe@example.com", "correct-horse-battery"), IP))
                .isInstanceOf(TooManyLoginAttemptsException.class);

        verifyNoInteractions(customerRepository, passwordEncoder);
    }

    @Test
    @DisplayName("the limiter sees the NORMALISED email, so JANE@ and jane@ share one bucket")
    void login_limiterKeyIsNormalisedEmail() {
        when(customerRepository.findByEmail("jane.doe@example.com"))
                .thenReturn(Optional.of(customerWithId(UUID.randomUUID())));
        when(passwordEncoder.matches("correct-horse-battery", "bcrypt-hash")).thenReturn(true);

        authService.login(new LoginRequest("  JANE.DOE@Example.com ", "correct-horse-battery"), IP);

        verify(loginRateLimiter).checkAndConsume(IP, "jane.doe@example.com");
    }

    @Test
    @DisplayName("a successful login forgives earlier typos for that email")
    void login_success_resetsEmailBucket() {
        when(customerRepository.findByEmail("jane.doe@example.com"))
                .thenReturn(Optional.of(customerWithId(UUID.randomUUID())));
        when(passwordEncoder.matches("correct-horse-battery", "bcrypt-hash")).thenReturn(true);

        authService.login(new LoginRequest("jane.doe@example.com", "correct-horse-battery"), IP);

        verify(loginRateLimiter).recordSuccess("jane.doe@example.com");
    }
}
