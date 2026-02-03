package com.evoila.janus.base;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.config.ProxyConfig;
import com.evoila.janus.common.labelstore.ConfigMapLabelStore;
import com.evoila.janus.common.labelstore.LabelStore;
import com.evoila.janus.security.config.OAuthToken;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Base class for unit tests with common mocks and utilities. Provides a consistent setup for all
 * unit tests in the application.
 */
@Tag("unit")
@Tag("fast")
@ExtendWith(MockitoExtension.class)
public abstract class BaseUnitTest {

  @Mock protected ProxyConfig mockConfig;

  @Mock protected LabelStore mockLabelStore;

  @Mock protected OAuthToken mockToken;

  protected ConfigMapLabelStore configMapLabelStore;
  protected File tempDir;

  @BeforeEach
  protected void setUpBase() throws IOException {
    // Initialize common components
    configMapLabelStore = new ConfigMapLabelStore();
    tempDir = createTempDirectory();

    // Setup common mock behavior
    setupCommonMocks();
  }

  /** Creates a temporary directory for test files */
  protected File createTempDirectory() throws IOException {
    File tempDirectory = File.createTempFile("test", "dir");
    tempDirectory.delete();
    tempDirectory.mkdir();
    return tempDirectory;
  }

  /** Sets up common mock behavior that most unit tests will need */
  protected void setupCommonMocks() {
    // Common mock setup can be overridden by subclasses
  }

  /** Creates a test OAuth token with specified groups */
  protected OAuthToken createTestToken(String... groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername("test-user");
    token.setGroups(List.of(groups));
    return token;
  }

  /** Creates a test configuration file with the given content */
  protected File createTestConfigFile(String configContent) throws IOException {
    File configFile = new File(tempDir, "test-config.yaml");
    try (FileWriter writer = new FileWriter(configFile)) {
      writer.write(configContent);
    }
    return configFile;
  }

  /** Sets up ConfigMapLabelStore with a test configuration file */
  protected void setupConfigMapLabelStore(File configFile) {
    ReflectionTestUtils.setField(
        configMapLabelStore, "configMapPath", configFile.getAbsolutePath());
    ReflectionTestUtils.setField(configMapLabelStore, "labelStoreType", "configmap");
    configMapLabelStore.initialize();
  }

  /** Sets up ConfigMapLabelStore with the actual local-configmap.yaml file */
  protected void setupConfigMapLabelStoreWithRealConfig() {
    ReflectionTestUtils.setField(
        configMapLabelStore, "configMapPath", "src/test/resources/configs/local-configmap.yaml");
    ReflectionTestUtils.setField(configMapLabelStore, "labelStoreType", "configmap");
    configMapLabelStore.initialize();
  }

  /** Creates a basic Loki configuration for testing */
  protected String createBasicLokiConfig() {
    return """
            loki:
              user-label-constraints:
                order-service-team:
                  labels:
                    - service_name
                    - k8s_namespace_name
                  service_name:
                    - "*order-service"
                    - "*stock-service"
                  k8s_namespace_name:
                    - demo
                    - observability
            """;
  }

  /** Creates a basic Thanos configuration for testing */
  protected String createBasicThanosConfig() {
    return """
            thanos:
              user-label-constraints:
                order-service-team:
                  labels:
                    - service
                    - namespace
                  service:
                    - "microservices-order-service"
                    - "microservices-stock-service"
                  namespace:
                    - demo
                    - observability
            """;
  }

  /** Creates a basic Tempo configuration for testing */
  protected String createBasicTempoConfig() {
    return """
            tempo:
              user-label-constraints:
                order-service-team:
                  labels:
                    - service.name
                    - k8s.namespace.name
                  service.name:
                    - "microservices-order-service"
                    - "microservices-stock-service"
                  k8s.namespace.name:
                    - demo
                    - observability
            """;
  }

  /** Asserts that a map contains the expected key-value pairs */
  protected void assertMapContains(
      Map<String, Set<String>> map, String key, String... expectedValues) {
    assertTrue(map.containsKey(key), "Map should contain key: " + key);
    Set<String> values = map.get(key);
    assertNotNull(values, "Values for key " + key + " should not be null");

    for (String expectedValue : expectedValues) {
      assertTrue(
          values.contains(expectedValue),
          "Values for key " + key + " should contain: " + expectedValue);
    }
  }

  /** Asserts that a string contains any of the expected patterns */
  protected void assertContainsAny(String actual, String... expectedPatterns) {
    boolean found = false;
    for (String pattern : expectedPatterns) {
      if (actual.contains(pattern)) {
        found = true;
        break;
      }
    }
    assertTrue(found, "String should contain one of: " + String.join(", ", expectedPatterns));
  }

  /** Asserts that a string contains all of the expected patterns */
  protected void assertContainsAll(String actual, String... expectedPatterns) {
    for (String pattern : expectedPatterns) {
      assertTrue(
          actual.contains(pattern), "String should contain: " + pattern + "\nActual: " + actual);
    }
  }

  /** Loads a test configuration file for a service */
  protected String loadTestConfig(String service, String configName) throws IOException {
    return TestResourceLoader.loadConfig(service, configName);
  }

  /** Loads sample queries for a service */
  protected Map<String, List<String>> loadSampleQueries(String service) throws IOException {
    return TestResourceLoader.loadSampleQueries(service);
  }

  /** Loads expected responses for a service */
  protected Map<String, Object> loadExpectedResponses(String service) throws IOException {
    return TestResourceLoader.loadExpectedResponses(service);
  }
}
