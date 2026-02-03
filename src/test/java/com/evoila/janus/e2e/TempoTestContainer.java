package com.evoila.janus.e2e;

import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Helper class to set up Tempo container for E2E testing. This class manages a Tempo container and
 * provides methods to push test trace data via OTLP HTTP.
 */
public class TempoTestContainer {

  private static final Logger logger = LoggerFactory.getLogger(TempoTestContainer.class);

  private static final String TEMPO_IMAGE = "grafana/tempo:2.3.1";

  private final Network network;
  private GenericContainer<?> tempo;
  private final Random random = new Random();

  public TempoTestContainer() {
    this.network = Network.newNetwork();
  }

  /** Starts the Tempo container. */
  public void start() {
    startTempo();
    pushTestTraces();
  }

  /** Stops the container and cleans up the network. */
  public void stop() {
    if (tempo != null) {
      tempo.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  /** Returns the Tempo HTTP URL accessible from the host. */
  public String getTempoUrl() {
    return String.format("http://%s:%d", tempo.getHost(), tempo.getMappedPort(3200));
  }

  /** Returns the Tempo OTLP HTTP URL for ingesting traces. */
  public String getOtlpUrl() {
    return String.format("http://%s:%d", tempo.getHost(), tempo.getMappedPort(4318));
  }

  /** Returns the Tempo container instance. */
  public GenericContainer<?> getTempoContainer() {
    return tempo;
  }

  private void startTempo() {
    logger.info("Starting Tempo container...");

    // Minimal Tempo config for testing with local storage
    String tempoConfig =
        """
        server:
          http_listen_port: 3200

        distributor:
          receivers:
            otlp:
              protocols:
                http:
                  endpoint: 0.0.0.0:4318
                grpc:
                  endpoint: 0.0.0.0:4317

        ingester:
          trace_idle_period: 10s
          max_block_bytes: 1_000_000
          max_block_duration: 5m

        compactor:
          compaction:
            compaction_window: 1h
            max_block_bytes: 100_000_000
            block_retention: 1h
            compacted_block_retention: 10m

        storage:
          trace:
            backend: local
            local:
              path: /tmp/tempo/blocks
            wal:
              path: /tmp/tempo/wal

        querier:
          frontend_worker:
            frontend_address: ""

        metrics_generator:
          registry:
            external_labels:
              source: tempo
          storage:
            path: /tmp/tempo/generator/wal
        """;

    tempo =
        new GenericContainer<>(DockerImageName.parse(TEMPO_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("tempo")
            .withExposedPorts(3200, 4318, 4317)
            .withCopyToContainer(Transferable.of(tempoConfig), "/etc/tempo/tempo.yaml")
            .withCommand("-config.file=/etc/tempo/tempo.yaml")
            .waitingFor(
                Wait.forHttp("/ready")
                    .forPort(3200)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    tempo.start();
    logger.info("Tempo started at http://{}:{}", tempo.getHost(), tempo.getMappedPort(3200));
  }

  /** Pushes test trace data to Tempo with different service names. */
  private void pushTestTraces() {
    logger.info("Pushing test traces to Tempo...");

    try {
      HttpClient client = HttpClient.newHttpClient();

      // Push traces for demo services (order-service-team)
      pushTraceForService(client, "demo-order-service", "Processing order #12345");
      pushTraceForService(client, "demo-order-service", "Validating order items");
      pushTraceForService(client, "demo-payment-service", "Processing payment");
      pushTraceForService(client, "demo-payment-service", "Payment completed");

      // Push traces for observability services (my-service-team)
      pushTraceForService(client, "observability-prometheus", "Scraping metrics");
      pushTraceForService(client, "observability-prometheus", "Evaluating rules");
      pushTraceForService(client, "observability-grafana", "Rendering dashboard");
      pushTraceForService(client, "observability-grafana", "Querying data source");

      // Push traces for production services (should be filtered out for teams)
      pushTraceForService(client, "production-critical-app", "Critical operation");
      pushTraceForService(client, "production-critical-app", "Database transaction");
      pushTraceForService(client, "production-api-gateway", "Routing request");
      pushTraceForService(client, "production-api-gateway", "Load balancing");

      // Push traces for pattern testing - wildcard patterns
      pushTraceForService(client, "demo-order-api", "order");
      pushTraceForService(client, "demo-payment-api", "payment");
      pushTraceForService(client, "demo-inventory-service", "inventory check");
      pushTraceForService(client, "demo-sensitive-service", "sensitive data access");

      // Push traces for staging services
      pushTraceForService(client, "staging-order-service", "Staging order processing");
      pushTraceForService(client, "staging-order-service", "Staging order validation");

      // Push traces for suffix pattern testing (*-service)
      pushTraceForService(client, "user-service", "User lookup");
      pushTraceForService(client, "auth-service", "Authentication check");

      // Push traces with different span names for complex query testing
      pushTraceForService(client, "demo-order-service", "HTTP GET /api/orders");
      pushTraceForService(client, "demo-order-service", "HTTP POST /api/orders");
      pushTraceForService(client, "demo-payment-service", "HTTP POST /api/payments");

      logger.info("Test traces pushed successfully");

      // Give Tempo time to ingest and index the traces
      await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
    } catch (Exception e) {
      logger.error("Failed to push test traces", e);
    }
  }

  private void pushTraceForService(HttpClient client, String serviceName, String spanName)
      throws Exception {

    String traceId = generateTraceId();
    String spanId = generateSpanId();
    long now = System.currentTimeMillis() * 1_000_000L; // nanoseconds

    // OTLP JSON format for traces
    String otlpPayload =
        String.format(
            """
            {
              "resourceSpans": [{
                "resource": {
                  "attributes": [{
                    "key": "service.name",
                    "value": {"stringValue": "%s"}
                  }]
                },
                "scopeSpans": [{
                  "scope": {
                    "name": "test-tracer",
                    "version": "1.0.0"
                  },
                  "spans": [{
                    "traceId": "%s",
                    "spanId": "%s",
                    "name": "%s",
                    "kind": 1,
                    "startTimeUnixNano": "%d",
                    "endTimeUnixNano": "%d",
                    "status": {}
                  }]
                }]
              }]
            }
            """,
            serviceName,
            traceId,
            spanId,
            spanName,
            now - 1_000_000_000L, // 1 second ago
            now);

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(getOtlpUrl() + "/v1/traces"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(otlpPayload))
            .build();

    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() != 200 && response.statusCode() != 202) {
      logger.warn(
          "Failed to push trace for service={}: {} - {}",
          serviceName,
          response.statusCode(),
          response.body());
    } else {
      logger.debug("Pushed trace for service={}, span={}", serviceName, spanName);
    }
  }

  private String generateTraceId() {
    // 32 hex characters (16 bytes)
    byte[] bytes = new byte[16];
    random.nextBytes(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  private String generateSpanId() {
    // 16 hex characters (8 bytes)
    byte[] bytes = new byte[8];
    random.nextBytes(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  /**
   * Waits until Tempo has indexed traces and they are queryable. This method queries Tempo until it
   * returns results for our test traces.
   */
  public void waitForTraces(Duration timeout) {
    logger.info("Waiting for traces to be available in Tempo...");

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
                        .uri(URI.create(getTempoUrl() + "/api/search?q={}"))
                        .GET()
                        .build();

                HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());

                return response.statusCode() == 200 && response.body().contains("\"traces\"");
              });

      logger.info("Traces are now available in Tempo");
    } catch (Exception _) {
      logger.warn("Timeout waiting for traces, proceeding anyway...");
    }
  }
}
