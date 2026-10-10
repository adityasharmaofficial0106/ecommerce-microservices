package com.ecommerce.user.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Set;

/**
 * Owns the RSA key pair that signs every access token this platform issues.
 *
 * <p>user-service is the only service that holds a private key. The other services
 * receive the public half, which can verify a signature but cannot produce one. That
 * asymmetry is the entire point of moving off HS256: with a shared secret, any service
 * able to check a token was also able to forge one.
 *
 * <p>On startup this class looks for an existing pair on disk and loads it. If none is
 * found it generates one and writes it out. Generating on first run means the private
 * key never has to be created by hand, pasted into a chat, or emailed to anyone.
 *
 * <p><b>The private key must never be committed.</b> Anyone holding jwt-private.pem can
 * mint a token claiming any customer id and the ADMIN role, and every service on the
 * platform will accept it as genuine. {@code keys/} and {@code *.pem} are in .gitignore
 * for that reason.
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private static final String PRIVATE_KEY_FILE = "jwt-private.pem";
    private static final String PUBLIC_KEY_FILE = "jwt-public.pem";

    /**
     * 2048 bits is the current floor for RSA. Below that is considered breakable;
     * 4096 is slower to sign with and buys little for a 15-minute token.
     */
    private static final int KEY_SIZE = 2048;

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    /**
     * The key directory is read with {@code @Value} rather than through JwtProperties
     * because it is bootstrap configuration: the keys must be on disk before anything
     * that depends on them is wired. The default lets every service start with no
     * configuration change at all.
     */
    public RsaKeyProvider(@Value("${jwt.key-directory:../keys}") String keyDirectory) {
        Path directory = Path.of(keyDirectory).toAbsolutePath().normalize();
        Path privatePath = directory.resolve(PRIVATE_KEY_FILE);
        Path publicPath = directory.resolve(PUBLIC_KEY_FILE);

        try {
            if (Files.exists(privatePath) && Files.exists(publicPath)) {
                this.privateKey = readPrivateKey(privatePath);
                this.publicKey = readPublicKey(publicPath);
                log.info("Loaded existing RSA key pair from {}", directory);
            } else {
                KeyPair pair = generateKeyPair();
                this.privateKey = (RSAPrivateKey) pair.getPrivate();
                this.publicKey = (RSAPublicKey) pair.getPublic();

                Files.createDirectories(directory);
                writePem(privatePath, "PRIVATE KEY", this.privateKey.getEncoded());
                writePem(publicPath, "PUBLIC KEY", this.publicKey.getEncoded());
                restrictToOwner(privatePath);

                log.warn("No RSA key pair found - generated a new {}-bit pair in {}", KEY_SIZE, directory);
                log.warn("Every token issued before now is no longer verifiable. Clients must log in again.");
                log.warn("Copy {} to the other services. NEVER copy or commit {}",
                        PUBLIC_KEY_FILE, PRIVATE_KEY_FILE);
            }
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Failing to start is the correct response. A service that cannot sign
            // tokens cannot authenticate anyone, and starting anyway would mean
            // every login failing at runtime with a far less obvious error.
            throw new IllegalStateException(
                    "Could not load or create the RSA key pair in " + directory, e);
        }
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    // -----------------------------------------------------------------
    //  Key generation and PEM handling
    // -----------------------------------------------------------------

    private static KeyPair generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        return generator.generateKeyPair();
    }

    /**
     * Reads a PKCS#8 private key. That is the format {@code getEncoded()} produces for
     * a private key, and what a PEM file labelled "BEGIN PRIVATE KEY" contains.
     */
    static RSAPrivateKey readPrivateKey(Path path)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = decodePem(Files.readString(path));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    /**
     * Reads an X.509 public key — the counterpart format, labelled "BEGIN PUBLIC KEY".
     * Using the wrong spec for the wrong half is the most common mistake here and
     * produces an InvalidKeySpecException rather than anything descriptive.
     */
    static RSAPublicKey readPublicKey(Path path)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] der = decodePem(Files.readString(path));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(der));
    }

    /**
     * A PEM file is just Base64-wrapped binary with a header and footer line.
     * Strip those and all whitespace, and what remains decodes to DER bytes.
     */
    static byte[] decodePem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static void writePem(Path path, String label, byte[] der) throws IOException {
        // MIME encoder wraps at 64 characters, which is what the PEM convention expects.
        String body = Base64.getMimeEncoder(64, System.lineSeparator().getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + label + "-----" + System.lineSeparator()
                + body + System.lineSeparator()
                + "-----END " + label + "-----" + System.lineSeparator();
        Files.writeString(path, pem);
    }

    /**
     * Best-effort file permissions. On Linux and macOS this makes the private key
     * readable only by its owner — which matters in a container where other processes
     * may share the filesystem. Windows has no POSIX permissions, so this is skipped
     * there rather than treated as a failure.
     */
    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            log.debug("Could not restrict permissions on {} (normal on Windows)", path);
        }
    }
}
