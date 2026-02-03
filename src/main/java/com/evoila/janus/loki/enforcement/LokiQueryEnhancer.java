package com.evoila.janus.loki.enforcement;

import com.evoila.janus.common.enforcement.labels.LabelEnforcementUtils;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service responsible for enhancing Loki/LogQL queries with security constraints.
 *
 * <p>This service provides a centralized way to enhance Loki queries for both: - GET requests (URL
 * query parameters) - POST requests (form data containing a 'query' parameter) - Special endpoints
 * (label values endpoints)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LokiQueryEnhancer implements LabelValuesEnhancer {

  private final LabelEnforcementUtils labelEnforcementUtils;

  @Override
  public String enhanceLabelValuesQuery(LabelValuesContext ctx) {
    return handleLabelValuesEndpoint(
        ctx.rawQuery(), ctx.enforcementParam(), ctx.labelConstraints());
  }

  /**
   * Handles Loki label values endpoints with security constraints.
   *
   * <p>This method processes special Loki endpoints that require custom constraint handling for
   * label values filtering.
   *
   * @param rawQuery The raw query string from the request
   * @param enforcementParam The enforcement parameter name
   * @param labelConstraints The label constraints to apply
   * @return The enhanced query string with security constraints
   */
  public String handleLabelValuesEndpoint(
      String rawQuery, String enforcementParam, Map<String, Set<String>> labelConstraints) {
    log.info("=== LOKI LABEL VALUES ENDPOINT PROCESSING ===");
    log.info("Raw query: '{}'", rawQuery);
    log.info("Enforcement param: '{}'", enforcementParam);
    log.info("Label constraints: {}", labelConstraints);

    // Validate enforcement parameter - use default "query" if null or empty
    String effectiveEnforcementParam = enforcementParam;
    if (effectiveEnforcementParam == null || effectiveEnforcementParam.trim().isEmpty()) {
      log.warn(
          "Enforcement parameter is null or empty, using default 'query' for Loki label values");
      effectiveEnforcementParam = "query";
    }

    String builtQuery = labelEnforcementUtils.buildSafeConstraintQuery("LogQL", labelConstraints);
    log.info("Built constraint query: '{}'", builtQuery);

    if (builtQuery.isEmpty()) {
      log.info("No constraints to add, returning original query: '{}'", rawQuery);
      return rawQuery != null ? rawQuery : "";
    }

    // Add the constraint query to the existing query parameters
    // rawQuery is the query string without the ? prefix (added by RequestUrlBuilder)
    String finalQuery;
    String safeRawQuery = rawQuery != null ? rawQuery : "";
    if (safeRawQuery.isEmpty()) {
      finalQuery =
          effectiveEnforcementParam + "=" + URLEncoder.encode(builtQuery, StandardCharsets.UTF_8);
    } else {
      finalQuery =
          safeRawQuery
              + "&"
              + effectiveEnforcementParam
              + "="
              + URLEncoder.encode(builtQuery, StandardCharsets.UTF_8);
    }

    log.info("Final Loki label values query: '{}'", finalQuery);
    return finalQuery;
  }
}
