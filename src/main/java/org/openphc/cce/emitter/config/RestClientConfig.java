package org.openphc.cce.emitter.config;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configures {@link RestClient} beans for outbound HTTP communication.
 *
 * <p>Two named beans are created:
 * <ul>
 *   <li>{@code collectorRestClient} — targets the CCE Collector with JSON content type and configurable timeout</li>
 *   <li>{@code coreApiRestClient} — targets the OpenHIM Core API with Basic Auth</li>
 * </ul>
 */
@Configuration
public class RestClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RestClientConfig.class);

    /**
     * Creates a {@link RestClient} for forwarding CloudEvents to the CCE Collector.
     *
     * <p>Configured with:
     * <ul>
     *   <li>Base URL from {@link CollectorProperties#url()}</li>
     *   <li>JSON content type default header</li>
     *   <li>Connect + read timeout from {@link CollectorProperties#timeout()}</li>
     * </ul>
     *
     * @param properties Collector configuration properties
     * @return configured RestClient for Collector communication
     */
    @Bean
    @Qualifier("collectorRestClient")
    public RestClient collectorRestClient(CollectorProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(properties.timeout()));
        requestFactory.setReadTimeout(Duration.ofMillis(properties.timeout()));

        var builder = RestClient.builder()
                .baseUrl(properties.url())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory);

        if (properties.auth() != null && properties.auth().token() != null) {
            builder.defaultHeader("Authorization", "Bearer " + properties.auth().token());
        }

        return builder.build();
    }

    /**
     * Creates a {@link RestClient} for communicating with the OpenHIM Core API.
     *
     * <p>Configured with:
     * <ul>
     *   <li>Base URL derived from {@link OpenHimProperties.CoreProperties#apiUrl()}</li>
     *   <li>Basic Authentication header from Core username/password</li>
     *   <li>JSON content type default header</li>
     * </ul>
     *
     * @param properties OpenHIM configuration properties
     * @return configured RestClient for OpenHIM Core API communication
     */
    @Bean
    @Qualifier("coreApiRestClient")
    public RestClient coreApiRestClient(OpenHimProperties properties) {
        String credentials = properties.core().username() + ":" + properties.core().password();
        String basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());

        SimpleClientHttpRequestFactory requestFactory = createTrustAllRequestFactory();

        return RestClient.builder()
                .baseUrl(properties.core().apiUrl())
                .defaultHeader("Authorization", basicAuth)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Creates a {@link SimpleClientHttpRequestFactory} that trusts all SSL certificates.
     * Required because OpenHIM Core uses a self-signed certificate on its API port.
     */
    private SimpleClientHttpRequestFactory createTrustAllRequestFactory() {
        try {
            TrustManager[] trustAllCerts = { new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }};

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            return new SimpleClientHttpRequestFactory();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            log.warn("Failed to configure trust-all SSL context for OpenHIM Core API: {}", e.getMessage());
            return new SimpleClientHttpRequestFactory();
        }
    }
}
