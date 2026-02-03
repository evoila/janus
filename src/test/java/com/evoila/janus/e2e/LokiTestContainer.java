package com.evoila.janus.e2e;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Helper class to set up Loki container for E2E testing. This class manages a Loki container and
 * provides methods to push test log data.
 */
public class LokiTestContainer {

  private static final Logger logger = LoggerFactory.getLogger(LokiTestContainer.class);

  private static final String LOKI_IMAGE = "grafana/loki:2.9.4";

  private final Network network;
  private GenericContainer<?> loki;

  public LokiTestContainer() {
    this.network = Network.newNetwork();
  }

  /** Starts the Loki container. */
  public void start() {
    startLoki();
    pushTestLogs();
  }

  /** Stops the container and cleans up the network. */
  public void stop() {
    if (loki != null) {
      loki.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  /** Returns the Loki HTTP URL accessible from the host. */
  public String getLokiUrl() {
    return String.format("http://%s:%d", loki.getHost(), loki.getMappedPort(3100));
  }

  /** Returns the Loki container instance. */
  public GenericContainer<?> getLokiContainer() {
    return loki;
  }

  private void startLoki() {
    logger.info("Starting Loki container...");

    // Minimal Loki config for testing
    String lokiConfig =
        """
        auth_enabled: false

        server:
          http_listen_port: 3100
          grpc_listen_port: 9096

        common:
          instance_addr: 127.0.0.1
          path_prefix: /tmp/loki
          storage:
            filesystem:
              chunks_directory: /tmp/loki/chunks
              rules_directory: /tmp/loki/rules
          replication_factor: 1
          ring:
            kvstore:
              store: inmemory

        query_range:
          results_cache:
            cache:
              embedded_cache:
                enabled: true
                max_size_mb: 100

        schema_config:
          configs:
            - from: 2020-10-24
              store: tsdb
              object_store: filesystem
              schema: v13
              index:
                prefix: index_
                period: 24h

        ruler:
          alertmanager_url: http://localhost:9093

        limits_config:
          reject_old_samples: false
          reject_old_samples_max_age: 168h
        """;

    loki =
        new GenericContainer<>(DockerImageName.parse(LOKI_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("loki")
            .withExposedPorts(3100)
            .withCopyToContainer(Transferable.of(lokiConfig), "/etc/loki/local-config.yaml")
            .withCommand("-config.file=/etc/loki/local-config.yaml")
            .waitingFor(
                Wait.forHttp("/ready")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    loki.start();
    logger.info("Loki started at http://{}:{}", loki.getHost(), loki.getMappedPort(3100));
  }

  /** Pushes test log data to Loki with different namespace labels. */
  private void pushTestLogs() {
    logger.info("Pushing test logs to Loki...");

    try {
      HttpClient client = HttpClient.newHttpClient();

      // Push logs for demo namespace
      pushLogsForNamespace(client, "demo", "order-service", "Processing order #12345");
      pushLogsForNamespace(client, "demo", "payment-service", "Payment received for order #12345");
      pushLogsForNamespace(client, "demo", "order-service", "Order #12345 completed successfully");

      // Push logs for observability namespace
      pushLogsForNamespace(
          client, "observability", "prometheus", "Scraping targets completed in 1.2s");
      pushLogsForNamespace(client, "observability", "grafana", "Dashboard loaded successfully");
      pushLogsForNamespace(client, "observability", "alertmanager", "Alert evaluation completed");

      // Push logs for production namespace (should be filtered out for teams)
      pushLogsForNamespace(client, "production", "critical-app", "Critical service heartbeat OK");
      pushLogsForNamespace(
          client, "production", "database", "Database connection pool at 80% capacity");
      pushLogsForNamespace(client, "production", "api-gateway", "Request rate: 10000 req/s");

      // Push logs for wildcard pattern testing (prefix: demo-*)
      pushLogsForNamespace(client, "demo-orders", "order-api", "New order received #67890");
      pushLogsForNamespace(client, "demo-orders", "order-api", "Order validation passed");
      pushLogsForNamespace(client, "demo-payments", "payment-api", "Payment processing started");
      pushLogsForNamespace(
          client, "demo-payments", "payment-api", "Payment completed successfully");
      pushLogsForNamespace(client, "demo-sensitive", "secret-api", "Sensitive data accessed");

      // Push logs for wildcard pattern testing (suffix: *-service)
      pushLogsForNamespace(client, "order-service", "order-api", "Service order endpoint called");
      pushLogsForNamespace(client, "payment-service", "payment-api", "Service payment processed");

      // Push logs for staging namespace
      pushLogsForNamespace(client, "staging-orders", "order-api", "Staging order test #99999");
      pushLogsForNamespace(client, "staging-orders", "order-api", "Staging validation complete");

      logger.info("Test logs pushed successfully");

      // Give Loki time to index the logs
      await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
    } catch (Exception e) {
      logger.error("Failed to push test logs", e);
    }
  }

  private void pushLogsForNamespace(
      HttpClient client, String namespace, String app, String logMessage) throws Exception {

    long timestampNanos = Instant.now().toEpochMilli() * 1_000_000L;

    // Loki push API expects JSON in a specific format
    String pushBody =
        String.format(
            """
            {
              "streams": [
                {
                  "stream": {
                    "k8s_namespace_name": "%s",
                    "app": "%s",
                    "job": "test-logs"
                  },
                  "values": [
                    ["%d", "%s"]
                  ]
                }
              ]
            }
            """,
            namespace, app, timestampNanos, logMessage);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getLokiUrl() + "/loki/api/v1/push"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(pushBody))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 204 && response.statusCode() != 200) {
      logger.warn(
          "Failed to push log for namespace={}, app={}: {} - {}",
          namespace,
          app,
          response.statusCode(),
          response.body());
    } else {
      logger.debug("Pushed log for namespace={}, app={}", namespace, app);
    }
  }

  /**
   * Waits until Loki has indexed logs and they are queryable. This method queries Loki until it
   * returns results for our test logs from all namespaces including pattern test namespaces.
   */
  public void waitForLogs(Duration timeout) {
    logger.info("Waiting for logs to be available in Loki...");

    try {
      await()
          .atMost(timeout)
          .pollInterval(Duration.ofMillis(500))
          .ignoreExceptions()
          .until(
              () -> {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request =
                    HttpRequest.newBuilder()
                        .uri(
                            URI.create(
                                getLokiUrl()
                                    + "/loki/api/v1/query?query="
                                    + java.net.URLEncoder.encode(
                                        "{job=\"test-logs\"}",
                                        java.nio.charset.StandardCharsets.UTF_8)))
                        .GET()
                        .build();

                HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

                return response.statusCode() == 200
                    && response.body().contains("\"result\":[{")
                    && response.body().contains("demo")
                    && response.body().contains("observability")
                    && response.body().contains("production")
                    && response.body().contains("demo-orders");
              });

      logger.info("Logs from all namespaces are now available in Loki");
      // Extra wait to ensure all logs are queryable
      await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> true);
    } catch (Exception _) {
      logger.warn("Timeout waiting for logs, proceeding anyway...");
    }
  }
}
