package com.evoila.janus.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.evoila.janus.app.JanusApplication;
import com.evoila.janus.integration.MockJwtConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * E2E tests for Tempo/TraceQL using the STRICT configuration. Tests exact match only constraints
 * with no wildcards or patterns - each team can only see their specific services.
 */
@Tag("e2e")
@Tag("tempo")
@Tag("strict-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class TempoStrictConfigE2ETest {

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
    // Use STRICT config with exact match only
    String configPath = E2ETestFixtures.getStrictConfigPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildSearchUri(String query) {
    return uriBuilder -> uriBuilder.path("/tempo/api/search").query("q=" + encode(query)).build();
  }

  @Nested
  @DisplayName("Exact Match Tests")
  class ExactMatchTests {

    @Test
    @DisplayName("order-service-team can access exact service=demo-order-service")
    void orderTeam_canAccessExactDemoOrderService() {
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
    @DisplayName("order-service-team can access exact service=demo-payment-service")
    void orderTeam_canAccessExactDemoPaymentService() {
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
    }

    @Test
    @DisplayName("my-service-team can access exact service=observability-prometheus")
    void myTeam_canAccessExactObservabilityPrometheus() {
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

    @Test
    @DisplayName("order-service-team query without filter returns only allowed services")
    void orderTeam_queryWithoutFilter_returnsOnlyAllowedServices() {
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

      Set<String> allowedServices = Set.of("demo-order-service", "demo-payment-service");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Service should be exactly in allowed list in strict mode")
                .isIn(allowedServices);
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Cross-Service Restriction Tests")
  class CrossServiceRestrictionTests {

    @Test
    @DisplayName("order-service-team cannot access observability services (blocked with 403)")
    void orderTeam_cannotAccessObservabilityServices() {
      // In strict mode with exact matches, Janus blocks access to unauthorized services
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
    @DisplayName("my-service-team cannot access demo services (blocked with 403)")
    void myTeam_cannotAccessDemoServices() {
      // In strict mode with exact matches, Janus blocks access to unauthorized services
      String query = "{resource.service.name=\"demo-order-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MY_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group-user can access both demo AND observability services")
    void multiGroupUser_canAccessBothServices() {
      String query = "{}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();

      Set<String> allowedServices =
          Set.of(
              "demo-order-service",
              "demo-payment-service",
              "observability-prometheus",
              "observability-grafana");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Service should be in the combined allowed list")
                .isIn(allowedServices);
          }
        }
      }
    }

    @Test
    @DisplayName("multi-group-user can access demo-order-service")
    void multiGroupUser_canAccessDemoOrderService() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("multi-group-user can access observability-prometheus")
    void multiGroupUser_canAccessObservabilityPrometheus() {
      String query = "{resource.service.name=\"observability-prometheus\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }
  }

  @Nested
  @DisplayName("Admin Access Tests")
  class AdminAccessTests {

    @Test
    @DisplayName("admin can access all services")
    void admin_canAccessAllServices() {
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
    @DisplayName("admin can access demo-order-service")
    void admin_canAccessDemoOrderService() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("admin can access observability-prometheus")
    void admin_canAccessObservabilityPrometheus() {
      String query = "{resource.service.name=\"observability-prometheus\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }
  }
}
