package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Wildcard Pattern Enhancement Tests")
class WildcardPatternTest {

  /**
   * Helper method to simulate single label enhancement using the new enhanceLabelsSection approach
   */
  private Optional<String> enhanceSingleLabel(
      String labelName, LabelConstraintInfo constraint, Map<String, Set<String>> labelConstraints) {
    // Create a labels section string from the single label
    String labelsSection = labelName + constraint.operator() + "\"" + constraint.value() + "\"";

    // Use the new enhanceLabelsSection method
    var result = LabelProcessor.enhanceLabelsSection(labelsSection, labelConstraints);

    if (!result.isSuccess()) {
      return Optional.empty();
    }

    String enhancedLabels = result.getEnhancedQuery();
    if (enhancedLabels.isEmpty()) {
      return Optional.empty();
    }

    // Extract the specific label we're testing from the result
    String[] labelPairs = enhancedLabels.split(",");
    for (String pair : labelPairs) {
      String trimmedPair = pair.trim();
      if (trimmedPair.startsWith(labelName + "=")) {
        return Optional.of(trimmedPair);
      }
    }

    // If we can't find the specific label, return the entire result
    // This handles cases where the new implementation processes labels differently
    return Optional.of(enhancedLabels);
  }

  @Test
  @DisplayName("Should enhance empty string with allowed values")
  void testEnhanceEmptyStringWithAllowedValues() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("", "=");
    Set<String> allowedValues = Set.of("observability", "demo", "test");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("namespace=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("observability"));
    assertTrue(resultStr.contains("demo"));
    assertTrue(resultStr.contains("test"));
  }

  @Test
  @DisplayName("Should enhance .+ pattern with allowed values")
  void testEnhanceDotPlusPatternWithAllowedValues() {
    // Given
    String labelName = "service";
    var constraint = new LabelConstraintInfo(".+", "=~");
    Set<String> allowedValues = Set.of("order-service", "stock-service", "user-service");
    Map<String, Set<String>> labelConstraints = Map.of("service", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("service=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("order-service"));
    assertTrue(resultStr.contains("stock-service"));
    assertTrue(resultStr.contains("user-service"));
  }

  @Test
  @DisplayName("Should enhance .* pattern with allowed values")
  void testEnhanceDotStarPatternWithAllowedValues() {
    // Given
    String labelName = "pod";
    var constraint = new LabelConstraintInfo(".*", "=~");
    Set<String> allowedValues = Set.of("order-pod-1", "stock-pod-2", "user-pod-3");
    Map<String, Set<String>> labelConstraints = Map.of("pod", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("pod=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("order-pod-1"));
    assertTrue(resultStr.contains("stock-pod-2"));
    assertTrue(resultStr.contains("user-pod-3"));
  }

  @Test
  @DisplayName("Should enhance * pattern with allowed values")
  void testEnhanceStarPatternWithAllowedValues() {
    // Given
    String labelName = "instance";
    var constraint = new LabelConstraintInfo("*", "=~");
    Set<String> allowedValues = Set.of("instance-1", "instance-2", "instance-3");
    Map<String, Set<String>> labelConstraints = Map.of("instance", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("instance=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("instance-1"));
    assertTrue(resultStr.contains("instance-2"));
    assertTrue(resultStr.contains("instance-3"));
  }

  @Test
  @DisplayName("Should fallback to .+ when no allowed values exist")
  void testFallbackToDotPlusWhenNoAllowedValues() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("", "=");
    Set<String> allowedValues = Set.of(); // Empty set
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    // The new approach returns a wildcard pattern when no allowed values exist
    assertTrue(result.isPresent(), "Should return wildcard pattern when no allowed values exist");
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("namespace=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains(".*"));
  }

  @Test
  @DisplayName("Should handle !~ operator with empty string correctly")
  void testNotRegexMatchWithEmptyString() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("namespace=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("observability"));
    assertTrue(resultStr.contains("demo"));
  }

  @Test
  @DisplayName("Should handle !~ operator with .+ pattern correctly")
  void testNotRegexMatchWithDotPlusPattern() {
    // Given
    String labelName = "service";
    var constraint = new LabelConstraintInfo(".+", "!~");
    Set<String> allowedValues = Set.of("order-service", "stock-service");
    Map<String, Set<String>> labelConstraints = Map.of("service", allowedValues);

    // When & Then
    assertThrows(
        SecurityException.class,
        () -> {
          enhanceSingleLabel(labelName, constraint, labelConstraints);
        },
        "Should throw SecurityException when all values match .+ pattern");
  }

  @Test
  @DisplayName("Should handle !~ operator with specific pattern")
  void testNotRegexMatchWithSpecificPattern() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("obs.*", "!~");
    Set<String> allowedValues = Set.of("observability", "demo", "production");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("namespace=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("demo"));
    assertTrue(resultStr.contains("production"));
    assertFalse(
        resultStr.contains("observability"), "Should exclude observability due to !~ pattern");
  }

  @Test
  @DisplayName("Should handle empty allowed values set")
  void testEmptyAllowedValuesSet() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("", "=");
    Set<String> allowedValues = Set.of(); // Empty set
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    // The new approach returns a wildcard pattern when allowed values set is empty
    assertTrue(
        result.isPresent(), "Should return wildcard pattern when allowed values set is empty");
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("namespace=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains(".*"));
  }

  @Test
  @DisplayName("Should handle single allowed value")
  void testSingleAllowedValue() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo("", "=");
    Set<String> allowedValues = Set.of("observability");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertEquals(
        "namespace=\"observability\"", resultStr, "Should use exact match for single value");
  }

  @Test
  @DisplayName("Should handle mixed wildcard and literal patterns")
  void testMixedWildcardAndLiteralPatterns() {
    // Given
    String labelName = "instance";
    var constraint = new LabelConstraintInfo("", "=");
    Set<String> allowedValues = Set.of("instance-1", "instance-2", ".*", ".+");
    Map<String, Set<String>> labelConstraints = Map.of("instance", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("instance=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("instance-1"));
    assertTrue(resultStr.contains("instance-2"));
    assertTrue(resultStr.contains(".*"));
    assertTrue(resultStr.contains(".+"));
  }

  @Test
  @DisplayName("Should handle !~ operator when all values are excluded")
  void testNotRegexMatchWhenAllValuesExcluded() {
    // Given
    String labelName = "namespace";
    var constraint = new LabelConstraintInfo(".*", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", allowedValues);

    // When & Then
    assertThrows(
        SecurityException.class,
        () -> {
          enhanceSingleLabel(labelName, constraint, labelConstraints);
        },
        "Should throw SecurityException when all values are excluded");
  }

  @Test
  @DisplayName("Should handle !~ operator with partial exclusion")
  void testNotRegexMatchWithPartialExclusion() {
    // Given
    String labelName = "service";
    var constraint = new LabelConstraintInfo("order.*", "!~");
    Set<String> allowedValues = Set.of("order-service", "stock-service", "user-service");
    Map<String, Set<String>> labelConstraints = Map.of("service", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("service=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("stock-service"));
    assertTrue(resultStr.contains("user-service"));
    assertFalse(
        resultStr.contains("order-service"), "Should exclude order-service due to !~ pattern");
  }

  @Test
  @DisplayName("Should handle real-world scenario from logs")
  void testRealWorldScenarioFromLogs() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");
    Map<String, Set<String>> labelConstraints = Map.of("k8s_namespace_name", allowedValues);

    // When
    Optional<String> result = enhanceSingleLabel(labelName, constraint, labelConstraints);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("k8s_namespace_name=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("observability"));
    assertTrue(resultStr.contains("demo"));
  }

  @Test
  @DisplayName("Should handle wildcard patterns in complex queries")
  void testWildcardPatternsInComplexQueries() {
    // Given
    String query = "sum(rate(http_requests_total{namespace=\"\", service=\"*\"}[5m])) by (pod)";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability", "demo"),
            "service", Set.of("order-service", "stock-service"));

    // When
    var context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    var result = QueryEnhancementProcessor.enhanceQuery(context);

    // Then
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\"observability|demo\"")
            || enhancedQuery.contains("namespace=~\"demo|observability\""));
    assertTrue(
        enhancedQuery.contains("service=~\"order-service|stock-service\"")
            || enhancedQuery.contains("service=~\"stock-service|order-service\""));
  }
}
