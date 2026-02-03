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
 * Scenario-based E2E tests for Loki/LogQL. Tests various scenarios including error handling,
 * multi-user access, and complex LogQL queries using the default e2e-configmap.yaml configuration.
 */
@Tag("e2e")
@Tag("loki")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class LokiScenarioE2ETest {

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
    if (lokiContainer == null) {
      lokiContainer = new LokiTestContainer();
      lokiContainer.start();
      lokiContainer.waitForLogs(Duration.ofSeconds(60));
    }

    registry.add("proxy.services.loki.url", lokiContainer::getLokiUrl);
    String configPath = E2ETestFixtures.getDefaultConfigPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildQueryUri(String query) {
    return uriBuilder ->
        uriBuilder.path("/loki/api/v1/query").query("query=" + encode(query)).build();
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildRangeQueryUri(String query, long start, long end) {
    return uriBuilder ->
        uriBuilder
            .path("/loki/api/v1/query_range")
            .query("query=" + encode(query) + "&start=" + start + "&end=" + end)
            .build();
  }

  // ============ Nested Test Classes ============

  @Nested
  @DisplayName("Wildcard Pattern Constraints")
  class WildcardPatternTests {

    @Test
    @DisplayName("Query with namespace filter returns matching logs")
    void queryWithNamespaceFilter_returnsMatchingLogs() {
      String query = "{k8s_namespace_name=\"demo\"}";

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
    @DisplayName("Query without namespace filter gets constraint added")
    void queryWithoutNamespaceFilter_getsConstraintAdded() {
      String query = "{job=\"test-logs\"}";

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
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        assertThat(stream)
            .as("Should only see allowed namespace")
            .containsEntry("k8s_namespace_name", "demo");
      }
    }

    @Test
    @DisplayName("my-service-team can access observability namespace")
    void myServiceTeam_canAccessObservabilityNamespace() {
      String query = "{k8s_namespace_name=\"observability\"}";

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
    @DisplayName("Invalid LogQL syntax returns 400 Bad Request")
    void invalidLogQLSyntax_returns400() {
      // Invalid LogQL syntax - unclosed brace
      String query = "{k8s_namespace_name=\"demo\"";

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
      String query = "{k8s_namespace_name=\"production\"}";

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
      // order-service-team tries to query observability namespace
      String query = "{k8s_namespace_name=\"observability\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Missing authorization header returns 401")
    void missingAuthHeader_returns401() {
      String query = "{k8s_namespace_name=\"demo\"}";

      webTestClient.get().uri(buildQueryUri(query)).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Invalid token returns 401")
    void invalidToken_returns401() {
      String query = "{k8s_namespace_name=\"demo\"}";

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
      String queryDemo = "{k8s_namespace_name=\"demo\"}";

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
      String queryObs = "{k8s_namespace_name=\"observability\"}";

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
    @DisplayName("Multi-group user query returns union of permissions")
    void multiGroupUser_queryReturnsUnion() {
      String query = "{job=\"test-logs\"}";

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
              .map(r -> ((Map<String, String>) r.get("stream")).get("k8s_namespace_name"))
              .distinct()
              .toList();

      assertThat(namespaces)
          .as("Multi-group user should see both allowed namespaces")
          .containsExactlyInAnyOrder("demo", "observability");
    }

    @Test
    @DisplayName("Multi-group user still cannot access production")
    void multiGroupUser_cannotAccessProduction() {
      String query = "{k8s_namespace_name=\"production\"}";

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
      String query = "{job=\"test-logs\"}";

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
              .map(r -> ((Map<String, String>) r.get("stream")).get("k8s_namespace_name"))
              .distinct()
              .toList();

      // Admin should see all namespaces
      assertThat(namespaces).contains("demo", "observability", "production");
    }
  }

  @Nested
  @DisplayName("Complex LogQL Queries")
  class ComplexLogQLTests {

    static java.util.stream.Stream<Arguments> simpleLogQLQueries() {
      return java.util.stream.Stream.of(
          Arguments.of("{k8s_namespace_name=\"demo\"} |= \"order\"", "line filter"),
          Arguments.of("{k8s_namespace_name=\"demo\"} |~ \"order|payment\"", "regex line filter"),
          Arguments.of("{k8s_namespace_name=\"demo\"} != \"error\"", "negative line filter"));
    }

    @ParameterizedTest(name = "LogQL with {1} is constrained")
    @MethodSource("simpleLogQLQueries")
    void logQLWithFilter_isConstrained(String query, String filterType) {
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
    @DisplayName("Range query with constraint")
    void rangeQuery_withConstraint() {
      String query = "{k8s_namespace_name=\"demo\"}";

      long endTime = System.currentTimeMillis() * 1_000_000L; // nanoseconds
      long startTime = endTime - (3600L * 1_000_000_000L); // 1 hour ago

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildRangeQueryUri(query, startTime, endTime))
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
      String query = "{k8s_namespace_name=\"demo\",app=\"order-service\"}";

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
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        assertThat(stream)
            .containsEntry("k8s_namespace_name", "demo")
            .containsEntry("app", "order-service");
      }
    }

    static java.util.stream.Stream<Arguments> metricAndAdvancedQueries() {
      return java.util.stream.Stream.of(
          Arguments.of("{k8s_namespace_name=\"demo\",app=~\".*service\"}", "label regex selector"),
          Arguments.of("count_over_time({k8s_namespace_name=\"demo\"}[5m])", "count_over_time"),
          Arguments.of("rate({k8s_namespace_name=\"demo\"}[5m])", "rate"),
          Arguments.of("bytes_over_time({k8s_namespace_name=\"demo\"}[5m])", "bytes_over_time"),
          Arguments.of(
              "sum(count_over_time({k8s_namespace_name=\"demo\"}[5m]))", "sum aggregation"));
    }

    @ParameterizedTest(name = "LogQL {1} query is constrained")
    @MethodSource("metricAndAdvancedQueries")
    void metricQuery_isConstrained(String query, String queryType) {
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
