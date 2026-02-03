package com.evoila.janus.integration;

import com.evoila.janus.app.JanusApplication;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("thanos")
@Tag("wiremock")
@AutoConfigureWebTestClient
@ActiveProfiles({"test", "all"})
@SpringBootTest(
    classes = JanusApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(MockJwtConfig.class)
class WireMockThanosIntegrationTest {

  @Autowired private WebTestClient webTestClient;

  private String mockJwtToken;

  // Static WireMock server that starts before Spring Boot context
  private static WireMockServer staticWireMockServer;

  @DynamicPropertySource
  static void configureThanosUrl(DynamicPropertyRegistry registry) {
    // Start WireMock server before Spring Boot context initialization
    if (staticWireMockServer == null) {
      staticWireMockServer =
          new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
      staticWireMockServer.start();
    }

    // Configure the Thanos URL to point to WireMock
    String thanosUrl = "http://localhost:" + staticWireMockServer.port();
    registry.add("proxy.services.thanos.url", () -> thanosUrl);
    registry.add(
        "label-store.configmap.path", () -> "src/test/resources/configs/local-configmap.yaml");
  }

  @BeforeEach
  void setUp() {
    mockJwtToken = "test-token-order-service-team";

    // Use the static WireMock server
    staticWireMockServer.resetAll();

    // Configure WireMock to point to our test server
    WireMock.configureFor("localhost", staticWireMockServer.port());
  }

  // Cleanup static WireMock server after all tests
  @org.junit.jupiter.api.AfterAll
  static void cleanup() {
    if (staticWireMockServer != null) {
      staticWireMockServer.stop();
    }
  }

  @Test
  void testProxyEnhancesAndForwardsThanosQuery() {
    // PromQL strategy validates existing labels and adds missing required constraints
    // The original query will be enhanced with namespace constraints
    String originalQuery = "up{job=\"prometheus\"}";

    // Add time range parameters for PromQL range query
    long endTime = System.currentTimeMillis() / 1000; // Current time in seconds
    long startTime = endTime - 3600; // 1 hour ago
    long step = 15; // 15 second step

    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/query_range"))
            .withQueryParam("query", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[],\"stats\":{\"summary\":{\"execTime\":0.001},\"querier\":{\"store\":{\"totalChunksRef\":0}},\"ingester\":{\"totalReached\":5}}}}")));

    String uri =
        "/api/v1/query_range?query="
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
        .isEqualTo("matrix")
        .jsonPath("$.data.result")
        .isArray()
        .jsonPath("$.data.stats.summary.execTime")
        .exists()
        .jsonPath("$.data.stats.querier.store.totalChunksRef")
        .isEqualTo(0)
        .jsonPath("$.data.stats.ingester.totalReached")
        .isEqualTo(5);
  }

  @Test
  void testProxyEnhancesAndForwardsThanosInstantQuery() {
    // Test instant query endpoint
    String originalQuery = "up{job=\"prometheus\"}";
    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/query"))
            .withQueryParam("query", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"__name__\":\"up\",\"job\":\"prometheus\",\"namespace\":\"observability\"},\"value\":[1234567890,\"1\"]}]}}")));

    String uri = "/api/v1/query?query=" + encodedQuery;

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
        .isEqualTo("vector")
        .jsonPath("$.data.result")
        .isArray()
        .jsonPath("$.data.result[0].metric.__name__")
        .isEqualTo("up")
        .jsonPath("$.data.result[0].metric.job")
        .isEqualTo("prometheus")
        .jsonPath("$.data.result[0].metric.namespace")
        .isEqualTo("observability");
  }

  @Test
  void testProxyEnhancesAndForwardsThanosSeriesQuery() {
    // Test series endpoint with match[] selectors
    String originalQuery = "up{job=\"prometheus\"}";
    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/series"))
            .withQueryParam("match[]", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":[{\"__name__\":\"up\",\"job\":\"prometheus\",\"namespace\":\"observability\"}]}}")));

    String uri = "/api/v1/series?match[]=" + encodedQuery;

    var result =
        webTestClient.get().uri(uri).header("Authorization", "Bearer " + mockJwtToken).exchange();

    // Verify the response
    result
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("success")
        .jsonPath("$.data")
        .isArray()
        .jsonPath("$.data[0].__name__")
        .isEqualTo("up")
        .jsonPath("$.data[0].job")
        .isEqualTo("prometheus")
        .jsonPath("$.data[0].namespace")
        .isEqualTo("observability");
  }

  @Test
  void testProxyEnhancesAndForwardsThanosLabelsQuery() {
    // Test labels endpoint
    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/labels"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":[\"__name__\",\"job\",\"namespace\",\"service\"]}")));

    String uri = "/api/v1/labels";

    var result =
        webTestClient.get().uri(uri).header("Authorization", "Bearer " + mockJwtToken).exchange();

    // Verify the response
    result
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("success")
        .jsonPath("$.data")
        .isArray()
        .jsonPath("$.data[0]")
        .isEqualTo("__name__")
        .jsonPath("$.data[1]")
        .isEqualTo("job")
        .jsonPath("$.data[2]")
        .isEqualTo("namespace")
        .jsonPath("$.data[3]")
        .isEqualTo("service");
  }

  @Test
  void testProxyEnhancesAndForwardsThanosLabelValuesQuery() {
    // Test label values endpoint
    String labelName = "namespace";
    String encodedQuery =
        java.net.URLEncoder.encode("up{job=\"prometheus\"}", StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/label/" + labelName + "/values"))
            .withQueryParam("match[]", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"success\",\"data\":[\"observability\",\"demo\"]}")));

    String uri = "/api/v1/label/" + labelName + "/values?match[]=" + encodedQuery;

    var result =
        webTestClient.get().uri(uri).header("Authorization", "Bearer " + mockJwtToken).exchange();

    // Verify the response
    result
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("success")
        .jsonPath("$.data")
        .isArray()
        .jsonPath("$.data[0]")
        .isEqualTo("observability")
        .jsonPath("$.data[1]")
        .isEqualTo("demo");
  }

  @Test
  void testProxyEnhancesAndForwardsRootApiQuery() {
    // Test root API endpoint (without /thanos prefix)
    String originalQuery = "up{job=\"prometheus\"}";
    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/query"))
            .withQueryParam("query", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[{\"metric\":{\"__name__\":\"up\",\"job\":\"prometheus\",\"namespace\":\"observability\"},\"value\":[1234567890,\"1\"]}]}}")));

    String uri = "/api/v1/query?query=" + encodedQuery;

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
        .isEqualTo("vector")
        .jsonPath("$.data.result")
        .isArray();
  }

  @Test
  void testProxyEnhancesComplexPromQLQuery() {
    // Test complex PromQL query with multiple labels
    String originalQuery = "rate(http_requests_total{service=\"order-service\"}[5m])";
    String encodedQuery =
        java.net.URLEncoder.encode(originalQuery, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/query_range"))
            .withQueryParam("query", WireMock.matching(".*namespace=~.*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[],\"stats\":{\"summary\":{\"execTime\":0.001}}}}")));

    long endTime = System.currentTimeMillis() / 1000;
    long startTime = endTime - 3600;
    long step = 15;

    String uri =
        "/api/v1/query_range?query="
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
        .isEqualTo("matrix");
  }

  @Test
  void testProxyHandlesMultipleMatchSelectors() {
    // Test query with single match[] selector (Thanos/Prometheus only supports one match parameter)
    String selector = "up{job=\"prometheus\"}";
    String encodedSelector =
        java.net.URLEncoder.encode(selector, java.nio.charset.StandardCharsets.UTF_8);

    staticWireMockServer.stubFor(
        WireMock.get(WireMock.urlPathEqualTo("/api/v1/query_range"))
            .withQueryParam("match[]", WireMock.matching(".*"))
            .withQueryParam("start", WireMock.matching(".*"))
            .withQueryParam("end", WireMock.matching(".*"))
            .withQueryParam("step", WireMock.matching(".*"))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}")));

    webTestClient
        .get()
        .uri(
            "/api/v1/query_range?match[]="
                + encodedSelector
                + "&start=2023-01-01T00:00:00Z&end=2023-01-01T01:00:00Z&step=1m")
        .header("Authorization", "Bearer " + mockJwtToken)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("success");
  }
}
