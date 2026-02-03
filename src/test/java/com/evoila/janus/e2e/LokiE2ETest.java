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
 * End-to-end integration tests for Janus with a real Loki instance. These tests verify that Janus
 * correctly enforces label-based access control when proxying LogQL queries to actual backends.
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
 * <p>Test data (using k8s_namespace_name label for Loki):
 *
 * <ul>
 *   <li>k8s_namespace_name="demo" - accessible to order-service-team
 *   <li>k8s_namespace_name="observability" - accessible to my-service-team
 *   <li>k8s_namespace_name="production" - should be filtered out for both teams
 * </ul>
 */
@Tag("e2e")
@Tag("loki")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class LokiE2ETest {

  private static LokiTestContainer lokiContainer;

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @BeforeAll
  static void startContainers() {
    lokiContainer = new LokiTestContainer();
    lokiContainer.start();
    lokiContainer.waitForLogs(Duration.ofSeconds(60));
  }

  @AfterAll
  static void stopContainers() {
    if (lokiContainer != null) {
      lokiContainer.stop();
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
    if (lokiContainer == null) {
      lokiContainer = new LokiTestContainer();
      lokiContainer.start();
      lokiContainer.waitForLogs(Duration.ofSeconds(60));
    }

    // Point Janus to the Loki container
    registry.add("proxy.services.loki.url", lokiContainer::getLokiUrl);

    // Use E2E configmap for label constraints
    String configPath = getConfigMapPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private static String getConfigMapPath() {
    try {
      java.net.URL resource =
          LokiE2ETest.class.getClassLoader().getResource("configs/e2e-configmap.yaml");
      if (resource != null) {
        return new java.io.File(resource.toURI()).getAbsolutePath();
      }
      return "src/test/resources/configs/e2e-configmap.yaml";
    } catch (Exception _) {
      return "src/test/resources/configs/e2e-configmap.yaml";
    }
  }

  // ============ Test Tokens ============

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
    return uriBuilder ->
        uriBuilder.path("/loki/api/v1/query").query("query=" + encode(query)).build();
  }

  /** Helper method to build range query URI */
  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildRangeQueryUri(String query, long start, long end) {
    return uriBuilder ->
        uriBuilder
            .path("/loki/api/v1/query_range")
            .query("query=" + encode(query) + "&start=" + start + "&end=" + end)
            .build();
  }

  /** Helper method to build label values URI */
  private String buildLabelValuesUri(String labelName) {
    return "/loki/api/v1/label/" + labelName + "/values";
  }

  // ============ Basic Enforcement Tests ============

  @Test
  @DisplayName("order-service-team: Query without namespace filter gets constraint added")
  void orderServiceTeam_queryWithoutNamespaceFilter_getsConstraintAdded() {
    // Query all logs without specifying namespace
    String query = "{job=\"test-logs\"}";

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

    // Verify results only contain k8s_namespace_name=demo
    @SuppressWarnings("unchecked")
    Map<String, Object> data = (Map<String, Object>) response.get("data");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream)
          .as("order-service-team should only see k8s_namespace_name=demo")
          .containsEntry("k8s_namespace_name", "demo");
    }
  }

  @Test
  @DisplayName("my-service-team: Query without namespace filter gets constraint added")
  void myServiceTeam_queryWithoutNamespaceFilter_getsConstraintAdded() {
    String query = "{job=\"test-logs\"}";

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
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream)
          .as("my-service-team should only see k8s_namespace_name=observability")
          .containsEntry("k8s_namespace_name", "observability");
    }
  }

  // ============ Allowed Namespace Tests ============

  @Test
  @DisplayName("order-service-team: Can query their allowed namespace (demo)")
  void orderServiceTeam_canQueryAllowedNamespace() {
    String query = "{k8s_namespace_name=\"demo\"}";

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

    assertThat(results).isNotEmpty();
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream).containsEntry("k8s_namespace_name", "demo");
    }
  }

  @Test
  @DisplayName("my-service-team: Can query their allowed namespace (observability)")
  void myServiceTeam_canQueryAllowedNamespace() {
    String query = "{k8s_namespace_name=\"observability\"}";

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
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream).containsEntry("k8s_namespace_name", "observability");
    }
  }

  // ============ Forbidden Namespace Tests ============

  @Test
  @DisplayName("order-service-team: Query for forbidden namespace is rejected")
  void orderServiceTeam_queryForbiddenNamespace_isRejected() {
    // order-service-team tries to query production namespace (not allowed)
    String query = "{k8s_namespace_name=\"production\"}";

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
    String query = "{k8s_namespace_name=\"production\"}";

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
    // order-service-team tries to query observability namespace (belongs to my-service-team)
    String query = "{k8s_namespace_name=\"observability\"}";

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
    String query = "{job=\"test-logs\"}";

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

    // Admin should see logs from all namespaces
    List<String> namespaces =
        results.stream()
            .map(r -> ((Map<String, String>) r.get("stream")).get("k8s_namespace_name"))
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
    String query = "{k8s_namespace_name=\"production\"}";

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
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream).containsEntry("k8s_namespace_name", "production");
    }
  }

  // ============ Range Query Tests ============

  @Test
  @DisplayName("order-service-team: Range query is also properly constrained")
  void orderServiceTeam_rangeQuery_isConstrained() {
    String query = "{job=\"test-logs\"}";

    long endTime = System.currentTimeMillis() * 1_000_000L; // nanoseconds
    long startTime = endTime - (3600L * 1_000_000_000L); // 1 hour ago in nanoseconds

    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildRangeQueryUri(query, startTime, endTime))
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

    // Range query results should also be constrained to k8s_namespace_name=demo
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream)
          .as("Range query should also be constrained to demo namespace")
          .containsEntry("k8s_namespace_name", "demo");
    }
  }

  // ============ Label Endpoints Tests ============

  @Test
  @DisplayName("order-service-team: Label values query respects constraints")
  void orderServiceTeam_labelValuesQuery_respectsConstraints() {
    Map<String, Object> response =
        webTestClient
            .get()
            .uri(buildLabelValuesUri("app"))
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

    // Should only see apps from k8s_namespace_name=demo (order-service, payment-service)
    // Should NOT see apps from other namespaces (prometheus, grafana, critical-app)
    assertThat(labelValues)
        .as("Should only see apps from demo namespace")
        .contains("order-service", "payment-service")
        .doesNotContain("critical-app", "prometheus", "grafana");
  }

  // ============ LogQL Filter Tests ============

  @Test
  @DisplayName("order-service-team: LogQL with line filter is properly constrained")
  void orderServiceTeam_logQLWithLineFilter_isConstrained() {
    // LogQL with a line filter - should still be constrained to allowed namespace
    String query = "{job=\"test-logs\"} |= \"order\"";

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

    // All results should be constrained to demo namespace
    for (Map<String, Object> result : results) {
      @SuppressWarnings("unchecked")
      Map<String, String> stream = (Map<String, String>) result.get("stream");
      assertThat(stream)
          .as("LogQL with line filter should be constrained to demo namespace")
          .containsEntry("k8s_namespace_name", "demo");
    }
  }
}
