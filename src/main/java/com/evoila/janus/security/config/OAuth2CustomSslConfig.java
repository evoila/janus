package com.evoila.janus.security.config;

import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * Configures OAuth2 JWT Decoder with custom SSL context for Keycloak connections. This allows using
 * custom CA certificates for self-signed Keycloak instances.
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri")
public class OAuth2CustomSslConfig {

  @Value("${security.oauth2.custom-ca-path:}")
  private String customCaPath;

  @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}")
  private String jwkSetUri;

  /**
   * Creates a custom JWT Decoder with SSL context that includes custom CA certificate. Falls back
   * to default SSL context if custom CA is not configured or not found.
   */
  @Bean
  public ReactiveJwtDecoder jwtDecoder() {
    try {
      if (!CustomSslContextFactory.isCustomCaAvailable(customCaPath)) {
        log.info("No custom CA configured for OAuth2, using default SSL context");
        return createDefaultDecoder();
      }

      log.info("Configuring OAuth2 JWT Decoder with custom CA from: {}", customCaPath);

      // Create WebClient with custom SSL context
      HttpClient httpClient =
          CustomSslContextFactory.createHttpClientWithCustomCa(Path.of(customCaPath));
      WebClient webClient =
          WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build();

      // Create JWT Decoder with custom WebClient
      NimbusReactiveJwtDecoder decoder =
          NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).webClient(webClient).build();

      log.info("OAuth2 JWT Decoder configured with custom CA from: {}", customCaPath);
      return decoder;

    } catch (Exception e) {
      log.error("Failed to configure custom SSL for OAuth2, falling back to default", e);
      return createDefaultDecoder();
    }
  }

  private ReactiveJwtDecoder createDefaultDecoder() {
    log.info("Using default SSL context for OAuth2 JWT Decoder");
    return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
  }
}
