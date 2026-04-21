package org.openphc.cce.emitter.openhim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.openphc.cce.emitter.config.OpenHimProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Intercepts outbound HTTP requests to OpenHIM Core API and applies the
 * standard OpenHIM token-based (custom) authentication.
 *
 * <p>OpenHIM's token auth flow:
 * <ol>
 *   <li>{@code GET /authenticate/<email>} → returns {@code {salt, ts}}</li>
 *   <li>Compute {@code passwordHash = SHA-512(serverSalt + password)}</li>
 *   <li>Generate a random client {@code authSalt}</li>
 *   <li>Compute {@code authToken = SHA-512(passwordHash + authSalt + ts)}</li>
 *   <li>Send headers: {@code auth-username}, {@code auth-ts}, {@code auth-salt}, {@code auth-token}</li>
 * </ol>
 */
public class OpenHimAuthInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OpenHimAuthInterceptor.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OpenHimProperties.CoreProperties coreProperties;
    private final RestClient authRestClient;

    public OpenHimAuthInterceptor(OpenHimProperties.CoreProperties coreProperties, RestClient authRestClient) {
        this.coreProperties = coreProperties;
        this.authRestClient = authRestClient;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws java.io.IOException {
        try {
            AuthChallenge challenge = fetchAuthChallenge();

            // passwordHash = SHA512(serverSalt + password)
            String passwordHash = sha512Hex(challenge.salt() + coreProperties.password());

            // Generate random client salt
            byte[] saltBytes = new byte[16];
            RANDOM.nextBytes(saltBytes);
            String authSalt = HexFormat.of().formatHex(saltBytes);

            // authToken = SHA512(passwordHash + authSalt + ts)
            String authToken = sha512Hex(passwordHash + authSalt + challenge.ts());

            HttpHeaders headers = request.getHeaders();
            headers.set("auth-username", coreProperties.username());
            headers.set("auth-ts", challenge.ts());
            headers.set("auth-salt", authSalt);
            headers.set("auth-token", authToken);
        } catch (Exception e) {
            log.warn("Failed to compute OpenHIM auth token, falling back to no auth: {}", e.getMessage());
        }

        return execution.execute(request, body);
    }

    private AuthChallenge fetchAuthChallenge() {
        return authRestClient.get()
                .uri("/authenticate/{email}", coreProperties.username())
                .retrieve()
                .body(AuthChallenge.class);
    }

    private static String sha512Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-512 not available", e);
        }
    }

    /**
     * Response from OpenHIM Core's {@code /authenticate/<email>} endpoint.
     */
    record AuthChallenge(String salt, String ts) {}
}
}
