package com.evoila.janus.thanos.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.evoila.janus.base.BaseUnitTest;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class ThanosQueryEnhancerTest extends BaseUnitTest {

  private ThanosQueryEnhancer thanosQueryEnhancer;

  @Mock
  private com.evoila.janus.common.enforcement.labels.LabelEnforcementUtils labelEnforcementUtils;

  @Mock private com.evoila.janus.common.enforcement.QueryEnforcementFlow queryEnforcementFlow;

  private static final Logger log = LoggerFactory.getLogger(ThanosQueryEnhancerTest.class);

  @BeforeEach
  void setUp() throws java.io.IOException {
    super.setUpBase();
    thanosQueryEnhancer = new ThanosQueryEnhancer(labelEnforcementUtils, queryEnforcementFlow);

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
  void
      testHandleLabelValuesEndpoint_WithExistingMatchParameters_ShouldNotCreateDoubleQuestionMarks() {
    // Given: Query with existing match parameters (URL-encoded)
    String rawQuery =
        "match%5B%5D=jvm_memory_used_bytes%7Bnamespace%3D%22demo%22%2C%20service%3D%22.%2B%22%2C%20pod%3D%22.%2B%22%2C%20area%3D%22nonheap%22%7D&start=1753215527&end=1753215827";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability", "demo"),
            "service", Set.of(".+"),
            "labels", Set.of("*"));

    ParsedQueryParameters preParsedQuery =
        new ParsedQueryParameters(
            Map.of(
                "match[]",
                    List.of(
                        "jvm_memory_used_bytes{namespace=\"demo\", service=\".+\", pod=\".+\", area=\"nonheap\"}"),
                "start", List.of("1753215527"),
                "end", List.of("1753215827")),
            "", // enforcementParamValue
            List.of(
                "jvm_memory_used_bytes{namespace=\"demo\", service=\".+\", pod=\".+\", area=\"nonheap\"}"), // matchSelectors
            rawQuery // originalQuery
            );

    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then: Should not contain double question marks
    assertNotNull(result);
    assertFalse(result.contains("??"), "Should not contain double question marks");
    assertFalse(result.contains("?match[]"), "Should not contain ? before match[]");
    assertTrue(result.contains("&match[]"), "Should contain & before match[]");

    // Count match[]= occurrences (both encoded and unencoded)
    long matchParamCount =
        java.util.regex.Pattern.compile("match%5B%5D=").matcher(result).results().count();

    long matchParamCountUnencoded =
        java.util.regex.Pattern.compile("match\\[\\]=").matcher(result).results().count();

    long totalMatchParams = matchParamCount + matchParamCountUnencoded;
    assertEquals(1, totalMatchParams, "Should have exactly one match[] parameter");

    // Verify the structure
    assertTrue(result.contains("start=1753215527"), "Should preserve start parameter");
    assertTrue(result.contains("end=1753215827"), "Should preserve end parameter");
    assertTrue(
        result.contains("match[]=") || result.contains("match%5B%5D="),
        "Should contain match[] parameter");
  }

  @Test
  void
      testHandleLabelValuesEndpoint_WithUnencodedMatchParameters_ShouldNotCreateDoubleQuestionMarks() {
    // Given: Query with unencoded match parameters
    String rawQuery = "match[]=up{job=\"prometheus\"}&start=1753215527&end=1753215827";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability", "demo"),
            "service", Set.of(".+"),
            "labels", Set.of("*"));

    ParsedQueryParameters preParsedQuery =
        new ParsedQueryParameters(
            Map.of(
                "match[]", List.of("up{job=\"prometheus\"}"),
                "start", List.of("1753215527"),
                "end", List.of("1753215827")),
            "", // enforcementParamValue
            List.of("up{job=\"prometheus\"}"), // matchSelectors
            rawQuery // originalQuery
            );

    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then: Should not contain double question marks
    assertNotNull(result);
    assertFalse(result.contains("??"), "Should not contain double question marks");
    assertFalse(result.contains("?match[]"), "Should not contain ? before match[]");
    assertTrue(result.contains("&match[]"), "Should contain & before match[]");

    // Should only have one match[] parameter (both encoded and unencoded)
    long matchParamCount =
        java.util.regex.Pattern.compile("match%5B%5D=").matcher(result).results().count();

    long matchParamCountUnencoded =
        java.util.regex.Pattern.compile("match\\[\\]=").matcher(result).results().count();

    long totalMatchParams = matchParamCount + matchParamCountUnencoded;
    assertEquals(1, totalMatchParams, "Should have exactly one match[] parameter");
  }

  @Test
  void testHandleLabelValuesEndpoint_EmptyQuery_ShouldCreateValidQuery() {
    // Given: Empty query
    String rawQuery = "";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "namespace", Set.of("observability", "demo"),
            "service", Set.of(".+"),
            "labels", Set.of("*"));

    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // When
    String result = thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, null);

    // Then: Should create valid query without question mark
    assertNotNull(result);
    assertFalse(result.startsWith("?"), "Should not start with question mark");
    assertTrue(result.startsWith("match[]="), "Should start with match[]=");
    assertFalse(result.contains("??"), "Should not contain double question marks");
  }

  @Test
  void testHandleLabelValuesEndpoint_NoConstraints_ShouldReturnOriginalQuery() {
    // Given: Query with no constraints to add
    String rawQuery = "match[]=up{job=\"prometheus\"}&start=1753215527&end=1753215827";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"), // Only wildcard constraints
            "labels", Set.of("*") // Only wildcard constraints
            );

    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn(""); // No constraints to add

    // When
    String result = thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, null);

    // Then: Should return original query unchanged
    assertEquals(rawQuery, result, "Should return original query when no constraints to add");
  }

  @Test
  @DisplayName("Should enhance existing selectors with constraints instead of replacing them")
  void testEnhanceExistingSelectorsWithConstraints() {
    // Given: A complex existing selector with multiple labels
    String rawQuery =
        "match%5B%5D=kubelet_volume_stats_capacity_bytes%7Bcluster%3D%22observability%22%2C%20job%3D%22kubelet%22%2C%20metrics_path%3D%22%2Fmetrics%22%2C%20namespace%3D%22observability%22%7D&start=1753216222&end=1753219822";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"),
            "namespace", Set.of("observability", "demo"),
            "labels", Set.of("*"));

    // Mock the labelEnforcementUtils to return a proper constraint selector
    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // Create pre-parsed query parameters
    Map<String, List<String>> parameters =
        Map.of(
            "match[]",
                List.of(
                    "kubelet_volume_stats_capacity_bytes{cluster=\"observability\", job=\"kubelet\", metrics_path=\"/metrics\", namespace=\"observability\"}"),
            "start", List.of("1753216222"),
            "end", List.of("1753219822"));
    ParsedQueryParameters preParsedQuery = new ParsedQueryParameters(parameters, null, null, null);

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then
    assertNotNull(result, "Result should not be null");

    // URL decode the result for easier assertion checking
    String decodedResult =
        java.net.URLDecoder.decode(result, java.nio.charset.StandardCharsets.UTF_8);

    // Verify that the original query structure is preserved
    assertTrue(
        decodedResult.contains("kubelet_volume_stats_capacity_bytes"),
        "Result should contain the original metric name");
    assertTrue(
        decodedResult.contains("cluster=\"observability\""),
        "Result should contain the original cluster label");
    assertTrue(
        decodedResult.contains("job=\"kubelet\""), "Result should contain the original job label");
    assertTrue(
        decodedResult.contains("metrics_path=\"/metrics\""),
        "Result should contain the original metrics_path label");

    // Verify that the namespace constraint is properly applied (order may vary)
    // OR that the original namespace label is preserved if it already satisfies the constraint
    assertTrue(
        decodedResult.contains("namespace=~\"observability|demo\"")
            || decodedResult.contains("namespace=~\"demo|observability\"")
            || decodedResult.contains("namespace=\"observability\""),
        "Result should contain the enhanced namespace constraint or preserve original namespace");

    // Verify that other query parameters are preserved
    assertTrue(
        decodedResult.contains("start=1753216222"), "Result should preserve the start parameter");
    assertTrue(
        decodedResult.contains("end=1753219822"), "Result should preserve the end parameter");

    // Verify that the result is not just the constraint selector
    assertNotEquals(
        "match[]={namespace=~\"observability|demo\"}",
        decodedResult,
        "Result should not be just the constraint selector");

    // Verify that the result contains the enhanced selector
    assertTrue(decodedResult.contains("match[]="), "Result should contain the match[] parameter");

    log.info("Enhanced query result: {}", result);
    log.info("Decoded result: {}", decodedResult);
  }

  @Test
  @DisplayName("Should enhance multiple existing selectors with constraints")
  void testEnhanceMultipleExistingSelectorsWithConstraints() {
    // Given: Multiple existing selectors
    String rawQuery =
        "match%5B%5D=up%7Bjob%3D%22prometheus%22%7D&match%5B%5D=node_exporter_build_info%7Binstance%3D%22localhost%3A9100%22%7D&start=1753215527&end=1753215827";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"),
            "namespace", Set.of("observability", "demo"),
            "labels", Set.of("*"));

    // Mock the labelEnforcementUtils to return a proper constraint selector
    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // Create pre-parsed query parameters
    Map<String, List<String>> parameters =
        Map.of(
            "match[]",
                List.of(
                    "up{job=\"prometheus\"}",
                    "node_exporter_build_info{instance=\"localhost:9100\"}"),
            "start", List.of("1753215527"),
            "end", List.of("1753215827"));
    ParsedQueryParameters preParsedQuery = new ParsedQueryParameters(parameters, null, null, null);

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then
    assertNotNull(result, "Result should not be null");

    // URL decode the result for easier assertion checking
    String decodedResult =
        java.net.URLDecoder.decode(result, java.nio.charset.StandardCharsets.UTF_8);

    // Verify that both original selectors are preserved and enhanced
    assertTrue(
        decodedResult.contains("up{job=\"prometheus\""),
        "Result should contain the first original selector");
    assertTrue(
        decodedResult.contains("node_exporter_build_info{instance=\"localhost:9100\""),
        "Result should contain the second original selector");

    // Verify that namespace constraints are applied to both selectors (order may vary)
    assertTrue(
        decodedResult.contains("namespace=~\"observability|demo\"")
            || decodedResult.contains("namespace=~\"demo|observability\""),
        "Result should contain the enhanced namespace constraint");

    // Verify that other query parameters are preserved
    assertTrue(
        decodedResult.contains("start=1753215527"), "Result should preserve the start parameter");
    assertTrue(
        decodedResult.contains("end=1753215827"), "Result should preserve the end parameter");

    log.info("Enhanced multiple selectors result: {}", result);
    log.info("Decoded result: {}", decodedResult);
  }

  @Test
  @DisplayName("Should handle empty existing selectors by using constraint selector")
  void testHandleEmptyExistingSelectors() {
    // Given: No existing selectors, only time parameters
    String rawQuery = "start=1753215527&end=1753215827";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"),
            "namespace", Set.of("observability", "demo"),
            "labels", Set.of("*"));

    // Mock the labelEnforcementUtils to return a proper constraint selector
    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // Create pre-parsed query parameters with no match[] selectors
    Map<String, List<String>> parameters =
        Map.of(
            "start", List.of("1753215527"),
            "end", List.of("1753215827"));
    ParsedQueryParameters preParsedQuery = new ParsedQueryParameters(parameters, null, null, null);

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then
    assertNotNull(result, "Result should not be null");

    // URL decode the result for easier assertion checking
    String decodedResult =
        java.net.URLDecoder.decode(result, java.nio.charset.StandardCharsets.UTF_8);

    // Verify that the constraint selector is used when no existing selectors
    assertTrue(decodedResult.contains("match[]="), "Result should contain the match[] parameter");
    assertTrue(
        decodedResult.contains("namespace=~\"observability|demo\""),
        "Result should contain the namespace constraint");

    // Verify that time parameters are preserved
    assertTrue(
        decodedResult.contains("start=1753215527"), "Result should preserve the start parameter");
    assertTrue(
        decodedResult.contains("end=1753215827"), "Result should preserve the end parameter");

    log.info("Empty selectors result: {}", result);
    log.info("Decoded result: {}", decodedResult);
  }

  @Test
  @DisplayName("Should preserve complex label expressions in existing selectors")
  void testPreserveComplexLabelExpressions() {
    // Given: A selector with complex label expressions including regex and negative matches
    String rawQuery =
        "match%5B%5D=histogram_quantile%280.99%2C%20sum%28rate%28rest_client_request_duration_seconds_bucket%7Bcluster%3D%22observability%22%2Cjob%3D%22kube-controller-manager%22%2Cinstance%3D%7E%22%2810%5C%5C.33%5C%5C.51%5C%5C.101%3A10250%7C10%5C%5C.33%5C%5C.51%5C%5C.103%3A10250%29%22%2Cverb%3D%22POST%22%2Cnamespace%3D%7E%22demo%22%7D%5B1m15s%5D%29%29%20by%20%28verb%2C%20le%29%29&start=1753213800&end=1753217400&step=60";

    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"),
            "namespace", Set.of("observability", "demo"),
            "labels", Set.of("*"));

    // Mock the labelEnforcementUtils to return a proper constraint selector
    when(labelEnforcementUtils.buildSafeConstraintQuery("PromQL", labelConstraints))
        .thenReturn("{namespace=~\"observability|demo\"}");

    // Create pre-parsed query parameters
    Map<String, List<String>> parameters =
        Map.of(
            "match[]",
                List.of(
                    "histogram_quantile(0.99, sum(rate(rest_client_request_duration_seconds_bucket{cluster=\"observability\",job=\"kube-controller-manager\",instance=~\"(10\\.33\\.51\\.101:10250|10\\.33\\.51\\.103:10250)\",verb=\"POST\",namespace=~\"demo\"}[1m15s])) by (verb, le))"),
            "start", List.of("1753213800"),
            "end", List.of("1753217400"),
            "step", List.of("60"));
    ParsedQueryParameters preParsedQuery = new ParsedQueryParameters(parameters, null, null, null);

    // When
    String result =
        thanosQueryEnhancer.handleLabelValuesEndpoint(rawQuery, labelConstraints, preParsedQuery);

    // Then
    assertNotNull(result, "Result should not be null");

    // URL decode the result for easier assertion checking
    String decodedResult =
        java.net.URLDecoder.decode(result, java.nio.charset.StandardCharsets.UTF_8);

    // Verify that the complex query structure is preserved
    assertTrue(
        decodedResult.contains("histogram_quantile(0.99"),
        "Result should contain the histogram_quantile function");
    assertTrue(
        decodedResult.contains("rest_client_request_duration_seconds_bucket"),
        "Result should contain the metric name");
    assertTrue(
        decodedResult.contains("cluster=\"observability\""),
        "Result should contain the cluster label");
    assertTrue(
        decodedResult.contains("job=\"kube-controller-manager\""),
        "Result should contain the job label");
    assertTrue(decodedResult.contains("verb=\"POST\""), "Result should contain the verb label");

    // Verify that the regex pattern for instance is preserved (IP addresses)
    assertTrue(
        decodedResult.contains("instance=~"), "Result should contain the instance regex pattern");

    // Verify that the namespace constraint is properly applied (order may vary)
    // OR that the original namespace label is preserved if it already satisfies the constraint
    assertTrue(
        decodedResult.contains("namespace=~\"observability|demo\"")
            || decodedResult.contains("namespace=~\"demo|observability\"")
            || decodedResult.contains("namespace=\"demo\""),
        "Result should contain the enhanced namespace constraint or preserve original namespace");

    // Verify that all query parameters are preserved
    assertTrue(
        decodedResult.contains("start=1753213800"), "Result should preserve the start parameter");
    assertTrue(
        decodedResult.contains("end=1753217400"), "Result should preserve the end parameter");
    assertTrue(decodedResult.contains("step=60"), "Result should preserve the step parameter");

    log.info("Complex expression result: {}", result);
    log.info("Decoded result: {}", decodedResult);
  }
}
