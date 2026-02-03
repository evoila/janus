package com.evoila.janus.base;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Test for TestResourceLoader to verify organized test resources work correctly */
@Tag("test-utils")
@Tag("resources")
class TestResourceLoaderTest extends BaseUnitTest {

  static Stream<Arguments> serviceConfigCases() {
    return Stream.of(
        Arguments.of("loki", "loki:", "service_name:"),
        Arguments.of("thanos", "thanos:", "service:"),
        Arguments.of("tempo", "tempo:", "service.name:"));
  }

  @ParameterizedTest(name = "Load {0} config")
  @MethodSource("serviceConfigCases")
  void testLoadServiceConfig(String service, String serviceKey, String labelKey)
      throws IOException {
    String config = TestResourceLoader.loadConfig(service, "basic-config.yaml");

    assertNotNull(config);
    assertTrue(config.contains(serviceKey));
    assertTrue(config.contains("order-service-team:"));
    assertTrue(config.contains(labelKey));
  }

  @Test
  void testLoadSampleQueries() throws IOException {
    Map<String, List<String>> lokiQueries = TestResourceLoader.loadSampleQueries("loki");

    assertNotNull(lokiQueries);
    assertTrue(lokiQueries.containsKey("basic_queries"));
    assertTrue(lokiQueries.containsKey("complex_queries"));
    assertTrue(lokiQueries.containsKey("label_queries"));

    List<String> basicQueries = lokiQueries.get("basic_queries");
    assertNotNull(basicQueries);
    assertFalse(basicQueries.isEmpty());
    assertTrue(basicQueries.get(0).contains("http_requests_total"));
  }

  @Test
  void testLoadExpectedResponses() throws IOException {
    Map<String, Object> lokiResponses = TestResourceLoader.loadExpectedResponses("loki");

    assertNotNull(lokiResponses);
    assertTrue(lokiResponses.containsKey("success_response"));
    assertTrue(lokiResponses.containsKey("labels_response"));
    assertTrue(lokiResponses.containsKey("error_response"));

    @SuppressWarnings("unchecked")
    Map<String, Object> successResponse =
        (Map<String, Object>) lokiResponses.get("success_response");
    assertEquals("success", successResponse.get("status"));
  }

  @Test
  void testLoadWireMockStub() throws IOException {
    String stub = TestResourceLoader.loadWireMockStub("loki", "health-check.json");

    assertNotNull(stub);
    assertTrue(stub.contains("\"method\""));
    assertTrue(stub.contains("\"GET\""));
    assertTrue(stub.contains("\"urlPath\""));
    assertTrue(stub.contains("\"/ready\""));
    assertTrue(stub.contains("\"status\""));
    assertTrue(stub.contains("200"));
  }

  @Test
  void testInheritedMethods() throws IOException {
    // Test that inherited methods work
    String config = loadTestConfig("loki", "basic-config.yaml");
    assertNotNull(config);
    assertTrue(config.contains("loki:"));

    Map<String, List<String>> queries = loadSampleQueries("thanos");
    assertNotNull(queries);
    assertTrue(queries.containsKey("basic_queries"));

    Map<String, Object> responses = loadExpectedResponses("tempo");
    assertNotNull(responses);
    assertTrue(responses.containsKey("success_response"));
  }
}
