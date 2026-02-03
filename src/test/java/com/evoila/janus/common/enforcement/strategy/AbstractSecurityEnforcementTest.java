package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import com.evoila.janus.security.config.OAuthToken;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Abstract base test class for all security enforcement operations Defines common test cases that
 * all query languages must pass
 */
@Tag("security-enforcement")
public abstract class AbstractSecurityEnforcementTest {

  @Mock protected OAuthToken mockToken;

  @Mock protected QueryEnforcementFlow queryEnforcementFlow;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);

    // Set up QueryEnforcementFlow mock to delegate to real implementation
    lenient()
        .when(queryEnforcementFlow.enhanceQuery(any()))
        .thenAnswer(
            invocation -> {
              QueryContext context = invocation.getArgument(0);
              return QueryEnhancementProcessor.enhanceQuery(context);
            });
  }

  /** Get the query language for testing */
  protected abstract QueryContext.QueryLanguage getQueryLanguage();

  /** Get the strategy name for logging */
  protected abstract String getStrategyName();

  // ============================================================================
  // WILDCARD HANDLING TESTS
  // ============================================================================

  @Test
  void testWildcardHandling_EmptyString_EnhancesToAllowedValues() {
    // Given: Query with empty string constraint
    String query = buildQueryWithConstraint("namespace", "");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should enhance empty string to allowed values
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("observability") || enhancedQuery.contains("demo"),
        getStrategyName() + " should enhance empty string to allowed values");
    assertFalse(
        enhancedQuery.contains("namespace=\"\""),
        getStrategyName() + " should not preserve empty namespace value");
  }

  @Test
  void testWildcardHandling_AsteriskWildcard_ExpandsToAllowedValues() {
    // Given: Query with "*" wildcard
    String query = buildQueryWithConstraint("service", "*");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "service", Set.of("*order-service", "microservices-stock-service"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should expand wildcard to allowed values
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("order-service") || enhancedQuery.contains("stock-service"),
        getStrategyName() + " should expand wildcard to allowed values");
  }

  @Test
  void testWildcardHandling_RegexWildcard_ExpandsToAllowedValues() {
    // Given: Query with ".*" regex wildcard
    String query = buildQueryWithConstraint("service", ".*");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "service", Set.of("*order-service", "microservices-stock-service"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should expand regex wildcard to allowed values
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("order-service") || enhancedQuery.contains("stock-service"),
        getStrategyName() + " should expand regex wildcard to allowed values");
  }

  @Test
  void testWildcardHandling_FullWildcardAllowed() {
    // Given: Query with wildcard and full wildcard access
    String query = buildQueryWithConstraint("namespace", "*");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("*") // Full wildcard access
            );

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should preserve wildcard when full access is allowed
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\".*\""),
        getStrategyName() + " should preserve wildcard when full access is allowed");
  }

  // ============================================================================
  // NOT EQUALS OPERATOR TESTS
  // ============================================================================

  @Test
  void testNotEqualsOperator_SingleRemainingValue_UsesExactMatch() {
    // Given: Query with not-equals operator and single remaining value
    String query = buildQueryWithNotEqualsConstraint("namespace", "observability");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should convert to exact match for remaining value
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertContainsConstraint(enhancedQuery, "namespace", "demo");
    assertFalse(
        enhancedQuery.contains("namespace!="),
        getStrategyName() + " should convert not-equals to exact match");
  }

  @Test
  void testNotEqualsOperator_MultipleRemainingValues_UsesRegex() {
    // Given: Query with not-equals operator and multiple remaining values
    String query = buildQueryWithNotEqualsConstraint("namespace", "observability");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo", "production"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should convert to regex match for remaining values
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    // Check for both possible orders since Set iteration order may vary
    boolean hasCorrectConstraint =
        enhancedQuery.contains("namespace=~\"demo|production\"")
            || enhancedQuery.contains("namespace=~\"production|demo\"");
    assertTrue(
        hasCorrectConstraint, getStrategyName() + " should convert not-equals to regex match");
  }

  @Test
  void testNotEqualsOperator_WildcardAccess_PreservesConstraint() {
    // Given: Query with not-equals operator and wildcard access
    String query = buildQueryWithNotEqualsConstraint("namespace", "observability");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("*") // Wildcard access
            );

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should preserve not-equals constraint
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertContainsNotEqualsConstraint(enhancedQuery, "namespace", "observability");
  }

  // ============================================================================
  // SPECIFIC VALUE TESTS
  // ============================================================================

  @Test
  void testSpecificValue_AllowedValue_PreservesConstraint() {
    // Given: Query with specific allowed value
    String query = buildQueryWithConstraint("namespace", "observability");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should preserve the constraint
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertContainsConstraint(enhancedQuery, "namespace", "observability");
  }

  @Test
  void testSpecificValue_NotAllowed_ThrowsSecurityException() {
    // Given: Query with specific not-allowed value
    String query = buildQueryWithConstraint("namespace", "forbidden");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When & Then: Should throw SecurityException
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    assertThrows(
        SecurityException.class,
        () -> {
          queryEnforcementFlow.enhanceQuery(context);
        },
        getStrategyName() + " should throw SecurityException for not-allowed values");
  }

  // ============================================================================
  // MISSING CONSTRAINTS TESTS
  // ============================================================================

  @Test
  void testMissingConstraints_AddsRequiredConstraints() {
    // Given: Query without required constraints
    String query = buildEmptyQuery();
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should add required constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    // Check for both possible orders since Set iteration order may vary
    boolean hasCorrectConstraint =
        enhancedQuery.contains("namespace=~\"observability|demo\"")
            || enhancedQuery.contains("namespace=~\"demo|observability\"");
    assertTrue(hasCorrectConstraint, getStrategyName() + " should add required constraints");
  }

  @Test
  void testMissingConstraints_WildcardAccess_NoAdditionalConstraints() {
    // Given: Query without constraints but with wildcard access
    String query = buildEmptyQuery();
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("*") // Wildcard access
            );

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should not add additional constraints for wildcard access
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertEquals(
        query,
        enhancedQuery,
        getStrategyName() + " should not add constraints for wildcard access");
  }

  // ============================================================================
  // EMPTY/NULL QUERY TESTS
  // ============================================================================

  @Test
  void testEmptyQuery_ReturnsSafeQuery() {
    // Given: Empty query
    String query = "";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should return safe query with constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    // Check for both possible orders since Set iteration order may vary
    boolean hasCorrectConstraint =
        enhancedQuery.contains("namespace=~\"observability|demo\"")
            || enhancedQuery.contains("namespace=~\"demo|observability\"");
    assertTrue(
        hasCorrectConstraint, getStrategyName() + " should return safe query with constraints");
  }

  @Test
  void testNullQuery_ReturnsSafeQuery() {
    // Given: Null query
    String query = null;
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should return safe query with constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    // Check for both possible orders since Set iteration order may vary
    boolean hasCorrectConstraint =
        enhancedQuery.contains("namespace=~\"observability|demo\"")
            || enhancedQuery.contains("namespace=~\"demo|observability\"");
    assertTrue(
        hasCorrectConstraint, getStrategyName() + " should return safe query with constraints");
  }

  // ============================================================================
  // CONFIGURATION KEYS TESTS
  // ============================================================================

  @Test
  void testConfigurationKeys_Skipped() {
    // Given: Query with configuration keys
    String query = buildQueryWithConstraint("labels", "namespace,service");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("observability", "demo"));

    // When
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(getQueryLanguage())
            .build();

    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should preserve configuration keys
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("labels=\"namespace,service\""),
        getStrategyName() + " should preserve configuration keys");
  }

  // ============================================================================
  // ABSTRACT METHODS
  // ============================================================================

  protected abstract String buildQueryWithConstraint(String name, String value);

  protected abstract String buildQueryWithNotEqualsConstraint(String name, String value);

  protected abstract String buildEmptyQuery();

  protected abstract void assertContainsConstraint(String result, String name, String value);

  protected abstract void assertContainsNotEqualsConstraint(
      String result, String name, String value);
}
