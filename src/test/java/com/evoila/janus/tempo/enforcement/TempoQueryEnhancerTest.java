package com.evoila.janus.tempo.enforcement;

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
class TempoQueryEnhancerTest {

  private TempoQueryEnhancer tempoQueryEnhancer;

  @Mock private LabelEnforcementUtils labelEnforcementUtils;

  private static final Map<String, Set<String>> CONSTRAINTS =
      Map.of("namespace", Set.of("observability", "demo"));

  @BeforeEach
  void setUp() {
    tempoQueryEnhancer = new TempoQueryEnhancer(labelEnforcementUtils);
  }

  @Test
  @DisplayName("Should add constraint query to empty raw query")
  void handleTagValuesEndpoint_emptyQuery_shouldCreateNewQuery() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result = tempoQueryEnhancer.handleTagValuesEndpoint("", CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("q="));
    String decoded = URLDecoder.decode(result.substring(2), StandardCharsets.UTF_8);
    assertEquals("{namespace=~\"observability|demo\"}", decoded);
  }

  @Test
  @DisplayName("Should add constraint query to null raw query")
  void handleTagValuesEndpoint_nullQuery_shouldCreateNewQuery() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result = tempoQueryEnhancer.handleTagValuesEndpoint(null, CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("q="));
  }

  @Test
  @DisplayName("Should append constraint query to existing raw query")
  void handleTagValuesEndpoint_existingQuery_shouldAppendConstraint() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    String result = tempoQueryEnhancer.handleTagValuesEndpoint("start=1234&end=5678", CONSTRAINTS);

    assertNotNull(result);
    assertTrue(result.startsWith("start=1234&end=5678&q="));
    assertTrue(result.contains("q="));
  }

  @Test
  @DisplayName("Should return original query when no constraints to add")
  void handleTagValuesEndpoint_noConstraints_shouldReturnOriginal() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", CONSTRAINTS)).thenReturn("");

    String rawQuery = "start=1234&end=5678";
    String result = tempoQueryEnhancer.handleTagValuesEndpoint(rawQuery, CONSTRAINTS);

    assertEquals(rawQuery, result);
  }

  @Test
  @DisplayName("Should delegate via enhanceLabelValuesQuery interface method")
  void enhanceLabelValuesQuery_shouldDelegateToHandleTagValues() {
    when(labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", CONSTRAINTS))
        .thenReturn("{namespace=~\"observability|demo\"}");

    LabelValuesContext ctx = new LabelValuesContext("", "q", CONSTRAINTS, null);
    String result = tempoQueryEnhancer.enhanceLabelValuesQuery(ctx);

    assertNotNull(result);
    assertTrue(result.startsWith("q="));
  }
}
