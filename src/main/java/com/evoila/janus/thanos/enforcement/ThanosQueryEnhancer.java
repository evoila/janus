package com.evoila.janus.thanos.enforcement;

import com.evoila.janus.common.enforcement.QueryEnforcementFlow;
import com.evoila.janus.common.enforcement.labels.LabelEnforcementUtils;
import com.evoila.janus.common.enforcement.labels.LabelValuesEnhancer;
import com.evoila.janus.common.enforcement.model.dto.QueryContext;
import com.evoila.janus.common.enforcement.model.result.EnhancementResult;
import com.evoila.janus.common.enforcement.query.ParsedQueryParameters;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Service responsible for enhancing Thanos/PromQL queries with security constraints.
 *
 * <p>This service provides a centralized way to enhance Thanos queries for both: - GET requests
 * (URL query parameters) - POST requests (form data containing a 'query' parameter) - Special
 * endpoints (label values endpoints)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThanosQueryEnhancer implements LabelValuesEnhancer {

  private static final String QUERY_LANGUAGE = "PromQL";
  private static final String LOG_CONSTRAINT_SELECTOR = "Built constraint selector: '{}'";

  private final LabelEnforcementUtils labelEnforcementUtils;
  private final QueryEnforcementFlow queryEnforcementFlow;

  @Override
  public String enhanceLabelValuesQuery(LabelValuesContext ctx) {
    return handleLabelValuesEndpoint(ctx.rawQuery(), ctx.labelConstraints(), ctx.parsedQuery());
  }

  /**
   * Handles Thanos series endpoints with security constraints.
   *
   * <p>The series endpoint uses match[] selectors which need special handling - they contain PromQL
   * selectors (metric names or label matchers) that need to be merged with constraints.
   *
   * @param rawQuery The raw query string from the request
   * @param labelConstraints The label constraints to apply
   * @param preParsedQuery Pre-parsed query parameters for optimization
   * @return The enhanced query string with security constraints
   */
  public String handleSeriesEndpoint(
      String rawQuery,
      Map<String, Set<String>> labelConstraints,
      ParsedQueryParameters preParsedQuery) {
    log.info("=== THANOS SERIES ENDPOINT PROCESSING ===");
    log.info("Raw query: '{}'", rawQuery);
    log.info("Label constraints: {}", labelConstraints);

    // Extract existing match[] selectors
    List<String> existingSelectors = extractMatchSelectors(preParsedQuery);
    log.info("Existing match[] selectors: {}", existingSelectors);

    // Build constraint selector
    String constraintSelector =
        labelEnforcementUtils.buildSafeConstraintQuery(QUERY_LANGUAGE, labelConstraints);
    log.info(LOG_CONSTRAINT_SELECTOR, constraintSelector);

    if (constraintSelector.isEmpty()) {
      log.info("No constraints to add, returning original query: '{}'", rawQuery);
      return rawQuery != null ? rawQuery : "";
    }

    // Merge existing selectors with constraint selector
    String mergedSelector = mergeSeriesSelectors(existingSelectors, constraintSelector);
    log.info("Merged selector: '{}'", mergedSelector);

    // Replace match[] selectors in the query
    String finalQuery = replaceMatchSelectors(rawQuery, mergedSelector);
    log.info("Final Thanos series query: '{}'", finalQuery);

    return finalQuery;
  }

  /**
   * Merges series selectors with constraint selector.
   *
   * <p>For series endpoint, we need to combine the user's selector with the constraint. If the user
   * provides a bare metric name like "http_requests_total", we wrap it with constraint labels.
   */
  private String mergeSeriesSelectors(List<String> existingSelectors, String constraintSelector) {
    if (existingSelectors.isEmpty()) {
      // No existing selectors, just return the constraint selector
      return constraintSelector;
    }

    // Merge each existing selector with the constraint
    List<String> mergedSelectors = new ArrayList<>();
    for (String selector : existingSelectors) {
      String merged = mergeWithConstraint(selector, constraintSelector);
      if (!merged.isEmpty()) {
        mergedSelectors.add(merged);
      }
    }

    if (mergedSelectors.isEmpty()) {
      return constraintSelector;
    }

    // Return the first merged selector (series endpoint typically uses one match[])
    return mergedSelectors.get(0);
  }

  /**
   * Merges a single selector with the constraint selector.
   *
   * <p>Examples: - "http_requests_total" + "{namespace=~\"demo\"}" ->
   * "http_requests_total{namespace=~\"demo\"}" - "{job=\"test\"}" + "{namespace=~\"demo\"}" ->
   * "{job=\"test\",namespace=~\"demo\"}"
   */
  private String mergeWithConstraint(String selector, String constraintSelector) {
    if (selector == null || selector.trim().isEmpty()) {
      return constraintSelector;
    }

    String trimmedSelector = selector.trim();

    // Extract constraint labels (remove outer braces)
    String constraintLabels = constraintSelector;
    if (constraintLabels.startsWith("{") && constraintLabels.endsWith("}")) {
      constraintLabels = constraintLabels.substring(1, constraintLabels.length() - 1);
    }

    // Check if selector has labels
    int braceIndex = trimmedSelector.indexOf('{');
    if (braceIndex == -1) {
      // Bare metric name: "http_requests_total" -> "http_requests_total{constraint}"
      return trimmedSelector + "{" + constraintLabels + "}";
    }

    // Selector has labels: "{job=\"test\"}" or "metric{job=\"test\"}"
    // Find the closing brace and insert constraint labels before it
    int closeBraceIndex = trimmedSelector.lastIndexOf('}');
    if (closeBraceIndex == -1) {
      // Malformed selector, just append constraint
      return trimmedSelector + "{" + constraintLabels + "}";
    }

    String beforeCloseBrace = trimmedSelector.substring(0, closeBraceIndex);
    String existingLabels = beforeCloseBrace.substring(braceIndex + 1);

    if (existingLabels.trim().isEmpty()) {
      // Empty labels: "{}" -> "{constraint}"
      return beforeCloseBrace + constraintLabels + "}";
    }

    // Non-empty labels: "{job=\"test\"}" -> "{job=\"test\",constraint}"
    return beforeCloseBrace + "," + constraintLabels + "}";
  }

  /**
   * Handles Thanos label values endpoints with security constraints.
   *
   * <p>This method processes special Thanos endpoints that require custom constraint handling for
   * label values filtering with match[] selectors.
   *
   * @param rawQuery The raw query string from the request
   * @param labelConstraints The label constraints to apply
   * @param preParsedQuery Pre-parsed query parameters for optimization
   * @return The enhanced query string with security constraints
   */
  public String handleLabelValuesEndpoint(
      String rawQuery,
      Map<String, Set<String>> labelConstraints,
      ParsedQueryParameters preParsedQuery) {
    log.info("=== THANOS LABEL VALUES ENDPOINT PROCESSING ===");
    log.info("Raw query: '{}'", rawQuery);
    log.info("Label constraints: {}", labelConstraints);

    // Extract existing match[] selectors
    List<String> existingSelectors = extractMatchSelectors(preParsedQuery);
    log.info("Existing match[] selectors: {}", existingSelectors);

    // Build constraint selector
    String constraintSelector =
        labelEnforcementUtils.buildSafeConstraintQuery(QUERY_LANGUAGE, labelConstraints);
    log.info(LOG_CONSTRAINT_SELECTOR, constraintSelector);

    if (constraintSelector.isEmpty()) {
      log.info("No constraints to add, returning original query: '{}'", rawQuery);
      return rawQuery;
    }

    // Merge existing selectors with constraint selector
    String mergedSelector = enforceCombinedMatchSelector(existingSelectors, labelConstraints);
    log.info("Merged selector: '{}'", mergedSelector);

    // Replace match[] selectors in the query
    String finalQuery = replaceMatchSelectors(rawQuery, mergedSelector);
    log.info("Final Thanos label values query: '{}'", finalQuery);

    return finalQuery;
  }

  /**
   * Extracts existing match[] selectors from the query. Optimized to use only the cached
   * preParsedQuery to avoid duplicate parsing.
   */
  private static List<String> extractMatchSelectors(ParsedQueryParameters preParsedQuery) {
    List<String> selectors = new ArrayList<>();

    // Extract from pre-parsed query parameters (cached result from earlier parsing)
    if (preParsedQuery != null) {
      List<String> matchValues = preParsedQuery.parameters().get("match[]");
      if (matchValues != null && !matchValues.isEmpty()) {
        selectors.addAll(matchValues);
      }
    }

    return selectors;
  }

  /** Replaces match[] selectors in the query with a merged selector. */
  private String replaceMatchSelectors(String rawQuery, String mergedSelector) {
    // Handle null or empty rawQuery
    if (rawQuery == null || rawQuery.trim().isEmpty()) {
      String encodedSelector = URLEncoder.encode(mergedSelector, StandardCharsets.UTF_8);
      return "match[]=" + encodedSelector;
    }

    // Remove all existing match[] parameters (both encoded and unencoded)
    // match%5B%5D= (URL-encoded) and match[]= (unencoded)
    String queryWithoutMatch =
        rawQuery
            .replaceAll("&?match%5B%5D=[^&]*", "") // Remove URL-encoded match[]=
            .replaceAll("&?match\\[\\]=[^&]*", ""); // Remove unencoded match[]=

    // Clean up any leading & that might be left after removal
    queryWithoutMatch = queryWithoutMatch.replaceAll("^&+", "");

    // Add the merged selector with proper URL construction
    String encodedSelector = URLEncoder.encode(mergedSelector, StandardCharsets.UTF_8);
    String finalQuery;
    if (queryWithoutMatch.isEmpty()) {
      finalQuery = "match[]=" + encodedSelector;
    } else {
      finalQuery = queryWithoutMatch + "&match[]=" + encodedSelector;
    }

    return finalQuery;
  }

  /** Enforces combined match selector by merging existing selectors with constraints. */
  private String enforceCombinedMatchSelector(
      List<String> existingSelectors, Map<String, Set<String>> labelConstraints) {
    log.info(
        "Enforcing combined match selector with existing: {} and constraints: {}",
        existingSelectors,
        labelConstraints);

    // Build constraint selector
    String constraintSelector =
        labelEnforcementUtils.buildSafeConstraintQuery(QUERY_LANGUAGE, labelConstraints);
    log.info(LOG_CONSTRAINT_SELECTOR, constraintSelector);

    if (constraintSelector.isEmpty()) {
      // No constraints, return existing selectors
      return String.join(",", existingSelectors);
    }

    if (existingSelectors.isEmpty()) {
      // No existing selectors, return constraint selector
      return constraintSelector;
    }

    // Merge existing selectors with constraint selector
    // We need to enhance each existing selector with the constraints
    List<String> enhancedSelectors = new ArrayList<>();

    for (String existingSelector : existingSelectors) {
      // Parse the existing selector to extract labels
      String enhancedSelector = enhanceExistingSelector(existingSelector, labelConstraints);
      if (!enhancedSelector.isEmpty()) {
        enhancedSelectors.add(enhancedSelector);
      }
    }

    if (enhancedSelectors.isEmpty()) {
      // If no enhanced selectors, fall back to constraint selector
      log.info(
          "No enhanced selectors produced, using constraint selector: '{}'", constraintSelector);
      return constraintSelector;
    }

    String mergedSelector = String.join(",", enhancedSelectors);
    log.info("Combined selectors - merged result: '{}'", mergedSelector);
    return mergedSelector;
  }

  /** Enhances an existing selector by applying label constraints. */
  private String enhanceExistingSelector(
      String existingSelector, Map<String, Set<String>> labelConstraints) {
    try {
      // Use the LabelConstraintEngine to enhance the existing selector
      // This will apply the constraints while preserving the original structure
      QueryContext context =
          QueryContext.builder()
              .originalQuery(existingSelector)
              .labelConstraints(labelConstraints)
              .language(QueryContext.QueryLanguage.PROMQL)
              .build();

      EnhancementResult result = queryEnforcementFlow.enhanceQuery(context);

      if (result.isSuccess()) {
        return result.getEnhancedQuery();
      } else {
        log.warn("Failed to enhance selector '{}': {}", existingSelector, result.getErrorMessage());
        return existingSelector; // Return original if enhancement fails
      }
    } catch (Exception e) {
      log.error("Error enhancing selector '{}': {}", existingSelector, e.getMessage());
      return existingSelector; // Return original if enhancement fails
    }
  }
}
