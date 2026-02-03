package com.evoila.janus.loki.enforcement;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.evoila.janus.common.enforcement.labels.LabelEnforcementUtils;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancer.LabelValuesContext;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LokiQueryEnhancerTest {

  private LokiQueryEnhancer lokiQueryEnhancer;

  @Mock private LabelEnforcementUtils labelEnforcementUtils;

  private static final Map<String, Set<String>> CONSTRAINTS =
      Map.of("namespace", Set.of("observability", "demo"));

  @BeforeEach
  void setUp() {
    lokiQueryEnhancer = new LokiQueryEnhancer(labelEnforcementUtils);
  }

  @Test
  @DisplayName("Should add constraint query to empty raw query")
  void handleLabelValuesEndpoint_emptyQuery_shouldCreateNewQuery() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("LogQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result = lokiQueryEnhancer.handleLabelValuesEndpoint("", "query", CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("query="));
    String decoded = URLDecoder.decode(result.substring("query=".length()), StandardCharsets.UTF_8);
    assertEquals("{namespace=~\"observability|demo\"}", decoded);
  }

  @Test
  @DisplayName("Should append constraint query to existing raw query")
  void handleLabelValuesEndpoint_existingQuery_shouldAppendConstraint() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("LogQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result =
        lokiQueryEnhancer.handleLabelValuesEndpoint("start=1234&end=5678", "query", CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("start=1234&end=5678&query="));
  }

  @Test
  @DisplayName("Should return original query when no constraints to add")
  void handleLabelValuesEndpoint_noConstraints_shouldReturnOriginal() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("LogQL", CONSTRAINTS)).thenReturn("");

    String rawQuery = "start=1234&end=5678";
    String result = lokiQueryEnhancer.handleLabelValuesEndpoint(rawQuery, "query", CONSTRAINTS);

    assertEquals(rawQuery, result);
  }

  @Test
  @DisplayName("Should use match[] enforcement param")
  void handleLabelValuesEndpoint_matchParam_shouldUseCorrectParam() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("LogQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result = lokiQueryEnhancer.handleLabelValuesEndpoint("", "match[]", CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("match[]="));
  }

  @Test
  @DisplayName("Should delegate via enhanceLabelValuesQuery interface method")
  void enhanceLabelValuesQuery_shouldDelegateToHandleLabelValues() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("LogQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    LabelValuesContext ctx = new LabelValuesContext("", "query", CONSTRAINTS, null);
    String result = lokiQueryEnhancer.enhanceLabelValuesQuery(ctx);

    assertNotNull(result);
    assertTrue(result.startsWith("query="));
  }
}
