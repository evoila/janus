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
 * E2E tests for Loki/LogQL using the STRICT configuration. Tests exact match only constraints with
 * no wildcards or patterns - each team can only see their specific namespace.
 */
@Tag("e2e")
@Tag("loki")
@Tag("strict-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class LokiStrictConfigE2ETest {

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
    // Use STRICT config with exact match only
    String configPath = E2ETestFixtures.getStrictConfigPath();
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
  @DisplayName("Exact Match Tests")
  class ExactMatchTests {

    @Test
    @DisplayName("order-service-team can access exact namespace=demo")
    void orderTeam_canAccessExactDemoNamespace() {
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
    @DisplayName("my-service-team can access exact namespace=observability")
    void myTeam_canAccessExactObservabilityNamespace() {
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

    @Test
    @DisplayName("order-service-team query without filter returns only demo namespace")
    void orderTeam_queryWithoutFilter_returnsOnlyDemoNamespace() {
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

      // All results should have exactly namespace=demo
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should be exactly 'demo' in strict mode")
            .isEqualTo("demo");
      }
    }
  }

  @Nested
  @DisplayName("Cross-Namespace Restriction Tests")
  class CrossNamespaceRestrictionTests {

    @Test
    @DisplayName("order-service-team cannot access observability (blocked with 403)")
    void orderTeam_cannotAccessObservability() {
      // In strict mode with exact matches, Janus blocks access to unauthorized namespaces
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
    @DisplayName("my-service-team cannot access demo (blocked with 403)")
    void myTeam_cannotAccessDemo() {
      // In strict mode with exact matches, Janus blocks access to unauthorized namespaces
      String query = "{k8s_namespace_name=\"demo\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MY_TEAM_TOKEN)
          .exchange()
          .expectStatus()
          .isForbidden();
    }

    @Test
    @DisplayName(
        "order-service-team cannot access demo-orders (blocked - no pattern matching in strict)")
    void orderTeam_cannotAccessDemoOrders() {
      // In strict mode, demo-orders is NOT the same as demo, so access is blocked
      String query = "{k8s_namespace_name=\"demo-orders\"}";

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
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group-user can access both demo AND observability")
    void multiGroupUser_canAccessBothNamespaces() {
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

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      // Results should only contain demo OR observability (exact match, no patterns)
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should be exactly 'demo' or 'observability'")
            .isIn("demo", "observability");
      }
    }

    @Test
    @DisplayName("multi-group-user can access demo namespace")
    void multiGroupUser_canAccessDemo() {
      String query = "{k8s_namespace_name=\"demo\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("multi-group-user can access observability namespace")
    void multiGroupUser_canAccessObservability() {
      String query = "{k8s_namespace_name=\"observability\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
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
    @DisplayName("admin can access all namespaces")
    void admin_canAccessAllNamespaces() {
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
    }

    @Test
    @DisplayName("admin can access demo namespace")
    void admin_canAccessDemo() {
      String query = "{k8s_namespace_name=\"demo\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("admin can access observability namespace")
    void admin_canAccessObservability() {
      String query = "{k8s_namespace_name=\"observability\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.ADMIN_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }
  }
}
