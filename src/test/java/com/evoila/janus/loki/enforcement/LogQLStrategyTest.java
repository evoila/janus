package com.evoila.janus.loki.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.base.BaseUnitTest;
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

@Tag("loki")
@Tag("logql")
class LogQLStrategyTest extends BaseUnitTest {

  @Mock private OAuthToken mockToken;

  @Mock private QueryEnforcementFlow queryEnforcementFlow;

  @BeforeEach
  void setUp() {
    // Setup is handled by BaseUnitTest

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
  void testEnforceQuery_NamespaceNotAllowed_ThrowsSecurityException() {
    // Given: Query requests namespace="demo" but user is only allowed namespace="observability"
    String query = "{namespace=\"demo\"}";
    Map<String, Set<String>> labelConstraints = Map.of("namespace", Set.of("observability"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When & Then: Should throw SecurityException for unauthorized access
    SecurityException exception =
        assertThrows(
            SecurityException.class,
            () -> {
              queryEnforcementFlow.enhanceQuery(context);
            });

    assertTrue(
        exception.getMessage().contains("Unauthorized label value"),
        "Exception message should indicate unauthorized namespace access");
  }

  @Test
  void testEnforceQuery_NamespaceEmpty_EnhancesToAllowedValue() {
    // Given: Query requests namespace="" but user is only allowed namespace="observability"
    String query = "{namespace=\"\"}";
    Map<String, Set<String>> labelConstraints = Map.of("namespace", Set.of("observability"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should enhance empty namespace to allowed value
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=\"observability\""),
        "Should enhance empty namespace to allowed value");
    assertFalse(
        enhancedQuery.contains("namespace=\"\""), "Should not preserve empty namespace value");
  }

  @Test
  void testEnforceQuery_WithSpecificServiceConstraint_RespectsUserConstraint() {
    // Given: User asks for specific service within their authorization scope
    String query = "{service=\"microservices-order-service\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of("*order-service", "microservices-stock-service"),
            "namespace", Set.of("demo"),
            "application", Set.of("*order-service", "*stock-service"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should respect user's specific constraint and add missing required constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("service=\"microservices-order-service\""),
        "Should preserve user's specific service constraint");
    assertTrue(
        enhancedQuery.contains("namespace=~\"demo\""), "Should add missing namespace constraint");
    assertTrue(
        enhancedQuery.contains("application=~"), "Should add missing application constraint");
  }

  @Test
  void testEnforceQuery_WithWildcardLabelValueAndNoConstraints_KeepsWildcard() {
    // Given: User provides "*" as a label value with no specific constraints for that label
    String query = "{service=\"*\"}";
    Map<String, Set<String>> labelConstraints = Map.of("namespace", Set.of("demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should keep wildcard and add missing constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("service=~\".*\""),
        "Should convert wildcard service value to regex pattern");
    assertTrue(
        enhancedQuery.contains("namespace=~\"demo\""), "Should add missing namespace constraint");
  }

  @Test
  void testEnforceQuery_WithRegexWildcard_ExpandsOrAllows() {
    // Given: Query with regex wildcard pattern
    String query = "{namespace=\".*\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of("namespace", Set.of("observability", "demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should expand regex wildcard to allowed values
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\"observability|demo\"")
            || enhancedQuery.contains("namespace=~\"demo|observability\""),
        "Should expand regex wildcard to allowed values");
  }

  @Test
  void testEnforceQuery_WithNotEqualsOperator_ReplacesWithAllowedValues() {
    // Given: Query with not-equals operator
    String query = "{namespace!=\"observability\"}";
    Map<String, Set<String>> labelConstraints =
        Map.of("namespace", Set.of("observability", "demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should replace with remaining allowed value
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertEquals(
        "{namespace=\"demo\"}",
        enhancedQuery,
        "Should replace not-equals with remaining allowed value");
  }

  @Test
  void testEnforceQuery_SimpleLogQuery_EnhancesWithConstraints() {
    // Given: Simple log query
    String query = "{}";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability"),
            "service", Set.of("order-service"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should add missing constraints
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=~\"observability\""), "Should add namespace constraint");
    assertTrue(
        enhancedQuery.contains("service=~\"order-service\""), "Should add service constraint");
  }

  @Test
  void testEnforceQuery_ComplexLokiQuery_EnhancesCorrectly() {
    // Given: Complex Loki query with existing labels
    String query =
        "{namespace=\"demo\", service=\"order-service\"} | json | line_format \"{{.message}}\"";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("demo", "observability"),
            "service", Set.of("order-service", "stock-service"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.LOGQL)
            .build();

    // When
    var result = queryEnforcementFlow.enhanceQuery(context);

    // Then: Should preserve existing labels and enhance query
    assertTrue(result.isSuccess());
    String enhancedQuery = result.getEnhancedQuery();
    assertNotNull(enhancedQuery);
    assertTrue(
        enhancedQuery.contains("namespace=\"demo\""), "Should preserve existing namespace label");
    assertTrue(
        enhancedQuery.contains("service=\"order-service\""),
        "Should preserve existing service label");
    assertTrue(enhancedQuery.contains("| json | line_format"), "Should preserve query pipeline");
  }
}
