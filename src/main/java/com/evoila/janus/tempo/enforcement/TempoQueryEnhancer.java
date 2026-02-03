package com.evoila.janus.tempo.enforcement;

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
 * Service responsible for enhancing Tempo/TraceQL queries with security constraints.
 *
 * <p>This service provides a centralized way to enhance Tempo queries for both: - GET requests (URL
 * query parameters) - POST requests (form data containing a 'query' parameter) - Special endpoints
 * (tag values endpoints)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TempoQueryEnhancer implements LabelValuesEnhancer {

  private final LabelEnforcementUtils labelEnforcementUtils;

  @Override
  public String enhanceLabelValuesQuery(LabelValuesContext ctx) {
    return handleTagValuesEndpoint(ctx.rawQuery(), ctx.labelConstraints());
  }

  /**
   * Handles Tempo tag values endpoints with security constraints.
   *
   * <p>This method processes special Tempo endpoints that require custom constraint handling for
   * tag values filtering.
   *
   * @param rawQuery The raw query string from the request
   * @param labelConstraints The label constraints to apply
   * @return The enhanced query string with security constraints
   */
  public String handleTagValuesEndpoint(
      String rawQuery, Map<String, Set<String>> labelConstraints) {
    log.info("=== TEMPO TAG VALUES ENDPOINT PROCESSING ===");
    log.info("Raw query: '{}'", rawQuery);
    log.info("Label constraints: {}", labelConstraints);

    String builtQuery = labelEnforcementUtils.buildSafeConstraintQuery("TraceQL", labelConstraints);
    log.info("Built constraint query: '{}'", builtQuery);

    if (builtQuery.isEmpty()) {
      log.info("No constraints to add, returning original query: '{}'", rawQuery);
      return rawQuery;
    }

    // Add the constraint query to the existing query parameters
    if (rawQuery == null || rawQuery.isEmpty()) {
      String finalQuery = "q=" + URLEncoder.encode(builtQuery, StandardCharsets.UTF_8);
      log.info("Final Tempo tag values query: '{}'", finalQuery);
      return finalQuery;
    }
    String finalQuery = rawQuery + "&q=" + URLEncoder.encode(builtQuery, StandardCharsets.UTF_8);

    log.info("Final Tempo tag values query: '{}'", finalQuery);
    return finalQuery;
  }
}
