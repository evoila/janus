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
 * E2E tests for Tempo/TraceQL using the REGEX configuration. Tests complex regex patterns with ~
 * prefix including anchors (^, $), character classes ([a-z]), and quantifiers.
 */
@Tag("e2e")
@Tag("tempo")
@Tag("regex-config")
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(MockJwtConfig.class)
class TempoRegexConfigE2ETest {

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
    // Use REGEX config with complex regex patterns
    String configPath = E2ETestFixtures.getRegexConfigPath();
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
  @DisplayName("Anchored Start Pattern Tests (^demo-.*$)")
  class AnchoredStartPatternTests {

    @Test
    @DisplayName("order-service-team can access demo-order-service (matches ^demo-.*$)")
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
    @DisplayName("order-service-team can access demo-payment-service (matches ^demo-.*$)")
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
    @DisplayName("order-service-team query returns only services starting with 'demo-'")
    void orderTeam_queryReturnsOnlyDemoServices() {
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
                .as("Service should start with 'demo-' to match ^demo-.*$")
                .startsWith("demo-");
          }
        }
      }
    }

    @Test
    @DisplayName(
        "order-service-team cannot access non-demo service (anchor prevents partial match)")
    void orderTeam_cannotAccessNonDemoService() {
      String query = "{resource.service.name=\"observability-prometheus\"}";

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

      // Results should only contain services starting with 'demo-'
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> traces = (List<Map<String, Object>>) response.get("traces");

      if (traces != null && !traces.isEmpty()) {
        for (Map<String, Object> trace : traces) {
          String serviceName = (String) trace.get("rootServiceName");
          if (serviceName != null) {
            assertThat(serviceName)
                .as("Should not contain observability-prometheus, only demo-* services")
                .startsWith("demo-");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Anchored End Pattern Tests (.*-service$)")
  class AnchoredEndPatternTests {

    @Test
    @DisplayName("my-service-team can access user-service (matches .*-service$)")
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
    @DisplayName("my-service-team can access auth-service (matches .*-service$)")
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
    @DisplayName("my-service-team query returns only services ending with '-service'")
    void myTeam_queryReturnsOnlyServiceServices() {
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
                .as("Service should end with '-service' to match .*-service$")
                .endsWith("-service");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Character Class Pattern Tests (^observability-[a-z]+$)")
  class CharacterClassPatternTests {

    @Test
    @DisplayName("regex-team can access observability-prometheus (matches ^observability-[a-z]+$)")
    void regexTeam_canAccessObservabilityPrometheus() {
      String query = "{resource.service.name=\"observability-prometheus\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("regex-team can access observability-grafana (matches ^observability-[a-z]+$)")
    void regexTeam_canAccessObservabilityGrafana() {
      String query = "{resource.service.name=\"observability-grafana\"}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
              .exchange()
              .expectStatus()
              .isOk()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(response).isNotNull();
    }

    @Test
    @DisplayName("regex-team query returns only services matching observability-[a-z]+ pattern")
    void regexTeam_queryReturnsOnlyMatchingServices() {
      String query = "{}";

      Map<String, Object> response =
          webTestClient
              .get()
              .uri(buildSearchUri(query))
              .header("Authorization", "Bearer " + E2ETestFixtures.REGEX_TEAM_TOKEN)
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
            // ^observability-[a-z]+$ means: "observability-" followed by lowercase letters
            assertThat(serviceName)
                .as("Service should match ^observability-[a-z]+$ pattern")
                .matches("^observability-[a-z]+$");
          }
        }
      }
    }
  }

  @Nested
  @DisplayName("Multi-Group Union Tests")
  class MultiGroupUnionTests {

    @Test
    @DisplayName("multi-group-user gets union of ^demo-.*$ AND .*-service$ patterns")
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
                .as("Service should match ^demo-.*$ OR .*-service$ pattern")
                .satisfiesAnyOf(
                    sn -> assertThat(sn).startsWith("demo-"),
                    sn -> assertThat(sn).endsWith("-service"));
          }
        }
      }
    }

    @Test
    @DisplayName("multi-group-user can access demo-order-service (matches ^demo-.*$)")
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
    @DisplayName("multi-group-user can access user-service (matches .*-service$)")
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
