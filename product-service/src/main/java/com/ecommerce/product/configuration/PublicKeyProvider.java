package com.ecommerce.product.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the public half of the platform's signing key.
 *
 * <p>This service can <b>verify</b> that a token was signed by user-service. It cannot
 * sign one. There is no code path here that could produce a valid token, because the
 * private key simply is not present — that is a mathematical guarantee, not a policy
 * this class enforces.
 *
 * <p>Compare with the HS256 arrangement it replaces: this service held the same secret
 * user-service did, and could have minted {@code {"sub": "<anyone>", "role": "ADMIN"}}
 * at will. If this service were ever compromised, the attacker got the whole platform.
 * Now they get this service.
 *
 * <p>The public key is not a secret. It can live in a config file, a git repository, or
 * a public HTTP endpoint without weakening anything. Production systems usually publish
 * it at {@code /.well-known/jwks.json} so verifiers fetch it rather than keeping a copy;
 * reading it from a file is the simpler first step.
 *
 * <p>The decode helpers are duplicated from user-service rather than extracted into a
 * shared module. That is deliberate. A shared library couples the deploy cycles of all
 * three services together, and twenty lines of Base64 handling is a cheaper price than
 * that coupling.
 */
@Component
public class PublicKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(PublicKeyProvider.class);

    private final RSAPublicKey publicKey;

    public PublicKeyProvider(
            @Value("${jwt.public-key-path:../keys/jwt-public.pem}") String publicKeyPath) {

        Path path = Path.of(publicKeyPath).toAbsolutePath().normalize();

        if (!Files.exists(path)) {
            // Refusing to start is correct. Without the public key this service cannot
            // verify any token, so every authenticated request would fail. Failing now,
            // with a message naming the missing file, beats failing later on every call.
            throw new IllegalStateException(
                    "JWT public key not found at " + path
                    + ". Start user-service first - it generates the key pair on its first run.");
        }

        try {
            byte[] der = decodePem(Files.readString(path));
            KeyFactory factory = KeyFactory.getInstance("RSA");
            this.publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
            log.info("Loaded JWT public key from {} (verify only - this service cannot sign tokens)", path);
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Could not read the JWT public key at " + path, e);
        }
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    static byte[] decodePem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
