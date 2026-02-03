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
 * Scenario-based E2E tests for Tempo/TraceQL. Tests various scenarios including error handling,
 * multi-user access, and complex TraceQL queries using the default e2e-configmap.yaml
 * configuration.
 */
@Tag("e2e")
@Tag("tempo")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class TempoScenarioE2ETest {

  private static TempoTestContainer tempoContainer;

  @LocalServerPort private int port;

  private WebTestClient webTestClient;

  @BeforeAll
  static void startContainers() {
    tempoContainer = new TempoTestContainer();
    tempoContainer.start();
    tempoContainer.waitForTraces(Duration.ofSeconds(30));
  }

  @AfterAll
  static void stopContainers() {
    if (tempoContainer != null) {
      tempoContainer.stop();
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
    if (tempoContainer == null) {
      tempoContainer = new TempoTestContainer();
      tempoContainer.start();
      tempoContainer.waitForTraces(Duration.ofSeconds(30));
    }

    registry.add("proxy.services.tempo.url", tempoContainer::getTempoUrl);
    String configPath = E2ETestFixtures.getDefaultConfigPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildSearchUri(String query) {
    return uriBuilder -> uriBuilder.path("/tempo/api/search").query("q=" + encode(query)).build();
  }

  // ============ Nested Test Classes ============

  @Nested
  @DisplayName("Wildcard Pattern Constraints")
  class WildcardPatternTests {

    @Test
    @DisplayName("Query with service filter returns matching traces")
    void queryWithServiceFilter_returnsMatchingTraces() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Query without filter returns only allowed services")
    void queryWithoutFilter_returnsOnlyAllowedServices() {
      String query = "{}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should only see allowed services")
                .satisfiesAnyOf(
                    s -> assertThat(s).startsWith("demo-"),
                    s -> assertThat(s).isIn("demo-order-service", "demo-payment-service"));
          }
        }
      }
    }

    @Test
    @DisplayName("my-service-team can access observability services")
    void myServiceTeam_canAccessObservabilityServices() {
      String query = "{resource.service.name=\"observability-prometheus\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.MY_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }
  }

  @Nested
  @DisplayName("Error Handling")
  class ErrorHandlingTests {

    @Test
    @DisplayName("Invalid TraceQL syntax returns 400 Bad Request")
    void invalidTraceQLSyntax_returns400() {
      // Invalid TraceQL syntax - unclosed brace
      String query = "{resource.service.name=\"demo-order-service\"";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isBadRequest();
    }

    @Test
    @DisplayName("Unauthorized service access returns 403 Forbidden")
    void unauthorizedServiceAccess_returns403() {
      // order-service-team tries to query production service
      String query = "{resource.service.name=\"production-critical-app\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Cross-team service access is forbidden")
    void crossTeamServiceAccess_isForbidden() {
      // order-service-team tries to query observability service
      String query = "{resource.service.name=\"observability-prometheus\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Missing authorization header returns 401")
    void missingAuthHeader_returns401() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      webTestClient.get().uri(buildSearchUri(query)).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("Invalid token returns 401")
    void invalidToken_returns401() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
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
    @DisplayName("Multi-group user can access both demo and observability services")
    void multiGroupUser_canAccessBothServices() {
      // Multi-group user queries demo service
      String queryDemo = "{resource.service.name=\"demo-order-service\"}";

      Map<String, Object> responseDemo =
          webTestClient
              .get()
              .uri(buildSearchUri(queryDemo))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(responseDemo).isNotNull();

      // Multi-group user queries observability service
      String queryObs = "{resource.service.name=\"observability-prometheus\"}";

      Map<String, Object> responseObs =
          webTestClient
              .get()
              .uri(buildSearchUri(queryObs))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(responseObs).isNotNull();
    }

    @Test
    @DisplayName("Multi-group user still cannot access production")
    void multiGroupUser_cannotAccessProduction() {
      String query = "{resource.service.name=\"production-critical-app\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Admin can query all services")
    void admin_canQueryAllServices() {
      String query = "{}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Admin can query production services")
    void admin_canQueryProductionServices() {
      String query = "{resource.service.name=\"production-critical-app\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }
  }

  @Nested
  @DisplayName("Complex TraceQL Queries")
  class ComplexTraceQLTests {

    @Test
    @DisplayName("TraceQL with span name filter is constrained")
    void traceQLWithSpanNameFilter_isConstrained() {
      String query = "{name=\"order\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should only see allowed services")
                .satisfiesAnyOf(
                    s -> assertThat(s).startsWith("demo-"),
                    s -> assertThat(s).isIn("demo-order-service", "demo-payment-service"));
          }
        }
      }
    }

    static java.util.stream.Stream<Arguments> simpleTraceQLQueries() {
      return java.util.stream.Stream.of(
          Arguments.of(
              "{resource.service.name=\"demo-order-service\" && name=\"order\"}",
              "multiple attribute conditions"),
          Arguments.of(
              "{resource.service.name=\"demo-order-service\" && duration > 1ms}",
              "duration filter"),
          Arguments.of(
              "{resource.service.name=\"demo-order-service\" && status = ok}", "status filter"),
          Arguments.of(
              "{resource.service.name=\"demo-order-service\" && kind = server}",
              "span kind filter"),
          Arguments.of(
              "{resource.service.name=\"demo-order-service\" && name =~ \".*order.*\"}",
              "regex name pattern"));
    }

    @ParameterizedTest(name = "TraceQL query with {1}")
    @MethodSource("simpleTraceQLQueries")
    void traceQLQuery_isConstrained(String query, String filterType) {
      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("Query for specific allowed service")
    void query_forSpecificAllowedService() {
      String query = "{resource.service.name=\"demo-payment-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName).isEqualTo("demo-payment-service");
          }
        }
      }
    }

    @Test
    @DisplayName("Query with OR condition on services - currently not supported by Janus")
    void query_withOrConditionOnServices() {
      // Note: OR conditions in TraceQL queries are currently not fully supported by Janus
      // as the label processor doesn't handle || operator properly for constraint validation.
      // This test documents the current behavior (returns 403) until OR support is implemented.
      String query =
          "{resource.service.name=\"demo-order-service\" || resource.service.name=\"demo-payment-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("Query with span count filter")
    void query_withSpanCountFilter() {
      // Query traces with at least 1 span
      String query = "{resource.service.name=\"demo-order-service\"} | count() > 0";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }
  }
}
