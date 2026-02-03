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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Scenario-based E2E tests for Thanos/PromQL. Tests various scenarios including error handling,
 * multi-user access, and complex queries using the default e2e-configmap.yaml configuration.
 */
@Tag("e2e")
@Tag("thanos")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class ThanosScenarioE2ETest {

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
    if (prometheusContainer == null) {
      prometheusContainer = new PrometheusTestContainer();
      prometheusContainer.start();
      prometheusContainer.waitForMetrics(Duration.ofSeconds(30));
    }

    registry.add("proxy.services.thanos.url", prometheusContainer::getPrometheusUrl);
    String configPath = E2ETestFixtures.getDefaultConfigPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildQueryUri(String query) {
    return uriBuilder -> uriBuilder.path("/api/v1/query").query("query=" + encode(query)).build();
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildRangeQueryUri(String query, long start, long end, long step) {
    return uriBuilder ->
        uriBuilder
            .path("/api/v1/query_range")
            .query("query=" + encode(query) + "&start=" + start + "&end=" + end + "&step=" + step)
            .build();
  }

  // ============ Nested Test Classes ============

  @Nested
  @DisplayName("Wildcard Pattern Constraints")
  class WildcardPatternTests {

    @Test
    @DisplayName("Prefix pattern allows matching namespaces")
    void prefixPattern_allowsMatchingNamespaces() {
      // Query for demo namespace which is allowed for order-service-team
      String query = "http_requests_total{namespace=\"demo\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("Query without filter returns only allowed namespaces")
    void queryWithoutFilter_returnsOnlyAllowedNamespaces() {
      String query = "http_requests_total";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
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

      // Verify all results are from allowed namespace
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        assertThat(metric)
            .as("Should only see allowed namespace")
            .containsEntry("namespace", "demo");
      }
    }

    @Test
    @DisplayName("my-service-team can access observability namespace")
    void myServiceTeam_canAccessObservabilityNamespace() {
      String query = "http_requests_total{namespace=\"observability\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.MY_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Malformed PromQL returns 400 Bad Request")
    void malformedPromQL_returns400() {
      // Invalid PromQL syntax
      String query = "http_requests_total{namespace=";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isBadRequest();
    }

    @Test
    @DisplayName("Unauthorized namespace access returns 403 Forbidden")
    void unauthorizedNamespaceAccess_returns403() {
      // order-service-team tries to query production namespace
      String query = "http_requests_total{namespace=\"production\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Cross-team namespace access is forbidden")
    void crossTeamNamespaceAccess_isForbidden() {
      // order-service-team tries to query observability namespace (belongs to my-service-team)
      String query = "http_requests_total{namespace=\"observability\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Empty result returns 200 with empty data")
    void emptyResult_returns200WithEmptyData() {
      // Query for a metric that doesn't exist
      String query = "nonexistent_metric_name{namespace=\"demo\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
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

      assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Missing authorization header returns 401")
    void missingAuthHeader_returns401() {
      String query = "http_requests_total";

      webTestClient.get().uri(buildQueryUri(query)).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Invalid token returns 401")
    void invalidToken_returns401() {
      String query = "http_requests_total";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer invalid-token")
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }
  }

  @Nested
  @DisplayName("Multi-User Scenarios")
  class MultiUserTests {

    @Test
    @DisplayName("Multi-group user can access both demo and observability namespaces")
    void multiGroupUser_canAccessBothNamespaces() {
      // Multi-group user queries demo namespace
      String queryDemo = "http_requests_total{namespace=\"demo\"}";

      Map<String, Object> responseDemo =
          webTestClient
              .get()
              .uri(buildQueryUri(queryDemo))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(responseDemo).isNotNull().containsEntry("status", "success");

      // Multi-group user queries observability namespace
      String queryObs = "http_requests_total{namespace=\"observability\"}";

      Map<String, Object> responseObs =
          webTestClient
              .get()
              .uri(buildQueryUri(queryObs))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(responseObs).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("Multi-group user query without filter returns union of permissions")
    void multiGroupUser_queryWithoutFilter_returnsUnion() {
      String query = "http_requests_total";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
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

      // Should see results from both demo AND observability
      List<String> namespaces =
          results.stream()
              .map(r -> ((Map<String, String>) r.get("metric")).get("namespace"))
              .distinct()
              .toList();

      assertThat(namespaces)
          .as("Multi-group user should see both allowed namespaces")
          .containsExactlyInAnyOrder("demo", "observability");
    }

    @Test
    @DisplayName("Multi-group user still cannot access production")
    void multiGroupUser_cannotAccessProduction() {
      String query = "http_requests_total{namespace=\"production\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Admin can query all namespaces")
    void admin_canQueryAllNamespaces() {
      String query = "http_requests_total";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
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

      List<String> namespaces =
          results.stream()
              .map(r -> ((Map<String, String>) r.get("metric")).get("namespace"))
              .distinct()
              .toList();

      // Admin should see all namespaces including production
      assertThat(namespaces).contains("demo", "observability", "production");
    }
  }

  @Nested
  @DisplayName("Complex Queries")
  class ComplexQueryTests {

    static java.util.stream.Stream<Arguments> simpleConstrainedQueries() {
      return java.util.stream.Stream.of(
          Arguments.of(
              "rate(http_requests_total{namespace=\"demo\"}[5m])",
              "Rate function with namespace constraint"),
          Arguments.of(
              "sum(http_requests_total{namespace=\"demo\"})", "Sum aggregation without grouping"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("simpleConstrainedQueries")
    void simpleQuery_withConstraint(String query, String description) {
      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("Aggregation by namespace and service")
    void aggregation_byNamespaceAndService() {
      String query = "sum(http_requests_total) by (namespace, service)";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
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

      // Verify aggregated results only contain allowed namespace
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        assertThat(metric).containsEntry("namespace", "demo");
      }
    }

    @Test
    @DisplayName("Range query with constraint")
    void rangeQuery_withConstraint() {
      String query = "http_requests_total{namespace=\"demo\"}";

      long endTime = System.currentTimeMillis() / 1000;
      long startTime = endTime - 3600; // 1 hour ago
      long step = 60;

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildRangeQueryUri(query, startTime, endTime, step))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("Histogram quantile query")
    void histogramQuantile_query() {
      String query =
          "histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket{namespace=\"demo\"}[5m])) by (le))";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("Query with multiple label selectors")
    void query_withMultipleLabelSelectors() {
      String query = "http_requests_total{namespace=\"demo\",service=\"order-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
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

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        assertThat(metric)
            .containsEntry("namespace", "demo")
            .containsEntry("service", "order-service");
      }
    }

    @Test
    @DisplayName("Arithmetic operations between metrics")
    void arithmeticOperations_betweenMetrics() {
      String query =
          "http_requests_total{namespace=\"demo\"} / on(namespace, service) http_request_duration_seconds_count{namespace=\"demo\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }
  }
}
