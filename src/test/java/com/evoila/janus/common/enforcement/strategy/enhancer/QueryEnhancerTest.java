package com.evoila.janus.common.enforcement.strategy.enhancer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.query.QueryEnhancementProcessor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueryEnhancerTest {

  @Mock private QueryEnforcementFlow queryEnforcementFlow;

  @BeforeEach
  void setUp() {
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
  void testEnhancementResultIsSuccess() {
    // Test that EnhancementResult.isSuccess() works correctly
    EnhancementResult result = EnhancementResult.success("test", List.of("constraint"));
    assertTrue(
        result.isSuccess(),
        "EnhancementResult.isSuccess() should return true for successful result");
  }

  @Test
  void testEnhanceQueryWithoutLabels_WithByClause() {
    // Test case for the specific issue: queries with 'by' clauses and no existing labels
    String query =
        "sum(node_namespace_pod_container:container_cpu_usage_seconds_total:sum_rate5m) by (cluster)";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of(".+"),
            "namespace", Set.of("observability", "demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    EnhancementResult result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhancedQuery = result.getEnhancedQuery();
    assertTrue(
        enhancedQuery.contains(
            "sum(node_namespace_pod_container:container_cpu_usage_seconds_total:sum_rate5m{"));
    // service is a wildcard, so it should NOT be present
    assertFalse(
        enhancedQuery.contains("service=~\".+\""),
        "Should not contain service constraint for wildcard");
    // Check for namespace constraint with flexible ordering
    assertTrue(
        enhancedQuery.contains("namespace=~\"demo|observability\"")
            || enhancedQuery.contains("namespace=~\"observability|demo\""));
    assertTrue(enhancedQuery.endsWith("}) by (cluster)"));
  }

  @Test
  void testEnhanceQuery_PreservesBackslashEscapingInIpAddresses() {
    // Regression test: Matcher.appendReplacement() was stripping backslashes
    // from escaped dots in IP addresses (e.g. 192\\.168 became 192\.168)
    String query =
        "rate(coredns_dns_request_duration_seconds{instance=~\"192\\\\.168\\\\.145\\\\.204:9153\"}[5m])";
    Map<String, Set<String>> labelConstraints = Map.of("namespace", Set.of("demo"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    EnhancementResult result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    String enhanced = result.getEnhancedQuery();
    assertTrue(
        enhanced.contains("192\\\\.168\\\\.145\\\\.204:9153"),
        "Escaped dots in IP address must be preserved, got: " + enhanced);
    assertTrue(enhanced.contains("namespace=~\"demo\""), "Namespace constraint should be added");
  }

  @Test
  void testEnhanceQueryWithoutLabels_SimpleQuery() {
    // Test case for simple queries without 'by' clauses
    String query = "rate(http_requests_total[5m])";
    Map<String, Set<String>> labelConstraints =
        Map.of(
            "service", Set.of("stock-service", "order-service"),
            "job", Set.of("node-exporter", "prometheus"));

    QueryContext context =
        QueryContext.builder()
            .originalQuery(query)
            .labelConstraints(labelConstraints)
            .language(QueryContext.QueryLanguage.PROMQL)
            .build();

    EnhancementResult result = queryEnforcementFlow.enhanceQuery(context);

    assertTrue(result.isSuccess(), "Query enhancement should succeed");
    // The correct behavior: insert constraints on the metric name, BEFORE the [range] selector
    String enhancedQuery = result.getEnhancedQuery();
    assertTrue(
        enhancedQuery.contains("rate(http_requests_total{"),
        "Constraints should be on metric name before [range], got: " + enhancedQuery);
    assertTrue(
        enhancedQuery.contains("}[5m]"),
        "Range selector should come after constraints, got: " + enhancedQuery);
    assertTrue(
        enhancedQuery.contains("service=~\"stock-service|order-service\"")
            || enhancedQuery.contains("service=~\"order-service|stock-service\""),
        "Should contain service constraint with either order");
    assertTrue(
        enhancedQuery.contains("job=~\"node-exporter|prometheus\"")
            || enhancedQuery.contains("job=~\"prometheus|node-exporter\""),
        "Should contain job constraint with either order");
  }
}
