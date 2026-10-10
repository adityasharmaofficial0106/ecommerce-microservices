package com.ecommerce.user.service;

import com.ecommerce.user.configuration.JwtProperties;
import com.ecommerce.user.configuration.RsaKeyProvider;
import com.ecommerce.user.entity.Customer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Builds signed JSON Web Tokens.
 *
 * <p>A JWT has three dot-separated parts: header, payload and signature. The payload is only
 * BASE64-ENCODED, never encrypted - anyone holding the token can read it. What they cannot do
 * is CHANGE it: altering one character breaks the signature, and producing a valid replacement
 * requires the signing key.
 *
 * <p>The rule that follows: never put anything secret in a token. Ids, roles and timestamps are
 * fine. Passwords, card numbers and personal details are not.
 *
 * <h2>Why RS256 and not HS256</h2>
 *
 * <p>HS256 signs with a shared secret, and the same secret both creates and checks a signature.
 * That meant product-service and order-service, which only ever need to CHECK tokens, also held
 * everything required to MINT one - including a token claiming any customer id with the ADMIN
 * role. A break-in at any single service was a break-in at all three.
 *
 * <p>RS256 splits the key in two. This service holds the private half and is the only thing on
 * the platform that can issue a token. The other services hold the public half, which verifies
 * signatures and is mathematically incapable of producing them. That is not a rule anyone
 * enforces; it is a property of the mathematics.
 */
@Service
public class JwtService {

    /** Our own claim for the caller's role. "sub" and "exp" are standard; this one is not. */
    public static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;

    /** Signs. Exists only in this service, and never leaves it. */
    private final RSAPrivateKey privateKey;

    /** Verifies. This service checks its own tokens too, e.g. on an authenticated request. */
    private final RSAPublicKey publicKey;

    public JwtService(JwtProperties properties, RsaKeyProvider keyProvider) {
        this.properties = properties;
        this.privateKey = keyProvider.privateKey();
        this.publicKey = keyProvider.publicKey();
        // No key-length check is needed here any more. RsaKeyProvider either loads a valid
        // pair or generates a 2048-bit one, and fails startup if it can do neither.
    }

    /**
     * Issues a token for a customer.
     *
     * <p>The subject is the customer's UUID rather than their email. Emails change; the id does
     * not, and it is the id that order-service and product-service already know this person by.
     */
    public String issueToken(Customer customer) {
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofMinutes(properties.expiryMinutes()));

        return Jwts.builder()
                .subject(customer.getId().toString())
                .claim(CLAIM_ROLE, customer.getRole().name())
                .issuer(properties.issuer())
                // A unique id per token. Unused today, but it is what a revocation denylist
                // would key on once Redis arrives.
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                // The algorithm is now stated explicitly. With a shared secret jjwt could infer
                // it from the key type; with a key pair, saying RS256 out loud is clearer and
                // guards against an accidental downgrade.
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    public long expirySeconds() {
        return Duration.ofMinutes(properties.expiryMinutes()).toSeconds();
    }

    /**
     * Verifies a token and returns what it says.
     *
     * <p>This is a purely LOCAL operation: signature check, expiry check, issuer check, all
     * arithmetic on bytes we already hold. No database query, no call to another service. That
     * is the whole reason a token beats a session in a multi-service system.
     *
     * @throws io.jsonwebtoken.JwtException if the signature is wrong, the token has expired,
     *                                      the issuer does not match, or it is malformed
     */
    public TokenPayload parseToken(String token) {
        Claims claims = Jwts.parser()
                // Verification uses the PUBLIC key, even here. Nothing in the checking path
                // needs the private half.
                .verifyWith(publicKey)
                // Reject a token minted by something else, even if it were somehow signed
                // with our key. Checking the issuer costs nothing and closes a door.
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new TokenPayload(
                UUID.fromString(claims.getSubject()),
                claims.get(CLAIM_ROLE, String.class));
    }

    /**
     * The facts carried by a verified token.
     *
     * @param userId the caller's customer id, taken from the standard "sub" claim
     * @param role   the caller's role, as a plain string - the filter turns it into an authority
     */
    public record TokenPayload(UUID userId, String role) {
    }
}
