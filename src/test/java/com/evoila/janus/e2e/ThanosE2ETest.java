package com.evoila.janus.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.evoila.janus.app.JanusApplication;
import com.evoila.janus.integration.MockJwtConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * End-to-end integration tests for Janus with a real Prometheus instance. These tests verify that
 * Janus correctly enforces label-based access control when proxying queries to actual backends.
 *
 * <p>Test scenarios:
 *
 * <ol>
 *   <li>Basic enforcement: User queries without namespace filter, Janus adds constraint
 *   <li>Allowed namespace: User queries their allowed namespace, results returned
 *   <li>Forbidden namespace: User queries a namespace they can't access, results filtered
 *   <li>Admin access: Admin user can query all namespaces
 * </ol>
 *
 * <p>Test data:
 *
 * <ul>
 *   <li>namespace="demo" - accessible to order-service-team
 *   <li>namespace="observability" - accessible to my-service-team
 *   <li>namespace="production" - should be filtered out for both teams
 * </ul>
 */
@Tag("e2e")
@Tag("thanos")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class ThanosE2ETest {

  private static PrometheusTestContainer prometheusContainer;

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @BeforeAll
  static void startContainers() {
    prometheusContainer = new PrometheusTestContainer();
    prometheusContainer.start();
    prometheusContainer.waitForMetrics(Duration.ofSeconds(30));
  }

  @AfterAll
  static void stopContainers() {
    if (prometheusContainer != null) {
      prometheusContainer.stop();
    }
  }

  @BeforeEach
  void setupWebTestClient() {
    // Use NONE encoding mode to prevent Spring from interpreting curly braces
    // in query parameter values as template variables. With NONE mode, we handle
    // URL encoding ourselves in the helper methods.
    org.springframework.web.util.DefaultUriBuilderFactory factory =
        new org.springframework.web.util.DefaultUriBuilderFactory("http://localhost:" + port);
    factory.setEncodingMode(
        org.springframework.web.util.DefaultUriBuilderFactory.EncodingMode.NONE);

    this.webTestClient =
        WebTestClient.bindToServer()
            .uriBuilderFactory(factory)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    // This will be called before @BeforeAll, so we need to start containers here too
    if (prometheusContainer == null) {
      prometheusContainer = new PrometheusTestContainer();
      prometheusContainer.start();
      prometheusContainer.waitForMetrics(Duration.ofSeconds(30));
    }

    // Point Janus to the Prometheus container
    registry.add("proxy.services.thanos.url", prometheusContainer::getPrometheusUrl);

    // Use E2E configmap for label constraints - must be a file path, not classpath resource
    // The ConfigMapLabelStore uses Files.exists() which doesn't work with classpath: prefix
    String configPath = getConfigMapPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private static String getConfigMapPath() {
    try {
      // Try to find the config file in the classpath and return its absolute path
      java.net.URL resource =
          ThanosE2ETest.class.getClassLoader().getResource("configs/e2e-configmap.yaml");
      if (resource != null) {
        return new java.io.File(resource.toURI()).getAbsolutePath();
      }
      // Fallback to project path
      return "src/test/resources/configs/e2e-configmap.yaml";
    } catch (Exception _) {
      return "src/test/resources/configs/e2e-configmap.yaml";
    }
  }

  // ============ Test Tokens ============
  // These tokens are decoded by MockJwtConfig to extract group membership

  private static final String ORDER_SERVICE_TEAM_TOKEN = "test-token-order-service-team";
  private static final String MY_SERVICE_TEAM_TOKEN = "test-token-my-service-team";
  private static final String ADMIN_TOKEN = "test-token-admin";

  /** URL-encode a value for use in query parameters */
  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  /**
   * Helper method to build query URI. Since WebTestClient is configured with NONE encoding mode, we
   * manually URL-encode the query parameter values to avoid template variable interpretation.
   */
  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildQueryUri(String query) {
    return uriBuilder -> uriBuilder.path("/api/v1/query").query("query=" + encode(query)).build();
  }

  /** Helper method to build range query URI */
  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildRangeQueryUri(String query, long start, long end, long step) {
    return uriBuilder ->
        uriBuilder
            .path("/api/v1/query_range")
            .query("query=" + encode(query) + "&start=" + start + "&end=" + end + "&step=" + step)
            .build();
  }

  /** Helper method to build series URI */
  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildSeriesUri(String selector) {
    return uriBuilder ->
        uriBuilder.path("/api/v1/series").query("match[]=" + encode(selector)).build();
  }

  /** Helper method to build label values URI */
  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildLabelValuesUri(String labelName, String selector) {
    return uriBuilder ->
        uriBuilder
            .path("/api/v1/label/" + labelName + "/values")
            .query("match[]=" + encode(selector))
            .build();
  }

  // ============ Basic Enforcement Tests ============

  @Test
  @DisplayName("order-service-team: Query without namespace filter gets constraint added")
  void orderServiceTeam_queryWithoutNamespaceFilter_getsConstraintAdded() {
    // Query all http_requests_total without specifying namespace
    String query = "http_requests_total";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    // Verify results only contain namespace=demo (order-service-team's allowed namespace)
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric)
          .as("order-service-team should only see namespace=demo")
          .containsEntry("namespace", "demo");
    }
  }

  @Test
  @DisplayName("my-service-team: Query without namespace filter gets constraint added")
  void myServiceTeam_queryWithoutNamespaceFilter_getsConstraintAdded() {
    String query = "http_requests_total";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + MY_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric)
          .as("my-service-team should only see namespace=observability")
          .containsEntry("namespace", "observability");
    }
  }

  // ============ Allowed Namespace Tests ============

  @Test
  @DisplayName("order-service-team: Can query their allowed namespace (demo)")
  void orderServiceTeam_canQueryAllowedNamespace() {
    String query = "http_requests_total{namespace=\"demo\"}";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    // Should get results for demo namespace
    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric).containsEntry("namespace", "demo");
    }
  }

  @Test
  @DisplayName("my-service-team: Can query their allowed namespace (observability)")
  void myServiceTeam_canQueryAllowedNamespace() {
    String query = "http_requests_total{namespace=\"observability\"}";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + MY_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric).containsEntry("namespace", "observability");
    }
  }

  // ============ Forbidden Namespace Tests ============

  @Test
  @DisplayName("order-service-team: Query for forbidden namespace is rejected")
  void orderServiceTeam_queryForbiddenNamespace_isRejected() {
    // order-service-team tries to query production namespace (not allowed)
    // Janus returns 403 FORBIDDEN when explicitly querying a namespace the user can't access
    String query = "http_requests_total{namespace=\"production\"}";

    webTestClient
        .get()
        .uri(buildQueryUri(query))
        .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("my-service-team: Query for forbidden namespace is rejected")
  void myServiceTeam_queryForbiddenNamespace_isRejected() {
    // my-service-team tries to query production namespace (not allowed)
    // Janus returns 403 FORBIDDEN when explicitly querying a namespace the user can't access
    String query = "http_requests_total{namespace=\"production\"}";

    webTestClient
        .get()
        .uri(buildQueryUri(query))
        .header("Authorization", "Bearer " + MY_SERVICE_TEAM_TOKEN)
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  @Test
  @DisplayName("order-service-team: Cannot see observability namespace")
  void orderServiceTeam_cannotSeeObservabilityNamespace() {
    // order-service-team tries to query observability namespace (which belongs to my-service-team)
    // Janus returns 403 FORBIDDEN when explicitly querying a namespace the user can't access
    String query = "http_requests_total{namespace=\"observability\"}";

    webTestClient
        .get()
        .uri(buildQueryUri(query))
        .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
        .exchange()
        .expectStatus()
        .isForbidden();
  }

  // ============ Admin Access Tests ============

  @Test
  @DisplayName("admin: Can query all namespaces")
  void admin_canQueryAllNamespaces() {
    String query = "http_requests_total";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();

    // Admin should see metrics from all namespaces
    List<String> namespaces =
        results.stream()
            .map(r -> ((Map<String, String>) r.get("metric")).get("namespace"))
            .distinct()
            .toList();

    assertThat(namespaces)
        .as("Admin should see at least the three main namespaces")
        .contains("demo", "observability", "production");
  }

  @Test
  @DisplayName("admin: Can explicitly query any specific namespace")
  void admin_canQuerySpecificNamespace() {
    // Admin queries production namespace (which other teams can't access)
    String query = "http_requests_total{namespace=\"production\"}";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ADMIN_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric).containsEntry("namespace", "production");
    }
  }

  // ============ Range Query Tests ============

  @Test
  @DisplayName("order-service-team: Range query is also properly constrained")
  void orderServiceTeam_rangeQuery_isConstrained() {
    String query = "http_requests_total";

    long endTime = System.currentTimeMillis() / 1000;
    long startTime = endTime - 3600; // 1 hour ago
    long step = 15;

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildRangeQueryUri(query, startTime, endTime, step))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    // Range query results should also be constrained to namespace=demo
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric)
          .as("Range query should also be constrained to demo namespace")
          .containsEntry("namespace", "demo");
    }
  }

  // ============ Series Endpoint Tests ============

  @Test
  @DisplayName("order-service-team: Series query is properly constrained")
  void orderServiceTeam_seriesQuery_isConstrained() {
    String selector = "http_requests_total";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildSeriesUri(selector))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    List<Map<String, String>> seriesData = (List<Map<String, String>>) response.get("data");

    // Series endpoint should also be constrained
    for (Map<String, String> series : seriesData) {
      assertThat(series)
          .as("Series query should be constrained to demo namespace")
          .containsEntry("namespace", "demo");
    }
  }

  // ============ Label Values Tests ============

  @Test
  @DisplayName("order-service-team: Label values query respects constraints")
  void orderServiceTeam_labelValuesQuery_respectsConstraints() {
    String selector = "http_requests_total";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildLabelValuesUri("service", selector))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    List<String> labelValues = (List<String>) response.get("data");

    // Should only see services from namespace=demo (order-service, payment-service)
    // Should NOT see services from other namespaces (monitoring, critical-app)
    assertThat(labelValues)
        .as("Should only see services from demo namespace")
        .contains("order-service", "payment-service")
        .doesNotContain("critical-app", "monitoring");
  }

  // ============ Complex Query Tests ============

  @Test
  @DisplayName(
      "order-service-team: Complex PromQL query with explicit namespace is properly constrained")
  void orderServiceTeam_complexPromQLQuery_isConstrained() {
    // Note: For rate() functions, we need to include the label selector within the metric
    // because Janus injects constraints at the metric level
    String query = "rate(http_requests_total{namespace=\"demo\"}[5m])";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    // Even complex queries should be constrained
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric)
          .as("Complex PromQL query should be constrained to demo namespace")
          .containsEntry("namespace", "demo");
    }
  }

  @Test
  @DisplayName("order-service-team: Aggregation query is properly constrained")
  void orderServiceTeam_aggregationQuery_isConstrained() {
    String query = "sum(http_requests_total) by (namespace, service)";

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildQueryUri(query))
            .header("Authorization", "Bearer " + ORDER_SERVICE_TEAM_TOKEN)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
            .returnResult()
            .getResponseBody();

    assertThat(response).isNotNull().containsEntry("status", "success");

    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    // Aggregated results should only contain allowed namespace
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> metric = (Map<String, String>) result.get("metric");
      assertThat(metric)
          .as("Aggregation query should be constrained to demo namespace")
          .containsEntry("namespace", "demo");
    }
  }
}
