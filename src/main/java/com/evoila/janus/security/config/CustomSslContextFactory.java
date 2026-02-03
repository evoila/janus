package com.evoila.janus.security.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

/**
 * Utility class for creating SSL contexts with custom CA certificates. This is used by both OAuth2
 * (Keycloak) and WebClient (Loki, Thanos, Tempo) configurations to load a shared root CA
 * certificate.
 */
@UtilityClass
@Slf4j
public class CustomSslContextFactory {

  /**
   * Loads a custom CA certificate and creates a TrustManagerFactory.
   *
   * @param caPath Path to the custom CA certificate file (PEM format)
   * @return TrustManagerFactory configured with the custom CA
   * @throws Exception if certificate loading fails
   */
  public static TrustManagerFactory loadCustomCaTrustManager(Path caPath) throws Exception {
    log.info("Loading custom CA certificate from: {}", caPath);

    // Load custom CA certificate
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    Certificate ca;
    try (InputStream in = Files.newInputStream(caPath)) {
      ca = cf.generateCertificate(in);
    }

    // Create KeyStore with custom CA
    KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    keyStore.load(null, null);
    keyStore.setCertificateEntry("custom-ca", ca);

    // Initialize TrustManager with custom KeyStore
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(keyStore);

    log.info("Custom CA TrustManager created successfully from: {}", caPath);
    return tmf;
  }

  /**
   * Creates an HttpClient configured with a custom CA certificate.
   *
   * @param caPath Path to the custom CA certificate file (PEM format)
   * @return HttpClient configured with custom SSL context
   * @throws Exception if certificate loading or SSL configuration fails
   */
  public static HttpClient createHttpClientWithCustomCa(Path caPath) throws Exception {
    TrustManagerFactory tmf = loadCustomCaTrustManager(caPath);

    // Create HttpClient with custom SSL context
    try {
      HttpClient httpClient =
          HttpClient.create()
              .secure(
                  sslSpec -> {
                    try {
                      sslSpec.sslContext(
                          io.netty.handler.ssl.SslContextBuilder.forClient()
                              .trustManager(tmf)
                              .build());
                    } catch (SSLException e) {
                      throw new IllegalStateException("Failed to configure SSL context", e);
                    }
                  });

      log.info("HttpClient with custom SSL context created successfully");
      return httpClient;

    } catch (Exception e) {
      throw new SSLException("Failed to create HttpClient with custom CA", e);
    }
  }

  /**
   * Checks if a custom CA file exists and is accessible.
   *
   * @param customCaPath Path to the custom CA certificate file
   * @return true if the file exists and is readable, false otherwise
   */
  public static boolean isCustomCaAvailable(String customCaPath) {
    if (customCaPath == null || customCaPath.trim().isEmpty()) {
      return false;
    }

    Path caPath = Path.of(customCaPath);
    return Files.exists(caPath) && Files.isReadable(caPath);
  }
}
