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
 * E2E tests for Thanos/PromQL using the PERMISSIVE configuration. Tests wildcard pattern matching
 * like prefix patterns (demo-*), suffix patterns (*-service), and middle patterns (*order*).
 */
@Tag("e2e")
@Tag("thanos")
@Tag("permissive-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class ThanosPermissiveConfigE2ETest {

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
    // Use PERMISSIVE config with wildcard patterns
    String configPath = E2ETestFixtures.getPermissiveConfigPath();
    registry.add("label-store.configmap.path", () -> configPath);
  }

  private String encode(String value) {
    return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
  }

  private java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI>
      buildQueryUri(String query) {
    return uriBuilder -> uriBuilder.path("/api/v1/query").query("query=" + encode(query)).build();
  }

  @Nested
  @DisplayName("Prefix Pattern Tests (demo-*)")
  class PrefixPatternTests {

    @Test
    @DisplayName("order-service-team can access demo-orders namespace (matches demo-*)")
    void orderTeam_canAccessDemoOrders() {
      String query = "http_requests_total{namespace=\"demo-orders\"}";

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
    @DisplayName("order-service-team can access demo-payments namespace (matches demo-*)")
    void orderTeam_canAccessDemoPayments() {
      String query = "http_requests_total{namespace=\"demo-payments\"}";

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
    @DisplayName(
        "order-service-team query for staging-orders returns only demo-* data (enhancement mode)")
    void orderTeam_queryForStagingOrders_returnsOnlyDemoData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
      // The query is replaced with the constraint pattern, so we get data matching the pattern.
      String query = "http_requests_total{namespace=\"staging-orders\"}";

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

      // Verify results only contain demo-* namespaces (not staging-orders)
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace)
            .as("Should not contain staging-orders, only demo-* namespaces")
            .startsWith("demo-");
      }
    }

    @Test
    @DisplayName("order-service-team query without filter returns only demo-* namespaces")
    void orderTeam_queryWithoutFilter_returnsOnlyDemoNamespaces() {
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

      // All results should have namespace starting with "demo-"
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace).as("Namespace should match demo-* pattern").startsWith("demo-");
      }
    }
  }

  @Nested
  @DisplayName("Suffix Pattern Tests (*-service)")
  class SuffixPatternTests {

    @Test
    @DisplayName("my-service-team can access order-service namespace (matches *-service)")
    void myTeam_canAccessOrderService() {
      String query = "http_requests_total{namespace=\"order-service\"}";

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
    @DisplayName("my-service-team can access payment-service namespace (matches *-service)")
    void myTeam_canAccessPaymentService() {
      String query = "http_requests_total{namespace=\"payment-service\"}";

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
    @DisplayName(
        "my-service-team query for demo-orders returns only *-service data (enhancement mode)")
    void myTeam_queryForDemoOrders_returnsOnlyServiceData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
      String query = "http_requests_total{namespace=\"demo-orders\"}";

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

      // Verify results only contain *-service namespaces (not demo-orders)
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace)
            .as("Should not contain demo-orders, only *-service namespaces")
            .endsWith("-service");
      }
    }

    @Test
    @DisplayName("my-service-team query without filter returns only *-service namespaces")
    void myTeam_queryWithoutFilter_returnsOnlyServiceNamespaces() {
      String query = "http_requests_total";

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

      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      // All results should have namespace ending with "-service"
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace).as("Namespace should match *-service pattern").endsWith("-service");
      }
    }
  }

  @Nested
  @DisplayName("Wildcard Team Tests (*order*)")
  class WildcardTeamTests {

    @ParameterizedTest(name = "wildcard-team can access {0} (contains ''order'')")
    @ValueSource(strings = {"demo-orders", "staging-orders", "order-service"})
    void wildcardTeam_canAccessNamespaceContainingOrder(String namespace) {
      String query = "http_requests_total{namespace=\"" + namespace + "\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName(
        "wildcard-team query for demo-payments returns only *order* data (enhancement mode)")
    void wildcardTeam_queryForDemoPayments_returnsOnlyOrderData() {
      // With regex pattern constraints, Janus enhances queries rather than blocking.
      String query = "http_requests_total{namespace=\"demo-payments\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");

      // Verify results only contain *order* namespaces (not demo-payments)
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace)
            .as("Should not contain demo-payments, only *order* namespaces")
            .containsIgnoringCase("order");
      }
    }

    @Test
    @DisplayName("wildcard-team query returns only namespaces containing 'order'")
    void wildcardTeam_queryWithoutFilter_returnsOnlyOrderNamespaces() {
      String query = "http_requests_total";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.WILDCARD_TEAM_TOKEN)
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

      // All results should have namespace containing "order"
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace).as("Namespace should contain 'order'").containsIgnoringCase("order");
      }
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group user gets union of demo-* AND *-service patterns")
    void multiGroupUser_getsUnionOfPatterns() {
      String query = "http_requests_total";

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

      // Results should match either demo-* OR *-service pattern
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> metric = (Map<String, String>) result.get("metric");
        String namespace = metric.get("namespace");
        assertThat(namespace)
            .as("Namespace should match demo-* OR *-service pattern")
            .satisfiesAnyOf(
                ns -> assertThat(ns).startsWith("demo-"),
                ns -> assertThat(ns).endsWith("-service"));
      }
    }

    @Test
    @DisplayName("multi-group user can access demo-orders (matches demo-*)")
    void multiGroupUser_canAccessDemoOrders() {
      String query = "http_requests_total{namespace=\"demo-orders\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("multi-group user can access payment-service (matches *-service)")
    void multiGroupUser_canAccessPaymentService() {
      String query = "http_requests_total{namespace=\"payment-service\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }
  }
}
