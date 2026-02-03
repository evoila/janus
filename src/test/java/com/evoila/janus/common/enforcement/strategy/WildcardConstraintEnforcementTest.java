package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WildcardConstraintEnforcementTest extends BaseUnitTest {

  @Mock private QueryEnforcementFlow queryEnforcementFlow;

  @BeforeEach
  void setUp() throws java.io.IOException {
    super.setUpBase();

    // Set up QueryEnforcementFlow mock to delegate to real implementation
    lenient()
        .when(queryEnforcementFlow.enhanceQuery(any()))
        .thenAnswer(
            invocation -> {
              QueryContext context = invocation.getArgument(0);
              return QueryEnhancementProcessor.enhanceQuery(context);
            });
  }

  @Test
  void testWildcardConstraintsAreSkipped_EmptyQuery() {
    // Given: Empty query with wildcard constraints
    String query = "";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "labels", Set.of("*") // Wildcard - should be skipped
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Only specific constraints should be added
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\"demo\""), "Should contain namespace constraint");
    assertFalse(enhancedQuery.contains("service=~"), "Should NOT contain service constraint");
    assertFalse(enhancedQuery.contains("labels=~"), "Should NOT contain labels constraint");

    // Verify the exact format
    assertEquals("{namespace=~\"demo\"}", enhancedQuery);
  }

  @Test
  void testWildcardConstraintsAreSkipped_ExistingQuery() {
    // Given: Query with existing labels and wildcard constraints
    String query = "{existing=\"value\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".*"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "existing", Set.of("value") // Existing - should be kept
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should keep existing labels and add only specific constraints
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(enhancedQuery.contains("existing=\"value\""), "Should keep existing label");
    assertTrue(enhancedQuery.contains("namespace=~\"demo\""), "Should add namespace constraint");
    assertFalse(enhancedQuery.contains("service=~"), "Should NOT add service constraint");
  }

  @Test
  void testWildcardConstraintsAreSkipped_LabelValuesEndpoint() {
    // Given: Label values endpoint with wildcard constraints
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "labels", Set.of("*") // Wildcard - should be skipped
            );

    // When: Simulating label values endpoint behavior
    var result =
        LabelProcessor.enhanceLabelsSection(
            "", // No existing labels
            labelConstraints);
    String enhancedQuery = result.getEnhancedQuery();

    // Then: Only essential constraints should be added
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\"demo\""), "Should contain namespace constraint");
    assertFalse(enhancedQuery.contains("service=~"), "Should NOT contain service constraint");
    assertFalse(enhancedQuery.contains("labels=~"), "Should NOT contain labels constraint");
  }

  @Test
  void testWildcardConstraintsAreSkipped_ExistingSelectors() {
    // Given: Existing selectors with wildcard constraints
    String existingLabels = "{existing=\"value\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".*"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "existing", Set.of("value") // Existing - should be kept
            );

    // When: Simulating existing selectors behavior
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then: Should keep existing labels and add only specific constraints
    assertTrue(result.isSuccess(), "Label enhancement should succeed");
    String enhancedLabels = result.getEnhancedQuery();
    assertNotNull(enhancedLabels);
    assertTrue(enhancedLabels.contains("existing=\"value\""), "Should keep existing label");
    assertTrue(enhancedLabels.contains("namespace=~\"demo\""), "Should add namespace constraint");
    assertFalse(enhancedLabels.contains("service=~"), "Should NOT add service constraint");
  }

  @Test
  void testAllWildcardPatternsAreSkipped() {
    // Test all wildcard patterns
    String[] wildcardPatterns = {"*", ".*", ".+", "()"};

    for (String pattern : wildcardPatterns) {
      // Given: Query with specific wildcard pattern
      String query = "";
      Map<String, Set<String>> labelConstraints =
          Map.of(
              "service", Set.of(pattern), // Wildcard pattern
              "namespace", Set.of("demo") // Specific - should be enforced
              );

      QueryContext context =
          QueryContext.builder()
              .originalQuery(query)
              .labelConstraints(labelConstraints)
              .language(QueryContext.QueryLanguage.PROMQL)
              .build();

      // When
      var result = queryEnforcementFlow.enhanceQuery(context);

      // Then: Wildcard constraint should be skipped
      assertTrue(result.isSuccess(), "Query enhancement should succeed for pattern: " + pattern);
      String enhancedQuery = result.getEnhancedQuery();
      assertNotNull(enhancedQuery, "Should not be null for pattern: " + pattern);
      assertTrue(
          enhancedQuery.contains("namespace=~\"demo\""),
          "Should contain namespace constraint for pattern: " + pattern);
      assertFalse(
          enhancedQuery.contains("service=~"),
          "Should NOT contain service constraint for pattern: " + pattern);
    }
  }

  @Test
  void testMixedWildcardAndSpecificConstraints() {
    // Given: Query with mixed wildcard and specific constraints
    String query = "{existing=\"value\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "environment", Set.of("prod"), // Specific - should be enforced
            "existing", Set.of("value") // Existing - should be kept
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should keep existing and add only specific constraints
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(enhancedQuery.contains("existing=\"value\""), "Should keep existing label");
    assertTrue(enhancedQuery.contains("namespace=~\"demo\""), "Should add namespace constraint");
    assertTrue(
        enhancedQuery.contains("environment=~\"prod\""), "Should add environment constraint");
    assertFalse(enhancedQuery.contains("service=~"), "Should NOT add service constraint");
  }

  @Test
  void testWildcardConstraintsWithExistingLabels() {
    // Given: Query with existing labels and wildcard constraints
    String query = "{job=\"prometheus\", instance=\"localhost:9090\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".*"), // Wildcard - should be skipped
            "namespace", Set.of("demo"), // Specific - should be enforced
            "job", Set.of("prometheus"), // Existing - should be kept
            "instance", Set.of("localhost:9090") // Existing - should be kept
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should keep existing labels and add only specific constraints
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(enhancedQuery.contains("job=\"prometheus\""), "Should keep existing job label");
    assertTrue(
        enhancedQuery.contains("instance=\"localhost:9090\""),
        "Should keep existing instance label");
    assertTrue(enhancedQuery.contains("namespace=~\"demo\""), "Should add namespace constraint");
    assertFalse(enhancedQuery.contains("service=~"), "Should NOT add service constraint");
  }

  @Test
  void testAllWildcardConstraints_ReturnsEmptyQuery() {
    // Given: Query with only wildcard constraints
    String query = "";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should be skipped
            "namespace", Set.of(".*"), // Wildcard - should be skipped
            "labels", Set.of("*") // Wildcard - should be skipped
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should return empty query since all constraints are wildcards
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertEquals(
        "{}", enhancedQuery, "Should return empty query when all constraints are wildcards");
  }
}
