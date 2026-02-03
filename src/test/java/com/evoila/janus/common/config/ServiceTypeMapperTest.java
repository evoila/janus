package com.evoila.janus.common.config;

import static org.junit.jupiter.api.Assertions.*;

import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ServiceTypeMapper Tests")
class ServiceTypeMapperTest {

  // ========================================================================
  // getEnforcementParams
  // ========================================================================

  @Nested
  @DisplayName("getEnforcementParams")
  class GetEnforcementParams {

    @Test
    @DisplayName("Should return Loki enforcement params")
    void loki() {
      Map<String, String> params = ServiceTypeMapper.getEnforcementParams(ServiceType.LOKI);
      assertEquals("query", params.get("v1/query"));
      assertEquals("query", params.get("v1/query_range"));
      assertEquals("match[]", params.get("v1/series"));
    }

    @Test
    @DisplayName("Should return Thanos enforcement params")
    void thanos() {
      Map<String, String> params = ServiceTypeMapper.getEnforcementParams(ServiceType.THANOS);
      assertEquals("query", params.get("v1/query"));
      assertEquals("query", params.get("api/v1/query"));
      assertEquals("match[]", params.get("api/v1/series"));
    }

    @Test
    @DisplayName("Should return Tempo enforcement params")
    void tempo() {
      Map<String, String> params = ServiceTypeMapper.getEnforcementParams(ServiceType.TEMPO);
      assertEquals("q", params.get("search"));
      assertEquals("q", params.get("api/search"));
      assertEquals("q", params.get("api/metrics/query_range"));
    }
  }

  // ========================================================================
  // getQueryLanguage
  // ========================================================================

  @Nested
  @DisplayName("getQueryLanguage")
  class GetQueryLanguage {

    @Test
    @DisplayName("Should return LOGQL for Loki")
    void loki() {
      assertEquals(
          QueryContext.QueryLanguage.LOGQL, ServiceTypeMapper.getQueryLanguage(ServiceType.LOKI));
    }

    @Test
    @DisplayName("Should return PROMQL for Thanos")
    void thanos() {
      assertEquals(
          QueryContext.QueryLanguage.PROMQL,
          ServiceTypeMapper.getQueryLanguage(ServiceType.THANOS));
    }

    @Test
    @DisplayName("Should return TRACEQL for Tempo")
    void tempo() {
      assertEquals(
          QueryContext.QueryLanguage.TRACEQL,
          ServiceTypeMapper.getQueryLanguage(ServiceType.TEMPO));
    }
  }

  // ========================================================================
  // getDynamicEnforcementParameter
  // ========================================================================

  @Nested
  @DisplayName("getDynamicEnforcementParameter")
  class GetDynamicEnforcementParameter {

    @Test
    @DisplayName("Should return query for Loki label values endpoint")
    void lokiLabelValues() {
      assertEquals(
          "query",
          ServiceTypeMapper.getDynamicEnforcementParameter(
              ServiceType.LOKI, "v1/label/namespace/values"));
    }

    @Test
    @DisplayName("Should return null for Loki non-label-values endpoint")
    void lokiOther() {
      assertNull(ServiceTypeMapper.getDynamicEnforcementParameter(ServiceType.LOKI, "v1/other"));
    }

    @Test
    @DisplayName("Should return match[] for Thanos label values endpoint")
    void thanosLabelValues() {
      assertEquals(
          "match[]",
          ServiceTypeMapper.getDynamicEnforcementParameter(
              ServiceType.THANOS, "v1/label/namespace/values"));
    }

    @Test
    @DisplayName("Should return match[] for Thanos api label values endpoint")
    void thanosApiLabelValues() {
      assertEquals(
          "match[]",
          ServiceTypeMapper.getDynamicEnforcementParameter(
              ServiceType.THANOS, "api/v1/label/namespace/values"));
    }

    @Test
    @DisplayName("Should return null for Thanos non-label-values endpoint")
    void thanosOther() {
      assertNull(ServiceTypeMapper.getDynamicEnforcementParameter(ServiceType.THANOS, "v1/other"));
    }

    @Test
    @DisplayName("Should return q for Tempo tag values endpoint")
    void tempoTagValues() {
      assertEquals(
          "q",
          ServiceTypeMapper.getDynamicEnforcementParameter(
              ServiceType.TEMPO, "api/v2/search/tag/service.name/values"));
    }

    @Test
    @DisplayName("Should return null for Tempo non-tag-values endpoint")
    void tempoOther() {
      assertNull(ServiceTypeMapper.getDynamicEnforcementParameter(ServiceType.TEMPO, "api/search"));
    }
  }

  // ========================================================================
  // supportsQueryParameters
  // ========================================================================

  @Nested
  @DisplayName("supportsQueryParameters")
  class SupportsQueryParameters {

    @Test
    @DisplayName("Should support Loki standard endpoints")
    void lokiStandard() {
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.LOKI, "v1/query"));
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.LOKI, "v1/query_range"));
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.LOKI, "v1/tail"));
    }

    @Test
    @DisplayName("Should support Loki label values pattern")
    void lokiLabelValues() {
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.LOKI, "v1/label/namespace/values"));
    }

    @Test
    @DisplayName("Should not support Loki unknown endpoint")
    void lokiUnknown() {
      assertFalse(ServiceTypeMapper.supportsQueryParameters(ServiceType.LOKI, "v1/unknown"));
    }

    @Test
    @DisplayName("Should support Thanos standard and api endpoints")
    void thanosStandard() {
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.THANOS, "v1/query"));
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.THANOS, "api/v1/query_range"));
    }

    @Test
    @DisplayName("Should support Thanos label values patterns")
    void thanosLabelValues() {
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.THANOS, "v1/label/job/values"));
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.THANOS, "api/v1/label/job/values"));
    }

    @Test
    @DisplayName("Should support Tempo standard endpoints")
    void tempoStandard() {
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.TEMPO, "search"));
      assertTrue(ServiceTypeMapper.supportsQueryParameters(ServiceType.TEMPO, "api/search"));
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.TEMPO, "api/metrics/query_range"));
    }

    @Test
    @DisplayName("Should support Tempo tag values endpoint")
    void tempoTagValues() {
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(
              ServiceType.TEMPO, "api/v2/search/tag/service.name/values"));
    }

    @Test
    @DisplayName("Should support Tempo individual trace endpoint")
    void tempoTraceById() {
      assertTrue(
          ServiceTypeMapper.supportsQueryParameters(ServiceType.TEMPO, "api/traces/abc123def456"));
    }

    @Test
    @DisplayName("Should not support Tempo unknown endpoint")
    void tempoUnknown() {
      assertFalse(ServiceTypeMapper.supportsQueryParameters(ServiceType.TEMPO, "api/unknown"));
    }
  }

  // ========================================================================
  // getPathProcessor
  // ========================================================================

  @Nested
  @DisplayName("getPathProcessor")
  class GetPathProcessor {

    @Test
    @DisplayName("Should strip /loki/api/ prefix for Loki")
    void lokiPath() {
      Function<String, String> processor = ServiceTypeMapper.getPathProcessor(ServiceType.LOKI);
      assertEquals("v1/query", processor.apply("/loki/api/v1/query"));
    }

    @Test
    @DisplayName("Should strip /thanos/api/ prefix for Thanos")
    void thanosServicePath() {
      Function<String, String> processor = ServiceTypeMapper.getPathProcessor(ServiceType.THANOS);
      assertEquals("v1/query", processor.apply("/thanos/api/v1/query"));
    }

    @Test
    @DisplayName("Should keep api/ prefix for root Thanos API calls")
    void thanosRootApiPath() {
      Function<String, String> processor = ServiceTypeMapper.getPathProcessor(ServiceType.THANOS);
      assertEquals("api/v1/query", processor.apply("/api/v1/query"));
    }

    @Test
    @DisplayName("Should return unchanged path for Thanos non-api paths")
    void thanosOtherPath() {
      Function<String, String> processor = ServiceTypeMapper.getPathProcessor(ServiceType.THANOS);
      assertEquals("other/path", processor.apply("other/path"));
    }

    @Test
    @DisplayName("Should strip /tempo/ prefix for Tempo")
    void tempoPath() {
      Function<String, String> processor = ServiceTypeMapper.getPathProcessor(ServiceType.TEMPO);
      assertEquals("api/search", processor.apply("/tempo/api/search"));
    }
  }
}
