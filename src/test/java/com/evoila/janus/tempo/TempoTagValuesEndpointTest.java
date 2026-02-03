package com.evoila.janus.tempo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TempoTagValuesEndpointTest {

  @Test
  void testTempoTagValuesEndpointPathPatterns() {
    // Test various valid patterns
    String[] validPatterns = {
      "api/search/tag/resource.service.name/values",
      "api/search/tag/service/values",
      "api/search/tag/namespace/values"
    };

    for (String pattern : validPatterns) {
      assertTrue(pattern.matches(".*/search/tag/[^/]+/values"), "Pattern should match: " + pattern);
    }

    // Test invalid patterns
    String[] invalidPatterns = {
      "api/search/tags",
      "api/search",
      "api/traces/123",
      "v1/label/service/values" // This is Thanos pattern, not Tempo
    };

    for (String pattern : invalidPatterns) {
      assertFalse(
          pattern.matches(".*/search/tag/[^/]+/values"), "Pattern should not match: " + pattern);
    }
  }

  @Test
  void testTagNameExtraction() {
    // Test extracting tag names from Tempo paths
    String[] testCases = {
      "api/search/tag/resource.service.name/values",
      "api/search/tag/service/values",
      "api/search/tag/namespace/values"
    };

    String[] expectedNames = {"resource.service.name", "service", "namespace"};

    for (int i = 0; i < testCases.length; i++) {
      String path = testCases[i];
      String expected = expectedNames[i];

      // Extract tag name using the same logic as the orchestrator
      String[] parts = path.split("/");
      String extractedName = "";
      for (int j = 0; j < parts.length - 1; j++) {
        if ("tag".equals(parts[j]) && j + 1 < parts.length) {
          extractedName = parts[j + 1];
          break;
        }
      }

      assertEquals(expected, extractedName, "Tag name extraction failed for path: " + path);
    }
  }
}
