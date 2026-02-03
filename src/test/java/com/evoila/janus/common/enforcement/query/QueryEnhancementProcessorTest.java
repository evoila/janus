package com.evoila.janus.common.enforcement.query;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.dto.QueryContext.QueryLanguage;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("QueryEnhancementProcessor Tests")
class QueryEnhancementProcessorTest {

  // ========================================================================
  // addMissingConstraints - query with existing labels missing some constraints
  // ========================================================================

  @Nested
  @DisplayName("addMissingConstraints via enhanceQuery")
  class AddMissingConstraints {

    @Test
    @DisplayName("Should add missing namespace constraint to query with only service label")
    void addMissingConstraintToExistingLabels() {
      // Query has service but NOT namespace - namespace should be added
      QueryContext context =
          QueryContext.builder()
              .originalQuery("metric{service=\"order-svc\"}")
              .labelConstraints(
                  Map.of(
                      "service", Set.of("order-svc"),
                      "namespace", Set.of("demo"),
                      "labels", Set.of("*")))
              .language(QueryLanguage.PROMQL)
              .build();

      EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

      assertTrue(result.isSuccess());
      String enhanced = result.getEnhancedQuery();
      assertTrue(enhanced.contains("namespace"), "Should contain added namespace constraint");
      assertTrue(enhanced.contains("service"), "Should still contain original service label");
    }

    @Test
    @DisplayName("Should add missing constraint with LogQL syntax")
    void addMissingConstraintLogQL() {
      QueryContext context =
          QueryContext.builder()
              .originalQuery("{service_name=\"order-svc\"}")
              .labelConstraints(
                  Map.of(
                      "service_name", Set.of("order-svc"),
                      "k8s_namespace_name", Set.of("demo"),
                      "labels", Set.of("*")))
              .language(QueryLanguage.LOGQL)
              .build();

      EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

      assertTrue(result.isSuccess());
      assertTrue(
          result.getEnhancedQuery().contains("k8s_namespace_name"),
          "Should add missing k8s_namespace_name constraint");
    }

    @Test
    @DisplayName("Should not add constraints when all required labels already present")
    void noMissingConstraints() {
      QueryContext context =
          QueryContext.builder()
              .originalQuery("metric{namespace=\"demo\"}")
              .labelConstraints(
                  Map.of(
                      "namespace", Set.of("demo"),
                      "labels", Set.of("*")))
              .language(QueryLanguage.PROMQL)
              .build();

      EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

      assertTrue(result.isSuccess());
    }
  }

  // ========================================================================
  // enhanceQueryWithoutLabels
  // ========================================================================

  @Nested
  @DisplayName("enhanceQuery without existing labels")
  class EnhanceQueryWithoutLabels {

    @Test
    @DisplayName("Should add constraints to query without label selectors")
    void addConstraintsToPlainQuery() {
      QueryContext context =
          QueryContext.builder()
              .originalQuery("up")
              .labelConstraints(
                  Map.of(
                      "namespace", Set.of("demo"),
                      "labels", Set.of("*")))
              .language(QueryLanguage.PROMQL)
              .build();

      EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

      assertTrue(result.isSuccess());
      assertTrue(result.getEnhancedQuery().contains("namespace"));
    }

    @Test
    @DisplayName("Should handle empty query with constraints")
    void emptyQueryWithConstraints() {
      QueryContext context =
          QueryContext.builder()
              .originalQuery("")
              .labelConstraints(
                  Map.of(
                      "namespace", Set.of("demo"),
                      "labels", Set.of("*")))
              .language(QueryLanguage.PROMQL)
              .build();

      EnhancementResult result = QueryEnhancementProcessor.enhanceQuery(context);

      assertTrue(result.isSuccess());
      assertTrue(result.getEnhancedQuery().contains("namespace"));
    }
  }
}
