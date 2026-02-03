package com.evoila.janus.thanos.enforcement;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for constraint insertion in PromQL queries without existing label selectors.
 *
 * <p>Covers the bug where {@code insertConstraintsInPromQLQuery()} places the label selector
 * after a function call's closing parenthesis instead of attaching it to the metric name
 * inside the function. This produces invalid PromQL that Thanos/Prometheus rejects with:
 * {@code parse error: unexpected "{" in aggregation}
 *
 * <p>Reproduction from production logs:
 * <pre>
 * Input:  sum by (client, server) (rate(traces_service_graph_request_server_seconds_bucket[3600s]))
 * Actual: sum by (client, server) (rate(traces_service_graph_request_server_seconds_bucket[3600s]){tenant_id=~"tenant-b"})
 * Expect: sum by (client, server) (rate(traces_service_graph_request_server_seconds_bucket{tenant_id=~"tenant-b"}[3600s]))
 * </pre>
 */
@Tag("thanos")
@Tag("promql")
class PromQLConstraintInsertionTest {

  private static final Map<String, Set<String>> TENANT_CONSTRAINT =
      Map.of("tenant_id", Set.of("tenant-b"));

  private EnhancementResult enhance(String query) {
    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(TENANT_CONSTRAINT)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();
    return QueryEnhancementProcessor.enhanceQuery(context);
  }

  // ========================================================================
  // Bug: nested function + range vector + aggregation
  // ========================================================================

  static Stream<Arguments> nestedFunctionWithRangeVectorCases() {
    return Stream.of(
        Arguments.of(
            "rate with range vector and by clause",
            "sum by (client, server) (rate(traces_service_graph_request_server_seconds_bucket[3600s]))",
            "traces_service_graph_request_server_seconds_bucket{tenant_id=~\"tenant-b\"}[3600s]"),
        Arguments.of(
            "rate with simple aggregation",
            "sum(rate(http_requests_total[5m]))",
            "http_requests_total{tenant_id=~\"tenant-b\"}[5m]"),
        Arguments.of(
            "deeply nested functions",
            "histogram_quantile(0.99, rate(http_request_duration_seconds_bucket[5m]))",
            "http_request_duration_seconds_bucket{tenant_id=~\"tenant-b\"}[5m]"),
        Arguments.of(
            "irate with by clause",
            "sum by (job) (irate(process_cpu_seconds_total[5m]))",
            "process_cpu_seconds_total{tenant_id=~\"tenant-b\"}[5m]"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nestedFunctionWithRangeVectorCases")
  @DisplayName("Should insert constraints on metric before range vector")
  void shouldInsertConstraintsBeforeRangeVector(
      String description, String query, String expectedFragment) {
    EnhancementResult result = enhance(query);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    assertTrue(
        enhanced.contains(expectedFragment),
        "Constraint should be on metric before [range], got: " + enhanced);
  }

  // ========================================================================
  // Existing behavior that already works (regression guards)
  // ========================================================================

  @Test
  @DisplayName("Should append constraints to simple metric without labels")
  void shouldAppendConstraintsToSimpleMetric() {
    String query = "up";

    EnhancementResult result = enhance(query);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    assertTrue(
        enhanced.contains("up{tenant_id=~\"tenant-b\"}"),
        "Simple metric should get constraints appended, got: " + enhanced);
  }

  @Test
  @DisplayName("Should enhance metric that already has labels")
  void shouldEnhanceMetricWithExistingLabels() {
    String query = "up{job=\"prometheus\"}";

    EnhancementResult result = enhance(query);

    assertTrue(result.isSuccess());
    String enhanced = result.getEnhancedQuery();

    assertTrue(
        enhanced.contains("job=\"prometheus\""),
        "Existing label should be preserved, got: " + enhanced);
    assertTrue(enhanced.contains("tenant_id"), "Constraint should be added, got: " + enhanced);
  }
}
