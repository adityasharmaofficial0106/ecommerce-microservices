package com.ecommerce.product.configuration;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * Verifies tokens. This service NEVER creates one - there is no issue method here at all,
 * which is the point: this service has no business minting identities.
 *
 * <p>Verification is entirely local: signature, expiry and issuer are all checked against bytes
 * this service already holds. No database query, no call to user-service. That is exactly why
 * a token beats a session across several services - this service can authenticate a caller
 * while user-service is switched off.
 *
 * <h2>Why the key is now public</h2>
 *
 * <p>Under HS256 this class held the same secret user-service did, because with a shared secret
 * the key that CHECKS a signature is the key that CREATES one. "There is no issue method here"
 * was therefore a convention, not a guarantee - anyone who compromised this service could write
 * the missing method themselves and mint a token claiming any customer id with the ADMIN role.
 *
 * <p>With RS256 the guarantee is real. This service holds only the public key. Producing a valid
 * signature from it is not a thing the code declines to do; it is a thing the mathematics does
 * not permit. A break-in here now costs you the catalogue, not the platform.
 */
@Service
public class JwtService {

    public static final String CLAIM_ROLE = "role";

    private final JwtProperties properties;

    /** Verifies only. There is no private key anywhere in this service. */
    private final RSAPublicKey publicKey;

    public JwtService(JwtProperties properties, PublicKeyProvider publicKeyProvider) {
        this.properties = properties;
        this.publicKey = publicKeyProvider.publicKey();
    }

    /**
     * @throws io.jsonwebtoken.JwtException if the signature is wrong, the token has expired,
     *                                      the issuer does not match, or it is malformed
     */
    public TokenPayload parseToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(publicKey)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new TokenPayload(UUID.fromString(claims.getSubject()), claims.get(CLAIM_ROLE, String.class));
    }

    public record TokenPayload(UUID userId, String role) {
    }
}
