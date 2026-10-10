package com.ecommerce.user.service;

import com.ecommerce.user.configuration.JwtProperties;
import com.ecommerce.user.configuration.RsaKeyProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

import static com.ecommerce.user.service.CustomerServiceTest.customerWithId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for token creation. No Spring, no database.
 *
 * <p>These pin down the properties everything else depends on: the token carries the right
 * facts, a tampered token is rejected, an expired token is rejected, and - new with RS256 -
 * a token signed by a DIFFERENT key pair is rejected.
 *
 * <p>The key pair is generated into a JUnit temporary directory that is deleted when the
 * class finishes. No test ever touches the real {@code keys/} folder, and nothing is left
 * behind on disk.
 */
class JwtServiceTest {

    private static final String ISSUER = "ecommerce-user-service";

    /**
     * Static, so the directory is created once for the whole class. RsaKeyProvider generates
     * a pair the first time and loads the same one afterwards, so building it repeatedly in
     * these tests costs almost nothing after the first.
     */
    @TempDir
    static Path keyDir;

    private RsaKeyProvider keys;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        keys = new RsaKeyProvider(keyDir.toString());
        jwtService = new JwtService(new JwtProperties(15, ISSUER), keys);
    }

    @Test
    @DisplayName("the token carries the customer id as subject and the role as a claim")
    void issueToken_carriesIdAndRole() {
        UUID id = UUID.randomUUID();

        Claims claims = parse(jwtService.issueToken(customerWithId(id)), keys.publicKey());

        assertThat(claims.getSubject()).isEqualTo(id.toString());
        assertThat(claims.get(JwtService.CLAIM_ROLE)).isEqualTo("CUSTOMER");
        assertThat(claims.getIssuer()).isEqualTo(ISSUER);
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    @DisplayName("the service can read back its own token")
    void parseToken_roundTrip() {
        UUID id = UUID.randomUUID();

        JwtService.TokenPayload payload = jwtService.parseToken(jwtService.issueToken(customerWithId(id)));

        assertThat(payload.userId()).isEqualTo(id);
        assertThat(payload.role()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("the header says RS256, not HS256")
    void tokenIsSignedWithRs256() {
        String token = jwtService.issueToken(customerWithId(UUID.randomUUID()));

        String header = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);

        assertThat(header).contains("RS256");
    }

    @Test
    @DisplayName("the payload is readable without any key - which is why no secret may go in it")
    void payloadIsOnlyEncoded_notEncrypted() {
        String token = jwtService.issueToken(customerWithId(UUID.randomUUID()));

        String payload = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertThat(payload).contains("CUSTOMER");
        // Reading it is fine and unavoidable. What matters is that it cannot be CHANGED.
    }

    @Test
    @DisplayName("a tampered payload fails verification")
    void tamperedToken_isRejected() {
        String token = jwtService.issueToken(customerWithId(UUID.randomUUID()));
        String[] parts = token.split("\\.");

        // Swap the role to ADMIN but keep the original signature
        String forgedPayload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                .replace("CUSTOMER", "ADMIN___");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(forgedPayload.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];

        assertThatThrownBy(() -> parse(forged, keys.publicKey()))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    @DisplayName("a token signed by a DIFFERENT key pair is rejected")
    void tokenFromAnotherKeyPair_isRejected(@TempDir Path otherKeyDir) {
        // A second, unrelated key pair - as if another service tried to mint its own token.
        RsaKeyProvider impostorKeys = new RsaKeyProvider(otherKeyDir.toString());
        JwtService impostor = new JwtService(new JwtProperties(15, ISSUER), impostorKeys);

        String forgedToken = impostor.issueToken(customerWithId(UUID.randomUUID()));

        // Verified against OUR public key, it fails. This is the whole point of RS256:
        // holding a verification key grants no ability to produce an acceptable token.
        assertThatThrownBy(() -> parse(forgedToken, keys.publicKey()))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void expiredToken_isRejected() {
        // A negative expiry issues a token that expired a minute ago
        JwtService expired = new JwtService(new JwtProperties(-1, ISSUER), keys);

        assertThatThrownBy(() -> parse(expired.issueToken(customerWithId(UUID.randomUUID())), keys.publicKey()))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("the key pair is written to disk and reused, not regenerated each startup")
    void keyPairIsPersistedAndReused() {
        assertThat(Files.exists(keyDir.resolve("jwt-private.pem"))).isTrue();
        assertThat(Files.exists(keyDir.resolve("jwt-public.pem"))).isTrue();

        // A fresh provider over the same directory must load the SAME pair. If it generated a
        // new one instead, every restart would silently invalidate every token in circulation.
        RsaKeyProvider reloaded = new RsaKeyProvider(keyDir.toString());

        assertThat(reloaded.publicKey()).isEqualTo(keys.publicKey());
    }

    private static Claims parse(String token, RSAPublicKey publicKey) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
