package com.evoila.janus.base;

import com.evoila.janus.security.config.OAuthToken;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Utility class providing common test helper methods. This class contains static methods that can
 * be used across all test types.
 */
public final class TestUtils {

  private TestUtils() {
    // Utility class - prevent instantiation
  }

  /** Creates a test OAuth token with the specified username and groups */
  public static OAuthToken createTestToken(String username, String... groups) {
    OAuthToken token = new OAuthToken();
    token.setPreferredUsername(username);
    token.setGroups(List.of(groups));
    return token;
  }

  /** Creates a test OAuth token with default username and specified groups */
  public static OAuthToken createTestToken(String... groups) {
    return createTestToken("test-user", groups);
  }

  /** Creates HTTP headers for testing with tenant information */
  public static HttpHeaders createTenantHeaders(String tenantId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Scope-OrgID", tenantId);
    return headers;
  }

  /** Creates HTTP headers for testing with multiple tenant headers */
  public static HttpHeaders createMultiTenantHeaders(Map<String, String> tenantHeaders) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    tenantHeaders.forEach(headers::set);
    return headers;
  }

  /** URL encodes a string using UTF-8 */
  public static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  /** URL decodes a string using UTF-8 */
  public static String urlDecode(String value) {
    return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
  }

  /** Creates a basic Loki query for testing */
  public static String createLokiQuery(String metricName, Map<String, String> labels) {
    StringBuilder query = new StringBuilder(metricName);
    if (!labels.isEmpty()) {
      query.append("{");
      boolean first = true;
      for (Map.Entry<String, String> entry : labels.entrySet()) {
        if (!first) {
          query.append(", ");
        }
        query.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
        first = false;
      }
      query.append("}");
    }
    return query.toString();
  }

  /** Creates a basic PromQL query for testing */
  public static String createPromQLQuery(String metricName, Map<String, String> labels) {
    return createLokiQuery(metricName, labels); // Same format for PromQL
  }

  /** Creates a basic TraceQL query for testing */
  public static String createTraceQLQuery(Map<String, String> attributes) {
    if (attributes.isEmpty()) {
      return "{}";
    }

    StringBuilder query = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      if (!first) {
        query.append(" && ");
      }
      query.append(entry.getKey()).append("=\"").append(entry.getValue()).append("\"");
      first = false;
    }
    query.append("}");
    return query.toString();
  }

  /** Creates a test configuration YAML for a specific service */
  public static String createServiceConfig(
      String serviceName, String groupName, Map<String, List<String>> labelConstraints) {
    StringBuilder config = new StringBuilder();
    config.append(serviceName).append(":\n");
    config.append("  user-label-constraints:\n");
    config.append("    ").append(groupName).append(":\n");

    // Add allowed labels
    if (labelConstraints.containsKey("labels")) {
      config.append("      labels:\n");
      for (String label : labelConstraints.get("labels")) {
        config.append("        - ").append(label).append("\n");
      }
    }

    // Add label constraints
    for (Map.Entry<String, List<String>> entry : labelConstraints.entrySet()) {
      if (!"labels".equals(entry.getKey())) {
        config.append("      ").append(entry.getKey()).append(":\n");
        for (String value : entry.getValue()) {
          config.append("        - \"").append(value).append("\"\n");
        }
      }
    }

    return config.toString();
  }

  /** Creates a test configuration YAML for admin tenant headers */
  public static String createAdminConfig(Map<String, String> tenantHeaders) {
    StringBuilder config = new StringBuilder();
    config.append("admin:\n");
    config.append("  tenant-headers:\n");
    for (Map.Entry<String, String> entry : tenantHeaders.entrySet()) {
      config
          .append("    ")
          .append(entry.getKey())
          .append(": ")
          .append(entry.getValue())
          .append("\n");
    }
    return config.toString();
  }

  /** Asserts that a string contains any of the expected patterns */
  public static boolean containsAny(String actual, String... expectedPatterns) {
    for (String pattern : expectedPatterns) {
      if (actual.contains(pattern)) {
        return true;
      }
    }
    return false;
  }

  /** Asserts that a string contains all of the expected patterns */
  public static boolean containsAll(String actual, String... expectedPatterns) {
    for (String pattern : expectedPatterns) {
      if (!actual.contains(pattern)) {
        return false;
      }
    }
    return true;
  }

  /** Creates a sample Loki response for testing */
  public static String createLokiResponse(String status, Object data) {
    return String.format("{\"status\":\"%s\",\"data\":%s}", status, data);
  }

  /** Creates a sample Prometheus response for testing */
  public static String createPrometheusResponse(String status, Object data) {
    return String.format("{\"status\":\"%s\",\"data\":%s}", status, data);
  }

  /** Creates a sample Tempo response for testing */
  public static String createTempoResponse(String status, Object data) {
    return String.format("{\"status\":\"%s\",\"data\":%s}", status, data);
  }

  /** Creates a test file path that's unique for each test */
  public static String createTestFilePath(String prefix, String suffix) {
    return prefix + System.currentTimeMillis() + suffix;
  }
}
