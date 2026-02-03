package com.evoila.janus.proxy;

import com.evoila.janus.security.config.CustomSslContextFactory;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Configuration
@Slf4j
public class WebClientConfig {

  @Value("${webclient.buffer-size:52428800}") // Default 50MB
  private int bufferSize;

  @Value("${security.oauth2.custom-ca-path:}")
  private String customCaPath;

  @Bean
  public WebClient webClient() {
    log.info("Creating WebClient with optimized connection pool and timeout configuration");

    // Create connection provider with proper pool settings
    ConnectionProvider connectionProvider =
        ConnectionProvider.builder("thanos-proxy-pool")
            .maxConnections(50) // Maximum connections in pool
            .maxIdleTime(Duration.ofSeconds(30)) // How long to keep idle connections
            .maxLifeTime(Duration.ofMinutes(5)) // Maximum lifetime of a connection
            .pendingAcquireTimeout(Duration.ofSeconds(10)) // How long to wait for a connection
            .evictInBackground(Duration.ofSeconds(30)) // Background cleanup interval
            .build();

    // Create HttpClient with connection provider, timeouts, and optional custom SSL
    HttpClient httpClient = createHttpClient(connectionProvider);

    return WebClient.builder()
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .codecs(
            configurer -> {
              // Increase buffer size for large responses
              configurer.defaultCodecs().maxInMemorySize(bufferSize);
              log.info(
                  "WebClient configured with buffer size: {} bytes ({} MB)",
                  bufferSize,
                  bufferSize / (1024 * 1024));
            })
        .build();
  }

  private HttpClient createHttpClient(ConnectionProvider connectionProvider) {
    // Create base HttpClient with connection provider
    HttpClient httpClient = HttpClient.create(connectionProvider);

    // Apply custom SSL if configured
    if (CustomSslContextFactory.isCustomCaAvailable(customCaPath)) {
      try {
        log.info("Configuring WebClient with custom CA from: {}", customCaPath);

        // Load custom CA TrustManager
        javax.net.ssl.TrustManagerFactory tmf =
            CustomSslContextFactory.loadCustomCaTrustManager(Path.of(customCaPath));

        // Apply SSL configuration to our client
        httpClient =
            httpClient.secure(
                sslSpec -> {
                  try {
                    sslSpec.sslContext(
                        io.netty.handler.ssl.SslContextBuilder.forClient()
                            .trustManager(tmf)
                            .build());
                  } catch (Exception e) {
                    throw new IllegalStateException("Failed to configure SSL context", e);
                  }
                });

        log.info("WebClient configured with custom CA successfully");

      } catch (Exception e) {
        log.error("Failed to configure custom SSL for WebClient, using default SSL context", e);
      }
    } else {
      log.info("No custom CA configured for WebClient, using default SSL context");
    }

    // Apply all other configurations
    return httpClient
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000) // 10 second connect timeout
        .responseTimeout(Duration.ofSeconds(30)) // 30 second response timeout
        .doOnConnected(
            conn ->
                conn.addHandlerLast(
                        new ReadTimeoutHandler(30, TimeUnit.SECONDS)) // 30 second read timeout
                    .addHandlerLast(
                        new WriteTimeoutHandler(10, TimeUnit.SECONDS)) // 10 second write timeout
            )
        .doOnRequest(
            (request, connection) ->
                log.debug("Making HTTP request to: {} {}", request.method(), request.uri()))
        .doOnResponse(
            (response, connection) ->
                log.debug("Received HTTP response: {} from {}", response.status(), response.uri()))
        .doOnError(
            (request, throwable) ->
                log.error(
                    "HTTP request failed: {} {} - {}",
                    request.method(),
                    request.uri(),
                    throwable.getMessage()),
            (response, throwable) ->
                log.error(
                    "HTTP response error: {} from {} - {}",
                    response.status(),
                    response.uri(),
                    throwable.getMessage()));
  }
}
