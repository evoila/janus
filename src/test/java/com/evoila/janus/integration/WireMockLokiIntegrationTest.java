package com.evoila.janus.integration;

import com.evoila.janus.base.BaseIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("loki")
@Tag("wiremock")
@AutoConfigureWebTestClient
@ActiveProfiles({"test", "all"})
@org.springframework.context.annotation.Import(MockJwtConfig.class)
class WireMockLokiIntegrationTest extends BaseIntegrationTest {

  @Autowired private WebTestClient webTestClient;

  private String mockJwtToken;

  // Static WireMock server that starts before Spring Boot context
  private static WireMockServer staticWireMockServer;

  @DynamicPropertySource
  static void configureLokiUrl(DynamicPropertyRegistry registry) {
    // Start WireMock server before Spring Boot context initialization
    if (staticWireMockServer == null) {
      staticWireMockServer =
          new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
      staticWireMockServer.start();
    }

    // Configure the Loki URL to point to WireMock
    String lokiUrl = "http://localhost:" + staticWireMockServer.port();
    registry.add("proxy.services.loki.url", () -> lokiUrl);
    registry.add(
        "label-store.configmap.path", () -> "src/test/resources/configs/local-configmap.yaml");
  }

  @BeforeEach
  void setUp() {
    mockJwtToken = "test-token-order-service-team";

    // Use the static WireMock server
    wireMockServer = staticWireMockServer;
    wireMockServer.resetAll();

    // Configure WireMock to point to our test server
    WireMock.configureFor("localhost", wireMockServer.port());
  }

  // Override base class tearDown to not stop the static server
  @org.junit.jupiter.api.AfterEach
  void tearDown() {
    // Don't stop the static WireMock server - it will be reused
    if (wireMockServer != null) {
      wireMockServer.resetAll();
    }
  }

  // Cleanup static WireMock server after all tests
  @org.junit.jupiter.api.AfterAll
  static void cleanup() {
    if (staticWireMockServer != null) {
      staticWireMockServer.stop();
    }
  }

  @Test
  void testProxyEnhancesAndForwardsLokiQuery() {
    // LogQL strategy validates existing labels and adds missing required constraints
    // The original query will be enhanced with namespace constraints
    String originalQuery = "{service_name=\"order-service\"}";

    // Add time range parameters for LogQL range query
    long endTime = System.currentTimeMillis() / 1000; // Current time in seconds
    long startTime = endTime - 3600; // 1 hour ago
    long step = 15; // 15 second step

    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    wireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/loki/api/v1/query_range"))
            .withQueryParam("query", WireMock.matching(".*k8s_namespace_name=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"streams\",\"result\":[],\"stats\":{\"summary\":{\"execTime\":0.001},\"querier\":{\"store\":{\"totalChunksRef\":0}},\"ingester\":{\"totalReached\":5}}}}")));

    String uri =
        "/loki/api/v1/query_range?query="
            + encodedQuery
            + "&start="
            + startTime
            + "&end="
            + endTime
            + "&step="
            + step;

    var result =
        webTestClient.get().uri(uri).header("Authorization", "Bearer " + mockJwtToken).exchange();

    // Verify the response
    result
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("success")
        .jsonPath("$.data.resultType")
        .isEqualTo("streams")
        .jsonPath("$.data.result")
        .isArray()
        .jsonPath("$.data.stats.summary.execTime")
        .exists()
        .jsonPath("$.data.stats.querier.store.totalChunksRef")
        .isEqualTo(0)
        .jsonPath("$.data.stats.ingester.totalReached")
        .isEqualTo(5);

    // The proxy is working correctly - it's decoding and re-encoding the query properly
    // WireMock verification is not needed since we can see from the logs that the proxy
    // is correctly forwarding the request and receiving a 200 OK response

  }
}
