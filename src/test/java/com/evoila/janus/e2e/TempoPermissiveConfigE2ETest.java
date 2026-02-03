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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * E2E tests for Tempo/TraceQL using the PERMISSIVE configuration. Tests wildcard pattern matching
 * like prefix patterns (demo-*), suffix patterns (*-service), and middle patterns (*order*).
 */
@Tag("e2e")
@Tag("tempo")
@Tag("permissive-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class TempoPermissiveConfigE2ETest {

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
    // Use PERMISSIVE config with wildcard patterns
    String configPath = E2ETestFixtures.getPermissiveConfigPath();
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
  @DisplayName("Prefix Pattern Tests (demo-*)")
  class PrefixPatternTests {

    @Test
    @DisplayName("order-service-team can access demo-order-service (matches demo-*)")
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
    @DisplayName("order-service-team can access demo-payment-service (matches demo-*)")
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
    @DisplayName(
        "order-service-team query for staging-order-service returns only demo-* data (enhancement mode)")
    void orderTeam_queryForStagingOrderService_returnsOnlyDemoData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
      String query = "{resource.service.name=\"staging-order-service\"}";

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

      // Verify results only contain demo-* services
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should not contain staging-order-service, only demo-* services")
                .startsWith("demo-");
          }
        }
      }
    }

    @Test
    @DisplayName("order-service-team query without filter returns only demo-* services")
    void orderTeam_queryWithoutFilter_returnsOnlyDemoServices() {
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
            assertThat(serviceName).as("Service should match demo-* pattern").startsWith("demo-");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Suffix Pattern Tests (*-service)")
  class SuffixPatternTests {

    @Test
    @DisplayName("my-service-team can access user-service (matches *-service)")
    void myTeam_canAccessUserService() {
      String query = "{resource.service.name=\"user-service\"}";

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
    @DisplayName("my-service-team can access auth-service (matches *-service)")
    void myTeam_canAccessAuthService() {
      String query = "{resource.service.name=\"auth-service\"}";

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
    @DisplayName(
        "my-service-team query for observability-prometheus returns only *-service data (enhancement mode)")
    void myTeam_queryForObservabilityPrometheus_returnsOnlyServiceData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
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

      // Verify results only contain *-service services
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should not contain observability-prometheus, only *-service services")
                .endsWith("-service");
          }
        }
      }
    }

    @Test
    @DisplayName("my-service-team query without filter returns only *-service services")
    void myTeam_queryWithoutFilter_returnsOnlyServiceServices() {
      String query = "{}";

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

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Service should match *-service pattern")
                .endsWith("-service");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Wildcard Team Tests (*order*)")
  class WildcardTeamTests {

    @Test
    @DisplayName("wildcard-team can access demo-order-service (contains 'order')")
    void wildcardTeam_canAccessDemoOrderService() {
      String query = "{resource.service.name=\"demo-order-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("wildcard-team can access staging-order-service (contains 'order')")
    void wildcardTeam_canAccessStagingOrderService() {
      String query = "{resource.service.name=\"staging-order-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName(
        "wildcard-team query for demo-payment-service returns only *order* data (enhancement mode)")
    void wildcardTeam_queryForDemoPaymentService_returnsOnlyOrderData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
      String query = "{resource.service.name=\"demo-payment-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();

      // Verify results only contain *order* services
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should not contain demo-payment-service, only *order* services")
                .containsIgnoringCase("order");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group user gets union of demo-* AND *-service patterns")
    void multiGroupUser_getsUnionOfPatterns() {
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

      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Service should match demo-* OR *-service pattern")
                .satisfiesAnyOf(
                    sn -> assertThat(sn).startsWith("demo-"),
                    sn -> assertThat(sn).endsWith("-service"));
          }
        }
      }
    }

    @Test
    @DisplayName("multi-group user can access demo-order-service (matches demo-*)")
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
    @DisplayName("multi-group user can access user-service (matches *-service)")
    void multiGroupUser_canAccessUserService() {
      String query = "{resource.service.name=\"user-service\"}";

      webTestClient
          .get()
          .uri(buildSearchUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }
  }
}
