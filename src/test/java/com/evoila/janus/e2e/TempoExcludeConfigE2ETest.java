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
 * E2E tests for Tempo/TraceQL using the EXCLUDE configuration. Tests include/exclude pattern
 * behavior where teams have explicit lists with some services excluded.
 */
@Tag("e2e")
@Tag("tempo")
@Tag("exclude-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class TempoExcludeConfigE2ETest {

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
    // Use EXCLUDE config with explicit include lists
    String configPath = E2ETestFixtures.getExcludeConfigPath();
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
  @DisplayName("Include List Tests")
  class IncludeListTests {

    @Test
    @DisplayName("order-service-team can access demo-order-service")
    void orderTeam_canAccessDemoOrderService() {
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
    @DisplayName("order-service-team can access demo-payment-service")
    void orderTeam_canAccessDemoPaymentService() {
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
    @DisplayName("order-service-team query returns only allowed services")
    void orderTeam_queryReturnsOnlyAllowedServices() {
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
            assertThat(serviceName).as("Service should be in allowed list").isIn(allowedServices);
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Exclude List Tests (via omission)")
  class ExcludeListTests {

    @Test
    @DisplayName("order-service-team cannot access demo-sensitive-service (blocked with 403)")
    void orderTeam_cannotAccessDemoSensitiveService() {
      // demo-sensitive-service is NOT in the include list for order-service-team, access is blocked
      String query = "{resource.service.name=\"demo-sensitive-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("order-service-team cannot access observability services (blocked with 403)")
    void orderTeam_cannotAccessObservabilityServices() {
      // observability-prometheus is NOT in the include list for order-service-team, access is
      // blocked
      String query = "{resource.service.name=\"observability-prometheus\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }
  }

  @Nested
  @DisplayName("Exclude Team Tests")
  class ExcludeTeamTests {

    @Test
    @DisplayName("exclude-team can access demo and observability services")
    void excludeTeam_canAccessAllowedServices() {
      String query = "{}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.EXCLUDE_TEAM_TOKEN)
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
                .as("Service should be in exclude-team's allowed list")
                .isIn(allowedServices);
          }
        }
      }
    }

    @Test
    @DisplayName("exclude-team cannot access production services (blocked with 403)")
    void excludeTeam_cannotAccessProductionServices() {
      // production-critical-app is NOT in the include list for exclude-team, access is blocked
      String query = "{resource.service.name=\"production-critical-app\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.EXCLUDE_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("exclude-team cannot access staging-order-service (blocked with 403)")
    void excludeTeam_cannotAccessStagingOrderService() {
      // staging-order-service is NOT in the include list for exclude-team, access is blocked
      String query = "{resource.service.name=\"staging-order-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.EXCLUDE_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group-user gets union of services from both teams")
    void multiGroupUser_getsUnionOfServices() {
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

      // Union of order-service-team and my-service-team services
      Set<String> allowedServices =
          Set.of(
              "demo-order-service",
              "demo-payment-service",
              "observability-prometheus",
              "observability-grafana",
              "staging-order-service");

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Service should be in the union of both teams' allowed services")
                .isIn(allowedServices);
          }
        }
      }
    }
  }
}
