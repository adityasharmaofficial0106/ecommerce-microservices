package com.ecommerce.product.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code jwt.*} settings.
 *
 * <p>The {@code secret} property is gone. This service no longer holds any key capable of
 * signing a token - it verifies with the public key loaded by {@link PublicKeyProvider},
 * whose path comes from {@code jwt.public-key-path}.
 *
 * @param issuer the value a token must carry in its "iss" claim to be accepted
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String issuer) {
}
