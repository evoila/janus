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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * E2E tests for Loki/LogQL using the EXCLUDE configuration. Tests include/exclude pattern behavior
 * where teams have explicit lists with some namespaces excluded.
 */
@Tag("e2e")
@Tag("loki")
@Tag("exclude-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class LokiExcludeConfigE2ETest {

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
    // Use EXCLUDE config with explicit include lists
    String configPath = E2ETestFixtures.getExcludeConfigPath();
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

  @Nested
  @DisplayName("Include List Tests")
  class IncludeListTests {

    @ParameterizedTest(name = "order-service-team can access {0} namespace")
    @ValueSource(strings = {"demo", "demo-orders", "demo-payments"})
    void orderTeam_canAccessAllowedNamespace(String namespace) {
      String query = "{k8s_namespace_name=\"" + namespace + "\"}";

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
    @DisplayName("order-service-team query returns only allowed namespaces")
    void orderTeam_queryReturnsOnlyAllowedNamespaces() {
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

      Set<String> allowedNamespaces = Set.of("demo", "demo-orders", "demo-payments");

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace).as("Namespace should be in allowed list").isIn(allowedNamespaces);
      }
    }
  }

  @Nested
  @DisplayName("Exclude List Tests (via omission)")
  class ExcludeListTests {

    @Test
    @DisplayName("order-service-team cannot access demo-sensitive (blocked with 403)")
    void orderTeam_cannotAccessDemoSensitive() {
      // demo-sensitive is NOT in the include list for order-service-team, access is blocked
      String query = "{k8s_namespace_name=\"demo-sensitive\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ORDER_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("order-service-team cannot access observability (blocked with 403)")
    void orderTeam_cannotAccessObservability() {
      // observability is NOT in the include list for order-service-team, access is blocked
      String query = "{k8s_namespace_name=\"observability\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
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
    @DisplayName("exclude-team can access demo namespaces and observability")
    void excludeTeam_canAccessAllowedNamespaces() {
      String query = "{job=\"test-logs\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.EXCLUDE_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");

      Set<String> allowedNamespaces =
          Set.of("demo", "demo-orders", "demo-payments", "observability");

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should be in exclude-team's allowed list")
            .isIn(allowedNamespaces);
      }
    }

    @Test
    @DisplayName("exclude-team cannot access production (blocked with 403)")
    void excludeTeam_cannotAccessProduction() {
      // production is NOT in the include list for exclude-team, access is blocked
      String query = "{k8s_namespace_name=\"production\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.EXCLUDE_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName("exclude-team cannot access staging-orders (blocked with 403)")
    void excludeTeam_cannotAccessStagingOrders() {
      // staging-orders is NOT in the include list for exclude-team, access is blocked
      String query = "{k8s_namespace_name=\"staging-orders\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
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
    @DisplayName("multi-group-user gets union of namespaces from both teams")
    void multiGroupUser_getsUnionOfNamespaces() {
      String query = "{job=\"test-logs\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");

      // Union of order-service-team (demo, demo-orders, demo-payments)
      // and my-service-team (observability, staging-orders)
      Set<String> allowedNamespaces =
          Set.of("demo", "demo-orders", "demo-payments", "observability", "staging-orders");

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should be in the union of both teams' allowed namespaces")
            .isIn(allowedNamespaces);
      }
    }
  }
}
