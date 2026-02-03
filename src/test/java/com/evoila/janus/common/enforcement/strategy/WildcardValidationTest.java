package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class WildcardValidationTest extends BaseUnitTest {

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
  void testWildcardPatternsAllowAnyValue() {
    // Given: Query with any service value and wildcard constraints
    String query = "{service=\"any-service-value\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should allow any value
            "namespace", Set.of("demo") // Specific - should be enforced
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When: Should NOT throw SecurityException
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should allow the query and add namespace constraint
    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("service=\"any-service-value\""),
        "Should keep original service value");
    assertTrue(enhancedQuery.contains("namespace=~\"demo\""), "Should add namespace constraint");
  }

  @Test
  void testAllWildcardPatternsAllowAnyValue() {
    // Test all wildcard patterns
    String[] wildcardPatterns = {"*", ".*", ".+", "()"};

    for (String pattern : wildcardPatterns) {
      // Given: Query with any service value and specific wildcard pattern
      String query = "{service=\"any-service-value\"}";
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

      // When: Should NOT throw SecurityException
      var result = queryEnforcementFlow.enhanceQuery(context);

      // Then: Should allow the query and add namespace constraint
      assertTrue(result.isSuccess(), "Query enhancement should succeed for pattern: " + pattern);
      String enhancedQuery = result.getEnhancedQuery();
      assertNotNull(enhancedQuery, "Should not be null for pattern: " + pattern);
      assertTrue(
          enhancedQuery.contains("service=\"any-service-value\""),
          "Should keep original service value for pattern: " + pattern);
      assertTrue(
          enhancedQuery.contains("namespace=~\"demo\""),
          "Should add namespace constraint for pattern: " + pattern);
    }
  }

  @Test
  void testMixedWildcardAndSpecificConstraints() {
    // Given: Query with mixed wildcard and specific constraints
    String query = "{service=\"any-service\",namespace=\"unauthorized\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Wildcard - should allow any value
            "namespace", Set.of("demo") // Specific - should reject unauthorized
            );

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    // When & Then: Should throw SecurityException for unauthorized namespace
    SecurityException exception =
        assertThrows(
            SecurityException.class,
            () -> {
              queryEnforcementFlow.enhanceQuery(context);
            });

    assertTrue(
        exception.getMessage().contains("Unauthorized label value"),
        "Should throw SecurityException for unauthorized namespace value");
  }
}
