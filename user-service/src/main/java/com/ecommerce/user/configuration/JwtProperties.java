package com.ecommerce.user.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the {@code jwt.*} settings. A typo in application.yml then fails at
 * startup instead of silently leaving a value null until the first login attempt.
 *
 * <p>The {@code secret} property is gone. Tokens are now signed with an RSA private key held
 * by {@link RsaKeyProvider}, which reads its directory from {@code jwt.key-directory}. A key
 * on disk is not a configuration value, so it does not belong in this record.
 *
 * @param expiryMinutes how long an issued token stays valid
 * @param issuer        recorded in the token and checked when it is verified
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(long expiryMinutes, String issuer) {
}
