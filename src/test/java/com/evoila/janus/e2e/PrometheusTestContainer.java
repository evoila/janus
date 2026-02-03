package com.evoila.janus.e2e;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Helper class to set up Prometheus and a metrics server container for E2E testing. This class
 * manages a two-container setup:
 *
 * <ol>
 *   <li>An nginx container serving static test metrics at /metrics
 *   <li>A Prometheus container configured to scrape the nginx metrics server
 * </ol>
 */
public class PrometheusTestContainer {

  private static final Logger logger = LoggerFactory.getLogger(PrometheusTestContainer.class);

  private static final String PROMETHEUS_IMAGE = "prom/prometheus:v2.50.1";
  private static final String NGINX_IMAGE = "nginx:alpine";

  private final Network network;
  private GenericContainer<?> metricsServer;
  private GenericContainer<?> prometheus;

  public PrometheusTestContainer() {
    this.network = Network.newNetwork();
  }

  /** Starts both the metrics server and Prometheus containers. */
  public void start() {
    startMetricsServer();
    startPrometheus();

    // Wait for Prometheus to scrape metrics (with some buffer for initial scrape)
    logger.info("Waiting for Prometheus to scrape initial metrics...");
    await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
  }

  /** Stops both containers and cleans up the network. */
  public void stop() {
    if (prometheus != null) {
      prometheus.stop();
    }
    if (metricsServer != null) {
      metricsServer.stop();
    }
    if (network != null) {
      network.close();
    }
  }

  /** Returns the Prometheus HTTP URL accessible from the host. */
  public String getPrometheusUrl() {
    return String.format("http://%s:%d", prometheus.getHost(), prometheus.getMappedPort(9090));
  }

  /** Returns the Prometheus container instance. */
  public GenericContainer<?> getPrometheusContainer() {
    return prometheus;
  }

  /** Returns the metrics server container instance. */
  public GenericContainer<?> getMetricsServerContainer() {
    return metricsServer;
  }

  private void startMetricsServer() {
    logger.info("Starting metrics server container...");

    metricsServer =
        new GenericContainer<>(DockerImageName.parse(NGINX_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("metrics-server")
            .withExposedPorts(80)
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("prometheus/nginx.conf"),
                "/etc/nginx/nginx.conf")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("prometheus/test-metrics.txt"),
                "/usr/share/nginx/html/metrics")
            .waitingFor(
                Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(30)));

    metricsServer.start();
    logger.info(
        "Metrics server started at http://{}:{}",
        metricsServer.getHost(),
        metricsServer.getMappedPort(80));
  }

  private void startPrometheus() {
    logger.info("Starting Prometheus container...");

    // Create Prometheus config content inline to ensure correct metrics-server hostname
    String prometheusConfig =
        """
        global:
          scrape_interval: 1s
          evaluation_interval: 1s

        scrape_configs:
          - job_name: 'test-metrics'
            static_configs:
              - targets: ['metrics-server:80']
            metrics_path: /metrics
            scrape_interval: 1s
            scrape_timeout: 1s
        """;

    prometheus =
        new GenericContainer<>(DockerImageName.parse(PROMETHEUS_IMAGE))
            .withNetwork(network)
            .withNetworkAliases("prometheus")
            .withExposedPorts(9090)
            .withCopyToContainer(
                Transferable.of(prometheusConfig), "/etc/prometheus/prometheus.yml")
            .withCommand(
                "--config.file=/etc/prometheus/prometheus.yml",
                "--storage.tsdb.path=/prometheus",
                "--web.console.libraries=/usr/share/prometheus/console_libraries",
                "--web.console.templates=/usr/share/prometheus/consoles",
                "--web.enable-lifecycle")
            .waitingFor(
                Wait.forHttp("/-/ready")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    prometheus.start();
    logger.info(
        "Prometheus started at http://{}:{}", prometheus.getHost(), prometheus.getMappedPort(9090));
  }

  /**
   * Waits until Prometheus has scraped metrics and they are queryable. This method queries
   * Prometheus until it returns results for our test metrics.
   */
  public void waitForMetrics(Duration timeout) {
    logger.info("Waiting for metrics to be available in Prometheus...");

    try {
      await()
          .atMost(timeout)
          .pollInterval(Duration.ofMillis(500))
          .ignoreExceptions()
          .until(
              () -> {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpRequest request =
                    java.net.http.HttpRequest.newBuilder()
                        .uri(
                            java.net.URI.create(
                                getPrometheusUrl() + "/api/v1/query?query=http_requests_total"))
                        .GET()
                        .build();

                java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

                return response.statusCode() == 200 && response.body().contains("\"result\":[{");
              });

      logger.info("Metrics are now available in Prometheus");
    } catch (Exception _) {
      logger.warn("Timeout waiting for metrics, proceeding anyway...");
    }
  }
}
