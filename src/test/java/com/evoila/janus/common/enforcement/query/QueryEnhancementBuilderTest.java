package com.evoila.janus.common.enforcement.query;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QueryEnhancementBuilder Tests")
class QueryEnhancementBuilderTest {

  // ========================================================================
  // buildMissingConstraints
  // ========================================================================

  @Nested
  @DisplayName("buildMissingConstraints")
  class BuildMissingConstraints {

    @Test
    @DisplayName("Should build constraints for labels not in existing set")
    void missingLabels() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("demo", "prod"),
              "labels", Set.of("*"));

      List<String> result = QueryEnhancementBuilder.buildMissingConstraints(Set.of(), constraints);

      assertEquals(1, result.size());
      assertTrue(result.get(0).contains("namespace"));
    }

    @Test
    @DisplayName("Should skip labels already present in existing set")
    void existingLabelsSkipped() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("demo"),
              "labels", Set.of("*"));

      List<String> result =
          QueryEnhancementBuilder.buildMissingConstraints(Set.of("namespace"), constraints);

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should skip wildcard-only constraints")
    void wildcardConstraintsSkipped() {
      Map<String, Set<String>> constraints =
          Map.of(
              "service", Set.of("*"),
              "labels", Set.of("*"));

      List<String> result = QueryEnhancementBuilder.buildMissingConstraints(Set.of(), constraints);

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should skip empty value sets")
    void emptyValuesSkipped() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of(),
              "labels", Set.of("*"));

      List<String> result = QueryEnhancementBuilder.buildMissingConstraints(Set.of(), constraints);

      assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should skip .+ wildcard pattern constraints")
    void dotPlusWildcardSkipped() {
      Map<String, Set<String>> constraints =
          Map.of(
              "service", Set.of(".+"),
              "labels", Set.of("*"));

      List<String> result = QueryEnhancementBuilder.buildMissingConstraints(Set.of(), constraints);

      assertTrue(result.isEmpty());
    }
  }

  // ========================================================================
  // buildSafeConstraint
  // ========================================================================

  @Nested
  @DisplayName("buildSafeConstraint")
  class BuildSafeConstraint {

    @Test
    @DisplayName("Should build regex constraint for multiple values")
    void multipleValues() {
      String result =
          QueryEnhancementBuilder.buildSafeConstraint("namespace", Set.of("demo", "prod"));

      assertTrue(result.contains("namespace"));
      assertTrue(result.contains("=~"));
    }

    @Test
    @DisplayName("Should build exact constraint for single value")
    void singleValue() {
      String result = QueryEnhancementBuilder.buildSafeConstraint("namespace", Set.of("demo"));

      assertTrue(result.contains("namespace"));
      assertTrue(result.contains("demo"));
    }
  }

  // ========================================================================
  // buildLabelsConstraintQuery
  // ========================================================================

  @Nested
  @DisplayName("buildLabelsConstraintQuery")
  class BuildLabelsConstraintQuery {

    @Test
    @DisplayName("Should build constraint query for specific values")
    void specificValues() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("demo", "prod"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelsConstraintQuery(constraints);

      assertFalse(result.isEmpty());
      assertTrue(result.startsWith("{"));
      assertTrue(result.endsWith("}"));
      assertTrue(result.contains("namespace"));
    }

    @Test
    @DisplayName("Should return empty for wildcard-only constraints")
    void wildcardOnly() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("*"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelsConstraintQuery(constraints);

      assertEquals("", result);
    }

    @Test
    @DisplayName("Should skip labels key in constraint query")
    void skipLabelsKey() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("namespace", "service"));

      String result = QueryEnhancementBuilder.buildLabelsConstraintQuery(constraints);

      assertEquals("", result);
    }
  }

  // ========================================================================
  // buildLabelValuesConstraintQuery
  // ========================================================================

  @Nested
  @DisplayName("buildLabelValuesConstraintQuery")
  class BuildLabelValuesConstraintQuery {

    @Test
    @DisplayName("Should build constraint query for specific values")
    void specificValues() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("demo"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(constraints);

      assertFalse(result.isEmpty());
      assertTrue(result.startsWith("{"));
      assertTrue(result.endsWith("}"));
      assertTrue(result.contains("namespace"));
    }

    @Test
    @DisplayName("Should return empty for wildcard-only constraints")
    void wildcardOnly() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("*"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(constraints);

      assertEquals("", result);
    }

    @Test
    @DisplayName("Should skip labels key")
    void skipLabelsKey() {
      Map<String, Set<String>> constraints = Map.of("labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(constraints);

      assertEquals("", result);
    }
  }

  // ========================================================================
  // buildLabelValuesConstraintQuery - additional edge cases
  // ========================================================================

  @Nested
  @DisplayName("buildLabelValuesConstraintQuery edge cases")
  class BuildLabelValuesConstraintQueryEdgeCases {

    @Test
    @DisplayName("Should include multiple non-wildcard labels")
    void multipleLabels() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of("demo"),
              "service", Set.of("order-svc"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(constraints);

      assertFalse(result.isEmpty());
      assertTrue(result.contains("namespace"));
      assertTrue(result.contains("service"));
    }

    @Test
    @DisplayName("Should skip .+ wildcard values in label values constraint")
    void dotPlusWildcardSkipped() {
      Map<String, Set<String>> constraints =
          Map.of(
              "namespace", Set.of(".+"),
              "labels", Set.of("*"));

      String result = QueryEnhancementBuilder.buildLabelValuesConstraintQuery(constraints);

      assertEquals("", result);
    }
  }

  // ========================================================================
  // buildPattern
  // ========================================================================

  @Nested
  @DisplayName("buildPattern")
  class BuildPattern {

    @Test
    @DisplayName("Should join multiple values with pipe")
    void multipleValues() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of("demo", "prod"));

      // Order may vary, so check both forms
      assertTrue(result.equals("demo|prod") || result.equals("prod|demo"));
    }

    @Test
    @DisplayName("Should convert * to .* for simple wildcard")
    void simpleWildcard() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of("order-*"));

      assertTrue(result.contains(".*"));
    }

    @Test
    @DisplayName("Should preserve existing regex patterns")
    void existingRegex() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of(".*order-service"));

      assertEquals(".*order-service", result);
    }

    @Test
    @DisplayName("Should filter out empty strings")
    void emptyStringsFiltered() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of("demo", ""));

      assertEquals("demo", result);
    }

    @Test
    @DisplayName("Should handle literal values without escaping dots")
    void literalValues() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of("10.0.0.1"));

      assertEquals("10.0.0.1", result);
    }

    @Test
    @DisplayName("Should preserve escaped patterns")
    void escapedPatterns() {
      String result = QueryEnhancementBuilder.buildPattern(Set.of("test\\.value"));

      assertEquals("test\\.value", result);
    }
  }
}
