package com.evoila.janus.base;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import tools.jackson.databind.json.JsonMapper;

/** Utility class for loading test resources from the organized directory structure. */
public final class TestResourceLoader {

  private static final JsonMapper jsonMapper = JsonMapper.builder().build();

  private TestResourceLoader() {
    // Utility class - prevent instantiation
  }

  /** Loads a configuration file from the configs directory */
  public static String loadConfig(String service, String configName) throws IOException {
    String path = String.format("configs/%s/%s", service, configName);
    return loadResourceAsString(path);
  }

  /** Loads a WireMock stub file */
  public static String loadWireMockStub(String service, String stubName) throws IOException {
    String path = String.format("wiremock/%s/%s", service, stubName);
    return loadResourceAsString(path);
  }

  /** Loads sample queries for a service */
  public static Map<String, List<String>> loadSampleQueries(String service) throws IOException {
    String path = String.format("data/sample-queries/%s-queries.json", service);
    return jsonMapper.readValue(loadResourceAsString(path), Map.class);
  }

  /** Loads expected responses for a service */
  public static Map<String, Object> loadExpectedResponses(String service) throws IOException {
    String path = String.format("data/expected-responses/%s-responses.json", service);
    return jsonMapper.readValue(loadResourceAsString(path), Map.class);
  }

  /** Loads a resource as a string from the classpath */
  public static String loadResourceAsString(String path) throws IOException {
    ClassPathResource resource = new ClassPathResource(path);
    return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
  }

  /** Loads a resource as a byte array from the classpath */
  public static byte[] loadResourceAsBytes(String path) throws IOException {
    ClassPathResource resource = new ClassPathResource(path);
    return StreamUtils.copyToByteArray(resource.getInputStream());
  }

  /** Creates a test configuration path for a service */
  public static String getConfigPath(String service, String configName) {
    return String.format("configs/%s/%s", service, configName);
  }

  /** Creates a WireMock stub path for a service */
  public static String getWireMockStubPath(String service, String stubName) {
    return String.format("wiremock/%s/%s", service, stubName);
  }

  /** Creates a sample queries path for a service */
  public static String getSampleQueriesPath(String service) {
    return String.format("data/sample-queries/%s-queries.json", service);
  }

  /** Creates an expected responses path for a service */
  public static String getExpectedResponsesPath(String service) {
    return String.format("data/expected-responses/%s-responses.json", service);
  }
}
