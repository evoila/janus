package com.evoila.janus.common.enforcement.strategy;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.label.LabelProcessor;
import com.evoila.janus.common.enforcement.model.dto.LabelConstraintInfo;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QuerySyntax;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.utils.LabelPatternUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LabelProcessor Tests")
class LabelProcessorTest {

  /** Helper method to simulate single label enhancement using the new batch approach */
  private Optional<String> enhanceSingleLabel(
      String labelName, LabelConstraintInfo constraint, Set<String> allowedValues) {
    // Create a label section with the single label
    String labelSection = labelName + constraint.operator() + "\"" + constraint.value() + "\"";

    // Convert to the expected format for enhanceLabelsSection
    Map<String, Set<String>> constraintsMap = new HashMap<>();
    constraintsMap.put(labelName, allowedValues);

    // Use the batch processing approach
    EnhancementResult result = LabelProcessor.enhanceLabelsSection(labelSection, constraintsMap);

    if (result.isSuccess()) {
      return Optional.of(result.getEnhancedQuery());
    } else {
      return Optional.empty();
    }
  }

  @Test
  @DisplayName("Should parse !~ operator correctly")
  void testParseLabelPair_NotRegexMatchOperator() {
    // Given
    String labelPair = "k8s_namespace_name!~\"observability\"";

    // When
    var result = LabelProcessor.parseLabelsSection(labelPair);

    // Then
    assertNotNull(result);
    assertEquals(1, result.size());
    assertTrue(result.containsKey("k8s_namespace_name"));

    var constraintInfo = result.get("k8s_namespace_name");
    assertEquals("observability", constraintInfo.value());
    assertEquals("!~", constraintInfo.operator());
  }

  @Test
  @DisplayName("Should enhance !~ operator with single remaining value")
  void testEnhanceSingleLabel_NotRegexMatchOperator_SingleRemainingValue() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("observability", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    assertEquals("k8s_namespace_name=\"demo\"", result.get());
  }

  @Test
  @DisplayName("Should enhance !~ operator with multiple remaining values")
  void testEnhanceSingleLabel_NotRegexMatchOperator_MultipleRemainingValues() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("observability", "!~");
    Set<String> allowedValues = Set.of("observability", "demo", "production", "staging");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("k8s_namespace_name=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("demo"));
    assertTrue(resultStr.contains("production"));
    assertTrue(resultStr.contains("staging"));
    assertFalse(resultStr.contains("observability"));
  }

  @Test
  @DisplayName("Should enhance !~ operator with regex pattern")
  void testEnhanceSingleLabel_NotRegexMatchOperator_RegexPattern() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("obs.*", "!~");
    Set<String> allowedValues = Set.of("observability", "demo", "production");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();

    // The !~ operator should exclude values that match the regex pattern
    // "obs.*" should match "observability", so it should be excluded
    assertTrue(resultStr.startsWith("k8s_namespace_name=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("demo"));
    assertTrue(resultStr.contains("production"));
    assertFalse(
        resultStr.contains("observability"),
        "observability should be excluded as it matches the regex pattern 'obs.*'");
  }

  @Test
  @DisplayName("Should enhance !~ operator with no remaining values")
  void testEnhanceSingleLabel_NotRegexMatchOperator_NoRemainingValues() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo(".*", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");

    // When & Then: Should throw SecurityException when all values are excluded
    String labelSection = labelName + constraint.operator() + "\"" + constraint.value() + "\"";
    Map<String, Set<String>> constraintsMap = new HashMap<>();
    constraintsMap.put(labelName, allowedValues);

    SecurityException exception =
        assertThrows(
            SecurityException.class,
            () -> LabelProcessor.enhanceLabelsSection(labelSection, constraintsMap));

    assertTrue(
        exception.getMessage().contains("Unauthorized label value"),
        "Should throw SecurityException when all values are excluded");
  }

  @Test
  @DisplayName("Should preserve !~ operator with wildcard access")
  void testEnhanceSingleLabel_NotRegexMatchOperator_WildcardAccess() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("observability", "!~");
    Set<String> allowedValues = Set.of(".+"); // Wildcard access

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    assertEquals("k8s_namespace_name!~\"observability\"", result.get());
  }

  @Test
  @DisplayName("Should enhance labels section with !~ operator")
  void testEnhanceLabelsSection_NotRegexMatchOperator() {
    // Given
    String existingLabels = "k8s_namespace_name!~\"observability\"";
    Map<String, Set<String>> labelConstraints =
        Map.of("k8s_namespace_name", Set.of("observability", "demo"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertEquals("k8s_namespace_name=\"demo\"", enhancedLabels);
  }

  @Test
  @DisplayName("Should enhance labels section with !~ operator and multiple values")
  void testEnhanceLabelsSection_NotRegexMatchOperator_MultipleValues() {
    // Given
    String existingLabels = "k8s_namespace_name!~\"observability\",service=\"order-service\"";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "k8s_namespace_name", Set.of("observability", "demo", "production"),
            "service", Set.of("order-service", "stock-service"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertTrue(
        enhancedLabels.contains("k8s_namespace_name=~\"demo|production\"")
            || enhancedLabels.contains("k8s_namespace_name=~\"production|demo\""),
        "Should contain either order of the remaining values");
    assertTrue(enhancedLabels.contains("service=\"order-service\""));
    assertFalse(enhancedLabels.contains("observability"));
  }

  @Test
  @DisplayName("Should enhance labels section with mixed operators")
  void testEnhanceLabelsSection_MixedOperators() {
    // Given
    String existingLabels =
        "k8s_namespace_name!~\"observability\",service=\"order-service\",pod=~\"order.*\"";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "k8s_namespace_name", Set.of("observability", "demo"),
            "service", Set.of("order-service", "stock-service"),
            "pod", Set.of("order-pod-1", "order-pod-2", "stock-pod-1"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertTrue(enhancedLabels.contains("k8s_namespace_name=\"demo\""));
    assertTrue(enhancedLabels.contains("service=\"order-service\""));
    assertTrue(enhancedLabels.contains("pod=~\"order-pod-1|order-pod-2\""));
  }

  @Test
  @DisplayName("Should handle !~ operator with empty string")
  void testEnhanceSingleLabel_NotRegexMatchOperator_EmptyString() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("", "!~");
    Set<String> allowedValues = Set.of("observability", "demo");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("k8s_namespace_name=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("observability"));
    assertTrue(resultStr.contains("demo"));
  }

  @Test
  @DisplayName("Should handle !~ operator with escaped regex characters")
  void testEnhanceSingleLabel_NotRegexMatchOperator_EscapedCharacters() {
    // Given
    String labelName = "service";
    var constraint = new LabelConstraintInfo("order\\.service", "!~");
    Set<String> allowedValues = Set.of("order.service", "stock.service", "user.service");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("service=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("stock.service"));
    assertTrue(resultStr.contains("user.service"));
    assertFalse(
        resultStr.contains("order.service"),
        "order.service should be excluded as it matches the escaped pattern");
  }

  @Test
  @DisplayName("Should handle !~ operator with complex regex pattern")
  void testEnhanceSingleLabel_NotRegexMatchOperator_ComplexRegex() {
    // Given
    String labelName = "service";
    var constraint = new LabelConstraintInfo("order.*|demo.*", "!~");
    Set<String> allowedValues =
        Set.of("order-service", "demo-service", "stock-service", "user-service");

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertTrue(result.isPresent());
    String resultStr = result.get();
    assertTrue(resultStr.startsWith("service=~\""));
    assertTrue(resultStr.endsWith("\""));
    assertTrue(resultStr.contains("stock-service"));
    assertTrue(resultStr.contains("user-service"));
    assertFalse(
        resultStr.contains("order-service"),
        "order-service should be excluded as it matches the regex pattern");
    assertFalse(
        resultStr.contains("demo-service"),
        "demo-service should be excluded as it matches the regex pattern");
  }

  @Test
  @DisplayName("Should handle !~ operator with null allowed values")
  void testEnhanceSingleLabel_NotRegexMatchOperator_NullAllowedValues() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("observability", "!~");
    Set<String> allowedValues = null;

    // When: Should preserve original operator when no constraints are defined
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then: Should preserve the original !~ operator
    assertTrue(result.isPresent(), "Should return a result when no constraints are defined");
    assertEquals("k8s_namespace_name!~\"observability\"", result.get());
  }

  @Test
  @DisplayName("Should handle !~ operator with empty allowed values")
  void testEnhanceSingleLabel_NotRegexMatchOperator_EmptyAllowedValues() {
    // Given
    String labelName = "k8s_namespace_name";
    var constraint = new LabelConstraintInfo("observability", "!~");
    Set<String> allowedValues = Set.of();

    // When
    var result = enhanceSingleLabel(labelName, constraint, allowedValues);

    // Then
    assertFalse(result.isPresent(), "Should return empty when no allowed values exist");
  }

  @Test
  void testWildcardPatternNormalization() {
    // Given
    String labels = "namespace=~\"obs.*\", service=\"order-service\", pod=~\"order.*\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of(
            "namespace", new LabelConstraintInfo("obs.*", "=~"),
            "service", new LabelConstraintInfo("order-service", "="),
            "pod", new LabelConstraintInfo("order.*", "=~"));
    Set<String> existingLabelNames = Set.of("namespace", "service", "pod");
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability", "demo"),
            "service", Set.of("order-service", "stock-service"),
            "pod", Set.of("order-pod", "stock-pod"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then
    assertNotNull(result);
    assertTrue(result.contains("namespace=~\"obs.*\""));
    assertTrue(result.contains("service=\"order-service\""));
    assertTrue(result.contains("pod=~\"order.*\""));
  }

  @Test
  void testEmptyStringReplacementWithSingleAllowedValue() {
    // Given: Empty string with single allowed value
    String labels = "namespace=\"\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of("namespace", new LabelConstraintInfo("", "="));
    Set<String> existingLabelNames = Set.of("namespace");
    Map<String, Set<String>> labelConstraints = Map.of("namespace", Set.of("observability"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then: Should replace empty string with the single allowed value
    assertNotNull(result);
    assertEquals("namespace=\"observability\"", result);
  }

  @Test
  void testEmptyStringReplacementWithMultipleAllowedValues() {
    // Given: Empty string with multiple allowed values
    String labels = "namespace=\"\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of("namespace", new LabelConstraintInfo("", "="));
    Set<String> existingLabelNames = Set.of("namespace");
    Map<String, Set<String>> labelConstraints =
        Map.of("namespace", Set.of("observability", "demo"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then: Should normalize to =~".+" since .+ is a regex pattern
    assertNotNull(result);
    assertEquals("namespace=~\".+\"", result);
  }

  @Test
  void testUrlDecodingFix() {
    // Given
    String labels = "namespace=~\"obs. \", service=\"order-service\"";

    // When
    String result = LabelPatternUtils.fixUrlDecodingIssues(labels);

    // Then
    assertNotNull(result);
    assertTrue(result.contains("namespace=~\"obs.+\""));
    assertTrue(result.contains("service=\"order-service\""));
  }

  @Test
  @DisplayName("Should not enhance __ignore_usage__ label")
  void testIgnoreUsageLabelNotEnhanced() {
    // Given
    String existingLabels = "__ignore_usage__=\"true\"";
    Map<String, Set<String>> labelConstraints = Map.of("__ignore_usage__", Set.of("true", "false"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertEquals(
        "__ignore_usage__=\"true\"", enhancedLabels, "Should not enhance __ignore_usage__ label");
  }

  @Test
  @DisplayName("Should not enhance __ignore_usage__ label even with other labels")
  void testIgnoreUsageLabelNotEnhancedWithOtherLabels() {
    // Given
    String existingLabels =
        "namespace=\"demo\",__ignore_usage__=\"true\",service=\"order-service\"";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("demo", "observability"),
            "__ignore_usage__", Set.of("true", "false"),
            "service", Set.of("order-service", "stock-service"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertTrue(enhancedLabels.contains("namespace=\"demo\""));
    assertTrue(enhancedLabels.contains("__ignore_usage__=\"true\""));
    assertTrue(enhancedLabels.contains("service=\"order-service\""));
  }

  @Test
  @DisplayName("Should fix the original issue with alertmanager_dispatcher_aggregation_groups")
  void testOriginalIssueFix() {
    // Given: This test reproduces the original issue that led to the creation of this fix
    String existingLabels = "alertmanager_dispatcher_aggregation_groups!~\"observability\"";
    Map<String, Set<String>> labelConstraints =
        Map.of("alertmanager_dispatcher_aggregation_groups", Set.of("observability", "demo"));

    // When
    var result = LabelProcessor.enhanceLabelsSection(existingLabels, labelConstraints);

    // Then
    assertTrue(result.isSuccess());
    String enhancedLabels = result.getEnhancedQuery();
    assertEquals(
        "alertmanager_dispatcher_aggregation_groups=\"demo\"",
        enhancedLabels,
        "Should correctly handle the !~ operator for alertmanager_dispatcher_aggregation_groups");
  }

  @Test
  @DisplayName("Should preserve empty string with != operator (container!=\"\")")
  void testPreserveEmptyStringWithNotEqualsOperator() {
    // Given: Empty string with != operator (common in PromQL for container!="")
    String labels = "container!=\"\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of("container", new LabelConstraintInfo("", "!="));
    Set<String> existingLabelNames = Set.of("container");
    Map<String, Set<String>> labelConstraints =
        Map.of("container", Set.of("app", "sidecar", "init"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then: Should preserve the original expression, not normalize to .+
    assertNotNull(result);
    assertEquals("container!=\"\"", result, "Empty string with != operator should be preserved");
  }

  @Test
  @DisplayName("Should preserve empty string with !~ operator")
  void testPreserveEmptyStringWithNotRegexOperator() {
    // Given: Empty string with !~ operator
    String labels = "container!~\"\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of("container", new LabelConstraintInfo("", "!~"));
    Set<String> existingLabelNames = Set.of("container");
    Map<String, Set<String>> labelConstraints =
        Map.of("container", Set.of("app", "sidecar", "init"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then: Should preserve the original expression, not normalize to .+
    assertNotNull(result);
    assertEquals("container!~\"\"", result, "Empty string with !~ operator should be preserved");
  }

  @Test
  @DisplayName("Should still normalize empty string with = operator")
  void testNormalizeEmptyStringWithEqualsOperator() {
    // Given: Empty string with = operator (should still be normalized)
    String labels = "namespace=\"\"";
    Map<String, LabelConstraintInfo> parsedConstraints =
        Map.of("namespace", new LabelConstraintInfo("", "="));
    Set<String> existingLabelNames = Set.of("namespace");
    Map<String, Set<String>> labelConstraints =
        Map.of("namespace", Set.of("observability", "demo"));

    // When
    String result =
        LabelProcessor.normalizeWildcardPatterns(
            labels, parsedConstraints, existingLabelNames, labelConstraints);

    // Then: Should normalize to =~".+" since .+ is a regex pattern
    assertNotNull(result);
    assertEquals(
        "namespace=~\".+\"",
        result,
        "Empty string with = operator should be normalized to =~\".+\"");
  }

  // ========================================================================
  // Targeted coverage: new uncovered lines
  // ========================================================================

  @Test
  @DisplayName("Should parse labels using 1-arg parseLabelPairs overload")
  void testParseLabelPairs_OneArgOverload() {
    // Covers LabelProcessor.java line 466
    var result = LabelProcessor.parseLabelPairs("namespace=\"demo\",service=\"order\"");

    assertNotNull(result);
    assertEquals(2, result.size());
  }

  @Test
  @DisplayName("Should handle empty string with != operator (preserve as-is)")
  void testEmptyValueWithNotEqualsOperator() {
    // Covers LabelProcessor.java line 656
    String labelSection = "namespace!=\"\"";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo", "prod"));

    EnhancementResult result = LabelProcessor.enhanceLabelsSection(labelSection, constraints);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains("namespace!=\"\""), "Should preserve namespace!=\"\" as-is");
  }

  @Test
  @DisplayName("Should convert != to !~ for regex pattern values during parsing")
  void testConvertNotEqualsToNotRegexMatch() {
    // Covers LabelProcessor.java lines 583-585
    var parsed = LabelProcessor.parseLabelsSection("service!=\"order.*\"");

    assertNotNull(parsed);
    assertTrue(parsed.containsKey("service"));
    assertEquals(
        "!~",
        parsed.get("service").operator(),
        "Should convert != to !~ when value is a regex pattern");
  }

  @Test
  @DisplayName("Should skip invalid ! prefix pairs in label parsing")
  void testParseLabelPair_InvalidBangPrefix() {
    // Covers LabelProcessor.java lines 536-538
    String labelSection = "!namespace=\"demo\"";
    Map<String, Set<String>> constraints = Map.of("namespace", Set.of("demo"));

    // Should handle gracefully even though the label is invalid
    EnhancementResult result = LabelProcessor.enhanceLabelsSection(labelSection, constraints);

    assertNotNull(result);
  }

  @Test
  @DisplayName("Should build constraints from labelConstraints skipping labels key")
  void testBuildConstraintsSkipsLabelsKey() {
    // Covers LabelProcessor.java lines 212-213
    // When enhancing empty labels, buildConstraintsFromLabelConstraints is called
    Map<String, Set<String>> constraints =
        Map.of(
            "labels", Set.of("*"),
            "namespace", Set.of("demo"));

    EnhancementResult result = LabelProcessor.enhanceLabelsSection("", constraints);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(enhanced.contains("namespace"), "Should contain namespace constraint");
    assertFalse(enhanced.contains("labels="), "Should not contain 'labels' as a label constraint");
  }

  @Test
  @DisplayName(
      "TraceQL: should preserve original spacing for labels without enforcement constraints")
  void traceQL_preservesOriginalSpacing_whenNoConstraints() {
    // Regression test: LabelProcessor was stripping spaces around operators
    // "undefined != nil" became "undefined!=nil" which broke Tempo's parser
    String labels = "nestedSetParent<0 && true && undefined != nil";
    Map<String, Set<String>> constraints = Map.of(); // no enforcement constraints

    QuerySyntax traceqlSyntax = QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);
    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, traceqlSyntax);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    // Spaces around != must be preserved
    assertTrue(
        enhanced.contains("undefined != nil"),
        "Should preserve original spacing: got '" + enhanced + "'");
    // Intrinsic attribute must be preserved
    assertTrue(enhanced.contains("nestedSetParent<0"), "Should preserve intrinsic attribute");
    // Passthrough keyword must be preserved
    assertTrue(enhanced.contains("true"), "Should preserve passthrough keyword 'true'");
  }

  @Test
  @DisplayName("TraceQL: should split on && without surrounding spaces")
  void traceQL_splitsOnCompactAmpersand() {
    // Regression test: Grafana may encode "true&&" without a space before "&&",
    // producing "true&& duration > 0" after URL decoding. The separator split must
    // handle "&&" regardless of surrounding whitespace.
    String labels = "nestedSetParent<0 && true&& duration > 0";
    Map<String, Set<String>> constraints = Map.of();

    QuerySyntax traceqlSyntax = QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);
    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, traceqlSyntax);

    assertTrue(result.isSuccess(), "Enhancement should succeed, got: " + result);
    String enhanced = result.getEnhancedQuery();

    assertTrue(enhanced.contains("nestedSetParent<0"), "Should preserve intrinsic attribute");
    assertTrue(enhanced.contains("true"), "Should preserve passthrough keyword 'true'");
    assertTrue(enhanced.contains("duration > 0"), "Should preserve intrinsic 'duration > 0'");
  }

  @Test
  @DisplayName("TraceQL: should preserve spacing for != nil even with empty constraint set")
  void traceQL_preservesSpacing_notEqualsNil_emptyConstraints() {
    // Regression test: resource.service.name != nil was reconstructed as
    // resource.service.name!=nil (spaces stripped) when allowedValues was an empty Set
    // rather than null. The OperatorHandlers must use originalText in pass-through branches.
    String labels =
        "nestedSetParent<0 && span.audit-id=\"d4ed60f3\" && resource.service.name != nil";
    // Empty map simulates admin user with no enforcement constraints
    Map<String, Set<String>> constraints = Map.of();

    QuerySyntax traceqlSyntax = QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);
    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, traceqlSyntax);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    assertTrue(
        enhanced.contains("resource.service.name != nil"),
        "Should preserve spaces around !=: got '" + enhanced + "'");
    assertTrue(
        enhanced.contains("span.audit-id=\"d4ed60f3\""), "Should preserve quoted attribute value");
  }

  @Test
  @DisplayName("TraceQL: should preserve both constraints when same label appears twice")
  void traceQL_preservesDuplicateLabelConstraints() {
    // Regression test: when the same label name appears with different operators
    // (e.g., resource.service.name="grafana" AND resource.service.name != nil),
    // the Map-based parseLabelsSection drops the first entry. Both must be preserved.
    String labels =
        "nestedSetParent<0 && resource.service.name=\"grafana\" && resource.service.name != nil";
    Map<String, Set<String>> constraints = Map.of();

    QuerySyntax traceqlSyntax = QuerySyntax.forLanguage(QueryContext.QueryLanguage.TRACEQL);
    EnhancementResult result =
        LabelProcessor.enhanceLabelsSection(labels, constraints, traceqlSyntax);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    assertTrue(
        enhanced.contains("resource.service.name=\"grafana\""),
        "Should preserve resource.service.name=\"grafana\": got '" + enhanced + "'");
    assertTrue(
        enhanced.contains("resource.service.name != nil"),
        "Should preserve resource.service.name != nil: got '" + enhanced + "'");
    assertTrue(enhanced.contains("nestedSetParent<0"), "Should preserve intrinsic attribute");
  }

  // ---------------------------------------------------------------------------
  // Operator prefix extraction from constraint values
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Should extract !~ operator prefix from constraint value when adding missing constraints")
  void testAddMissingConstraints_WithNegativeRegexOperatorPrefix() {
    // Given: constraint value encodes operator prefix "!~^kube-.*"
    String existingLabels = "pod=\"my-pod\"";
    Map<String, Set<String>> constraints = new HashMap<>();
    constraints.put("pod", Set.of("my-pod"));
    constraints.put("k8s_namespace_name", Set.of("!~^kube-.*"));

    // When
    EnhancementResult result = LabelProcessor.enhanceLabelsSection(existingLabels, constraints);

    // Then: should extract !~ as operator, ^kube-.* as value
    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(
        enhanced.contains("k8s_namespace_name!~\"^kube-.*\""),
        "Should extract !~ operator prefix from value: got '" + enhanced + "'");
    assertFalse(
        enhanced.contains("=~\"!~"),
        "Should NOT wrap operator prefix in value: got '" + enhanced + "'");
  }

  @Test
  @DisplayName(
      "Should extract =~ operator prefix from constraint value when adding missing constraints")
  void testAddMissingConstraints_WithRegexOperatorPrefix() {
    String existingLabels = "pod=\"my-pod\"";
    Map<String, Set<String>> constraints = new HashMap<>();
    constraints.put("pod", Set.of("my-pod"));
    constraints.put("k8s_namespace_name", Set.of("=~prod-.*"));

    EnhancementResult result = LabelProcessor.enhanceLabelsSection(existingLabels, constraints);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(
        enhanced.contains("k8s_namespace_name=~\"prod-.*\""),
        "Should extract =~ operator prefix from value: got '" + enhanced + "'");
  }

  @Test
  @DisplayName(
      "Should extract != operator prefix from constraint value when adding missing constraints")
  void testAddMissingConstraints_WithNotEqualsOperatorPrefix() {
    String existingLabels = "pod=\"my-pod\"";
    Map<String, Set<String>> constraints = new HashMap<>();
    constraints.put("pod", Set.of("my-pod"));
    constraints.put("k8s_namespace_name", Set.of("!=kube-system"));

    EnhancementResult result = LabelProcessor.enhanceLabelsSection(existingLabels, constraints);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();
    assertTrue(
        enhanced.contains("k8s_namespace_name!=\"kube-system\""),
        "Should extract != operator prefix from value: got '" + enhanced + "'");
  }
}
