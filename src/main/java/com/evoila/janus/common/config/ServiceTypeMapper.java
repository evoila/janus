package com.evoila.janus.common.config;

import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Utility class for mapping ServiceType to various configurations and behaviors. Consolidates
 * switch statements scattered across the codebase into a single location.
 */
@Slf4j
public final class ServiceTypeMapper {

  // Endpoint path constants
  private static final String V1_QUERY = "v1/query";
  private static final String V1_QUERY_RANGE = "v1/query_range";
  private static final String V1_SERIES = "v1/series";
  private static final String V1_LABELS = "v1/labels";

  // Parameter constants
  private static final String QUERY_PARAM = "query";
  private static final String MATCH_SELECTOR_PARAM = "match[]";

  // Enforcement parameter mappings
  private static final Map<String, String> LOKI_ENFORCEMENT_PARAMS =
      Map.ofEntries(
          Map.entry(V1_QUERY, QUERY_PARAM),
          Map.entry(V1_QUERY_RANGE, QUERY_PARAM),
          Map.entry(V1_SERIES, MATCH_SELECTOR_PARAM),
          Map.entry(V1_LABELS, MATCH_SELECTOR_PARAM),
          Map.entry("v1/index/stats", QUERY_PARAM),
          Map.entry("v1/format_query", QUERY_PARAM),
          Map.entry("v1/query_exemplars", QUERY_PARAM),
          Map.entry("v1/status/buildinfo", QUERY_PARAM));

  private static final Map<String, String> THANOS_ENFORCEMENT_PARAMS =
      Map.ofEntries(
          Map.entry(V1_QUERY, QUERY_PARAM),
          Map.entry(V1_QUERY_RANGE, QUERY_PARAM),
          Map.entry(V1_SERIES, MATCH_SELECTOR_PARAM),
          Map.entry(V1_LABELS, MATCH_SELECTOR_PARAM),
          Map.entry("api/v1/query", QUERY_PARAM),
          Map.entry("api/v1/query_range", QUERY_PARAM),
          Map.entry("api/v1/series", MATCH_SELECTOR_PARAM),
          Map.entry("api/v1/labels", MATCH_SELECTOR_PARAM));

  private static final Map<String, String> TEMPO_ENFORCEMENT_PARAMS =
      Map.ofEntries(
          Map.entry("search", "q"),
          Map.entry("v2/search", "q"),
          Map.entry("api/search", "q"),
          Map.entry("api/traces", "q"),
          Map.entry("api/metrics/query_range", "q"));

  // Supported endpoints
  private static final Set<String> LOKI_SUPPORTED_ENDPOINTS =
      Set.of(
          V1_QUERY,
          V1_QUERY_RANGE,
          "v1/tail",
          "v1/index/stats",
          "v1/format_query",
          V1_LABELS,
          "v1/query_exemplars",
          "v1/status/buildinfo");

  private static final Set<String> THANOS_SUPPORTED_ENDPOINTS =
      Set.of(
          V1_QUERY,
          V1_QUERY_RANGE,
          V1_SERIES,
          V1_LABELS,
          "api/v1/query",
          "api/v1/query_range",
          "api/v1/series",
          "api/v1/labels");

  private static final Set<String> TEMPO_SUPPORTED_ENDPOINTS =
      Set.of("search", "v2/search", "api/search", "api/traces", "api/metrics/query_range");

  // Pattern constants
  private static final Pattern LOKI_LABEL_VALUES_PATTERN = Pattern.compile("v1/label/[^/]+/values");
  private static final Pattern THANOS_LABEL_VALUES_PATTERN =
      Pattern.compile("v1/label/[^/]+/values");
  private static final Pattern THANOS_API_LABEL_VALUES_PATTERN =
      Pattern.compile("api/v1/label/[^/]+/values");
  private static final Pattern TEMPO_TAG_VALUES_PATTERN =
      Pattern.compile("api/v2/search/tag/[^/]+/values");

  private ServiceTypeMapper() {
    // Utility class - prevent instantiation
  }

  /** Get enforcement parameters for the given service type */
  public static Map<String, String> getEnforcementParams(ServiceType serviceType) {
    return switch (serviceType) {
      case LOKI -> LOKI_ENFORCEMENT_PARAMS;
      case THANOS -> THANOS_ENFORCEMENT_PARAMS;
      case TEMPO -> TEMPO_ENFORCEMENT_PARAMS;
    };
  }

  /** Get query language for the given service type */
  public static QueryContext.QueryLanguage getQueryLanguage(ServiceType serviceType) {
    return switch (serviceType) {
      case LOKI -> QueryContext.QueryLanguage.LOGQL;
      case THANOS -> QueryContext.QueryLanguage.PROMQL;
      case TEMPO -> QueryContext.QueryLanguage.TRACEQL;
    };
  }

  /** Get dynamic enforcement parameter for the given service type and endpoint path */
  public static String getDynamicEnforcementParameter(
      ServiceType serviceType, String endpointPath) {
    return switch (serviceType) {
      case LOKI -> LOKI_LABEL_VALUES_PATTERN.matcher(endpointPath).matches() ? QUERY_PARAM : null;
      case THANOS -> {
        if (THANOS_LABEL_VALUES_PATTERN.matcher(endpointPath).matches()
            || THANOS_API_LABEL_VALUES_PATTERN.matcher(endpointPath).matches()) {
          yield MATCH_SELECTOR_PARAM;
        }
        yield null;
      }
      case TEMPO -> TEMPO_TAG_VALUES_PATTERN.matcher(endpointPath).matches() ? "q" : null;
    };
  }

  /** Check if endpoint supports query parameters for the given service type */
  public static boolean supportsQueryParameters(ServiceType serviceType, String endpointPath) {
    return switch (serviceType) {
      case THANOS ->
          THANOS_SUPPORTED_ENDPOINTS.contains(endpointPath)
              || THANOS_LABEL_VALUES_PATTERN.matcher(endpointPath).matches()
              || THANOS_API_LABEL_VALUES_PATTERN.matcher(endpointPath).matches();
      case LOKI ->
          LOKI_SUPPORTED_ENDPOINTS.contains(endpointPath)
              || LOKI_LABEL_VALUES_PATTERN.matcher(endpointPath).matches();
      case TEMPO ->
          TEMPO_SUPPORTED_ENDPOINTS.contains(endpointPath)
              || TEMPO_TAG_VALUES_PATTERN.matcher(endpointPath).matches()
              || endpointPath.matches("api/traces/[^/]+");
    };
  }

  /** Get path processor function for the given service type */
  public static Function<String, String> getPathProcessor(ServiceType serviceType) {
    return switch (serviceType) {
      case LOKI -> path -> path.replaceFirst("^/loki/api/?", "");
      case THANOS -> ServiceTypeMapper::processThanosRequestPath;
      case TEMPO -> path -> path.replaceFirst("^/tempo/?", ""); // Leaves /api/search
    };
  }

  // Helper methods for specific service logic
  private static String processThanosRequestPath(String requestPath) {
    // Handle /thanos/api/** pattern (service-specific)
    if (requestPath.startsWith("/thanos/api/")) {
      return requestPath.replaceFirst("^/thanos/api/?", "");
    } else if (requestPath.startsWith("/api/")) {
      // For root API calls, keep the /api prefix - just strip leading slash
      return requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
    } else {
      return requestPath;
    }
  }
}
