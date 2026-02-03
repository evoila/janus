package com.evoila.janus.tempo.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for TraceQL query parsing and enhancement.
 *
 * <p>Validates that TraceQL-specific syntax (&&-separator, comparison operators, intrinsic
 * attributes) is correctly handled by the label processing pipeline.
 */
@Tag("tempo")
@Tag("traceql")
class TraceQLParserTest {

  private static final QuerySyntax TRACEQL_SYNTAX =
      QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);

  private static final QuerySyntax PROMQL_SYNTAX =
      QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL);

  // ========================================================================
  // Test 1: && Separator correctly parsed
  // ========================================================================

  @Test
  void shouldSplitTraceQLLabelsOnAndSeparator() {
    String labels = "nestedSetParent<0 && name=\"lets-go\" && status=error";

    List<String> pairs = LabelProcessor.parseLabelPairs(labels, TRACEQL_SYNTAX);

    assertEquals(3, pairs.size());
    assertEquals("nestedSetParent<0", pairs.get(0));
    assertEquals("name=\"lets-go\"", pairs.get(1));
    assertEquals("status=error", pairs.get(2));
  }

  @Test
  void shouldHandleSingleTraceQLPairWithoutSeparator() {
    String labels = "status=error";

    List<String> pairs = LabelProcessor.parseLabelPairs(labels, TRACEQL_SYNTAX);

    assertEquals(1, pairs.size());
    assertEquals("status=error", pairs.get(0));
  }

  @Test
  void shouldRespectQuotesWhenSplittingTraceQL() {
    String labels = "name=\"hello && world\" && status=ok";

    List<String> pairs = LabelProcessor.parseLabelPairs(labels, TRACEQL_SYNTAX);

    assertEquals(2, pairs.size());
    assertEquals("name=\"hello && world\"", pairs.get(0));
    assertEquals("status=ok", pairs.get(1));
  }

  // ========================================================================
  // Test 2: Intrinsic attributes pass through without enforcement
  // ========================================================================

  @Test
  void shouldPassThroughIntrinsicAttributes() {
    String labels = "status=error && name=\"lets-go\"";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    // Intrinsics should be preserved as-is
    assertTrue(enhanced.contains("status=error"), "status intrinsic should be preserved");
    assertTrue(enhanced.contains("name=\"lets-go\""), "name intrinsic should be preserved");
    // Missing constraint should be added
    assertTrue(enhanced.contains("namespace"), "namespace constraint should be added");
  }

  @Test
  void shouldNotEnforceIntrinsicDuration() {
    String labels = "duration>100ms && nestedSetParent<0";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains("duration>100ms"), "duration intrinsic should be preserved");
    assertTrue(
        enhanced.contains("nestedSetParent<0"), "nestedSetParent intrinsic should be preserved");
  }

  // ========================================================================
  // Test 3: Comparison operators (<, >, <=, >=) parsed correctly
  // ========================================================================

  @Test
  void shouldParseComparisonOperators() {
    String labels = "duration>100ms && nestedSetParent<0";

    Map<String, LabelConstraintInfo> parsed =
        LabelProcessor.parseLabelsSection(labels, TRACEQL_SYNTAX);

    assertTrue(parsed.containsKey("duration"), "Should contain duration");
    assertEquals(">", parsed.get("duration").operator());
    assertEquals("100ms", parsed.get("duration").value());

    assertTrue(parsed.containsKey("nestedSetParent"), "Should contain nestedSetParent");
    assertEquals("<", parsed.get("nestedSetParent").operator());
    assertEquals("0", parsed.get("nestedSetParent").value());
  }

  @Test
  void shouldParseGreaterThanOrEqualOperator() {
    String labels = "duration>=500ms";

    Map<String, LabelConstraintInfo> parsed =
        LabelProcessor.parseLabelsSection(labels, TRACEQL_SYNTAX);

    assertTrue(parsed.containsKey("duration"));
    assertEquals(">=", parsed.get("duration").operator());
    assertEquals("500ms", parsed.get("duration").value());
  }

  @Test
  void shouldParseLessThanOrEqualOperator() {
    String labels = "childCount<=5";

    Map<String, LabelConstraintInfo> parsed =
        LabelProcessor.parseLabelsSection(labels, TRACEQL_SYNTAX);

    assertTrue(parsed.containsKey("childCount"));
    assertEquals("<=", parsed.get("childCount").operator());
    assertEquals("5", parsed.get("childCount").value());
  }

  // ========================================================================
  // Test 4: Mixed intrinsics and enforced labels
  // ========================================================================

  @Test
  void shouldEnforceNonIntrinsicLabelsAndPassThroughIntrinsics() {
    String labels = ".namespace=\"demo\" && status=error";
    Map<String, Set<String>> constraints = Map.of(".namespace", Set.of("demo"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    // .namespace should be enforced
    assertTrue(enhanced.contains(".namespace"), ".namespace should be present");
    // status is intrinsic, should pass through
    assertTrue(enhanced.contains("status=error"), "status intrinsic should be preserved");
  }

  // ========================================================================
  // Test 5: Only intrinsics -> constraints added
  // ========================================================================

  @Test
  void shouldAddConstraintsWhenOnlyIntrinsicsPresent() {
    String labels = "status=error";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains("status=error"), "status should be preserved");
    assertTrue(enhanced.contains("namespace"), "namespace constraint should be added");
    // Separator should be " && " not ","
    assertFalse(enhanced.contains(","), "TraceQL should not use comma separator, got: " + enhanced);
  }

  // ========================================================================
  // Test 6: Empty query with TraceQL language
  // ========================================================================

  @Test
  void shouldBuildConstraintsInTraceQLFormatForEmptyLabels() {
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(null, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    assertTrue(
        result.getEnhancedQuery().contains("namespace"), "Should contain namespace constraint");
  }

  @Test
  void shouldBuildMultipleConstraintsWithTraceQLSeparator() {
    Map<String, Set<String>> constraints =
        Map.of("namespace", Set.of("demo"), "cluster", Set.of("prod"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(null, constraints, TRACEQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    // Should use " && " separator between constraints
    assertTrue(enhanced.contains(" && "), "Should use && separator, got: " + enhanced);
    assertFalse(enhanced.contains(","), "Should not use comma separator, got: " + enhanced);
  }

  // ========================================================================
  // Test 7: Unquoted values preserved
  // ========================================================================

  @Test
  void shouldPreserveUnquotedIntrinsicValues() {
    String labels = "status=error";

    Map<String, LabelConstraintInfo> parsed =
        LabelProcessor.parseLabelsSection(labels, TRACEQL_SYNTAX);

    assertTrue(parsed.containsKey("status"));
    assertEquals("error", parsed.get("status").value());
    assertFalse(parsed.get("status").quoted(), "status=error should be unquoted");
  }

  @Test
  void shouldPreserveQuotedValues() {
    String labels = "name=\"lets-go\"";

    Map<String, LabelConstraintInfo> parsed =
        LabelProcessor.parseLabelsSection(labels, TRACEQL_SYNTAX);

    assertTrue(parsed.containsKey("name"));
    assertEquals("lets-go", parsed.get("name").value());
    assertTrue(parsed.get("name").quoted(), "name=\"lets-go\" should be quoted");
  }

  // ========================================================================
  // Test 8: PromQL/LogQL still uses comma separator
  // ========================================================================

  @Test
  void shouldStillUseCommaSeparatorForPromQL() {
    Map<String, Set<String>> constraints =
        Map.of("namespace", Set.of("demo"), "cluster", Set.of("prod"));

    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(null, constraints, PROMQL_SYNTAX);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains(","), "PromQL should use comma separator, got: " + enhanced);
    assertFalse(enhanced.contains(" && "), "PromQL should not use && separator, got: " + enhanced);
  }

  @Test
  void shouldParsePromQLWithCommaSeparator() {
    String labels = "namespace=\"demo\",service=\"api\"";

    List<String> pairs = LabelProcessor.parseLabelPairs(labels, PROMQL_SYNTAX);

    assertEquals(2, pairs.size());
    assertEquals("namespace=\"demo\"", pairs.get(0));
    assertEquals("service=\"api\"", pairs.get(1));
  }

  // ========================================================================
  // Test 9: Full QueryEnhancementProcessor integration
  // ========================================================================

  @Test
  void shouldEnhanceTraceQLQueryWithExistingLabels() {
    String query = "{nestedSetParent<0 && name=\"lets-go\" && status=error}";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.TRACEQL)
            .build();

    EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Enhancement should succeed");
    String enhanced = result.getEnhancedQuery();
    // Intrinsics should be preserved
    assertTrue(enhanced.contains("nestedSetParent<0"), "nestedSetParent should be in: " + enhanced);
    assertTrue(enhanced.contains("name=\"lets-go\""), "name should be in: " + enhanced);
    assertTrue(enhanced.contains("status=error"), "status should be in: " + enhanced);
    // Constraint should be added
    assertTrue(enhanced.contains("namespace"), "namespace should be in: " + enhanced);
    // Should use TraceQL separator
    assertTrue(enhanced.contains(" && "), "Should use && separator in: " + enhanced);
  }

  @Test
  void shouldEnhanceTraceQLQueryWithoutExistingLabels() {
    String query = "{}";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.TRACEQL)
            .build();

    EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Enhancement should succeed");
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains("namespace"), "namespace should be in: " + enhanced);
  }

  @Test
  void shouldEnhanceTraceQLQueryWithMixedLabels() {
    String query = "{.namespace=\"demo\" && status=error}";
    Map<String, Set<String>> constraints = Map.of(".namespace", Set.of("demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(constraints)
            .language(QueryContext.QueryLanguage.TRACEQL)
            .build();

    EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Enhancement should succeed");
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains(".namespace"), ".namespace should be in: " + enhanced);
    assertTrue(
        enhanced.contains("status=error"), "status intrinsic should be preserved in: " + enhanced);
  }

  // ========================================================================
  // Test 10: QuerySyntax factory
  // ========================================================================

  @Test
  void shouldReturnCorrectSyntaxForEachLanguage() {
    QuerySyntax promql = QuerySyntax.forLanguage(QueryContext.QueryLanguage.PROMQL);
    QuerySyntax logql = QuerySyntax.forLanguage(QueryContext.QueryLanguage.LOGQL);
    QuerySyntax traceql = QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);

    assertEquals(",", promql.separator());
    assertEquals(",", logql.separator());
    assertEquals(" && ", traceql.separator());

    assertFalse(promql.isIntrinsicAttribute("status"));
    assertFalse(logql.isIntrinsicAttribute("status"));
    assertTrue(traceql.isIntrinsicAttribute("status"));
    assertTrue(traceql.isIntrinsicAttribute("duration"));
    assertTrue(traceql.isIntrinsicAttribute("nestedSetParent"));
    assertFalse(traceql.isIntrinsicAttribute("namespace"));
  }
}
