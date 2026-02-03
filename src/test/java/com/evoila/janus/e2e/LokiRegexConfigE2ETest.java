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
 * E2E tests for Loki/LogQL using the REGEX configuration. Tests complex regex patterns with ~
 * prefix including anchors (^, $), character classes ([a-z]), and quantifiers.
 */
@Tag("e2e")
@Tag("loki")
@Tag("regex-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class LokiRegexConfigE2ETest {

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
    // Use REGEX config with complex regex patterns
    String configPath = E2ETestFixtures.getRegexConfigPath();
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
  @DisplayName("Anchored Start Pattern Tests (^demo.*$)")
  class AnchoredStartPatternTests {

    @Test
    @DisplayName("order-service-team can access demo namespace (matches ^demo.*$)")
    void orderTeam_canAccessDemo() {
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
    @DisplayName("order-service-team can access demo-orders (matches ^demo.*$)")
    void orderTeam_canAccessDemoOrders() {
      String query = "{k8s_namespace_name=\"demo-orders\"}";

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
    @DisplayName("order-service-team query returns only namespaces starting with 'demo'")
    void orderTeam_queryReturnsOnlyDemoNamespaces() {
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

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should start with 'demo' to match ^demo.*$")
            .startsWith("demo");
      }
    }

    @Test
    @DisplayName(
        "order-service-team cannot access non-demo namespace (anchor prevents partial match)")
    void orderTeam_cannotAccessNonDemoNamespace() {
      String query = "{k8s_namespace_name=\"observability\"}";

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

      // Results should only contain namespaces starting with 'demo'
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) response.get("data");
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("result");

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Should not contain observability, only demo* namespaces")
            .startsWith("demo");
      }
    }
  }

  @Nested
  @DisplayName("Anchored End Pattern Tests (.*-service$)")
  class AnchoredEndPatternTests {

    @Test
    @DisplayName("my-service-team can access order-service (matches .*-service$)")
    void myTeam_canAccessOrderService() {
      String query = "{k8s_namespace_name=\"order-service\"}";

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
    @DisplayName("my-service-team can access payment-service (matches .*-service$)")
    void myTeam_canAccessPaymentService() {
      String query = "{k8s_namespace_name=\"payment-service\"}";

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
    @DisplayName("my-service-team query returns only namespaces ending with '-service'")
    void myTeam_queryReturnsOnlyServiceNamespaces() {
      String query = "{job=\"test-logs\"}";

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

      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should end with '-service' to match .*-service$")
            .endsWith("-service");
      }
    }
  }

  @Nested
  @DisplayName("Character Class Pattern Tests (^[a-z]+-service$)")
  class CharacterClassPatternTests {

    @Test
    @DisplayName("regex-team can access order-service (matches ^[a-z]+-service$)")
    void regexTeam_canAccessOrderService() {
      String query = "{k8s_namespace_name=\"order-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("regex-team can access payment-service (matches ^[a-z]+-service$)")
    void regexTeam_canAccessPaymentService() {
      String query = "{k8s_namespace_name=\"payment-service\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull().containsEntry("status", "success");
    }

    @Test
    @DisplayName("regex-team query returns only namespaces matching [a-z]+-service pattern")
    void regexTeam_queryReturnsOnlyMatchingNamespaces() {
      String query = "{job=\"test-logs\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildQueryUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
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
        String namespace = stream.get("k8s_namespace_name");
        // ^[a-z]+-service$ means: starts with lowercase letters, then hyphen, then "service"
        assertThat(namespace)
            .as("Namespace should match ^[a-z]+-service$ pattern")
            .matches("^[a-z]+-service$");
      }
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group-user gets union of ^demo.*$ AND .*-service$ patterns")
    void multiGroupUser_getsUnionOfPatterns() {
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

      // Results should match either ^demo.*$ OR .*-service$ pattern
      for (Map<String, Object> result : results) {
        @SuppressWarnings("unchecked")
        Map<String, String> stream = (Map<String, String>) result.get("stream");
        String namespace = stream.get("k8s_namespace_name");
        assertThat(namespace)
            .as("Namespace should match ^demo.*$ OR .*-service$ pattern")
            .satisfiesAnyOf(
                ns -> assertThat(ns).startsWith("demo"), ns -> assertThat(ns).endsWith("-service"));
      }
    }

    @Test
    @DisplayName("multi-group-user can access demo-orders (matches ^demo.*$)")
    void multiGroupUser_canAccessDemoOrders() {
      String query = "{k8s_namespace_name=\"demo-orders\"}";

      webTestClient
          .get()
          .uri(buildQueryUri(query))
          .header("Authorization", "Bearer " + E2ETestFixtures.MULTI_GROUP_USER_TOKEN)
          .exchange()
          .expectStatus()
          .isOk();
    }

    @Test
    @DisplayName("multi-group-user can access payment-service (matches .*-service$)")
    void multiGroupUser_canAccessPaymentService() {
      String query = "{k8s_namespace_name=\"payment-service\"}";

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
